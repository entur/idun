package org.entur.idun.eval

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.entur.idun.config.IdunConfig
import org.entur.idun.rules.FileContents
import org.entur.idun.rules.FileStatus
import org.entur.idun.rules.evaluateFiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CanaryRulesTest {
    private val synthetic =
        listOf(
            "value-of-to-first-or-null",
            "sort-order-changed",
            "column-name-in-string",
            "class-to-data-class",
            "extract-function",
        )

    // Uten henter: kanarifugler fra ekte PR-er hoppes over, så testen trenger ikke nettverk.
    private val loaded = loadCanaries(Path.of("eval/canaries")).associateBy { it.name }

    private val canaries =
        loaded.values.filterIsInstance<LoadedCanary.Ready>().associate { it.name to it.canary }

    private fun rules(name: String) =
        canaries.getValue(name).let { evaluateFiles(it.files, it.contents, IdunConfig.DEFAULT) }

    @Test
    fun `syntetiske kanarifugler lastes, de fra ekte PR-er hoppes over uten henter`() {
        canaries.keys shouldContainExactlyInAnyOrder synthetic
        loaded.values.filterIsInstance<LoadedCanary.Skipped>().map { it.name } shouldContainExactlyInAnyOrder
            listOf("pr-525", "pr-526", "pr-396")
        (loaded.getValue("pr-525") as LoadedCanary.Skipped).reason shouldContain "entur/products-api#525"
    }

    @Test
    fun `kanarifugler fra ekte PR-er har bare meta-json i repoet`() {
        for (name in listOf("pr-525", "pr-526", "pr-396")) {
            Path.of("eval/canaries", name).toFile().list()!!.toList() shouldBe listOf("meta.json")
        }
    }

    @Test
    fun `syntetiske kanarifugler passerer reglene så de når modellen`() {
        synthetic.forEach { name ->
            val result = rules(name)
            result.reasons shouldBe emptyList()
            result.changedLines shouldBeLessThanOrEqual 50
        }
    }

    @Test
    fun `filstatus utledes fra hvilke sider som finnes`(
        @TempDir dir: Path,
    ) {
        dir.resolve("meta.json").writeText("""{"title": "refactor: x", "expected": "any"}""")
        dir.resolve("before/src/main/kotlin/Gone.kt").apply { parent.createDirectories() }.writeText("class Gone\n")
        dir.resolve("after/src/main/kotlin/New.kt").apply { parent.createDirectories() }.writeText("class New\nclass Other\n")
        for (side in listOf("before", "after")) {
            dir.resolve("$side/src/main/kotlin/Both.kt").writeText(if (side == "before") "val a = 1\n" else "val a = 2\n")
        }

        val canary = loadCanary(dir)

        val byPath = canary.files.associateBy { it.path }
        byPath.getValue("src/main/kotlin/Gone.kt").let {
            it.status shouldBe FileStatus.REMOVED
            it.deletions shouldBe 1
            it.additions shouldBe 0
        }
        byPath.getValue("src/main/kotlin/New.kt").let {
            it.status shouldBe FileStatus.ADDED
            it.additions shouldBe 2
            it.deletions shouldBe 0
        }
        byPath.getValue("src/main/kotlin/Both.kt").status shouldBe FileStatus.MODIFIED
        canary.contents.getValue("src/main/kotlin/New.kt") shouldBe FileContents(null, "class New\nclass Other\n")
        canary.contents.getValue("src/main/kotlin/Gone.kt") shouldBe FileContents("class Gone\n", null)
        canary.expected shouldBe Expected.ANY
    }

    // --- regel- og linjetallssjekken som kjøres når evalen henter ekte PR-er ---

    private fun canaryIn(
        dir: Path,
        meta: String,
        before: String,
        after: String,
    ): Canary {
        dir.resolve("meta.json").writeText(meta)
        dir.resolve("before/src/main/kotlin/A.kt").apply { parent.createDirectories() }.writeText(before)
        dir.resolve("after/src/main/kotlin/A.kt").apply { parent.createDirectories() }.writeText(after)
        return loadCanary(dir)
    }

    @Test
    fun `ruleMismatches melder avvik fra forventede regler`(
        @TempDir dir: Path,
    ) {
        val canary = canaryIn(dir, """{"title":"refactor: x","expected":"any","rules":["TOO_LARGE"]}""", "val a = 1\n", "val a = 2\n")
        ruleMismatches(canary).single() shouldContain "reglene ga [], forventet [TOO_LARGE]"
        ruleMismatches(canary.copy(expectedRules = emptyList())) shouldBe emptyList()
        ruleMismatches(canary.copy(expectedRules = null)) shouldBe emptyList()
    }

    @Test
    fun `ruleMismatches melder linjetall som ikke stemmer med GitHub`(
        @TempDir dir: Path,
    ) {
        val canary = canaryIn(dir, """{"title":"refactor: x","expected":"any"}""", "val a = 1\n", "val a = 2\n")
        ruleMismatches(canary.copy(githubLineCounts = mapOf("src/main/kotlin/A.kt" to LineCounts(1, 1)))) shouldBe emptyList()
        ruleMismatches(canary.copy(githubLineCounts = mapOf("src/main/kotlin/A.kt" to LineCounts(2, 1)))).single() shouldContain
            "GitHub +2/-1"
        ruleMismatches(canary.copy(githubLineCounts = mapOf("src/main/kotlin/B.kt" to LineCounts(1, 1)))).single() shouldContain
            "GitHub"
    }
}
