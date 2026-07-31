package com.voicetoinvoice.app.domain.query

import com.voicetoinvoice.app.data.local.AppDatabase

/**
 * The single definition of what a customer owes.
 *
 *     balance = Σ(credit sales) − Σ(returns against credit) − Σ(payments)
 *
 * Returns are already stored as negative-`total` rows (`TxnType.RETURN`), so summing `total` over
 * the customer's CREDIT transactions handles them without a special case.
 *
 * ## Why this type exists
 *
 * There were two competing credit ledgers: the legacy `credits` table and
 * `CustomerDao.getOutstandingFor`. `LedgerQueries.getCustomerBalance` tried the new one and then
 * *fell back* to the legacy one, so the same customer could show a different balance on two
 * screens depending on which path ran. Two sources of truth for money owed means the shopkeeper
 * trusts neither. Every balance read now goes through here.
 */
class CustomerBalance(private val db: AppDatabase) {

    suspend fun balanceFor(customerId: String): Double {
        val creditSales = db.transactionDao().sumCreditTotalForCustomer(customerId)
        val paid = db.customerPaymentDao().totalPaidBy(customerId)
        return (creditSales - paid).coerceAtLeast(0.0)
    }

    /** Bulk variant for the customer list, so it does not run one query per row. */
    suspend fun allBalances(): Map<String, Double> {
        val credits = db.transactionDao().sumCreditTotalsGroupedByCustomer()
            .associate { it.customerId to it.total }
        val result = mutableMapOf<String, Double>()
        for (customer in db.customerDao().getActiveCustomersList()) {
            val owed = credits[customer.id] ?: 0.0
            val paid = db.customerPaymentDao().totalPaidBy(customer.id)
            result[customer.id] = (owed - paid).coerceAtLeast(0.0)
        }
        return result
    }

    /**
     * Total receivables split by how old the oldest unpaid credit sale is.
     *
     * Feeds the Udhaar-health component of the business health score and the overdue reminder,
     * because "₹4,200 outstanding" is far less actionable than "₹4,200, and ₹3,000 of it is over a
     * month old".
     */
    suspend fun aging(nowMs: Long = System.currentTimeMillis()): ReceivablesAging {
        var fresh = 0.0
        var mid = 0.0
        var old = 0.0
        val balances = allBalances()

        for ((customerId, balance) in balances) {
            if (balance <= 0.0) continue
            val oldestMs = db.transactionDao().oldestCreditSaleMsForCustomer(customerId) ?: nowMs
            val ageDays = ((nowMs - oldestMs) / DAY_MS).toInt()
            when {
                ageDays <= 7 -> fresh += balance
                ageDays <= 30 -> mid += balance
                else -> old += balance
            }
        }
        return ReceivablesAging(fresh, mid, old)
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

data class ReceivablesAging(
    /** 0–7 days. */
    val fresh: Double,
    /** 8–30 days. */
    val mid: Double,
    /** 31+ days -- the part that needs chasing. */
    val overdue: Double
) {
    val total: Double get() = fresh + mid + overdue

    /** Share of receivables that is over a month old, 0..1. Lower is healthier. */
    val overdueRatio: Double get() = if (total > 0.0) overdue / total else 0.0
}
