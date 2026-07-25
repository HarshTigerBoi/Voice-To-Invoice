package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.StockInRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface StockInDao {
    @Query("SELECT * FROM stock_in ORDER BY timestamp DESC")
    fun getAllStockIn(): Flow<List<StockInRecord>>

    @Query("SELECT * FROM stock_in ORDER BY timestamp DESC")
    suspend fun getAllStockInRecordsList(): List<StockInRecord>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(stockIn: StockInRecord)

    @Query("SELECT * FROM stock_in WHERE synced = 0")
    suspend fun getUnsyncedStockIn(): List<StockInRecord>

    @Query("UPDATE stock_in SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
