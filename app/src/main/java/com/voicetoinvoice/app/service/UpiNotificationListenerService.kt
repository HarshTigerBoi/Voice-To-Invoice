package com.voicetoinvoice.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UpiNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                reconcileUpiPayment(amount)
            }
        }
    }

    private fun reconcileUpiPayment(amount: Double) {
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val twoMinutesAgo = System.currentTimeMillis() - (2 * 60 * 1000)
                val candidates = db.transactionDao().getRecentTransactionsByAmount(amount, twoMinutesAgo)

                if (candidates.size == 1) {
                    val matchedTx = candidates.first()
                    db.transactionDao().markTransactionPaidViaUpi(matchedTx.id)
                    Log.i("UpiListener", "Successfully reconciled UPI payment of ₹$amount to transaction ID: ${matchedTx.id}")

                    // Sweep sync to cloud
                    val syncEngine = SyncEngine(db.transactionDao(), db.stockInDao(), db.catalogDao(), db.creditDao(), db.sttJobDao())
                    syncEngine.syncAllUnsynced()
                } else if (candidates.size > 1) {
                    Log.w("UpiListener", "Ambiguous UPI payment: found ${candidates.size} transactions for ₹$amount within 2 mins. Leaving un-reconciled.")
                } else {
                    Log.d("UpiListener", "No matching pending transaction found for UPI payment of ₹$amount within 2 mins.")
                }
            } catch (e: Exception) {
                Log.e("UpiListener", "Error during UPI reconciliation", e)
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
