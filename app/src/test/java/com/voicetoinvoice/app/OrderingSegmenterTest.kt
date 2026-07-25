package com.voicetoinvoice.app

import com.voicetoinvoice.app.domain.parser.OrderingSegmenter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OrderingSegmenterTest {

    private lateinit var segmenter: OrderingSegmenter

    @Before
    fun setUp() {
        segmenter = OrderingSegmenter()
    }

    @Test
    fun testQuantityPrecedesItem() {
        val result = segmenter.segmentTranscript("2 किलो सेब")
        assertEquals(1, result.segments.size)

        val seg = result.segments.first()
        assertEquals(2.0, seg.quantity, 0.01)
        assertEquals("KG", seg.unit)
        assertTrue(seg.itemTokens.contains("सेब"))
        assertFalse(seg.isSanityFlagged)
    }

    @Test
    fun testQuantityAfterItemBelongsToNextItem() {
        val result = segmenter.segmentTranscript("सेब 3 किलो आलू")
        assertEquals(2, result.segments.size)

        val item1 = result.segments[0]
        assertEquals(1.0, item1.quantity, 0.01) // Defaulted to 1.0
        assertTrue(item1.itemTokens.contains("सेब"))

        val item2 = result.segments[1]
        assertEquals(3.0, item2.quantity, 0.01) // Belongs to Aaloo
        assertEquals("KG", item2.unit)
        assertTrue(item2.itemTokens.contains("आलू"))
    }

    @Test
    fun testIndicFractionQuantity() {
        val result = segmenter.segmentTranscript("आधा किलो टमाटर")
        assertEquals(1, result.segments.size)

        val seg = result.segments.first()
        assertEquals(0.5, seg.quantity, 0.01)
        assertEquals("KG", seg.unit)
        assertTrue(seg.itemTokens.contains("टमाटर"))
    }

    @Test
    fun testMultiItemSequence() {
        val result = segmenter.segmentTranscript("2 किलो सेब 3 किलो आलू 1 दर्जन केला")
        assertEquals(3, result.segments.size)

        assertEquals(2.0, result.segments[0].quantity, 0.01)
        assertEquals("KG", result.segments[0].unit)

        assertEquals(3.0, result.segments[1].quantity, 0.01)
        assertEquals("KG", result.segments[1].unit)

        assertEquals(1.0, result.segments[2].quantity, 0.01)
        assertEquals("DOZEN", result.segments[2].unit)
    }

    @Test
    fun testDoubleQuantityNoItemFlagsSanity() {
        val result = segmenter.segmentTranscript("2 किलो 3 किलो सेब")
        assertEquals(1, result.segments.size)
        assertTrue(result.segments.first().isSanityFlagged)
    }

    @Test
    fun testClipEndsOnQuantitySetsCarryover() {
        val result = segmenter.segmentTranscript("3 kilo")
        assertEquals(0, result.segments.size)
        assertEquals(3.0, result.carryoverQty!!, 0.01)
    }
}
