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
        createNotificationChannel()
        startPeriodicSync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Shop Ledger Active")
            .setContentText("Listening for UPI payments & sync active")
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
                db.sttJobDao()
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

