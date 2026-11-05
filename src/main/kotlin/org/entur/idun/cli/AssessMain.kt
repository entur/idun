package org.entur.idun.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.google.genai.Client
import kotlinx.coroutines.runBlocking
import org.entur.idun.decide.Mode
import org.entur.idun.github.DryRunGitHub
import org.entur.idun.github.GitHub
import org.entur.idun.github.GitHubFactory
import org.entur.idun.github.KtorGitHub
import org.entur.idun.github.RepoRef
import org.entur.idun.github.githubHttpClient
import org.entur.idun.review.BehaviourReviewer
import org.entur.idun.review.DailyBudget
import org.entur.idun.review.GeminiReviewer
import org.entur.idun.server.Assessor
import org.entur.idun.server.PrLocks
import java.time.Clock
import kotlin.system.exitProcess

private const val DEFAULT_MODEL = "gemini-3.1-pro-preview"
private const val DRY_RUN_BUDGET_USD = 5.0
private const val USAGE = "Bruk: assess <owner/repo> <pr-nummer> [--mode approve|advise]"

internal class UsageException(
    message: String,
) : Exception(message)

internal data class AssessArgs(
    val repo: RepoRef,
    val number: Int,
    val mode: Mode,
)

internal fun parseArgs(args: Array<String>): AssessArgs {
    val positional = mutableListOf<String>()
    var mode = Mode.APPROVE
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--mode" -> {
                val value = args.getOrNull(i + 1) ?: throw UsageException("--mode trenger en verdi (approve|advise). $USAGE")
                mode =
                    when (value) {
                        "approve" -> Mode.APPROVE
                        "advise" -> Mode.ADVISE
                        else -> throw UsageException("Ugyldig modus '$value', bruk approve|advise. $USAGE")
                    }
                i++
            }
            arg.startsWith("-") -> throw UsageException("Ukjent flagg $arg. $USAGE")
            else -> positional += arg
        }
        i++
    }
    if (positional.size != 2) throw UsageException(USAGE)
    val parts = positional[0].split("/")
    if (parts.size != 2 || parts.any { it.isBlank() }) {
        throw UsageException("Ugyldig repo '${positional[0]}', forventet owner/repo. $USAGE")
    }
    val number =
        positional[1].toIntOrNull()?.takeIf { it > 0 }
            ?: throw UsageException("Ugyldig PR-nummer '${positional[1]}', forventet et positivt heltall. $USAGE")
    return AssessArgs(RepoRef(parts[0], parts[1]), number, mode)
}

/** Fanger Iduns egen feillogging (`idun.error`) og skriver advarsler og feil til [sink]. */
private class ErrorCapture(
    private val sink: (String) -> Unit,
) : AppenderBase<ILoggingEvent>() {
    var failed = false
        private set

    override fun append(event: ILoggingEvent) {
        if (event.level.isGreaterOrEqual(Level.WARN)) {
            if (event.level == Level.ERROR) failed = true
            val cause = event.throwableProxy?.let { " (${it.className}: ${it.message})" }.orEmpty()
            sink(event.formattedMessage + cause)
        }
    }
}

/**
 * Selve tørrkjøringen, uten oppsettet rundt. Gir exit-koden: 0 når vurderingen gikk gjennom, 1 når Assessor
 * endte i feilstien (`idun.error`).
 */
internal suspend fun runDryRun(
    github: GitHub,
    reviewer: BehaviourReviewer,
    args: AssessArgs,
    model: String,
    out: (String) -> Unit,
    err: (String) -> Unit,
): Int {
    val dry = DryRunGitHub(github, out)
    val capture = ErrorCapture(err)
    val context = LoggerContext()
    capture.context = context
    capture.start()
    val logger = context.getLogger("idun.dry-run").apply { addAppender(capture) }
    val assessor =
        Assessor(
            github = GitHubFactory { dry },
            reviewer = reviewer,
            ownAppId = 0L,
            appLogin = "idun-dry-run[bot]",
            locks = PrLocks(),
            log = logger,
            modeOf = { args.mode },
            model = model,
        )
    assessor.onPullRequest(installationId = 0L, repo = args.repo, number = args.number, action = "synchronize")

    out("")
    out("Tørrkjøring ferdig for ${args.repo.owner}/${args.repo.name}#${args.number} (modus ${args.mode.name.lowercase()}).")
    out("Ville skrevet ${dry.writes.size} ting til GitHub. Ingenting er skrevet.")
    out(
        if (dry.wouldApprove) {
            "Idun ville godkjenne denne PR-en."
        } else {
            "Idun ville ikke godkjenne denne PR-en."
        },
    )
    out("Begrunnelsen står i check- og kommentar-linjene over.")
    if (capture.failed) {
        err("Vurderingen endte i Iduns feilsti (se idun.error over).")
        return 1
    }
    return 0
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}

private fun githubToken(): String {
    System.getenv("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val fromGh =
        try {
            val process = ProcessBuilder(listOf("gh", "auth", "token")).redirectErrorStream(false).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && text.isNotEmpty()) text else null
        } catch (e: java.io.IOException) {
            null
        }
    return fromGh ?: fail("Fant ingen GitHub-token. Sett GITHUB_TOKEN, eller kjør `gh auth login`.")
}

private fun price(name: String): Double =
    System.getenv(name)?.takeIf { it.isNotBlank() }?.let {
        it.toDoubleOrNull()?.takeIf { v -> v >= 0 && v.isFinite() }
            ?: fail("$name må være et tall (USD per million tokens), var '$it'")
    } ?: 0.0

/** Tørrkjøring: vurderer én ekte PR fra laptopen uten å skrive noe til GitHub. Se README. */
fun main(args: Array<String>) {
    val parsed =
        try {
            parseArgs(args)
        } catch (e: UsageException) {
            fail(e.message.orEmpty())
        }
    val project =
        System.getenv("GCP_PROJECT")?.takeIf { it.isNotBlank() }
            ?: fail("GCP_PROJECT må være satt, for eksempel: GCP_PROJECT=<prosjekt> ./gradlew assess --args=\"entur/idun-sandbox 12\"")
    val model = System.getenv("GEMINI_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    val priceIn = price("GEMINI_PRICE_IN_PER_MTOK")
    val priceOut = price("GEMINI_PRICE_OUT_PER_MTOK")
    val token = githubToken()

    val vertex = Client.builder().vertexAI(true).project(project).location("global").build()
    val reviewer =
        GeminiReviewer(
            client = vertex,
            model = model,
            budget = DailyBudget(DRY_RUN_BUDGET_USD, Clock.systemUTC()),
            priceInPerMTok = priceIn,
            priceOutPerMTok = priceOut,
        )
    println("Modell: $model, prosjekt: $project, modus: ${parsed.mode.name.lowercase()}")
    if (priceIn == 0.0 && priceOut == 0.0) {
        println("Merk: GEMINI_PRICE_IN_PER_MTOK/GEMINI_PRICE_OUT_PER_MTOK er ikke satt, så kostnaden er ukjent.")
    }
    // App-id 0: ingenting regnes som Iduns eget, så hver kjøring er en ny vurdering.
    val github = KtorGitHub(githubHttpClient(), token, appId = 0L)
    val code =
        runBlocking {
            runDryRun(github, reviewer, parsed, model, ::println) { System.err.println(it) }
        }
    exitProcess(code)
}
