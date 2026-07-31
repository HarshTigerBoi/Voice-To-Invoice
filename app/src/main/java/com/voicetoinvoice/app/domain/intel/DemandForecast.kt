package com.voicetoinvoice.app.domain.intel

import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.repository.DailyRollupRepository
import java.util.Calendar

enum class ForecastConfidence { LOW, MEDIUM, HIGH }

data class ForecastResult(
    val itemId: String,
    val itemName: String,
    val next7DayQty: Double,
    val confidence: ForecastConfidence,
    val historyDays: Int
)

/**
 * EWMA + day-of-week seasonality, explicitly NOT a trained model. See Docs/master_build_plan.md
 * §4.3: kirana demand is dominated by weekly rhythm, a ~30-line explainable model captures most
 * of that signal, runs instantly on-device, and -- critically -- can be explained to a
 * shopkeeper and debugged when it's wrong. An opaque forecast that says "expect to sell 60kg"
 * with no reason a shopkeeper can sanity-check will not be trusted, matching the same principle
 * `ReorderAdvisor` is built on.
 *
 * Confidence is reported and MEANT to gate display: below [MIN_HISTORY_DAYS] a forecast is
 * hidden entirely rather than shown with false confidence, because a new shop with 10 days of
 * data showing a number that LOOKS as certain as one with 90 days would be actively misleading.
 */
class DemandForecast(private val db: AppDatabase) {

    private val EWMA_ALPHA = 0.3
    private val SEASONALITY_WINDOW_DAYS = 56 // 8 weeks
    private val MIN_HISTORY_DAYS = 14
    private val MEDIUM_HISTORY_DAYS = 56

    /** Shop-wide day-of-week factor: how much busier/quieter each weekday is than the shop's own
     *  average, from the last 8 weeks of REVENUE (a stabler signal shop-wide than any single
     *  item's own quantity, which can be noisy at the per-item level). Applied uniformly to
     *  every item's own baseline below. */
    private suspend fun dayOfWeekFactors(nowMs: Long): Map<Int, Double> {
        val start = nowMs - SEASONALITY_WINDOW_DAYS * DAY_MS
        val series = db.dailyRollupDao().getDailyRevenueSeries(start, nowMs)
        if (series.isEmpty()) return emptyMap()

        val byDow = series.groupBy { weekdayOf(it.dayStartMs) }
        val overallMean = series.map { it.revenue }.average()
        if (overallMean <= 0.0) return emptyMap()

        return byDow.mapValues { (_, days) -> days.map { it.revenue }.average() / overallMean }
    }

    private fun weekdayOf(ts: Long): Int = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.DAY_OF_WEEK)

    suspend fun forecastTopItems(nowMs: Long = System.currentTimeMillis(), limit: Int = 20): List<ForecastResult> {
        val dowFactors = dayOfWeekFactors(nowMs)
        val topItems = db.transactionDao()
            .getItemSalesBetween(nowMs - SEASONALITY_WINDOW_DAYS * DAY_MS, nowMs)
            .sortedByDescending { it.qty }
            .take(limit)

        val results = mutableListOf<ForecastResult>()
        for (item in topItems) {
            val earliestDay = db.dailyRollupDao().getEarliestDayForItem(item.itemId) ?: continue
            val historyDays = ((nowMs - earliestDay) / DAY_MS).toInt().coerceAtLeast(0)
            if (historyDays < MIN_HISTORY_DAYS) continue // hidden, not shown with false confidence

            val series = db.dailyRollupDao().getItemDailyQtySeries(item.itemId, earliestDay, nowMs)
            val baseline = ewma(series.map { it.qty }, EWMA_ALPHA)
            if (baseline <= 0.0) continue

            // Next 7 days, each weighted by its own weekday's seasonality factor (defaulting to
            // 1.0 -- no adjustment -- when there isn't enough shop-wide history to derive one).
            var next7 = 0.0
            for (dayOffset in 1..7) {
                val futureDay = nowMs + dayOffset * DAY_MS
                val factor = dowFactors[weekdayOf(futureDay)] ?: 1.0
                next7 += baseline * factor
            }

            val confidence = when {
                historyDays >= MEDIUM_HISTORY_DAYS -> ForecastConfidence.HIGH
                historyDays >= MIN_HISTORY_DAYS -> ForecastConfidence.MEDIUM
                else -> ForecastConfidence.LOW
            }

            results += ForecastResult(item.itemId, item.itemName, next7, confidence, historyDays)
        }
        return results.sortedByDescending { it.next7DayQty }
    }

    /** Exponentially weighted moving average over a chronological series, treating gaps
     *  (no rollup row that day) as zero sold rather than skipping them -- a day with no sale
     *  IS a data point, not a missing one. */
    private fun ewma(values: List<Double>, alpha: Double): Double {
        if (values.isEmpty()) return 0.0
        var current = values.first()
        for (v in values.drop(1)) current = alpha * v + (1 - alpha) * current
        return current
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
