package org.entur.idun.server

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.entur.idun.ci.GITHUB_ACTIONS_APP_ID
import org.entur.idun.decide.Mode
import org.entur.idun.github.CheckRunInfo
import org.entur.idun.github.CheckRunWrite
import org.entur.idun.github.GitHubApiException
import org.entur.idun.github.IssueComment
import org.entur.idun.github.Reactions
import org.entur.idun.github.RepoRef
import org.entur.idun.github.Review
import org.entur.idun.report.APPROVED_LABEL
import org.entur.idun.report.COMMENT_MARKER
import org.entur.idun.report.NEEDS_HUMAN_LABEL
import org.entur.idun.report.NOT_REFACTOR_ANY_MORE
import org.entur.idun.review.FIXTURE_PATH
import org.entur.idun.review.ReviewResult
import org.entur.idun.review.Verdict
import org.entur.idun.review.validResponse
import org.entur.idun.rules.FileStatus
import org.junit.jupiter.api.Test

class AssessorTest {
    private val gh = FakeGitHub()
    private val factory = CountingFactory(gh)
    private val reviewer = FakeReviewer()
    private val log = CapturedLog()
    private var mode = Mode.ADVISE

    private val assessor =
        Assessor(
            github = factory,
            reviewer = reviewer,
            ownAppId = OWN_APP_ID,
            appLogin = APP_LOGIN,
            locks = PrLocks(),
            log = log.logger,
            modeOf = { repo -> if (repo == REPO) mode else Mode.OFF },
        )

    private suspend fun assess() = assessor.onPullRequest(installationId = 1, repo = REPO, number = 7, action = "synchronize")

    private fun approvalOf(sha: String) = "<!-- idun:approval sha=$sha -->"

    private fun ownComment() = gh.comments.single { it.login == APP_LOGIN && COMMENT_MARKER in it.body }

    @Test
    fun `mode off skriver ingenting`() =
        runBlocking<Unit> {
            assessor.onPullRequest(1, RepoRef("entur", "ikke-listet"), 7, "opened")
            gh.writes.shouldBeEmpty()
            factory.calls shouldBe 0
            reviewer.calls shouldBe 0
        }

    @Test
    fun `ikke-refactor uten tidligere vurdering skriver ingenting`() =
        runBlocking<Unit> {
            gh.pr = gh.pr.copy(title = "feat: noe nytt")
            assess()
            gh.writes.shouldBeEmpty()
            reviewer.calls shouldBe 0
        }

    @Test
    fun `tittel endret bort fra refactor trekker godkjenning, fjerner label og oppdaterer kommentar`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            assess()
            gh.labels shouldContainExactly setOf(APPROVED_LABEL)
            gh.reviews.single().state shouldBe "APPROVED"

            gh.pr = gh.pr.copy(title = "feat: likevel ny oppførsel")
            assessor.onPullRequest(1, REPO, 7, "edited")

