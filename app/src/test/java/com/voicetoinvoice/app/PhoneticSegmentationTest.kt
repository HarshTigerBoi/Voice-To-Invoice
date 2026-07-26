package com.voicetoinvoice.app

import com.voicetoinvoice.app.domain.parser.OrderingSegmenter
import com.voicetoinvoice.app.domain.parser.PhoneticKey
import com.voicetoinvoice.app.domain.parser.SalePlausibility
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

    // ---------- ISSUE-021: distance words must never eat the item ----------
    //
    // Production trace 9fc1fc32-7685-4503-9330-1363a16ec544: the shopkeeper said
    // "पांच किलो मैगी" and STT returned "पांच किलोमीटर". Because "किलोमीटर" was listed in
    // UNIT_SET it matched exactly, which suppressed split expansions entirely, so the
    // decode was NUM(5) + UNIT(KG) with no ITEM at all — closeSegment() never fired and
    // the whole sale came back as `segments: []`.

    @Test
    fun kilometerIsNotAShopUnit() {
        assertFalse(
            "a distance word in UNIT_SET exact-matches and suppresses splitting, " +
                "which is what swallowed the item in ISSUE-021",
            OrderingSegmenter.UNIT_SET.contains("kilometer")
        )
        assertFalse(OrderingSegmenter.UNIT_SET.contains("किलोमीटर"))
    }

    @Test
    fun kilometerMisTranscriptionStillYieldsASegment() {
        // The item name is genuinely unrecoverable here — मीटर and मैगी differ by a
        // consonant, and the information was destroyed at the audio->text boundary. What
        // must NOT happen is the utterance evaporating: quantity and unit are certain and
        // have to survive so the shopkeeper gets a one-tap review row instead of silence.
        val result = segmenter.segmentTranscript("पांच किलोमीटर")
        assertEquals(
            "quantity+unit with no item must still produce a reviewable segment",
            1, result.segments.size
        )
        assertEquals(5.0, result.segments[0].quantity, 0.01)
        assertEquals("KG", result.segments[0].unit)
    }

    @Test
    fun kilometerDerivedSegmentIsFlaggedForReview() {
        // The recovered item name is a guess resting on a token STT is known to have
        // mangled, so it must never ride the auto-confirm path.
        val result = segmenter.segmentTranscript("पांच किलोमीटर")
        assertTrue(
            "a segment recovered from a distance-word mis-decode must be sanity-flagged",
            result.segments[0].isSanityFlagged
        )
    }

    @Test
    fun romanizedKilometerBehavesTheSameWay() {
        val result = segmenter.segmentTranscript("paanch kilometer")
        assertEquals(1, result.segments.size)
        assertEquals(5.0, result.segments[0].quantity, 0.01)
        assertEquals("KG", result.segments[0].unit)
        assertTrue(result.segments[0].isSanityFlagged)
    }

    @Test
    fun realItemAfterAKilometerMisdecodeIsStillRead() {
        // "पांच किलो आलू" mis-heard with the unit fused: the item is present and must win.
        val result = segmenter.segmentTranscript("पांच किलोमीटर आलू")
        assertEquals(1, result.segments.size)
        assertEquals(5.0, result.segments[0].quantity, 0.01)
        assertEquals("KG", result.segments[0].unit)
        assertTrue(
            "expected आलू to survive, got ${result.segments[0].itemTokens}",
            result.segments[0].itemTokens.any { PhoneticKey.of(it) == PhoneticKey.of("आलू") }
        )
    }

    // ---------- Terminal grammar constraint ----------

    @Test
    fun trailingQuantityAndUnitStillCarriesOverToNextUtterance() {
        // The END-transition penalty must not break the deliberate carryover feature: a
        // clean "चार किलो" with no item is a legitimate reading, not a broken one.
        val result = segmenter.segmentTranscript("चार किलो")
        assertEquals("a bare quantity+unit must not invent an item", 0, result.segments.size)
        assertEquals(4.0, result.carryoverQty ?: 0.0, 0.01)
    }

    @Test
    fun carryoverQuantityAttachesToTheFollowingUtterance() {
        val result = segmenter.segmentTranscript("आलू", pendingCarryoverQty = 4.0)
        assertEquals(1, result.segments.size)
        assertEquals(4.0, result.segments[0].quantity, 0.01)
    }

    // ---------- ISSUE-022: match QUALITY must be measurable ----------
    //
    // Production trace e0b68f80-6876-42e2-b556-2adf73ce463f: shopkeeper said
    // "पांच किलो अमचूर". Grok returned "साथ गिलम चोर", Sarvam "सात गुलामचूर". The token
    // "चोर" matched catalog item "Jeera" at normalized distance 0.250 — exactly the
    // loosest match the thresholds permit — and was auto-confirmed to the ledger at
    // 0.95 confidence, because confidence was `isCatalogMatched ? 0.95 : 0.60` and
    // discarded the distance entirely.

    @Test
    fun exactMatchReportsZeroDistance() {
        val result = segmenter.segmentTranscript("पांच किलो आलू")
        assertEquals(0.0, result.segments[0].itemMatchNorm ?: -1.0, 0.001)
    }

    @Test
    fun borderlineMatchReportsItsDistanceInsteadOfHidingIt() {
        // "चोर" (thief) against "Jeera" (cumin) — a match only because it squeaks under
        // the 0.25 ceiling. The segmenter must report that, so confidence downstream can
        // be built on match QUALITY rather than on match EXISTENCE.
        val result = segmenter.segmentTranscript("साथ गिलम चोर", catalogNames = listOf("Jeera"))
        assertEquals(1, result.segments.size)
        val norm = result.segments[0].itemMatchNorm
        assertNotNull("a fuzzy item match must report its distance", norm)
        // The specific catalog word this lands on is not the point and will shift as the
        // vocabulary grows (with the expanded lexicon "चोर" now reaches छोले/Chole at
        // 0.125 rather than Jeera at 0.250). What must hold is that the distance is
        // reported and is clearly non-exact, so the server's confidence mapping keeps it
        // under the 0.80 auto-confirm gate instead of treating it as a certainty.
        assertTrue(
            "a fuzzy match must be distinguishable from an exact one, got $norm",
            norm!! > 0.05
        )
    }

    @Test
    fun unmatchedItemReportsNullDistanceRatherThanAFakeScore() {
        val result = segmenter.segmentTranscript("दो किलो zxqwvr")
        assertNull(
            "an unrecognized word kept verbatim has no match distance to report",
            result.segments[0].itemMatchNorm
        )
    }

    @Test
    fun amchoorIsInTheDefaultVocabulary() {
        // The deepest cause of ISSUE-022: अमचूर existed in NO vocabulary anywhere, so
        // the matcher could not recognize it — only map it onto the nearest word it did
        // know. A missing staple is a guaranteed future mis-booking.
        val keys = OrderingSegmenter.DEFAULT_ITEM_VOCAB.map { PhoneticKey.of(it) }
        assertTrue("अमचूर must be in the default item vocabulary", keys.contains(PhoneticKey.of("अमचूर")))
        assertTrue("हल्दी must be in the default item vocabulary", keys.contains(PhoneticKey.of("हल्दी")))
        assertTrue("हींग must be in the default item vocabulary", keys.contains(PhoneticKey.of("हींग")))
    }

    @Test
    fun amchoorNowResolvesToItselfRatherThanTheNearestKnownSpice() {
        val result = segmenter.segmentTranscript("पांच किलो अमचूर")
        assertEquals(1, result.segments.size)
        assertEquals(5.0, result.segments[0].quantity, 0.01)
        assertEquals("KG", result.segments[0].unit)
        assertTrue(
            "expected अमचूर, got ${result.segments[0].itemTokens}",
            result.segments[0].itemTokens.any { PhoneticKey.of(it) == PhoneticKey.of("अमचूर") }
        )
        assertEquals(0.0, result.segments[0].itemMatchNorm ?: -1.0, 0.001)
    }

    // ---------- Domain plausibility ----------

    @Test
    fun sevenGramsIsNotARealSale() {
        // The exact combination the ISSUE-022 trace auto-confirmed: 7 GRAM Jeera, ₹2.80.
        assertNotNull(
            "7 GRAM must be rejected as a plausible retail quantity",
            SalePlausibility.reason("GRAM", 7.0, 2.80)
        )
    }

    @Test
    fun ordinaryKiranaSalesStayPlausible() {
        assertNull(SalePlausibility.reason("KG", 5.0, 250.0))
        assertNull(SalePlausibility.reason("GRAM", 500.0, 120.0))
        assertNull(SalePlausibility.reason("PACKET", 2.0, 40.0))
        assertNull(SalePlausibility.reason("LITRE", 1.0, 60.0))
    }

    @Test
    fun absurdQuantitiesAndTinyValuesAreRejected() {
        assertNotNull(SalePlausibility.reason("KG", 5000.0, 100000.0))
        assertNotNull(SalePlausibility.reason("ML", 3.0, 50.0))
        assertNotNull("a sub-₹5 sale must not auto-confirm", SalePlausibility.reason("KG", 1.0, 2.0))
        assertNotNull(SalePlausibility.reason("KG", 0.0, 0.0))
    }

    // ---------- ISSUE-023: a missing vocab word loses to a wrong-but-present one ----------
    //
    // Production trace 0df2895e-9542-4e07-a10c-7bed88e2dfdf: shopkeeper said
    // "पांच किलो चंदन" (5 kg chandan / sandalwood paste, a real pooja-item kirana sale).
    // Sarvam heard "पांच कुल संधन". "संधन" is genuinely closer to चंदन (normalized 0.167)
    // than to any other vocab word — but चंदन did not exist in the vocabulary at all, so
    // the decoder matched the nearest word that DID exist: "संतरा"/Santra (orange) at
    // 0.214. A worse-but-present word beat a better-but-absent one, and the shopkeeper's
    // sandalwood was booked toward review as "Santra".

    @Test
    fun chandanIsInTheDefaultVocabulary() {
        val keys = OrderingSegmenter.DEFAULT_ITEM_VOCAB.map { PhoneticKey.of(it) }
        assertTrue("चंदन must be in the default item vocabulary", keys.contains(PhoneticKey.of("चंदन")))
    }

    @Test
    fun chandanMisheardAsSandhanNowResolvesCorrectly() {
        // "संधन" must now resolve to चंदन (dist 0.167), not संतरा (dist 0.214) — the
        // closer word wins once it actually exists as a competing candidate.
        val result = segmenter.segmentTranscript("पांच कुल संधन")
        assertEquals(1, result.segments.size)
        assertEquals(5.0, result.segments[0].quantity, 0.01)
        assertEquals("KG", result.segments[0].unit)
        assertTrue(
            "expected चंदन, got ${result.segments[0].itemTokens} (norm=${result.segments[0].itemMatchNorm})",
            result.segments[0].itemTokens.any { PhoneticKey.of(it) == PhoneticKey.of("चंदन") }
        )
    }

    @Test
    fun fusedKiloAmchoorIsRecoveredFromTheSarvamReading() {
        // Sarvam heard "सात गुलामचूर" — "kilo am-choor" fused into one token. With अमचूर
        // in the vocabulary the split किलो + अमचूर is now available to the lattice.
        val result = segmenter.segmentTranscript("सात गुलामचूर")
        assertEquals(1, result.segments.size)
        assertTrue(
            "expected अमचूर to be recovered from the fused token, got ${result.segments[0].itemTokens}",
            result.segments[0].itemTokens.any { PhoneticKey.of(it) == PhoneticKey.of("अमचूर") }
        )
    }

    // ---------- Phase 0a: Candidate distance ranking and margin instrumentation ----------

    @Test
    fun candidateRankAndMarginArePopulatedOnItemMatch() {
        val result = segmenter.segmentTranscript("पांच कुल संधन")
        assertEquals(1, result.segments.size)
        val seg = result.segments[0]
        assertNotNull("itemMatchNorm must be populated for matched items", seg.itemMatchNorm)
        assertNotNull("itemMargin must be populated for matched items", seg.itemMargin)
        assertTrue("top3Candidates must not be empty", seg.top3Candidates.isNotEmpty())
        assertTrue("top3Candidates must contain at least 1 candidate", seg.top3Candidates.size >= 1)
        val topCandidate = seg.top3Candidates[0]
        assertEquals(PhoneticKey.of("चंदन"), PhoneticKey.of(topCandidate.itemName))
    }

    @Test
    fun heardTextSurvivesEvenWhenItemIsMisresolved() {
        // Production trace 26ee5b12: Sarvam correctly heard "सोयाबीन" (soyabean); the
        // segmenter's own vocabulary fuzzy-matched it to "साबुन" (soap) at norm 0.214.
        // The RESOLVED item name is allowed to be wrong here (that's what margin-gating
        // in Phase 0b is for) — what must NEVER happen is losing the fact that "सोयाबीन"
        // is what was actually said.
        val result = segmenter.segmentTranscript("चार किलो सोयाबीन")
        assertEquals(1, result.segments.size)
        val seg = result.segments[0]
        assertTrue(
            "resolved item may legitimately be wrong pending Phase 0b, got ${seg.itemTokens}",
            seg.itemTokens.isNotEmpty()
        )
        assertTrue(
            "heardSegmentText must preserve सोयाबीन verbatim regardless of what it resolved to, got '${seg.heardSegmentText}'",
            seg.heardSegmentText.contains("सोयाबीन")
        )
    }
}
