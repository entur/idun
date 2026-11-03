package org.entur.idun.eval

import com.google.genai.Client
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.entur.idun.config.IdunConfig
import org.entur.idun.decide.Outcome
import org.entur.idun.decide.decideAfterReview
import org.entur.idun.decide.externalId
import org.entur.idun.review.BehaviourReviewer
import org.entur.idun.review.DailyBudget
import org.entur.idun.review.GeminiReviewer
import org.entur.idun.review.ReviewFile
import org.entur.idun.review.ReviewInput
import org.entur.idun.review.ReviewResult
import org.entur.idun.rules.ReasonCode
import org.entur.idun.rules.evaluateFiles

private const val DEFAULT_MODEL = "gemini-3.1-pro-preview"

/** Taket for én eval-kjøring i USD. Evalen er liten, så taket er bare et sikkerhetsnett. */
private const val EVAL_BUDGET_USD = 20.0

private data class Scored(
    val canary: Canary,
    val outcome: Outcome,
    val costUsd: Double,
    /** `true` når modellen ikke ga et brukbart svar. Det teller som feil selv om utfallet tilfeldigvis er `needs-human`. */
    val reviewFailed: Boolean,
    /** Fra [ruleMismatches]: reglene eller linjetallene stemmer ikke med forventningen. */
    val ruleProblems: List<String> = emptyList(),
) {
    val actual: String = outcome.externalId() ?: "unknown"

    val ok: Boolean
        get() =
            when {
                reviewFailed || ruleProblems.isNotEmpty() -> false
                canary.expected == Expected.ANY -> true
                canary.expected == Expected.SAFE -> actual == "safe"
                else -> actual == "needs-human"
            }
}

/**
 * Kjører kanarifuglene i `eval/canaries` gjennom reglene og, hvis de slipper gjennom, gjennom Gemini.
 * Kanarifugler med `source` i `meta.json` hentes først fra PR-en med `gh` (se [GhCanaryFetcher]).
 * Gir exit-kode 1 hvis en kanarifugl med forventet utfall (`safe`/`needs-human`) får et annet utfall, hvis
 * reglene eller linjetallene ikke stemmer med `meta.json`, hvis modellen ikke svarte, eller hvis en kanarifugl
 * ikke kunne hentes (den rapporteres da som SKIPPED, aldri som bestått). Koster penger: kjør den med `./gradlew eval`.
 */
