package com.voicetoinvoice.app.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.StockBatch
import com.voicetoinvoice.app.data.local.entity.StockLedgerEntry
import com.voicetoinvoice.app.data.local.entity.StockReason

/**
 * The ONLY code permitted to change `catalog_items.stockQty` or `catalog_items.avgCostPrice`.
 *
 * Every stock movement is (a) appended to `stock_ledger` and (b) applied to the materialized
 * `catalog_items.stockQty`, both inside one DB transaction, so the audit trail and the fast
 * read path cannot drift. See Docs/master_build_plan.md §1.2-1.3.
 *
 * ## Costing: weighted average, not FIFO
 *
 * `avgCostPrice` is a moving weighted average maintained on inbound movements that carry a
 * cost. FIFO would require resolving which lot each sale drew from -- impossible to capture by
 * voice, and not how kirana/vegetable shops account. Batches ([StockBatch]) track expiry dates
 * for alerting only; they do not participate in costing.
 *
 * An inbound movement with an UNKNOWN cost (`unitCost == null`, the `costMissing` stock-in
 * case) deliberately leaves `avgCostPrice` untouched rather than averaging in a zero. Treating
 * unknown as free would fabricate 100% margin on those goods.
 */
class StockLedgerRepository(private val db: AppDatabase) {

    private val catalogDao = db.catalogDao()
    private val ledgerDao = db.stockLedgerDao()
    private val batchDao = db.stockBatchDao()
    private val transactionDao = db.transactionDao()

    /**
     * `record()`/`recordSale()`/`voidSale()` are already the single, centralized entry points
     * for every stock and sale movement in the app -- so they are also the natural (and only)
     * place to keep `daily_rollups` current, without hunting down every commit call site a
     * second time. Lazily constructed to avoid a circular-construction dependency at startup.
     */
    private val rollups: DailyRollupRepository by lazy { DailyRollupRepository(db) }

