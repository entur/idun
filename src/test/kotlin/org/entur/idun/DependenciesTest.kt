package org.entur.idun

import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.entur.idun.github.AppAuth
import org.entur.idun.github.githubHttpClient
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.time.Clock

class DependenciesTest {
    private val pem: String =
        StringWriter()
            .also { w ->
                JcaPEMWriter(w).use {
                    it.writeObject(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private)
                }
            }.toString()

    private val complete =
        mapOf(
            "GITHUB_APP_ID" to "123",
            "GITHUB_APP_SLUG" to "entur-idun",
            "GITHUB_PRIVATE_KEY" to pem,
            "GITHUB_WEBHOOK_SECRET" to "s3cret",
            "GCP_PROJECT" to "ent-idun-prd",
            "GEMINI_PRICE_IN_PER_MTOK" to "2",
            "GEMINI_PRICE_OUT_PER_MTOK" to "12",
        )

    private fun degradedWith(change: Map<String, String?>): Boolean {
        val env = (complete + change).filterValues { it != null }.mapValues { it.value!! }
        return Dependencies.fromEnvironment(env).degraded
    }

    @Test
    fun `manglende, tom eller unset nøkkel gir redusert modus`() {
        degradedWith(mapOf("GITHUB_PRIVATE_KEY" to null)) shouldBe true
        degradedWith(mapOf("GITHUB_PRIVATE_KEY" to "")) shouldBe true
        degradedWith(mapOf("GITHUB_PRIVATE_KEY" to "unset")) shouldBe true
    }

    @Test
    fun `manglende, tom eller unset webhook-hemmelighet gir redusert modus`() {
        degradedWith(mapOf("GITHUB_WEBHOOK_SECRET" to null)) shouldBe true
        degradedWith(mapOf("GITHUB_WEBHOOK_SECRET" to "  ")) shouldBe true
        degradedWith(mapOf("GITHUB_WEBHOOK_SECRET" to "unset\n")) shouldBe true
    }

    @Test
    fun `tom eller ugyldig app-id eller slug gir redusert modus`() {
        degradedWith(mapOf("GITHUB_APP_ID" to "")) shouldBe true
        degradedWith(mapOf("GITHUB_APP_ID" to "abc")) shouldBe true
        degradedWith(mapOf("GITHUB_APP_SLUG" to "")) shouldBe true
    }

    @Test
    fun `ugyldig PEM gir redusert modus uten å kaste`() {
        degradedWith(mapOf("GITHUB_PRIVATE_KEY" to "-----BEGIN RSA PRIVATE KEY-----\nsøppel\n-----END RSA PRIVATE KEY-----")) shouldBe true
    }

    @Test
    fun `manglende eller ugyldige priser gir redusert modus`() {
        degradedWith(mapOf("GEMINI_PRICE_IN_PER_MTOK" to null)) shouldBe true
        degradedWith(mapOf("GEMINI_PRICE_OUT_PER_MTOK" to "tolv")) shouldBe true
        degradedWith(mapOf("GEMINI_DAILY_USD" to "-1")) shouldBe true
    }

    @Test
    fun `forTest er redusert`() {
        Dependencies.forTest().degraded shouldBe true
    }

    @Test
    fun `toString viser ikke webhook-hemmeligheten`() {
        Dependencies(webhookSecret = "s3cret").toString().contains("s3cret") shouldBe false
    }

    // --- identiteten kommer fra GET /app (I1) ---

    private val appRequests = mutableListOf<HttpRequestData>()

    private fun appApi(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = githubHttpClient(
        MockEngine { req ->
            appRequests += req
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    @Test
    fun `GET app som feiler gir redusert modus`() {
        Dependencies.fromEnvironment(complete, http = appApi("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized))
            .degraded shouldBe true
        appRequests.single().url.encodedPath shouldBe "/app"
        appRequests.single().headers[HttpHeaders.Authorization]!!.startsWith("Bearer ") shouldBe true
    }

    @Test
    fun `slug eller id som ikke stemmer med GET app gir redusert modus`() {
        Dependencies.fromEnvironment(complete, http = appApi("""{"id":123,"slug":"noen-andre"}""")).degraded shouldBe true
        Dependencies.fromEnvironment(complete, http = appApi("""{"id":999,"slug":"entur-idun"}""")).degraded shouldBe true
        appRequests.size shouldBe 2
    }

    @Test
    fun `GET app som stemmer gir full modus`() {
        Dependencies.fromEnvironment(complete, http = appApi("""{"id":123,"slug":"entur-idun"}""")).let {
            it.degraded shouldBe false
            it.ownAppId shouldBe 123
        }
    }

    @Test
    fun `identiteten tas fra GET app`() =
        runBlocking<Unit> {
            val auth = AppAuth("123", pem, Clock.systemUTC(), appApi("""{"id":123,"slug":"entur-idun","name":"Idun"}"""))
            verifyAppIdentity(auth, appId = 123, slug = "entur-idun") shouldBe AppIdentityCheck.Verified(123, "entur-idun[bot]")
            verifyAppIdentity(auth, appId = 123, slug = "annen") shouldBe
                AppIdentityCheck.Rejected("GITHUB_APP_SLUG (annen) stemmer ikke med GET /app (entur-idun)")
        }
}
