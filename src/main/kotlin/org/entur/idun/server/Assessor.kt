package org.entur.idun.server

import kotlinx.coroutines.CancellationException
import org.entur.idun.ci.evaluateCi
import org.entur.idun.config.ConfigResult
import org.entur.idun.config.IdunConfig
import org.entur.idun.config.fromYaml
import org.entur.idun.decide.Mode
import org.entur.idun.decide.Outcome
import org.entur.idun.decide.decideAfterReview
import org.entur.idun.decide.decideBeforeModel
import org.entur.idun.decide.decideBeforeReview
import org.entur.idun.decide.externalId
import org.entur.idun.github.CheckRunInfo
import org.entur.idun.github.CheckRunWrite
import org.entur.idun.github.GitHub
import org.entur.idun.github.GitHubApiException
import org.entur.idun.github.GitHubFactory
import org.entur.idun.github.PullRequest
import org.entur.idun.github.RepoRef
import org.entur.idun.report.APPROVED_LABEL
import org.entur.idun.report.ApprovalState
import org.entur.idun.report.CHECK_NAME
import org.entur.idun.report.COMMENT_MARKER
import org.entur.idun.report.NEEDS_HUMAN_LABEL
import org.entur.idun.report.NOT_REFACTOR_ANY_MORE
import org.entur.idun.report.Reporter
import org.entur.idun.report.renderCheck
import org.entur.idun.report.renderComment
import org.entur.idun.review.BehaviourReviewer
import org.entur.idun.review.ReviewFile
import org.entur.idun.review.ReviewInput
import org.entur.idun.review.ReviewResult
import org.entur.idun.rules.ChangedFile
import org.entur.idun.rules.ConventionalTitle
import org.entur.idun.rules.FileClass
import org.entur.idun.rules.FileContents
import org.entur.idun.rules.FileStatus
import org.entur.idun.rules.classify
import org.entur.idun.rules.evaluateFiles
import org.entur.idun.rules.isRefactor
import org.entur.idun.rules.parseTitle
import org.slf4j.Logger

private const val CONFIG_PATH = ".github/idun.yaml"
private const val ERROR_TEXT_LENGTH = 200

private const val SAFE_ID = "safe"
private const val NEEDS_HUMAN_ID = "needs-human"

/** `external_id` for checker som ikke er et gjenbrukbart utfall. Satt eksplisitt, så en gammel `safe` aldri blir stående. */
private const val ASSESSING_ID = "assessing"
private const val WAITING_ID = "waiting"
private const val NOT_REFACTOR_ID = "not-refactor"
private const val ERROR_ID = "error"

private val ASSESSING_CHECK =
    CheckRunWrite("in_progress", null, "Vurderer endringen", "Idun vurderer endringen.", ASSESSING_ID)

private val NOT_REFACTOR_CHECK =
    CheckRunWrite(
        "completed",
        "neutral",
        "Ikke lenger en refactor-PR",
        "Tittelen er ikke lenger `refactor`, så Idun vurderer ikke denne PR-en.",
        NOT_REFACTOR_ID,
    )

private val ERROR_CHECK =
    CheckRunWrite(
        "completed",
        "neutral",
        "Feil – trenger et menneske",
        "Idun klarte ikke å vurdere PR-en. Et menneske må se på den.",
        ERROR_ID,
    )

/** Ting som kan ligne tokens eller hemmeligheter i en feilmelding. */
private val SECRET_LIKE = Regex("""(?i)(gh[pousr]_\w+|github_pat_\w+|(bearer|token)\s+\S+)""")

/**
 * Vurderer PR-er når GitHub sender hendelser. Hver PR vurderes under sin egen lås, så to hendelser for samme
 * PR aldri kjører samtidig. Feiler lukket: alle unntak logges som `idun.error`, label og godkjenning fjernes,
 * og checken settes til `neutral` hvis det går.
 *
 * En godkjenning står bare så lenge siste vurdering av head er `Safe` i `APPROVE`. Alle andre utfall trekker den.
 *
 * Modusen kommer fra [modeOf] (Iduns egen konfigurasjon), aldri fra repoet som vurderes.
 */