    /**
     * Appends one movement and applies it to the materialized quantity.
     *
     * @param deltaQty SIGNED. Callers pass the true sign; this method does not guess from
     *   [reason], because RECOUNT and MANUAL_ADJUST are legitimately either sign.
     * @param unitCost per-unit cost, for inbound movements only. Null = genuinely unknown.
     * @param refId the transactions/stock_in row this movement came from. When non-null it is
     *   used for idempotency: a WorkManager retry that re-delivers the same job (ISSUE-045 saw
     *   one recording reach the server twice) must not deduct stock twice.
     */
    suspend fun record(
        itemId: String,
        itemName: String,
        deltaQty: Double,
        reason: StockReason,
        unitCost: Double? = null,
        refId: String? = null,
        batchId: String? = null,
        note: String? = null,
        occurredAtMs: Long = System.currentTimeMillis(),
        expiryDateMs: Long? = null
    ): StockLedgerEntry? {
        if (deltaQty == 0.0) return null

        return db.withTransaction {
            // Idempotency: same ref + same reason means this movement already landed.
            if (refId != null && ledgerDao.countForRef(refId, reason.name) > 0) {
                Log.i(
                    TAG,
                    "Skipping duplicate stock movement: refId=$refId reason=$reason " +
                        "(already recorded -- most likely a WorkManager retry)"
                )
                return@withTransaction null
            }

            // Pre-movement snapshot, needed both for the WAC weight below and to resolve what
            // cost a WASTE movement should be valued and STORED at (see storedUnitCost).
            val itemBefore = catalogDao.getById(itemId)

            // What to persist on the ledger row itself. This used to be
            // `unitCost.takeIf { reason.isInbound }`, which correctly kept WASTE out of the
            // weighted-average calculation (it never feeds WAC either way -- that block below is
            // separately gated on `reason.isInbound`) but ALSO meant a WASTE row stored no cost
            // at all, even though it IS valued (at the item's avgCostPrice) when first written.
            // `repairRange()` recomputing this same day later from the raw ledger had no way to
            // recover that valuation and produced 0 instead of the original waste value -- a
            // real drift between the incremental write and a from-scratch repair. Snapshotting
            // the valuation cost onto WASTE rows (not just inbound ones) closes that gap and
            // makes the two paths agree by construction rather than by coincidence.
            val storedUnitCost = when {
                reason.isInbound -> unitCost
                reason == StockReason.WASTE -> unitCost ?: itemBefore?.avgCostPrice
                else -> unitCost
            }

            val entry = StockLedgerEntry(
                shopId = ShopContext.requireShopId(),
                itemId = itemId,
                itemName = itemName,
                deltaQty = deltaQty,
                reason = reason,
                unitCost = storedUnitCost,
                refId = refId,
                batchId = batchId,
                note = note,
                occurredAtMs = occurredAtMs
            )
            ledgerDao.insert(entry)

            // Recompute the weighted average BEFORE the quantity update, using the pre-movement
            // on-hand as the weight of the existing average.
            if (reason.isInbound && unitCost != null && unitCost > 0.0 && itemBefore != null) {
                val newAvg = weightedAverage(
                    oldQty = itemBefore.stockQty,
                    oldAvg = itemBefore.avgCostPrice,
                    inQty = deltaQty,
                    inCost = unitCost
                )
                if (newAvg != null) catalogDao.setAvgCostPrice(itemId, newAvg)
            }

            catalogDao.applyStockDelta(itemId, deltaQty, occurredAtMs)

            val item = catalogDao.getById(itemId)

            // Feed the daily rollup for the two reasons `record()` itself has enough information
            // to value. SALE/SALE_VOID go through `recordSale`/`voidSale` (which know the sale's
            // total/costAtSale, unavailable here), RETURN_IN through `recordReturnIn`.
            when (reason) {
                StockReason.WASTE -> rollups.recordWaste(
                    itemId, itemName, -deltaQty, -deltaQty * (storedUnitCost ?: 0.0), occurredAtMs
                )
                StockReason.STOCK_IN -> rollups.recordStockIn(
                    itemId, itemName, deltaQty, deltaQty * (unitCost ?: 0.0), occurredAtMs
                )
                else -> Unit
            }

            // Expiry batches, for opt-in items only.
            if (item?.trackExpiry == true) {
                if (reason.isInbound && deltaQty > 0) {
                    batchDao.insert(
                        StockBatch(
                            shopId = entry.shopId,
                            itemId = itemId,
                            itemName = itemName,
                            receivedQty = deltaQty,
                            remainingQty = deltaQty,
                            unitCost = unitCost,
                            expiryDateMs = expiryDateMs,
                            receivedAtMs = occurredAtMs
                        )
                    )
                } else if (deltaQty < 0) {
                    drawDownFefo(itemId, -deltaQty)
                }
            }

            entry
        }
    }

    /**
     * Books a sale's stock effect. Separate entry point so the sale path cannot forget the
     * `refId`, which is what makes the movement idempotent across retries.
     */
    suspend fun recordSale(
        itemId: String,
        itemName: String,
        qty: Double,
        transactionId: String,
        occurredAtMs: Long = System.currentTimeMillis(),
        total: Double = 0.0,
        costAtSale: Double? = null
    ): StockLedgerEntry? {
        val entry = record(
            itemId = itemId,
            itemName = itemName,
            deltaQty = -kotlin.math.abs(qty),
            reason = StockReason.SALE,
            refId = transactionId,
            occurredAtMs = occurredAtMs
        )
        // Only count the rollup once per unique movement (the idempotency guard in `record()`
        // returns null on a duplicate refId -- e.g. a WorkManager retry -- and a null here must
        // not double-count that sale's revenue).
        if (entry != null) rollups.recordSale(itemId, itemName, qty, total, costAtSale, occurredAtMs)
        return entry
    }

