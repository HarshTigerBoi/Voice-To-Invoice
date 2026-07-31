package com.voicetoinvoice.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voicetoinvoice.app.data.local.entity.CustomerPayment
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerPaymentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(payment: CustomerPayment)

    @Query("SELECT * FROM customer_payments WHERE customerId = :customerId ORDER BY receivedAtMs DESC")
    fun observeForCustomer(customerId: String): Flow<List<CustomerPayment>>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM customer_payments WHERE customerId = :customerId")
    suspend fun totalPaidBy(customerId: String): Double

    @Query(
        """
        SELECT COALESCE(SUM(amount), 0.0) FROM customer_payments
        WHERE receivedAtMs >= :startMs AND receivedAtMs < :endMs
        """
    )
    suspend fun totalCollectedBetween(startMs: Long, endMs: Long): Double

    @Query("SELECT * FROM customer_payments ORDER BY receivedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<CustomerPayment>>

    @Query("SELECT * FROM customer_payments WHERE synced = 0 LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 200): List<CustomerPayment>

    @Query("UPDATE customer_payments SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
