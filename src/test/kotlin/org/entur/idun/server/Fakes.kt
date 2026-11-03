package org.entur.idun.server

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.delay
import org.entur.idun.ci.GITHUB_ACTIONS_APP_ID
import org.entur.idun.github.CheckRunInfo
import org.entur.idun.github.CheckRunWrite
import org.entur.idun.github.CheckSuiteInfo
import org.entur.idun.github.GitHub
import org.entur.idun.github.GitHubFactory
import org.entur.idun.github.IssueComment
import org.entur.idun.github.PullRequest
import org.entur.idun.github.Reactions
import org.entur.idun.github.RepoRef
import org.entur.idun.github.Review
import org.entur.idun.github.StatusInfo
import org.entur.idun.github.WorkflowRunInfo
import org.entur.idun.report.CHECK_NAME
import org.entur.idun.review.BehaviourReviewer
import org.entur.idun.review.FIXTURE_PATH
import org.entur.idun.review.ReviewInput
import org.entur.idun.review.ReviewResult
import org.entur.idun.review.validResponse
import org.entur.idun.rules.ChangedFile
import org.entur.idun.rules.FileStatus
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

internal const val OWN_APP_ID = 4242L
internal const val APP_LOGIN = "entur-idun[bot]"
internal const val HEAD = "head1"
internal const val BASE = "base1"
internal const val MERGE_BASE = "mergebase1"
internal val REPO = RepoRef("entur", "idun-sandbox")

/** Et lite kildekodeendring som passerer reglene, med innhold på merge-base og head. */
internal fun sourceFile(
    path: String = FIXTURE_PATH,
    previousPath: String? = null,
    status: FileStatus = FileStatus.MODIFIED,
) = ChangedFile(path, previousPath, status, additions = 1, deletions = 1, patch = "@@ -1 +1 @@\n-val a = 1\n+val b = 1")

