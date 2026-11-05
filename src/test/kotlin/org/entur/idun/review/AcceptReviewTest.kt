package org.entur.idun.review

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class AcceptReviewTest {
    private val input = fixtureInput
    private val path = FIXTURE_PATH

    @Test
    fun `godtar bare preserving med alle punkter NO og gyldige kilder`() {
        acceptReview(validResponse(), input) shouldBe Acceptance.Accepted
    }

    @Test
    fun `avviser UNSURE, manglende punkt, YES, kilde til ukjent fil, linje utenfor filen og funn`() {
        acceptReview(validResponse().copy(verdict = Verdict.UNSURE), input).shouldBeInstanceOf<Acceptance.Rejected>()
        acceptReview(validResponse().copy(verdict = Verdict.BEHAVIOUR_CHANGES), input).shouldBeInstanceOf<Acceptance.Rejected>()
        acceptReview(validResponse().copy(checklist = validResponse().checklist.drop(1)), input)
            .shouldBeInstanceOf<Acceptance.Rejected>()
        acceptReview(validResponse().withAnswer(ChecklistItem.STRING_LITERALS, Answer.YES), input)
            .shouldBeInstanceOf<Acceptance.Rejected>()
        acceptReview(validResponse().withAnswer(ChecklistItem.STRING_LITERALS, Answer.UNSURE), input)
            .shouldBeInstanceOf<Acceptance.Rejected>()
        acceptReview(validResponse().withEvidence(Evidence("other/File.kt", 1, "x")), input)
            .shouldBeInstanceOf<Acceptance.Rejected>()
        acceptReview(validResponse().withEvidence(Evidence(path, 9999, "x")), input).shouldBeInstanceOf<Acceptance.Rejected>()
        acceptReview(validResponse().copy(findings = listOf(Finding(path, 1, "x"))), input)
            .shouldBeInstanceOf<Acceptance.Rejected>()
    }

    @Test
    fun `avviser duplisert punkt, svar uten kilde og linje 0`() {
        val dup = validResponse().checklist
        acceptReview(validResponse().copy(checklist = dup + dup.first()), input).shouldBeInstanceOf<Acceptance.Rejected>()
        acceptReview(
            validResponse().copy(
                checklist = dup.drop(1) + dup.first(),
            ),
            input,
        ) shouldBe Acceptance.Accepted
        acceptReview(
            validResponse().copy(checklist = dup.map { if (it.item == ChecklistItem.SIGNATURES) it.copy(evidence = emptyList()) else it }),
            input,
        ).shouldBeInstanceOf<Acceptance.Rejected>()
        acceptReview(validResponse().withEvidence(Evidence(path, 0, "x")), input).shouldBeInstanceOf<Acceptance.Rejected>()
    }

    @Test
    fun `linjegrensen er antall linjer i after, og i before for slettede filer`() {
        acceptReview(validResponse().withEvidence(Evidence(path, 20, "x")), input) shouldBe Acceptance.Accepted
        acceptReview(validResponse().withEvidence(Evidence(path, 21, "x")), input).shouldBeInstanceOf<Acceptance.Rejected>()

        val deleted = ReviewInput("t", listOf(ReviewFile(path, "patch", before = "a\nb\nc\n", after = null)))
        acceptReview(validResponse().withEvidence(Evidence(path, 3, "x")), deleted) shouldBe Acceptance.Accepted
        acceptReview(validResponse().withEvidence(Evidence(path, 4, "x")), deleted).shouldBeInstanceOf<Acceptance.Rejected>()

        val empty = ReviewInput("t", listOf(ReviewFile(path, "patch", before = null, after = null)))
        acceptReview(validResponse(), empty).shouldBeInstanceOf<Acceptance.Rejected>()
    }
}
