package com.voicetoinvoice.app.router

import com.voicetoinvoice.app.domain.router.AssistantIntent
import com.voicetoinvoice.app.domain.router.IntentRouter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trilingual fixture set: the same intents spoken in Devanagari, Latin-Hinglish and English.
 *
 * This is the mirror-drift guard named in Docs/master_build_plan.md §2.6. The Deno side
 * (`process-voice-job/intent_router_test.ts`) must run the same phrase list and agree on every
 * one, or the app will behave differently depending on whether it happened to be open.
 *
 * The Hinglish rows are the point. The previous Devanagari-only router returned UNKNOWN for
 * *"aaj ki sale kitni hui"* because STT often returns Latin text (ISSUE-004/020) and a Latin token
 * shares zero characters with a Devanagari keyword.
 */
class IntentRouterFixtureTest {

    private fun lines(vararg items: Triple<String, Double, Double>): JSONArray {
        val arr = JSONArray()
        for ((name, qty, total) in items) {
            arr.put(JSONObject().apply {
                put("item_name", name)
                put("quantity", qty)
                put("unit", "KG")
                put("total", total)
            })
        }
        return arr
    }

    private val noLines = JSONArray()
    private val onePotato = lines(Triple("आलू", 5.0, 150.0))
    private val singleUnitPotato = lines(Triple("आलू", 1.0, 30.0))

    private fun assertIntent(expected: AssistantIntent, transcript: String, items: JSONArray = JSONArray()) {
        val res = IntentRouter.classify(transcript, items)
        assertEquals(
            "\"$transcript\" -> expected $expected but got ${res.intent} " +
                "(confidence=${"%.2f".format(res.confidence)}, scores=${res.scores})",
            expected,
            res.intent
        )
    }

    // ---------------------------------------------------------------- READ_QUERY

    @Test
    fun readQuery_devanagari() {
        assertIntent(AssistantIntent.READ_QUERY, "आज कितना कमाया")
        assertIntent(AssistantIntent.READ_QUERY, "आलू कितना बचा है")
        assertIntent(AssistantIntent.READ_QUERY, "रमेश का कितना बाकी है")
        assertIntent(AssistantIntent.READ_QUERY, "आज का हिसाब बताओ")
    }

    @Test
    fun readQuery_hinglish() {
        // The headline regression: all of these returned UNKNOWN before.
        assertIntent(AssistantIntent.READ_QUERY, "aaj ki sale kitni hui")
        assertIntent(AssistantIntent.READ_QUERY, "aaloo kitna bacha hai")
        assertIntent(AssistantIntent.READ_QUERY, "aaj kitna kamaya")
        assertIntent(AssistantIntent.READ_QUERY, "ramesh ka kitna baki hai")
    }

    @Test
    fun readQuery_english() {
        assertIntent(AssistantIntent.READ_QUERY, "how much stock is left")
        assertIntent(AssistantIntent.READ_QUERY, "what is today sale")
        assertIntent(AssistantIntent.READ_QUERY, "tell me today profit")
    }

    // ---------------------------------------------------------------- SALE

    @Test
    fun sale_isTheDefaultWhenGoodsArePresentWithNoKeyword() {
        assertIntent(AssistantIntent.SALE, "पाँच किलो आलू", onePotato)
        assertIntent(AssistantIntent.SALE, "paanch kilo aaloo", onePotato)
        assertIntent(AssistantIntent.SALE, "five kilo potato", onePotato)
    }

    @Test
    fun sale_customerNameWithoutACreditWordIsStillACashSale() {
        // Regression guard carried over from the original test: a name plus "जी" must not be
        // mistaken for Udhaar just because a person is addressed.
        assertIntent(AssistantIntent.SALE, "रमेश जी दो किलो टमाटर", lines(Triple("टमाटर", 2.0, 40.0)))
    }

    // ---------------------------------------------------------------- CREDIT_SALE

    @Test
    fun creditSale_allThreeScripts() {
        assertIntent(AssistantIntent.CREDIT_SALE, "रमेश जी को दो किलो प्याज उधार", onePotato)
        assertIntent(AssistantIntent.CREDIT_SALE, "ramesh ko do kilo pyaz udhaar", onePotato)
        assertIntent(AssistantIntent.CREDIT_SALE, "khate mein likh do", onePotato)
        assertIntent(AssistantIntent.CREDIT_SALE, "two kilo onion on credit", onePotato)
    }

    // ---------------------------------------------------------------- PAYMENT_RECEIVED

    @Test
    fun paymentReceived_isNotConfusedWithCreditSale() {
        // Opposite sign on the same ledger -- booking a payment as new debt doubles the error.
        assertIntent(AssistantIntent.PAYMENT_RECEIVED, "Ramesh ne 500 diye")
        assertIntent(AssistantIntent.PAYMENT_RECEIVED, "रमेश ने 500 दे दिए")
        assertIntent(AssistantIntent.PAYMENT_RECEIVED, "ramesh ne 500 chuka diya")
        assertIntent(AssistantIntent.PAYMENT_RECEIVED, "ramesh paid 500")
    }

    // ---------------------------------------------------------------- PRICE_UPDATE

