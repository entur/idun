package org.entur.idun.server

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.entur.idun.review.PROMPT_VERSION
import org.entur.idun.review.ReviewFile
import org.entur.idun.review.ReviewInput
import org.junit.jupiter.api.Test

class FingerprintTest {
    private val input = ReviewInput("refactor: x", listOf(ReviewFile("a.kt", "p", "before", "after")))

    private fun fingerprint(
        number: Int,
        mergeBase: String,
        input: ReviewInput,
        model: String = "gemini-x",
        promptVersion: String = PROMPT_VERSION,
    ) = reviewFingerprint(number, mergeBase, input, model, promptVersion)

    @Test
    fun `modell og promptversjon teller`() {
        val fp = fingerprint(7, "mb", input)
        fingerprint(7, "mb", input, model = "gemini-y") shouldNotBe fp
        fingerprint(7, "mb", input, promptVersion = "$PROMPT_VERSION-neste") shouldNotBe fp
        reviewFingerprint(7, "mb", input, "gemini-x") shouldBe fp
    }

    @Test
    fun `fingeravtrykket er 16 hex-tegn og stabilt`() {
        val fp = fingerprint(7, "mb", input)
        fp shouldMatch Regex("[0-9a-f]{16}")
        fingerprint(7, "mb", input) shouldBe fp
    }

    @Test
    fun `hvert felt teller`() {
        val fp = fingerprint(7, "mb", input)
        fingerprint(8, "mb", input) shouldNotBe fp
        fingerprint(7, "mb2", input) shouldNotBe fp
        fingerprint(7, "mb", input.copy(title = "refactor: y")) shouldNotBe fp
        val file = input.files.single()
        for (changed in listOf(file.copy(path = "b.kt"), file.copy(patch = "q"), file.copy(before = null), file.copy(after = "x"))) {
            fingerprint(7, "mb", input.copy(files = listOf(changed))) shouldNotBe fp
        }
    }

    @Test
    fun `feltgrensene er entydige`() {
        val a = ReviewInput("t", listOf(ReviewFile("ab", "c", null, "")))
        val b = ReviewInput("t", listOf(ReviewFile("a", "bc", null, "")))
        fingerprint(1, "m", a) shouldNotBe fingerprint(1, "m", b)
        // null og tom streng er forskjellige
        val c = ReviewInput("t", listOf(ReviewFile("a", "b", "", "")))
        fingerprint(1, "m", a.copy(files = listOf(ReviewFile("a", "b", null, "")))) shouldNotBe fingerprint(1, "m", c)
    }
}
