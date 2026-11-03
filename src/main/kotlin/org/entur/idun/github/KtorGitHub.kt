package org.entur.idun.github

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.entur.idun.rules.ChangedFile
import org.entur.idun.rules.FileStatus
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal const val GITHUB_API_URL = "https://api.github.com"
internal const val GITHUB_ACCEPT = "application/vnd.github+json"
internal const val GITHUB_API_VERSION = "2022-11-28"

private const val PER_PAGE = "100"
private const val MAX_PAGES = 1000

/** GitHubs tak for filer i et compare-svar. */
private const val MAX_COMPARE_FILES = 300

internal val githubJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/** Klient med JSON-støtte og timeout. Brukes av både [AppAuth] og [KtorGitHub]. */
fun githubHttpClient(engine: HttpClientEngine = CIO.create()): HttpClient =
    HttpClient(engine) {
        install(ContentNegotiation) { json(githubJson) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }

/** Kastes for alle ikke-2xx-svar fra GitHub som ikke eksplisitt er tillatt. */
class GitHubApiException(
    val status: Int,
    message: String,
) : RuntimeException(message)

/**
 * [GitHub] over Ktor. Kaster på alle ikke-2xx-svar, bortsett fra 404 i `fileContent` (gir `null`)
 * og `removeLabel` (ignoreres). Lister pagineres via `Link`-headeren.
 *
 * [appId] er appens egen id: `upsertCheckRun` oppdaterer aldri en run som tilhører en annen app.
 */
class KtorGitHub(
    private val client: HttpClient,
    private val token: String,
    private val appId: Long,
    private val baseUrl: String = GITHUB_API_URL,
) : GitHub {
    // --- pull requests ---

    override suspend fun pullRequest(
        repo: RepoRef,
        number: Int,
    ): PullRequest {
        val dto = call(HttpMethod.Get, repoUrl(repo, "pulls", number)).body<PullRequestDto>()
        return PullRequest(
            number = dto.number,
            title = dto.title,
            draft = dto.draft,
            state = dto.state,
            merged = dto.merged,
            headSha = dto.head.sha,
            baseSha = dto.base.sha,
            headRepoId = dto.head.repo?.id,
            baseRepoId = dto.base.repo.id,
            changedFiles = dto.changedFiles,
        )
    }

    /**
     * Uten sideparametre gir compare alle filene på første svar, men høyst [MAX_COMPARE_FILES]. Er listen så lang,
     * kan den være avkortet, og da kastes det.
     */
    override suspend fun compareFiles(
        repo: RepoRef,
        base: String,
        head: String,
    ): List<ChangedFile> {
        val files = call(HttpMethod.Get, repoUrl(repo, "compare", "$base...$head")).body<CompareFilesDto>().files
        check(files.size < MAX_COMPARE_FILES) { "compare $base...$head har ${files.size} filer og kan være avkortet" }
        return files.map { it.toDomain() }
    }

    override suspend fun pullRequestsForCommit(
        repo: RepoRef,
        sha: String,
    ): List<Int> =
        paged(repoUrl(repo, "commits", sha, "pulls", query = perPage())) { it.body<List<NumberDto>>() }
            .map { it.number }

    override suspend fun mergeBase(
        repo: RepoRef,
        base: String,
        head: String,
    ): String = call(HttpMethod.Get, repoUrl(repo, "compare", "$base...$head")).body<CompareDto>().mergeBaseCommit.sha

    // --- innhold ---

    override suspend fun fileContent(
        repo: RepoRef,
        path: String,
        ref: String,
    ): String? {
        val segments = path.split("/")
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Ugyldig filsti: $path" }
        val response = send(HttpMethod.Get, repoUrl(repo, "contents", *segments.toTypedArray(), query = mapOf("ref" to ref)))
        if (response.status == HttpStatusCode.NotFound) return null
        response.requireSuccess(HttpMethod.Get, "contents/$path")

        val obj =
            response.body<JsonElement>() as? JsonObject
                ?: error("contents/$path er ikke en fil (katalog eller uventet svar)")
        val type = obj.string("type")
        check(type == "file") { "contents/$path har type '$type', forventet 'file'" }
        val encoding = obj.string("encoding")
        check(encoding == "base64") { "contents/$path har encoding '$encoding', forventet 'base64'" }
        val content = obj.string("content") ?: error("contents/$path mangler content")
        return decodeUtf8(content, path)
    }

    // --- checks og statuser ---

    override suspend fun checkRuns(
        repo: RepoRef,
        sha: String,
    ): List<CheckRunInfo> = listCheckRuns(repo, sha, null)

    private suspend fun listCheckRuns(
        repo: RepoRef,
        sha: String,
        checkName: String?,
    ): List<CheckRunInfo> {
        val query = perPage() + listOfNotNull(checkName?.let { "check_name" to it })
        return paged(repoUrl(repo, "commits", sha, "check-runs", query = query)) { it.body<CheckRunsDto>().checkRuns }
            .map {
                CheckRunInfo(it.id, it.name, it.status, it.conclusion, it.checkSuite.id, it.app.id, it.externalId)
            }
    }

    override suspend fun checkSuites(
        repo: RepoRef,
        sha: String,
    ): List<CheckSuiteInfo> =
        paged(repoUrl(repo, "commits", sha, "check-suites", query = perPage())) { it.body<CheckSuitesDto>().checkSuites }
            .map { CheckSuiteInfo(it.id, it.status, it.app.id) }

    override suspend fun statuses(
        repo: RepoRef,
        sha: String,
    ): List<StatusInfo> =
        paged(repoUrl(repo, "commits", sha, "status", query = perPage())) { it.body<CombinedStatusDto>().statuses }
            .map { StatusInfo(it.context, it.state) }

    override suspend fun workflowRuns(
        repo: RepoRef,
        sha: String,
    ): List<WorkflowRunInfo> =
        paged(repoUrl(repo, "actions", "runs", query = perPage() + ("head_sha" to sha))) {
            it.body<WorkflowRunsDto>().workflowRuns
        }.map { WorkflowRunInfo(it.id, it.workflowId, it.checkSuiteId) }

    override suspend fun upsertCheckRun(
        repo: RepoRef,
        sha: String,
        name: String,
        write: CheckRunWrite,
    ) {
        val existing =
            listCheckRuns(repo, sha, name)
                .filter { it.name == name && it.appId == appId }
                .maxByOrNull { it.id }
        val output =
            buildJsonObject {
                put("title", write.title)
                put("summary", write.summary)
            }
        if (existing == null) {
            val body =
                buildJsonObject {
                    put("name", name)
                    put("head_sha", sha)
                    put("status", write.status)
                    write.conclusion?.let { put("conclusion", it) }
                    write.externalId?.let { put("external_id", it) }
                    put("output", output)
                }
            call(HttpMethod.Post, repoUrl(repo, "check-runs"), body)
        } else {
            val body =
                buildJsonObject {
                    put("status", write.status)
                    write.conclusion?.let { put("conclusion", it) }
                    write.externalId?.let { put("external_id", it) }
                    put("output", output)
                }
            call(HttpMethod.Patch, repoUrl(repo, "check-runs", existing.id), body)
        }
    }

    // --- kommentarer ---

    override suspend fun comments(
        repo: RepoRef,
        number: Int,
    ): List<IssueComment> =
        paged(repoUrl(repo, "issues", number, "comments", query = perPage())) { it.body<List<CommentDto>>() }
            .map { IssueComment(it.id, it.user.login, it.body) }

    override suspend fun createComment(
        repo: RepoRef,
        number: Int,
        body: String,
    ): Long =
        call(HttpMethod.Post, repoUrl(repo, "issues", number, "comments"), buildJsonObject { put("body", body) })
            .body<IdDto>()
            .id

    override suspend fun updateComment(
        repo: RepoRef,
        id: Long,
        body: String,
    ) {
        call(HttpMethod.Patch, repoUrl(repo, "issues", "comments", id), buildJsonObject { put("body", body) })
    }

    override suspend fun reactions(
        repo: RepoRef,
        commentId: Long,
    ): Reactions {
        val dto = call(HttpMethod.Get, repoUrl(repo, "issues", "comments", commentId)).body<CommentReactionsDto>()
        return Reactions(dto.reactions.thumbsUp, dto.reactions.thumbsDown)
    }

    // --- labels ---

    override suspend fun ensureLabel(
        repo: RepoRef,
        name: String,
        color: String,
        description: String,
    ) {
        val body =
            buildJsonObject {
                put("name", name)
                put("color", color)
                put("description", description)
            }
        val response = send(HttpMethod.Post, repoUrl(repo, "labels"), body)
        if (response.status == HttpStatusCode.UnprocessableEntity && response.isAlreadyExists()) return
        response.requireSuccess(HttpMethod.Post, "labels")
    }

    override suspend fun addLabel(
        repo: RepoRef,
        number: Int,
        name: String,
    ) {
        val body = buildJsonObject { putJsonArray("labels") { add(JsonPrimitive(name)) } }
        call(HttpMethod.Post, repoUrl(repo, "issues", number, "labels"), body)
    }

    override suspend fun removeLabel(
        repo: RepoRef,
        number: Int,
        name: String,
    ) {
        val response = send(HttpMethod.Delete, repoUrl(repo, "issues", number, "labels", name))
        if (response.status == HttpStatusCode.NotFound) return
        response.requireSuccess(HttpMethod.Delete, "issues/$number/labels")
    }

    // --- reviews ---

    override suspend fun reviews(
        repo: RepoRef,
        number: Int,
    ): List<Review> =
        paged(repoUrl(repo, "pulls", number, "reviews", query = perPage())) { it.body<List<ReviewDto>>() }
            .map { Review(it.id, it.user.login, it.state, it.body.orEmpty(), it.commitId) }

    override suspend fun approve(
        repo: RepoRef,
        number: Int,
        sha: String,
        body: String,
    ) {
        val json =
            buildJsonObject {
                put("commit_id", sha)
                put("event", "APPROVE")
                put("body", body)
            }
        call(HttpMethod.Post, repoUrl(repo, "pulls", number, "reviews"), json)
    }

    override suspend fun dismissReview(
        repo: RepoRef,
        number: Int,
        reviewId: Long,
        message: String,
    ) {
        call(
            HttpMethod.Put,
            repoUrl(repo, "pulls", number, "reviews", reviewId, "dismissals"),
            buildJsonObject { put("message", message) },
        )
    }

    // --- HTTP-hjelpere ---

    private fun perPage(): Map<String, String> = mapOf("per_page" to PER_PAGE)

    private fun repoUrl(
        repo: RepoRef,
        vararg segments: Any,
        query: Map<String, String> = emptyMap(),
    ): String =
        URLBuilder(baseUrl)
            .apply {
                appendPathSegments(listOf("repos", repo.owner, repo.name) + segments.map { it.toString() }, encodeSlash = true)
                query.forEach { (k, v) -> parameters.append(k, v) }
            }.buildString()

    private suspend fun send(
        method: HttpMethod,
        url: String,
        body: JsonElement? = null,
    ): HttpResponse =
        client.request {
            this.method = method
            url(url)
            bearerAuth(token)
            header(HttpHeaders.Accept, GITHUB_ACCEPT)
            header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun call(
        method: HttpMethod,
        url: String,
        body: JsonElement? = null,
    ): HttpResponse = send(method, url, body).also { it.requireSuccess(method, Url(url).encodedPath) }

    private suspend fun HttpResponse.requireSuccess(
        method: HttpMethod,
        what: String,
    ) {
        if (status.isSuccess()) return
        val text = runCatching { bodyAsText().take(500) }.getOrDefault("")
        throw GitHubApiException(status.value, "${method.value} $what feilet med ${status.value}: $text")
    }

    private suspend fun HttpResponse.isAlreadyExists(): Boolean =
        runCatching {
            val errors = (githubJson.parseToJsonElement(bodyAsText()) as JsonObject)["errors"] as? JsonArray
            errors?.any { ((it as? JsonObject)?.get("code") as? JsonPrimitive)?.content == "already_exists" } == true
        }.getOrDefault(false)

    /** Henter alle sider ved å følge `rel="next"` i `Link`-headeren. */
    private suspend fun <T> paged(
        firstUrl: String,
        extract: suspend (HttpResponse) -> List<T>,
    ): List<T> {
        val result = mutableListOf<T>()
        var next: String? = firstUrl
        var pages = 0
        while (next != null) {
            check(++pages <= MAX_PAGES) { "For mange sider fra $firstUrl" }
            val response = call(HttpMethod.Get, next)
            result += extract(response)
            next = nextLink(response)?.also { requireSameOrigin(it) }
        }
        return result
    }

    private fun nextLink(response: HttpResponse): String? {
        val header = response.headers.getAll(HttpHeaders.Link)?.joinToString(",") ?: return null
        return LINK_ENTRY
            .findAll(header)
            .firstOrNull { entry ->
                REL.find(entry.groupValues[2])?.groupValues?.get(1)?.split(' ')?.contains("next") == true
            }?.groupValues
            ?.get(1)
    }

    /** Tokenet skal aldri sendes til en annen vert enn den vi startet mot. */
    private fun requireSameOrigin(next: String) {
        val base = Url(baseUrl)
        val target = Url(next)
        check(target.protocol == base.protocol && target.host == base.host && target.port == base.port) {
            "Link-header peker til annen opprinnelse: ${target.host}"
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun decodeUtf8(
        base64: String,
        path: String,
    ): String {
        val bytes =
            try {
                // GitHub bryter linjer med \n; Base64.getDecoder() avviser alt annet ugyldig.
                Base64.getDecoder().decode(base64.filterNot { it == '\n' || it == '\r' })
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("contents/$path har ugyldig base64", e)
            }
        return Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private companion object {
        val LINK_ENTRY = Regex("""<([^>]*)>([^<]*)""")
        val REL = Regex("""rel\s*=\s*"?([^";,]*)"?""")
    }
}

// --- interne JSON-modeller ---

@Serializable
private class PullRequestDto(
    val number: Int,
    val title: String,
    val draft: Boolean,
    val state: String,
    val merged: Boolean,
    val head: HeadDto,
    val base: BaseDto,
    @SerialName("changed_files") val changedFiles: Int,
) {
    @Serializable
    class HeadDto(
        val sha: String,
        val repo: RepoDto? = null,
    )

    @Serializable
    class BaseDto(
        val sha: String,
        val repo: RepoDto,
    )

    @Serializable
    class RepoDto(
        val id: Long,
    )
}

@Serializable
private class CompareFilesDto(
    val files: List<PullFileDto>,
)

@Serializable
private class PullFileDto(
    val filename: String,
    @SerialName("previous_filename") val previousFilename: String? = null,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val patch: String? = null,
) {
    fun toDomain(): ChangedFile =
        ChangedFile(
            path = filename,
            previousPath = previousFilename,
            status = parseStatus(status),
            additions = additions,
            deletions = deletions,
            patch = patch,
        )
}

private fun parseStatus(status: String): FileStatus =
    when (status) {
        "added" -> FileStatus.ADDED
        "modified" -> FileStatus.MODIFIED
        "removed" -> FileStatus.REMOVED
        "renamed" -> FileStatus.RENAMED
        "copied" -> FileStatus.COPIED
        "changed" -> FileStatus.CHANGED
        "unchanged" -> FileStatus.UNCHANGED
        else -> error("Ukjent filstatus fra GitHub: '$status'")
    }

@Serializable
private class NumberDto(
    val number: Int,
)

@Serializable
private class IdDto(
    val id: Long,
)

@Serializable
private class CompareDto(
    @SerialName("merge_base_commit") val mergeBaseCommit: ShaDto,
) {
    @Serializable
    class ShaDto(
        val sha: String,
    )
}

@Serializable
private class AppDto(
    val id: Long,
)

@Serializable
private class CheckRunsDto(
    @SerialName("check_runs") val checkRuns: List<CheckRunDto>,
) {
    @Serializable
    class CheckRunDto(
        val id: Long,
        val name: String,
        val status: String,
        val conclusion: String? = null,
        @SerialName("check_suite") val checkSuite: IdDto,
        val app: AppDto,
        @SerialName("external_id") val externalId: String? = null,
    )
}

@Serializable
private class CheckSuitesDto(
    @SerialName("check_suites") val checkSuites: List<CheckSuiteDto>,
) {
    @Serializable
    class CheckSuiteDto(
        val id: Long,
        val status: String,
        val app: AppDto,
    )
}

@Serializable
private class CombinedStatusDto(
    val statuses: List<StatusDto>,
) {
    @Serializable
    class StatusDto(
        val context: String,
        val state: String,
    )
}

@Serializable
private class WorkflowRunsDto(
    @SerialName("workflow_runs") val workflowRuns: List<WorkflowRunDto>,
) {
    @Serializable
    class WorkflowRunDto(
        val id: Long,
        @SerialName("workflow_id") val workflowId: Long,
        @SerialName("check_suite_id") val checkSuiteId: Long,
    )
}

@Serializable
private class UserDto(
    val login: String,
)

@Serializable
private class CommentDto(
    val id: Long,
    val user: UserDto,
    val body: String,
)

@Serializable
private class CommentReactionsDto(
    val reactions: ReactionsDto,
) {
    @Serializable
    class ReactionsDto(
        @SerialName("+1") val thumbsUp: Int,
        @SerialName("-1") val thumbsDown: Int,
    )
}

@Serializable
private class ReviewDto(
    val id: Long,
    val user: UserDto,
    val state: String,
    val body: String? = null,
    @SerialName("commit_id") val commitId: String? = null,
)
