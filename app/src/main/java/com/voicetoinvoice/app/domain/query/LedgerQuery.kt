package com.voicetoinvoice.app.domain.query

import org.json.JSONObject
import java.util.Calendar

enum class QueryMetric {
    SALES_TOTAL, ITEM_SALES, CREDIT_SALES, RECEIVABLES_TOTAL, CUSTOMER_BALANCE,
    STOCK_ON_HAND, LOW_STOCK, STOCK_VALUE, PROFIT, WASTE_VALUE, TOP_ITEM, SLOWEST_ITEM,
    UNSUPPORTED
}

enum class PeriodKind { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, LAST_N_DAYS, ALL_TIME }

data class QueryPeriod(val kind: PeriodKind, val n: Int? = null) {
    /** Resolved against the device clock at answer time, never the server's. */
    fun windowMs(nowMs: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfToday = cal.timeInMillis

        return when (kind) {
            PeriodKind.TODAY -> Pair(startOfToday, nowMs)
            PeriodKind.YESTERDAY -> {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                val startOfYesterday = cal.timeInMillis
                Pair(startOfYesterday, startOfToday - 1)
            }
            PeriodKind.THIS_WEEK -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                Pair(cal.timeInMillis, nowMs)
            }
            PeriodKind.THIS_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                Pair(cal.timeInMillis, nowMs)
            }
            PeriodKind.LAST_N_DAYS -> {
                val days = n ?: 7
                cal.add(Calendar.DAY_OF_YEAR, -days)
                Pair(cal.timeInMillis, nowMs)
            }
            PeriodKind.ALL_TIME -> Pair(0L, nowMs)
        }
    }
}

data class LedgerQuery(
    val metric: QueryMetric,
    val period: QueryPeriod,
    val item: String?,
    val customer: String?,
    val confidence: Double,
    val unsupportedReason: String?,
) {
    companion object {
        /** Below this the assistant asks the shopkeeper to repeat rather than look up
         *  something it is guessing at. Mirrors the prompt's own instruction in §4 A3. */
        const val MIN_CONFIDENCE = 0.6

        /** Reads `step_2c_semantic_query.query` out of the merged trace. Returns null for a
         *  missing key, `kind != "QUERY"`, an unknown metric, or malformed JSON — every one
         *  of those means "we did not understand", which is an honest answer. */
        fun fromTraceJson(traceJson: String): LedgerQuery? {
            if (traceJson.isBlank()) return null
            return try {
                val root = JSONObject(traceJson)
                val server = root.optJSONObject("server") ?: root
                val step2c = server.optJSONObject("step_2c_semantic_query") ?: return null
                val queryObj = step2c.optJSONObject("query") ?: return null

                val kind = queryObj.optString("kind", "")
                if (kind != "QUERY") return null

                val metricStr = queryObj.optString("metric", "")
                val metric = try {
                    enumValueOf<QueryMetric>(metricStr)
                } catch (e: Exception) {
                    return null
                }

                val periodObj = queryObj.optJSONObject("period")
                val periodKindStr = periodObj?.optString("kind", "TODAY") ?: "TODAY"
                val periodKind = try {
                    enumValueOf<PeriodKind>(periodKindStr)
                } catch (e: Exception) {
                    PeriodKind.TODAY
                }
                val nVal = periodObj?.optInt("n", -1).let { if (it == null || it <= 0) null else it }
                val period = QueryPeriod(periodKind, nVal)

                val item = queryObj.optString("item", "").let { if (it.isBlank() || it == "null") null else it }
                val customer = queryObj.optString("customer", "").let { if (it.isBlank() || it == "null") null else it }
                val confidence = queryObj.optDouble("confidence", 0.0)
                val unsupportedReason = queryObj.optString("unsupported_reason", "").let { if (it.isBlank() || it == "null") null else it }

                LedgerQuery(metric, period, item, customer, confidence, unsupportedReason)
            } catch (e: Exception) {
                null
            }
        }
    }
}
