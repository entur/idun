package org.entur.idun.review

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Daglig kostnadstak i USD. Telleren nullstilles ved UTC-midnatt. Trådsikker.
 *
 * `canSpend` og `record` er hver for seg atomiske, men ikke sammen: samtidige vurderinger kan
 * overskride taket med det som er underveis når taket nås.
 */
class DailyBudget(
    private val limitUsd: Double,
    private val clock: Clock,
) {
    private val lock = Any()
    private var day: LocalDate = today()
    private var spent = 0.0

    fun canSpend(): Boolean = synchronized(lock) { rollOver(); spent < limitUsd }

    fun record(costUsd: Double) {
        synchronized(lock) {
            rollOver()
            spent += costUsd
        }
    }

    internal fun spentToday(): Double = synchronized(lock) { rollOver(); spent }

    private fun today(): LocalDate = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()

    private fun rollOver() {
        val now = today()
        if (now != day) {
            day = now
            spent = 0.0
        }
    }
}
