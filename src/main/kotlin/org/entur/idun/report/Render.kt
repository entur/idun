package org.entur.idun.report

import org.entur.idun.decide.Mode
import org.entur.idun.decide.Outcome
import org.entur.idun.decide.WaitReason
import org.entur.idun.decide.externalId
import org.entur.idun.github.CheckRunWrite
import org.entur.idun.review.ReviewResponse
import org.entur.idun.rules.Reason
import org.entur.idun.rules.ReasonCode

const val CHECK_NAME = "idun/refactor"
const val COMMENT_MARKER = "<!-- idun:comment -->"

private const val SHORT_SHA = 7
private const val DETAIL_LENGTH = 300

const val NOT_REFACTOR_ANY_MORE: String =
    "$COMMENT_MARKER\n**Idun**: Tittelen er ikke lenger `refactor`, så Idun vurderer ikke denne PR-en. " +
        "Eventuell tidligere godkjenning er trukket tilbake."

private fun Outcome.requireReportable() {
    require(this !is Outcome.Ignore) { "Ignore skal aldri rapporteres" }
}

private fun reasonLines(reasons: List<Reason>): String =
    reasons.joinToString("\n") { "- `${it.code.name}`: ${defuse(it.detail, DETAIL_LENGTH)}" }

/** Modellens oppsummering og funn. All modelltekst, og filstiene, går gjennom [defuse]. */
private fun modelLines(review: ReviewResponse): String =
    buildString {
        append("Modellens oppsummering: ${defuse(review.summary)}")
        if (review.findings.isNotEmpty()) {
            append("\n\nFunn:")
            for (finding in review.findings) {
                append("\n- ${defuse(finding.file, DETAIL_LENGTH)}:${finding.line}: ${defuse(finding.text)}")
            }
        }
    }

private fun flaggedReview(outcome: Outcome.NeedsHuman): ReviewResponse? =
    if (outcome.reasons.any { it.code == ReasonCode.MODEL_FLAGGED }) outcome.review else null

fun renderCheck(outcome: Outcome): CheckRunWrite {
    outcome.requireReportable()
    return when (outcome) {
        Outcome.Ignore -> error("unreachable")
        is Outcome.Safe ->
            CheckRunWrite(
                status = "completed",
                conclusion = "success",
                title = "Trygg refaktorering",
                summary = defuse(outcome.review.summary),
                externalId = outcome.externalId(),
            )
        is Outcome.NeedsHuman ->
            CheckRunWrite(
                status = "completed",
                conclusion = "neutral",
                title = "Trenger et menneske",
                summary = reasonLines(outcome.reasons),
                externalId = outcome.externalId(),
            )
        Outcome.CiFailed ->
            CheckRunWrite(
                status = "completed",
                conclusion = "neutral",
                title = "CI feilet",
                summary = "CI er ikke grønn, så Idun vurderer ikke PR-en.",
                externalId = outcome.externalId(),
            )
        is Outcome.Waiting ->
            CheckRunWrite(
                status = "in_progress",
                conclusion = null,
                title = "Venter",
                summary =
                    when (outcome.why) {
                        WaitReason.DRAFT -> "PR-en er et utkast. Idun venter til den er klar for review."
                        WaitReason.PENDING_CI -> "Venter på at CI blir ferdig."
                    },
                externalId = outcome.externalId(),
            )
    }
}

fun renderComment(
    outcome: Outcome,
    sha: String,
    mode: Mode,
): String {
    outcome.requireReportable()
    val short = sha.take(SHORT_SHA)
    val body =
        when (outcome) {
            Outcome.Ignore -> error("unreachable")
            is Outcome.Safe ->
                "Idun har vurdert refaktoreringen som trygg (commit `$short`).\n\n${modelLines(outcome.review)}"
            is Outcome.NeedsHuman ->
                buildString {
                    append("Idun ber om at et menneske ser på denne PR-en (commit `$short`).\n\nGrunner:\n")
                    append(reasonLines(outcome.reasons))
                    flaggedReview(outcome)?.let { append("\n\n${modelLines(it)}") }
                }
            Outcome.CiFailed ->
                "CI feilet på commit `$short`. Idun vurderer ikke PR-en før CI er grønn."
            is Outcome.Waiting ->
                when (outcome.why) {
                    WaitReason.DRAFT -> "PR-en er et utkast. Idun venter med vurderingen til den er klar (commit `$short`)."
                    WaitReason.PENDING_CI -> "Idun venter på at CI blir ferdig på commit `$short`."
                }
        }
    val advise =
        if (mode == Mode.ADVISE) {
            "\n\nIdun er i advise-modus og godkjenner ikke. " +
                "Var denne vurderingen riktig? Svar med 👍 eller 👎 på denne kommentaren."
        } else {
            ""
        }
    return "$COMMENT_MARKER\n**Idun**: $body$advise"
}
