package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.CreditRecord
import com.voicetoinvoice.app.data.local.entity.CustomerRecord
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.domain.parser.PhoneticKey
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers WHERE mergedIntoId IS NULL ORDER BY lastSeenMs DESC, createdAt DESC")
    fun getActiveCustomers(): Flow<List<CustomerRecord>>

    @Query("SELECT * FROM customers WHERE mergedIntoId IS NULL ORDER BY lastSeenMs DESC, createdAt DESC")
    suspend fun getActiveCustomersList(): List<CustomerRecord>

    // Credit sales booked before a customer was identified (Fix 2 / ISSUE-039).
    // Never blocks the sale itself -- see UdhaarPickerOverlay, non-blocking by design.
    @Query("SELECT * FROM credits WHERE customerId IS NULL ORDER BY updatedAt ASC")
    fun getUnassignedCredits(): Flow<List<CreditRecord>>

    @Query("UPDATE credits SET customerId = :customerId, synced = 0 WHERE id = :creditId")
    suspend fun assignCreditCustomer(creditId: String, customerId: String)

    @Query("UPDATE transactions SET customerId = :customerId, synced = 0 WHERE id = :transactionId")
    suspend fun assignTransactionCustomer(transactionId: String, customerId: String)

    @Query("UPDATE customers SET lastSeenMs = :timestamp, txnCount = txnCount + 1, synced = 0 WHERE id = :customerId")
    suspend fun bumpUsage(customerId: String, timestamp: Long = System.currentTimeMillis())

    @Transaction
    suspend fun assignCustomerToCredit(credit: CreditRecord, customerId: String) {
        assignCreditCustomer(credit.id, customerId)
        credit.linkedTransactionId?.let { assignTransactionCustomer(it, customerId) }
        bumpUsage(customerId)
    }

    @Query("SELECT * FROM customers WHERE code = :code AND mergedIntoId IS NULL LIMIT 1")
    suspend fun getByCode(code: Int): CustomerRecord?

    @Query("SELECT * FROM customers WHERE phone LIKE '%' || :suffix AND mergedIntoId IS NULL")
    suspend fun getByPhoneSuffix(suffix: String): List<CustomerRecord>

    @Query("SELECT * FROM customers WHERE (phoneticKey = :key OR keywordPhoneticKey = :key) AND mergedIntoId IS NULL")
    suspend fun getByPhoneticKey(key: String): List<CustomerRecord>

    @Query("SELECT * FROM transactions WHERE customerId = :customerId ORDER BY timestamp DESC")
    fun getLedgerFor(customerId: String): Flow<List<TransactionRecord>>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM credits WHERE customerId = :customerId AND status != 'PAID'")
    suspend fun getOutstandingFor(customerId: String): Double

    @Query("SELECT COALESCE(MAX(code), 0) + 1 FROM customers")
    suspend fun nextFreeCode(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(customer: CustomerRecord)

    @Update
    suspend fun update(customer: CustomerRecord)

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CustomerRecord?

    @Query("SELECT * FROM customers WHERE mergedIntoId IS NULL ORDER BY name ASC")
    suspend fun getAllActiveList(): List<CustomerRecord>

    @Query("SELECT * FROM customers WHERE synced = 0")
    suspend fun getUnsyncedCustomers(): List<CustomerRecord>

    @Query("UPDATE customers SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE credits SET customerId = :targetId, synced = 0 WHERE customerId = :sourceId")
    suspend fun reassignCreditCustomerId(sourceId: String, targetId: String)

    @Query("UPDATE transactions SET customerId = :targetId, synced = 0 WHERE customerId = :sourceId")
    suspend fun reassignTransactionCustomerId(sourceId: String, targetId: String)

    @Transaction
    suspend fun mergeInto(sourceId: String, targetId: String) {
        val source = getById(sourceId) ?: return
        val target = getById(targetId) ?: return
        
        val updatedTxnCount = source.txnCount + target.txnCount
        val lowerCode = minOf(source.code, target.code)
        
        reassignCreditCustomerId(sourceId, targetId)
        reassignTransactionCustomerId(sourceId, targetId)
        
        update(target.copy(code = lowerCode, txnCount = updatedTxnCount, synced = false))
        update(source.copy(mergedIntoId = targetId, synced = false))
    }

    suspend fun findLikelyDuplicates(): List<Pair<CustomerRecord, CustomerRecord>> {
        val list = getAllActiveList()
        val duplicates = mutableListOf<Pair<CustomerRecord, CustomerRecord>>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val c1 = list[i]
                val c2 = list[j]
                val nameDist = PhoneticKey.normalizedDistance(c1.phoneticKey, c2.phoneticKey)
                val samePhone = !c1.phone.isNullOrBlank() && c1.phone == c2.phone
                if (nameDist <= 0.15 || samePhone) {
                    duplicates.add(Pair(c1, c2))
                }
            }
        }
        return duplicates
    }
}
