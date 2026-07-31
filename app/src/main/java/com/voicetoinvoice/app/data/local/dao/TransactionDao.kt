package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import kotlinx.coroutines.flow.Flow

/** One-row aggregate for a reporting window. See [TransactionDao.getPeriodTotals]. */
data class PeriodTotals(
    val revenue: Double,
    val cogs: Double,
    /** Revenue restricted to lines with a known cost -- the correct margin denominator. */
    val revenueWithCost: Double,
    val linesWithoutCost: Int,
    val lineCount: Int
)

data class CustomerCreditTotal(val customerId: String, val total: Double)

data class ItemSales(
    val itemId: String,
    val itemName: String,
    val qty: Double,
    val revenue: Double
)

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

    /** Non-Flow variant for one-shot repair/rollup recomputation. */
    @Query("SELECT * FROM transactions WHERE voided = 0 AND timestamp >= :startMs AND timestamp < :endMs")
    suspend fun getTransactionsInRangeOnce(startMs: Long, endMs: Long): List<TransactionRecord>

    /** Earliest sale on record -- used once, at first launch after the rollups migration, to
     *  bound how far back the one-time full-history backfill needs to look. */
    @Query("SELECT MIN(timestamp) FROM transactions")
    suspend fun getEarliestTimestamp(): Long?

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
    /**
     * Marks a sale void. **Do not call directly** -- use
     * `StockLedgerRepository.voidSale(txId)`, which also books the compensating `SALE_VOID`
     * stock movement. Calling this alone leaves the item permanently short, which was the bug
     * described in Docs/master_build_plan.md §1.1(1).
     */
    @Query("UPDATE transactions SET voided = 1, voidedAtMs = :voidedAtMs, synced = 0 WHERE id = :txId")
    suspend fun voidTransaction(txId: String, voidedAtMs: Long)

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransactionRecord?

    @Query("SELECT * FROM transactions WHERE billId = :billId AND voided = 0 ORDER BY lineNo ASC")
    suspend fun getByBillId(billId: String): List<TransactionRecord>

    @Query("SELECT * FROM transactions WHERE jobId = :jobId AND voided = 0 ORDER BY lineNo ASC")
    suspend fun getByJobId(jobId: String): List<TransactionRecord>

    /**
     * Most recent non-voided sale, for the voice "galat/cancel" (VOID_LAST) command. Bounded by
     * [sinceMs] so an unbounded voice "delete" can never reach back into settled history.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE voided = 0 AND txnType = 'SALE' AND timestamp >= :sinceMs
        ORDER BY timestamp DESC LIMIT 1
        """
    )
    suspend fun getMostRecentSaleSince(sinceMs: Long): TransactionRecord?

    /**
     * Period revenue/COGS in one aggregate instead of loading every row into Kotlin.
     *
     * `revenueWithCost` is deliberately separate from `revenue`: margin must be computed only
     * over lines whose cost is known, otherwise uncosted lines silently understate it. See
     * `ProfitCalculator`.
     */
    @Query(
        """
        SELECT
          COALESCE(SUM(total), 0.0)                                                        AS revenue,
          COALESCE(SUM(CASE WHEN costAtSale IS NOT NULL THEN quantity * costAtSale END), 0.0) AS cogs,
          COALESCE(SUM(CASE WHEN costAtSale IS NOT NULL THEN total END), 0.0)              AS revenueWithCost,
          COALESCE(SUM(CASE WHEN costAtSale IS NULL THEN 1 ELSE 0 END), 0)                 AS linesWithoutCost,
          COUNT(*)                                                                          AS lineCount
        FROM transactions
        WHERE voided = 0 AND timestamp >= :startMs AND timestamp < :endMs
        """
    )
    suspend fun getPeriodTotals(startMs: Long, endMs: Long): PeriodTotals

    @Query(
        """
        SELECT COALESCE(SUM(total), 0.0) FROM transactions
        WHERE voided = 0 AND paymentMode = :mode AND timestamp >= :startMs AND timestamp < :endMs
        """
    )
    suspend fun getTotalByPaymentMode(mode: String, startMs: Long, endMs: Long): Double

    /** Quantity sold per item in a window -- feeds low-stock thresholds and forecasting. */
    @Query(
        """
        SELECT itemId, itemName, COALESCE(SUM(quantity), 0.0) AS qty, COALESCE(SUM(total), 0.0) AS revenue
        FROM transactions
        WHERE voided = 0 AND txnType = 'SALE' AND timestamp >= :startMs AND timestamp < :endMs
        GROUP BY itemId ORDER BY qty DESC
        """
    )
    suspend fun getItemSalesBetween(startMs: Long, endMs: Long): List<ItemSales>

    @Query(
        """
        SELECT COALESCE(SUM(quantity), 0.0) FROM transactions
        WHERE voided = 0 AND txnType = 'SALE' AND itemId = :itemId
          AND timestamp >= :startMs AND timestamp < :endMs
        """
    )
    suspend fun getQtySoldForItem(itemId: String, startMs: Long, endMs: Long): Double

    /**
     * Credit owed by one customer. RETURN rows carry a negative `total`, so summing handles
     * returns-against-credit without a special case. See `CustomerBalance`.
     */
    @Query(
        """
        SELECT COALESCE(SUM(total), 0.0) FROM transactions
        WHERE voided = 0 AND paymentMode = 'CREDIT' AND customerId = :customerId
        """
    )
    suspend fun sumCreditTotalForCustomer(customerId: String): Double

    @Query(
        """
        SELECT customerId AS customerId, COALESCE(SUM(total), 0.0) AS total FROM transactions
        WHERE voided = 0 AND paymentMode = 'CREDIT' AND customerId IS NOT NULL
        GROUP BY customerId
        """
    )
    suspend fun sumCreditTotalsGroupedByCustomer(): List<CustomerCreditTotal>

    /** Oldest unsettled credit sale for a customer -- drives receivables aging. */
    @Query(
        """
        SELECT MIN(timestamp) FROM transactions
        WHERE voided = 0 AND paymentMode = 'CREDIT' AND customerId = :customerId AND total > 0
        """
    )
    suspend fun oldestCreditSaleMsForCustomer(customerId: String): Long?

    /** Newest bill belonging to a customer -- what "send Ramesh his bill" means by default. */
    @Query(
        """
        SELECT billId FROM transactions
        WHERE voided = 0 AND customerId = :customerId AND billId IS NOT NULL
        ORDER BY timestamp DESC LIMIT 1
        """
    )
    suspend fun getRecentBillIdForCustomer(customerId: String): String?

    /** Newest bill overall, for "send the bill" with no customer named. */
    @Query(
        """
        SELECT billId FROM transactions
        WHERE voided = 0 AND billId IS NOT NULL ORDER BY timestamp DESC LIMIT 1
        """
    )
    suspend fun getMostRecentBillId(): String?
}
