package com.voicetoinvoice.app.domain.query

import com.voicetoinvoice.app.data.local.AppDatabase

/**
 * Gross profit over a window, computed only from sales whose cost is actually known.
 *
 * ## Why coverage is part of the result, not a footnote
 *
 * `TransactionRecord.costAtSale` is null whenever the item's cost was unknown at sale time --
 * which is common by design, because a voice stock-in with no spoken price is committed with
 * `StockInRecord.costMissing = true`. Two wrong ways to handle that:
 *
 *  - Treat unknown cost as 0 -> fabricates 100% margin on those lines.
 *  - Apply today's cost to old sales -> nonsense for vegetables, where cost swings 40%/week.
 *
 * So uncosted lines are EXCLUDED from margin, and [ProfitResult.costCoveragePct] reports how
 * much of the period's revenue the profit figure actually speaks for. Callers must surface it.
 * A confidently-wrong profit number costs a shopkeeper's trust permanently, and unlike a
 * missing feature that is not recoverable.
 */
class ProfitCalculator(private val db: AppDatabase) {

    suspend fun compute(startMs: Long, endMs: Long): ProfitResult {
        val t = db.transactionDao().getPeriodTotals(startMs, endMs)

        val expenses = db.expenseDao().getPeriodTotal(startMs, endMs)

        val grossProfit = t.revenueWithCost - t.cogs
        // Margin's denominator is revenue WITH cost, never total revenue: mixing costed and
        // uncosted lines understates margin by exactly the uncosted share.
        val marginPct = if (t.revenueWithCost > 0.0) (grossProfit / t.revenueWithCost) * 100.0 else 0.0
        val coveragePct = if (t.revenue > 0.0) (t.revenueWithCost / t.revenue) * 100.0 else 0.0

        return ProfitResult(
            revenue = t.revenue,
            cogs = t.cogs,
            revenueWithCost = t.revenueWithCost,
            grossProfit = grossProfit,
            marginPct = marginPct,
            costCoveragePct = coveragePct,
            linesWithCost = (t.lineCount - t.linesWithoutCost).coerceAtLeast(0),
            linesWithoutCost = t.linesWithoutCost,
            expenses = expenses,
            hasExpenseData = expenses > 0.0
        )
    }
}

data class ProfitResult(
    val revenue: Double,
    val cogs: Double,
    val revenueWithCost: Double,
    val grossProfit: Double,
    val marginPct: Double,
    val costCoveragePct: Double,
    val linesWithCost: Int,
    val linesWithoutCost: Int,
    val expenses: Double = 0.0,
    val hasExpenseData: Boolean = false
) {
    /** Gross profit minus period expenses. Only meaningful when [hasExpenseData]. */
    val netProfit: Double get() = grossProfit - expenses

    /** True when the figure covers essentially all revenue and needs no caveat. */
    val isFullyCosted: Boolean get() = linesWithoutCost == 0 || costCoveragePct >= 99.5

    /** True when so little cost is known that quoting a profit would mislead. */
    val isTooSparseToReport: Boolean get() = costCoveragePct < 25.0

    companion object {
        val EMPTY = ProfitResult(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0)
    }
}
