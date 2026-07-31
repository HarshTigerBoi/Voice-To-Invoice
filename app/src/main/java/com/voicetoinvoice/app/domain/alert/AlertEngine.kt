package com.voicetoinvoice.app.domain.alert

import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.AlertDismissal
import com.voicetoinvoice.app.data.local.entity.StockReason
import com.voicetoinvoice.app.data.repository.StockLedgerRepository
import com.voicetoinvoice.app.domain.query.CustomerBalance
import kotlin.math.ceil

enum class AlertType { LOW_STOCK, OUT_OF_STOCK, EXPIRING_SOON, EXPIRED, DEAD_STOCK, UDHAAR_OVERDUE, MISSING_COST }
enum class AlertSeverity { HIGH, MEDIUM, LOW }

data class Alert(
    val type: AlertType,
    val severity: AlertSeverity,
    val message: String,
    val itemId: String? = null,
    val itemName: String? = null,
    val value: Double = 0.0
)

/**
 * Derives actionable alerts from the current state of the ledger -- no configuration required,
 * because a shopkeeper will not set 135 manual thresholds by hand, and a system that requires
 * that produces zero alerts in practice. See Docs/master_build_plan.md §3.4.
 *
 * Alerts are computed live rather than stored as persistent rows: everything they read from
 * (`catalog_items`, `stock_batches`, `stock_in`, receivables) is already indexed and fast, so
 * there is no caching benefit, and recomputing avoids a second place these facts could go stale.
 * Suppression is persisted in `alert_dismissals`, keyed on `(type, itemId)`, and applied as a
 * post-computation filter so the alert set stays a pure function of ledger state.
 */
class AlertEngine(private val db: AppDatabase) {

    /** Multiplier applied to (avg daily sold × lead time) to build in a safety margin before an
     *  item actually runs out. */
    private val LOW_STOCK_MULTIPLIER = 1.5

    /** Default days-to-restock assumption. Real per-shop lead time (`ShopProfile
     *  .defaultLeadTimeDays`) is part of the Phase 0 onboarding flow, which needs a paid SMS
     *  provider for phone-OTP auth and is not built yet -- this constant is the same default
     *  value that flow would have used. */
    private val DEFAULT_LEAD_TIME_DAYS = 2

    private val DEAD_STOCK_DAYS = 30
    private val AVG_DAILY_WINDOW_DAYS = 14
    private val MISSING_COST_THRESHOLD = 5

    /** A snoozed alert comes back after this long. One day: long enough to stop nagging within a
     *  single shift, short enough that a genuinely unresolved problem resurfaces tomorrow. */
    private val SNOOZE_DURATION_MS = 24 * 60 * 60 * 1000L

