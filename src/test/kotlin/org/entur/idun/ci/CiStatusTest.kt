package org.entur.idun.ci

import io.kotest.matchers.shouldBe
import org.entur.idun.github.CheckRunInfo
import org.entur.idun.github.CheckSuiteInfo
import org.entur.idun.github.StatusInfo
import org.entur.idun.github.WorkflowRunInfo
import org.entur.idun.report.CHECK_NAME
import org.junit.jupiter.api.Test

class CiStatusTest {
    private val ownAppId = 999L

    private fun run(
        id: Long,
        name: String = "build",
        status: String = "completed",
        conclusion: String? = "success",
        suite: Long = 1,
        appId: Long = GITHUB_ACTIONS_APP_ID,
    ) = CheckRunInfo(id, name, status, conclusion, suite, appId, null)

    private fun suite(
        id: Long = 1,
        status: String = "completed",
        appId: Long = GITHUB_ACTIONS_APP_ID,
    ) = CheckSuiteInfo(id, status, appId)

    private fun eval(
        runs: List<CheckRunInfo> = emptyList(),
        suites: List<CheckSuiteInfo> = emptyList(),
        statuses: List<StatusInfo> = emptyList(),
        workflowRuns: List<WorkflowRunInfo> = emptyList(),
        ownAppId: Long = this.ownAppId,
    ) = evaluateCi(runs, suites, statuses, workflowRuns, ownAppId, CHECK_NAME)

    @Test
    fun `ingen CI gir PENDING`() {
        eval() shouldBe CiState.PENDING
    }

    @Test
    fun `egen check run ignoreres`() {
        eval(
            runs = listOf(run(1), run(2, name = CHECK_NAME, status = "in_progress", conclusion = null, suite = 2, appId = ownAppId)),
            suites = listOf(suite(1), suite(2, status = "in_progress", appId = ownAppId)),
        ) shouldBe CiState.GREEN
    }

    @Test
    fun `bare egen check run gir PENDING`() {
        eval(
            runs = listOf(run(2, name = CHECK_NAME, conclusion = "failure", suite = 2, appId = ownAppId)),
            suites = listOf(suite(2, appId = ownAppId)),
        ) shouldBe CiState.PENDING
    }

    @Test
    fun `andre runs fra egen app teller, bare egen check er Iduns`() {
        eval(
            runs = listOf(run(1), run(2, name = "noe annet", conclusion = "failure", suite = 2, appId = ownAppId)),
        ) shouldBe CiState.RED
    }

    @Test
    fun `Actions-suite som ikke er completed gir PENDING`() {
        eval(
            runs = listOf(run(1)),
            suites = listOf(suite(1, status = "queued")),
        ) shouldBe CiState.PENDING
    }

    @Test
    fun `run som ikke er completed gir PENDING`() {
        eval(runs = listOf(run(1, status = "in_progress", conclusion = null))) shouldBe CiState.PENDING
        eval(runs = listOf(run(1, status = "queued", conclusion = null))) shouldBe CiState.PENDING
    }

    @Test
    fun `success neutral og skipped er grønt`() {
        eval(runs = listOf(run(1, conclusion = "success"), run(2, name = "b", conclusion = "neutral"), run(3, name = "c", conclusion = "skipped"))) shouldBe
            CiState.GREEN
    }

    @Test
    fun `nyeste run per navn i samme suite teller`() {
        eval(runs = listOf(run(1, conclusion = "failure"), run(2, conclusion = "success"))) shouldBe CiState.GREEN
        eval(runs = listOf(run(5, conclusion = "success"), run(2, conclusion = "failure"))) shouldBe CiState.GREEN
        eval(runs = listOf(run(1, conclusion = "success"), run(2, conclusion = "failure"))) shouldBe CiState.RED
    }

    @Test
    fun `samme navn i annen suite kan ikke skjule en feil`() {
        eval(
            runs = listOf(run(1, conclusion = "failure", suite = 1), run(2, conclusion = "success", suite = 2)),
        ) shouldBe CiState.RED
    }

    @Test
    fun `kansellert run erstattet av nyere run av samme workflow er grønn`() {
        eval(
            runs = listOf(run(1, conclusion = "cancelled", suite = 1), run(2, conclusion = "success", suite = 2)),
            workflowRuns = listOf(WorkflowRunInfo(10, 7, 1), WorkflowRunInfo(11, 7, 2)),
        ) shouldBe CiState.GREEN
    }

