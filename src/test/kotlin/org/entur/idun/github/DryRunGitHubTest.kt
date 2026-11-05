package org.entur.idun.github

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class DryRunGitHubTest {
    private val repo = RepoRef("entur", "idun-sandbox")
    private val delegate = mockk<GitHub>()
    private val printed = mutableListOf<String>()
    private val dry = DryRunGitHub(delegate) { printed += it }

    @Test
    fun `lesing går til delegaten`() =
        runBlocking<Unit> {
            val comments = listOf(IssueComment(1, "x", "hei"))
            coEvery { delegate.comments(repo, 7) } returns comments
            coEvery { delegate.fileContent(repo, "a.kt", "sha") } returns null

            dry.comments(repo, 7) shouldBe comments
            dry.fileContent(repo, "a.kt", "sha") shouldBe null

            coVerify(exactly = 1) { delegate.comments(repo, 7) }
            coVerify(exactly = 1) { delegate.fileContent(repo, "a.kt", "sha") }
            confirmVerified(delegate)
            dry.writes.size shouldBe 0
        }

    @Test
    fun `skriving kaller aldri delegaten`() =
        runBlocking<Unit> {
            dry.upsertCheckRun(repo, "abc", "idun/refactor", CheckRunWrite("completed", "success", "Trygg refaktorering", "s", "safe"))
            dry.createComment(repo, 7, "innhold")
            dry.updateComment(repo, 5, "nytt")
            dry.ensureLabel(repo, "idun:approved", "00ff00", "d")
            dry.addLabel(repo, 7, "idun:approved")
            dry.removeLabel(repo, 7, "idun:needs-human")
            dry.approve(repo, 7, "abc", "ok")
            dry.dismissReview(repo, 7, 9, "trukket")

            confirmVerified(delegate)
            dry.writes shouldHaveSize 8
        }

    @Test
    fun `skrivinger registreres i rekkefølge og skrives ut på norsk`() =
        runBlocking<Unit> {
            dry.upsertCheckRun(repo, "abc", "idun/refactor", CheckRunWrite("completed", "success", "Trygg refaktorering", "s", "safe"))
            val id = dry.createComment(repo, 7, "linje 1\nlinje 2")
            dry.addLabel(repo, 7, "idun:approved")
            dry.approve(repo, 7, "abc", "ok")

            dry.writes[0] shouldBe "[tørrkjøring] check idun/refactor: completed/success – \"Trygg refaktorering\""
            dry.writes[1] shouldBe "[tørrkjøring] kommentar:\nlinje 1\nlinje 2"
            dry.writes[2] shouldBe "[tørrkjøring] label +idun:approved"
            dry.writes[3] shouldBe "[tørrkjøring] ville GODKJENT abc"
            printed shouldContainExactly dry.writes
            (id > 0) shouldBe true
            dry.wouldApprove shouldBe true
        }

    @Test
    fun `label fjernes og kommentar oppdateres med egne linjer`() =
        runBlocking<Unit> {
            dry.removeLabel(repo, 7, "x")
            dry.updateComment(repo, 5, "ny tekst")
            dry.dismissReview(repo, 7, 9, "grunn")

            dry.writes[0] shouldBe "[tørrkjøring] label -x"
            dry.writes[1] shouldStartWith "[tørrkjøring] oppdatert kommentar"
            dry.writes[1] shouldContain "ny tekst"
            dry.writes[2] shouldStartWith "[tørrkjøring] ville trukket godkjenning"
            dry.wouldApprove shouldBe false
        }
}
