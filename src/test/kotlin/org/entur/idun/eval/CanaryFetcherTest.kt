package org.entur.idun.eval

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.entur.idun.rules.FileStatus
import org.entur.idun.rules.ReasonCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir

class CanaryFetcherTest {
    private val base = "a".repeat(40)
    private val head = "b".repeat(40)
    private val mergeBase = "c".repeat(40)
    private val source = CanarySource("entur/products-api", 525)

    /** Svarer som `gh` ville gjort, og husker kommandoene. */
    private class FakeGh(
        val answer: (List<String>) -> CommandResult,
    ) : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>): CommandResult {
            commands += command
            return answer(command)
        }
    }

    private fun ok(text: String) = CommandResult(0, text.toByteArray(), "")

    private val notFound = CommandResult(1, ByteArray(0), "gh: Not Found (HTTP 404)\n")

    private fun view(files: String) = ok("""{"baseRefOid":"$base","headRefOid":"$head","files":$files}""")

    private fun fakeGh(
        files: String,
        contents: Map<Pair<String, String>, String>,
    ) = FakeGh { cmd ->
        when {
            cmd.take(3) == listOf("gh", "pr", "view") -> view(files)
            cmd.any { "/compare/" in it } -> ok("$mergeBase\n")
            else -> {
                val url = cmd.last()
                val path = url.substringAfter("/contents/").substringBefore("?ref=")
                val ref = url.substringAfter("?ref=")
                contents[path to ref]?.let { ok(it) } ?: notFound
            }
        }
    }

    @Test
    fun `henter filene fra merge-base og head med gh og gir GitHubs linjetall`(
        @TempDir dir: Path,
    ) {
        val gh =
            fakeGh(
                """[{"path":"src/A.kt","additions":1,"deletions":1,"changeType":"MODIFIED"},""" +
                    """{"path":"src/New.kt","additions":1,"deletions":0,"changeType":"ADDED"},""" +
                    """{"path":"src/Gone.kt","additions":0,"deletions":1,"changeType":"DELETED"}]""",
                mapOf(
                    ("src/A.kt" to mergeBase) to "val a = 1\n",
                    ("src/A.kt" to head) to "val a = 2\n",
                    ("src/New.kt" to head) to "val n = 1\n",
                    ("src/Gone.kt" to mergeBase) to "val g = 1\n",
                ),
            )
        val counts = GhCanaryFetcher(gh).fetch(source, dir)

        counts shouldBe
            mapOf(
                "src/A.kt" to LineCounts(1, 1),
                "src/New.kt" to LineCounts(1, 0),
                "src/Gone.kt" to LineCounts(0, 1),
            )
        dir.resolve("before/src/A.kt").readText() shouldBe "val a = 1\n"
        dir.resolve("after/src/A.kt").readText() shouldBe "val a = 2\n"
        dir.resolve("after/src/New.kt").readText() shouldBe "val n = 1\n"
        dir.resolve("before/src/New.kt").exists() shouldBe false
        dir.resolve("before/src/Gone.kt").readText() shouldBe "val g = 1\n"
        dir.resolve("after/src/Gone.kt").exists() shouldBe false
        gh.commands.first() shouldContainExactly
            listOf("gh", "pr", "view", "525", "-R", "entur/products-api", "--json", "baseRefOid,headRefOid,files")
        gh.commands[1] shouldContainExactly
            listOf("gh", "api", "repos/entur/products-api/compare/$base...$head", "--jq", ".merge_base_commit.sha")
        gh.commands.drop(2).all { it.take(4) == listOf("gh", "api", "-H", "Accept: application/vnd.github.raw") } shouldBe true
    }

    @Test
    fun `gh som ikke er logget inn gir CanaryFetchException med grunnen`(
        @TempDir dir: Path,
    ) {
        val gh = FakeGh { CommandResult(4, ByteArray(0), "To get started with GitHub CLI, please run:  gh auth login\n") }
        val e = shouldThrow<CanaryFetchException> { GhCanaryFetcher(gh).fetch(source, dir) }
        e.message!! shouldContain "gh auth login"
        e.message!! shouldContain "exit 4"
    }

    @Test
    fun `gh som mangler gir CanaryFetchException`(
        @TempDir dir: Path,
    ) {
        val e = shouldThrow<CanaryFetchException> { GhCanaryFetcher { throw IOException("Cannot run program \"gh\"") }.fetch(source, dir) }
        e.message!! shouldContain "gh er ikke tilgjengelig"
    }

    @Test
    fun `andre feil enn 404 ved henting av innhold kaster`(
        @TempDir dir: Path,
    ) {
        val gh =
            FakeGh { cmd ->
                when {
                    cmd.take(3) == listOf("gh", "pr", "view") -> view("""[{"path":"src/A.kt","additions":1,"deletions":1,"changeType":"MODIFIED"}]""")
                    cmd.any { "/compare/" in it } -> ok(mergeBase)
                    else -> CommandResult(1, ByteArray(0), "gh: Forbidden (HTTP 403)")
                }
            }
        shouldThrow<CanaryFetchException> { GhCanaryFetcher(gh).fetch(source, dir) }.message!! shouldContain "HTTP 403"
    }

    @Test
    fun `utrygge filstier og omdøpte filer avvises`(
        @TempDir dir: Path,
    ) {
        for (path in listOf("../escape.kt", "/abs.kt", "src/./A.kt", "src/A\nB.kt")) {
            val files = """[{"path":${kotlinx.serialization.json.JsonPrimitive(path)},"additions":1,"deletions":0,"changeType":"ADDED"}]"""
            shouldThrow<CanaryFetchException> { GhCanaryFetcher(fakeGh(files, emptyMap())).fetch(source, dir) }
        }
        val renamed = """[{"path":"src/B.kt","additions":0,"deletions":0,"changeType":"RENAMED"}]"""
        shouldThrow<CanaryFetchException> { GhCanaryFetcher(fakeGh(renamed, emptyMap())).fetch(source, dir) }.message!! shouldContain
            "RENAMED"
        dir.parent.resolve("escape.kt").exists() shouldBe false
    }

    @Test
    fun `loadCanaries henter kanarifugler med source og hopper over dem som feiler`(
        @TempDir root: Path,
        @TempDir work: Path,
    ) {
        root.resolve("ok").createDirectories().resolve("meta.json").writeText(
            """{"title":"refactor: x","expected":"any","source":{"repo":"entur/products-api","pr":1},"rules":[]}""",
        )
        root.resolve("feiler").createDirectories().resolve("meta.json").writeText(
            """{"title":"refactor: y","expected":"any","source":{"repo":"entur/products-api","pr":2}}""",
        )
        val fetcher =
            CanaryFetcher { src, into ->
                if (src.pr == 2) throw CanaryFetchException("ingen tilgang")
                into.resolve("before/src/main/kotlin/A.kt").apply { parent.createDirectories() }.writeText("val a = 1\n")
                into.resolve("after/src/main/kotlin/A.kt").apply { parent.createDirectories() }.writeText("val a = 2\n")
                mapOf("src/main/kotlin/A.kt" to LineCounts(1, 1))
            }

        val loaded = loadCanaries(root, fetcher, work).associateBy { it.name }

        loaded.getValue("feiler").shouldBeInstanceOf<LoadedCanary.Skipped>().reason shouldBe "ingen tilgang"
        val canary = loaded.getValue("ok").shouldBeInstanceOf<LoadedCanary.Ready>().canary
        canary.files.single().status shouldBe FileStatus.MODIFIED
        canary.expectedRules shouldBe emptyList<ReasonCode>()
        ruleMismatches(canary) shouldBe emptyList()
        // Innholdet havner i arbeidsmappen, ikke ved siden av meta.json.
        root.resolve("ok/before").exists() shouldBe false
    }

    /**
     * Henter de ekte PR-ene med `gh` og sjekker reglene og linjetallene mot `meta.json`, uten modellen. Trenger
     * nettverk og `gh auth login` med tilgang til kilderepoet, så den kjører bare med `IDUN_CANARY_FETCH=1`.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "IDUN_CANARY_FETCH", matches = "1")
    fun `kanarifugler fra ekte PR-er hentes med gh og stemmer med reglene`(
        @TempDir work: Path,
    ) {
        val loaded = loadCanaries(Path.of("eval/canaries"), GhCanaryFetcher(), work)
        loaded.filterIsInstance<LoadedCanary.Skipped>() shouldBe emptyList()
        val fetched = loaded.filterIsInstance<LoadedCanary.Ready>().map { it.canary }.filter { it.githubLineCounts != null }
        fetched.map { it.name } shouldContainExactly listOf("pr-396", "pr-525", "pr-526")
        fetched.associate { it.name to ruleMismatches(it) } shouldBe fetched.associate { it.name to emptyList<String>() }
    }
}
