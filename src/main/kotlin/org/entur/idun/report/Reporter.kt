package org.entur.idun.report

import kotlinx.coroutines.CancellationException
import org.entur.idun.decide.Outcome
import org.entur.idun.github.GitHub
import org.entur.idun.github.GitHubApiException
import org.entur.idun.github.RepoRef
import org.entur.idun.github.Review

const val APPROVED_LABEL = "idun:approved"
const val NEEDS_HUMAN_LABEL = "idun:needs-human"

private const val APPROVED_COLOR = "1a8e60"
private const val APPROVED_DESCRIPTION = "idun: trygg refaktorering"
private const val NEEDS_HUMAN_COLOR = "ff5959"
private const val NEEDS_HUMAN_DESCRIPTION = "idun: trenger et menneske"

private val APPROVAL_MARKER = Regex("<!-- idun:approval sha=([0-9A-Za-z]+) -->")

private fun approvalBody(sha: String) =
    "Idun har vurdert refaktoreringen som trygg.\n\n<!-- idun:approval sha=$sha -->"

enum class ApprovalState { NONE, APPROVED, VETOED }

/** Skriver Iduns eget resultat til GitHub. `appLogin` er Iduns bruker; bare dens egne objekter røres. */
class Reporter(
    private val gh: GitHub,
    private val appLogin: String,
) {
    suspend fun writeCheck(
        repo: RepoRef,
        sha: String,
        outcome: Outcome,
    ) {
        gh.upsertCheckRun(repo, sha, CHECK_NAME, renderCheck(outcome))
    }

    /** Oppdaterer Iduns kommentar med markøren, ellers opprettes en ny. */
    suspend fun writeComment(
        repo: RepoRef,
        number: Int,
        body: String,
    ) {
        val existing = gh.comments(repo, number).firstOrNull { it.login == appLogin && COMMENT_MARKER in it.body }
        if (existing != null) gh.updateComment(repo, existing.id, body) else gh.createComment(repo, number, body)
    }

    /** `null` fjerner begge. Den ønskede legges til før den andre fjernes, så PR-en aldri står uten label. */
    suspend fun setLabel(
        repo: RepoRef,
        number: Int,
        label: String?,
    ) {
        require(label == null || label == APPROVED_LABEL || label == NEEDS_HUMAN_LABEL) { "Ukjent label: $label" }
        gh.ensureLabel(repo, APPROVED_LABEL, APPROVED_COLOR, APPROVED_DESCRIPTION)
        gh.ensureLabel(repo, NEEDS_HUMAN_LABEL, NEEDS_HUMAN_COLOR, NEEDS_HUMAN_DESCRIPTION)
        if (label != null) gh.addLabel(repo, number, label)
        for (other in listOf(APPROVED_LABEL, NEEDS_HUMAN_LABEL)) {
            if (other != label) gh.removeLabel(repo, number, other)
        }
    }

    suspend fun approve(
        repo: RepoRef,
        number: Int,
        sha: String,
    ) {
        gh.approve(repo, number, sha, approvalBody(sha))
    }

    /**
     * Iduns godkjenning av [sha]: `APPROVED` når Idun har en gjeldende godkjenning med markør for [sha], `VETOED`
     * når en egen review på [sha] (markør eller `commit_id`) er trukket tilbake (`DISMISSED`), ellers `NONE`.
     *
     * GitHub sier ikke hvem som trakk en review tilbake, så også Iduns egne tilbaketrekkinger gir `VETOED`. Idun
     * godkjenner altså aldri samme commit på nytt etter en tilbaketrekking; da må et menneske godkjenne, eller en
     * ny push gi en ny commit. Det koster en manuell godkjenning når tittel eller base endres på samme head, men et
     * menneskes tilbaketrekking blir aldri overkjørt.
     */
    suspend fun approvalState(
        repo: RepoRef,
        number: Int,
        sha: String,
    ): ApprovalState {
        val own = gh.reviews(repo, number).filter { it.login == appLogin }
        fun Review.isFor(sha: String) = commitId == sha || APPROVAL_MARKER.find(body)?.groupValues?.get(1) == sha
        return when {
            own.any { it.state == "DISMISSED" && it.isFor(sha) } -> ApprovalState.VETOED
            own.any { it.state == "APPROVED" && APPROVAL_MARKER.find(it.body)?.groupValues?.get(1) == sha } -> ApprovalState.APPROVED
            else -> ApprovalState.NONE
        }
    }

    /**
     * Trekker tilbake Iduns egne `APPROVED`-reviews hvis markør-sha ikke er [keepSha]. En godkjenning uten
     * lesbar markør regnes som «ikke keepSha» og trekkes tilbake (fail closed).
     *
     * Hver godkjenning forsøkes trukket selv om en tidligere feilet. Feilet noen, kastes et
     * [IllegalStateException] etterpå som lister dem, med den første feilen som årsak.
     */
    suspend fun dismissApprovals(
        repo: RepoRef,
        number: Int,
        keepSha: String?,
    ) {
        val failures = mutableListOf<Pair<Long, Exception>>()
        for (review in gh.reviews(repo, number)) {
            if (review.login != appLogin || review.state != "APPROVED") continue
            val markedSha = APPROVAL_MARKER.find(review.body)?.groupValues?.get(1)
            if (keepSha != null && markedSha == keepSha) continue
            try {
                gh.dismissReview(repo, number, review.id, "Idun: godkjenningen gjelder ikke lenger denne commiten.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += review.id to e
            }
        }
        if (failures.isEmpty()) return
        val listed =
            failures.joinToString { (id, e) ->
                "$id (${e::class.simpleName}${(e as? GitHubApiException)?.status?.let { " $it" }.orEmpty()})"
            }
        throw IllegalStateException("Klarte ikke å trekke tilbake godkjenning: $listed", failures.first().second).apply {
            failures.drop(1).forEach { addSuppressed(it.second) }
        }
    }
}
