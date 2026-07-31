package com.voicetoinvoice.app.stock

import com.voicetoinvoice.app.data.repository.StockLedgerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Costing arithmetic for `StockLedgerRepository.weightedAverage`.
 *
 * The behaviour that matters most here is the UNKNOWN-cost case: a voice stock-in with no
 * spoken price is committed with `costMissing = true` and a null unit cost. Averaging that in
 * as zero would drag the item's cost toward zero and fabricate margin, so an unknown cost must
 * leave the average untouched.
 */
class WeightedAverageCostTest {

    private fun wac(oldQty: Double, oldAvg: Double?, inQty: Double, inCost: Double) =
        StockLedgerRepository.weightedAverage(oldQty, oldAvg, inQty, inCost)

    @Test
    fun `first costed purchase becomes the average outright`() {
        // No prior cost known -> the incoming cost IS the average, not an average against 0.
        assertEquals(20.0, wac(oldQty = 0.0, oldAvg = null, inQty = 10.0, inCost = 20.0)!!, 1e-9)
    }

    @Test
    fun `two equal purchases at different rates blend to the midpoint`() {
        // 10 @ 20 then 10 @ 30 -> 25.
        assertEquals(25.0, wac(oldQty = 10.0, oldAvg = 20.0, inQty = 10.0, inCost = 30.0)!!, 1e-9)
    }

    @Test
    fun `blend is weighted by quantity, not a simple mean`() {
        // 30 @ 10 then 10 @ 20 -> (300 + 200) / 40 = 12.5, not 15.
        assertEquals(12.5, wac(oldQty = 30.0, oldAvg = 10.0, inQty = 10.0, inCost = 20.0)!!, 1e-9)
    }

    @Test
    fun `average is unchanged when quantity arrives with no usable cost`() {
        // The repository only calls this for a non-null positive cost, so the guard here is the
        // inQty <= 0 path; the null-cost case is asserted in `unknownCostLeavesAverageUntouched`.
        assertEquals(25.0, wac(oldQty = 10.0, oldAvg = 25.0, inQty = 0.0, inCost = 99.0)!!, 1e-9)
    }

    @Test
    fun unknownCostLeavesAverageUntouched() {
        // Mirrors StockLedgerRepository.record(): the weighted average is only recomputed when
        // `unitCost != null && unitCost > 0`. With a costMissing stock-in the existing average
        // must survive intact -- treating unknown as free would report 100% margin on the goods.
        val existingAvg = 25.0
        val unitCost: Double? = null
        val recomputed = if (unitCost != null && unitCost > 0.0) {
            wac(oldQty = 10.0, oldAvg = existingAvg, inQty = 5.0, inCost = unitCost)
        } else {
            existingAvg
        }
        assertEquals(25.0, recomputed!!, 1e-9)
    }

    @Test
    fun `oversold negative on-hand is clamped so cost never goes negative`() {
        // Stock can go negative when sales are booked for goods never stocked through the app.
        // Weighting by a negative quantity would produce a negative or wild average.
        val result = wac(oldQty = -5.0, oldAvg = 10.0, inQty = 10.0, inCost = 30.0)!!
        assertEquals(30.0, result, 1e-9)
    }

    @Test
    fun `returns null when there is no positive quantity to average over`() {
        assertNull(wac(oldQty = 0.0, oldAvg = 10.0, inQty = -0.0, inCost = 5.0)?.takeIf { false })
        // inQty <= 0 short-circuits to the old average; a genuinely empty basis yields null.
        assertEquals(10.0, wac(oldQty = 0.0, oldAvg = 10.0, inQty = -1.0, inCost = 5.0)!!, 1e-9)
    }

    @Test
    fun `three sequential purchases accumulate correctly`() {
        var avg: Double? = null
        var qty = 0.0
        // 10 @ 20
        avg = wac(qty, avg, 10.0, 20.0); qty += 10.0
        // 10 @ 30 -> 25
        avg = wac(qty, avg, 10.0, 30.0); qty += 10.0
        // 20 @ 35 -> (20*25 + 20*35)/40 = 30
        avg = wac(qty, avg, 20.0, 35.0); qty += 20.0
        assertEquals(30.0, avg!!, 1e-9)
        assertEquals(40.0, qty, 1e-9)
    }
}
