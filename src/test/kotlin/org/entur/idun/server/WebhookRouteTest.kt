package org.entur.idun.server

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import org.entur.idun.Dependencies
import org.entur.idun.decide.Mode
import org.entur.idun.module
import org.junit.jupiter.api.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookRouteTest {
    private val secret = "s3cret"
    private val gh = FakeGitHub()
    private val factory = CountingFactory(gh)
    private val reviewer = FakeReviewer()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val assessor =
        Assessor(factory, reviewer, OWN_APP_ID, APP_LOGIN, PrLocks(), CapturedLog().logger, modeOf = { Mode.ADVISE }, model = "gemini-test")
    private val deps = Dependencies(webhookSecret = secret, assessor = assessor, ownAppId = OWN_APP_ID, scope = scope)

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun ApplicationTestBuilder.deliver(
        event: String,
        body: String,
        signature: String? = sign(body),
        delivery: String = "d-1",
    ) = client.post("/webhook") {
        header("X-GitHub-Event", event)
        header("X-GitHub-Delivery", delivery)
        signature?.let { header("X-Hub-Signature-256", it) }
        setBody(body)
    }

    private suspend fun awaitBackground() = job.children.toList().joinAll()

    private val repository = """"repository":{"name":"idun-sandbox","owner":{"login":"entur"}},"installation":{"id":99}"""

    @Test
    fun `ugyldig signatur gir 401`() =
        testApplication {
            application { module(deps) }
            val body = """{"action":"opened","number":7,$repository}"""
            deliver("pull_request", body, signature = "sha256=" + "00".repeat(32)).status shouldBe HttpStatusCode.Unauthorized
            deliver("pull_request", body, signature = null).status shouldBe HttpStatusCode.Unauthorized
            awaitBackground()
            factory.calls shouldBe 0
        }

    @Test
    fun `gyldig ping gir 202`() =
        testApplication {
            application { module(deps) }
            deliver("ping", """{"zen":"Keep it logically awesome."}""").status shouldBe HttpStatusCode.Accepted
        }

    @Test
    fun `redusert modus gir 503 og healthz 200`() =
        testApplication {
            application { module(Dependencies.forTest()) }
            deliver("ping", "{}").status shouldBe HttpStatusCode.ServiceUnavailable
            client.get("/healthz").status shouldBe HttpStatusCode.OK
        }

    @Test
    fun `signert pull_request starter vurdering i bakgrunnen`() =
        testApplication {
            application { module(deps) }
            deliver("pull_request", """{"action":"opened","number":7,$repository}""").status shouldBe HttpStatusCode.Accepted
            awaitBackground()
            reviewer.calls shouldBe 1
            gh.ownRuns.getValue(HEAD).externalId!!.startsWith("safe:") shouldBe true
        }

    @Test
    fun `pull_request med en handling Idun ikke bryr seg om gir 202 uten vurdering`() =
        testApplication {
            application { module(deps) }
            deliver("pull_request", """{"action":"labeled","number":7,$repository}""").status shouldBe HttpStatusCode.Accepted
            awaitBackground()
            factory.calls shouldBe 0
        }

    @Test
    fun `check_suite fra egen app ignoreres`() =
        testApplication {
            application { module(deps) }
            gh.prsForCommit[HEAD] = listOf(7)
            val body = """{"action":"completed","check_suite":{"head_sha":"$HEAD","app":{"id":$OWN_APP_ID}},$repository}"""
            deliver("check_suite", body).status shouldBe HttpStatusCode.Accepted
            awaitBackground()
            factory.calls shouldBe 0
        }

    @Test
    fun `check_suite completed fra CI vurderer PR-ene til commiten`() =
        testApplication {
            application { module(deps) }
            gh.prsForCommit[HEAD] = listOf(7)
            val body = """{"action":"completed","check_suite":{"head_sha":"$HEAD","app":{"id":15368}},$repository}"""
            deliver("check_suite", body).status shouldBe HttpStatusCode.Accepted
            awaitBackground()
            reviewer.calls shouldBe 1
        }

    @Test
    fun `status vurderer PR-ene til commiten`() =
        testApplication {
            application { module(deps) }
            gh.prsForCommit[HEAD] = listOf(7)
            deliver("status", """{"sha":"$HEAD","state":"success",$repository}""").status shouldBe HttpStatusCode.Accepted
            awaitBackground()
            reviewer.calls shouldBe 1
        }

    @Test
    fun `en jobb som feiler stopper ikke bakgrunnsscopet`() =
        testApplication {
            application { module(deps) }
            gh.onCall = { if (it == "pullRequestsForCommit") error("boom") }
            deliver("status", """{"sha":"$HEAD","state":"success",$repository}""").status shouldBe HttpStatusCode.Accepted
            awaitBackground()
            gh.onCall = {}
            deliver("pull_request", """{"action":"opened","number":7,$repository}""").status shouldBe HttpStatusCode.Accepted
            awaitBackground()
            reviewer.calls shouldBe 1
            (job as Job).isActive shouldBe true
        }

    @Test
    fun `for stor body gir 413 uten å verifisere signaturen`() =
        testApplication {
            application { module(deps) }
            val body = "x".repeat(1024 * 1024 + 1)
            deliver("pull_request", body).status shouldBe HttpStatusCode.PayloadTooLarge
            awaitBackground()
            factory.calls shouldBe 0
        }

    @Test
    fun `signert men ugyldig payload gir 400`() =
        testApplication {
            application { module(deps) }
            deliver("pull_request", """{"action":"opened"}""").status shouldBe HttpStatusCode.BadRequest
            awaitBackground()
            gh.writes.shouldBeEmpty()
        }

    @Test
    fun `X-GitHub-Delivery logges bare når den er kort og bare har trygge tegn`() =
        testApplication {
            application { module(deps) }
            val logger = org.slf4j.LoggerFactory.getLogger("org.entur.idun.Application") as ch.qos.logback.classic.Logger
            val appender =
                ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
                    context = logger.loggerContext
                    start()
                }
            logger.addAppender(appender)
            try {
                val ping = """{"zen":"x"}"""
                deliver("ping", ping, delivery = "72d3162e-cc78-11e3-81ab-4c9367dc0958")
                deliver("ping", ping, delivery = "a".repeat(65))
                deliver("ping", ping, delivery = "x delivery=forfalsket")
                val received = appender.list.map { it.formattedMessage }.filter { it.startsWith("webhook.received") }
                received[0] shouldStartWith "webhook.received delivery=72d3162e-cc78-11e3-81ab-4c9367dc0958 "
                received[1] shouldStartWith "webhook.received delivery=ugyldig "
                received[2] shouldStartWith "webhook.received delivery=ugyldig "
                received.none { "forfalsket" in it || "a".repeat(65) in it } shouldBe true
            } finally {
                logger.detachAppender(appender)
            }
        }
}
