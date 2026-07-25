package com.voicetoinvoice.app.data.sync

import android.util.Log
import com.voicetoinvoice.app.data.local.dao.*
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.CreditRecord
import com.voicetoinvoice.app.data.local.entity.StockInRecord
import com.voicetoinvoice.app.network.CloudSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncEngine(
    private val transactionDao: TransactionDao,
    private val stockInDao: StockInDao,
    private val catalogDao: CatalogDao,
    private val creditDao: CreditDao,
    private val sttJobDao: SttJobDao,
    private val cloudSyncManager: CloudSyncManager = CloudSyncManager()
) {

    companion object {
        private const val TAG = "SyncEngine"
    }

    /**
     * Executes a full synchronization sweep for all unsynced local data to Supabase REST API.
     */
    suspend fun syncAllUnsynced(): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            count += syncUnsyncedTransactions()
            count += syncUnsyncedJobTraces()
            count += syncUnsyncedCatalog()
            count += syncUnsyncedCredits()
            count += syncUnsyncedStockIn()
        } catch (e: Exception) {
            Log.w(TAG, "Error during full sync sweep: ${e.message}")
        }
        count
    }

    suspend fun syncUnsyncedTransactions(): Int = withContext(Dispatchers.IO) {
        val unsynced = transactionDao.getUnsyncedTransactions()
        if (unsynced.isEmpty()) return@withContext 0

        val syncedIds = mutableListOf<String>()
        for (tx in unsynced) {
            val success = cloudSyncManager.syncTransactionToCloud(tx)
            if (success) syncedIds.add(tx.id)
        }

        if (syncedIds.isNotEmpty()) {
            transactionDao.markSynced(syncedIds)
            Log.i(TAG, "Synced ${syncedIds.size}/${unsynced.size} transactions to Supabase")
        }
        syncedIds.size
    }

    suspend fun syncUnsyncedJobTraces(): Int = withContext(Dispatchers.IO) {
        val unsynced = sttJobDao.getUnsyncedJobs()
        if (unsynced.isEmpty()) return@withContext 0

        val syncedIds = mutableListOf<String>()
        for (job in unsynced) {
            val success = cloudSyncManager.syncJobRecordToCloud(job)
            if (success) syncedIds.add(job.id)
        }

        if (syncedIds.isNotEmpty()) {
            sttJobDao.markSynced(syncedIds)
            Log.i(TAG, "Synced ${syncedIds.size}/${unsynced.size} job trace logs to Supabase")
        }
        syncedIds.size
    }

    suspend fun syncUnsyncedCatalog(): Int = withContext(Dispatchers.IO) {
        val unsynced = catalogDao.getUnsyncedCatalog()
        if (unsynced.isEmpty()) return@withContext 0

        val syncedIds = mutableListOf<String>()
        for (item in unsynced) {
            val success = cloudSyncManager.syncCatalogItemToCloud(item)
            if (success) syncedIds.add(item.id)
        }

        if (syncedIds.isNotEmpty()) {
            catalogDao.markSynced(syncedIds)
            Log.i(TAG, "Synced ${syncedIds.size}/${unsynced.size} catalog items to Supabase")
        }
        syncedIds.size
    }

    suspend fun syncUnsyncedCredits(): Int = withContext(Dispatchers.IO) {
        val unsynced = creditDao.getUnsyncedCredits()
        if (unsynced.isEmpty()) return@withContext 0

        val syncedIds = mutableListOf<String>()
        for (credit in unsynced) {
            val success = cloudSyncManager.syncCreditToCloud(credit)
            if (success) syncedIds.add(credit.id)
        }

        if (syncedIds.isNotEmpty()) {
            creditDao.markSynced(syncedIds)
            Log.i(TAG, "Synced ${syncedIds.size}/${unsynced.size} credits to Supabase")
        }
        syncedIds.size
    }

    suspend fun syncUnsyncedStockIn(): Int = withContext(Dispatchers.IO) {
        val unsynced = stockInDao.getUnsyncedStockIn()
        if (unsynced.isEmpty()) return@withContext 0

        val syncedIds = mutableListOf<String>()
        for (stockIn in unsynced) {
            val success = cloudSyncManager.syncStockInToCloud(stockIn)
            if (success) syncedIds.add(stockIn.id)
        }

        if (syncedIds.isNotEmpty()) {
            stockInDao.markSynced(syncedIds)
            Log.i(TAG, "Synced ${syncedIds.size}/${unsynced.size} stock-in records to Supabase")
        }
        syncedIds.size
    }
}

