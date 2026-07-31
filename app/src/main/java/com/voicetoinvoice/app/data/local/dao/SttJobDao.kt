package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
import com.voicetoinvoice.app.data.local.entity.SttJobStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SttJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: SttJobRecord)

    @Update
    suspend fun updateJob(job: SttJobRecord)

    @Query("SELECT * FROM stt_jobs WHERE id = :jobId LIMIT 1")
    suspend fun getJobById(jobId: String): SttJobRecord?

    @Query("SELECT * FROM stt_jobs ORDER BY recordedAtMs DESC LIMIT 1")
    suspend fun getLatestJob(): SttJobRecord?

    @Query("SELECT * FROM stt_jobs WHERE status = 'QUEUED' ORDER BY recordedAtMs ASC")
    suspend fun getQueuedJobsList(): List<SttJobRecord>

    /**
     * How many jobs recorded strictly before [ts] have not yet reached a terminal status.
     *
     * This is the predicate `CommitSequencer` polls to decide whether a job may commit: STT and
     * parsing run in parallel for speed, but if job B (spoken second) finishes its network
     * round-trip before job A (spoken first, e.g. "aaloo ka rate 30 kar do" before "5 kilo
     * aaloo"), committing in arrival order would book the sale at the wrong price. Ordering is
     * enforced only at the commit step, not the parse step, so the mic stays responsive.
     */
    @Query(
        """
        SELECT COUNT(*) FROM stt_jobs
        WHERE recordedAtMs < :ts AND status IN ('QUEUED', 'TRANSCRIBING')
        """
    )
    suspend fun countUnterminatedBefore(ts: Long): Int

    @Query("UPDATE stt_jobs SET status = 'PARSED', rawTranscript = 'Voice Recording (Server Timeout)', errorMessage = 'Server Timeout', isSanityFlagged = 1 WHERE status IN ('QUEUED', 'TRANSCRIBING') AND recordedAtMs < :thresholdMs")
    suspend fun markStuckJobsAsFailed(thresholdMs: Long)

    // PARTIALLY_CONFIRMED jobs (multi-item capture -- ISSUE-029) still have pending
    // lines needing review even though some of their lines already booked, so they
    // belong in the same review queue as PARSED jobs.
    @Query("SELECT * FROM stt_jobs WHERE status IN ('PARSED', 'PARTIALLY_CONFIRMED') ORDER BY recordedAtMs DESC")
    fun getParsedJobsFlow(): Flow<List<SttJobRecord>>

    @Query("SELECT COUNT(*) FROM stt_jobs WHERE status IN ('PARSED', 'PARTIALLY_CONFIRMED')")
    fun getParsedJobsCountFlow(): Flow<Int>

    // End-to-End Processing Trace Log Queries
    @Query("SELECT * FROM stt_jobs ORDER BY recordedAtMs DESC LIMIT 200")
    fun getAllJobsTraceLogsFlow(): Flow<List<SttJobRecord>>

    @Query("SELECT * FROM stt_jobs ORDER BY recordedAtMs DESC LIMIT 200")
    suspend fun getAllJobsTraceLogsList(): List<SttJobRecord>

    @Query("DELETE FROM stt_jobs WHERE id = :jobId")
    suspend fun deleteJob(jobId: String)

    @Query("DELETE FROM stt_jobs WHERE status = 'CONFIRMED'")
    suspend fun purgeConfirmedJobs()

    @Query("DELETE FROM stt_jobs")
    suspend fun clearAllJobsLogs()

    @Query("SELECT * FROM stt_jobs WHERE synced = 0 AND status IN ('CONFIRMED', 'AUTO_CONFIRMED', 'PARSED', 'FAILED', 'PARTIALLY_CONFIRMED', 'RATE_UPDATED')")
    suspend fun getUnsyncedJobs(): List<SttJobRecord>

    @Query("UPDATE stt_jobs SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
