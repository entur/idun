package org.entur.idun.eval

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.entur.idun.config.IdunConfig
import org.entur.idun.rules.FileContents
import org.entur.idun.rules.FileStatus
import org.entur.idun.rules.ReasonCode
import org.entur.idun.rules.evaluateFiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CanaryRulesTest {
    private val canaries = loadCanaries(Path.of("eval/canaries")).associateBy { it.name }

    private fun rules(name: String) =
        canaries.getValue(name).let { evaluateFiles(it.files, it.contents, IdunConfig.DEFAULT) }

    @Test
    fun `alle kanarifuglene lastes`() {
        canaries.keys shouldContainExactlyInAnyOrder
            listOf(
                "value-of-to-first-or-null",
                "sort-order-changed",
                "column-name-in-string",
                "class-to-data-class",
                "extract-function",
                "pr-525",
                "pr-526",
                "pr-396",
            )
    }

    @Test
    fun `pr-525 stoppes av reglene fordi den er for stor`() {
        rules("pr-525").reasons.map { it.code } shouldBe listOf(ReasonCode.TOO_LARGE)
    }

    @Test
    fun `pr-396 stoppes av reglene fordi den rører specs`() {
        rules("pr-396").reasons.map { it.code } shouldBe List(2) { ReasonCode.PROTECTED_PATH }
    }

    @Test
    fun `pr-526 passerer reglene og går videre til modellen`() {
        rules("pr-526").eligible shouldBe true
    }

    @Test
    fun `syntetiske kanarifugler passerer reglene så de når modellen`() {
        listOf(
            "value-of-to-first-or-null",
            "sort-order-changed",
            "column-name-in-string",
            "class-to-data-class",
            "extract-function",
        ).forEach { name ->
            val result = rules(name)
            result.reasons shouldBe emptyList()
            result.changedLines shouldBeLessThanOrEqual 50
        }
    }

    @Test
    fun `linjetall kommer fra diffen og stemmer med GitHub for pr-525`() {
        val file = canaries.getValue("pr-525").files.single()
        file.status shouldBe FileStatus.MODIFIED
        file.additions shouldBe 54
        file.deletions shouldBe 29
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
}
