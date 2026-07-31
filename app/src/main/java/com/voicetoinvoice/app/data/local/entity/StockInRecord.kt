package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "stock_in",
    indices = [Index("itemId"), Index("timestamp"), Index("costMissing"), Index("synced"), Index("shopId")]
)
data class StockInRecord(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = com.voicetoinvoice.app.data.ShopContext.currentOrLegacy(),
    val itemId: String,
    val itemName: String,
    val quantity: Double,
    /**
     * Cost **per unit** (per KG/piece/packet), not the invoice total.
     *
     * This was ambiguous and inconsistent before: `SttWorker` and `HomeScreen` wrote a
     * per-unit rate here, `StockInScreen`'s "Total Cost Price (₹)" field wrote an invoice
     * total, and `StockInDao.getLatestCostPricePerItem` divided by quantity to "recover" a
     * per-unit figure. For voice stock-ins -- the primary path -- that produced a cost
     * understated by a factor of `quantity`, which overstated profit. Per-unit is now the
     * single convention; UI that collects a total divides by quantity before writing.
     */
    val costPrice: Double,
    val supplier: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val supplierId: String? = null,
    /** True when the stock-in was booked from voice with no spoken cost price. The
     *  quantity is trusted and committed; cost is back-filled later from StockInScreen. */
    val costMissing: Boolean = false
)

