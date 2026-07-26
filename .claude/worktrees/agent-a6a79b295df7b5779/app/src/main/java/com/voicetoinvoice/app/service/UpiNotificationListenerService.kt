package com.voicetoinvoice.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
    private var testReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.voicetoinvoice.app.SEED_TEST_TX") {
                    val amount = intent.getDoubleExtra("amount", 150.0)
                    serviceScope.launch {
                        val db = AppDatabase.getInstance(applicationContext)
                        val tx = com.voicetoinvoice.app.data.local.entity.TransactionRecord(
                            itemId = "test-item-id",
                            itemName = "Test Pyaz",
                            quantity = 2.0,
                            priceAtSale = amount / 2.0,
                            total = amount,
                            paymentMode = com.voicetoinvoice.app.data.local.entity.PaymentMode.CASH,
                            source = com.voicetoinvoice.app.data.local.entity.TransactionSource.VOICE,
                            timestamp = System.currentTimeMillis()
                        )
                        db.transactionDao().insert(tx)
                        Log.d("UpiListener", "SEED TEST TX Success: inserted ₹$amount CASH transaction ID ${tx.id}")
                    }
                    return
                }

                val title = intent?.getStringExtra("title") ?: ""
                val text = intent?.getStringExtra("text") ?: ""
                Log.d("UpiListener", "TEST UPI Broadcast Received - title: '$title', text: '$text'")
                val amount = parseUpiAmount(title, text)
                if (amount > 0.0) {
                    Log.d("UpiListener", "UPI Payment Detected via Test Broadcast: ₹$amount")
                    reconcileUpiPayment(amount)
                }
            }
        }
        testReceiver = receiver
        val filter = IntentFilter().apply {
            addAction("com.voicetoinvoice.app.TEST_UPI")
            addAction("com.voicetoinvoice.app.SEED_TEST_TX")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        testReceiver?.let { unregisterReceiver(it) }
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
                    val syncEngine = SyncEngine(db.transactionDao(), db.stockInDao(), db.catalogDao(), db.creditDao(), db.sttJobDao(), db.supplierDao())
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
