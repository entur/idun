package org.entur.idun.review

internal const val FIXTURE_PATH = "src/main/kotlin/org/entur/products/Foo.kt"

internal val fixtureInput =
    ReviewInput(
        title = "refactor: rename helper",
        files =
            listOf(
                ReviewFile(
                    path = FIXTURE_PATH,
                    patch = "@@ -1,3 +1,3 @@\n-fun a() = 1\n+fun b() = 1",
                    before = (1..20).joinToString("\n") { "val before$it = $it" },
                    after = (1..20).joinToString("\n") { "val after$it = $it" },
                ),
            ),
    )

internal fun validResponse(): ReviewResponse =
    ReviewResponse(
        verdict = Verdict.BEHAVIOUR_PRESERVING,
        summary = "Ren omdøping.",
        checklist =
            ChecklistItem.entries.map {
                ChecklistAnswer(it, Answer.NO, listOf(Evidence(FIXTURE_PATH, 1, "uendret")))
            },
        findings = emptyList(),
    )

internal fun ReviewResponse.withAnswer(
    item: ChecklistItem,
    answer: Answer,
): ReviewResponse = copy(checklist = checklist.map { if (it.item == item) it.copy(answer = answer) else it })

internal fun ReviewResponse.withEvidence(evidence: Evidence): ReviewResponse =
    copy(checklist = checklist.map { if (it.item == ChecklistItem.EQUALITY) it.copy(evidence = listOf(evidence)) else it })
