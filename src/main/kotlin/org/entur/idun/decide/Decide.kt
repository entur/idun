package org.entur.idun.decide

import org.entur.idun.ci.CiState
import org.entur.idun.config.ConfigResult
import org.entur.idun.github.PullRequest
import org.entur.idun.review.Acceptance
import org.entur.idun.review.ReviewInput
import org.entur.idun.review.ReviewResponse
import org.entur.idun.review.ReviewResult
import org.entur.idun.review.acceptReview
import org.entur.idun.rules.ConventionalTitle
import org.entur.idun.rules.Reason
import org.entur.idun.rules.ReasonCode
import org.entur.idun.rules.RuleResult
import org.entur.idun.rules.isRefactor

enum class Mode { OFF, ADVISE, APPROVE }

/** Nøyaktig `off`, `advise` og `approve`. Alt annet, også `null`, er `OFF`. */
fun parseMode(value: String?): Mode =
    when (value) {
        "advise" -> Mode.ADVISE
        "approve" -> Mode.APPROVE
        else -> Mode.OFF
    }

enum class WaitReason { DRAFT, PENDING_CI }

sealed interface Outcome {
    data object Ignore : Outcome

    data class Waiting(
        val why: WaitReason,
    ) : Outcome

    data object CiFailed : Outcome

    data class NeedsHuman(
        val reasons: List<Reason>,
        val review: ReviewResponse? = null,
    ) : Outcome

    data class Safe(
        val review: ReviewResponse,
    ) : Outcome
}

private const val MAX_CHANGED_FILES = 100

private fun needsHuman(
    code: ReasonCode,
    detail: String,
): Outcome.NeedsHuman = Outcome.NeedsHuman(listOf(Reason(code, detail)))

/**
 * Avgjørelsen som kan tas før modellen kalles. Gir `null` når PR-en skal videre til review.
 * Parametere som er `null` er ikke hentet ennå; kalleren stopper ved første ikke-`null` svar.
 * Rekkefølgen er viktig: billige sjekker først, slik at vi ikke henter data vi ikke trenger.
 *
 * Et `null`-svar er bare grønt lys til modellen når `ci`, `config` og `rules` alle er ikke-`null`.
 * Ellers betyr `null` «hent neste steg». Bruk [decideBeforeModel] rett før modellkallet.
 */
fun decideBeforeReview(
    mode: Mode,
    pr: PullRequest,
    title: ConventionalTitle?,
    ci: CiState?,
    config: ConfigResult?,
    rules: RuleResult?,
): Outcome? {
    if (mode == Mode.OFF || !title.isRefactor()) return Outcome.Ignore
    if (title?.breaking == true) return needsHuman(ReasonCode.BREAKING, "Tittelen markerer en breaking change")
    // headRepoId == null er en slettet fork: feiler lukket.
    if (pr.headRepoId != pr.baseRepoId) return needsHuman(ReasonCode.FORK, "PR-en kommer fra en fork")
    if (pr.draft) return Outcome.Waiting(WaitReason.DRAFT)
    when (ci) {
        CiState.PENDING -> return Outcome.Waiting(WaitReason.PENDING_CI)
        CiState.RED -> return Outcome.CiFailed
        CiState.GREEN, null -> Unit
    }
    if (pr.changedFiles > MAX_CHANGED_FILES) {
        return needsHuman(ReasonCode.TOO_MANY_FILES, "${pr.changedFiles} filer endret, høyst $MAX_CHANGED_FILES")
    }
    if (config is ConfigResult.Invalid) return needsHuman(ReasonCode.INVALID_CONFIG, config.reason)
    if (rules != null && !rules.eligible) return Outcome.NeedsHuman(rules.reasons)
    return null
}

/**
 * Samme avgjørelse som [decideBeforeReview], men med alle data påkrevd. Dette er det eneste kallet
 * der et `null`-svar gir lov til å kalle modellen.
 */
fun decideBeforeModel(
    mode: Mode,
    pr: PullRequest,
    title: ConventionalTitle?,
    ci: CiState,
    config: ConfigResult,
    rules: RuleResult,
): Outcome? = decideBeforeReview(mode, pr, title, ci, config, rules)

fun decideAfterReview(
    result: ReviewResult,
    input: ReviewInput,
): Outcome =
    when (result) {
        is ReviewResult.Failed ->
            needsHuman(
                if (result.budgetSpent) ReasonCode.BUDGET_SPENT else ReasonCode.REVIEW_FAILED,
                result.reason,
            )
        is ReviewResult.Answered ->
            when (val acceptance = acceptReview(result.response, input)) {
                Acceptance.Accepted -> Outcome.Safe(result.response)
                is Acceptance.Rejected ->
                    Outcome.NeedsHuman(
                        listOf(Reason(ReasonCode.MODEL_FLAGGED, acceptance.why)),
                        review = result.response,
                    )
            }
    }

/** Verdien som lagres i check runen. */
fun Outcome.externalId(): String? =
    when (this) {
        is Outcome.Safe -> "safe"
        is Outcome.NeedsHuman -> "needs-human"
        Outcome.CiFailed -> "ci-failed"
        Outcome.Ignore, is Outcome.Waiting -> null
    }
