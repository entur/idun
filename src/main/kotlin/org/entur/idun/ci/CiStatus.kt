package org.entur.idun.ci

import org.entur.idun.github.CheckRunInfo
import org.entur.idun.github.CheckSuiteInfo
import org.entur.idun.github.StatusInfo
import org.entur.idun.github.WorkflowRunInfo

enum class CiState { GREEN, PENDING, RED }

const val GITHUB_ACTIONS_APP_ID = 15368L

private val GREEN_CONCLUSIONS = setOf("success", "neutral", "skipped")

/**
 * Samlet CI-status for en commit, etter samme regler som freyja ADR 0004. Feiler lukket: alt som ikke
 * eksplisitt er grønt eller ventende (ukjente strenger, `conclusion == null` på en ferdig run osv.) er `RED`.
 * `RED` slår `PENDING`, som slår `GREEN`. Uten noe CI å vurdere er svaret `PENDING`.
 *
 * Iduns egen check kjennes igjen på app-id **og** navn ([ownAppId] og [ownCheckName]), ikke på app-id alene: kjører
 * Idun som GitHub Actions (`assess --write`), har den samme app-id ([GITHUB_ACTIONS_APP_ID]) som repoets egen CI, og
 * da må CI-en fra samme app fortsatt telle. Suiter filtreres derfor ikke på egen app-id. Som GitHub-app er Iduns
 * app-id unik og suiten dens er ikke en Actions-suite, så den ble aldri vurdert uansett.
 *
 * I Actions-modus er Idun-workflowens egen jobb også en Actions-run. Den startes av `workflow_run` og kjører på
 * default-branchens sha, ikke på PR-ens head, så den dukker ikke opp her. En Actions-suite på head som ikke er
 * ferdig gir alltid `PENDING`, også om den bare venter på Iduns egen check (feiler lukket).
 */
fun evaluateCi(
    runs: List<CheckRunInfo>,
    suites: List<CheckSuiteInfo>,
    statuses: List<StatusInfo>,
    workflowRuns: List<WorkflowRunInfo>,
    ownAppId: Long,
    ownCheckName: String,
): CiState {
    val states = mutableListOf<CiState>()

    suites
        .filter { it.appId == GITHUB_ACTIONS_APP_ID }
        .forEach { if (it.status != "completed") states += CiState.PENDING }

    val latestRuns =
        runs
            .filterNot { it.appId == ownAppId && it.name == ownCheckName }
            .groupBy { it.checkSuiteId to it.name }
            .values
            .map { group -> group.maxBy { it.id } }

    val workflowRunBySuite = workflowRuns.associateBy { it.checkSuiteId }

    fun supersededByNewerWorkflowRun(run: CheckRunInfo): Boolean {
        val workflowRun = workflowRunBySuite[run.checkSuiteId] ?: return false
        return workflowRuns.any { it.workflowId == workflowRun.workflowId && it.id > workflowRun.id }
    }

    for (run in latestRuns) {
        states +=
            when {
                run.status != "completed" -> CiState.PENDING
                run.conclusion in GREEN_CONCLUSIONS -> CiState.GREEN
                run.conclusion == "cancelled" && supersededByNewerWorkflowRun(run) -> continue
                else -> CiState.RED
            }
    }

    for (status in statuses) {
        states +=
            when (status.state) {
                "success" -> CiState.GREEN
                "pending" -> CiState.PENDING
                else -> CiState.RED
            }
    }

    return when {
        CiState.RED in states -> CiState.RED
        CiState.PENDING in states -> CiState.PENDING
        CiState.GREEN in states -> CiState.GREEN
        else -> CiState.PENDING
    }
}
