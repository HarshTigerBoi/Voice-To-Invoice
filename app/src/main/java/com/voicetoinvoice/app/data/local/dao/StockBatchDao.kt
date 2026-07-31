package com.voicetoinvoice.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voicetoinvoice.app.data.local.entity.StockBatch
import kotlinx.coroutines.flow.Flow

@Dao
interface StockBatchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(batch: StockBatch)

    /** FEFO draw-down order: soonest expiry first, undated batches last. */
    @Query(
        """
        SELECT * FROM stock_batches
        WHERE itemId = :itemId AND remainingQty > 0
        ORDER BY (expiryDateMs IS NULL), expiryDateMs ASC, receivedAtMs ASC
        """
    )
    suspend fun getOpenBatchesFefo(itemId: String): List<StockBatch>

    @Query("UPDATE stock_batches SET remainingQty = :remainingQty, synced = 0 WHERE id = :id")
    suspend fun setRemaining(id: String, remainingQty: Double)

    @Query(
        """
        SELECT * FROM stock_batches
        WHERE remainingQty > 0 AND expiryDateMs IS NOT NULL AND expiryDateMs < :beforeMs
        ORDER BY expiryDateMs ASC
        """
    )
    suspend fun getExpiringBefore(beforeMs: Long): List<StockBatch>

    @Query(
        """
        SELECT * FROM stock_batches
        WHERE remainingQty > 0 AND expiryDateMs IS NOT NULL
        ORDER BY expiryDateMs ASC LIMIT :limit
        """
    )
    fun observeExpiring(limit: Int = 50): Flow<List<StockBatch>>

    @Query("SELECT * FROM stock_batches WHERE synced = 0 LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 200): List<StockBatch>

    @Query("UPDATE stock_batches SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
