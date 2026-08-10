package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.UnmatchedQueueItem
import com.voicetoinvoice.app.data.local.entity.UnmatchedStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface UnmatchedQueueDao {
    @Query("SELECT * FROM unmatched_queue WHERE status = 'PENDING' ORDER BY timestamp DESC")
    fun getPendingItems(): Flow<List<UnmatchedQueueItem>>

    @Query("SELECT * FROM unmatched_queue WHERE status = 'PENDING' ORDER BY timestamp DESC")
    suspend fun getPendingItemsList(): List<UnmatchedQueueItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: UnmatchedQueueItem)

    @Query("UPDATE unmatched_queue SET status = :status, resolvedItemId = :resolvedItemId WHERE id = :id")
    suspend fun resolveItem(id: String, status: UnmatchedStatus, resolvedItemId: String?)

    @Query("UPDATE unmatched_queue SET resolvedItemId = :newId WHERE resolvedItemId = :oldId")
    suspend fun relinkItemId(oldId: String, newId: String)
}
