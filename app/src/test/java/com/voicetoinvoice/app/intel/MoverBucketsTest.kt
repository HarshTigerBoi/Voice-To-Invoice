package com.voicetoinvoice.app.intel

import com.voicetoinvoice.app.data.local.dao.RollupItemTotal
import com.voicetoinvoice.app.domain.intel.MoverBucket
import com.voicetoinvoice.app.domain.intel.MoverBuckets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoverBucketsTest {

    private fun total(id: String, qty: Double) = RollupItemTotal(
        itemId = id,
        itemName = id,
        qtySold = qty,
        revenue = qty * 10,
        cogs = 0.0,
        costKnownRevenue = 0.0,
        txnCount = 1,
        wasteQty = 0.0,
        wasteValue = 0.0,
        returnQty = 0.0,
        returnValue = 0.0,
        stockInQty = 0.0,
        stockInValue = 0.0
    )

    @Test
    fun tenItemsSplitTwoFastThreeSlow() {
        val items = (10 downTo 1).map { total("item_$it", it.toDouble()) }
        val classified = MoverBuckets.classify(items)

        val fast = classified.filter { it.bucket == MoverBucket.FAST }
        val steady = classified.filter { it.bucket == MoverBucket.STEADY }
        val slow = classified.filter { it.bucket == MoverBucket.SLOW }

        assertEquals(2, fast.size)
        assertEquals(5, steady.size)
        assertEquals(3, slow.size)
    }

    @Test
    fun zeroQuantityItemsAreDeadNotSlow() {
        val soldItems = listOf(total("A", 10.0), total("B", 5.0))
        val deadItems = listOf(total("C", 0.0), total("D", 0.0))
        val classified = MoverBuckets.classify(soldItems + deadItems)

        val dead = classified.filter { it.bucket == MoverBucket.DEAD }
        assertEquals(2, dead.size)
        assertTrue(dead.all { it.qtySold == 0.0 })

        // Sold items ranking is unaffected by dead items
        val soldClassified = MoverBuckets.classify(soldItems)
        val classifiedSoldOnly = classified.filter { it.bucket != MoverBucket.DEAD }
        assertEquals(soldClassified.map { it.bucket }, classifiedSoldOnly.map { it.bucket })
    }

    @Test
    fun singleItemIsFast() {
        val items = listOf(total("A", 5.0))
        val classified = MoverBuckets.classify(items)

        assertEquals(1, classified.size)
        assertEquals(MoverBucket.FAST, classified[0].bucket)
    }

    @Test
    fun emptyListReturnsEmpty() {
        val classified = MoverBuckets.classify(emptyList())
        assertTrue(classified.isEmpty())
    }

    @Test
    fun allSameQuantityStillPartitions() {
        val items = (1..10).map { total("item_$it", 5.0) }
        val classified = MoverBuckets.classify(items)

        assertEquals(10, classified.size)
        val fast = classified.count { it.bucket == MoverBucket.FAST }
        val steady = classified.count { it.bucket == MoverBucket.STEADY }
        val slow = classified.count { it.bucket == MoverBucket.SLOW }
        assertEquals(10, fast + steady + slow)
    }
}