    /**
     * Voids a sale AND returns its stock, atomically.
     *
     * This is the only void entry point. `TransactionDao.voidTransaction` alone was the bug in
     * Docs/master_build_plan.md §1.1(1): because the old derived stock query never filtered
     * `voided = 0`, voiding a wrongly-booked sale left the item permanently short. Voiding is
     * the correction signal for Learned Parse Memory (ISSUE-031), so this path is hot.
     */
    suspend fun voidSale(transactionId: String, voidedAtMs: Long = System.currentTimeMillis()): Boolean {
        return db.withTransaction {
            val tx = transactionDao.getById(transactionId)
            if (tx == null) {
                Log.w(TAG, "voidSale: no transaction $transactionId")
                return@withTransaction false
            }
            if (tx.voided) {
                Log.i(TAG, "voidSale: $transactionId already voided, nothing to do")
                return@withTransaction false
            }

            transactionDao.voidTransaction(transactionId, voidedAtMs)

            // The reversal delta is the transaction's OWN signed quantity, which works for both
            // directions without a special case:
            //   SALE   quantity=+3 booked a -3 movement -> reversing restores +3.
            //   RETURN quantity=-2 booked a +2 movement -> reversing removes  -2.
            val restoreQty = tx.quantity
            record(
                itemId = tx.itemId,
                itemName = tx.itemName,
                deltaQty = restoreQty,
                reason = StockReason.SALE_VOID,
                refId = transactionId,
                note = "Void of ${tx.quantity} ${tx.itemName}",
                occurredAtMs = voidedAtMs
            )
            // Reverses the ORIGINAL day's rollup (not today's) -- a sale made yesterday and
            // voided today must stop counting toward YESTERDAY's revenue, not subtract from
            // today's, or both days end up wrong.
            if (tx.txnType == com.voicetoinvoice.app.data.local.entity.TxnType.SALE) {
                rollups.recordSaleVoid(tx.itemId, tx.itemName, tx.quantity, tx.total, tx.costAtSale, tx.timestamp)
            }
            // A void is the one signal that can CONTRADICT an already-confirmed item alias --
            // the shopkeeper is saying the booked item was wrong. See ShopLearningRepository.
            com.voicetoinvoice.app.data.repository.ShopLearningRepository(db).decayItemAlias(tx.itemId)
            true
        }
    }

    /**
     * Books a purchase where the shopkeeper supplied the INVOICE TOTAL rather than a unit rate.
     *
     * `StockInScreen`'s field is labelled "Total Cost Price (₹)" while `StockInRecord.costPrice`
     * stores cost PER UNIT, and those two conventions were previously mixed in one column
     * (ISSUE-054). Doing the conversion here rather than inline in a UI lambda means there is one
     * place it can be wrong, and it can be tested.
     *
     * @param invoiceTotal what was paid for the whole lot.
     * @return the per-unit cost that was stored, or null when it could not be derived.
     */
    suspend fun recordPurchaseFromInvoiceTotal(
        itemId: String,
        itemName: String,
        qty: Double,
        invoiceTotal: Double,
        supplier: String? = null,
        supplierId: String? = null,
        expiryDateMs: Long? = null
    ): Double? {
        if (qty <= 0.0) return null
        val unitCost = if (invoiceTotal > 0.0) invoiceTotal / qty else 0.0

        val stockRecord = com.voicetoinvoice.app.data.local.entity.StockInRecord(
            shopId = ShopContext.requireShopId(),
            itemId = itemId,
            itemName = itemName,
            quantity = qty,
            costPrice = unitCost,
            costMissing = unitCost <= 0.0,
            supplier = supplier,
            supplierId = supplierId
        )
        db.stockInDao().insert(stockRecord)
        record(
            itemId = itemId,
            itemName = itemName,
            deltaQty = qty,
            reason = StockReason.STOCK_IN,
            unitCost = unitCost.takeIf { it > 0.0 },
            refId = stockRecord.id,
            expiryDateMs = expiryDateMs
        )
        return unitCost.takeIf { it > 0.0 }
    }

