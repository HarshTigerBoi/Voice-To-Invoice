package com.voicetoinvoice.app.router

import com.voicetoinvoice.app.domain.router.AssistantIntent
import com.voicetoinvoice.app.domain.router.IntentRouter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class IntentRouterTest {

    @Test
    fun testIntentClassification() {
        val potatoLine = JSONArray().put(JSONObject().apply {
            put("item_name", "आलू")
            put("quantity", 5.0)
            put("unit", "KG")
            put("total", 100.0)
        })

        val tomatoLine = JSONArray().put(JSONObject().apply {
            put("item_name", "टमाटर")
            put("quantity", 2.0)
            put("unit", "KG")
            put("total", 40.0)
        })

        // 1. "आज कितना कमाया" -> READ_QUERY
        val res1 = IntentRouter.classify("आज कितना कमाया", JSONArray())
        assertEquals(AssistantIntent.READ_QUERY, res1.intent)

        // 2. "पाँच किलो आलू" (with item lines) -> SALE
        val res2 = IntentRouter.classify("पाँच किलो आलू", potatoLine)
        assertEquals(AssistantIntent.SALE, res2.intent)

        // 3. "पचास किलो आलू आया" -> STOCK_IN
        val res3 = IntentRouter.classify("पचास किलो आलू आया", potatoLine)
        assertEquals(AssistantIntent.STOCK_IN, res3.intent)

        // 4. "रमेश जी को दो किलो प्याज उधार" -> CREDIT_SALE
        val res4 = IntentRouter.classify("रमेश जी को दो किलो प्याज उधार", potatoLine)
        assertEquals(AssistantIntent.CREDIT_SALE, res4.intent)

        // 5. "दो किलो टमाटर" spoken with name "…जी" with no udhaar word -> SALE (regression guard)
        val res5 = IntentRouter.classify("रमेश जी दो किलो टमाटर", tomatoLine)
        assertEquals(AssistantIntent.SALE, res5.intent)

        // 6. "" -> UNKNOWN
        val res6 = IntentRouter.classify("", JSONArray())
        assertEquals(AssistantIntent.UNKNOWN, res6.intent)
    }
}
