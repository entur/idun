package org.entur.idun

import com.google.genai.Client
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.entur.idun.decide.parseRepoModes
import org.entur.idun.github.AppAuth
import org.entur.idun.github.GitHubApiException
import org.entur.idun.github.GitHubFactory
import org.entur.idun.github.KtorGitHub
import org.entur.idun.github.githubHttpClient
import org.entur.idun.github.verifySignature
import org.entur.idun.review.DailyBudget
import org.entur.idun.review.GeminiReviewer
import org.entur.idun.server.Assessor
import org.entur.idun.server.PrLocks
import org.entur.idun.server.jobFor
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_PORT = 8080
private const val DEFAULT_MODEL = "gemini-3.1-pro-preview"
private const val DEFAULT_DAILY_USD = 20.0
/** GitHub-payloadene Idun bryr seg om er små; 1 MB er god margin. */
private const val MAX_WEBHOOK_BYTES = 1024 * 1024

private const val MAX_DELIVERY_LENGTH = 64
private val DELIVERY_ID = Regex("[A-Za-z0-9-]+")

/** Under startup-proben i Cloud Run (10 × 3 s), så en treg GitHub gir redusert modus i stedet for omstart. */
private val APP_LOOKUP_TIMEOUT = 10.seconds

private val log = LoggerFactory.getLogger("org.entur.idun.Application")

/** Scopet bakgrunnsjobbene kjører i. En jobb som feiler, stopper ikke de andre. */
fun backgroundScope(): CoroutineScope =
    CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            // Siste skanse: Assessor fanger selv. Bare typen logges, aldri teksten, som kan inneholde hva som helst.
            CoroutineExceptionHandler { _, e -> log.error("idun.error errorType={}", e::class.qualifiedName) },
    )

/**
 * Avhengigheter som injiseres i [module]. Uten [webhookSecret] eller [assessor] er Idun i redusert modus:
 * `/webhook` svarer `503`, mens `/healthz` fortsatt svarer `200`.
 */
data class Dependencies(
    val webhookSecret: String? = null,
    val assessor: Assessor? = null,
    val ownAppId: Long = 0,
    val scope: CoroutineScope = backgroundScope(),
) {
    val degraded: Boolean get() = webhookSecret == null || assessor == null

    override fun toString(): String = "Dependencies(degraded=$degraded, ownAppId=$ownAppId)"

    companion object {
        fun forTest(): Dependencies = Dependencies()

        /** Starter aldri med unntak: alt som mangler eller er ugyldig gir redusert modus med `startup.degraded`. */
        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            clock: Clock = Clock.systemUTC(),
            http: HttpClient? = null,
        ): Dependencies {
            val repoModes = parseRepoModes(env["IDUN_REPO_MODES"])
            repoModes.invalidReason?.let { log.error("idun.repo_modes_invalid reason={} (alle repoer er off)", it) }

            fun degraded(reason: String): Dependencies {
                log.warn("startup.degraded reason={}", reason)
                return Dependencies()
            }

            fun secret(name: String) = env[name]?.trim()?.takeUnless { it.isEmpty() || it == "unset" }

            fun text(name: String) = env[name]?.trim()?.takeUnless { it.isEmpty() }

            fun amount(name: String) = text(name)?.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }

            val privateKey = secret("GITHUB_PRIVATE_KEY") ?: return degraded("GITHUB_PRIVATE_KEY mangler")
            val webhookSecret = secret("GITHUB_WEBHOOK_SECRET") ?: return degraded("GITHUB_WEBHOOK_SECRET mangler")
            val appId = text("GITHUB_APP_ID")?.toLongOrNull()?.takeIf { it > 0 } ?: return degraded("GITHUB_APP_ID mangler")
            val slug = text("GITHUB_APP_SLUG") ?: return degraded("GITHUB_APP_SLUG mangler")
            val project = text("GCP_PROJECT") ?: return degraded("GCP_PROJECT mangler")
            val model = text("GEMINI_MODEL") ?: DEFAULT_MODEL
            val dailyUsd =
                if (text("GEMINI_DAILY_USD") == null) {
                    DEFAULT_DAILY_USD
                } else {
                    amount("GEMINI_DAILY_USD") ?: return degraded("GEMINI_DAILY_USD er ugyldig")
                }
            // Uten priser blir kostnadstaket virkningsløst, så de er påkrevd (Terraform har ingen standardverdi).
            val priceIn = amount("GEMINI_PRICE_IN_PER_MTOK") ?: return degraded("GEMINI_PRICE_IN_PER_MTOK mangler")
            val priceOut = amount("GEMINI_PRICE_OUT_PER_MTOK") ?: return degraded("GEMINI_PRICE_OUT_PER_MTOK mangler")

            return try {
                val http = http ?: githubHttpClient()
                val auth = AppAuth(appId.toString(), privateKey, clock, http)
                val identity =
                    when (val check = runBlocking { verifyAppIdentity(auth, appId, slug) }) {
                        is AppIdentityCheck.Rejected -> return degraded(check.reason)
                        is AppIdentityCheck.Verified -> check
                    }
                // Ny klient per vurdering: installasjonstokenet varer en time og caches i AppAuth.
                val factory = GitHubFactory { installationId -> KtorGitHub(http, auth.installationToken(installationId), appId) }
                val vertex = Client.builder().vertexAI(true).project(project).location("global").build()
                val reviewer =
                    GeminiReviewer(
                        client = vertex,
                        model = model,
                        budget = DailyBudget(dailyUsd, clock),
                        priceInPerMTok = priceIn,
                        priceOutPerMTok = priceOut,
                    )
                val appLogin = identity.appLogin
                val assessor =
                    Assessor(
                        github = factory,
                        reviewer = reviewer,
                        ownAppId = identity.appId,
                        appLogin = appLogin,
                        locks = PrLocks(),
                        log = LoggerFactory.getLogger(Assessor::class.java),
                        modeOf = repoModes::modeOf,
                        model = model,
                    )
                log.info("startup.ready appId={} appLogin={} model={} {}", identity.appId, appLogin, model, repoModes)
                Dependencies(webhookSecret = webhookSecret, assessor = assessor, ownAppId = identity.appId)
            } catch (e: Exception) {
                // Bare typen: meldingen kan inneholde deler av nøkkelen.
                degraded("oppsett feilet: ${e::class.qualifiedName}")
            }
        }
    }
}

