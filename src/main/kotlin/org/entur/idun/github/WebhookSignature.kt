package org.entur.idun.github

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val PREFIX = "sha256="

/**
 * Verifiserer `X-Hub-Signature-256` for en webhook-body. Feiler lukket: manglende eller
 * misformet header gir `false`, og selve sammenligningen tar like lang tid uansett input.
 */
fun verifySignature(
    secret: ByteArray,
    body: ByteArray,
    header: String?,
): Boolean {
    if (header == null || !header.startsWith(PREFIX)) return false
    val provided = decodeHex(header.substring(PREFIX.length)) ?: return false
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret, "HmacSHA256"))
    val expected = mac.doFinal(body)
    return MessageDigest.isEqual(expected, provided)
}

private fun decodeHex(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(hex[2 * i], 16)
        val lo = Character.digit(hex[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
