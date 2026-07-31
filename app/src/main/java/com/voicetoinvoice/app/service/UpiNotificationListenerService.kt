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

    // ISSUE-018 (resolved, see ISSUE-050): an exported BroadcastReceiver listening for
    // `SEED_TEST_TX` / `TEST_UPI` used to be registered here with RECEIVER_EXPORTED and no
    // debug guard, letting ANY app on the phone insert fake sales into the real ledger or
    // falsely mark a pending/Udhaar sale as UPI-paid. It has been removed outright -- manual
    // UPI testing now goes through a real notification or an instrumented test, never through
    // an IPC surface that ships to shopkeepers.

    override fun onCreate() {
        super.onCreate()
        // Cold entry point: the system binds this listener independently of the Activity, and
        // `reconcileUpiPayment` writes to shop-scoped tables.
        com.voicetoinvoice.app.data.ShopContext.initialize(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        val extras = sbn.notification?.extras ?: return

        val title = extras.getString("android.title") ?: ""
        val text = extras.getString("android.text") ?: ""

        val lowerPkg = packageName.lowercase()
        val combinedText = "$title $text".lowercase()

        // Check for Paytm, PhonePe, Google Pay notification packages or title/text triggers
        if (lowerPkg.contains("paytm") || lowerPkg.contains("phonepe") || lowerPkg.contains("gpay") || lowerPkg.contains("net.one97.paytm") ||
            lowerPkg.contains("shell") || combinedText.contains("paytm") || combinedText.contains("phonepe") || combinedText.contains("gpay")) {
            val amount = parseUpiAmount(title, text)
            if (amount > 0.0) {
                Log.d("UpiListener", "UPI Payment Detected: ₹$amount from $packageName (title: $title)")
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
                    val syncEngine = SyncEngine(db.transactionDao(), db.stockInDao(), db.catalogDao(), db.creditDao(), db.sttJobDao(), db.supplierDao(), db.customerDao(), db.stockLedgerDao(), db.stockBatchDao(), db.customerPaymentDao(), db.shopLearningDao())
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
