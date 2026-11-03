package org.entur.idun.eval

import com.google.genai.Client
import java.nio.file.Path
import java.time.Clock
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.entur.idun.config.IdunConfig
import org.entur.idun.decide.Outcome
import org.entur.idun.decide.decideAfterReview
import org.entur.idun.decide.externalId
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
) {
    val actual: String = outcome.externalId() ?: "unknown"

    val ok: Boolean
        get() =
            when {
                reviewFailed -> false
                canary.expected == Expected.ANY -> true
                canary.expected == Expected.SAFE -> actual == "safe"
                else -> actual == "needs-human"
            }
}

/**
 * Kjører kanarifuglene i `eval/canaries` gjennom reglene og, hvis de slipper gjennom, gjennom Gemini.
 * Gir exit-kode 1 hvis en kanarifugl med forventet utfall (`safe`/`needs-human`) får et annet utfall,
 * eller hvis modellen ikke svarte. Koster penger: kjør den med `./gradlew eval`.
 */
fun main() {
    val project =
        System.getenv("GCP_PROJECT")?.takeIf { it.isNotBlank() }
            ?: failFast("GCP_PROJECT må være satt, for eksempel: GCP_PROJECT=ent-idun-dev ./gradlew eval")
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

    val canaries = loadCanaries(Path.of("eval/canaries"))
    if (canaries.isEmpty()) failFast("Fant ingen kanarifugler i eval/canaries (kjør fra rotmappen)")

    println("Modell: $model, prosjekt: $project, ${canaries.size} kanarifugler")
    val scored = runBlocking { canaries.map { score(it, reviewer) } }

    printTable(scored)
    val failed = scored.filter { !it.ok }
    println()
    println("Total kostnad: ${"%.4f".format(scored.sumOf { it.costUsd })} USD")
    if (priceIn == 0.0 && priceOut == 0.0) {
        println("Merk: GEMINI_PRICE_IN_PER_MTOK/GEMINI_PRICE_OUT_PER_MTOK er ikke satt, så kostnaden vises som 0.")
    }
    if (failed.isEmpty()) {
        println("OK: alle kanarifuglene ble fanget som forventet.")
    } else {
        println("FEIL: ${failed.joinToString { it.canary.name }}")
        exitProcess(1)
    }
}

private suspend fun score(
    canary: Canary,
    reviewer: GeminiReviewer,
): Scored {
    val rules = evaluateFiles(canary.files, canary.contents, IdunConfig.DEFAULT)
    if (!rules.eligible) {
        // Reglene stopper den før modellen, akkurat som i produksjon.
        return Scored(canary, Outcome.NeedsHuman(rules.reasons), 0.0, reviewFailed = false)
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
    return Scored(canary, outcome, cost, failed)
}

private val MODEL_FAILURES = setOf(ReasonCode.REVIEW_FAILED, ReasonCode.BUDGET_SPENT)

private fun detail(scored: Scored): String =
    when (val outcome = scored.outcome) {
        is Outcome.NeedsHuman ->
            outcome.reasons.joinToString("; ") { "${it.code}: ${it.detail}" } +
                outcome.review?.let { " | modell: ${it.verdict} - ${it.summary}" }.orEmpty()
        is Outcome.Safe -> "modell: ${outcome.review.verdict} - ${outcome.review.summary}"
        else -> outcome.toString()
    }

private fun printTable(scored: List<Scored>) {
    val nameWidth = maxOf("Kanarifugl".length, scored.maxOf { it.canary.name.length })
    val row = "%-${nameWidth}s  %-11s  %-11s  %-4s  %10s"
    println()
    println(row.format("Kanarifugl", "Forventet", "Faktisk", "OK", "Kostnad USD"))
    scored.forEach {
        println(row.format(it.canary.name, it.canary.expected.name.lowercase().replace('_', '-'), it.actual, if (it.ok) "ja" else "NEI", "%.4f".format(it.costUsd)))
    }
    println()
    scored.forEach { println("- ${it.canary.name}: ${detail(it)}") }
}

private fun price(name: String): Double =
    System.getenv(name)?.takeIf { it.isNotBlank() }?.let {
        it.toDoubleOrNull() ?: failFast("$name må være et tall (USD per million tokens), var '$it'")
    } ?: 0.0

private fun failFast(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}
