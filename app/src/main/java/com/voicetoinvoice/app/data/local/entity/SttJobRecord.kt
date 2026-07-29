package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class SttJobStatus {
    QUEUED, TRANSCRIBING, PARSED, CONFIRMED, AUTO_CONFIRMED, FAILED,
    // Mirror the server's finalStatus values (process-voice-job/index.ts) so a job that
    // lands in either state is recognized instead of falling into the generic PARSED/
    // review bucket -- see ISSUE-029 (multi-item partial commit) and ISSUE-026
    // (RATE_UPDATE jobs, which previously had no Kotlin equivalent at all).
    PARTIALLY_CONFIRMED, RATE_UPDATED
}

@Entity(tableName = "stt_jobs")
data class SttJobRecord(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val audioFilePath: String,
    val status: SttJobStatus = SttJobStatus.QUEUED,
    val rawTranscript: String = "",
    val parsedItemId: String? = null,
    val parsedItemName: String = "",
    val parsedQty: Double = 1.0,
    val parsedUnit: String = "PACKET",
    val parsedTotal: Double = 0.0,
    val parsedIsPendingPrice: Boolean = false,
    val isSanityFlagged: Boolean = false,
    val errorMessage: String = "",
    val recordedAtMs: Long = System.currentTimeMillis(),
    val holdDurationMs: Long = 0L,
    val pressStartMs: Long = 0L,
    val releaseMs: Long = 0L,
    val audioStartMs: Long = 0L,
    val audioEndMs: Long = 0L,
    val diagnosticTraceJson: String = "",
    val onDeviceTranscript: String = "",
    val onDeviceStatus: String = "",
    val previousJobId: String? = null,
    val precedingGapMs: Long = -1L,
    val synced: Boolean = false,
    // Full per-line breakdown of a multi-item recording (the server's finalParsedItems /
    // step_4_grok_ai_interpretation array, verbatim JSON) -- the scalar parsed* columns
    // above stay a line-0 summary for backward compatibility, this is the source of
    // truth for lines 1..N. See ISSUE-029.
    val parsedItemsJson: String = "",
    val lineCount: Int = 0
)
