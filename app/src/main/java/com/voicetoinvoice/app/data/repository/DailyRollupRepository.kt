package com.voicetoinvoice.app.data.repository

import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.dao.RollupTotals
import com.voicetoinvoice.app.data.local.entity.StockReason
import java.util.Calendar

/**
 * Keeps `daily_rollups` current so reports read O(days) rows instead of scanning every
 * transaction the shop has ever made -- see Docs/master_build_plan.md §3.2.
 *
 * ## Self-healing instead of a scheduled repair job
 *
 * The plan called for a WorkManager `RollupRepairWorker` running nightly. This implementation
 * takes a simpler path with the same guarantee: [repairRange] recomputes any window directly
 * from `transactions`/`stock_ledger` (the actual source of truth) rather than trusting the
 * incremental counters, and the caller (`MainActivity`) runs it for the last 2 days on every app
 * start -- cheap (indexed, bounded queries) and it covers the realistic drift case (a process
 * killed mid-write) without needing periodic background scheduling. A manual "recalculate" action
 * on the Reports screen covers deeper, rarer drift.
 */
class DailyRollupRepository(private val db: AppDatabase) {

    private val dao = db.dailyRollupDao()

    /** Device-local midnight for the day containing [ts] -- matches how the rest of the app
     *  (e.g. `MainActivity`'s `todayMidnight`) already defines "today". */
    fun dayStartFor(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    suspend fun recordSale(itemId: String, itemName: String, qty: Double, total: Double, costAtSale: Double?, occurredAtMs: Long) {
        dao.increment(
            shopId = ShopContext.requireShopId(),
            dayStartMs = dayStartFor(occurredAtMs),
            itemId = itemId, itemName = itemName,
            qtySold = qty, revenue = total,
            cogs = if (costAtSale != null) qty * costAtSale else 0.0,
            costKnownRevenue = if (costAtSale != null) total else 0.0,
            txnCount = 1
        )
    }

    /** Reverses a voided sale's contribution -- keeps the rollup in sync with the ledger
     *  rather than leaving a phantom sale in historical reports forever. */
    suspend fun recordSaleVoid(itemId: String, itemName: String, qty: Double, total: Double, costAtSale: Double?, originalOccurredAtMs: Long) {
        dao.increment(
            shopId = ShopContext.requireShopId(),
            dayStartMs = dayStartFor(originalOccurredAtMs),
            itemId = itemId, itemName = itemName,
            qtySold = -qty, revenue = -total,
            cogs = if (costAtSale != null) -(qty * costAtSale) else 0.0,
            costKnownRevenue = if (costAtSale != null) -total else 0.0,
            txnCount = -1
        )
    }

    suspend fun recordReturn(itemId: String, itemName: String, qty: Double, value: Double, occurredAtMs: Long) {
        dao.increment(
            shopId = ShopContext.requireShopId(),
            dayStartMs = dayStartFor(occurredAtMs),
            itemId = itemId, itemName = itemName,
            returnQty = qty, returnValue = value
        )
    }

    suspend fun recordWaste(itemId: String, itemName: String, qty: Double, value: Double, occurredAtMs: Long) {
        dao.increment(
            shopId = ShopContext.requireShopId(),
            dayStartMs = dayStartFor(occurredAtMs),
            itemId = itemId, itemName = itemName,
            wasteQty = qty, wasteValue = value
        )
    }

    suspend fun recordStockIn(itemId: String, itemName: String, qty: Double, value: Double, occurredAtMs: Long) {
        dao.increment(
            shopId = ShopContext.requireShopId(),
            dayStartMs = dayStartFor(occurredAtMs),
            itemId = itemId, itemName = itemName,
            stockInQty = qty, stockInValue = value
        )
    }

    suspend fun getTotals(startMs: Long, endMs: Long): RollupTotals = dao.getTotals(startMs, endMs)

    suspend fun getTopByRevenue(startMs: Long, endMs: Long, limit: Int = 5) =
        dao.getItemTotalsByRevenue(startMs, endMs).take(limit)

    suspend fun getTopByQuantity(startMs: Long, endMs: Long, limit: Int = 5) =
        dao.getItemTotalsByQuantity(startMs, endMs).take(limit)

    suspend fun getBottomByQuantity(startMs: Long, endMs: Long, limit: Int = 5) =
        dao.getItemTotalsByQuantity(startMs, endMs).takeLast(limit).reversed()

    suspend fun getDailyRevenueSeries(startMs: Long, endMs: Long) = dao.getDailyRevenueSeries(startMs, endMs)

    /**
     * Recomputes every rollup row touching [startMs, endMs) directly from `transactions` and
     * `stock_ledger`. Deletes the existing rows for the window first, so this is idempotent --
     * safe to call repeatedly (once at every app start for a short recent window, or on demand
     * from the Reports screen for a full recompute).
     */
    suspend fun repairRange(startMs: Long, endMs: Long) {
        val shopId = ShopContext.requireShopId()
        dao.deleteRange(startMs, endMs)

        // Sales (and their voids -- non-voided only, since a voided sale should contribute
        // nothing at all, not a sale entry AND a cancelling entry).
        val sales = db.transactionDao().getTransactionsInRangeOnce(startMs, endMs)
        for (tx in sales) {
            if (tx.txnType == com.voicetoinvoice.app.data.local.entity.TxnType.SALE) {
                dao.increment(
                    shopId = shopId, dayStartMs = dayStartFor(tx.timestamp),
                    itemId = tx.itemId, itemName = tx.itemName,
                    qtySold = tx.quantity, revenue = tx.total,
                    cogs = if (tx.costAtSale != null) tx.quantity * tx.costAtSale else 0.0,
                    costKnownRevenue = if (tx.costAtSale != null) tx.total else 0.0,
                    txnCount = 1
                )
            } else {
                // TxnType.RETURN rows carry negative quantity/total already.
                dao.increment(
                    shopId = shopId, dayStartMs = dayStartFor(tx.timestamp),
                    itemId = tx.itemId, itemName = tx.itemName,
                    returnQty = -tx.quantity, returnValue = -tx.total
                )
            }
        }

        // Waste and stock-in, from the ledger (the source of truth since Phase 1 -- see
        // StockLedgerRepository).
        val movements = db.stockLedgerDao().getInRange(startMs, endMs)
        for (m in movements) {
            when (m.reason) {
                StockReason.WASTE -> dao.increment(
                    shopId = shopId, dayStartMs = dayStartFor(m.occurredAtMs),
                    itemId = m.itemId, itemName = m.itemName,
                    wasteQty = -m.deltaQty, // deltaQty is negative for WASTE
                    wasteValue = -m.deltaQty * (m.unitCost ?: 0.0)
                )
                StockReason.STOCK_IN -> dao.increment(
                    shopId = shopId, dayStartMs = dayStartFor(m.occurredAtMs),
                    itemId = m.itemId, itemName = m.itemName,
                    stockInQty = m.deltaQty,
                    stockInValue = m.deltaQty * (m.unitCost ?: 0.0)
                )
                else -> Unit // SALE/SALE_VOID/RETURN_IN etc. are already covered via transactions above
            }
        }
    }
}
