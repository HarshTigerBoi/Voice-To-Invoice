package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.CreditRecord
import com.voicetoinvoice.app.data.local.entity.CreditStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CreditDao {
    @Query("SELECT * FROM credits ORDER BY status ASC, dueDate ASC")
    fun getAllCredits(): Flow<List<CreditRecord>>

    @Query("SELECT * FROM credits ORDER BY status ASC, dueDate ASC")
    suspend fun getAllCreditsList(): List<CreditRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(credit: CreditRecord)

    @Query("UPDATE credits SET status = :status, updatedAt = :timestamp, synced = 0 WHERE id = :id")
    suspend fun updateStatus(id: String, status: CreditStatus, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM credits WHERE synced = 0")
    suspend fun getUnsyncedCredits(): List<CreditRecord>

    @Query("UPDATE credits SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
