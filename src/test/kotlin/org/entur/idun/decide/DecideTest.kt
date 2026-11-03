package org.entur.idun.decide

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.entur.idun.ci.CiState
import org.entur.idun.config.ConfigResult
import org.entur.idun.config.IdunConfig
import org.entur.idun.github.PullRequest
import org.entur.idun.review.ReviewResult
import org.entur.idun.review.Verdict
import org.entur.idun.review.fixtureInput
import org.entur.idun.review.validResponse
import org.entur.idun.rules.ConventionalTitle
import org.entur.idun.rules.Reason
import org.entur.idun.rules.ReasonCode
import org.entur.idun.rules.RuleResult
import org.junit.jupiter.api.Test

class DecideTest {
    private val pr =
        PullRequest(
            number = 1,
            title = "refactor: rename helper",
            draft = false,
            state = "open",
            merged = false,
            headSha = "head",
            baseSha = "base",
            headRepoId = 10L,
            baseRepoId = 10L,
            changedFiles = 3,
        )
    private val refactor = ConventionalTitle("refactor", null, breaking = false)
    private val config = ConfigResult.Ok(IdunConfig.DEFAULT)
    private val eligible = RuleResult(emptyList(), emptyList(), 0)

    private fun decide(
        mode: Mode = Mode.APPROVE,
        pr: PullRequest = this.pr,
        title: ConventionalTitle? = refactor,
        ci: CiState? = CiState.GREEN,
        config: ConfigResult? = this.config,
        rules: RuleResult? = eligible,
    ): Outcome? = decideBeforeReview(mode, pr, title, ci, config, rules)

    @Test
    fun `modus OFF gir Ignore`() {
        decide(mode = Mode.OFF) shouldBe Outcome.Ignore
    }

    @Test
    fun `ukjent modus er OFF`() {
        parseMode("approve") shouldBe Mode.APPROVE
        parseMode("advise") shouldBe Mode.ADVISE
        parseMode("off") shouldBe Mode.OFF
        parseMode("APPROVE") shouldBe Mode.OFF
        parseMode("yes") shouldBe Mode.OFF
        parseMode("") shouldBe Mode.OFF
        parseMode(null) shouldBe Mode.OFF
    }

    @Test
    fun `ikke refactor gir Ignore`() {
        decide(title = ConventionalTitle("feat", null, breaking = false)) shouldBe Outcome.Ignore
        decide(title = null) shouldBe Outcome.Ignore
    }

    @Test
    fun `breaking gir NeedsHuman BREAKING`() {
        val outcome = decide(title = refactor.copy(breaking = true))
        outcome.shouldBeInstanceOf<Outcome.NeedsHuman>().reasons.map { it.code } shouldBe listOf(ReasonCode.BREAKING)
    }

    @Test
    fun `fork gir NeedsHuman FORK`() {
        val outcome = decide(pr = pr.copy(headRepoId = 99L))
        outcome.shouldBeInstanceOf<Outcome.NeedsHuman>().reasons.map { it.code } shouldBe listOf(ReasonCode.FORK)
    }

    @Test
    fun `slettet fork er også fork`() {
        val outcome = decide(pr = pr.copy(headRepoId = null))
        outcome.shouldBeInstanceOf<Outcome.NeedsHuman>().reasons.map { it.code } shouldBe listOf(ReasonCode.FORK)
    }

    @Test
    fun `draft gir Waiting DRAFT`() {
        decide(pr = pr.copy(draft = true)) shouldBe Outcome.Waiting(WaitReason.DRAFT)
    }

    @Test
    fun `rekkefølgen gjør at draft venter før CI hentes`() {
        decide(pr = pr.copy(draft = true), ci = null, config = null, rules = null) shouldBe Outcome.Waiting(WaitReason.DRAFT)
    }

    @Test
    fun `pending CI gir Waiting PENDING_CI`() {
        decide(ci = CiState.PENDING) shouldBe Outcome.Waiting(WaitReason.PENDING_CI)
    }

    @Test
    fun `rød CI gir CiFailed`() {
        decide(ci = CiState.RED) shouldBe Outcome.CiFailed
    }

