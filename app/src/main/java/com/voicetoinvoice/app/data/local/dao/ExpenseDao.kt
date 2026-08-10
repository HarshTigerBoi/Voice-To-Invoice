package com.voicetoinvoice.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voicetoinvoice.app.data.local.entity.ExpenseCategory
import com.voicetoinvoice.app.data.local.entity.ExpenseRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE voided = 0 AND timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    fun getBetween(startMs: Long, endMs: Long): Flow<List<ExpenseRecord>>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM expenses WHERE voided = 0 AND timestamp >= :startMs AND timestamp < :endMs")
    suspend fun getPeriodTotal(startMs: Long, endMs: Long): Double

    @Query("SELECT category AS category, COALESCE(SUM(amount), 0.0) AS total FROM expenses WHERE voided = 0 AND timestamp >= :startMs AND timestamp < :endMs GROUP BY category ORDER BY total DESC")
    suspend fun getTotalsByCategory(startMs: Long, endMs: Long): List<ExpenseCategoryTotal>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ExpenseRecord)

    /**
     * Soft-delete. Named `voidExpense`, NOT `void`: Room's KSP processor generates a Java
     * `ExpenseDao_Impl`, and `void` is a Java reserved word — a DAO method called `void`
     * compiles fine in Kotlin and then fails codegen with
     * "ExpenseDao_Impl is not abstract and does not override abstract method void(String,…)".
     * Mirrors the existing `TransactionDao.voidTransaction` naming.
     */
    @Query("UPDATE expenses SET voided = 1, synced = 0 WHERE id = :id")
    suspend fun voidExpense(id: String)

    @Query("SELECT * FROM expenses WHERE synced = 0")
    suspend fun getUnsynced(): List<ExpenseRecord>

    @Query("UPDATE expenses SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}

data class ExpenseCategoryTotal(val category: ExpenseCategory, val total: Double)
