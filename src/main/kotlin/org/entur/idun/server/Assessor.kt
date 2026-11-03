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
import org.entur.idun.report.CHECK_NAME
import org.entur.idun.report.COMMENT_MARKER
import org.entur.idun.report.NEEDS_HUMAN_LABEL
import org.entur.idun.report.NOT_REFACTOR_ANY_MORE
import org.entur.idun.report.Reporter
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

/** `external_id` for checker som ikke er et utfall. Satt eksplisitt, så en gammel `safe` aldri blir stående. */
private const val ASSESSING_ID = "assessing"
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
 * PR aldri kjører samtidig. Feiler lukket: alle unntak logges som `idun.error`, og checken settes til
 * `neutral` hvis det går.
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
) {
    private class Context(
        val gh: GitHub,
        val reporter: Reporter,
        val repo: RepoRef,
        val number: Int,
        val mode: Mode,
    )

    /** Det som kommer ut av trinnene før rapporteringen. */
    private sealed interface Assessment {
        data class Fresh(
            val outcome: Outcome,
            val costUsd: Double = 0.0,
        ) : Assessment

        /** Egen ferdig check på samme commit med `safe` eller `needs-human`: modellen kalles ikke på nytt. */
        data class Reused(
            val externalId: String,
        ) : Assessment
    }

    suspend fun onPullRequest(
        installationId: Long,
        repo: RepoRef,
        number: Int,
        action: String,
    ) {
        log.debug("idun.event repo={} pr={} action={}", repo.slug(), number, action)
        assess(installationId, repo, number)
    }

    /** For `check_suite` og `status`: vurderer alle PR-er som har [sha]. */
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
        for (number in numbers) assess(installationId, repo, number)
    }

    private suspend fun assess(
        installationId: Long,
        repo: RepoRef,
        number: Int,
    ) = locks.withLock(repo, number) {
        val mode = modeOf(repo)
        if (mode == Mode.OFF) return@withLock

        var gh: GitHub? = null
        // Commiten en feil-check kan skrives på. Satt først når Idun faktisk har noe å si om PR-en.
        var engagedSha: String? = null
        try {
            val client = github.forInstallation(installationId)
            gh = client
            run(Context(client, Reporter(client, appLogin), repo, number, mode)) { engagedSha = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(repo, number, engagedSha, e)
            val client = gh
            val sha = engagedSha
            if (client != null && sha != null) {
                try {
                    client.upsertCheckRun(repo, sha, CHECK_NAME, ERROR_CHECK)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logError(repo, number, sha, e)
                }
            }
        }
    }

    private suspend fun run(
        ctx: Context,
        engage: (String) -> Unit,
    ) {
        val pr = ctx.gh.pullRequest(ctx.repo, ctx.number)
        if (pr.state == "closed") {
            logFeedback(ctx, pr)
            return
        }
        val title = parseTitle(pr.title)
        if (!title.isRefactor()) {
            leave(ctx, pr, engage)
            return
        }
        engage(pr.headSha)
        ctx.reporter.dismissApprovals(ctx.repo, ctx.number, keepSha = pr.headSha)
        when (val assessment = evaluate(ctx, pr, title)) {
            is Assessment.Fresh -> report(ctx, pr, assessment)
            is Assessment.Reused -> reuse(ctx, pr, assessment)
        }
    }

    /** Tittelen er ikke `refactor` (lenger). Har Idun vurdert PR-en før, ryddes godkjenning, label og kommentar. */
    private suspend fun leave(
        ctx: Context,
        pr: PullRequest,
        engage: (String) -> Unit,
    ) {
        val ownRun = ownRun(ctx.gh.checkRuns(ctx.repo, pr.headSha))
        val comment = ownComment(ctx)
        if (ownRun == null && comment == null) return
        // Allerede ryddet for denne commiten (eller kommentaren sier det, og det finnes ingen check å oppdatere).
        if (ownRun?.externalId == NOT_REFACTOR_ID || (ownRun == null && comment?.body == NOT_REFACTOR_ANY_MORE)) return

        engage(pr.headSha)
        ctx.reporter.dismissApprovals(ctx.repo, ctx.number, keepSha = null)
        ctx.reporter.setLabel(ctx.repo, ctx.number, null)
        ctx.reporter.writeComment(ctx.repo, ctx.number, NOT_REFACTOR_ANY_MORE)
        ctx.gh.upsertCheckRun(ctx.repo, pr.headSha, CHECK_NAME, NOT_REFACTOR_CHECK)
        logAssessed(ctx, pr.headSha, NOT_REFACTOR_ID, emptyList(), 0.0, reused = false)
    }

    /**
     * Trinnene før modellen, billigst først, og modellen til slutt. En tidligere `safe`/`needs-human` på samme
     * commit gjenbrukes bare når alle reglene fortsatt slipper PR-en gjennom, rett før modellen ellers ville blitt
     * kalt. Da blir en PR som er blitt draft, har fått rød CI eller ny base-konfig, vurdert på nytt.
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

        val files = gh.pullRequestFiles(repo, ctx.number)
        val contents = sourceContents(ctx, pr, files, idunConfig)
        val rules = evaluateFiles(files, contents, idunConfig)
        // Den eneste veien til modellen.
        decideBeforeModel(ctx.mode, pr, title, ci, config, rules)?.let { return Assessment.Fresh(it) }

        ownRun(runs)
            ?.takeIf { it.status == "completed" && (it.externalId == "safe" || it.externalId == "needs-human") }
            ?.let { return Assessment.Reused(it.externalId!!) }

        gh.upsertCheckRun(repo, pr.headSha, CHECK_NAME, ASSESSING_CHECK)
        val input =
            ReviewInput(
                title = pr.title,
                files =
                    rules.sourceFiles.map { file ->
                        val content = contents.getValue(file.path)
                        ReviewFile(file.path, checkNotNull(file.patch), content.before, content.after)
                    },
            )
        val result = reviewer.review(input)
        return Assessment.Fresh(decideAfterReview(result, input), (result as? ReviewResult.Answered)?.costUsd ?: 0.0)
    }

    /**
     * Innhold for kildekodefilene: `before` fra den gamle stien på merge-base (ikke for nye filer), `after` fra head
     * (ikke for slettede filer). Mangler innhold som skulle vært der (404), utelates filen, og reglene gir
     * `MISSING_DIFF`. Andre feil kaster.
     */
    private suspend fun sourceContents(
        ctx: Context,
        pr: PullRequest,
        files: List<ChangedFile>,
        config: IdunConfig,
    ): Map<String, FileContents> {
        val sources = files.filter { classify(it, config) == FileClass.SOURCE }
        if (sources.isEmpty()) return emptyMap()
        val mergeBase = ctx.gh.mergeBase(ctx.repo, pr.baseSha, pr.headSha)
        val contents = mutableMapOf<String, FileContents>()
        for (file in sources) {
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

    private suspend fun report(
        ctx: Context,
        pr: PullRequest,
        assessment: Assessment.Fresh,
    ) {
        val outcome = assessment.outcome
        val sha = pr.headSha
        // Har head flyttet seg, får den nye commiten sin egen hendelse. Ingenting skrives for den gamle.
        if (ctx.gh.pullRequest(ctx.repo, ctx.number).headSha != sha) {
            log.info("idun.stale repo={} pr={} sha={}", ctx.repo.slug(), ctx.number, sha)
            return
        }
        // Ignore kan ikke komme hit (modus og tittel er sjekket), men skal aldri rapporteres.
        if (outcome is Outcome.Ignore) return

        ctx.reporter.writeCheck(ctx.repo, sha, outcome)
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
        val safe = assessment.externalId == "safe"
        ctx.reporter.setLabel(ctx.repo, ctx.number, if (safe) APPROVED_LABEL else NEEDS_HUMAN_LABEL)
        if (safe) approveIfAllowed(ctx, pr.headSha)
        logAssessed(ctx, pr.headSha, assessment.externalId, emptyList(), 0.0, reused = true)
    }

    /** Godkjenner bare i `APPROVE` (advise godkjenner aldri), og trekker tilbake hvis head flyttet seg imens. */
    private suspend fun approveIfAllowed(
        ctx: Context,
        sha: String,
    ) {
        if (ctx.mode != Mode.APPROVE) return
        if (ctx.reporter.hasApproval(ctx.repo, ctx.number, sha)) return
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
            run?.externalId,
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

private fun RepoRef.slug() = "$owner/$name"

private fun Outcome.name(): String =
    externalId() ?: when (this) {
        is Outcome.Waiting -> "waiting-${why.name.lowercase()}"
        else -> "ignore"
    }