fun Application.module(deps: Dependencies) {
    routing {
        get("/healthz") {
            call.respondText("ok", status = HttpStatusCode.OK)
        }
        post("/webhook") {
            handleWebhook(call, deps)
        }
    }
}

private suspend fun handleWebhook(
    call: ApplicationCall,
    deps: Dependencies,
) {
    val secret = deps.webhookSecret
    val assessor = deps.assessor
    if (secret == null || assessor == null) {
        call.respond(HttpStatusCode.ServiceUnavailable)
        return
    }
    // Headeren er ikke signert: logg den bare når den ser ut som en GitHub-id, så den ikke kan forfalske logglinjer.
    val delivery =
        call.request
            .header("X-GitHub-Delivery")
            ?.takeIf { it.length <= MAX_DELIVERY_LENGTH && DELIVERY_ID.matches(it) }
            ?: "ugyldig"
    val event = call.request.header("X-GitHub-Event")
    // Før signaturen: en stor body skal ikke leses inn eller hashes. GitHub-payloads Idun bryr seg om er små.
    val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declared != null && declared > MAX_WEBHOOK_BYTES) {
        log.warn("webhook.too_large delivery={} bytes={}", delivery, declared)
        call.respond(HttpStatusCode.PayloadTooLarge)
        return
    }
    val body = call.receiveChannel().readBuffer(MAX_WEBHOOK_BYTES + 1L).readByteArray()
    if (body.size > MAX_WEBHOOK_BYTES) {
        log.warn("webhook.too_large delivery={}", delivery)
        call.respond(HttpStatusCode.PayloadTooLarge)
        return
    }
    if (!verifySignature(secret.toByteArray(Charsets.UTF_8), body, call.request.header("X-Hub-Signature-256"))) {
        log.warn("webhook.unauthorized delivery={}", delivery)
        call.respond(HttpStatusCode.Unauthorized)
        return
    }
    val job =
        try {
            jobFor(event, body, deps.ownAppId, assessor)
        } catch (e: IllegalArgumentException) {
            // Også SerializationException. Payloaden logges ikke.
            log.warn("webhook.malformed delivery={} event={}", delivery, event)
            call.respond(HttpStatusCode.BadRequest)
            return
        }
    log.info("webhook.received delivery={} event={} handled={}", delivery, event, job != null)
    if (job != null) deps.scope.launch { job() }
    call.respond(HttpStatusCode.Accepted)
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val deps = Dependencies.fromEnvironment()
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(deps) }.start(wait = true)
}

internal sealed interface AppIdentityCheck {
    data class Verified(
        val appId: Long,
        val appLogin: String,
    ) : AppIdentityCheck

    data class Rejected(
        val reason: String,
    ) : AppIdentityCheck
}

/**
 * Spør GitHub hvem appen er (`GET /app` med app-JWT-en), i stedet for å stole på miljøvariablene. Iduns bruker
 * (`<slug>[bot]`) avgjør hvilke reviews, kommentarer og godkjenninger Idun regner som sine egne, så en feil slug
 * ville fått Idun til å overse eller røre feil objekter. Avviker miljøet fra svaret, eller feiler kallet, er
 * resultatet [AppIdentityCheck.Rejected].
 */
internal suspend fun verifyAppIdentity(
    auth: AppAuth,
    appId: Long,
    slug: String,
): AppIdentityCheck {
    val app =
        try {
            withTimeout(APP_LOOKUP_TIMEOUT) { auth.app() }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) return AppIdentityCheck.Rejected("GET /app svarte ikke innen $APP_LOOKUP_TIMEOUT")
            throw e
        } catch (e: Exception) {
            // Bare typen og statusen: meldingen kan inneholde hva som helst fra svaret.
            return AppIdentityCheck.Rejected(
                "GET /app feilet: ${e::class.qualifiedName}${(e as? GitHubApiException)?.status?.let { " $it" }.orEmpty()}",
            )
        }
    return when {
        app.id != appId -> AppIdentityCheck.Rejected("GITHUB_APP_ID ($appId) stemmer ikke med GET /app (${app.id})")
        app.slug != slug -> AppIdentityCheck.Rejected("GITHUB_APP_SLUG ($slug) stemmer ikke med GET /app (${app.slug})")
        else -> AppIdentityCheck.Verified(app.id, "${app.slug}[bot]")
    }
}