fun main() {
    val project =
        System.getenv("GCP_PROJECT")?.takeIf { it.isNotBlank() }
            ?: failFast("GCP_PROJECT må være satt, for eksempel: GCP_PROJECT=ent-idun-prd ./gradlew eval")
    val model = System.getenv("GEMINI_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    val priceIn = price("GEMINI_PRICE_IN_PER_MTOK")
    val priceOut = price("GEMINI_PRICE_OUT_PER_MTOK")

    val client = Client.builder().vertexAI(true).project(project).location("global").build()
    val reviewer =
        GeminiReviewer(
            client = client,
            model = model,
            budget = DailyBudget(EVAL_BUDGET_USD, Clock.systemUTC()),
            priceInPerMTok = priceIn,
            priceOutPerMTok = priceOut,
        )

    println("Modell: $model, prosjekt: $project")
    if (priceIn == 0.0 && priceOut == 0.0) {
        println("Merk: GEMINI_PRICE_IN_PER_MTOK/GEMINI_PRICE_OUT_PER_MTOK er ikke satt, så kostnaden vises som 0.")
    }
    // Hentede filer ligger bare her mens evalen kjører.
    val workDir = Files.createTempDirectory("idun-canaries-")
    val exitCode =
        try {
            runEval(reviewer, Path.of("eval/canaries"), GhCanaryFetcher(), workDir)
        } finally {
            workDir.toFile().deleteRecursively()
        }
    exitProcess(exitCode)
}

/** Evalen uten oppsettet rundt. Gir exit-koden: 0 når alt er som forventet, 1 ved feil eller SKIPPED, 2 uten kanarifugler. */
internal fun runEval(
    reviewer: BehaviourReviewer,
    root: Path,
    fetcher: CanaryFetcher,
    workDir: Path,
): Int {
    val loaded = loadCanaries(root, fetcher, workDir)
    if (loaded.isEmpty()) {
        System.err.println("Fant ingen kanarifugler i $root (kjør fra rotmappen)")
        return 2
    }
    val canaries = loaded.filterIsInstance<LoadedCanary.Ready>().map { it.canary }
    val skipped = loaded.filterIsInstance<LoadedCanary.Skipped>()

    println("${canaries.size} kanarifugler, ${skipped.size} hoppet over")
    val scored = runBlocking { canaries.map { score(it, reviewer) } }

    printTable(scored, skipped)
    val failed = scored.filter { !it.ok }
    println()
    println("Total kostnad: ${"%.4f".format(scored.sumOf { it.costUsd })} USD")
    if (skipped.isNotEmpty()) {
        println("SKIPPED (ikke bestått): ${skipped.joinToString { "${it.name} (${it.reason})" }}")
        println("Hentingen bruker gh: kjør `gh auth login` med tilgang til kilderepoet.")
    }
    if (failed.isNotEmpty()) println("FEIL: ${failed.joinToString { it.canary.name }}")
    if (failed.isNotEmpty() || skipped.isNotEmpty()) return 1
    println("OK: alle kanarifuglene ble fanget som forventet.")
    return 0
}

private suspend fun score(
    canary: Canary,
    reviewer: BehaviourReviewer,
): Scored {
    val ruleProblems = ruleMismatches(canary)
    val rules = evaluateFiles(canary.files, canary.contents, IdunConfig.DEFAULT)
    if (!rules.eligible) {
        // Reglene stopper den før modellen, akkurat som i produksjon.
        return Scored(canary, Outcome.NeedsHuman(rules.reasons), 0.0, reviewFailed = false, ruleProblems)
    }

    val input =
        ReviewInput(
            title = canary.title,
            files =
                rules.sourceFiles.map {
                    val contents = canary.contents.getValue(it.path)
                    ReviewFile(it.path, it.patch.orEmpty(), contents.before, contents.after)
                },
        )
    val result = reviewer.review(input)
    val outcome = decideAfterReview(result, input)
    val cost = (result as? ReviewResult.Answered)?.costUsd ?: 0.0
    val failed = outcome is Outcome.NeedsHuman && outcome.reasons.any { it.code in MODEL_FAILURES }
    return Scored(canary, outcome, cost, failed, ruleProblems)
}

private val MODEL_FAILURES = setOf(ReasonCode.REVIEW_FAILED, ReasonCode.BUDGET_SPENT)

private fun detail(scored: Scored): String {
    val text =
        when (val outcome = scored.outcome) {
            is Outcome.NeedsHuman ->
                outcome.reasons.joinToString("; ") { "${it.code}: ${it.detail}" } +
                    outcome.review?.let { " | modell: ${it.verdict} - ${it.summary}" }.orEmpty()
            is Outcome.Safe -> "modell: ${outcome.review.verdict} - ${outcome.review.summary}"
            else -> outcome.toString()
        }
    return text + scored.ruleProblems.joinToString("") { " | REGLER: $it" }
}

private fun printTable(
    scored: List<Scored>,
    skipped: List<LoadedCanary.Skipped>,
) {
    val names = scored.map { it.canary.name } + skipped.map { it.name }
    val nameWidth = maxOf("Kanarifugl".length, names.maxOfOrNull { it.length } ?: 0)
    val row = "%-${nameWidth}s  %-11s  %-11s  %-7s  %10s"
    println()
    println(row.format("Kanarifugl", "Forventet", "Faktisk", "OK", "Kostnad USD"))
    scored.forEach {
        println(row.format(it.canary.name, it.canary.expected.name.lowercase().replace('_', '-'), it.actual, if (it.ok) "ja" else "NEI", "%.4f".format(it.costUsd)))
    }
    skipped.forEach { println(row.format(it.name, "-", "-", "SKIPPED", "-")) }
    println()
    scored.forEach { println("- ${it.canary.name}: ${detail(it)}") }
    skipped.forEach { println("- ${it.name}: SKIPPED: ${it.reason}") }
}

private fun price(name: String): Double =
    System.getenv(name)?.takeIf { it.isNotBlank() }?.let {
        it.toDoubleOrNull() ?: failFast("$name må være et tall (USD per million tokens), var '$it'")
    } ?: 0.0

private fun failFast(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}
