package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "catalog_items",
    indices = [Index("active"), Index("name"), Index("synced"), Index("shopId"), Index("lastSoldAtMs")]
)
data class CatalogItem(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = com.voicetoinvoice.app.data.ShopContext.currentOrLegacy(),
    val name: String, // e.g. "Tamatar", "Aaloo", "Pyaz", "Bhindi"
    val unitId: String = "KG", // Default stored unit for this catalog item
    val price: Double, // Price per default unit
    val active: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val lowStockThreshold: Double? = null,

    /**
     * Materialized on-hand quantity, kept as the running total of `stock_ledger.deltaQty`.
     * Only `StockLedgerRepository` may write this. Replaces the old derive-on-read query in
     * `CatalogDao.getStockLevels()`, which silently ignored voided sales and rescanned the
     * whole transactions table per item. Rebuildable via
     * `StockLedgerRepository.rebuildFromLedger()`.
     */
    val stockQty: Double = 0.0,

    /**
     * Weighted-average cost per [unitId]. Null means this item has never had a costed
     * inbound movement -- profit for its sales is reported as UNKNOWN rather than as zero
     * cost, which would fabricate 100% margin. See `ProfitCalculator`.
     */
    val avgCostPrice: Double? = null,

    /** False for services/unmeasured items that should not appear in stock reports. */
    val trackStock: Boolean = true,

    /** Opt-in expiry batching. Defaulted by shop type; hides all expiry UI when false. */
    val trackExpiry: Boolean = false,

    /** Drives dead-stock and fast/slow-moving analysis without scanning transactions. */
    val lastSoldAtMs: Long? = null,
    val lastStockedAtMs: Long? = null
)
