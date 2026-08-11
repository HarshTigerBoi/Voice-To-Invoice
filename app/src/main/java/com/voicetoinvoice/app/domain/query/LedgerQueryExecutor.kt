package com.voicetoinvoice.app.domain.query

import com.voicetoinvoice.app.domain.voice.ResponseComposer

class LedgerQueryExecutor(private val ledgerQueries: LedgerQueries) {
    /** Every number spoken by the assistant originates in this method, from Room. The model
     *  chose WHICH question; it never supplied an answer. */
    suspend fun execute(query: LedgerQuery): String {
        if (query.confidence < LedgerQuery.MIN_CONFIDENCE) {
            return ResponseComposer.formatUnrecognized()
        }

        if (query.metric == QueryMetric.UNSUPPORTED) {
            return "यह मैं अभी नहीं बता सकता"
        }

        val (startMs, endMs) = query.period.windowMs()

        return when (query.metric) {
            QueryMetric.SALES_TOTAL -> {
                val (revenue, count) = ledgerQueries.getSalesBetween(startMs, endMs)
                ResponseComposer.formatDailySales(revenue, count)
            }
            QueryMetric.ITEM_SALES -> {
                val itemQuery = query.item
                if (itemQuery.isNullOrBlank()) {
                    ResponseComposer.formatUnrecognized()
                } else {
                    val result = ledgerQueries.getItemSalesInPeriod(itemQuery, startMs, endMs)
                    if (result == null) {
                        "$itemQuery नाम का कोई आइटम नहीं मिला"
                    } else {
                        ResponseComposer.formatItemSales(result.first, result.second, result.third)
                    }
                }
            }
            QueryMetric.CREDIT_SALES -> {
                val creditSales = ledgerQueries.getCreditSalesInPeriod(startMs, endMs)
                ResponseComposer.formatCreditSales(creditSales)
            }
            QueryMetric.RECEIVABLES_TOTAL -> {
                val receivables = ledgerQueries.getTotalReceivables()
                val receivablesInt = receivables.toInt()
                if (receivablesInt > 0) "कुल ₹$receivablesInt का उधार बकाया है"
                else "कोई उधार बकाया नहीं है"
            }
            QueryMetric.CUSTOMER_BALANCE -> {
                val customerQuery = query.customer
                if (customerQuery.isNullOrBlank()) {
                    ResponseComposer.formatUnrecognized()
                } else {
                    val result = ledgerQueries.getCustomerBalanceWithName(customerQuery)
                    if (result == null) {
                        "$customerQuery नाम का कोई ग्राहक नहीं मिला"
                    } else {
                        val (name, balance) = result
                        val balanceInt = balance.toInt()
                        if (balanceInt > 0) "$name का ₹$balanceInt का उधार बकाया है"
                        else "$name का कोई उधार बकाया नहीं है"
                    }
                }
            }
            QueryMetric.STOCK_ON_HAND -> {
                val itemQuery = query.item
                if (itemQuery.isNullOrBlank()) {
                    ResponseComposer.formatUnrecognized()
                } else {
                    val result = ledgerQueries.getStockLevelWithName(itemQuery)
                    if (result == null) {
                        "$itemQuery नाम का कोई आइटम नहीं मिला"
                    } else {
                        ResponseComposer.formatStockReport(result.first, result.second, "इकाई")
                    }
                }
            }
            QueryMetric.LOW_STOCK -> {
                val lowStock = ledgerQueries.getLowStockItems()
                if (lowStock.isEmpty()) {
                    "कोई आइटम कम नहीं है"
                } else {
                    val itemsStr = lowStock.take(3).joinToString(", ") { "${it.first} (${if (it.second % 1.0 == 0.0) it.second.toInt() else it.second})" }
                    "कम स्टॉक वाले आइटम: $itemsStr"
                }
            }
            QueryMetric.STOCK_VALUE -> {
                val totalValue = ledgerQueries.getTotalStockValue()
                "कुल स्टॉक का मूल्य ₹${totalValue.toInt()} है"
            }
            QueryMetric.PROFIT -> {
                val profitResult = ledgerQueries.getProfit(startMs, endMs)
                val grossInt = profitResult.grossProfit.toInt()
                "अनुमानित सकल लाभ ₹$grossInt है (केवल दर्ज खरीद लागत पर आधारित)"
            }
            QueryMetric.WASTE_VALUE -> {
                val wasteVal = ledgerQueries.getWasteValue(startMs, endMs)
                "₹${wasteVal.toInt()} का सामान खराब हुआ है"
            }
            QueryMetric.TOP_ITEM -> {
                val topItem = ledgerQueries.getTopSellingItem(startMs, endMs)
                if (topItem != null) "सबसे ज्यादा बिकने वाला आइटम $topItem है"
                else "कोई बिक्री डेटा नहीं है"
            }
            QueryMetric.SLOWEST_ITEM -> {
                val slowestItem = ledgerQueries.getSlowestSellingItem(startMs, endMs)
                if (slowestItem != null) "सबसे कम बिकने वाला आइटम $slowestItem है"
                else "कोई बिक्री डेटा नहीं है"
            }
            QueryMetric.UNSUPPORTED -> "यह मैं अभी नहीं बता सकता"
        }
    }
}