            gh.reviews.single().state shouldBe "DISMISSED"
            gh.labels.shouldBeEmpty()
            ownComment().body shouldBe NOT_REFACTOR_ANY_MORE
            gh.comments shouldHaveSize 1
            val check = gh.ownRuns.getValue(HEAD)
            check.status shouldBe "completed"
            check.conclusion shouldBe "neutral"
            check.title shouldBe "Ikke lenger en refactor-PR"
            reviewer.calls shouldBe 1
        }

    @Test
    fun `tilbake til refactor etter ikke-refactor gjenbruker ikke gammel safe-check`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            assess()
            gh.pr = gh.pr.copy(title = "feat: likevel ny oppførsel")
            assess()
            gh.pr = gh.pr.copy(title = "refactor: rename helper")
            assess()
            reviewer.calls shouldBe 2
            gh.ownRuns.getValue(HEAD).conclusion shouldBe "success"
        }

    @Test
    fun `grønn CI og gyldige regler i advise gir success-check, kommentar og label uten godkjenning`() =
        runBlocking<Unit> {
            mode = Mode.ADVISE
            assess()
            val check = gh.ownRuns.getValue(HEAD)
            check.status shouldBe "completed"
            check.conclusion shouldBe "success"
            check.externalId shouldStartWith "safe:"
            ownComment().body shouldContain "advise-modus"
            gh.labels shouldContainExactly setOf(APPROVED_LABEL)
            gh.reviews.shouldBeEmpty()
            gh.writes.none { it.startsWith("approve") } shouldBe true
            reviewer.calls shouldBe 1
        }

    @Test
    fun `samme i approve gir godkjenning med markør for headSha`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            assess()
            gh.ownRuns.getValue(HEAD).conclusion shouldBe "success"
            gh.labels shouldContainExactly setOf(APPROVED_LABEL)
            val review = gh.reviews.single()
            review.state shouldBe "APPROVED"
            review.body shouldContain approvalOf(HEAD)
            ownComment().body shouldNotContain "advise-modus"
        }

    @Test
    fun `modellen får tittel, diff og innhold fra merge-base og head`() =
        runBlocking<Unit> {
            assess()
            val input = reviewer.inputs.single()
            input.title shouldBe "refactor: rename helper"
            val file = input.files.single()
            file.path shouldBe FIXTURE_PATH
            file.patch shouldBe sourceFile().patch
            file.before shouldBe gh.contents.getValue(FIXTURE_PATH to MERGE_BASE)
            file.after shouldBe gh.contents.getValue(FIXTURE_PATH to HEAD)
        }

    @Test
    fun `filene hentes fra compare mellom merge-base og head, ikke fra PR-ens gjeldende head`() =
        runBlocking<Unit> {
            assess()
            gh.mergeBaseCalls.first() shouldBe (BASE to HEAD)
            gh.compareCalls shouldContainExactly listOf(MERGE_BASE to HEAD)
        }

    @Test
    fun `antall filer som avviker fra changedFiles gir feil-check uten modellkall`() =
        runBlocking<Unit> {
            gh.pr = gh.pr.copy(changedFiles = 2)
            assess()
            reviewer.calls shouldBe 0
            gh.ownRuns.getValue(HEAD).externalId shouldBe "error"
            log.single("idun.error") shouldContain "changed_files"
        }

    @Test
    fun `omdøpt fil leses fra gammel sti på merge-base og ny sti på head`() =
        runBlocking<Unit> {
            val old = "src/main/kotlin/org/entur/products/Old.kt"
            gh.files = listOf(sourceFile(previousPath = old, status = FileStatus.RENAMED))
            gh.contents[old to MERGE_BASE] = "val a = 1\nval x = 2\n"
            assess()
            gh.contentReads shouldContain (old to MERGE_BASE)
            gh.contentReads shouldContain (FIXTURE_PATH to HEAD)
            gh.contentReads shouldNotContain (FIXTURE_PATH to MERGE_BASE)
            reviewer.inputs.single().files.single().before shouldBe "val a = 1\nval x = 2\n"
        }

    @Test
    fun `ny fil leses bare fra head`() =
        runBlocking<Unit> {
            gh.files = listOf(sourceFile(status = FileStatus.ADDED))
            assess()
            gh.contentReads shouldNotContain (FIXTURE_PATH to MERGE_BASE)
            reviewer.inputs.single().files.single().before shouldBe null
        }

    @Test
    fun `manglende innhold for endret kildefil gir needs-human uten modellkall`() =
        runBlocking<Unit> {
            gh.contents.remove(FIXTURE_PATH to MERGE_BASE)
            assess()
            reviewer.calls shouldBe 0
            gh.ownRuns.getValue(HEAD).externalId shouldBe "needs-human"
            gh.ownRuns.getValue(HEAD).summary shouldContain "MISSING_DIFF"
            gh.labels shouldContainExactly setOf(NEEDS_HUMAN_LABEL)
        }

    @Test
    fun `ny push under review skriver ingenting for gammel sha`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            reviewer.beforeAnswer = { gh.pr = gh.pr.copy(headSha = "head2") }
            assess()
            reviewer.calls shouldBe 1
            gh.ownRuns.getValue(HEAD).status shouldBe "in_progress"
            gh.ownRuns.keys shouldContainExactly setOf(HEAD)
            gh.comments.shouldBeEmpty()
            gh.labels.shouldBeEmpty()
            gh.reviews.shouldBeEmpty()
        }

    @Test
    fun `head flyttet etter godkjenning gir tilbaketrekking`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            gh.afterApprove = { gh.pr = gh.pr.copy(headSha = "head2") }
            assess()
            val review = gh.reviews.single()
            review.body shouldContain approvalOf(HEAD)
            review.state shouldBe "DISMISSED"
        }

    @Test
    fun `to samtidige hendelser for samme PR kaller modellen én gang`() =
        runBlocking<Unit> {
            reviewer.beforeAnswer = { slowly() }
            listOf(launch { assess() }, launch { assess() }).joinAll()
            reviewer.calls shouldBe 1
            gh.ownRuns.getValue(HEAD).externalId shouldStartWith "safe:"
        }

    @Test
    fun `gjenbruk av safe-check godkjenner etter bytte fra advise til approve uten nytt modellkall`() =
        runBlocking<Unit> {
            mode = Mode.ADVISE
            assess()
            gh.reviews.shouldBeEmpty()

            mode = Mode.APPROVE
            assess()
            reviewer.calls shouldBe 1
            gh.reviews.single().body shouldContain approvalOf(HEAD)
            gh.labels shouldContainExactly setOf(APPROVED_LABEL)

            // Én godkjenning er nok: neste hendelse godkjenner ikke på nytt.
            assess()
            gh.reviews shouldHaveSize 1
            reviewer.calls shouldBe 1
        }

    @Test
    fun `gjenbruk av needs-human fra modellen setter label uten nytt modellkall`() =
        runBlocking<Unit> {
            reviewer.result = ReviewResult.Answered(validResponse().copy(verdict = Verdict.UNSURE), costUsd = 0.1)
            mode = Mode.APPROVE
            assess()
            gh.ownRuns.getValue(HEAD).externalId shouldStartWith "needs-human:"
            gh.labels.clear()
            assess()
            reviewer.calls shouldBe 1
            gh.labels shouldContainExactly setOf(NEEDS_HUMAN_LABEL)
            gh.reviews.shouldBeEmpty()
        }

    @Test
    fun `needs-human uten fingeravtrykk gjenbrukes ikke`() =
        runBlocking<Unit> {
            gh.ownRuns[HEAD] = CheckRunWrite("completed", "neutral", "Trenger et menneske", "x", "needs-human")
            assess()
            reviewer.calls shouldBe 1
        }

    @Test
    fun `identisk hendelse gjenbruker vurderingen uten modellkall`() =
        runBlocking<Unit> {
            assess()
            val first = gh.ownRuns.getValue(HEAD)
            assess()
            reviewer.calls shouldBe 1
            gh.ownRuns.getValue(HEAD) shouldBe first
        }

    @Test
    fun `samme head i en annen PR med annen base vurderes av modellen på nytt`() =
        runBlocking<Unit> {
            assess()
            gh.pr = gh.pr.copy(number = 8, baseSha = "base2")
            gh.mergeBaseSha = "mergebase2"
            gh.contents[FIXTURE_PATH to "mergebase2"] = "val c = 1\nval x = 2\n"
            gh.files = listOf(sourceFile().copy(patch = "@@ -1 +1 @@\n-val c = 1\n+val b = 1"))
            assessor.onPullRequest(1, REPO, 8, "opened")
            reviewer.calls shouldBe 2
        }

    @Test
    fun `ny base for samme PR gir nytt modellkall`() =
        runBlocking<Unit> {
            assess()
            gh.pr = gh.pr.copy(baseSha = "base2")
            gh.mergeBaseSha = "mergebase2"
            gh.contents[FIXTURE_PATH to "mergebase2"] = "val a = 1\nval x = 2\n"
            assess()
            reviewer.calls shouldBe 2
        }

    @Test
    fun `endret refactor-tittel gir nytt modellkall`() =
        runBlocking<Unit> {
            assess()
            gh.pr = gh.pr.copy(title = "refactor: rename another helper")
            assess()
            reviewer.calls shouldBe 2
        }

    @Test
    fun `safe med feil conclusion gjenbrukes ikke`() =
        runBlocking<Unit> {
            assess()
            gh.ownRuns[HEAD] = gh.ownRuns.getValue(HEAD).copy(conclusion = "neutral")
            assess()
            reviewer.calls shouldBe 2
        }

    @Test
    fun `gjenbruk leser head på nytt og skriver ingenting når den har flyttet seg`() =
        runBlocking<Unit> {
            mode = Mode.ADVISE
            assess()
            mode = Mode.APPROVE
            gh.labels.clear()
            gh.onCall = { if (it == "compareFiles") gh.pr = gh.pr.copy(headSha = "head2") }
            assess()
            reviewer.calls shouldBe 1
            gh.reviews.shouldBeEmpty()
            gh.labels.shouldBeEmpty()
        }

    @Test
    fun `ventende CI etter safe gir egen external_id`() =
        runBlocking<Unit> {
            assess()
            gh.ciRuns.clear()
            gh.ciRuns += CheckRunInfo(2, "test", "in_progress", null, 12, GITHUB_ACTIONS_APP_ID, null)
            assess()
            gh.ownRuns.getValue(HEAD).externalId shouldBe "waiting"
        }

    @Test
    fun `refactor-utropstegn etter godkjenning trekker godkjenningen tilbake`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            assess()
            gh.reviews.single().state shouldBe "APPROVED"
            gh.pr = gh.pr.copy(title = "refactor!: rename helper")
            assess()
            gh.reviews.single().state shouldBe "DISMISSED"
            gh.labels shouldContainExactly setOf(NEEDS_HUMAN_LABEL)
            gh.ownRuns.getValue(HEAD).summary shouldContain "BREAKING"
        }

    @Test
    fun `CI som blir rød på samme commit trekker godkjenningen tilbake`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            assess()
            gh.ciRuns.clear()
            gh.ciRuns += CheckRunInfo(2, "test", "completed", "failure", 12, GITHUB_ACTIONS_APP_ID, null)
            assess()
            gh.reviews.single().state shouldBe "DISMISSED"
            gh.labels.shouldBeEmpty()
        }

    @Test
    fun `bytte til advise trekker godkjenningen tilbake`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            assess()
            mode = Mode.ADVISE
            assess()
            reviewer.calls shouldBe 1
            gh.reviews.single().state shouldBe "DISMISSED"
            gh.labels shouldContainExactly setOf(APPROVED_LABEL)
        }

    @Test
    fun `tittel endret mens modellen svarer gir ingen skriving`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            reviewer.beforeAnswer = { gh.pr = gh.pr.copy(title = "refactor: something else") }
            assess()
            reviewer.calls shouldBe 1
            gh.ownRuns.getValue(HEAD).status shouldBe "in_progress"
            gh.reviews.shouldBeEmpty()
            gh.labels.shouldBeEmpty()
            gh.comments.shouldBeEmpty()
        }

    @Test
    fun `ny base med annen merge-base mens modellen svarer gir ingen skriving`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            reviewer.beforeAnswer = {
                gh.pr = gh.pr.copy(baseSha = "base2")
                gh.mergeBaseSha = "mergebase2"
            }
            assess()
            gh.ownRuns.getValue(HEAD).status shouldBe "in_progress"
            gh.reviews.shouldBeEmpty()
            gh.labels.shouldBeEmpty()
            gh.comments.shouldBeEmpty()
        }

    @Test
    fun `main som flytter seg mens modellen svarer stopper ikke resultatet`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            reviewer.beforeAnswer = { gh.pr = gh.pr.copy(baseSha = "base3") }
            assess()
            gh.ownRuns.getValue(HEAD).conclusion shouldBe "success"
            gh.reviews.single().state shouldBe "APPROVED"
            gh.labels shouldContainExactly setOf(APPROVED_LABEL)
        }

    @Test
    fun `etter ny base er gammel godkjenning og label borte mens modellen kjører`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            assess()
            gh.reviews.single().state shouldBe "APPROVED"

            gh.pr = gh.pr.copy(baseSha = "base2")
            gh.mergeBaseSha = "mergebase2"
            gh.contents[FIXTURE_PATH to "mergebase2"] = "val a = 1\nval x = 2\n"
            var statesDuringReview: List<String>? = null
            var labelsDuringReview: Set<String>? = null
            reviewer.beforeAnswer = {
                statesDuringReview = gh.reviews.map { it.state }
                labelsDuringReview = gh.labels.toSet()
            }
            assess()
            reviewer.calls shouldBe 2
            statesDuringReview shouldBe listOf("DISMISSED")
            labelsDuringReview shouldBe emptySet()
            // Etterpå er den nye vurderingen skrevet, men samme commit godkjennes ikke på nytt etter en
            // tilbaketrekking (R30): et menneske må godkjenne, eller en ny push gi en ny commit.
            gh.reviews.map { it.state } shouldContainExactly listOf("DISMISSED")
            gh.labels shouldContainExactly setOf(APPROVED_LABEL)
            log.single("idun.approval_vetoed") shouldContain "sha=$HEAD"
        }

    @Test
    fun `feil mens tittelen ikke lenger er refactor fjerner label og godkjenning`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            assess()
            gh.labels shouldContainExactly setOf(APPROVED_LABEL)
            gh.pr = gh.pr.copy(title = "feat: likevel ny oppførsel")
            gh.onCall = { if (it == "comments") throw GitHubApiException(502, "Bad gateway") }
            assess()
            gh.reviews.single().state shouldBe "DISMISSED"
            gh.labels.shouldBeEmpty()
            log.single("idun.error") shouldContain "status=502"
        }

    @Test
    fun `feil etter tidligere godkjenning fjerner label og godkjenning`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            assess()
            gh.labels shouldContainExactly setOf(APPROVED_LABEL)
            gh.onCall = { if (it == "checkSuites") throw GitHubApiException(500, "boom") }
            assess()
            gh.reviews.single().state shouldBe "DISMISSED"
            gh.labels.shouldBeEmpty()
            gh.ownRuns.getValue(HEAD).externalId shouldBe "error"
        }

    @Test
    fun `safe-check gjenbrukes ikke når CI er blitt rød på samme commit`() =
        runBlocking<Unit> {
            assess()
            gh.labels shouldContainExactly setOf(APPROVED_LABEL)
            gh.ciRuns.clear()
            gh.ciRuns += CheckRunInfo(2, "test", "completed", "failure", 12, GITHUB_ACTIONS_APP_ID, null)
            assess()
            reviewer.calls shouldBe 1
            gh.ownRuns.getValue(HEAD).externalId shouldBe "ci-failed"
            gh.labels.shouldBeEmpty()
        }

    @Test
    fun `rød CI gir neutral check uten label og uten modellkall`() =
        runBlocking<Unit> {
            gh.labels += NEEDS_HUMAN_LABEL
            gh.ciRuns.clear()
            gh.ciRuns += CheckRunInfo(1, "test", "completed", "failure", 11, GITHUB_ACTIONS_APP_ID, null)
            assess()
            reviewer.calls shouldBe 0
            val check = gh.ownRuns.getValue(HEAD)
            check.status shouldBe "completed"
            check.conclusion shouldBe "neutral"
            check.externalId shouldBe "ci-failed"
            gh.labels.shouldBeEmpty()
            gh.comments.shouldBeEmpty()
        }

    @Test
    fun `ventende CI gir in_progress uten modellkall`() =
        runBlocking<Unit> {
            gh.ciRuns.clear()
            gh.ciRuns += CheckRunInfo(1, "test", "in_progress", null, 11, GITHUB_ACTIONS_APP_ID, null)
            assess()
            reviewer.calls shouldBe 0
            gh.ownRuns.getValue(HEAD).status shouldBe "in_progress"
            gh.contentReads.shouldBeEmpty()
        }

    @Test
    fun `konfig leses fra baseSha, ikke headSha`() =
        runBlocking<Unit> {
            gh.contents[".github/idun.yaml" to BASE] = "maxChangedLines: 1\n"
            gh.contents[".github/idun.yaml" to HEAD] = "maxChangedLines: 50\n"
            assess()
            gh.contentReads shouldContain (".github/idun.yaml" to BASE)
            gh.contentReads shouldNotContain (".github/idun.yaml" to HEAD)
            reviewer.calls shouldBe 0
            gh.ownRuns.getValue(HEAD).summary shouldContain "TOO_LARGE"
        }

    @Test
    fun `closed logger feedback med reaksjoner`() =
        runBlocking<Unit> {
            gh.pr = gh.pr.copy(state = "closed", merged = true)
            gh.ownRuns[HEAD] = CheckRunWrite("completed", "success", "Trygg refaktorering", "x", "safe:0123456789abcdef")
            gh.comments += IssueComment(55, "en-person", "$COMMENT_MARKER kopiert")
            gh.comments += IssueComment(56, APP_LOGIN, "$COMMENT_MARKER\n**Idun**: trygg")
            gh.reactions[56] = Reactions(thumbsUp = 2, thumbsDown = 1)
            assess()
            val line = log.single("idun.feedback")
            line shouldContain "repo=entur/idun-sandbox"
            line shouldContain "pr=7"
            line shouldContain "sha=$HEAD"
            line shouldContain "merged=true"
            line shouldContain "outcome=safe"
            line shouldContain "thumbsUp=2"
            line shouldContain "thumbsDown=1"
            gh.writes.shouldBeEmpty()
            reviewer.calls shouldBe 0
        }

    @Test
    fun `unntak fra GitHub gir neutral feil-check`() =
        runBlocking<Unit> {
            gh.onCall = { if (it == "compareFiles") throw GitHubApiException(502, "Bad gateway") }
            assess()
            val check = gh.ownRuns.getValue(HEAD)
            check.status shouldBe "completed"
            check.conclusion shouldBe "neutral"
            check.title shouldBe "Feil – trenger et menneske"
            check.externalId shouldBe "error"
            log.single("idun.error") shouldContain "status=502"
            reviewer.calls shouldBe 0
        }

    @Test
    fun `feillogg inneholder ikke token`() =
        runBlocking<Unit> {
            gh.onCall = { if (it == "mergeBase") throw IllegalStateException("Authorization: token ghs_hemmelig123 avvist") }
            assess()
            val all = log.all()
            all shouldContain "idun.error"
            all shouldNotContain "ghs_hemmelig123"
        }

    @Test
    fun `idun-assessed logger utfall, grunner og kostnad, men ikke modellens tekst`() =
        runBlocking<Unit> {
            assess()
            val line = log.single("idun.assessed")
            line shouldContain "outcome=safe"
            line shouldContain "mode=advise"
            line shouldContain "sha=$HEAD"
            line shouldContain "costUsd=0.25"
            log.all() shouldNotContain validResponse().summary
        }

    @Test
    fun `modellen som feiler gir needs-human med REVIEW_FAILED`() =
        runBlocking<Unit> {
            reviewer.result = ReviewResult.Failed("tidsgrense")
            mode = Mode.APPROVE
            assess()
            gh.ownRuns.getValue(HEAD).externalId shouldBe "needs-human"
            gh.labels shouldContainExactly setOf(NEEDS_HUMAN_LABEL)
            gh.reviews.shouldBeEmpty()
            log.single("idun.assessed") shouldContain "REVIEW_FAILED"
            // En feilet modell gjenbrukes ikke: neste hendelse prøver igjen.
            assess()
            reviewer.calls shouldBe 2
        }

    @Test
    fun `ny push trekker tilbake godkjenning av forrige commit`() =
        runBlocking<Unit> {
            gh.reviews += Review(9, APP_LOGIN, "APPROVED", "ok ${approvalOf("old")}")
            assess()
            gh.reviews.single { it.id == 9L }.state shouldBe "DISMISSED"
        }

    @Test
    fun `godkjenning et menneske har trukket tilbake gis ikke på nytt for samme commit`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            assess()
            gh.reviews.single().state shouldBe "APPROVED"
            // Et menneske trekker godkjenningen tilbake.
            gh.reviews[0] = gh.reviews[0].copy(state = "DISMISSED")

            gh.prsForCommit[HEAD] = listOf(7)
            assessor.onCommit(installationId = 1, repo = REPO, sha = HEAD)

            gh.reviews.map { it.state } shouldContainExactly listOf("DISMISSED")
            gh.writes.count { it == "approve $HEAD" } shouldBe 1
            log.single("idun.approval_vetoed") shouldContain "sha=$HEAD"
        }

    @Test
    fun `tilbaketrukket godkjenning uten markør, men med commit_id, gis ikke på nytt`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            gh.reviews += Review(9, APP_LOGIN, "DISMISSED", "Godkjent", commitId = HEAD)
            assess()
            gh.reviews.none { it.state == "APPROVED" } shouldBe true
            log.single("idun.approval_vetoed") shouldContain "pr=7"
        }

    @Test
    fun `tilbaketrukket godkjenning av en annen commit stopper ikke godkjenning av head`() =
        runBlocking<Unit> {
            mode = Mode.APPROVE
            gh.reviews += Review(9, APP_LOGIN, "DISMISSED", "ok ${approvalOf("old")}", commitId = "old")
            gh.reviews += Review(10, "en-person", "DISMISSED", "ok ${approvalOf(HEAD)}", commitId = HEAD)
            assess()
            gh.reviews.single { it.state == "APPROVED" }.commitId shouldBe HEAD
        }

    @Test
    fun `onCommit vurderer PR-ene til commiten`() =
        runBlocking<Unit> {
            gh.prsForCommit[HEAD] = listOf(7)
            assessor.onCommit(installationId = 1, repo = REPO, sha = HEAD)
            reviewer.calls shouldBe 1
            gh.ownRuns.getValue(HEAD).externalId shouldStartWith "safe:"
        }
}
