package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionRecord>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun getAllTransactionsList(): List<TransactionRecord>

    @Query("SELECT * FROM transactions WHERE timestamp >= :startTimeOfDay ORDER BY timestamp DESC")
    fun getTodayTransactions(startTimeOfDay: Long): Flow<List<TransactionRecord>>

    @Query("SELECT SUM(total) FROM transactions WHERE timestamp >= :startTimeOfDay")
    fun getTodayTotalSales(startTimeOfDay: Long): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionRecord)

    @Query("SELECT * FROM transactions WHERE synced = 0")
    suspend fun getUnsyncedTransactions(): List<TransactionRecord>

    @Query("UPDATE transactions SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