class Assessor(
    private val github: GitHubFactory,
    private val reviewer: BehaviourReviewer,
    private val ownAppId: Long,
    private val appLogin: String,
    private val locks: PrLocks,
    private val log: Logger,
    private val modeOf: (RepoRef) -> Mode,
    /** Modellnavnet, til fingeravtrykket. */
    private val model: String,
) {
    private class Context(
        val gh: GitHub,
        val reporter: Reporter,
        val repo: RepoRef,
        val number: Int,
        val mode: Mode,
    )

    /** Hvor langt en vurdering kom, for feilhåndteringen. */
    private class Progress {
        var prRead = false

        /** Commiten en feil-check kan skrives på. Satt først når Idun faktisk har noe å si om PR-en. */
        var engagedSha: String? = null

        /** Godkjenning og label skal ryddes ved feil, selv uten [engagedSha] (feil under oppryddingen i `leave`). */
        var cleanOnError = false
    }

    /** Det som kommer ut av trinnene før rapporteringen. */
    private sealed interface Assessment {
        /** [fingerprint] er satt bare når modellen faktisk svarte; da kan utfallet gjenbrukes. */
        data class Fresh(
            val outcome: Outcome,
            val costUsd: Double = 0.0,
            val fingerprint: String? = null,
            /** Merge-basen vurderingen bygde på, når den ble hentet. */
            val mergeBase: String? = null,
        ) : Assessment

        /** Egen ferdig check med samme fingeravtrykk: modellen kalles ikke på nytt. */
        data class Reused(
            val safe: Boolean,
            val mergeBase: String,
        ) : Assessment
    }

    suspend fun onPullRequest(
        installationId: Long,
        repo: RepoRef,
        number: Int,
        action: String,
    ) {
        log.debug("idun.event repo={} pr={} action={}", repo.slug(), number, action)
        assess(installationId, repo, number, action)
    }

    /** For `check_suite` og `status`: vurderer alle åpne PR-er som har [sha]. Lukkede PR-er hoppes over. */
    suspend fun onCommit(
        installationId: Long,
        repo: RepoRef,
        sha: String,
    ) {
        if (modeOf(repo) == Mode.OFF) return
        val numbers =
            try {
                github.forInstallation(installationId).pullRequestsForCommit(repo, sha)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(repo, null, sha, e)
                return
            }
        for (number in numbers) assess(installationId, repo, number, action = null)
    }

    /** [action] er `pull_request`-handlingen, eller `null` for `check_suite` og `status`. */
    private suspend fun assess(
        installationId: Long,
        repo: RepoRef,
        number: Int,
        action: String?,
    ) = locks.withLock(repo, number) {
        val mode = modeOf(repo)
        if (mode == Mode.OFF) return@withLock

        var ctx: Context? = null
        val progress = Progress()
        try {
            val client = github.forInstallation(installationId)
            ctx = Context(client, Reporter(client, appLogin), repo, number, mode)
            run(ctx, progress, action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(repo, number, progress.engagedSha, e)
            ctx?.let { failClosed(it, progress) }
        }
    }

    /**
     * Etter en feil: trekk godkjenning og label (hver for seg, så én feil ikke stopper den andre), og skriv en
     * feil-check. Gjøres når Idun har noe å si om PR-en, eller når PR-en ikke engang kunne leses (da vet vi ikke).
     */
    private suspend fun failClosed(
        ctx: Context,
        progress: Progress,
    ) {
        val sha = progress.engagedSha
        if (sha == null && progress.prRead && !progress.cleanOnError) return
        bestEffort(ctx, sha) { ctx.reporter.dismissApprovals(ctx.repo, ctx.number, keepSha = null) }
        bestEffort(ctx, sha) { ctx.reporter.setLabel(ctx.repo, ctx.number, null) }
        if (sha != null) bestEffort(ctx, sha) { ctx.gh.upsertCheckRun(ctx.repo, sha, CHECK_NAME, ERROR_CHECK) }
    }

    private suspend fun bestEffort(
        ctx: Context,
        sha: String?,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(ctx.repo, ctx.number, sha, e)
        }
    }

    private suspend fun run(
        ctx: Context,
        progress: Progress,
        action: String?,
    ) {
        val pr = ctx.gh.pullRequest(ctx.repo, ctx.number)
        progress.prRead = true
        if (pr.state == "closed") {
            // Én feedback-linje per lukking: ikke fra `check_suite`, `status` eller `edited` på en lukket PR.
            if (action == "closed") logFeedback(ctx, pr)
            return
        }
        val title = parseTitle(pr.title)
        if (!title.isRefactor()) {
            leave(ctx, pr, progress)
            return
        }
        progress.engagedSha = pr.headSha
        ctx.reporter.dismissApprovals(ctx.repo, ctx.number, keepSha = pr.headSha)
        when (val assessment = evaluate(ctx, pr, title)) {
            is Assessment.Fresh -> report(ctx, pr, assessment)
            is Assessment.Reused -> reuse(ctx, pr, assessment)
        }
    }

    /**
     * Tittelen er ikke `refactor` (lenger). Har Idun vurdert akkurat denne PR-en før (egen kommentar på PR-en),
     * ryddes godkjenning, label og kommentar, og checken settes til «ikke lenger refactor». En egen check på commiten
     * alene er ikke nok: checken hører til commiten og kan komme fra en annen PR med samme head. En egen
     * godkjenning på PR-en trekkes uansett.
     */
    private suspend fun leave(
        ctx: Context,
        pr: PullRequest,
        progress: Progress,
    ) {
        // Feiler lesingen under, vet vi ikke om Idun har godkjent før: rydd godkjenning og label (uten feil-check).
        progress.cleanOnError = true
        val comment = ownComment(ctx)
        if (comment == null) {
            ctx.reporter.dismissApprovals(ctx.repo, ctx.number, keepSha = null)
            return
        }
        val ownRun = ownRun(ctx.gh.checkRuns(ctx.repo, pr.headSha))
        // Allerede ryddet for denne commiten (eller kommentaren sier det, og det finnes ingen check å oppdatere).
        if (ownRun?.externalId == NOT_REFACTOR_ID || (ownRun == null && comment.body == NOT_REFACTOR_ANY_MORE)) return

        progress.engagedSha = pr.headSha
        ctx.reporter.dismissApprovals(ctx.repo, ctx.number, keepSha = null)
        ctx.reporter.setLabel(ctx.repo, ctx.number, null)
        ctx.reporter.writeComment(ctx.repo, ctx.number, NOT_REFACTOR_ANY_MORE)
        ctx.gh.upsertCheckRun(ctx.repo, pr.headSha, CHECK_NAME, NOT_REFACTOR_CHECK)
        logAssessed(ctx, pr.headSha, NOT_REFACTOR_ID, emptyList(), 0.0, reused = false)
    }

    /**
     * Trinnene før modellen, billigst først, og modellen til slutt. En tidligere `safe`/`needs-human` gjenbrukes
     * bare når alle reglene fortsatt slipper PR-en gjennom og fingeravtrykket av det modellen fikk se er likt
     * (samme PR, merge-base, tittel og filer), rett før modellen ellers ville blitt kalt.
     */
    private suspend fun evaluate(
        ctx: Context,
        pr: PullRequest,
        title: ConventionalTitle?,
    ): Assessment {
        val (gh, repo) = ctx.gh to ctx.repo
        decideBeforeReview(ctx.mode, pr, title, null, null, null)?.let { return Assessment.Fresh(it) }

        val runs = gh.checkRuns(repo, pr.headSha)
        val ci =
            evaluateCi(runs, gh.checkSuites(repo, pr.headSha), gh.statuses(repo, pr.headSha), gh.workflowRuns(repo, pr.headSha), ownAppId)
        decideBeforeReview(ctx.mode, pr, title, ci, null, null)?.let { return Assessment.Fresh(it) }

        // Alltid fra base: en PR kan endre sin egen konfig.
        val config = IdunConfig.fromYaml(gh.fileContent(repo, CONFIG_PATH, pr.baseSha))
        decideBeforeReview(ctx.mode, pr, title, ci, config, null)?.let { return Assessment.Fresh(it) }
        val idunConfig = (config as? ConfigResult.Ok)?.config ?: error("Ugyldig konfig skulle gitt NeedsHuman")

        val mergeBase = gh.mergeBase(repo, pr.baseSha, pr.headSha)
        // Fra faste sha-er: `/pulls/{n}/files` følger PR-ens gjeldende head og kunne gitt filene til en nyere push.
        val files = gh.compareFiles(repo, mergeBase, pr.headSha)
        check(files.size == pr.changedFiles) {
            "compare ga ${files.size} filer, men PR-en har changed_files=${pr.changedFiles}"
        }
        val contents = sourceContents(ctx, pr, mergeBase, files, idunConfig)
        val rules = evaluateFiles(files, contents, idunConfig)
        // Den eneste veien til modellen.
        decideBeforeModel(ctx.mode, pr, title, ci, config, rules)?.let { return Assessment.Fresh(it, mergeBase = mergeBase) }

        val input =
            ReviewInput(
                title = pr.title,
                files =
                    rules.sourceFiles.map { file ->
                        val content = contents.getValue(file.path)
                        ReviewFile(file.path, checkNotNull(file.patch), content.before, content.after)
                    },
            )
        val fingerprint = reviewFingerprint(ctx.number, mergeBase, input, model)
        ownRun(runs)?.let { run ->
            when {
                run.status != "completed" -> Unit
                run.externalId == "$SAFE_ID:$fingerprint" && run.conclusion == "success" -> return Assessment.Reused(safe = true, mergeBase)
                run.externalId == "$NEEDS_HUMAN_ID:$fingerprint" && run.conclusion == "neutral" -> return Assessment.Reused(safe = false, mergeBase)
            }
        }

        // Modellen har ikke godtatt denne diffen (ny base eller tittel på samme head): ingen godkjenning imens.
        ctx.reporter.dismissApprovals(repo, ctx.number, keepSha = null)
        ctx.reporter.setLabel(repo, ctx.number, null)
        gh.upsertCheckRun(repo, pr.headSha, CHECK_NAME, ASSESSING_CHECK)
        val result = reviewer.review(input)
        val outcome = decideAfterReview(result, input)
        // En feilet modell (timeout, budsjett, ugyldig svar) gjenbrukes ikke: neste hendelse prøver igjen.
        return when (result) {
            is ReviewResult.Answered -> Assessment.Fresh(outcome, result.costUsd, fingerprint, mergeBase)
            is ReviewResult.Failed -> Assessment.Fresh(outcome, mergeBase = mergeBase)
        }
    }

    /**
     * Innhold for kildekodefilene: `before` fra den gamle stien på merge-base (ikke for nye filer), `after` fra head
     * (ikke for slettede filer). Mangler innhold som skulle vært der (404), utelates filen, og reglene gir
     * `MISSING_DIFF`. Andre feil kaster.
     */
    private suspend fun sourceContents(
        ctx: Context,
        pr: PullRequest,
        mergeBase: String,
        files: List<ChangedFile>,
        config: IdunConfig,
    ): Map<String, FileContents> {
        val contents = mutableMapOf<String, FileContents>()
        for (file in files.filter { classify(it, config) == FileClass.SOURCE }) {
            val before =
                if (file.status == FileStatus.ADDED) {
                    null
                } else {
                    ctx.gh.fileContent(ctx.repo, file.previousPath ?: file.path, mergeBase) ?: continue
                }
            val after =
                if (file.status == FileStatus.REMOVED) {
                    null
                } else {
                    ctx.gh.fileContent(ctx.repo, file.path, pr.headSha) ?: continue
                }
            contents[file.path] = FileContents(before, after)
        }
        return contents
    }

    /**
     * Er PR-en fortsatt det som ble vurdert? Head og tittel må være like, og merge-basen (når vurderingen brukte den)
     * må være lik. Rå `baseSha` sammenlignes ikke: den flytter seg hver gang main gjør det, mens diffen ikke endres.
     * Er noe endret, skrives ingenting; `synchronize`/`edited` følger.
     */
    private suspend fun stillReviewed(
        ctx: Context,
        reviewed: PullRequest,
        mergeBase: String?,
    ): Boolean {
        val now = ctx.gh.pullRequest(ctx.repo, ctx.number)
        val same =
            now.headSha == reviewed.headSha &&
                now.title == reviewed.title &&
                (mergeBase == null || ctx.gh.mergeBase(ctx.repo, now.baseSha, now.headSha) == mergeBase)
        if (!same) log.info("idun.stale repo={} pr={} sha={}", ctx.repo.slug(), ctx.number, reviewed.headSha)
        return same
    }

    /** Godkjenningen står bare når utfallet er `Safe` i `APPROVE`. Alt annet trekker den, også `Safe` i advise. */
    private suspend fun dismissUnlessApproving(
        ctx: Context,
        safe: Boolean,
    ) {
        if (!(safe && ctx.mode == Mode.APPROVE)) ctx.reporter.dismissApprovals(ctx.repo, ctx.number, keepSha = null)
    }

    private suspend fun report(
        ctx: Context,
        pr: PullRequest,
        assessment: Assessment.Fresh,
    ) {
        val outcome = assessment.outcome
        val sha = pr.headSha
        if (!stillReviewed(ctx, pr, assessment.mergeBase)) return
        // Ignore kan ikke komme hit (modus og tittel er sjekket), men skal aldri rapporteres.
        if (outcome is Outcome.Ignore) return

        dismissUnlessApproving(ctx, outcome is Outcome.Safe)
        ctx.gh.upsertCheckRun(ctx.repo, sha, CHECK_NAME, checkWrite(outcome, assessment.fingerprint))
        when (outcome) {
            is Outcome.Safe -> {
                ctx.reporter.writeComment(ctx.repo, ctx.number, renderComment(outcome, sha, ctx.mode))
                ctx.reporter.setLabel(ctx.repo, ctx.number, APPROVED_LABEL)
                approveIfAllowed(ctx, sha)
            }
            is Outcome.NeedsHuman -> {
                ctx.reporter.writeComment(ctx.repo, ctx.number, renderComment(outcome, sha, ctx.mode))
                ctx.reporter.setLabel(ctx.repo, ctx.number, NEEDS_HUMAN_LABEL)
            }
            Outcome.CiFailed, is Outcome.Waiting -> ctx.reporter.setLabel(ctx.repo, ctx.number, null)
            Outcome.Ignore -> Unit
        }
        val reasons = (outcome as? Outcome.NeedsHuman)?.reasons.orEmpty().map { it.code.name }
        logAssessed(ctx, sha, outcome.name(), reasons, assessment.costUsd, reused = false)
    }

    private suspend fun reuse(
        ctx: Context,
        pr: PullRequest,
        assessment: Assessment.Reused,
    ) {
        if (!stillReviewed(ctx, pr, assessment.mergeBase)) return
        dismissUnlessApproving(ctx, assessment.safe)
        ctx.reporter.setLabel(ctx.repo, ctx.number, if (assessment.safe) APPROVED_LABEL else NEEDS_HUMAN_LABEL)
        if (assessment.safe) approveIfAllowed(ctx, pr.headSha)
        logAssessed(ctx, pr.headSha, if (assessment.safe) SAFE_ID else NEEDS_HUMAN_ID, emptyList(), 0.0, reused = true)
    }

    /**
     * Godkjenner bare i `APPROVE` (advise godkjenner aldri), og trekker tilbake hvis head flyttet seg imens.
     * Er en egen review på [sha] trukket tilbake, godkjennes [sha] aldri på nytt (se [Reporter.approvalState]).
     */
    private suspend fun approveIfAllowed(
        ctx: Context,
        sha: String,
    ) {
        if (ctx.mode != Mode.APPROVE) return
        when (ctx.reporter.approvalState(ctx.repo, ctx.number, sha)) {
            ApprovalState.APPROVED -> return
            ApprovalState.VETOED -> {
                log.info("idun.approval_vetoed repo={} pr={} sha={}", ctx.repo.slug(), ctx.number, sha)
                return
            }
            ApprovalState.NONE -> Unit
        }
        ctx.reporter.approve(ctx.repo, ctx.number, sha)
        if (ctx.gh.pullRequest(ctx.repo, ctx.number).headSha != sha) {
            ctx.reporter.dismissApprovals(ctx.repo, ctx.number, keepSha = null)
        }
    }

    private suspend fun logFeedback(
        ctx: Context,
        pr: PullRequest,
    ) {
        val run = ownRun(ctx.gh.checkRuns(ctx.repo, pr.headSha))
        val comment = ownComment(ctx)
        if (run == null && comment == null) return
        val reactions = comment?.let { ctx.gh.reactions(ctx.repo, it.id) }
        log.info(
            "idun.feedback repo={} pr={} sha={} merged={} mode={} outcome={} thumbsUp={} thumbsDown={}",
            ctx.repo.slug(),
            ctx.number,
            pr.headSha,
            pr.merged,
            ctx.mode.name.lowercase(),
            // `safe:<fingeravtrykk>` → `safe`
            run?.externalId?.substringBefore(':'),
            reactions?.thumbsUp,
            reactions?.thumbsDown,
        )
    }

    /** Aldri modellens tekst: bare utfall, grunnkoder og kostnad. */
    private fun logAssessed(
        ctx: Context,
        sha: String,
        outcome: String,
        reasons: List<String>,
        costUsd: Double,
        reused: Boolean,
    ) {
        log.info(
            "idun.assessed repo={} pr={} sha={} mode={} outcome={} reasons={} costUsd={} reused={}",
            ctx.repo.slug(),
            ctx.number,
            sha,
            ctx.mode.name.lowercase(),
            outcome,
            reasons,
            costUsd,
            reused,
        )
    }

    /** Bare typen, HTTP-status og en vasket, avkortet melding. Unntaket sendes ikke til loggeren. */
    private fun logError(
        repo: RepoRef,
        number: Int?,
        sha: String?,
        e: Exception,
    ) {
        val message = e.message?.let { SECRET_LIKE.replace(it, "[fjernet]").take(ERROR_TEXT_LENGTH) }
        log.error(
            "idun.error repo={} pr={} sha={} errorType={} status={} message={} at={}",
            repo.slug(),
            number,
            sha,
            e::class.qualifiedName,
            (e as? GitHubApiException)?.status,
            message,
            e.stackTrace.take(3).joinToString(" < "),
        )
    }

    private fun ownRun(runs: List<CheckRunInfo>): CheckRunInfo? =
        runs.filter { it.appId == ownAppId && it.name == CHECK_NAME }.maxByOrNull { it.id }

    private suspend fun ownComment(ctx: Context) =
        ctx.gh.comments(ctx.repo, ctx.number).firstOrNull { it.login == appLogin && COMMENT_MARKER in it.body }
}

/**
 * Checken for et utfall. `safe`/`needs-human` fra et faktisk modellsvar får fingeravtrykket med
 * (`safe:<fp>`), så det bare gjenbrukes for nøyaktig samme input. `Waiting` får egen id, så en gammel `safe` ikke
 * blir stående (GitHub beholder `external_id` når den ikke sendes).
 */
private fun checkWrite(
    outcome: Outcome,
    fingerprint: String?,
): CheckRunWrite {
    val write = renderCheck(outcome)
    val id =
        when {
            outcome is Outcome.Waiting -> WAITING_ID
            fingerprint != null && write.externalId != null -> "${write.externalId}:$fingerprint"
            else -> write.externalId
        }
    return write.copy(externalId = id)
}

private fun RepoRef.slug() = "$owner/$name"

private fun Outcome.name(): String =
    externalId() ?: when (this) {
        is Outcome.Waiting -> "waiting-${why.name.lowercase()}"
        else -> "ignore"
    }