/** GitHub i minnet. Alle skrivende kall logges i [writes]. Egne check runs oppfører seg som i KtorGitHub. */
internal class FakeGitHub(
    var pr: PullRequest =
        PullRequest(
            number = 7,
            title = "refactor: rename helper",
            draft = false,
            state = "open",
            merged = false,
            headSha = HEAD,
            baseSha = BASE,
            headRepoId = 1L,
            baseRepoId = 1L,
            changedFiles = 1,
        ),
) : GitHub {
    val writes = mutableListOf<String>()
    var files: List<ChangedFile> = listOf(sourceFile())
    val contents =
        mutableMapOf(
            (FIXTURE_PATH to MERGE_BASE) to "val a = 1\nval x = 2\n",
            (FIXTURE_PATH to HEAD) to "val b = 1\nval x = 2\n",
        )
    val contentReads = mutableListOf<Pair<String, String>>()
    val ciRuns =
        mutableListOf(
            CheckRunInfo(1, "test", "completed", "success", checkSuiteId = 11, appId = GITHUB_ACTIONS_APP_ID, externalId = null),
        )
    val suites = mutableListOf(CheckSuiteInfo(11, "completed", GITHUB_ACTIONS_APP_ID))
    val statuses = mutableListOf<StatusInfo>()

    /** Egne check runs per sha (bare én check med navnet [CHECK_NAME]). */
    val ownRuns = mutableMapOf<String, CheckRunWrite>()
    val comments = mutableListOf<IssueComment>()
    val reactions = mutableMapOf<Long, Reactions>()
    val labels = mutableSetOf<String>()
    val reviews = mutableListOf<Review>()
    val prsForCommit = mutableMapOf<String, List<Int>>()
    var pullRequestReads = 0
    var mergeBaseSha = MERGE_BASE

    /** Kalles med navnet på hvert kall; kan kaste for å simulere feil. */
    var onCall: (String) -> Unit = {}

    /** Kalles etter at en godkjenning er sendt. */
    var afterApprove: () -> Unit = {}

    private var nextId = 100L

    private fun call(name: String) = onCall(name)

    private fun write(entry: String) {
        writes += entry
    }

    override suspend fun pullRequest(
        repo: RepoRef,
        number: Int,
    ): PullRequest {
        call("pullRequest")
        pullRequestReads++
        return pr
    }

    override suspend fun pullRequestFiles(
        repo: RepoRef,
        number: Int,
    ): List<ChangedFile> {
        call("pullRequestFiles")
        return files
    }

    override suspend fun pullRequestsForCommit(
        repo: RepoRef,
        sha: String,
    ): List<Int> {
        call("pullRequestsForCommit")
        return prsForCommit[sha].orEmpty()
    }

    override suspend fun mergeBase(
        repo: RepoRef,
        base: String,
        head: String,
    ): String {
        call("mergeBase")
        return mergeBaseSha
    }

    override suspend fun fileContent(
        repo: RepoRef,
        path: String,
        ref: String,
    ): String? {
        call("fileContent")
        contentReads += path to ref
        return contents[path to ref]
    }

    override suspend fun customProperty(
        repo: RepoRef,
        name: String,
    ): String? = error("modusen skal ikke leses fra custom property")

    override suspend fun checkRuns(
        repo: RepoRef,
        sha: String,
    ): List<CheckRunInfo> {
        call("checkRuns")
        val own =
            ownRuns[sha]?.let {
                CheckRunInfo(1000, CHECK_NAME, it.status, it.conclusion, checkSuiteId = 999, appId = OWN_APP_ID, externalId = it.externalId)
            }
        return ciRuns + listOfNotNull(own)
    }

    override suspend fun checkSuites(
        repo: RepoRef,
        sha: String,
    ): List<CheckSuiteInfo> = suites.also { call("checkSuites") }

    override suspend fun statuses(
        repo: RepoRef,
        sha: String,
    ): List<StatusInfo> = statuses.also { call("statuses") }

    override suspend fun workflowRuns(
        repo: RepoRef,
        sha: String,
    ): List<WorkflowRunInfo> = emptyList<WorkflowRunInfo>().also { call("workflowRuns") }

    override suspend fun upsertCheckRun(
        repo: RepoRef,
        sha: String,
        name: String,
        write: CheckRunWrite,
    ) {
        call("upsertCheckRun")
        check(name == CHECK_NAME)
        // Som i KtorGitHub: external_id sendes ikke når den er null, så den gamle blir stående.
        ownRuns[sha] = write.copy(externalId = write.externalId ?: ownRuns[sha]?.externalId)
        write("check $sha ${write.status}/${write.conclusion}")
    }

    override suspend fun comments(
        repo: RepoRef,
        number: Int,
    ): List<IssueComment> = comments.toList().also { call("comments") }

    override suspend fun createComment(
        repo: RepoRef,
        number: Int,
        body: String,
    ): Long {
        call("createComment")
        val id = nextId++
        comments += IssueComment(id, APP_LOGIN, body)
        write("createComment")
        return id
    }

    override suspend fun updateComment(
        repo: RepoRef,
        id: Long,
        body: String,
    ) {
        call("updateComment")
        val i = comments.indexOfFirst { it.id == id }
        comments[i] = comments[i].copy(body = body)
        write("updateComment")
    }

    override suspend fun reactions(
        repo: RepoRef,
        commentId: Long,
    ): Reactions = (reactions[commentId] ?: Reactions(0, 0)).also { call("reactions") }

    override suspend fun ensureLabel(
        repo: RepoRef,
        name: String,
        color: String,
        description: String,
    ) {
        call("ensureLabel")
    }

    override suspend fun addLabel(
        repo: RepoRef,
        number: Int,
        name: String,
    ) {
        call("addLabel")
        labels += name
        write("addLabel $name")
    }

    override suspend fun removeLabel(
        repo: RepoRef,
        number: Int,
        name: String,
    ) {
        call("removeLabel")
        if (labels.remove(name)) write("removeLabel $name")
    }

    override suspend fun labels(
        repo: RepoRef,
        number: Int,
    ): List<String> = labels.toList().also { call("labels") }

    override suspend fun reviews(
        repo: RepoRef,
        number: Int,
    ): List<Review> = reviews.toList().also { call("reviews") }

    override suspend fun approve(
        repo: RepoRef,
        number: Int,
        sha: String,
        body: String,
    ) {
        call("approve")
        reviews += Review(nextId++, APP_LOGIN, "APPROVED", body, commitId = sha)
        write("approve $sha")
        afterApprove()
    }

    override suspend fun dismissReview(
        repo: RepoRef,
        number: Int,
        reviewId: Long,
        message: String,
    ) {
        call("dismissReview")
        val i = reviews.indexOfFirst { it.id == reviewId }
        reviews[i] = reviews[i].copy(state = "DISMISSED")
        write("dismissReview $reviewId")
    }
}

/** Teller kall og svarer med [result]. [beforeAnswer] kjøres før svaret, for å simulere at noe skjer underveis. */
internal class FakeReviewer(
    var result: ReviewResult = ReviewResult.Answered(validResponse(), costUsd = 0.25),
    var beforeAnswer: suspend () -> Unit = {},
) : BehaviourReviewer {
    var calls = 0
    val inputs = mutableListOf<ReviewInput>()

    override suspend fun review(input: ReviewInput): ReviewResult {
        calls++
        inputs += input
        beforeAnswer()
        return result
    }
}

internal class CountingFactory(
    private val gh: GitHub,
) : GitHubFactory {
    var calls = 0

    override suspend fun forInstallation(installationId: Long): GitHub {
        calls++
        return gh
    }
}

/** En egen logger med en appender som samler hendelsene, så testene kan lese hva som ble logget. */
internal class CapturedLog {
    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private val appender = ListAppender<ILoggingEvent>().apply {
        context = this@CapturedLog.context
        start()
    }
    val logger: Logger =
        context.getLogger("test.${UUID.randomUUID()}").apply {
            addAppender(appender)
            isAdditive = false
        }

    val messages: List<String> get() = appender.list.map { it.formattedMessage }

    fun single(prefix: String): String = messages.single { it.startsWith("$prefix ") || it == prefix }

    fun all(): String = appender.list.joinToString("\n") { e ->
        e.formattedMessage + (e.throwableProxy?.let { " ${it.className}: ${it.message}" } ?: "")
    }
}

internal suspend fun slowly() = delay(50)
