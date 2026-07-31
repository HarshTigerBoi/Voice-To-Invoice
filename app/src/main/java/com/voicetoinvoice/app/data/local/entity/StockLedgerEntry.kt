package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Why every stock movement is a row here (see Docs/master_build_plan.md §1.1):
 *
 * On-hand quantity used to be derived on every read as
 * `SUM(stock_in.quantity) - SUM(transactions.quantity)` in `CatalogDao.getStockLevels()`.
 * That had three defects this table fixes:
 *
 *  1. It did not filter `voided = 0`, so voiding a wrongly-booked sale never returned the
 *     stock -- the item stayed permanently short. Voiding is the correction signal for
 *     Learned Parse Memory (ISSUE-031), so it fires often.
 *  2. Two correlated subqueries per catalog item on every recomposition -- a full scan of
 *     `transactions` per item, 135 items, on a UI Flow.
 *  3. It could not express *why* stock moved. Spoilage was smuggled in as a NEGATIVE
 *     `stock_in.quantity`, making "purchased" and "spoiled" indistinguishable in one
 *     column, so purchase totals and spoilage reporting were both unrecoverable.
 *
 * This ledger is append-only. `catalog_items.stockQty` is the materialized running total,
 * written in the same DB transaction as the ledger insert, and
 * `StockLedgerRepository.rebuildFromLedger()` can always recompute it from these rows.
 */
@Entity(
    tableName = "stock_ledger",
    indices = [Index("itemId"), Index("occurredAtMs"), Index("refId"), Index("shopId")]
)
data class StockLedgerEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopId: String,
    val itemId: String,
    /** Denormalized so an unlisted item's movement stays readable if the catalog row goes. */
    val itemName: String,
    /** SIGNED: positive brings stock in, negative takes it out. */
    val deltaQty: Double,
    val reason: StockReason,
    /** Per-unit cost for IN movements. Null means genuinely unknown -- never treat as 0. */
    val unitCost: Double? = null,
    /** `transactions.id`, `stock_in.id`, or a bill id, depending on [reason]. */
    val refId: String? = null,
    val batchId: String? = null,
    val note: String? = null,
    val occurredAtMs: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

enum class StockReason {
    /** First-time physical count captured at onboarding. */
    OPENING,

    /** Purchase received from a supplier. Positive. */
    STOCK_IN,

    /** Sold to a customer. Negative. */
    SALE,

    /** Reverses a [SALE] when a transaction is voided. Positive. */
    SALE_VOID,

    /** Customer brought goods back and they went back on the shelf. Positive. */
    RETURN_IN,

    /** Sent back to the supplier. Negative. */
    RETURN_TO_SUPPLIER,

    /** Spoiled or damaged. Negative. Replaces the old negative-`stock_in` hack. */
    WASTE,

    /** Written off past its expiry date. Negative. */
    EXPIRY,

    /** Physical count adjustment: delta = counted - system. Either sign. */
    RECOUNT,

    /** Manual correction from the UI. Either sign. */
    MANUAL_ADJUST;

    /** True when this reason represents stock arriving and can therefore carry a cost. */
    val isInbound: Boolean
        get() = this == OPENING || this == STOCK_IN || this == RETURN_IN

    /** Short Hindi label for movement lists. */
    val hindiLabel: String
        get() = when (this) {
            OPENING -> "शुरुआती स्टॉक"
            STOCK_IN -> "माल आया"
            SALE -> "बिक्री"
            SALE_VOID -> "बिक्री रद्द"
            RETURN_IN -> "वापस आया"
            RETURN_TO_SUPPLIER -> "सप्लायर को वापस"
            WASTE -> "खराब"
            EXPIRY -> "एक्सपायरी"
            RECOUNT -> "गिनती सुधार"
            MANUAL_ADJUST -> "मैन्युअल बदलाव"
        }
}
