package org.entur.idun.eval

import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.entur.idun.rules.CONTROL_CHARACTERS

/** Kanarifuglen kunne ikke hentes. Den skal da rapporteres som hoppet over, aldri som bestått. */
class CanaryFetchException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Henter innholdet før og etter en PR inn i `before/` og `after/` under en mappe. */
fun interface CanaryFetcher {
    /** Skriver filene under [into] og gir linjetallene GitHub oppgir per fil. Kaster [CanaryFetchException]. */
    fun fetch(
        source: CanarySource,
        into: Path,
    ): Map<String, LineCounts>
}

class CommandResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
)

/** Sømmen rundt prosesskallet, så testene kan svare i stedet for `gh`. */
fun interface CommandRunner {
    /** Kaster [IOException] når kommandoen ikke kan startes. */
    fun run(command: List<String>): CommandResult
}

private const val COMMAND_TIMEOUT_SECONDS = 120L

/** Kjører en kommando og venter på den. stderr går til en fil, så store svar på stdout ikke låser prosessen. */
object ProcessRunner : CommandRunner {
    override fun run(command: List<String>): CommandResult {
        val stderr = File.createTempFile("idun-gh-", ".err")
        try {
            val process = ProcessBuilder(command).redirectError(stderr).start()
            process.outputStream.close()
            val stdout = process.inputStream.use { it.readBytes() }
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException("${command.first()} svarte ikke innen $COMMAND_TIMEOUT_SECONDS s")
            }
            return CommandResult(process.exitValue(), stdout, stderr.readText())
        } finally {
            stderr.delete()
        }
    }
}

private val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
private val SHA = Regex("[0-9a-f]{40}")

private val ghJson = Json { ignoreUnknownKeys = true }

@Serializable
private class PrView(
    val baseRefOid: String,
    val headRefOid: String,
    val files: List<PrFile>,
)

@Serializable
private class PrFile(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val changeType: String,
)

/**
 * Henter en PR med GitHub CLI (`gh`), bare lesing: `gh pr view` for base, head og filene, compare for
 * merge-basen og `contents` for hver fil på merge-basen og på head. Innholdet havner bare i [CanaryFetcher.fetch]
 * sin mappe, aldri i repoet.
 */
class GhCanaryFetcher(
    private val runner: CommandRunner = ProcessRunner,
) : CanaryFetcher {
    override fun fetch(
        source: CanarySource,
        into: Path,
    ): Map<String, LineCounts> {
        if (!REPO.matches(source.repo)) throw CanaryFetchException("ugyldig repo: ${source.repo}")
        val label = "${source.repo}#${source.pr}"

        val view =
            try {
                ghJson.decodeFromString(
                    PrView.serializer(),
                    gh(label, "pr", "view", source.pr.toString(), "-R", source.repo, "--json", "baseRefOid,headRefOid,files")
                        .decodeToString(),
                )
            } catch (e: IllegalArgumentException) {
                throw CanaryFetchException("$label: uventet svar fra gh pr view", e)
            }
        val head = view.headRefOid.requireSha(label)
        val mergeBase =
            gh(label, "api", "repos/${source.repo}/compare/${view.baseRefOid.requireSha(label)}...$head", "--jq", ".merge_base_commit.sha")
                .decodeToString()
                .trim()
                .requireSha(label)

        val counts = mutableMapOf<String, LineCounts>()
        for (file in view.files) {
            val segments = safeSegments(label, file.path)
            val (before, after) =
                when (file.changeType) {
                    "ADDED" -> null to content(label, source.repo, segments, head)
                    "DELETED" -> content(label, source.repo, segments, mergeBase) to null
                    "MODIFIED", "CHANGED" -> content(label, source.repo, segments, mergeBase) to content(label, source.repo, segments, head)
                    else -> throw CanaryFetchException("$label: ${file.path} har endringstypen ${file.changeType}, som ikke støttes")
                }
            before?.let { write(into.resolve("before"), segments, it) }
            after?.let { write(into.resolve("after"), segments, it) }
            counts[file.path] = LineCounts(file.additions, file.deletions)
        }
        return counts
    }

    /** Råinnholdet til filen på [ref], eller `null` når den ikke finnes der (HTTP 404). */
    private fun content(
        label: String,
        repo: String,
        segments: List<String>,
        ref: String,
    ): ByteArray? {
        val encoded = segments.joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
        val command = listOf("gh", "api", "-H", "Accept: application/vnd.github.raw", "repos/$repo/contents/$encoded?ref=$ref")
        val result = start(label, command)
        if (result.exitCode == 0) return result.stdout
        if ("HTTP 404" in result.stderr) return null
        throw failure(label, command, result)
    }

    private fun gh(
        label: String,
        vararg args: String,
    ): ByteArray {
        val command = listOf("gh") + args
        val result = start(label, command)
        if (result.exitCode != 0) throw failure(label, command, result)
        return result.stdout
    }

    private fun start(
        label: String,
        command: List<String>,
    ): CommandResult =
        try {
            runner.run(command)
        } catch (e: IOException) {
            throw CanaryFetchException("$label: gh er ikke tilgjengelig (${e.message})", e)
        }

    private fun failure(
        label: String,
        command: List<String>,
        result: CommandResult,
    ) = CanaryFetchException(
        "$label: `${command.take(3).joinToString(" ")}` feilet (exit ${result.exitCode}): " +
            result.stderr.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(200),
    )

    private fun String.requireSha(label: String): String = takeIf { SHA.matches(it) } ?: throw CanaryFetchException("$label: ugyldig sha '$this'")

    /** Stien kommer fra nettet: ingen tomme, `.`- eller `..`-ledd, ingen absolutte stier og ingen kontrolltegn. */
    private fun safeSegments(
        label: String,
        path: String,
    ): List<String> {
        val segments = path.split("/")
        val unsafe =
            segments.any { it.isEmpty() || it == "." || it == ".." || '\\' in it } || CONTROL_CHARACTERS.containsMatchIn(path)
        if (unsafe) throw CanaryFetchException("$label: utrygg filsti '${CONTROL_CHARACTERS.replace(path, "?")}'")
        return segments
    }

    private fun write(
        root: Path,
        segments: List<String>,
        bytes: ByteArray,
    ) {
        val target = segments.fold(root) { dir, segment -> dir.resolve(segment) }.normalize()
        check(target.startsWith(root.normalize())) { "filen havner utenfor $root" }
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }
}
