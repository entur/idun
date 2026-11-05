package org.entur.idun.eval

import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.entur.idun.review.BehaviourReviewer
import org.entur.idun.review.ReviewResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EvalRunTest {
    private val reviewer = BehaviourReviewer { ReviewResult.Failed("ikke i bruk") }

    @Test
    fun `en kanarifugl som ikke kan hentes gir SKIPPED og exit-kode 1, aldri bestått`(
        @TempDir root: Path,
        @TempDir work: Path,
    ) {
        root.resolve("pr-1").createDirectories().resolve("meta.json").writeText(
            """{"title":"refactor: x","expected":"needs-human","source":{"repo":"entur/products-api","pr":1},"rules":["TOO_LARGE"]}""",
        )
        runEval(reviewer, root, { _, _ -> throw CanaryFetchException("gh er ikke tilgjengelig") }, work) shouldBe 1
    }

    @Test
    fun `alt som forventet gir exit-kode 0, og regelavvik gir 1`(
        @TempDir root: Path,
        @TempDir work: Path,
    ) {
        val dir = root.resolve("stor").createDirectories()
        dir.resolve("before/src/main/kotlin/A.kt").apply { parent.createDirectories() }.writeText("")
        dir.resolve("after/src/main/kotlin/A.kt").apply { parent.createDirectories() }
            .writeText((1..60).joinToString("\n", postfix = "\n") { "val a$it = $it" })
        dir.resolve("meta.json").writeText("""{"title":"refactor: x","expected":"needs-human","rules":["TOO_LARGE"]}""")
        runEval(reviewer, root, { _, _ -> error("ingen henting") }, work) shouldBe 0

        dir.resolve("meta.json").writeText("""{"title":"refactor: x","expected":"needs-human","rules":["OTHER_FILE"]}""")
        runEval(reviewer, root, { _, _ -> error("ingen henting") }, work) shouldBe 1
    }
}
