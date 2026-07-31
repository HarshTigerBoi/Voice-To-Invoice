package com.voicetoinvoice.app.domain.intel

import com.voicetoinvoice.app.data.local.dao.RollupItemTotal

enum class MoverBucket { FAST, STEADY, SLOW, DEAD }

data class MoverLine(
    val itemId: String,
    val itemName: String,
    val qtySold: Double,
    val revenue: Double,
    val bucket: MoverBucket
)

/**
 * Buckets items by how fast they move, using RANK within the shop's own range rather than absolute
 * thresholds -- a vegetable stall's "fast" is a different number from a kirana's, and any absolute
 * cutoff would be wrong for one of them. Percentile boundaries match Docs/remaining_work_plan.md
 * §2.1: top 20% FAST, bottom 30% SLOW, DEAD is a separate state (never sold at all in the window),
 * not the tail of SLOW -- "sold twice" and "never sold" call for different actions.
 */
object MoverBuckets {

    const val FAST_PERCENTILE = 0.20
    const val SLOW_PERCENTILE = 0.30

    fun classify(items: List<RollupItemTotal>): List<MoverLine> {
        val sold = items.filter { it.qtySold > 0.0 }.sortedByDescending { it.qtySold }
        val dead = items.filter { it.qtySold <= 0.0 }

        val fastCount = Math.ceil(sold.size * FAST_PERCENTILE).toInt()
        val slowCount = Math.ceil(sold.size * SLOW_PERCENTILE).toInt()
        val steadyEnd = (sold.size - slowCount).coerceAtLeast(fastCount)

        val result = sold.mapIndexed { idx, item ->
            val bucket = when {
                idx < fastCount -> MoverBucket.FAST
                idx < steadyEnd -> MoverBucket.STEADY
                else -> MoverBucket.SLOW
            }
            MoverLine(item.itemId, item.itemName, item.qtySold, item.revenue, bucket)
        }
        return result + dead.map { MoverLine(it.itemId, it.itemName, 0.0, it.revenue, MoverBucket.DEAD) }
    }
}
