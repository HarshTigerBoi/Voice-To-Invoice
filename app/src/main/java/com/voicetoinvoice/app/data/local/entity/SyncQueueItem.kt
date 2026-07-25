package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class SyncAction {
    INSERT, UPDATE, DELETE
}

@Entity(tableName = "sync_queue")
data class SyncQueueItem(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val tableName: String, // "transactions", "stock_in", "catalog_items", "credits"
    val recordId: String,
    val action: SyncAction,
    val payloadJson: String,
    val timestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)
