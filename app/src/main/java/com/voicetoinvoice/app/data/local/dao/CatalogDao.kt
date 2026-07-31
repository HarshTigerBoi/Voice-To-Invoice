package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import kotlinx.coroutines.flow.Flow

data class StockLevel(val itemId: String, val onHand: Double)

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog_items WHERE active = 1 ORDER BY name ASC")
    fun getActiveCatalog(): Flow<List<CatalogItem>>

    @Query("SELECT * FROM catalog_items WHERE active = 1 ORDER BY name ASC")
    suspend fun getActiveCatalogList(): List<CatalogItem>

    @Query("SELECT * FROM catalog_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CatalogItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(item: CatalogItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CatalogItem>)

    @Query("UPDATE catalog_items SET price = :newPrice, updatedAt = :timestamp, synced = 0 WHERE id = :id")
    suspend fun updatePrice(id: String, newPrice: Double, timestamp: Long = System.currentTimeMillis())

    /** Every row, active or not — the merge in `SyncEngine.pullCatalogFromCloud` needs to see
     *  deactivated rows too, or it would re-insert an item the shopkeeper deliberately hid. */
    @Query("SELECT * FROM catalog_items")
    suspend fun getAllCatalogList(): List<CatalogItem>

    /** Soft delete. The catalog is referenced by `transactions.itemId`, so rows are hidden,
     *  never removed — a hard delete would orphan the history that priced those sales. */
    @Query("UPDATE catalog_items SET active = 0, updatedAt = :timestamp, synced = 0 WHERE id = :id")
    suspend fun deactivate(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM catalog_items WHERE synced = 0")
    suspend fun getUnsyncedCatalog(): List<CatalogItem>

    @Query("UPDATE catalog_items SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /**
     * Reads the materialized [CatalogItem.stockQty]. This used to derive on-hand as
     * `SUM(stock_in) - SUM(transactions)` with two correlated subqueries per item, which
     * (a) omitted the `voided = 0` filter every other transaction query has, so a voided
     * sale never returned its stock, and (b) rescanned `transactions` once per catalog item
     * on every recomposition. `StockLedgerRepository` now keeps `stockQty` current.
     */
    @Query("SELECT id AS itemId, stockQty AS onHand FROM catalog_items WHERE active = 1")
    fun getStockLevels(): Flow<List<StockLevel>>

    @Query("SELECT id AS itemId, stockQty AS onHand FROM catalog_items WHERE active = 1")
    suspend fun getStockLevelsList(): List<StockLevel>

    @Query("SELECT stockQty FROM catalog_items WHERE id = :id")
    suspend fun getStockQty(id: String): Double?

    /**
     * The ONLY way stock quantity changes. Called exclusively from
     * `StockLedgerRepository.record()`, inside the same DB transaction as the ledger insert,
     * so the materialized total and the append-only ledger can never disagree.
     */
    @Query(
        """
        UPDATE catalog_items SET
          stockQty = stockQty + :delta,
          lastSoldAtMs    = CASE WHEN :delta < 0 THEN :ts ELSE lastSoldAtMs END,
          lastStockedAtMs = CASE WHEN :delta > 0 THEN :ts ELSE lastStockedAtMs END,
          synced = 0
        WHERE id = :itemId
        """
    )
    suspend fun applyStockDelta(itemId: String, delta: Double, ts: Long)

    @Query("UPDATE catalog_items SET stockQty = :qty, synced = 0 WHERE id = :itemId")
    suspend fun setStockQty(itemId: String, qty: Double)

    @Query("UPDATE catalog_items SET avgCostPrice = :avgCost, synced = 0 WHERE id = :itemId")
    suspend fun setAvgCostPrice(itemId: String, avgCost: Double?)

    @Query("UPDATE catalog_items SET lowStockThreshold = :threshold, synced = 0 WHERE id = :id")
    suspend fun updateLowStockThreshold(id: String, threshold: Double?)

    @Query("UPDATE catalog_items SET trackExpiry = :track, synced = 0 WHERE id = :id")
    suspend fun setTrackExpiry(id: String, track: Boolean)

    /** Total value of goods on the shelf, at cost. Items with unknown cost contribute 0. */
    @Query("SELECT COALESCE(SUM(stockQty * COALESCE(avgCostPrice, 0.0)), 0.0) FROM catalog_items WHERE active = 1 AND stockQty > 0")
    suspend fun getTotalStockValueAtCost(): Double

    /** Items sitting on the shelf that have not sold since [beforeMs] -- dead stock. */
    @Query(
        """
        SELECT * FROM catalog_items
        WHERE active = 1 AND trackStock = 1 AND stockQty > 0
          AND (lastSoldAtMs IS NULL OR lastSoldAtMs < :beforeMs)
        ORDER BY stockQty * COALESCE(avgCostPrice, 0.0) DESC
        """
    )
    suspend fun getDeadStock(beforeMs: Long): List<CatalogItem>

    @Query("SELECT * FROM catalog_items WHERE active = 1 AND trackExpiry = 1")
    suspend fun getExpiryTrackedItems(): List<CatalogItem>
}
