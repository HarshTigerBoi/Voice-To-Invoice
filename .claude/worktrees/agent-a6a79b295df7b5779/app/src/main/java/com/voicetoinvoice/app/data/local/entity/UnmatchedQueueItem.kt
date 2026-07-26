package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class UnmatchedStatus {
    PENDING, RESOLVED, DISCARDED
}

@Entity(tableName = "unmatched_queue")
data class UnmatchedQueueItem(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val shopId: String? = null,
    val audioRef: String? = null,
    val rawTranscript: String,
    val resolvedItemId: String? = null,
    val status: UnmatchedStatus = UnmatchedStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis()
)
