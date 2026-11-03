package org.entur.idun.review

import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executors
import org.junit.jupiter.api.Test

class DailyBudgetTest {
    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    @Test
    fun `budsjettet nullstilles ved UTC-midnatt`() {
        val clock = MutableClock(Instant.parse("2026-11-03T23:59:59Z"))
        val budget = DailyBudget(1.0, clock)

        budget.canSpend() shouldBe true
        budget.record(1.0)
        budget.canSpend() shouldBe false

        clock.now = Instant.parse("2026-11-04T00:00:00Z")
        budget.canSpend() shouldBe true
    }

    @Test
    fun `summerer kostnader innen samme dag`() {
        val budget = DailyBudget(1.0, MutableClock(Instant.parse("2026-11-03T10:00:00Z")))
        budget.record(0.4)
        budget.canSpend() shouldBe true
        budget.record(0.4)
        budget.canSpend() shouldBe true
        budget.record(0.2)
        budget.canSpend() shouldBe false
    }

    @Test
    fun `er trådsikkert`() {
        val budget = DailyBudget(1_000_000.0, MutableClock(Instant.parse("2026-11-03T10:00:00Z")))
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..8).map { pool.submit { repeat(10_000) { budget.record(1.0) } } }
            tasks.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        budget.spentToday() shouldBe 80_000.0
    }
}
