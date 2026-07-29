package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE voided = 0 ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionRecord>>

    @Query("SELECT * FROM transactions WHERE voided = 0 ORDER BY timestamp DESC")
    suspend fun getAllTransactionsList(): List<TransactionRecord>

    @Query("SELECT * FROM transactions WHERE voided = 0 AND timestamp >= :startTimeOfDay ORDER BY timestamp DESC")
    fun getTodayTransactions(startTimeOfDay: Long): Flow<List<TransactionRecord>>

    @Query("SELECT * FROM transactions WHERE voided = 0 AND timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    fun getTransactionsBetween(startMs: Long, endMs: Long): Flow<List<TransactionRecord>>

    @Query("SELECT SUM(total) FROM transactions WHERE voided = 0 AND timestamp >= :startTimeOfDay")
    fun getTodayTotalSales(startTimeOfDay: Long): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionRecord)

    @Query("SELECT * FROM transactions WHERE synced = 0")
    suspend fun getUnsyncedTransactions(): List<TransactionRecord>

    @Query("UPDATE transactions SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("SELECT * FROM transactions WHERE total = :amount AND timestamp >= :sinceMs AND paymentMode != 'UPI' ORDER BY timestamp DESC")
    suspend fun getRecentTransactionsByAmount(amount: Double, sinceMs: Long): List<TransactionRecord>

    @Query("UPDATE transactions SET paymentMode = 'UPI', synced = 0 WHERE id = :txId")
    suspend fun markTransactionPaidViaUpi(txId: String)

    // Soft delete only -- the ledger stays append-only. This is also the correction
    // signal the server-side Learned Parse Memory relies on (ISSUE-031): once synced,
    // a trigger on Supabase's transactions table demotes any memoized parse this
    // transaction's job_id contributed to.
    @Query("UPDATE transactions SET voided = 1, voidedAtMs = :voidedAtMs, synced = 0 WHERE id = :txId")
    suspend fun voidTransaction(txId: String, voidedAtMs: Long)
}