    @Test
    fun `kansellert run uten nyere run er rød`() {
        eval(
            runs = listOf(run(1, conclusion = "cancelled", suite = 1)),
            workflowRuns = listOf(WorkflowRunInfo(10, 7, 1)),
        ) shouldBe CiState.RED
        eval(runs = listOf(run(1, conclusion = "cancelled", suite = 1))) shouldBe CiState.RED
    }

    @Test
    fun `kansellert run med nyere run av annen workflow er rød`() {
        eval(
            runs = listOf(run(1, conclusion = "cancelled", suite = 1), run(2, conclusion = "success", suite = 2)),
            workflowRuns = listOf(WorkflowRunInfo(10, 7, 1), WorkflowRunInfo(11, 8, 2)),
        ) shouldBe CiState.RED
    }

    @Test
    fun `feilet run erstattet av nyere workflow-run er fortsatt rød`() {
        eval(
            runs = listOf(run(1, conclusion = "failure", suite = 1), run(2, conclusion = "success", suite = 2)),
            workflowRuns = listOf(WorkflowRunInfo(10, 7, 1), WorkflowRunInfo(11, 7, 2)),
        ) shouldBe CiState.RED
    }

    @Test
    fun `alt annet enn grønt er rød`() {
        for (c in listOf("failure", "action_required", "timed_out", "stale", "startup_failure", "cancelled", "something_new", null)) {
            eval(runs = listOf(run(1, conclusion = c))) shouldBe CiState.RED
        }
    }

    @Test
    fun `RED slår PENDING som slår GREEN`() {
        eval(runs = listOf(run(1, name = "a", conclusion = "failure"), run(2, name = "b", status = "queued", conclusion = null))) shouldBe CiState.RED
        eval(runs = listOf(run(1, name = "a"), run(2, name = "b", status = "queued", conclusion = null))) shouldBe CiState.PENDING
        eval(runs = listOf(run(1)), statuses = listOf(StatusInfo("ci", "failure"), StatusInfo("x", "pending"))) shouldBe CiState.RED
    }

    @Test
    fun `statuser`() {
        eval(statuses = listOf(StatusInfo("ci", "success"))) shouldBe CiState.GREEN
        eval(statuses = listOf(StatusInfo("ci", "pending"))) shouldBe CiState.PENDING
        eval(statuses = listOf(StatusInfo("ci", "failure"))) shouldBe CiState.RED
        eval(statuses = listOf(StatusInfo("ci", "error"))) shouldBe CiState.RED
        eval(statuses = listOf(StatusInfo("ci", "whatever"))) shouldBe CiState.RED
        eval(runs = listOf(run(1)), statuses = listOf(StatusInfo("ci", "success"))) shouldBe CiState.GREEN
    }

    // --- Actions-modus: Idun kjører som GitHub Actions og har samme app-id som repoets CI ---

    @Test
    fun `i Actions-modus teller CI fra Actions`() {
        val actions = GITHUB_ACTIONS_APP_ID
        eval(runs = listOf(run(1, name = "test")), suites = listOf(suite(1)), ownAppId = actions) shouldBe CiState.GREEN
        eval(runs = listOf(run(1, name = "test", conclusion = "failure")), suites = listOf(suite(1)), ownAppId = actions) shouldBe CiState.RED
        eval(
            runs = listOf(run(1, name = "test", status = "in_progress", conclusion = null)),
            suites = listOf(suite(1, status = "in_progress")),
            ownAppId = actions,
        ) shouldBe CiState.PENDING
    }

    @Test
    fun `i Actions-modus ignoreres bare egen idun-refactor`() {
        eval(
            runs =
                listOf(
                    run(1, name = "test"),
                    run(2, name = "PR title lint"),
                    run(3, name = CHECK_NAME, conclusion = "failure"),
                ),
            suites = listOf(suite(1)),
            ownAppId = GITHUB_ACTIONS_APP_ID,
        ) shouldBe CiState.GREEN
    }

    @Test
    fun `i Actions-modus gir bare egen idun-refactor PENDING`() {
        eval(runs = listOf(run(3, name = CHECK_NAME)), suites = listOf(suite(1)), ownAppId = GITHUB_ACTIONS_APP_ID) shouldBe CiState.PENDING
    }

    @Test
    fun `i Actions-modus gir en annen Actions-suite som ikke er ferdig PENDING`() {
        eval(
            runs = listOf(run(1, name = "test")),
            suites = listOf(suite(1), suite(2, status = "in_progress")),
            ownAppId = GITHUB_ACTIONS_APP_ID,
        ) shouldBe CiState.PENDING
    }
}
