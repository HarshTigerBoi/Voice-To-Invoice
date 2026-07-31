package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A costed, dated lot of an item. Created only on inbound stock for items whose
 * [CatalogItem.trackExpiry] is true (defaulted by shop type -- a vegetable seller does not
 * want expiry prompts on tomatoes, a kirana/medical shop needs them on packaged goods).
 *
 * Batches exist to drive expiry ALERTING, not costing. Cost stays on the weighted-average
 * `catalog_items.avgCostPrice`: FIFO/lot costing would force every sale to resolve which lot
 * it drew from, which is impossible to capture by voice and is not how this segment accounts.
 *
 * Sales draw down [remainingQty] nearest-expiry-first (FEFO), matching what a shopkeeper
 * physically does when facing up a shelf.
 */
@Entity(
    tableName = "stock_batches",
    indices = [Index("itemId"), Index("expiryDateMs"), Index("shopId")]
)
data class StockBatch(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopId: String,
    val itemId: String,
    val itemName: String,
    val receivedQty: Double,
    val remainingQty: Double,
    val unitCost: Double? = null,
    /** Null when the shopkeeper did not state an expiry; the batch still tracks quantity. */
    val expiryDateMs: Long? = null,
    val receivedAtMs: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
