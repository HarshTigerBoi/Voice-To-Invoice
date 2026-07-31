package com.voicetoinvoice.app.domain.intel

import com.voicetoinvoice.app.data.local.AppDatabase
import kotlin.math.ceil

data class ReorderLine(
    val itemId: String,
    val itemName: String,
    val unitId: String,
    val avgDailySold: Double,
    val stockQty: Double,
    val daysOfCover: Double,
    val suggestQty: Double,
    val reasoning: String
)

data class SupplierReorderGroup(
    val supplierId: String?,
    val supplierName: String,
    val lines: List<ReorderLine>
)

/**
 * Explainable-arithmetic reorder suggestions -- no model, no AI call. See Docs/master_build_plan
 * .md §4.2: a suggestion the shopkeeper can't sanity-check in their head is one they'll ignore,
 * and every number here is derived from the shop's own recent sales, nothing else.
 *
 * Grouped by supplier because that is the actual unit of work: a shopkeeper places one order per
 * supplier, not one decision per item. Since `catalog_items` carries no supplier of its own, the
 * "supplier for this item" is derived as whoever it was most recently bought from.
 */
class ReorderAdvisor(private val db: AppDatabase) {

    /** Same default used by `AlertEngine` -- see its docs for why this isn't read from a
     *  per-shop setting yet (that's part of the Phase 0 onboarding flow, gated on paid SMS). */
    private val DEFAULT_LEAD_TIME_DAYS = 2
    private val SAFETY_DAYS = 1
    private val SALES_WINDOW_DAYS = 21
    private val TARGET_COVER_DAYS = 7

    suspend fun suggestions(nowMs: Long = System.currentTimeMillis()): List<ReorderLine> {
        val dayMs = 24 * 60 * 60 * 1000L
        val windowStart = nowMs - SALES_WINDOW_DAYS * dayMs
        val sales = db.transactionDao().getItemSalesBetween(windowStart, nowMs).associateBy { it.itemId }
        val catalog = db.catalogDao().getActiveCatalogList().filter { it.trackStock }

        val lines = mutableListOf<ReorderLine>()
        for (item in catalog) {
            val qtySold = sales[item.id]?.qty ?: 0.0
            if (qtySold <= 0.0) continue // never sold in the window -- nothing to project from

            val avgDaily = qtySold / SALES_WINDOW_DAYS
            val daysOfCover = item.stockQty / avgDaily
            val reorderPoint = avgDaily * (DEFAULT_LEAD_TIME_DAYS + SAFETY_DAYS)

            if (item.stockQty > reorderPoint) continue

            val suggestQty = ceil((avgDaily * TARGET_COVER_DAYS - item.stockQty).coerceAtLeast(0.0))
            if (suggestQty <= 0.0) continue

            lines += ReorderLine(
                itemId = item.id,
                itemName = item.name,
                unitId = item.unitId,
                avgDailySold = avgDaily,
                stockQty = item.stockQty,
                daysOfCover = daysOfCover,
                suggestQty = suggestQty,
                reasoning = "रोज़ ~${fmt(avgDaily)} ${item.unitId} बिकता है, " +
                    "${fmt(item.stockQty)} ${item.unitId} बचा है (${daysOfCover.toInt()} दिन का स्टॉक)"
            )
        }
        return lines.sortedBy { it.daysOfCover }
    }

    /** The same suggestions, grouped into one order list per supplier -- the actual unit of
     *  work when a shopkeeper picks up the phone to place an order. */
    suspend fun suggestionsBySupplier(nowMs: Long = System.currentTimeMillis()): List<SupplierReorderGroup> {
        val lines = suggestions(nowMs)
        if (lines.isEmpty()) return emptyList()

        val itemToSupplier = db.stockInDao().getLatestSupplierPerItem().associate { it.itemId to it.supplierId }
        val suppliers = db.supplierDao().getAllSuppliersList().associateBy { it.id }

        return lines.groupBy { itemToSupplier[it.itemId] }
            .map { (supplierId, groupLines) ->
                SupplierReorderGroup(
                    supplierId = supplierId,
                    supplierName = supplierId?.let { suppliers[it]?.name } ?: "अन्य / सप्लायर तय नहीं",
                    lines = groupLines
                )
            }
            .sortedBy { it.supplierName }
    }

    private fun fmt(q: Double): String = if (q % 1.0 == 0.0) q.toInt().toString() else "%.1f".format(q)
}
