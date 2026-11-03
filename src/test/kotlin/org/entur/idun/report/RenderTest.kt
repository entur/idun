package org.entur.idun.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.entur.idun.decide.Mode
import org.entur.idun.decide.Outcome
import org.entur.idun.decide.WaitReason
import org.entur.idun.review.FIXTURE_PATH
import org.entur.idun.review.Finding
import org.entur.idun.review.validResponse
import org.entur.idun.rules.Reason
import org.entur.idun.rules.ReasonCode
import org.junit.jupiter.api.Test

class RenderTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"

    private val flagged =
        Outcome.NeedsHuman(
            reasons = listOf(Reason(ReasonCode.MODEL_FLAGGED, "modellen rapporterte 1 funn")),
            review =
                validResponse().copy(
                    summary = "Se https://evil.example og ping @stunor",
                    findings = listOf(Finding(FIXTURE_PATH, 12, "Endrer default, jf. #123 <b>fet</b> @stunor")),
                ),
        )

    @Test
    fun `Safe gir success, NeedsHuman gir neutral, CiFailed gir neutral, Waiting gir in_progress`() {
        renderCheck(Outcome.Safe(validResponse())).let {
            it.status shouldBe "completed"
            it.conclusion shouldBe "success"
            it.externalId shouldBe "safe"
        }
        renderCheck(flagged).let {
            it.status shouldBe "completed"
            it.conclusion shouldBe "neutral"
            it.externalId shouldBe "needs-human"
        }
        renderCheck(Outcome.CiFailed).let {
            it.status shouldBe "completed"
            it.conclusion shouldBe "neutral"
            it.externalId shouldBe "ci-failed"
        }
        renderCheck(Outcome.Waiting(WaitReason.PENDING_CI)).let {
            it.status shouldBe "in_progress"
            it.conclusion shouldBe null
            it.externalId shouldBe null
        }
        renderCheck(Outcome.Waiting(WaitReason.DRAFT)).status shouldBe "in_progress"
    }

    @Test
    fun `renderCheck for Ignore kaster`() {
        shouldThrow<IllegalArgumentException> { renderCheck(Outcome.Ignore) }
        shouldThrow<IllegalArgumentException> { renderComment(Outcome.Ignore, sha, Mode.ADVISE) }
    }

    @Test
    fun `check-teksten vasker modelltekst og detaljer`() {
        val out =
            renderCheck(
                Outcome.NeedsHuman(
                    listOf(Reason(ReasonCode.PROTECTED_PATH, "src/@evil/#12/[x](http://e.example) endret")),
                ),
            )
        val text = out.title + out.summary
        text shouldContain "PROTECTED_PATH"
        text shouldNotContain "@evil"
        text shouldNotContain "#12"
        text shouldNotContain "http://"
        text shouldNotContain "]("
        renderCheck(Outcome.Safe(validResponse().copy(summary = "ping @stunor"))).summary shouldNotContain "@stunor"
    }

    @Test
    fun `kommentaren starter med markor og vasker modelltekst`() {
        val body = renderComment(flagged, sha, Mode.APPROVE)
        body shouldStartWith "<!-- idun:comment -->"
        body shouldContain "0123456"
        body shouldNotContain sha
        body shouldContain "MODEL_FLAGGED"
        body shouldContain "modellen rapporterte 1 funn"
        body shouldContain FIXTURE_PATH.let { defuse(it) } + ":12"
        body shouldNotContain "@stunor"
        body shouldNotContain "#123"
        body shouldNotContain "https://"
        body shouldNotContain "<b>"
    }

    @Test
    fun `kommentaren vasker ogsaa filsti og detalj fra PR-en`() {
        val body =
            renderComment(
                Outcome.NeedsHuman(
                    listOf(Reason(ReasonCode.OTHER_FILE, "docs/@stunor/README.md @stunor")),
                    review =
                        validResponse().copy(
                            findings = listOf(Finding("src/@sti/A.kt", 3, "tekst")),
                        ),
                ),
                sha,
                Mode.APPROVE,
            )
        body shouldNotContain "@stunor"
        body shouldNotContain "@sti"
    }

    @Test
    fun `modellens funn tas bare med ved MODEL_FLAGGED`() {
        val outcome =
            Outcome.NeedsHuman(
                listOf(Reason(ReasonCode.TEST_CHANGED, "en test er endret")),
                review = validResponse().copy(summary = "hemmelig oppsummering"),
            )
        renderComment(outcome, sha, Mode.APPROVE) shouldNotContain "hemmelig oppsummering"
    }

    @Test
    fun `advise sier at Idun ikke godkjenner og ber om tommel`() {
        val body = renderComment(Outcome.Safe(validResponse()), sha, Mode.ADVISE)
        body shouldContain "godkjenner ikke"
        body shouldContain "👍"
        body shouldContain "👎"
        renderComment(Outcome.Safe(validResponse()), sha, Mode.APPROVE) shouldNotContain "godkjenner ikke"
    }

    @Test
    fun `kommentar for Waiting og CiFailed`() {
        renderComment(Outcome.CiFailed, sha, Mode.APPROVE) shouldStartWith "<!-- idun:comment -->"
        renderComment(Outcome.Waiting(WaitReason.DRAFT), sha, Mode.APPROVE) shouldStartWith "<!-- idun:comment -->"
        renderComment(Outcome.Waiting(WaitReason.PENDING_CI), sha, Mode.APPROVE) shouldContain "CI"
    }

    @Test
    fun `NOT_REFACTOR_ANY_MORE har markor`() {
        NOT_REFACTOR_ANY_MORE shouldStartWith "<!-- idun:comment -->"
    }
}