    @Test
    fun priceUpdate_allThreeScripts() {
        assertIntent(AssistantIntent.PRICE_UPDATE, "aaloo ka rate 30 kar do", singleUnitPotato)
        assertIntent(AssistantIntent.PRICE_UPDATE, "आलू का रेट 30 कर दो", singleUnitPotato)
        assertIntent(AssistantIntent.PRICE_UPDATE, "aaloo ka bhav 30", singleUnitPotato)
        assertIntent(AssistantIntent.PRICE_UPDATE, "potato price 30", singleUnitPotato)
    }

    @Test
    fun priceUpdate_doesNotSwallowABulkSale() {
        // "5 kilo aaloo 150 ka" is a sale, not a rate change, even though a price is present.
        assertIntent(AssistantIntent.SALE, "paanch kilo aaloo 150 ka", onePotato)
    }

    // ---------------------------------------------------------------- STOCK_IN

    @Test
    fun stockIn_allThreeScripts() {
        assertIntent(AssistantIntent.STOCK_IN, "पचास किलो आलू आया", onePotato)
        assertIntent(AssistantIntent.STOCK_IN, "pachas kilo aaloo aaya", onePotato)
        assertIntent(AssistantIntent.STOCK_IN, "fifty kilo potato purchase", onePotato)
        assertIntent(AssistantIntent.STOCK_IN, "maal aaya 20 kilo pyaz", onePotato)
    }

    // ---------------------------------------------------------------- RETURN

    @Test
    fun returns_allThreeScripts() {
        assertIntent(AssistantIntent.RETURN, "do kilo aaloo wapas kar diya", onePotato)
        assertIntent(AssistantIntent.RETURN, "दो किलो आलू वापस कर दिया", onePotato)
        assertIntent(AssistantIntent.RETURN, "two kilo potato return", onePotato)
    }

    // ---------------------------------------------------------------- WASTE / EXPIRY

    @Test
    fun waste_allThreeScripts() {
        assertIntent(AssistantIntent.WASTE, "do kilo tamatar kharab ho gaya", onePotato)
        assertIntent(AssistantIntent.WASTE, "दो किलो टमाटर खराब हो गया", onePotato)
        assertIntent(AssistantIntent.WASTE, "two kilo tomato spoiled", onePotato)
    }

    @Test
    fun expiry_beatsWasteBecauseItIsMoreSpecific() {
        assertIntent(AssistantIntent.EXPIRY_WRITEOFF, "ye biscuit expire ho gaya", onePotato)
        assertIntent(AssistantIntent.EXPIRY_WRITEOFF, "इसकी तारीख निकल गई", onePotato)
    }

    // ---------------------------------------------------------------- VOID_LAST

    @Test
    fun voidLast_allThreeScripts() {
        assertIntent(AssistantIntent.VOID_LAST, "galat ho gaya cancel karo")
        assertIntent(AssistantIntent.VOID_LAST, "पिछला गलत था मिटा दो")
        assertIntent(AssistantIntent.VOID_LAST, "cancel that, it was wrong")
    }

    // ---------------------------------------------------------------- ACTION_COMMAND

    @Test
    fun actionCommand_isReachableAtAll() {
        // Previously impossible: ACTION_WORDS was declared and never read, so no input could
        // produce ACTION_COMMAND. This is the test that fails if that regresses.
        assertIntent(AssistantIntent.ACTION_COMMAND, "Ramesh ko bill bhejo")
        assertIntent(AssistantIntent.ACTION_COMMAND, "रमेश को बिल भेज दो")
        assertIntent(AssistantIntent.ACTION_COMMAND, "send bill to Ramesh on whatsapp")
        assertIntent(AssistantIntent.ACTION_COMMAND, "ramesh ko yaad dilao")
    }

    // ---------------------------------------------------------------- UNKNOWN / safety

    @Test
    fun emptyAndNoiseYieldUnknown() {
        assertIntent(AssistantIntent.UNKNOWN, "")
        assertIntent(AssistantIntent.UNKNOWN, "   ")
    }

    @Test
    fun readQueryIsAlwaysMarkedReadOnly() {
        val res = IntentRouter.classify("aaj ki sale kitni hui", noLines)
        assertEquals(AssistantIntent.READ_QUERY, res.intent)
        assertTrue("a question must never be able to write to the ledger", res.isReadOnly)
    }

    @Test
    fun everyWriteIntentIsMarkedNotReadOnly() {
        val writes = listOf(
            "paanch kilo aaloo" to onePotato,
            "ramesh ko udhaar likh do" to onePotato,
            "pachas kilo aaloo aaya" to onePotato
        )
        for ((text, items) in writes) {
            val res = IntentRouter.classify(text, items)
            assertEquals("$text should not be read-only", false, res.isReadOnly)
        }
    }

    @Test
    fun classificationExposesScoresForDiagnostics() {
        // Misroutes are the failure shopkeepers report as "it did the wrong thing", and they are
        // undebuggable from a bare intent name.
        val res = IntentRouter.classify("ramesh ko udhaar likh do", onePotato)
        assertTrue("scores must be populated for the trace", res.scores.isNotEmpty())
    }

    @Test
    fun ambiguousUtteranceIsFlaggedRatherThanGuessed() {
        // Mixed signals should not silently commit; they should come back low-confidence.
        val res = IntentRouter.classify("kharab aaloo ka udhaar likh do", onePotato)
        assertTrue(
            "expected a low/mid confidence for a genuinely mixed utterance, got ${res.confidence}",
            res.confidence < IntentRouter.DIRECT_ROUTE_CONFIDENCE || res.needsArbitration
        )
    }
}
