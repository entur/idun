package org.entur.idun.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.entur.idun.decide.Mode
import org.entur.idun.github.GitHubApiException
import org.entur.idun.github.RepoRef
import org.entur.idun.server.FakeGitHub
import org.entur.idun.server.FakeReviewer
import org.entur.idun.server.REPO
import org.junit.jupiter.api.Test

class AssessMainTest {
    @Test
    fun `gyldige argumenter gir approve som standard`() {
        val args = parseArgs(arrayOf("entur/idun-sandbox", "12"))
        args.repo shouldBe RepoRef("entur", "idun-sandbox")
        args.number shouldBe 12
        args.mode shouldBe Mode.APPROVE
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
}
