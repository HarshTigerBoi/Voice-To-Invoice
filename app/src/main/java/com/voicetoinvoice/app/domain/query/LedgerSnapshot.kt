package com.voicetoinvoice.app.domain.query

import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CreditStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import java.util.Calendar

/** The answers to every question the assistant can be asked, kept hot in memory so a
 *  spoken query costs zero database work at ask-time -- Room's Flows already emit on
 *  every write to the underlying tables (transactions/stock_in/credits/customers), so
 *  this cannot go stale without the data changing first. Built entirely from Flows the
 *  rest of the app already collects (getTodayTransactions/getStockLevels/getAllCredits/
 *  getActiveCustomers) rather than new SQL -- those are already correctly scoped
 *  (today-only, or a single aggregating query); the actual cost problem was
 *  `LedgerQueries` re-running `getAllTransactionsList()`/`getAllStockInRecordsList()`
 *  (the ENTIRE table) on every single question, which this replaces for the common
 *  cases. See Docs/assistant_speed_and_pipeline_fix_plan.md §3.2. */
object LedgerSnapshot {
    @Volatile var todayTotal: Double = 0.0
        private set
    @Volatile var todayCount: Int = 0
        private set
    @Volatile var topItemToday: String? = null
        private set
    @Volatile var stockByItemName: Map<String, Double> = emptyMap()
        private set
    @Volatile var outstandingByCustomerName: Map<String, Double> = emptyMap()
        private set
    @Volatile var lastRefreshedMs: Long = 0L
        private set

    @Volatile private var started = false

    fun start(scope: CoroutineScope, db: AppDatabase) {
        if (started) return
        started = true

        val todayMidnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        combine(
            db.transactionDao().getTodayTransactions(todayMidnight),
            db.catalogDao().getStockLevels(),
            db.catalogDao().getActiveCatalog(),
            db.creditDao().getAllCredits(),
            db.customerDao().getActiveCustomers()
        ) { todayTxns, stockLevels, catalog, credits, customers ->
            todayTotal = todayTxns.sumOf { it.total }
            todayCount = todayTxns.size
            topItemToday = todayTxns.groupBy { it.itemName }
                .mapValues { entry -> entry.value.sumOf { it.quantity } }
                .maxByOrNull { it.value }?.key

            val catalogById = catalog.associateBy { it.id }
            stockByItemName = stockLevels.mapNotNull { level ->
                catalogById[level.itemId]?.name?.let { name -> name to level.onHand }
            }.toMap()

            val customerById = customers.associateBy { it.id }
            outstandingByCustomerName = credits
                .filter { it.status != CreditStatus.PAID && it.customerId != null }
                // groupBy's keySelector re-reads it.customerId as its static (nullable)
                // type regardless of the filter above -- the !! here is justified by
                // that filter, not an unchecked assumption.
                .groupBy { it.customerId!! }
                .mapNotNull { (custId, list) ->
                    customerById[custId]?.name?.let { name -> name to list.sumOf { c -> c.amount } }
                }
                .toMap()

            lastRefreshedMs = System.currentTimeMillis()
        }.launchIn(scope)
    }

    /** False before the first Flow emission (cold-start race) or if the snapshot is
     *  older than [maxAgeMs] -- callers should fall back to a cold `LedgerQueries` read
     *  rather than answer from a snapshot that might not reflect reality. */
    fun isFresh(maxAgeMs: Long = 10_000L): Boolean =
        lastRefreshedMs > 0L && System.currentTimeMillis() - lastRefreshedMs < maxAgeMs
}
