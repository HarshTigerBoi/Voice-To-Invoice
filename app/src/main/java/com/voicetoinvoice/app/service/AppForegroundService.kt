package com.voicetoinvoice.app.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Cold entry point: this service can be restarted by the system with no Activity alive,
        // so it must resolve shop identity itself rather than assuming MainActivity already did.
        com.voicetoinvoice.app.data.ShopContext.initialize(this)
        createNotificationChannel()
        startPeriodicSync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // §5 (Docs/assistant_speed_and_pipeline_fix_plan.md): the microphone is no
        // longer held open by this service or while the app is backgrounded -- it only
        // runs while a screen is actually visible (see MainActivity's lifecycle-bound
        // RollingAudioBuffer start/stop). This text must not claim otherwise.
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Shop Ledger Active")
            .setContentText("UPI payments & sync active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPeriodicSync() {
        syncJob = serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val syncEngine = SyncEngine(
                db.transactionDao(),
                db.stockInDao(),
                db.catalogDao(),
                db.creditDao(),
                db.sttJobDao(),
                db.supplierDao(),
                db.customerDao(),
                db.stockLedgerDao(),
                db.stockBatchDao(),
                db.customerPaymentDao(),
                db.shopLearningDao()
            )
            while (isActive) {
                try {
                    syncEngine.syncAllUnsynced()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(30_000L) // Sweep every 30 seconds
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shop Ledger Background Sync & UPI Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "voice_ledger_service_channel"
        const val NOTIFICATION_ID = 1001
    }
}