    /** Customer brought goods back and they went back on the shelf. */
    suspend fun recordReturnIn(
        itemId: String,
        itemName: String,
        qty: Double,
        returnTransactionId: String,
        unitCost: Double? = null,
        occurredAtMs: Long = System.currentTimeMillis(),
        returnValue: Double = 0.0
    ): StockLedgerEntry? {
        val entry = record(
            itemId = itemId,
            itemName = itemName,
            deltaQty = kotlin.math.abs(qty),
            reason = StockReason.RETURN_IN,
            unitCost = unitCost,
            refId = returnTransactionId,
            occurredAtMs = occurredAtMs
        )
        if (entry != null) rollups.recordReturn(itemId, itemName, qty, returnValue, occurredAtMs)
        return entry
    }

    /**
     * Back-fills a cost onto an earlier inbound movement (the `costMissing` stock-in case,
     * where the shopkeeper stated a quantity but no price) and re-derives the item's weighted
     * average from the full ledger, so a late cost still lands correctly.
     */
    suspend fun backfillCost(stockInId: String, unitCost: Double) {
        if (unitCost <= 0.0) return
        db.withTransaction {
            val itemId = db.stockInDao().getById(stockInId)?.itemId ?: return@withTransaction
            ledgerDao.setCostForRef(stockInId, unitCost)
            db.stockInDao().setCost(stockInId, unitCost)
            // Re-derive from the whole ledger rather than incrementally: a late cost changes the
            // weight of a movement that already happened, so the incremental formula no longer
            // applies and only a full recompute is correct.
            catalogDao.setAvgCostPrice(itemId, ledgerDao.weightedAvgCostForItem(itemId))
        }
    }

    /**
     * Recomputes `stockQty` and `avgCostPrice` for every item from the ledger.
     *
     * Exposed because a materialized counter will eventually drift (a process killed between
     * the ledger insert and the counter update, a partial migration), and a shop whose stock
     * number is wrong needs a recovery path that does not involve reinstalling.
     */
    suspend fun rebuildFromLedger(): Int {
        var rebuilt = 0
        val itemIds = (catalogDao.getAllCatalogList().map { it.id } +
            ledgerDao.getAllItemIdsWithMovement()).distinct()
        for (id in itemIds) {
            db.withTransaction {
                catalogDao.setStockQty(id, ledgerDao.sumDeltaForItem(id))
                catalogDao.setAvgCostPrice(id, ledgerDao.weightedAvgCostForItem(id))
            }
            rebuilt++
        }
        Log.i(TAG, "rebuildFromLedger: recomputed stockQty/avgCostPrice for $rebuilt items")
        return rebuilt
    }

    /** Consumes [qty] from open batches, soonest-expiry-first. */
    private suspend fun drawDownFefo(itemId: String, qty: Double) {
        var remaining = qty
        for (batch in batchDao.getOpenBatchesFefo(itemId)) {
            if (remaining <= 0.0) break
            val take = minOf(batch.remainingQty, remaining)
            batchDao.setRemaining(batch.id, batch.remainingQty - take)
            remaining -= take
        }
        if (remaining > 0.0) {
            // Sold more than any batch accounted for -- legitimate when stock predates batch
            // tracking being switched on. Quantity truth stays with stockQty; don't invent a batch.
            Log.i(TAG, "drawDownFefo: $itemId short by $remaining with no open batch (pre-tracking stock)")
        }
    }

    companion object {
        private const val TAG = "StockLedgerRepo"

        /**
         * Moving weighted-average cost.
         *
         * Returns null when the result would be meaningless (no positive quantity after the
         * movement). A null [oldAvg] means the item had no known cost, so the incoming cost
         * becomes the average outright rather than being averaged against a fabricated zero.
         * A negative [oldQty] (oversold) is clamped to zero for weighting.
         */
        fun weightedAverage(oldQty: Double, oldAvg: Double?, inQty: Double, inCost: Double): Double? {
            if (inQty <= 0.0) return oldAvg
            val weightQty = oldQty.coerceAtLeast(0.0)
            val totalQty = weightQty + inQty
            if (totalQty <= 0.0) return null
            val effectiveOldAvg = oldAvg ?: return inCost
            return (weightQty * effectiveOldAvg + inQty * inCost) / totalQty
        }
    }
}
