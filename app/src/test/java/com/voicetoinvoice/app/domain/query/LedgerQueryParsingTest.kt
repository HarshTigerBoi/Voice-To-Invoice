package com.voicetoinvoice.app.domain.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class LedgerQueryParsingTest {

    @Test
    fun `parses valid CREDIT_SALES query from trace json`() {
        val traceJson = """
            {
              "server": {
                "step_2c_semantic_query": {
                  "query": {
                    "kind": "QUERY",
                    "metric": "CREDIT_SALES",
                    "period": { "kind": "TODAY", "n": null },
                    "item": null,
                    "customer": null,
                    "confidence": 0.95,
                    "unsupported_reason": null
                  }
                }
              }
            }
        """.trimIndent()

        val query = LedgerQuery.fromTraceJson(traceJson)
        assertNotNull(query)
        assertEquals(QueryMetric.CREDIT_SALES, query!!.metric)
        assertEquals(PeriodKind.TODAY, query.period.kind)
        assertNull(query.item)
        assertNull(query.customer)
        assertEquals(0.95, query.confidence, 0.001)
    }

    @Test
    fun `returns null for NOT_A_QUERY`() {
        val traceJson = """
            {
              "server": {
                "step_2c_semantic_query": {
                  "query": {
                    "kind": "NOT_A_QUERY",
                    "booking_intent": "SALE"
                  }
                }
              }
            }
        """.trimIndent()

        val query = LedgerQuery.fromTraceJson(traceJson)
        assertNull(query)
    }

    @Test
    fun `returns null for unknown metric string`() {
        val traceJson = """
            {
              "server": {
                "step_2c_semantic_query": {
                  "query": {
                    "kind": "QUERY",
                    "metric": "FUTURE_PREDICTION_METRIC",
                    "period": { "kind": "TODAY", "n": null }
                  }
                }
              }
            }
        """.trimIndent()

        val query = LedgerQuery.fromTraceJson(traceJson)
        assertNull(query)
    }

    @Test
    fun `returns null when step_2c_semantic_query is missing`() {
        val traceJson = """
            {
              "server": {
                "step_2_stt_proxy_response": {}
              }
            }
        """.trimIndent()

        val query = LedgerQuery.fromTraceJson(traceJson)
        assertNull(query)
    }

    @Test
    fun `returns null on malformed json without throwing`() {
        val query = LedgerQuery.fromTraceJson("not json { {")
        assertNull(query)
    }

    @Test
    fun `QueryPeriod windowMs computes correct bounds for fixed clock`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.AUGUST, 12, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val fixedNow = cal.timeInMillis

        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis

        val (startToday, endToday) = QueryPeriod(PeriodKind.TODAY).windowMs(fixedNow)
        assertEquals(todayStart, startToday)
        assertEquals(fixedNow, endToday)

        val (startAllTime, endAllTime) = QueryPeriod(PeriodKind.ALL_TIME).windowMs(fixedNow)
        assertEquals(0L, startAllTime)
        assertEquals(fixedNow, endAllTime)

        val (start7Days, end7Days) = QueryPeriod(PeriodKind.LAST_N_DAYS, 7).windowMs(fixedNow)
        val expected7DaysStart = Calendar.getInstance().apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, -7)
        }.timeInMillis
        assertEquals(expected7DaysStart, start7Days)
        assertEquals(fixedNow, end7Days)
    }
}
