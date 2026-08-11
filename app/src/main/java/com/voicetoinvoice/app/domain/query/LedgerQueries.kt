package com.voicetoinvoice.app.domain.query

import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.domain.parser.PhoneticKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only ledger lookups behind the assistant's spoken answers.
 *
 * ## Two things changed here
 *
 * **1. Stock comes from the ledger, not from arithmetic over `stock_in`.**
 * `getStockLevel` used to compute `SUM(stock_in.quantity) − SUM(transactions.quantity)` in Kotlin.
 * That is now actively wrong: spoilage no longer writes a `stock_in` row (it is a `WASTE` movement
 * in `stock_ledger`), so the old formula would over-report on-hand by every kilo ever thrown away,
 * and it never subtracted voided sales either. It now reads the materialized
 * `catalog_items.stockQty`, which `StockLedgerRepository` keeps current.
 *
 * **2. Aggregates run in SQL.** Every method used to call `getAllTransactionsList()` and filter in
 * Kotlin — `getStockLevel` alone loaded the entire catalog, the entire `stock_in` table and the
 * entire `transactions` table to answer one question about one item. The assistant is supposed to
 * feel instant, and after a year of trading that approach is seconds of work per spoken question.
 */
class LedgerQueries(private val db: AppDatabase) {

    /** Revenue and sale count for a window. */
    suspend fun getTodaySalesSummary(todayMidnightMs: Long): Pair<Double, Int> = withContext(Dispatchers.IO) {
        val totals = db.transactionDao().getPeriodTotals(todayMidnightMs, Long.MAX_VALUE)
        totals.revenue to totals.lineCount
    }

    suspend fun getSalesBetween(startMs: Long, endMs: Long): Pair<Double, Int> = withContext(Dispatchers.IO) {
        val totals = db.transactionDao().getPeriodTotals(startMs, endMs)
        totals.revenue to totals.lineCount
    }

    /** Revenue booked on credit in a window — "आज उधार पर कितना बेचा". Distinct from
     *  [getTotalReceivables], which is the standing balance across all time. RETURN rows carry
     *  a negative `total`, so the sum nets returns-against-credit without a special case. */
    suspend fun getCreditSalesInPeriod(startMs: Long, endMs: Long): Double = withContext(Dispatchers.IO) {
        db.transactionDao().getTotalByPaymentMode("CREDIT", startMs, endMs)
    }

    /** Gross profit for a window, with its own coverage caveat. See [ProfitCalculator]. */
    suspend fun getProfit(startMs: Long, endMs: Long): ProfitResult = withContext(Dispatchers.IO) {
        ProfitCalculator(db).compute(startMs, endMs)
    }

    /**
     * On-hand for the item whose name best matches [itemNameQuery].
     *
     * Matching is phonetic rather than substring so "aaloo", "आलू" and a mis-transcribed "alu" all
     * resolve to the same catalog row — the same reason item matching moved to [PhoneticKey] in
     * ISSUE-020.
     */
    suspend fun getStockLevel(itemNameQuery: String): Double? = withContext(Dispatchers.IO) {
        val matched = findCatalogItem(itemNameQuery) ?: return@withContext null
        matched.stockQty
    }

    /** Resolved item plus its on-hand, so callers can name what they answered about. */
    suspend fun getStockLevelWithName(itemNameQuery: String): Pair<String, Double>? =
        withContext(Dispatchers.IO) {
            val matched = findCatalogItem(itemNameQuery) ?: return@withContext null
            matched.name to matched.stockQty
        }

    /**
     * Quantity and revenue for ONE item in a window.
     *
     * ISSUE-118: "आज कितने आलू बिके" previously fell through to the whole-shop revenue branch
     * because REVENUE_WORDS contains "बिका" (≈ "बिके"), so the shopkeeper was told total business
     * when they asked about one item. Resolution reuses [findCatalogItem] so "aaloo" / "आलू" /
     * a mis-transcribed "alu" all land on the same row.
     *
     * @return (resolvedItemName, qtySold, revenue), or null when the name matches no catalog item.
     */
    suspend fun getItemSalesInPeriod(
        itemNameQuery: String,
        startMs: Long,
        endMs: Long
    ): Triple<String, Double, Double>? = withContext(Dispatchers.IO) {
        val matched = findCatalogItem(itemNameQuery) ?: return@withContext null
        val row = db.transactionDao().getItemSalesBetween(startMs, endMs)
            .firstOrNull { it.itemId == matched.id }
            ?: return@withContext Triple(matched.name, 0.0, 0.0)
        Triple(matched.name, row.qty, row.revenue)
    }

    suspend fun getTotalStockValue(): Double = withContext(Dispatchers.IO) {
        db.catalogDao().getTotalStockValueAtCost()
    }

