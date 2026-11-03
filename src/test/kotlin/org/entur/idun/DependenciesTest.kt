package org.entur.idun

import io.kotest.matchers.shouldBe
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.security.KeyPairGenerator

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
}
