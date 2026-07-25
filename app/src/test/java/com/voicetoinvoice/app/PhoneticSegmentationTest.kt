package com.voicetoinvoice.app

import com.voicetoinvoice.app.domain.parser.OrderingSegmenter
import com.voicetoinvoice.app.domain.parser.PhoneticKey
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Regression suite for ISSUE-020: cross-script / fused-token voice parsing.
 *
 * The failing production trace (jobId 2966f386-7211-407a-810c-169042b2ecfc) had a
 * shopkeeper say "तीन किलो सेब" and STT return the Malay-looking "tinggal sebab" —
 * correct phonetics decoded into the wrong lexicon and the wrong script. Everything
 * downstream compared Latin text against Devanagari vocabulary with orthographic edit
 * distance, so quantity, unit, and item were all lost at once and the sale booked as
 * "1 PACKET tinggal sebab".
 */
class PhoneticSegmentationTest {

    private lateinit var segmenter: OrderingSegmenter

    @Before
    fun setUp() {
        segmenter = OrderingSegmenter()
    }

    // ---------- PhoneticKey: the two scripts must converge ----------

    @Test
    fun devanagariAndRomanizedFormsShareAKey() {
        assertEquals(PhoneticKey.of("तीन"), PhoneticKey.of("teen"))
        assertEquals(PhoneticKey.of("किलो"), PhoneticKey.of("kilo"))
        assertEquals(PhoneticKey.of("सेब"), PhoneticKey.of("seb"))
        assertEquals(PhoneticKey.of("आलू"), PhoneticKey.of("aaloo"))
    }

    @Test
    fun aspiratedAndUnaspiratedConsonantsCollapse() {
        // The single most common Hindi STT error class (ISSUE-011: बिंडी -> भिंडी).
        assertEquals(PhoneticKey.of("भिंडी"), PhoneticKey.of("बिंडी"))
        assertEquals(PhoneticKey.of("bhindi"), PhoneticKey.of("bindi"))
    }

    @Test
    fun vowelSubstitutionsCostLessThanConsonantSubstitutions() {
        val vowelSwap = PhoneticKey.distance("KILO", "KALO")
        val consonantSwap = PhoneticKey.distance("KILO", "KISO")
        assertTrue(
            "vowel edits ($vowelSwap) must be cheaper than consonant edits ($consonantSwap)",
            vowelSwap < consonantSwap
        )
    }

    @Test
    fun unrelatedWordsDoNotCollide() {
        assertNotEquals(PhoneticKey.of("आलू"), PhoneticKey.of("सेब"))
        assertNotEquals(PhoneticKey.of("पनीर"), PhoneticKey.of("चीनी"))
    }

    // ---------- The actual production failure ----------

    @Test
    fun malayMisTranscriptionOfTeenKiloSebRecoversFully() {
        val result = segmenter.segmentTranscript("tinggal sebab")
        assertEquals(1, result.segments.size)

        val seg = result.segments.first()
        assertEquals(3.0, seg.quantity, 0.01)
        assertEquals("KG", seg.unit)
        assertTrue(
            "expected the item to resolve to Seb, got ${seg.itemTokens}",
            seg.itemTokens.any { PhoneticKey.of(it) == PhoneticKey.of("सेब") }
        )
    }

    @Test
    fun cleanRomanizedTranscriptStillParses() {
        val result = segmenter.segmentTranscript("teen kilo seb")
        assertEquals(1, result.segments.size)
        assertEquals(3.0, result.segments[0].quantity, 0.01)
        assertEquals("KG", result.segments[0].unit)
    }

    @Test
    fun cleanDevanagariTranscriptStillParses() {
        val result = segmenter.segmentTranscript("तीन किलो सेब")
        assertEquals(1, result.segments.size)
        assertEquals(3.0, result.segments[0].quantity, 0.01)
        assertEquals("KG", result.segments[0].unit)
        assertTrue(result.segments[0].itemTokens.contains("सेब"))
    }

    // ---------- Fused tokens ----------

    @Test
    fun fusedNumberAndUnitSplitsCorrectly() {
        // "चार किलो आलू" spoken fast; STT welds the first two words.
        val result = segmenter.segmentTranscript("चरगलो आलू")
        assertEquals(1, result.segments.size)
        assertEquals(4.0, result.segments[0].quantity, 0.01)
        assertEquals("KG", result.segments[0].unit)
        assertTrue(result.segments[0].itemTokens.any { PhoneticKey.of(it) == PhoneticKey.of("आलू") })
    }

    @Test
    fun fusedUnitAndItemSplitsCorrectly() {
        val result = segmenter.segmentTranscript("एक ग्लोसोना")
        assertEquals(1, result.segments.size)
        assertEquals(1.0, result.segments[0].quantity, 0.01)
        assertEquals("KG", result.segments[0].unit)
        assertTrue(result.segments[0].itemTokens.any { PhoneticKey.of(it) == PhoneticKey.of("सोना") })
    }

    /**
     * "एकलो" is genuinely ambiguous in isolation — it reads equally well as "ek kilo"
     * and "ek aaloo". Only the following token settles it, which is exactly why splits
     * are arbitrated inside the Viterbi lattice instead of by a greedy pre-pass.
     */
    @Test
    fun ambiguousFusedTokenIsResolvedByFollowingContext() {
        val withItem = segmenter.segmentTranscript("एकलो सेब")
        assertEquals(1, withItem.segments.size)
        assertEquals(1.0, withItem.segments[0].quantity, 0.01)
        assertEquals(
            "a following item should force the [NUM][UNIT] reading of एकलो",
            "KG", withItem.segments[0].unit
        )
    }

    @Test
    fun unknownWordIsNotHallucinatedIntoAKnownItem() {
        // A word that matches nothing must survive as an item name rather than being
        // rewritten into the nearest catalog entry.
        val result = segmenter.segmentTranscript("दो किलो zxqwvr")
        assertEquals(1, result.segments.size)
        assertEquals(2.0, result.segments[0].quantity, 0.01)
        assertEquals("KG", result.segments[0].unit)
        assertTrue(result.segments[0].itemTokens.contains("zxqwvr"))
    }

    @Test
    fun shopCatalogNamesAreUsableAsSplitTargets() {
        val result = segmenter.segmentTranscript(
            "do kilo dragonfruit",
            catalogNames = listOf("Dragon Fruit", "Broccoli")
        )
        assertEquals(1, result.segments.size)
        assertEquals(2.0, result.segments[0].quantity, 0.01)
        assertEquals("KG", result.segments[0].unit)
    }
}
