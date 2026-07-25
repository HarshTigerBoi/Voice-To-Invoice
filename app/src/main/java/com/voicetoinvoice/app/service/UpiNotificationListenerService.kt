package com.voicetoinvoice.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class UpiNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        val extras = sbn.notification?.extras ?: return

        val title = extras.getString("android.title") ?: ""
        val text = extras.getString("android.text") ?: ""

        // Check for Paytm, PhonePe, Google Pay notification packages
        if (packageName.contains("paytm") || packageName.contains("phonepe") || packageName.contains("gpay") || packageName.contains("net.one97.paytm")) {
            val amount = parseUpiAmount(title, text)
            if (amount > 0.0) {
                Log.d("UpiListener", "UPI Payment Detected: ₹$amount from $packageName")
                // Triggers passive payment matching with pending voice transactions
            }
        }
    }

    private fun parseUpiAmount(title: String, text: String): Double {
        val combined = "$title $text"
        val regex = Regex("(?:rs|inr|₹)\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        val match = regex.find(combined)
        return match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }
}
