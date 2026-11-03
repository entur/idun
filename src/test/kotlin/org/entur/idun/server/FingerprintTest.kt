package org.entur.idun.server

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.entur.idun.review.ReviewFile
import org.entur.idun.review.ReviewInput
import org.junit.jupiter.api.Test

class FingerprintTest {
    private val input = ReviewInput("refactor: x", listOf(ReviewFile("a.kt", "p", "before", "after")))

    @Test
    fun `fingeravtrykket er 16 hex-tegn og stabilt`() {
        val fp = reviewFingerprint(7, "mb", input)
        fp shouldMatch Regex("[0-9a-f]{16}")
        reviewFingerprint(7, "mb", input) shouldBe fp
    }

    @Test
    fun `hvert felt teller`() {
        val fp = reviewFingerprint(7, "mb", input)
        reviewFingerprint(8, "mb", input) shouldNotBe fp
        reviewFingerprint(7, "mb2", input) shouldNotBe fp
        reviewFingerprint(7, "mb", input.copy(title = "refactor: y")) shouldNotBe fp
        val file = input.files.single()
        for (changed in listOf(file.copy(path = "b.kt"), file.copy(patch = "q"), file.copy(before = null), file.copy(after = "x"))) {
            reviewFingerprint(7, "mb", input.copy(files = listOf(changed))) shouldNotBe fp
        }
    }

    @Test
    fun `feltgrensene er entydige`() {
        val a = ReviewInput("t", listOf(ReviewFile("ab", "c", null, "")))
        val b = ReviewInput("t", listOf(ReviewFile("a", "bc", null, "")))
        reviewFingerprint(1, "m", a) shouldNotBe reviewFingerprint(1, "m", b)
        // null og tom streng er forskjellige
        val c = ReviewInput("t", listOf(ReviewFile("a", "b", "", "")))
        reviewFingerprint(1, "m", a.copy(files = listOf(ReviewFile("a", "b", null, "")))) shouldNotBe reviewFingerprint(1, "m", c)
    }
}
