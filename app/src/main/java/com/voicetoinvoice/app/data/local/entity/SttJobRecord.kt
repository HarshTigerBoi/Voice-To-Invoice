package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class SttJobStatus {
    QUEUED, TRANSCRIBING, PARSED, CONFIRMED, AUTO_CONFIRMED, FAILED
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
    val previousJobId: String? = null,
    val precedingGapMs: Long = -1L,
    val synced: Boolean = false
)
