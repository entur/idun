package org.entur.idun.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.google.genai.Client
import kotlinx.coroutines.runBlocking
import org.entur.idun.ci.GITHUB_ACTIONS_APP_ID
import org.entur.idun.ci.GITHUB_ACTIONS_LOGIN
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
import org.slf4j.Logger
import java.time.Clock
import kotlin.system.exitProcess

private const val DEFAULT_MODEL = "gemini-3.1-pro-preview"
private const val DRY_RUN_BUDGET_USD = 5.0
private const val USAGE = "Bruk: assess <owner/repo> <pr-nummer> [--mode approve|advise] [--write]"

internal class UsageException(
    message: String,
) : Exception(message)

internal data class AssessArgs(
    val repo: RepoRef,
    val number: Int,
    val mode: Mode,
    /** Skriv til GitHub på ekte, som GitHub Actions (`github-actions[bot]`), i stedet for å tørrkjøre. */
    val write: Boolean = false,
)

internal fun parseArgs(args: Array<String>): AssessArgs {
    val positional = mutableListOf<String>()
    var mode: Mode? = null
    var write = false
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
            arg == "--write" -> write = true
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
    // Med --write må godkjenning bes om eksplisitt; tørrkjøringen viser som standard om Idun ville godkjent.
    return AssessArgs(RepoRef(parts[0], parts[1]), number, mode ?: if (write) Mode.ADVISE else Mode.APPROVE, write)
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

/** Logger som fanger Iduns egen feillogging for én kjøring. */
private class Capture(
    err: (String) -> Unit,
    name: String,
) {
    private val appender = ErrorCapture(err)
    val logger: Logger

    init {
        val context = LoggerContext()
        appender.context = context
        appender.start()
        logger = context.getLogger(name).apply { addAppender(appender) }
    }

    val failed: Boolean get() = appender.failed
}

private suspend fun assessOnce(
    github: GitHub,
    reviewer: BehaviourReviewer,
    args: AssessArgs,
    model: String,
    ownAppId: Long,
    appLogin: String,
    log: Logger,
) {
    val assessor =
        Assessor(
            github = GitHubFactory { github },
            reviewer = reviewer,
            ownAppId = ownAppId,
            appLogin = appLogin,
            locks = PrLocks(),
            log = log,
            modeOf = { args.mode },
            model = model,
        )
    assessor.onPullRequest(installationId = 0L, repo = args.repo, number = args.number, action = "synchronize")
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
    val capture = Capture(err, "idun.dry-run")
    assessOnce(dry, reviewer, args, model, ownAppId = 0L, appLogin = "idun-dry-run[bot]", log = capture.logger)

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
    return exitCode(capture, err)
}

/**
 * Skrivemodus (`--write`): vurderer PR-en og skriver check, kommentar, label og eventuelt godkjenning til GitHub på
 * ekte, som GitHub Actions. Iduns identitet er da app-id [GITHUB_ACTIONS_APP_ID] og [GITHUB_ACTIONS_LOGIN], de samme
 * som repoets egen CI har, så egen check kjennes igjen på app-id og navn (se `evaluateCi`). Samme exit-koder som
 * [runDryRun].
 */
internal suspend fun runWrite(
    github: GitHub,
    reviewer: BehaviourReviewer,
    args: AssessArgs,
    model: String,
    out: (String) -> Unit,
    err: (String) -> Unit,
): Int {
    val capture = Capture(err, "idun.write")
    var approved = false
    val tracking =
        object : GitHub by github {
            override suspend fun approve(
                repo: RepoRef,
                number: Int,
                sha: String,
                body: String,
            ) {
                github.approve(repo, number, sha, body)
                approved = true
            }
        }
    assessOnce(tracking, reviewer, args, model, GITHUB_ACTIONS_APP_ID, GITHUB_ACTIONS_LOGIN, capture.logger)

    out("Vurdering skrevet til GitHub for ${args.repo.owner}/${args.repo.name}#${args.number} (modus ${args.mode.name.lowercase()}).")
    out(if (approved) "Idun har godkjent denne PR-en." else "Idun ga ingen ny godkjenning i denne kjøringen.")
    return exitCode(capture, err)
}

private fun exitCode(
    capture: Capture,
    err: (String) -> Unit,
): Int {
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

/**
 * GitHub-tokenet. Tørrkjøringen tar `GITHUB_TOKEN`, ellers `gh auth token`. Med `--write` kreves `GITHUB_TOKEN`
 * (workflowens token): det faller aldri tilbake til `gh auth token`, så ingen skriver som seg selv ved et uhell.
 */
internal fun githubToken(
    write: Boolean,
    env: (String) -> String? = System::getenv,
    ghAuthToken: () -> String? = ::ghAuthToken,
): String {
    env("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (write) {
        throw UsageException(
            "--write krever GITHUB_TOKEN (workflowens token). Det hentes ikke fra `gh auth token`, så ingen skriver som seg selv.",
        )
    }
    return ghAuthToken() ?: throw UsageException("Fant ingen GitHub-token. Sett GITHUB_TOKEN, eller kjør `gh auth login`.")
}

private fun ghAuthToken(): String? =
    try {
        val process = ProcessBuilder(listOf("gh", "auth", "token")).redirectErrorStream(false).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && text.isNotEmpty()) text else null
    } catch (e: java.io.IOException) {
        null
    }

private fun price(name: String): Double =
    System.getenv(name)?.takeIf { it.isNotBlank() }?.let {
        it.toDoubleOrNull()?.takeIf { v -> v >= 0 && v.isFinite() }
            ?: fail("$name må være et tall (USD per million tokens), var '$it'")
    } ?: 0.0

/**
 * Vurderer én ekte PR. Uten `--write` er det en tørrkjøring fra laptopen som ikke skriver noe til GitHub. Med
 * `--write` skrives resultatet på ekte som GitHub Actions, fra en workflow i repoet. Se README.
 */
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
    val token =
        try {
            githubToken(parsed.write)
        } catch (e: UsageException) {
            fail(e.message.orEmpty())
        }

    val vertex = Client.builder().vertexAI(true).project(project).location("global").build()
    val reviewer =
        GeminiReviewer(
            client = vertex,
            model = model,
            budget = DailyBudget(DRY_RUN_BUDGET_USD, Clock.systemUTC()),
            priceInPerMTok = priceIn,
            priceOutPerMTok = priceOut,
        )
    val kind = if (parsed.write) "skriver til GitHub som $GITHUB_ACTIONS_LOGIN" else "tørrkjøring"
    println("Modell: $model, prosjekt: $project, modus: ${parsed.mode.name.lowercase()}, $kind")
    if (priceIn == 0.0 && priceOut == 0.0) {
        println("Merk: GEMINI_PRICE_IN_PER_MTOK/GEMINI_PRICE_OUT_PER_MTOK er ikke satt, så kostnaden er ukjent.")
    }
    val code =
        if (parsed.write) {
            // Som GitHub Actions: upsertCheckRun oppdaterer bare idun/refactor fra app 15368.
            val github = KtorGitHub(githubHttpClient(), token, appId = GITHUB_ACTIONS_APP_ID)
            runBlocking { runWrite(github, reviewer, parsed, model, ::println) { System.err.println(it) } }
        } else {
            // App-id 0: ingenting regnes som Iduns eget, så hver kjøring er en ny vurdering.
            val github = KtorGitHub(githubHttpClient(), token, appId = 0L)
            runBlocking { runDryRun(github, reviewer, parsed, model, ::println) { System.err.println(it) } }
        }
    exitProcess(code)
}
