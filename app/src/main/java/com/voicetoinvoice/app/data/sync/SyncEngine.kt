package com.voicetoinvoice.app.data.sync

import android.util.Log
import com.voicetoinvoice.app.data.local.dao.*
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.CreditRecord
import com.voicetoinvoice.app.data.local.entity.StockInRecord
import com.voicetoinvoice.app.data.local.entity.SupplierRecord
import com.voicetoinvoice.app.network.CloudSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncEngine(
    private val transactionDao: TransactionDao,
    private val stockInDao: StockInDao,
    private val catalogDao: CatalogDao,
    private val creditDao: CreditDao,
    private val sttJobDao: SttJobDao,
    private val supplierDao: SupplierDao,
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
            count += syncUnsyncedSuppliers()
            count += syncUnsyncedStockIn()
            // Pull LAST, so local edits have already been pushed and the server copy we
            // merge against includes them. Pulling first would compare local changes
            // against a server state that predates them and look like a spurious conflict.
            count += pullCatalogFromCloud()
        } catch (e: Exception) {
            Log.w(TAG, "Error during full sync sweep: ${e.message}")
        }
        count
    }

    /**
     * Merges the server's catalog into the local Room copy — the app's only server→client
     * read path. See `CloudSyncManager.fetchCatalogFromCloud` for why it exists at all.
     *
     * The merge is deliberately conservative, because the local DB is the source of truth
     * for everything the shopkeeper typed:
     *
     *  - A local row with `synced = false` is NEVER overwritten. That row is a pending local
     *    edit that has not reached the server yet, so the server's copy is by definition
     *    stale; clobbering it would silently discard a price the shopkeeper just set.
     *  - Otherwise last-write-wins on `updatedAt`, so a server-side rate update lands but an
     *    older server row can't resurrect a value the phone already moved past.
     *  - A server row whose id is unknown locally is INSERTED — this is the case that makes
     *    ISSUE-033's auto-added items actually reach the phone.
     *  - A server row is skipped if some other local row already carries the same name. The
     *    two sides allocate their own UUIDs, so matching on id alone would let the same item
     *    appear twice in the picker under two different ids.
     *  - Nothing is ever deleted locally. A row missing from the server is treated as "not
     *    yet pushed", not as "deleted remotely" — there is no tombstone mechanism that could
     *    tell those apart, and guessing wrong destroys data.
     */
    suspend fun pullCatalogFromCloud(): Int = withContext(Dispatchers.IO) {
        val remote = cloudSyncManager.fetchCatalogFromCloud() ?: return@withContext 0
        if (remote.isEmpty()) return@withContext 0

        val local = catalogDao.getAllCatalogList()
        val localById = local.associateBy { it.id }
        val localIdsByName = local.groupBy { it.name.trim().lowercase() }

        var applied = 0
        for (remoteItem in remote) {
            val existing = localById[remoteItem.id]

            if (existing == null) {
                // Guard against inserting a duplicate of an item the phone already knows
                // under a locally-generated id.
                val nameCollision = localIdsByName[remoteItem.name.trim().lowercase()]
                if (!nameCollision.isNullOrEmpty()) continue

                catalogDao.insertOrUpdate(remoteItem)
                applied++
                Log.i(TAG, "⬇️ Learned new catalog item from cloud: '${remoteItem.name}' (₹${remoteItem.price})")
                continue
            }

            if (!existing.synced) continue
            if (remoteItem.updatedAt <= existing.updatedAt) continue
            if (remoteItem.price == existing.price &&
                remoteItem.unitId == existing.unitId &&
                remoteItem.active == existing.active
            ) continue

            catalogDao.insertOrUpdate(
                existing.copy(
                    name = remoteItem.name,
                    unitId = remoteItem.unitId,
                    price = remoteItem.price,
                    active = remoteItem.active,
                    updatedAt = remoteItem.updatedAt,
                    synced = true
                )
            )
            applied++
            Log.i(TAG, "⬇️ Updated '${existing.name}' from cloud: ₹${existing.price} → ₹${remoteItem.price}")
        }

        if (applied > 0) Log.i(TAG, "Catalog pull merged $applied change(s) from Supabase")
        applied
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

    suspend fun syncUnsyncedSuppliers(): Int = withContext(Dispatchers.IO) {
        val unsynced = supplierDao.getUnsyncedSuppliers()
        if (unsynced.isEmpty()) return@withContext 0

        val syncedIds = mutableListOf<String>()
        for (supplier in unsynced) {
            val success = cloudSyncManager.syncSupplierToCloud(supplier)
            if (success) syncedIds.add(supplier.id)
        }

        if (syncedIds.isNotEmpty()) {
            supplierDao.markSynced(syncedIds)
            Log.i(TAG, "Synced ${syncedIds.size}/${unsynced.size} suppliers to Supabase")
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
