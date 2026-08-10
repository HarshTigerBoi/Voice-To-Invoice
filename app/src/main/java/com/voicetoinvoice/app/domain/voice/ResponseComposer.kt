package com.voicetoinvoice.app.domain.voice

import com.voicetoinvoice.app.data.local.entity.CustomerRecord

object ResponseComposer {

    fun formatSaleConfirmed(itemName: String, quantity: Double, unit: String, total: Double): String {
        val qtyString = if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
        val totalInt = total.toInt()
        return "$qtyString $unit $itemName दर्ज हो गया, कुल ₹$totalInt"
    }

    fun formatUdhaarConfirmed(customerName: String, code: Int, totalBalance: Double): String {
        val formattedCode = String.format("#%03d", code)
        val balanceInt = totalBalance.toInt()
        return "$customerName $formattedCode, ₹$balanceInt का उधार दर्ज हो गया"
    }

    fun formatClarifyCustomer(query: String, candidates: List<CustomerRecord>): String {
        if (candidates.isEmpty()) {
            return "कोई ग्राहक नहीं मिला। क्या नया ग्राहक जोड़ना है?"
        }
        val firstTwo = candidates.take(2).joinToString(" या ") { "${it.name} ${String.format("#%03d", it.code)}" }
        return "किसका उधार? $firstTwo?"
    }

    fun formatStockReport(itemName: String, stockQty: Double, unit: String): String {
        val qtyInt = if (stockQty % 1.0 == 0.0) stockQty.toInt().toString() else stockQty.toString()
        return "$itemName का वर्तमान स्टॉक $qtyInt $unit है"
    }

    fun formatDailySales(totalAmount: Double, txnCount: Int): String {
        val amountInt = totalAmount.toInt()
        return "आज कुल $txnCount बिक्री हुई है, कुल ₹$amountInt का व्यापार हुआ"
    }

    fun formatItemSales(itemName: String, qty: Double, revenue: Double): String {
        if (qty <= 0.0) return "आज $itemName नहीं बिका"
        val qtyString = if (qty % 1.0 == 0.0) qty.toInt().toString() else qty.toString()
        return "आज $itemName $qtyString बिका, ₹${revenue.toInt()} का"
    }

    fun formatUnrecognized(): String {
        return "समझ नहीं आया, कृपया फिर से बोलिए"
    }

    fun formatActionCompleted(actionName: String): String {
        return "$actionName पूरा हो गया"
    }
}
