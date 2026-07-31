package com.voicetoinvoice.app.domain.query

import com.voicetoinvoice.app.data.local.AppDatabase

data class HealthComponent(
    val name: String,
    /** 0..100. */
    val scoreOutOf100: Int,
    val weight: Int,
    val explanation: String
)

data class HealthScoreResult(
    val score: Int,
    val components: List<HealthComponent>
) {
    /** The two weakest components -- what the shopkeeper should actually act on. Docs/master
     *  _build_plan.md §3.5: a bare number is either ignored or insulting; the actions are the
     *  point. */
    val weakestTwo: List<HealthComponent> get() = components.sortedBy { it.scoreOutOf100 }.take(2)
}

/**
 * A 0-100 business health score built from 5 transparent, always-shown components -- never a
 * bare number. See Docs/master_build_plan.md §3.5.
 *
 * ## One deliberate simplification from the original design
 *
 * The margin component was specified as "current margin vs the shop's own 90-day MEDIAN" (a
 * distribution over many rolling samples). This computes it as current-30d margin vs a single
 * aggregate over the trailing 90 days instead -- still the shop's OWN history, never an industry
 * benchmark, just a simpler statistic than a median-of-many-windows. The intent (don't judge a
 * shop against anyone but itself) is preserved; the precision of "median" is not.
 */
class HealthScore(private val db: AppDatabase) {

    suspend fun compute(nowMs: Long = System.currentTimeMillis()): HealthScoreResult {
        val dayMs = 24 * 60 * 60 * 1000L
        val profitCalc = ProfitCalculator(db)

        // 1. Sales trend: last 7 days vs the 7 days before that.
        val last7 = profitCalc.compute(nowMs - 7 * dayMs, nowMs)
        val prior7 = profitCalc.compute(nowMs - 14 * dayMs, nowMs - 7 * dayMs)
        val trendScore = if (prior7.revenue > 0.0) {
            val growthPct = (last7.revenue - prior7.revenue) / prior7.revenue
            // +50% growth saturates at 100, 0% sits at neutral 50, -50% saturates at 0.
            clamp01to100(50.0 + growthPct * 100.0)
        } else if (last7.revenue > 0.0) 65 else 50 // some sales with no prior baseline reads as mildly positive
        val trend = HealthComponent(
            "बिक्री का रुझान", trendScore, WEIGHT_TREND,
            if (prior7.revenue > 0.0) {
                val pct = ((last7.revenue - prior7.revenue) / prior7.revenue * 100).toInt()
                if (pct >= 0) "पिछले हफ़्ते से बिक्री ${pct}% ऊपर" else "पिछले हफ़्ते से बिक्री ${-pct}% नीचे"
            } else "तुलना के लिए पुराना डेटा नहीं है"
        )

        // 2. Margin vs the shop's OWN trailing 90 days (never an industry benchmark).
        val last30 = profitCalc.compute(nowMs - 30 * dayMs, nowMs)
        val trailing90 = profitCalc.compute(nowMs - 90 * dayMs, nowMs)
        val marginScore = if (!trailing90.isTooSparseToReport && !last30.isTooSparseToReport) {
            clamp01to100(50.0 + (last30.marginPct - trailing90.marginPct) * 2.0)
        } else 50
        val margin = HealthComponent(
            "मार्जिन", marginScore, WEIGHT_MARGIN,
            if (!trailing90.isTooSparseToReport && !last30.isTooSparseToReport) {
                "इस महीने मार्जिन ${last30.marginPct.toInt()}%, औसतन ${trailing90.marginPct.toInt()}%"
            } else "मार्जिन आँकने के लिए पर्याप्त खरीद-भाव दर्ज नहीं"
        )

        // 3. Udhaar health: share of receivables that is NOT overdue.
        val aging = CustomerBalance(db).aging(nowMs)
        val udhaarScore = if (aging.total > 0.0) clamp01to100((1.0 - aging.overdueRatio) * 100.0) else 100
        val udhaar = HealthComponent(
            "उधार की सेहत", udhaarScore, WEIGHT_UDHAAR,
            if (aging.overdue > 0.0) "₹${aging.overdue.toInt()} का उधार 1 महीने से पुराना है"
            else if (aging.total > 0.0) "सारा उधार हाल का है, कोई पुराना बकाया नहीं"
            else "कोई उधार बाकी नहीं"
        )

        // 4. Stock efficiency: how much of the shelf is dead weight.
        val totalStockValue = db.catalogDao().getTotalStockValueAtCost()
        val deadStock = db.catalogDao().getDeadStock(nowMs - 30 * dayMs)
        val deadStockValue = deadStock.sumOf { it.stockQty * (it.avgCostPrice ?: 0.0) }
        val stockScore = if (totalStockValue > 0.0) {
            clamp01to100((1.0 - (deadStockValue / totalStockValue)) * 100.0)
        } else 100
        val stock = HealthComponent(
            "स्टॉक दक्षता", stockScore, WEIGHT_STOCK,
            if (deadStock.isNotEmpty()) "${deadStock.size} आइटम 30 दिन से नहीं बिके (₹${deadStockValue.toInt()})"
            else "सारा स्टॉक चल रहा है"
        )

        // 5. Data completeness: how much of revenue has a known cost, discounted by how much of
        // the review queue is still unresolved. Deliberately visible: it nudges the shopkeeper
        // toward backfilling costs, which improves every other money number in the app.
        val periodTotals = profitCalc.compute(nowMs - 30 * dayMs, nowMs)
        val pendingCount = db.unmatchedQueueDao().getPendingItemsList().size
        val recentJobCount = db.sttJobDao().getAllJobsTraceLogsList()
            .count { it.recordedAtMs >= nowMs - 30 * dayMs }
        val unresolvedRatio = if (recentJobCount > 0) (pendingCount.toDouble() / recentJobCount).coerceIn(0.0, 1.0) else 0.0
        val completenessScore = clamp01to100(periodTotals.costCoveragePct * (1.0 - unresolvedRatio))
        val completeness = HealthComponent(
            "डेटा पूर्णता", completenessScore, WEIGHT_COMPLETENESS,
            "बिक्री का ${periodTotals.costCoveragePct.toInt()}% हिसाब पूरा है" +
                if (pendingCount > 0) ", $pendingCount समीक्षा बाकी" else ""
        )

        val components = listOf(trend, margin, udhaar, stock, completeness)
        val weighted = components.sumOf { it.scoreOutOf100 * it.weight } / 100
        return HealthScoreResult(weighted, components)
    }

    private fun clamp01to100(v: Double): Int = v.coerceIn(0.0, 100.0).toInt()

    companion object {
        const val WEIGHT_TREND = 25
        const val WEIGHT_MARGIN = 20
        const val WEIGHT_UDHAAR = 20
        const val WEIGHT_STOCK = 20
        const val WEIGHT_COMPLETENESS = 15
    }
}
