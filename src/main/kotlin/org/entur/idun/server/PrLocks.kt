package org.entur.idun.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.entur.idun.github.RepoRef
import java.util.concurrent.ConcurrentHashMap

/**
 * Én [Mutex] per PR, så hendelser for samme PR vurderes etter hverandre og aldri samtidig.
 * Låsene ryddes ikke bort; med én instans og et fåtall repoer er det noen hundre små objekter.
 */
class PrLocks {
    private val locks = ConcurrentHashMap<Pair<RepoRef, Int>, Mutex>()

    suspend fun <T> withLock(
        repo: RepoRef,
        number: Int,
        block: suspend () -> T,
    ): T = locks.computeIfAbsent(repo to number) { Mutex() }.withLock { block() }
}
