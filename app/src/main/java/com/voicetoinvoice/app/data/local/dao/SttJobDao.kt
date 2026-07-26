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

    @Query("UPDATE stt_jobs SET status = 'PARSED', rawTranscript = 'Voice Recording (Server Timeout)', errorMessage = 'Server Timeout', isSanityFlagged = 1 WHERE status IN ('QUEUED', 'TRANSCRIBING') AND recordedAtMs < :thresholdMs")
    suspend fun markStuckJobsAsFailed(thresholdMs: Long)

    @Query("SELECT * FROM stt_jobs WHERE status = 'PARSED' ORDER BY recordedAtMs DESC")
    fun getParsedJobsFlow(): Flow<List<SttJobRecord>>

    @Query("SELECT COUNT(*) FROM stt_jobs WHERE status = 'PARSED'")
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

    @Query("SELECT * FROM stt_jobs WHERE synced = 0 AND status IN ('CONFIRMED', 'AUTO_CONFIRMED', 'PARSED', 'FAILED')")
    suspend fun getUnsyncedJobs(): List<SttJobRecord>

    @Query("UPDATE stt_jobs SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
