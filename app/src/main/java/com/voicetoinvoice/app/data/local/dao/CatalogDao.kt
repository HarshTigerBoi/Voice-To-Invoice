package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM catalog_items WHERE synced = 0")
    suspend fun getUnsyncedCatalog(): List<CatalogItem>

    @Query("UPDATE catalog_items SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