    suspend fun computeAlerts(nowMs: Long = System.currentTimeMillis()): List<Alert> {
        val alerts = mutableListOf<Alert>()
        val dayMs = 24 * 60 * 60 * 1000L
        val windowStart = nowMs - AVG_DAILY_WINDOW_DAYS * dayMs

        val catalog = db.catalogDao().getActiveCatalogList().filter { it.trackStock }
        val salesInWindow = db.transactionDao().getItemSalesBetween(windowStart, nowMs)
            .associateBy { it.itemId }

        for (item in catalog) {
            val avgDailySold = (salesInWindow[item.id]?.qty ?: 0.0) / AVG_DAILY_WINDOW_DAYS
            val effectiveThreshold = item.lowStockThreshold
                ?: kotlin.math.max(1.0, ceil(avgDailySold * DEFAULT_LEAD_TIME_DAYS * LOW_STOCK_MULTIPLIER))

            val everSold = salesInWindow.containsKey(item.id) || item.lastSoldAtMs != null
            when {
                item.stockQty <= 0.0 && everSold -> alerts += Alert(
                    type = AlertType.OUT_OF_STOCK,
                    severity = AlertSeverity.HIGH,
                    itemId = item.id, itemName = item.name,
                    message = "${item.name} खत्म हो गया"
                )
                item.stockQty in 0.01..effectiveThreshold -> alerts += Alert(
                    type = AlertType.LOW_STOCK,
                    severity = AlertSeverity.MEDIUM,
                    itemId = item.id, itemName = item.name,
                    value = item.stockQty,
                    message = "${item.name} सिर्फ़ ${fmt(item.stockQty)} ${item.unitId} बचा है"
                )
            }
        }

        // Dead stock: sitting on the shelf, not moving.
        val deadStockCutoff = nowMs - DEAD_STOCK_DAYS * dayMs
        for (item in db.catalogDao().getDeadStock(deadStockCutoff)) {
            alerts += Alert(
                type = AlertType.DEAD_STOCK,
                severity = AlertSeverity.LOW,
                itemId = item.id, itemName = item.name,
                value = item.stockQty * (item.avgCostPrice ?: 0.0),
                message = "${item.name} $DEAD_STOCK_DAYS दिन से नहीं बिका"
            )
        }

        // Expiry, for opt-in items only.
        val expired = db.stockBatchDao().getExpiringBefore(nowMs)
        val expiringSoon = db.stockBatchDao().getExpiringBefore(nowMs + 7 * dayMs).filterNot { it in expired }
        for (batch in expired) {
            alerts += Alert(
                type = AlertType.EXPIRED,
                severity = AlertSeverity.HIGH,
                itemId = batch.itemId, itemName = batch.itemName,
                value = batch.remainingQty,
                message = "${batch.itemName} की ${fmt(batch.remainingQty)} मात्रा एक्सपायर हो चुकी है"
            )
        }
        for (batch in expiringSoon) {
            alerts += Alert(
                type = AlertType.EXPIRING_SOON,
                severity = AlertSeverity.MEDIUM,
                itemId = batch.itemId, itemName = batch.itemName,
                value = batch.remainingQty,
                message = "${batch.itemName} जल्द एक्सपायर होगा (${fmt(batch.remainingQty)} बचा)"
            )
        }

        // Overdue Udhaar.
        val aging = CustomerBalance(db).aging(nowMs)
        if (aging.overdue > 0.0) {
            alerts += Alert(
                type = AlertType.UDHAAR_OVERDUE,
                severity = AlertSeverity.MEDIUM,
                value = aging.overdue,
                message = "₹${aging.overdue.toInt()} का उधार 1 महीने से बाकी है"
            )
        }

        // Missing cost: directly improves profit coverage, so this one alert can visibly
        // improve every other number on the reports screen.
        val missingCostCount = db.stockInDao().countCostMissing()
        if (missingCostCount >= MISSING_COST_THRESHOLD) {
            alerts += Alert(
                type = AlertType.MISSING_COST,
                severity = AlertSeverity.LOW,
                value = missingCostCount.toDouble(),
                message = "$missingCostCount खरीद का भाव दर्ज नहीं — मुनाफ़ा अधूरा दिख रहा है"
            )
        }

        // Suppression is applied AFTER computation, not by skipping the query: the alert set stays
        // a pure function of ledger state, and a snooze expiring restores the alert with no extra work.
        val suppressed = db.alertDismissalDao()
            .getActive(ShopContext.requireShopId(), nowMs)
            .map { it.type to it.itemId }
            .toSet()

        return alerts
            .filterNot { (it.type.name to (it.itemId ?: "")) in suppressed }
            .sortedBy { it.severity.ordinal }
            .take(MAX_ALERTS)
    }

    /** Hides this alert until [SNOOZE_DURATION_MS] from now. */
    suspend fun snooze(alert: Alert, nowMs: Long = System.currentTimeMillis()) {
        db.alertDismissalDao().upsert(
            AlertDismissal(
                shopId = ShopContext.requireShopId(),
                type = alert.type.name,
                itemId = alert.itemId ?: "",
                snoozedUntilMs = nowMs + SNOOZE_DURATION_MS
            )
        )
    }

    /** Hides this alert permanently (until the underlying condition clears and re-fires it). */
    suspend fun dismissForever(alert: Alert) {
        db.alertDismissalDao().upsert(
            AlertDismissal(
                shopId = ShopContext.requireShopId(),
                type = alert.type.name,
                itemId = alert.itemId ?: "",
                snoozedUntilMs = Long.MAX_VALUE
            )
        )
    }

    /**
     * Writes off the remaining quantity of every expired batch for one item and zeroes those
     * batches. Returns the quantity written off. Uses StockReason.EXPIRY (not WASTE) so expiry
     * loss stays separable from spoilage in reporting -- they have different causes and different
     * fixes (order less vs. store better).
     */
    suspend fun writeOffExpired(
        itemId: String,
        stockLedger: StockLedgerRepository,
        nowMs: Long = System.currentTimeMillis()
    ): Double {
        val batches = db.stockBatchDao().getExpiringBefore(nowMs).filter { it.itemId == itemId }
        var total = 0.0
        for (batch in batches) {
            if (batch.remainingQty <= 0.0) continue
            stockLedger.record(
                itemId = batch.itemId,
                itemName = batch.itemName,
                deltaQty = -batch.remainingQty,
                reason = StockReason.EXPIRY,
                unitCost = batch.unitCost,
                refId = batch.id,
                batchId = batch.id,
                note = "एक्सपायरी राइट-ऑफ़",
                occurredAtMs = nowMs
            )
            db.stockBatchDao().setRemaining(batch.id, 0.0)
            total += batch.remainingQty
        }
        return total
    }

    private fun fmt(q: Double): String = if (q % 1.0 == 0.0) q.toInt().toString() else "%.1f".format(q)

    companion object {
        const val MAX_ALERTS = 5
    }
}
