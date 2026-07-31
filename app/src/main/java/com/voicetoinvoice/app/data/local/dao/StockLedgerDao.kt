package com.voicetoinvoice.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voicetoinvoice.app.data.local.entity.StockLedgerEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface StockLedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: StockLedgerEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<StockLedgerEntry>)

    @Query("SELECT * FROM stock_ledger WHERE itemId = :itemId ORDER BY occurredAtMs DESC LIMIT :limit")
    suspend fun getForItem(itemId: String, limit: Int = 100): List<StockLedgerEntry>

    @Query("SELECT * FROM stock_ledger WHERE itemId = :itemId ORDER BY occurredAtMs DESC LIMIT :limit")
    fun observeForItem(itemId: String, limit: Int = 100): Flow<List<StockLedgerEntry>>

    @Query("SELECT * FROM stock_ledger ORDER BY occurredAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<StockLedgerEntry>>

    @Query("SELECT * FROM stock_ledger ORDER BY occurredAtMs DESC LIMIT :limit")
    suspend fun recentMovements(limit: Int = 20): List<StockLedgerEntry>

    /** For rollup repair: every movement in a window, regardless of reason. */
    @Query("SELECT * FROM stock_ledger WHERE occurredAtMs >= :startMs AND occurredAtMs < :endMs")
    suspend fun getInRange(startMs: Long, endMs: Long): List<StockLedgerEntry>

    /** Earliest movement on record -- bounds the one-time full-history rollup backfill. */
    @Query("SELECT MIN(occurredAtMs) FROM stock_ledger")
    suspend fun getEarliestTimestamp(): Long?

    /** Authoritative on-hand for one item, recomputed from the ledger. */
    @Query("SELECT COALESCE(SUM(deltaQty), 0.0) FROM stock_ledger WHERE itemId = :itemId")
    suspend fun sumDeltaForItem(itemId: String): Double

    /** Cost-weighted average over inbound movements that actually carried a cost. */
    @Query(
        """
        SELECT CASE
                 WHEN COALESCE(SUM(CASE WHEN unitCost IS NOT NULL THEN deltaQty ELSE 0 END), 0) <= 0
                 THEN NULL
                 ELSE SUM(CASE WHEN unitCost IS NOT NULL THEN deltaQty * unitCost ELSE 0 END)
                      / SUM(CASE WHEN unitCost IS NOT NULL THEN deltaQty ELSE 0 END)
               END
        FROM stock_ledger
        WHERE itemId = :itemId AND deltaQty > 0
        """
    )
    suspend fun weightedAvgCostForItem(itemId: String): Double?

    /** Quantity moved for a reason inside a window -- powers waste/return reporting. */
    @Query(
        """
        SELECT COALESCE(SUM(ABS(deltaQty)), 0.0) FROM stock_ledger
        WHERE reason = :reason AND occurredAtMs >= :startMs AND occurredAtMs < :endMs
        """
    )
    suspend fun sumQtyByReason(reason: String, startMs: Long, endMs: Long): Double

    /** Value moved for a reason inside a window, at the cost carried on each entry. */
    @Query(
        """
        SELECT COALESCE(SUM(ABS(l.deltaQty) * COALESCE(l.unitCost, c.avgCostPrice, 0.0)), 0.0)
        FROM stock_ledger l LEFT JOIN catalog_items c ON c.id = l.itemId
        WHERE l.reason = :reason AND l.occurredAtMs >= :startMs AND l.occurredAtMs < :endMs
        """
    )
    suspend fun sumValueByReason(reason: String, startMs: Long, endMs: Long): Double

    /** True when this ref already produced a ledger entry -- idempotency guard for retries. */
    @Query("SELECT COUNT(*) FROM stock_ledger WHERE refId = :refId AND reason = :reason")
    suspend fun countForRef(refId: String, reason: String): Int

    @Query("SELECT * FROM stock_ledger WHERE synced = 0 LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 200): List<StockLedgerEntry>

    @Query("UPDATE stock_ledger SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /** Back-fills a cost onto an inbound entry once the shopkeeper supplies it. */
    @Query("UPDATE stock_ledger SET unitCost = :unitCost, synced = 0 WHERE refId = :refId AND deltaQty > 0")
    suspend fun setCostForRef(refId: String, unitCost: Double)

    @Query("SELECT DISTINCT itemId FROM stock_ledger")
    suspend fun getAllItemIdsWithMovement(): List<String>
}
