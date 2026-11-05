package org.entur.idun.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.entur.idun.ci.GITHUB_ACTIONS_APP_ID
import org.entur.idun.ci.GITHUB_ACTIONS_LOGIN
import org.entur.idun.decide.Mode
import org.entur.idun.github.GitHubApiException
import org.entur.idun.github.RepoRef
import org.entur.idun.server.FakeGitHub
import org.entur.idun.server.FakeReviewer
import org.entur.idun.server.HEAD
import org.entur.idun.server.REPO
import org.junit.jupiter.api.Test

class AssessMainTest {
    @Test
    fun `gyldige argumenter gir approve som standard`() {
        val args = parseArgs(arrayOf("entur/idun-sandbox", "12"))
        args.repo shouldBe RepoRef("entur", "idun-sandbox")
        args.number shouldBe 12
        args.mode shouldBe Mode.APPROVE
        args.write shouldBe false
    }

    @Test
    fun `write gir advise som standard`() {
        val args = parseArgs(arrayOf("entur/idun-sandbox", "12", "--write"))
        args.write shouldBe true
        args.mode shouldBe Mode.ADVISE
    }

    @Test
    fun `write med mode approve godkjenner bare når det er bedt om`() {
        parseArgs(arrayOf("entur/idun-sandbox", "12", "--write", "--mode", "approve")).mode shouldBe Mode.APPROVE
        parseArgs(arrayOf("entur/idun-sandbox", "--mode", "approve", "12", "--write")).mode shouldBe Mode.APPROVE
    }

    @Test
    fun `write krever GITHUB_TOKEN og faller ikke tilbake til gh auth token`() {
        var askedGh = false
        val gh = { askedGh = true; "gho_personlig" }
        shouldThrow<UsageException> { githubToken(write = true, env = { null }, ghAuthToken = gh) }.message shouldContain "GITHUB_TOKEN"
        shouldThrow<UsageException> { githubToken(write = true, env = { " " }, ghAuthToken = gh) }.message shouldContain "GITHUB_TOKEN"
        askedGh shouldBe false
        githubToken(write = true, env = { if (it == "GITHUB_TOKEN") "ghs_workflow" else null }, ghAuthToken = gh) shouldBe "ghs_workflow"
        askedGh shouldBe false
    }

    @Test
    fun `tørrkjøring tar GITHUB_TOKEN, ellers gh auth token`() {
        githubToken(write = false, env = { "ghs_env" }, ghAuthToken = { "gho_gh" }) shouldBe "ghs_env"
        githubToken(write = false, env = { null }, ghAuthToken = { "gho_gh" }) shouldBe "gho_gh"
        shouldThrow<UsageException> { githubToken(write = false, env = { null }, ghAuthToken = { null }) }.message shouldContain "gh auth login"
    }

    @Test
    fun `mode advise kan velges`() {
        parseArgs(arrayOf("entur/idun", "1", "--mode", "advise")).mode shouldBe Mode.ADVISE
    }

    @Test
    fun `ugyldig repo gir tydelig feil`() {
        shouldThrow<UsageException> { parseArgs(arrayOf("idun", "1")) }.message shouldContain "owner/repo"
        shouldThrow<UsageException> { parseArgs(arrayOf("a/b/c", "1")) }.message shouldContain "owner/repo"
    }

    @Test
    fun `ugyldig PR-nummer gir tydelig feil`() {
        shouldThrow<UsageException> { parseArgs(arrayOf("entur/idun", "abc")) }.message shouldContain "PR-nummer"
        shouldThrow<UsageException> { parseArgs(arrayOf("entur/idun", "0")) }.message shouldContain "PR-nummer"
    }

    @Test
    fun `ukjent flagg og ugyldig modus gir tydelig feil`() {
        shouldThrow<UsageException> { parseArgs(arrayOf("entur/idun", "1", "--force")) }.message shouldContain "--force"
        shouldThrow<UsageException> { parseArgs(arrayOf("entur/idun", "1", "--mode", "off")) }.message shouldContain "approve|advise"
        shouldThrow<UsageException> { parseArgs(arrayOf("entur/idun", "1", "--mode")) }.message shouldContain "--mode"
        shouldThrow<UsageException> { parseArgs(arrayOf("entur/idun")) }.message shouldContain "Bruk"
    }

    @Test
    fun `trygg refaktorering i approve gir ville GODKJENT og ingen ekte skriving`() =
        runBlocking<Unit> {
            val fake = FakeGitHub()
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()

            val code = runDryRun(fake, FakeReviewer(), AssessArgs(REPO, 7, Mode.APPROVE), "gemini-test", { out += it }, { err += it })

            code shouldBe 0
            fake.writes.shouldBeEmpty()
            out.any { it.contains("ville GODKJENT") } shouldBe true
            out.any { it.contains("Idun ville godkjenne") } shouldBe true
        }

    @Test
    fun `feil i vurderingen gir exit-kode ulik null`() =
        runBlocking<Unit> {
            val fake = FakeGitHub()
            fake.onCall = { if (it == "compareFiles") throw GitHubApiException(500, "boom") }
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()

            val code = runDryRun(fake, FakeReviewer(), AssessArgs(REPO, 7, Mode.APPROVE), "gemini-test", { out += it }, { err += it })

            code shouldBe 1
            err.joinToString("\n") shouldContain "idun.error"
            fake.writes.shouldBeEmpty()
        }

    @Test
    fun `write skriver på ekte som github-actions`() =
        runBlocking<Unit> {
            val fake = FakeGitHub(ownAppId = GITHUB_ACTIONS_APP_ID, appLogin = GITHUB_ACTIONS_LOGIN)
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()

            val code = runWrite(fake, FakeReviewer(), AssessArgs(REPO, 7, Mode.APPROVE, write = true), "gemini-test", { out += it }, { err += it })

            code shouldBe 0
            fake.reviews.single().login shouldBe GITHUB_ACTIONS_LOGIN
            fake.writes.any { it.startsWith("approve") } shouldBe true
            out.none { it.contains("[tørrkjøring]") } shouldBe true
            out.joinToString("\n") shouldContain "godkjent"
        }

    @Test
    fun `write i advise godkjenner ikke`() =
        runBlocking<Unit> {
            val fake = FakeGitHub(ownAppId = GITHUB_ACTIONS_APP_ID, appLogin = GITHUB_ACTIONS_LOGIN)
            val code = runWrite(fake, FakeReviewer(), AssessArgs(REPO, 7, Mode.ADVISE, write = true), "gemini-test", {}, {})

            code shouldBe 0
            fake.reviews.shouldBeEmpty()
            fake.ownRuns.getValue(HEAD).conclusion shouldBe "success"
        }

    @Test
    fun `feil i write gir exit-kode ulik null`() =
        runBlocking<Unit> {
            val fake = FakeGitHub(ownAppId = GITHUB_ACTIONS_APP_ID, appLogin = GITHUB_ACTIONS_LOGIN)
            fake.onCall = { if (it == "compareFiles") throw GitHubApiException(500, "boom") }
            val err = mutableListOf<String>()

            val code = runWrite(fake, FakeReviewer(), AssessArgs(REPO, 7, Mode.APPROVE, write = true), "gemini-test", {}, { err += it })

            code shouldBe 1
            err.joinToString("\n") shouldContain "idun.error"
        }
}
