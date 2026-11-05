package org.entur.idun.github

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookSignatureTest {
    private val secret = "hemmelig".toByteArray()
    private val body = """{"action":"opened"}""".toByteArray()

    private fun hmacHex(
        key: ByteArray,
        data: ByteArray,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `gyldig signatur gir true`() {
        verifySignature(secret, body, "sha256=" + hmacHex(secret, body)) shouldBe true
    }

    @Test
    fun `feil hemmelighet gir false`() {
        verifySignature(secret, body, "sha256=" + hmacHex("annen".toByteArray(), body)) shouldBe false
    }

    @Test
    fun `endret body gir false`() {
        verifySignature(secret, "{}".toByteArray(), "sha256=" + hmacHex(secret, body)) shouldBe false
    }

    @Test
    fun `manglende header gir false`() {
        verifySignature(secret, body, null) shouldBe false
    }

    @Test
    fun `header uten sha256-prefiks gir false`() {
        verifySignature(secret, body, hmacHex(secret, body)) shouldBe false
        verifySignature(secret, body, "sha1=" + hmacHex(secret, body)) shouldBe false
    }

    @Test
    fun `ugyldig hex og feil lengde gir false`() {
        verifySignature(secret, body, "sha256=") shouldBe false
        verifySignature(secret, body, "sha256=xyz") shouldBe false
        verifySignature(secret, body, "sha256=abc") shouldBe false
        verifySignature(secret, body, "sha256=" + "zz".repeat(32)) shouldBe false
    }

    @Test
    fun `store bokstaver i hex godtas ikke feilaktig avvist`() {
        verifySignature(secret, body, "sha256=" + hmacHex(secret, body).uppercase()) shouldBe true
    }
}
