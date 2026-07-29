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

    @Query("""
        SELECT c.id as itemId,
          COALESCE((SELECT SUM(s.quantity) FROM stock_in s WHERE s.itemId = c.id), 0.0) -
          COALESCE((SELECT SUM(t.quantity) FROM transactions t WHERE t.itemId = c.id), 0.0) as onHand
        FROM catalog_items c
        WHERE c.active = 1
    """)
    fun getStockLevels(): Flow<List<StockLevel>>

    @Query("UPDATE catalog_items SET lowStockThreshold = :threshold WHERE id = :id")
    suspend fun updateLowStockThreshold(id: String, threshold: Double?)
}
