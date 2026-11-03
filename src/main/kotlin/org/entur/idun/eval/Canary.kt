package org.entur.idun.eval

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.entur.idun.rules.ChangedFile
import org.entur.idun.rules.FileContents
import org.entur.idun.rules.FileStatus

/** Utfallet vi forventer av en kanarifugl. `ANY` rapporteres bare og kan ikke feile evalen. */
@Serializable
enum class Expected {
    @SerialName("safe")
    SAFE,

    @SerialName("needs-human")
    NEEDS_HUMAN,

    @SerialName("any")
    ANY,
}

@Serializable
private data class CanaryMeta(
    val title: String,
    val expected: Expected,
)

/** En kanarifugl: en endring Idun skal vurdere på nøyaktig samme måte som en ekte PR. */
data class Canary(
    val name: String,
    val title: String,
    val expected: Expected,
    val files: List<ChangedFile>,
    /** Nøklet på [ChangedFile.path], slik [org.entur.idun.rules.evaluateFiles] forventer. */
    val contents: Map<String, FileContents>,
)

private const val DIFF_CONTEXT = 3

/**
 * Laster `meta.json`, `before/` og `after/` fra [dir]. Filstatus utledes av hvilke sider som har filen:
 * bare `after` er `ADDED`, bare `before` er `REMOVED`, begge er `MODIFIED`. Patch og linjetall kommer
 * fra en generert unified diff, ikke fra håndskrevne tall.
 */
fun loadCanary(dir: Path): Canary {
    val meta = Json.decodeFromString(CanaryMeta.serializer(), dir.resolve("meta.json").readText())
    val before = readTree(dir.resolve("before"))
    val after = readTree(dir.resolve("after"))

    val files = mutableListOf<ChangedFile>()
    val contents = mutableMapOf<String, FileContents>()
    for (path in (before.keys + after.keys).toSortedSet()) {
        val old = before[path]
        val new = after[path]
        if (old == new) continue
        val status =
            when {
                old == null -> FileStatus.ADDED
                new == null -> FileStatus.REMOVED
                else -> FileStatus.MODIFIED
            }
        files += changedFile(path, status, old, new)
        contents[path] = FileContents(old, new)
    }
    return Canary(dir.name, meta.title, meta.expected, files, contents)
}

/** Alle kanarifuglene under [root], sortert på navn. */
fun loadCanaries(root: Path): List<Canary> =
    root
        .toFile()
        .listFiles { f -> f.isDirectory && f.toPath().resolve("meta.json").exists() }
        .orEmpty()
        .map { loadCanary(it.toPath()) }
        .sortedBy { it.name }

private fun changedFile(
    path: String,
    status: FileStatus,
    before: String?,
    after: String?,
): ChangedFile {
    val oldLines = before.orEmpty().lines().dropLastWhile { it.isEmpty() }
    val newLines = after.orEmpty().lines().dropLastWhile { it.isEmpty() }
    val diff = DiffUtils.diff(oldLines, newLines)
    // GitHubs `patch` starter ved første hunk, uten `---`/`+++`-linjene.
    val patch =
        UnifiedDiffUtils
            .generateUnifiedDiff(path, path, oldLines, diff, DIFF_CONTEXT)
            .drop(2)
            .joinToString("\n")
    return ChangedFile(
        path = path,
        previousPath = null,
        status = status,
        additions = diff.deltas.sumOf { it.target.size() },
        deletions = diff.deltas.sumOf { it.source.size() },
        patch = patch,
    )
}

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun readTree(root: Path): Map<String, String> {
    if (!root.isDirectory()) return emptyMap()
    return root
        .walk()
        .filter { !it.isDirectory() }
        .associate { it.relativeTo(root).joinToString("/") to it.readText() }
}
