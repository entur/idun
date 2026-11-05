package org.entur.idun.eval

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import java.nio.file.Files
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
import org.entur.idun.config.IdunConfig
import org.entur.idun.rules.ChangedFile
import org.entur.idun.rules.FileContents
import org.entur.idun.rules.FileStatus
import org.entur.idun.rules.ReasonCode
import org.entur.idun.rules.evaluateFiles

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

/**
 * En ekte PR som kanarifuglen hentes fra når evalen kjører. Innholdet ligger aldri i dette repoet: kilderepoet
 * kan være internt, mens `entur/idun` er offentlig.
 */
@Serializable
data class CanarySource(
    val repo: String,
    val pr: Int,
)

@Serializable
private data class CanaryMeta(
    val title: String,
    val expected: Expected,
    val source: CanarySource? = null,
    /** Grunnkodene filreglene skal gi, i rekkefølge. Tom liste betyr at reglene skal slippe den gjennom. */
    val rules: List<ReasonCode>? = null,
)

/** Linjetallene GitHub selv oppgir for en fil. */
data class LineCounts(
    val additions: Int,
    val deletions: Int,
)

/** En kanarifugl: en endring Idun skal vurdere på nøyaktig samme måte som en ekte PR. */
data class Canary(
    val name: String,
    val title: String,
    val expected: Expected,
    val files: List<ChangedFile>,
    /** Nøklet på [ChangedFile.path], slik [org.entur.idun.rules.evaluateFiles] forventer. */
    val contents: Map<String, FileContents>,
    /** Fra `rules` i `meta.json`, når den er satt. */
    val expectedRules: List<ReasonCode>? = null,
    /** Fra GitHub, for kanarifugler som hentes fra en PR. */
    val githubLineCounts: Map<String, LineCounts>? = null,
)

/** En kanarifugl som er lastet, eller som ikke kunne lastes. En hoppet over kanarifugl er aldri bestått. */
sealed interface LoadedCanary {
    val name: String

    data class Ready(
        val canary: Canary,
    ) : LoadedCanary {
        override val name: String get() = canary.name
    }

    data class Skipped(
        override val name: String,
        val reason: String,
    ) : LoadedCanary
}

private const val DIFF_CONTEXT = 3

private val metaJson = Json { ignoreUnknownKeys = false }

private fun readMeta(dir: Path): CanaryMeta = metaJson.decodeFromString(CanaryMeta.serializer(), dir.resolve("meta.json").readText())

/**
 * Laster `meta.json` fra [dir] og `before/` og `after/` fra [trees]. Filstatus utledes av hvilke sider som har
 * filen: bare `after` er `ADDED`, bare `before` er `REMOVED`, begge er `MODIFIED`. Patch og linjetall kommer
 * fra en generert unified diff, ikke fra håndskrevne tall.
 */
fun loadCanary(
    dir: Path,
    trees: Path = dir,
    githubLineCounts: Map<String, LineCounts>? = null,
): Canary {
    val meta = readMeta(dir)
    val before = readTree(trees.resolve("before"))
    val after = readTree(trees.resolve("after"))

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
    return Canary(dir.name, meta.title, meta.expected, files, contents, meta.rules, githubLineCounts)
}

/**
 * Alle kanarifuglene under [root], sortert på navn. En kanarifugl med `source` i `meta.json` hentes med
 * [fetcher] inn i en ny mappe under [workDir]. Uten [fetcher], eller når hentingen feiler, blir den
 * [LoadedCanary.Skipped].
 */
fun loadCanaries(
    root: Path,
    fetcher: CanaryFetcher? = null,
    workDir: Path? = null,
): List<LoadedCanary> =
    root
        .toFile()
        .listFiles { f -> f.isDirectory && f.toPath().resolve("meta.json").exists() }
        .orEmpty()
        .map { it.toPath() }
        .sortedBy { it.name }
        .map { dir -> load(dir, fetcher, workDir) }

private fun load(
    dir: Path,
    fetcher: CanaryFetcher?,
    workDir: Path?,
): LoadedCanary {
    val source = readMeta(dir).source ?: return LoadedCanary.Ready(loadCanary(dir))
    if (fetcher == null || workDir == null) {
        return LoadedCanary.Skipped(dir.name, "hentes fra ${source.repo}#${source.pr} når evalen kjører")
    }
    return try {
        val trees = Files.createDirectories(workDir.resolve(dir.name))
        val counts = fetcher.fetch(source, trees)
        LoadedCanary.Ready(loadCanary(dir, trees, counts))
    } catch (e: CanaryFetchException) {
        LoadedCanary.Skipped(dir.name, e.message ?: "hentingen feilet")
    }
}

/**
 * Det som ikke stemmer med forventningene til reglene: grunnkodene fra `rules` i `meta.json`, og linjetallene
 * fra diffen mot dem GitHub oppgir. Tom liste når alt stemmer, eller når det ikke er noe å sjekke.
 */
fun ruleMismatches(canary: Canary): List<String> {
    val problems = mutableListOf<String>()
    canary.expectedRules?.let { expected ->
        val actual = evaluateFiles(canary.files, canary.contents, IdunConfig.DEFAULT).reasons.map { it.code }
        if (actual != expected) problems += "reglene ga $actual, forventet $expected"
    }
    canary.githubLineCounts?.let { github ->
        val generated = canary.files.associate { it.path to LineCounts(it.additions, it.deletions) }
        if (generated.keys != github.keys) problems += "diffen har filene ${generated.keys}, GitHub ${github.keys}"
        for ((path, counts) in generated) {
            val fromGitHub = github[path] ?: continue
            if (counts != fromGitHub) {
                problems += "$path: diffen gir +${counts.additions}/-${counts.deletions}, " +
                    "GitHub +${fromGitHub.additions}/-${fromGitHub.deletions}"
            }
        }
    }
    return problems
}

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
