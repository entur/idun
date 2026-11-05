package org.entur.idun.github

import org.entur.idun.rules.ChangedFile

private const val PREFIX = "[tørrkjøring]"
private const val FAKE_COMMENT_ID = 1L

/**
 * [GitHub] for tørrkjøring: lesing går til [delegate], mens alle skrivende kall bare registreres i [writes] og
 * skrives ut via [out]. Delegatens skrivemetoder kalles aldri.
 */
class DryRunGitHub(
    private val delegate: GitHub,
    private val out: (String) -> Unit,
) : GitHub {
    private val recorded = mutableListOf<String>()

    /** Hver skrivende kall slik den ble skrevet ut, i rekkefølge. */
    val writes: List<String> get() = recorded

    /** `true` når Idun ville sendt en godkjenning. */
    var wouldApprove: Boolean = false
        private set

    private fun record(line: String) {
        recorded += line
        out(line)
    }

    // --- lesing ---

    override suspend fun pullRequest(
        repo: RepoRef,
        number: Int,
    ): PullRequest = delegate.pullRequest(repo, number)

    override suspend fun compareFiles(
        repo: RepoRef,
        base: String,
        head: String,
    ): List<ChangedFile> = delegate.compareFiles(repo, base, head)

    override suspend fun pullRequestsForCommit(
        repo: RepoRef,
        sha: String,
    ): List<Int> = delegate.pullRequestsForCommit(repo, sha)

    override suspend fun mergeBase(
        repo: RepoRef,
        base: String,
        head: String,
    ): String = delegate.mergeBase(repo, base, head)

    override suspend fun fileContent(
        repo: RepoRef,
        path: String,
        ref: String,
    ): String? = delegate.fileContent(repo, path, ref)

    override suspend fun checkRuns(
        repo: RepoRef,
        sha: String,
    ): List<CheckRunInfo> = delegate.checkRuns(repo, sha)

    override suspend fun checkSuites(
        repo: RepoRef,
        sha: String,
    ): List<CheckSuiteInfo> = delegate.checkSuites(repo, sha)

    override suspend fun statuses(
        repo: RepoRef,
        sha: String,
    ): List<StatusInfo> = delegate.statuses(repo, sha)

    override suspend fun workflowRuns(
        repo: RepoRef,
        sha: String,
    ): List<WorkflowRunInfo> = delegate.workflowRuns(repo, sha)

    override suspend fun comments(
        repo: RepoRef,
        number: Int,
    ): List<IssueComment> = delegate.comments(repo, number)

    override suspend fun reactions(
        repo: RepoRef,
        commentId: Long,
    ): Reactions = delegate.reactions(repo, commentId)

    override suspend fun reviews(
        repo: RepoRef,
        number: Int,
    ): List<Review> = delegate.reviews(repo, number)

    // --- skriving ---

    override suspend fun upsertCheckRun(
        repo: RepoRef,
        sha: String,
        name: String,
        write: CheckRunWrite,
    ) = record("$PREFIX check $name: ${write.status}/${write.conclusion ?: "-"} – \"${write.title}\"")

    override suspend fun createComment(
        repo: RepoRef,
        number: Int,
        body: String,
    ): Long {
        record("$PREFIX kommentar:\n$body")
        return FAKE_COMMENT_ID
    }

    override suspend fun updateComment(
        repo: RepoRef,
        id: Long,
        body: String,
    ) = record("$PREFIX oppdatert kommentar $id:\n$body")

    override suspend fun ensureLabel(
        repo: RepoRef,
        name: String,
        color: String,
        description: String,
    ) = record("$PREFIX ville opprettet label $name")

    override suspend fun addLabel(
        repo: RepoRef,
        number: Int,
        name: String,
    ) = record("$PREFIX label +$name")

    override suspend fun removeLabel(
        repo: RepoRef,
        number: Int,
        name: String,
    ) = record("$PREFIX label -$name")

    override suspend fun approve(
        repo: RepoRef,
        number: Int,
        sha: String,
        body: String,
    ) {
        wouldApprove = true
        record("$PREFIX ville GODKJENT $sha")
    }

    override suspend fun dismissReview(
        repo: RepoRef,
        number: Int,
        reviewId: Long,
        message: String,
    ) = record("$PREFIX ville trukket godkjenning (review $reviewId): $message")
}
