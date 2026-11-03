package org.entur.idun.server

import org.entur.idun.review.ReviewInput
import java.nio.ByteBuffer
import java.security.MessageDigest

private const val FINGERPRINT_LENGTH = 16
private const val FINGERPRINT_VERSION = "idun-fp-v1"

/**
 * Fingeravtrykk av det modellen vurderte: PR-nummer, merge-base, tittel og hver fil (sti, diff, før, etter) i
 * rekkefølge. Hvert felt lengdeprefikses og `null` skilles fra tom streng, så feltgrensene er entydige.
 * Gir de første 16 hex-tegnene av SHA-256. En vurdering gjenbrukes bare når fingeravtrykket er likt.
 */
internal fun reviewFingerprint(
    number: Int,
    mergeBase: String,
    input: ReviewInput,
): String {
    val digest = MessageDigest.getInstance("SHA-256")

    fun field(value: String?) {
        if (value == null) {
            digest.update(0)
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(1)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    field(FINGERPRINT_VERSION)
    field(number.toString())
    field(mergeBase)
    field(input.title)
    field(input.files.size.toString())
    for (file in input.files) {
        field(file.path)
        field(file.patch)
        field(file.before)
        field(file.after)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(FINGERPRINT_LENGTH)
}
