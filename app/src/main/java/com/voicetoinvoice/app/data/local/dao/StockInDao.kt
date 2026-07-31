package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.StockInRecord
import kotlinx.coroutines.flow.Flow

data class ItemCost(val itemId: String, val costPrice: Double)

@Dao
interface StockInDao {
    @Query("SELECT * FROM stock_in ORDER BY timestamp DESC")
    fun getAllStockIn(): Flow<List<StockInRecord>>

    @Query("SELECT * FROM stock_in ORDER BY timestamp DESC")
    suspend fun getAllStockInRecordsList(): List<StockInRecord>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(stockIn: StockInRecord)

    @Query("SELECT * FROM stock_in WHERE synced = 0")
    suspend fun getUnsyncedStockIn(): List<StockInRecord>

    @Query("UPDATE stock_in SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /**
     * Latest known per-unit cost per item.
     *
     * This used to `SELECT (s.costPrice / s.quantity)`, which assumed `costPrice` held an
     * invoice total. `StockInRecord.costPrice` is per-unit (see its docs), so for voice
     * stock-ins the division understated cost by a factor of `quantity` and inflated profit.
     * The division is gone.
     *
     * Prefer `catalog_items.avgCostPrice` (weighted average, maintained by
     * `StockLedgerRepository`) for costing. This "latest cost" view remains for screens that
     * want the most recent purchase rate rather than a blended one.
     */
    @Query("""
        SELECT s.itemId as itemId, s.costPrice as costPrice
        FROM stock_in s
        INNER JOIN (
            SELECT itemId, MAX(timestamp) as maxTs FROM stock_in GROUP BY itemId
        ) latest ON s.itemId = latest.itemId AND s.timestamp = latest.maxTs
        WHERE s.quantity > 0 AND s.costPrice > 0
    """)
    fun getLatestCostPricePerItem(): Flow<List<ItemCost>>

    @Query("SELECT * FROM stock_in WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): StockInRecord?

    @Query("UPDATE stock_in SET costPrice = :unitCost, costMissing = 0, synced = 0 WHERE id = :id")
    suspend fun setCost(id: String, unitCost: Double)

    /** Rows awaiting a cost -- each one is a hole in profit coverage. */
    @Query("SELECT * FROM stock_in WHERE costMissing = 1 ORDER BY timestamp DESC")
    suspend fun getCostMissingRecords(): List<StockInRecord>

    @Query("SELECT COUNT(*) FROM stock_in WHERE costMissing = 1")
    suspend fun countCostMissing(): Int

    /**
     * Most recently used supplier per item. `catalog_items` has no supplier field of its own --
     * the association only exists per purchase, on `stock_in.supplierId` -- so "this item's
     * supplier" is derived as whoever it was most recently bought from. Feeds `ReorderAdvisor`'s
     * per-supplier grouping.
     */
    @Query(
        """
        SELECT s.itemId as itemId, s.supplierId as supplierId
        FROM stock_in s
        INNER JOIN (
            SELECT itemId, MAX(timestamp) as maxTs FROM stock_in WHERE supplierId IS NOT NULL GROUP BY itemId
        ) latest ON s.itemId = latest.itemId AND s.timestamp = latest.maxTs
        WHERE s.supplierId IS NOT NULL
        """
    )
    suspend fun getLatestSupplierPerItem(): List<ItemSupplier>
}

data class ItemSupplier(val itemId: String, val supplierId: String)
