package org.entur.idun.rules

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TitleTest {
    @Test
    fun `refactor med og uten scope`() {
        parseTitle("refactor: Split loadAssignments").isRefactor() shouldBe true
        parseTitle("refactor(ETU-75389): Decode grouping operators to enums").isRefactor() shouldBe true
        parseTitle("refactor!: drop old api")!!.breaking shouldBe true
    }

    @Test
    fun `titler som bare ligner refactor`() {
        listOf(
            "Refactor: x",
            "refactor:",
            "refactor: ",
            "refactoring: x",
            "test: Refactor SalesPackage tests",
            "feat: refactor syntax",
        ).forEach { parseTitle(it).isRefactor() shouldBe false }
    }

    @Test
    fun `parser type, scope og breaking`() {
        parseTitle("refactor(ETU-1)!: x") shouldBe ConventionalTitle("refactor", "ETU-1", true)
        parseTitle("feat: x") shouldBe ConventionalTitle("feat", null, false)
        parseTitle("ikke conventional") shouldBe null
    }

    @Test
    fun `null-tittel er ikke refactor`() {
        (null as ConventionalTitle?).isRefactor() shouldBe false
    }
}
