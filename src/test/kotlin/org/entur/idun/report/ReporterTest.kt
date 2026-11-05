package org.entur.idun.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.entur.idun.github.GitHub
import org.entur.idun.github.GitHubApiException
import org.entur.idun.github.IssueComment
import org.entur.idun.github.RepoRef
import org.entur.idun.github.Review
import org.junit.jupiter.api.Test

class ReporterTest {
    private val repo = RepoRef("entur", "products-api")
    private val gh = mockk<GitHub>(relaxed = true)
    private val reporter = Reporter(gh, "idun[bot]")

    private fun approval(
        id: Long,
        login: String,
        sha: String?,
        state: String = "APPROVED",
    ) = Review(id, login, state, if (sha == null) "Godkjent" else "ok <!-- idun:approval sha=$sha -->")

    @Test
    fun `dismissApprovals trekker bare egne godkjenninger av andre commits`() =
        runBlocking<Unit> {
            coEvery { gh.reviews(repo, 1) } returns
                listOf(
                    approval(10, "idun[bot]", "a"),
                    approval(11, "idun[bot]", "b"),
                    approval(12, "stunor", "a"),
                    approval(13, "idun[bot]", "a", state = "COMMENTED"),
                    approval(14, "idun[bot]", "a", state = "DISMISSED"),
                )
            reporter.dismissApprovals(repo, 1, keepSha = "b")
            coVerify(exactly = 1) { gh.dismissReview(repo, 1, 10, any()) }
            coVerify(exactly = 1) { gh.dismissReview(any(), any(), any(), any()) }
        }

    @Test
    fun `dismissApprovals med null trekker alle egne tilbake`() =
        runBlocking<Unit> {
            coEvery { gh.reviews(repo, 1) } returns
                listOf(approval(10, "idun[bot]", "a"), approval(11, "idun[bot]", "b"), approval(12, "stunor", "b"))
            reporter.dismissApprovals(repo, 1, keepSha = null)
            coVerify(exactly = 1) { gh.dismissReview(repo, 1, 10, any()) }
            coVerify(exactly = 1) { gh.dismissReview(repo, 1, 11, any()) }
            coVerify(exactly = 2) { gh.dismissReview(any(), any(), any(), any()) }
        }

    @Test
    fun `dismissApprovals trekker tilbake egen godkjenning uten lesbar markor`() =
        runBlocking<Unit> {
            coEvery { gh.reviews(repo, 1) } returns
                listOf(
                    approval(10, "idun[bot]", null),
                    Review(11, "idun[bot]", "APPROVED", "<!-- idun:approval sha= -->"),
                    Review(12, "idun[bot]", "APPROVED", "<!-- idun:approval sha=b -->"),
                )
            reporter.dismissApprovals(repo, 1, keepSha = "b")
            coVerify(exactly = 1) { gh.dismissReview(repo, 1, 10, any()) }
            coVerify(exactly = 1) { gh.dismissReview(repo, 1, 11, any()) }
            coVerify(exactly = 2) { gh.dismissReview(any(), any(), any(), any()) }
        }

    @Test
    fun `dismissApprovals prøver alle og kaster etterpå når én feiler`() =
        runBlocking<Unit> {
            coEvery { gh.reviews(repo, 1) } returns
                listOf(approval(10, "idun[bot]", "a"), approval(11, "idun[bot]", "b"), approval(12, "idun[bot]", "c"))
            coEvery { gh.dismissReview(repo, 1, 10, any()) } throws GitHubApiException(502, "Bad gateway")
            coEvery { gh.dismissReview(repo, 1, 12, any()) } throws GitHubApiException(500, "boom")
            val e = shouldThrow<IllegalStateException> { reporter.dismissApprovals(repo, 1, keepSha = null) }
            coVerify(exactly = 1) { gh.dismissReview(repo, 1, 10, any()) }
            coVerify(exactly = 1) { gh.dismissReview(repo, 1, 11, any()) }
            coVerify(exactly = 1) { gh.dismissReview(repo, 1, 12, any()) }
            e.message shouldContain "10"
            e.message shouldContain "12"
            e.message shouldContain "502"
        }

