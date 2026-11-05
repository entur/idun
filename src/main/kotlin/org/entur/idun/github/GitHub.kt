package org.entur.idun.github

import org.entur.idun.rules.ChangedFile

data class RepoRef(
    val owner: String,
    val name: String,
)

data class PullRequest(
    val number: Int,
    val title: String,
    val draft: Boolean,
    val state: String,
    val merged: Boolean,
    val headSha: String,
    val baseSha: String,
    val headRepoId: Long?,
    val baseRepoId: Long,
    val changedFiles: Int,
)

data class CheckRunInfo(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val checkSuiteId: Long,
    val appId: Long,
    val externalId: String?,
)

data class CheckSuiteInfo(
    val id: Long,
    val status: String,
    val appId: Long,
)

data class StatusInfo(
    val context: String,
    val state: String,
)

data class WorkflowRunInfo(
    val id: Long,
    val workflowId: Long,
    val checkSuiteId: Long,
)

data class Review(
    val id: Long,
    val login: String,
    val state: String,
    val body: String,
    /** Commiten reviewen ble gitt på (`commit_id`). */
    val commitId: String? = null,
)

data class IssueComment(
    val id: Long,
    val login: String,
    val body: String,
)

data class Reactions(
    val thumbsUp: Int,
    val thumbsDown: Int,
)

data class CheckRunWrite(
    val status: String,
    val conclusion: String?,
    val title: String,
    val summary: String,
    val externalId: String?,
)

/**
 * Det Idun trenger fra GitHub. Implementasjoner kaster ved feil; bare `fileContent` (404 gir `null`)
 * og `removeLabel` (404 ignoreres) har unntak.
 */
interface GitHub {
    suspend fun pullRequest(
        repo: RepoRef,
        number: Int,
    ): PullRequest

    /**
     * Filene som er endret fra [base] til [head], fra compare-API-et. Begge er faste sha-er, så listen kan ikke
     * flytte seg med PR-ens head. Kaster når listen kan være avkortet.
     */
    suspend fun compareFiles(
        repo: RepoRef,
        base: String,
        head: String,
    ): List<ChangedFile>

    suspend fun pullRequestsForCommit(
        repo: RepoRef,
        sha: String,
    ): List<Int>

    suspend fun mergeBase(
        repo: RepoRef,
        base: String,
        head: String,
    ): String

    /** `null` bare når filen ikke finnes (HTTP 404). Alt annet uventet kaster. */
    suspend fun fileContent(
        repo: RepoRef,
        path: String,
        ref: String,
    ): String?

    suspend fun checkRuns(
        repo: RepoRef,
        sha: String,
    ): List<CheckRunInfo>

    suspend fun checkSuites(
        repo: RepoRef,
        sha: String,
    ): List<CheckSuiteInfo>

    /** Siste status per context, fra `/commits/{sha}/status`. */
    suspend fun statuses(
        repo: RepoRef,
        sha: String,
    ): List<StatusInfo>

    suspend fun workflowRuns(
        repo: RepoRef,
        sha: String,
    ): List<WorkflowRunInfo>

    /** Oppdaterer egen run med samme navn på samme sha hvis den finnes, ellers opprettes den. */
    suspend fun upsertCheckRun(
        repo: RepoRef,
        sha: String,
        name: String,
        write: CheckRunWrite,
    )

    suspend fun comments(
        repo: RepoRef,
        number: Int,
    ): List<IssueComment>

    suspend fun createComment(
        repo: RepoRef,
        number: Int,
        body: String,
    ): Long

    suspend fun updateComment(
        repo: RepoRef,
        id: Long,
        body: String,
    )

    suspend fun reactions(
        repo: RepoRef,
        commentId: Long,
    ): Reactions

    suspend fun ensureLabel(
        repo: RepoRef,
        name: String,
        color: String,
        description: String,
    )

    suspend fun addLabel(
        repo: RepoRef,
        number: Int,
        name: String,
    )

    /** 404 ignoreres. */
    suspend fun removeLabel(
        repo: RepoRef,
        number: Int,
        name: String,
    )

    suspend fun reviews(
        repo: RepoRef,
        number: Int,
    ): List<Review>

    suspend fun approve(
        repo: RepoRef,
        number: Int,
        sha: String,
        body: String,
    )

    suspend fun dismissReview(
        repo: RepoRef,
        number: Int,
        reviewId: Long,
        message: String,
    )
}

fun interface GitHubFactory {
    suspend fun forInstallation(installationId: Long): GitHub
}