    /**
     * What one customer still owes, via the single balance definition in [CustomerBalance].
     *
     * The old implementation looked up `customers` and then *fell back* to the legacy `credits`
     * table, so two screens could report two different numbers for the same person. That fallback
     * is gone.
     */
    suspend fun getCustomerBalance(customerNameQuery: String): Double? =
        getCustomerBalanceWithName(customerNameQuery)?.second

    /** Resolved customer plus balance, so the answer speaks the customer's real name rather
     *  than echoing back whatever was heard. Same margin guard as [findCatalogItem]. */
    suspend fun getCustomerBalanceWithName(customerNameQuery: String): Pair<String, Double>? =
        withContext(Dispatchers.IO) {
            val customers = db.customerDao().getActiveCustomersList()
            if (customers.isEmpty()) return@withContext null
            val queryKey = PhoneticKey.of(customerNameQuery)
            if (queryKey.length < MIN_QUERY_PHONES) return@withContext null
            val ranked = customers
                .map { it to PhoneticKey.normalizedDistance(queryKey, it.phoneticKey) }
                .sortedBy { it.second }
            val best = ranked.firstOrNull() ?: return@withContext null
            if (best.second > MAX_ENTITY_DISTANCE) return@withContext null
            val rival = ranked.firstOrNull { !it.first.name.equals(best.first.name, ignoreCase = true) }
            val margin = if (rival != null) rival.second - best.second else 1.0
            if (margin < MIN_ENTITY_MARGIN) return@withContext null
            best.first.name to CustomerBalance(db).balanceFor(best.first.id)
        }

    /** Everything owed to the shop right now. */
    suspend fun getTotalReceivables(): Double = withContext(Dispatchers.IO) {
        CustomerBalance(db).allBalances().values.sum()
    }

    suspend fun getTopSellingItem(startMs: Long, endMs: Long): String? = withContext(Dispatchers.IO) {
        db.transactionDao().getItemSalesBetween(startMs, endMs).firstOrNull()?.itemName
    }

    suspend fun getSlowestSellingItem(startMs: Long, endMs: Long): String? = withContext(Dispatchers.IO) {
        db.transactionDao().getItemSalesBetween(startMs, endMs).lastOrNull()?.itemName
    }

    /** Items at or below their low-stock threshold, worst first. */
    suspend fun getLowStockItems(): List<Pair<String, Double>> = withContext(Dispatchers.IO) {
        db.catalogDao().getActiveCatalogList()
            .filter { it.trackStock && it.lowStockThreshold != null && it.stockQty <= it.lowStockThreshold!! }
            .sortedBy { it.stockQty }
            .map { it.name to it.stockQty }
    }

    /** Value of stock written off as spoiled in a window. */
    suspend fun getWasteValue(startMs: Long, endMs: Long): Double = withContext(Dispatchers.IO) {
        db.stockLedgerDao().sumValueByReason(
            com.voicetoinvoice.app.data.local.entity.StockReason.WASTE.name, startMs, endMs
        )
    }

    private suspend fun findCatalogItem(query: String): com.voicetoinvoice.app.data.local.entity.CatalogItem? {
        val catalog = db.catalogDao().getActiveCatalogList()
        if (catalog.isEmpty()) return null
        catalog.find { it.name.equals(query, ignoreCase = true) }?.let { return it }
        if (query.length >= MIN_SUBSTRING_QUERY_LEN) {
            catalog.find { it.name.contains(query, ignoreCase = true) }?.let { return it }
        }
        val queryKey = PhoneticKey.of(query)
        if (queryKey.length < MIN_QUERY_PHONES) return null
        val ranked = catalog
            .map { it to PhoneticKey.normalizedDistance(queryKey, PhoneticKey.of(it.name)) }
            .sortedBy { it.second }
        val best = ranked.firstOrNull() ?: return null
        if (best.second > MAX_ENTITY_DISTANCE) return null
        val rival = ranked.firstOrNull { !it.first.name.equals(best.first.name, ignoreCase = true) }
        val margin = if (rival != null) rival.second - best.second else 1.0
        if (margin < MIN_ENTITY_MARGIN) return null
        return best.first
    }

    companion object {
        /** Nearest-neighbour ceiling. Unchanged — the ceiling was never the problem. */
        private const val MAX_ENTITY_DISTANCE = 0.34
        /** How far clear the winner must be. `पर सामान` matched Baingan at 0.3333 with Lahsun
         *  0.056 behind it, and `कुल` tied kela and Kheera at 0.25 — both junk, both under the
         *  ceiling. Measured against 7 genuine item questions the smallest true-positive margin
         *  is टमाटर→Tamatar at 0.143, so 0.08 clears every real match with room to spare. */
        private const val MIN_ENTITY_MARGIN = 0.08
        private const val MIN_QUERY_PHONES = 3
        private const val MIN_SUBSTRING_QUERY_LEN = 3
    }
}