    @Test
    fun `approvalState skiller gjeldende, tilbaketrukket og ingen egen godkjenning`() =
        runBlocking<Unit> {
            coEvery { gh.reviews(repo, 1) } returns
                listOf(
                    approval(10, "idun[bot]", "a"),
                    approval(11, "idun[bot]", "b", state = "DISMISSED"),
                    Review(12, "idun[bot]", "DISMISSED", "uten markor", commitId = "c"),
                    approval(13, "stunor", "d", state = "DISMISSED"),
                    approval(14, "idun[bot]", "a", state = "COMMENTED"),
                )
            reporter.approvalState(repo, 1, "a") shouldBe ApprovalState.APPROVED
            reporter.approvalState(repo, 1, "b") shouldBe ApprovalState.VETOED
            reporter.approvalState(repo, 1, "c") shouldBe ApprovalState.VETOED
            reporter.approvalState(repo, 1, "d") shouldBe ApprovalState.NONE
            reporter.approvalState(repo, 1, "e") shouldBe ApprovalState.NONE
        }

    @Test
    fun `approve sender markor med sha`() =
        runBlocking<Unit> {
            val body = slot<String>()
            coEvery { gh.approve(repo, 1, "abc123", capture(body)) } returns Unit
            reporter.approve(repo, 1, "abc123")
            body.captured shouldContain "<!-- idun:approval sha=abc123 -->"
        }

    @Test
    fun `writeComment oppdaterer eksisterende kommentar med markor`() =
        runBlocking<Unit> {
            coEvery { gh.comments(repo, 1) } returns
                listOf(
                    IssueComment(5, "stunor", "hei"),
                    IssueComment(6, "stunor", "<!-- idun:comment --> falsk"),
                    IssueComment(7, "idun[bot]", "<!-- idun:comment -->\ngammel"),
                )
            reporter.writeComment(repo, 1, "<!-- idun:comment -->\nny")
            coVerify(exactly = 1) { gh.updateComment(repo, 7, "<!-- idun:comment -->\nny") }
            coVerify(exactly = 0) { gh.createComment(any(), any(), any()) }
        }

    @Test
    fun `writeComment oppretter ny kommentar nar ingen har markor`() =
        runBlocking<Unit> {
            coEvery { gh.comments(repo, 1) } returns listOf(IssueComment(5, "idun[bot]", "ingen markor"))
            coEvery { gh.createComment(repo, 1, any()) } returns 9L
            reporter.writeComment(repo, 1, "<!-- idun:comment -->\nny")
            coVerify(exactly = 1) { gh.createComment(repo, 1, "<!-- idun:comment -->\nny") }
            coVerify(exactly = 0) { gh.updateComment(any(), any(), any()) }
        }

    @Test
    fun `setLabel legger til onsket label og fjerner den motsatte`() =
        runBlocking<Unit> {
            reporter.setLabel(repo, 1, APPROVED_LABEL)
            coVerify { gh.ensureLabel(repo, "idun:approved", "1a8e60", "idun: trygg refaktorering") }
            coVerify { gh.ensureLabel(repo, "idun:needs-human", "ff5959", "idun: trenger et menneske") }
            coVerifyOrder {
                gh.addLabel(repo, 1, "idun:approved")
                gh.removeLabel(repo, 1, "idun:needs-human")
            }
            coVerify(exactly = 0) { gh.removeLabel(repo, 1, "idun:approved") }
        }

    @Test
    fun `setLabel med null fjerner begge`() =
        runBlocking<Unit> {
            reporter.setLabel(repo, 1, null)
            coVerify(exactly = 1) { gh.removeLabel(repo, 1, "idun:approved") }
            coVerify(exactly = 1) { gh.removeLabel(repo, 1, "idun:needs-human") }
            coVerify(exactly = 0) { gh.addLabel(any(), any(), any()) }
        }

    @Test
    fun `setLabel avviser ukjent label`() =
        runBlocking<Unit> {
            shouldThrow<IllegalArgumentException> { reporter.setLabel(repo, 1, "annet") }
            coVerify(exactly = 0) { gh.addLabel(any(), any(), any()) }
        }
}
