package com.voicetoinvoice.app

import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.domain.parser.MultiSaleDetector
import com.voicetoinvoice.app.domain.parser.VoiceParser
import com.voicetoinvoice.app.network.TermInterpretation
import com.voicetoinvoice.app.network.TermInterpretationResult
import com.voicetoinvoice.app.network.TermInterpreterClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class VoiceParserTest {

    private lateinit var voiceParser: VoiceParser
    private lateinit var sampleCatalog: List<CatalogItem>

    @Before
    fun setUp() {
        voiceParser = VoiceParser()
        sampleCatalog = listOf(
            CatalogItem(id = "1", name = "Pyaz", unitId = "KG", price = 35.0),
            CatalogItem(id = "2", name = "Aaloo", unitId = "KG", price = 25.0),
            CatalogItem(id = "3", name = "Amul Gold Milk", unitId = "LITRE", price = 68.0),
            CatalogItem(id = "4", name = "Desi Ghee", unitId = "KG", price = 650.0),
            CatalogItem(id = "5", name = "Ponds Cream", unitId = "PACKET", price = 0.0)
        )
    }

    @Test
    fun testSmartUnitFallback_NoExplicitUnit() {
        // "2 pyaz" -> No explicit unit mentioned, but Pyaz.unitId is KG -> Unit inferred as KG!
        val parsed = voiceParser.parseUtterance("2 pyaz", sampleCatalog)
        assertEquals("Pyaz", parsed.matchedItem?.name)
        assertEquals(2.0, parsed.quantity, 0.01)
        assertEquals("KG", parsed.unit)
        assertEquals(70.0, parsed.estimatedTotal, 0.01)
    }

    @Test
    fun testMultiSaleDetector_RapidFireUtterance() {
        val detector = MultiSaleDetector(voiceParser)
        val sales = detector.detectMultipleSales("2 kg pyaz 1 kg aaloo", sampleCatalog)
        assertEquals(2, sales.size)
        assertEquals("Pyaz", sales[0].matchedItem?.name)
        assertEquals(2.0, sales[0].quantity, 0.01)
        assertEquals("Aaloo", sales[1].matchedItem?.name)
        assertEquals(1.0, sales[1].quantity, 0.01)
    }

    @Test
    fun testTier1_LocalHardcodedAadiVariant() {
        val parsed = voiceParser.parseUtterance("aadi kilo pyaz", sampleCatalog)
        assertEquals("Pyaz", parsed.matchedItem?.name)
        assertEquals(0.5, parsed.quantity, 0.01)
        assertEquals("KG", parsed.unit)
        assertEquals(17.5, parsed.estimatedTotal, 0.01)
    }

    @Test
    fun testPriceBasedQuantityDisambiguation_SattarVsSatrah() {
        // "sattar kilo pyaz 595 rs" -> STT heard 70, but 595 / 35 = 17 -> Auto-corrected to 17.0 KG!
        val parsed = voiceParser.parseUtterance("sattar kilo pyaz 595 rs", sampleCatalog)
        assertEquals("Pyaz", parsed.matchedItem?.name)
        assertEquals(17.0, parsed.quantity, 0.01)
        assertEquals("KG", parsed.unit)
        assertEquals(595.0, parsed.estimatedTotal, 0.01)
    }

    @Test
    fun testTier1_HindiNumberWordParsing() {
        // Test "सत्तर किलो पॉन्ड्स क्रीम 70 रुपये" -> Qty 70.0 KG, Item Ponds Cream, Total 70.0
        val parsedSattar = voiceParser.parseUtterance("सत्तर किलो पॉन्ड्स क्रीम 70 रुपये", sampleCatalog)
        assertEquals("Ponds Cream", parsedSattar.matchedItem?.name)
        assertEquals(70.0, parsedSattar.quantity, 0.01)
        assertEquals("KG", parsedSattar.unit)
        assertEquals(70.0, parsedSattar.estimatedTotal, 0.01)

        // Test "पचास पॉन्ड्स क्रीम 50 रुपये" -> Qty 50.0 PACKET, Item Ponds Cream, Total 50.0
        val parsedPachas = voiceParser.parseUtterance("पचास पॉन्ड्स क्रीम 50 रुपये", sampleCatalog)
        assertEquals("Ponds Cream", parsedPachas.matchedItem?.name)
        assertEquals(50.0, parsedPachas.quantity, 0.01)
        assertEquals("PACKET", parsedPachas.unit)
        assertEquals(50.0, parsedPachas.estimatedTotal, 0.01)
    }

    @Test
    fun testTier1_KilometerUnitNormalizationGuard() {
        val parsed = voiceParser.parseUtterance("70 kilometer pyaz 70 rs", sampleCatalog)
        assertEquals("Pyaz", parsed.matchedItem?.name)
        assertEquals(70.0, parsed.quantity, 0.01)
        assertEquals("KG", parsed.unit)
        assertEquals(70.0, parsed.estimatedTotal, 0.01)
    }

    @Test
    fun testUnrecognizedUtteranceReturnsNullItem() {
        val parsedOnlyUnitAndQty = voiceParser.parseUtterance("70 kilometer", sampleCatalog)
        assertNull("Unrecognized utterance must return null matchedItem instead of Kilometer", parsedOnlyUnitAndQty.matchedItem)
        assertEquals(70.0, parsedOnlyUnitAndQty.quantity, 0.01)
        assertEquals("KG", parsedOnlyUnitAndQty.unit)

        val parsedJustKilo = voiceParser.parseUtterance("5 kilo", sampleCatalog)
        assertNull("5 kilo must return null matchedItem instead of Kilo or Kilometer", parsedJustKilo.matchedItem)
        assertEquals(5.0, parsedJustKilo.quantity, 0.01)
        assertEquals("KG", parsedJustKilo.unit)
    }

    @Test
    fun testPositionalGheeVsGramDisambiguation() {
        val parsedGhee = voiceParser.parseUtterance("1 kg ghee 650 rs", sampleCatalog)
        assertEquals("Ghee", parsedGhee.matchedItem?.name)
        assertEquals(1.0, parsedGhee.quantity, 0.01)
        assertEquals("KG", parsedGhee.unit)
        assertEquals(650.0, parsedGhee.estimatedTotal, 0.01)

        val parsedGram = voiceParser.parseUtterance("50 g pyaz", sampleCatalog)
        assertEquals("Pyaz", parsedGram.matchedItem?.name)
        assertEquals(50.0, parsedGram.quantity, 0.01)
        assertEquals("GRAM", parsedGram.unit)
    }

    @Test
    fun testTier2_UnseenTermNetworkFallbackMock() = runBlocking {
        val mockClient = object : TermInterpreterClient() {
            override suspend fun interpretTerm(rawTerm: String, contextText: String, shopId: String): TermInterpretationResult {
                return TermInterpretationResult.Success(
                    rawTerm = rawTerm,
                    interpretation = TermInterpretation(
                        canonicalTerm = "آधा",
                        quantityValue = 0.5
                    )
                )
            }
        }

        val result = mockClient.interpretTerm("adh-kilo", "adh-kilo pyaz")
        assert(result is TermInterpretationResult.Success)
        val success = result as TermInterpretationResult.Success
        assertEquals(0.5, success.interpretation.quantityValue, 0.01)
    }

    @Test
    fun testPriceIntent_RateUpdate_NoQuantity() {
        val parsed = voiceParser.parseUtterance("aaloo pachaas rupay", sampleCatalog)
        assertEquals("Aaloo", parsed.matchedItem?.name)
        assertEquals(com.voicetoinvoice.app.domain.parser.PriceIntent.RATE_UPDATE, parsed.priceIntent)
        assertEquals(50.0, parsed.updatedUnitPrice, 0.01)
        assertEquals(0.0, parsed.estimatedTotal, 0.01)
    }

    @Test
    fun testPriceIntent_RateUpdate_ExplicitRsKeyword() {
        val parsed = voiceParser.parseUtterance("aaloo 20 rs", sampleCatalog)
        assertEquals("Aaloo", parsed.matchedItem?.name)
        assertEquals(com.voicetoinvoice.app.domain.parser.PriceIntent.RATE_UPDATE, parsed.priceIntent)
        assertEquals(20.0, parsed.updatedUnitPrice, 0.01)
    }

    @Test
    fun testPriceIntent_BulkSaleTotal() {
        val parsed = voiceParser.parseUtterance("4 kilo aloo 500 rupay", sampleCatalog)
        assertEquals("Aaloo", parsed.matchedItem?.name)
        assertEquals(com.voicetoinvoice.app.domain.parser.PriceIntent.BULK_SALE_TOTAL, parsed.priceIntent)
        assertEquals(4.0, parsed.quantity, 0.01)
        assertEquals(500.0, parsed.estimatedTotal, 0.01)
        assertEquals(125.0, parsed.updatedUnitPrice, 0.01)
    }

    @Test
    fun testPriceIntent_BulkSaleTotal_QtyOne() {
        val parsed = voiceParser.parseUtterance("ek kilo aloo pachaas rupay", sampleCatalog)
        assertEquals("Aaloo", parsed.matchedItem?.name)
        assertEquals(com.voicetoinvoice.app.domain.parser.PriceIntent.BULK_SALE_TOTAL, parsed.priceIntent)
        assertEquals(1.0, parsed.quantity, 0.01)
        assertEquals(50.0, parsed.estimatedTotal, 0.01)
    }

    @Test
    fun testPriceIntent_AmbiguousNoRupeeWord() {
        val parsed = voiceParser.parseUtterance("4 kilo aloo 500", sampleCatalog)
        assertEquals("Aaloo", parsed.matchedItem?.name)
        assertEquals(com.voicetoinvoice.app.domain.parser.PriceIntent.AMBIGUOUS_UNTRUSTED, parsed.priceIntent)
        assertEquals(true, parsed.isPendingPrice)
        assertEquals(0.0, parsed.estimatedTotal, 0.01)
    }

    @Test
    fun testPriceIntent_None_NoPriceSpoken() {
        val parsed = voiceParser.parseUtterance("4 kilo aloo", sampleCatalog)
        assertEquals("Aaloo", parsed.matchedItem?.name)
        assertEquals(com.voicetoinvoice.app.domain.parser.PriceIntent.NONE, parsed.priceIntent)
        assertEquals(100.0, parsed.estimatedTotal, 0.01)
    }

    @Test
    fun testPriceIntent_SanityFlag_LargeRateJump() {
        val parsed = voiceParser.parseUtterance("aaloo 90 rupay", sampleCatalog)
        assertEquals("Aaloo", parsed.matchedItem?.name)
        assertEquals(com.voicetoinvoice.app.domain.parser.PriceIntent.RATE_UPDATE, parsed.priceIntent)
        assertEquals(true, parsed.isSanityFlagged)
    }

    @Test
    fun testItemNameExtraction_ExcludesRupeeWordVariants() {
        val parsed = voiceParser.parseUtterance("50 rupaya paneer", sampleCatalog)
        assertEquals("Paneer", parsed.matchedItem?.name)
        assertEquals(false, parsed.matchedItem?.name?.lowercase()?.contains("rupaya") ?: true)
    }

    @Test
    fun testPriceIntent_RateUpdate_RupayeSpellingVariant() {
        val parsed = voiceParser.parseUtterance("gold 50 रुपए", sampleCatalog)
        assertEquals(com.voicetoinvoice.app.domain.parser.PriceIntent.RATE_UPDATE, parsed.priceIntent)
        assertEquals(50.0, parsed.updatedUnitPrice, 0.01)
    }
}
