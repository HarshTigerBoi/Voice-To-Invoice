package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.SupplierRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplierDao {
    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    fun getAllSuppliers(): Flow<List<SupplierRecord>>

    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    suspend fun getAllSuppliersList(): List<SupplierRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(supplier: SupplierRecord)

    @Query("UPDATE suppliers SET balanceOwed = balanceOwed + :delta, updatedAt = :timestamp, synced = 0 WHERE id = :id")
    suspend fun addToBalance(id: String, delta: Double, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE suppliers SET balanceOwed = 0.0, updatedAt = :timestamp, synced = 0 WHERE id = :id")
    suspend fun settleBalance(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM suppliers WHERE synced = 0")
    suspend fun getUnsyncedSuppliers(): List<SupplierRecord>

    @Query("UPDATE suppliers SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
