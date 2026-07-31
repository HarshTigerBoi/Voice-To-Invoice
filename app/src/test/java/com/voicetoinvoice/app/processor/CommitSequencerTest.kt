package com.voicetoinvoice.app.processor

import com.voicetoinvoice.app.domain.processor.CommitSequencer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `CommitSequencer` enforces that jobs commit in the order they were SPOKEN even though their
 * network round-trips can finish in any order. These tests use a fake `countUnterminatedBefore`
 * so they run as plain JVM tests, with no Room/emulator dependency, per Docs/master_build_plan.md
 * §2.6's wiring gate for this exact behaviour.
 */
class CommitSequencerTest {

    @Test
    fun `the common case adds no wait when nothing else is in flight`() = runTest {
        val result = CommitSequencer.runInOrder(
            recordedAtMs = 1000L,
            countUnterminatedBefore = { 0 }, // nothing else pending, ever
            block = { "committed" }
        )
        assertEquals("committed", result)
    }

    @Test
    fun `a later job waits for an earlier one to finish before committing`() = runTest {
        // Simulates: job A (recordedAtMs=1000) is still in flight when job B (recordedAtMs=2000)
        // is ready to commit. B must wait until A clears before B's block runs.
        val order = mutableListOf<String>()
        var aStillInFlight = true

        val bJob = async {
            CommitSequencer.runInOrder(
                recordedAtMs = 2000L,
                countUnterminatedBefore = { ts -> if (ts > 1000L && aStillInFlight) 1 else 0 },
                block = { order.add("B") }
            )
        }

        // Give B a moment to start waiting, then let A finish.
        delay(50)
        aStillInFlight = false
        order.add("A") // A's own commit
        bJob.await()

        assertEquals(listOf("A", "B"), order)
    }

    @Test
    fun `three jobs whose STT returns in reverse order still commit in recordedAtMs order`() = runTest {
        // job1=1000, job2=2000, job3=3000 spoken in that order, but their network round-trips
        // return 3, then 2, then 1 -- reverse of speaking order. Commit order must still be 1,2,3.
        val committed = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val finished = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

        fun unterminatedBefore(ts: Long): Int =
            listOf(1000L, 2000L, 3000L).count { it < ts && finished[it] != true }

        // recordedAtMs order is 1000,2000,3000 but LAUNCH order below is deliberately reversed
        // (3, then 2, then 1) -- acquisition-order independence is exactly the property this
        // guards, since the earlier design deadlocked whenever a later job reached the
        // function before an earlier one (see CommitSequencer's header comment on the fix).
        val job3 = async {
            CommitSequencer.runInOrder(3000L, nowMs = { currentTime }, countUnterminatedBefore = { unterminatedBefore(it) }) {
                committed.add(3000L); finished[3000L] = true
            }
        }
        val job2 = async {
            CommitSequencer.runInOrder(2000L, nowMs = { currentTime }, countUnterminatedBefore = { unterminatedBefore(it) }) {
                committed.add(2000L); finished[2000L] = true
            }
        }
        val job1 = async {
            CommitSequencer.runInOrder(1000L, nowMs = { currentTime }, countUnterminatedBefore = { unterminatedBefore(it) }) {
                committed.add(1000L); finished[1000L] = true
            }
        }

        withTimeout(5000) {
            job3.await(); job2.await(); job1.await()
        }

        assertEquals(listOf(1000L, 2000L, 3000L), committed.toList())
    }

    @Test
    fun `a job that never terminates does not block past the ceiling`() = runTest {
        val trace = JSONObject()

        // Always reports something unterminated before it -- simulates a permanently stuck
        // sibling job (e.g. a network call that never returns). `nowMs = { currentTime }` ties
        // the ceiling check to the SAME virtual clock `delay()` advances -- using real
        // System.currentTimeMillis() here would never progress under runTest's virtual
        // scheduler, since delay() does not actually sleep the real thread.
        val result = withTimeout(CommitSequencer.CEILING_MS + 3000) {
            CommitSequencer.runInOrder(
                recordedAtMs = 5000L,
                clientTrace = trace,
                nowMs = { currentTime },
                countUnterminatedBefore = { 1 },
                block = { "committed anyway" }
            )
        }

        assertEquals("committed anyway", result)
        assertTrue(trace.getBoolean("commit_order_violated"))
    }

    @Test
    fun `the violation flag is false when the wait resolves normally`() = runTest {
        val trace = JSONObject()
        CommitSequencer.runInOrder(
            recordedAtMs = 1000L,
            clientTrace = trace,
            nowMs = { currentTime },
            countUnterminatedBefore = { 0 },
            block = { Unit }
        )
        assertFalse(trace.getBoolean("commit_order_violated"))
    }

    @Test
    fun `concurrent commits never interleave -- each block runs to completion alone`() = runTest {
        // A regression guard for the "wait, then commit" being ONE critical section: if the
        // mutex were released between the wait and the block, two commits could race.
        val activeCount = AtomicInteger(0)
        var maxObservedConcurrency = 0

        val jobs = (1..5).map { i ->
            async {
                CommitSequencer.runInOrder(
                    recordedAtMs = i.toLong(),
                    countUnterminatedBefore = { 0 },
                ) {
                    val active = activeCount.incrementAndGet()
                    if (active > maxObservedConcurrency) maxObservedConcurrency = active
                    delay(10)
                    activeCount.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.await() }

        assertEquals("two commit blocks ran concurrently", 1, maxObservedConcurrency)
    }
}
