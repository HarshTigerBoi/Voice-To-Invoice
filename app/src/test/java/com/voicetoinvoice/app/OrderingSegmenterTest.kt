package com.voicetoinvoice.app

import com.voicetoinvoice.app.domain.parser.OrderingSegmenter
import com.voicetoinvoice.app.domain.parser.ResolutionKind
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
        assertEquals(ResolutionKind.AMBIGUOUS, seg.resolutionKind)
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

    // Regression for the "ek kilo chaandi" misparse: STT clipped "किलो" down to
    // "लो" (trace jobId 24fb3b5b-ed17-43b9-bea2-f0df1137e17f), and the old
    // per-token classifier matched the orphaned "लो" against the number "दो"
    // (edit distance 1) instead of recognizing it as a truncated unit, turning
    // "1 KG Chaandi" into a phantom "2 PACKET Chaandi". The grammar-aware
    // lattice decoder should prefer the elided-unit reading because
    // NUM -> UNIT -> ITEM is far cheaper under the shopkeeper grammar than
    // NUM -> NUM -> ITEM.
    @Test
    fun testElidedKiloUnitRecoversQuantityAndUnit() {
        val result = segmenter.segmentTranscript("एक लो चांदी")
        assertEquals(1, result.segments.size)

        val seg = result.segments.first()
        assertEquals(1.0, seg.quantity, 0.01)
        assertEquals("KG", seg.unit)
        assertTrue(seg.itemTokens.contains("चांदी"))
        assertFalse(seg.isSanityFlagged)
    }

    // Same elision pattern but mid-utterance, ahead of a second item, to make
    // sure the fix doesn't just work when the ambiguous token happens to be
    // last in the sequence.
    @Test
    fun testElidedUnitMidUtteranceBeforeSecondItem() {
        val result = segmenter.segmentTranscript("दो लो आलू एक किलो प्याज")
        assertEquals(2, result.segments.size)

        val item1 = result.segments[0]
        assertEquals(2.0, item1.quantity, 0.01)
        assertEquals("KG", item1.unit)
        assertTrue(item1.itemTokens.contains("आलू"))

        val item2 = result.segments[1]
        assertEquals(1.0, item2.quantity, 0.01)
        assertEquals("KG", item2.unit)
        assertTrue(item2.itemTokens.contains("प्याज"))
    }
}