    @Test
    fun `for mange filer gir NeedsHuman TOO_MANY_FILES`() {
        decide(pr = pr.copy(changedFiles = 101)).shouldBeInstanceOf<Outcome.NeedsHuman>()
            .reasons.map { it.code } shouldBe listOf(ReasonCode.TOO_MANY_FILES)
        decide(pr = pr.copy(changedFiles = 100)) shouldBe null
    }

    @Test
    fun `ugyldig konfig gir NeedsHuman INVALID_CONFIG med årsak`() {
        val outcome = decide(config = ConfigResult.Invalid("ugyldig yaml")).shouldBeInstanceOf<Outcome.NeedsHuman>()
        outcome.reasons.map { it.code } shouldBe listOf(ReasonCode.INVALID_CONFIG)
        outcome.reasons.single().detail shouldContain "ugyldig yaml"
    }

    @Test
    fun `regler som ikke er eligible gir NeedsHuman med reglenes årsaker`() {
        val reasons =
            listOf(
                Reason(ReasonCode.PROTECTED_PATH, "a"),
                Reason(ReasonCode.TEST_CHANGED, "b"),
            )
        val outcome = decide(rules = RuleResult(reasons, emptyList(), 0))
        outcome shouldBe Outcome.NeedsHuman(reasons)
    }

    @Test
    fun `fork med grønn CI og gyldige regler er NeedsHuman`() {
        val outcome = decide(pr = pr.copy(headRepoId = 99L), ci = CiState.GREEN, rules = eligible)
        outcome.shouldBeInstanceOf<Outcome.NeedsHuman>().reasons.map { it.code } shouldBe listOf(ReasonCode.FORK)
    }

    @Test
    fun `alt i orden gir null og PR-en går videre til review`() {
        decide() shouldBe null
    }

    @Test
    fun `data som ikke er hentet ennå gir null`() {
        decide(ci = null, config = null, rules = null) shouldBe null
        decide(config = null, rules = null) shouldBe null
        decide(rules = null) shouldBe null
    }

    @Test
    fun `review Failed gir NeedsHuman REVIEW_FAILED`() {
        val outcome = decideAfterReview(ReviewResult.Failed("tidsavbrudd"), fixtureInput).shouldBeInstanceOf<Outcome.NeedsHuman>()
        outcome.reasons shouldHaveSize 1
        outcome.reasons.single().code shouldBe ReasonCode.REVIEW_FAILED
        outcome.reasons.single().detail shouldContain "tidsavbrudd"
        outcome.review shouldBe null
    }

    @Test
    fun `review Failed med brukt budsjett gir BUDGET_SPENT`() {
        val outcome =
            decideAfterReview(ReviewResult.Failed("budsjett brukt", budgetSpent = true), fixtureInput)
                .shouldBeInstanceOf<Outcome.NeedsHuman>()
        outcome.reasons.single().code shouldBe ReasonCode.BUDGET_SPENT
    }

    @Test
    fun `godtatt review gir Safe`() {
        val response = validResponse()
        decideAfterReview(ReviewResult.Answered(response, 0.01), fixtureInput) shouldBe Outcome.Safe(response)
    }

    @Test
    fun `avvist review gir NeedsHuman MODEL_FLAGGED med svaret vedlagt`() {
        val response = validResponse().copy(verdict = Verdict.UNSURE)
        val outcome = decideAfterReview(ReviewResult.Answered(response, 0.01), fixtureInput).shouldBeInstanceOf<Outcome.NeedsHuman>()
        outcome.reasons.single().code shouldBe ReasonCode.MODEL_FLAGGED
        outcome.reasons.single().detail shouldContain "unsure"
        outcome.review shouldBe response
    }

    @Test
    fun `externalId for hver utfall`() {
        Outcome.Safe(validResponse()).externalId() shouldBe "safe"
        Outcome.NeedsHuman(emptyList()).externalId() shouldBe "needs-human"
        Outcome.CiFailed.externalId() shouldBe "ci-failed"
        Outcome.Ignore.externalId() shouldBe null
        Outcome.Waiting(WaitReason.DRAFT).externalId() shouldBe null
    }
}
