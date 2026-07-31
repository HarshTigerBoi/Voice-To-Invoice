package com.voicetoinvoice.app.domain.processor

import com.voicetoinvoice.app.data.local.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Enforces that commands COMMIT in the order they were SPOKEN, even though STT/parsing for
 * different recordings runs in parallel (that parallelism is what keeps the mic responsive while
 * the shopkeeper keeps talking -- see Docs/master_build_plan.md §2.5).
 *
 * ## Why order matters here specifically
 *
 * If "aaloo ka rate 30 kar do" is spoken before "5 kilo aaloo" but its network round-trip happens
 * to finish SECOND (STT/parse latency varies per recording), committing in arrival order books
 * the sale at the OLD price -- a silent, wrong-but-plausible money error the shopkeeper has no way
 * to notice. Ordering only needs to be enforced at the COMMIT step (the DB writes), not at the
 * parse step, so this barrier sits at exactly one seam in `SttWorker.doWork()`.
 *
 * ## Cost in the common case: zero
 *
 * [countUnterminatedBefore] returns 0 immediately when nothing else is in flight -- which is true
 * for the overwhelming majority of single-utterance presses -- so [runInOrder] adds no measurable
 * latency to the common case. The wait only engages when utterances genuinely overlap.
 *
 * ## The 6-second ceiling
 *
 * An unbounded wait means one stuck network call (a dropped connection, a slow cold start) freezes
 * every later commit behind it -- turning a single bad recording into an outage of the whole
 * ledger. Past [CEILING_MS] the job commits anyway, and [clientTrace] (if supplied) records
 * `commit_order_violated: true` so the trade is visible in diagnostics rather than silently
 * eaten -- this is exactly the kind of thing a shopkeeper would report as "it did the wrong thing"
 * with no way for anyone to reconstruct why.
 */
object CommitSequencer {

    /** Poll interval while waiting for an earlier job to finish. */
    const val POLL_INTERVAL_MS = 150L

    /** Past this wait, commit anyway rather than freeze the whole ledger behind one stuck job. */
    const val CEILING_MS = 6000L

    private val mutex = Mutex()

    /**
     * Runs [block] only after every job recorded strictly before [recordedAtMs] has reached a
     * terminal state (or the ceiling elapses). The whole wait-then-commit is one critical section,
     * so two jobs can never interleave their commits.
     *
     * @param countUnterminatedBefore counts still-in-flight jobs older than a timestamp. Decoupled
     *   from `AppDatabase` so the waiting/ceiling logic itself is unit-testable without Room.
     */
    suspend fun <T> runInOrder(
        recordedAtMs: Long,
        clientTrace: JSONObject? = null,
        /**
         * Wall-clock source for the ceiling check, real time by default. Tests run under
         * `kotlinx.coroutines.test`'s virtual scheduler, where `delay()` advances a VIRTUAL clock
         * that has no relationship to `System.currentTimeMillis()` -- injecting `TestScope
         * .currentTime` here is what lets the ceiling test resolve in milliseconds of real test
         * time instead of actually blocking for 6 real seconds (or, worse, spinning against a
         * clock that never advances at all).
         */
        nowMs: () -> Long = System::currentTimeMillis,
        countUnterminatedBefore: suspend (Long) -> Int,
        block: suspend () -> T
    ): T {
        // The WAIT must be lock-free. An earlier design held `mutex` across the whole
        // wait-then-commit section, which deadlocked: if job B (spoken later) happened to
        // reach this function before job A (spoken earlier) -- acquisition order here has no
        // relationship to recordedAtMs order, that's the entire premise this function exists
        // to handle -- B would acquire the mutex, see A as still in flight, and poll... while
        // holding the ONE mutex A needs to ever reach its own commit and mark itself
        // terminal. B would poll forever (until the ceiling) unable to see A finish, because
        // A could never start. Polling reads the DB directly and costs nothing to run
        // concurrently across jobs, so only the actual commit -- the part that must not
        // interleave -- takes the lock.
        val start = nowMs()
        while (true) {
            val unterminated = countUnterminatedBefore(recordedAtMs)
            if (unterminated == 0) {
                clientTrace?.put("commit_order_violated", false)
                break
            }
            if (nowMs() - start >= CEILING_MS) {
                clientTrace?.put("commit_order_violated", true)
                clientTrace?.put("commit_order_wait_ms", nowMs() - start)
                break
            }
            delay(POLL_INTERVAL_MS)
        }
        return mutex.withLock { block() }
    }

    /** Convenience overload wired to the real database. */
    suspend fun <T> runInOrder(
        db: AppDatabase,
        recordedAtMs: Long,
        clientTrace: JSONObject? = null,
        block: suspend () -> T
    ): T = runInOrder(
        recordedAtMs = recordedAtMs,
        clientTrace = clientTrace,
        countUnterminatedBefore = { ts -> db.sttJobDao().countUnterminatedBefore(ts) },
        block = block
    )
}
