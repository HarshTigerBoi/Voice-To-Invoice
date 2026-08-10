package com.voicetoinvoice.app.network

import android.util.Log
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.CreditRecord
import com.voicetoinvoice.app.data.local.entity.CustomerPayment
import com.voicetoinvoice.app.data.local.entity.CustomerRecord
import com.voicetoinvoice.app.data.local.entity.ShopLearning
import com.voicetoinvoice.app.data.local.entity.StockBatch
import com.voicetoinvoice.app.data.local.entity.StockInRecord
import com.voicetoinvoice.app.data.local.entity.StockLedgerEntry
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
import com.voicetoinvoice.app.data.local.entity.SttJobStatus
import com.voicetoinvoice.app.data.local.entity.SupplierRecord
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.data.local.entity.UnmatchedQueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

class CloudSyncManager(
    private val supabaseUrl: String = SupabaseConfig.SUPABASE_URL,
    private val anonKey: String = SupabaseConfig.SUPABASE_ANON_KEY
) {

    companion object {
        private const val TAG = "CloudSyncManager"
    }

    /**
     * Calls ensure_shop RPC on Supabase so the shops row exists before pushing
     * transactions, credits, or stock rows with a real shopId foreign key.
     */
    suspend fun ensureShopExists(shopId: String): Boolean = withContext(Dispatchers.IO) {
        if (shopId.isBlank()) return@withContext false
        try {
            val url = URL("$supabaseUrl/rest/v1/rpc/ensure_shop")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", anonKey)
            conn.setRequestProperty("Authorization", "Bearer $anonKey")
            conn.doOutput = true

            val body = JSONObject().apply { put("p_shop_id", shopId) }.toString()
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            val code = conn.responseCode
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "ensure_shop RPC call failed for $shopId: ${e.message}")
            false
        }
    }

    /**
     * Uploads audio file + diagnostic JSON trace to Supabase Cloud Storage & stt_job_logs table.
     * Called automatically after every voice recording job completes processing.
     */
    suspend fun syncJobTraceAndAudioToCloud(job: SttJobRecord, traceObj: JSONObject): Boolean = withContext(Dispatchers.IO) {
        try {
            // Step 1: Upload Audio File to Supabase Storage Bucket ('voice-recordings')
            val audioFile = File(job.audioFilePath)
            if (audioFile.exists() && audioFile.length() > 0) {
                uploadAudioToSupabaseStorage(job.id, audioFile)
            }

            // Step 2: Sync Full Trace Log to Supabase stt_job_logs table (direct REST API)
            val posted = postTraceLogToSupabaseDatabase(job, traceObj)
            posted
        } catch (e: Exception) {
            Log.w(TAG, "Background cloud sync deferred (offline or error): ${e.message}")
            false
        }
    }

    /**
     * Syncs an SttJobRecord directly to Supabase stt_job_logs table.
     */
    suspend fun syncJobRecordToCloud(job: SttJobRecord): Boolean = withContext(Dispatchers.IO) {
        try {
            val traceObj = if (job.diagnosticTraceJson.isNotBlank()) {
                try { JSONObject(job.diagnosticTraceJson) } catch (e: Exception) { JSONObject() }
            } else {
                JSONObject()
            }
            postTraceLogToSupabaseDatabase(job, traceObj)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync job record to cloud: ${e.message}")
            false
        }
    }

    /**
     * Syncs a transaction to Supabase 'transactions' table with full voice-to-output trace links.
     */
    suspend fun syncTransactionToCloud(transaction: TransactionRecord): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", transaction.id)
                put("item_name", transaction.itemName)
                put("quantity", transaction.quantity)
                put("price_at_sale", transaction.priceAtSale)
                put("total", transaction.total)
                put("payment_mode", transaction.paymentMode.name)
                put("timestamp", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                    it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(transaction.timestamp)))
                put("source", transaction.source.name)
                put("device_id", transaction.deviceId)
                put("raw_transcript", transaction.rawTranscript)
                // Without line_no the column defaults to 0 server-side, which collides
                // with the unique (job_id, line_no) index whenever this transaction is
                // NOT the job's first line (e.g. the shopkeeper manually confirms line 2
                // of a 3-item recording) -- the sync would be silently rejected. See ISSUE-029.
                put("line_no", transaction.lineNo)
                // Correction signal for the server-side Learned Parse Memory (ISSUE-031) --
                // a trigger on Supabase's transactions table demotes any memoized parse
                // this job_id contributed to the moment voided flips to true.
                put("voided", transaction.voided)
                if (transaction.voidedAtMs != null) {
                    put("voided_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                        it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.format(java.util.Date(transaction.voidedAtMs)))
                }
                if (!transaction.jobId.isNull_or_empty()) put("job_id", transaction.jobId)
                if (!transaction.audioCloudUrl.isNull_or_empty()) {
                    put("audio_cloud_url", transaction.audioCloudUrl)
                } else if (!transaction.jobId.isNull_or_empty()) {
                    put("audio_cloud_url", "$supabaseUrl/storage/v1/object/public/voice-recordings/${transaction.jobId}.wav")
                }
                if (transaction.customerId != null) put("customer_id", transaction.customerId)
            }
            // ISSUE-056: for a voice-sourced transaction, `process-voice-job` has ALREADY
            // inserted a row server-side for this exact (job_id, line_no) -- upserted with
            // `onConflict: 'job_id,line_no'` (index_transactions_job_line in schema.sql).
            // Upserting here on `id` instead (the client's own, different, random UUID) does
            // not match that row, so Postgres accepts the id-conflict clause but then rejects
            // the insert with a 23505 unique_violation on the SEPARATE (job_id, line_no) index
            // -- a 409 that made `success` false forever. `markSynced` never ran, so this row
            // retried on every single sync sweep (every screen load, per CLAUDE.md) for the
            // rest of the app's life, and the retry count only grew as more voice sales
            // accumulated. Targeting the SAME conflict key the server already used makes this
            // an idempotent update of that row instead -- the fix is which column(s) to upsert
            // on, not a new code path.
            val onConflict = if (!transaction.jobId.isNull_or_empty()) "job_id,line_no" else "id"
            val resCode = upsertToRestApi("/rest/v1/transactions", payload, onConflictColumn = onConflict)
            if (resCode in 200..299) {
                Log.i(TAG, "✅ Synced transaction '${transaction.itemName}' to Supabase cloud")
                true
            } else {
                Log.w(TAG, "❌ Transaction sync HTTP $resCode for '${transaction.itemName}'")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync transaction to cloud: ${e.message}")
            false
        }
    }

    /**
     * Syncs a catalog item to Supabase 'catalog_items' table.
     */
    suspend fun syncCatalogItemToCloud(item: CatalogItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", item.id)
                // Catalog was the one entity that never sent shop_id, so every item the phone
                // pushed landed with shop_id NULL while process-voice-job fetches the catalog
                // with a strict `shop_id = <uuid>` filter. The shop's priced catalog was
                // therefore invisible to the parser and every sale priced out at 0.
                SupabaseConfig.getNullSafeShopId(item.shopId)?.let { put("shop_id", it) }
                put("name", item.name)
                put("unit_id", item.unitId)
                put("price", item.price)
                put("active", item.active)
                if (!item.imageUrl.isNullOrBlank()) put("image_url", item.imageUrl)
                put("updated_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                    it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(item.updatedAt)))
            }
            val resCode = upsertToRestApi("/rest/v1/catalog_items", payload)
            if (resCode in 200..299) {
                Log.i(TAG, "✅ Synced catalog item '${item.name}' to Supabase cloud")
                true
            } else {
                Log.w(TAG, "❌ Catalog item sync HTTP $resCode for '${item.name}'")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync catalog item to cloud: ${e.message}")
            false
        }
    }

    /**
     * Syncs a credit record (Udhaar) to Supabase 'credits' table.
     */
    suspend fun syncCreditToCloud(credit: CreditRecord): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", credit.id)
                put("customer_name", credit.customerName)
                put("amount", credit.amount)
                put("status", credit.status.name)
                if (credit.dueDate != null) {
                    put("due_date", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                        it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.format(java.util.Date(credit.dueDate)))
                }
                if (credit.linkedTransactionId != null) put("linked_transaction_id", credit.linkedTransactionId)
                if (credit.customerId != null) put("customer_id", credit.customerId)
                put("updated_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                    it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(credit.updatedAt)))
            }
            val resCode = upsertToRestApi("/rest/v1/credits", payload)
            if (resCode in 200..299) {
                Log.i(TAG, "✅ Synced credit record for '${credit.customerName}' to Supabase cloud")
                true
            } else {
                Log.w(TAG, "❌ Credit record sync HTTP $resCode for '${credit.customerName}'")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync credit record to cloud: ${e.message}")
            false
        }
    }

    /**
     * Syncs a customer record to Supabase 'customers' table.
     */
    suspend fun syncCustomerToCloud(customer: CustomerRecord): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", customer.id)
                put("shop_id", customer.shopId)
                put("code", customer.code)
                put("name", customer.name)
                if (customer.keyword != null) put("keyword", customer.keyword)
                if (customer.phone != null) put("phone", customer.phone)
                if (customer.photoPath != null) put("photo_path", customer.photoPath)
                put("phonetic_key", customer.phoneticKey)
                if (customer.keywordPhoneticKey != null) put("keyword_phonetic_key", customer.keywordPhoneticKey)
                put("last_seen_ms", customer.lastSeenMs)
                put("txn_count", customer.txnCount)
                if (customer.mergedIntoId != null) put("merged_into_id", customer.mergedIntoId)
                put("created_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                    it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(customer.createdAt)))
            }
            val resCode = upsertToRestApi("/rest/v1/customers", payload)
            if (resCode in 200..299) {
                Log.i(TAG, "✅ Synced customer record for '${customer.name}' to Supabase cloud")
                true
            } else {
                Log.w(TAG, "❌ Customer record sync HTTP $resCode for '${customer.name}'")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync customer record to cloud: ${e.message}")
            false
        }
    }

    /**
     * Syncs a supplier record to Supabase 'suppliers' table.
     */
    suspend fun syncSupplierToCloud(supplier: SupplierRecord): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", supplier.id)
                put("name", supplier.name)
                if (supplier.phone != null) put("phone", supplier.phone)
                put("balance_owed", supplier.balanceOwed)
                put("updated_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                    it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(supplier.updatedAt)))
            }
            val resCode = upsertToRestApi("/rest/v1/suppliers", payload)
            if (resCode in 200..299) {
                Log.i(TAG, "✅ Synced supplier record for '${supplier.name}' to Supabase cloud")
                true
            } else {
                Log.w(TAG, "❌ Supplier record sync HTTP $resCode for '${supplier.name}'")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync supplier record to cloud: ${e.message}")
            false
        }
    }

    /**
     * Syncs a stock-in record to Supabase 'stock_in' table.
     */
    suspend fun syncStockInToCloud(stockIn: StockInRecord): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", stockIn.id)
                put("item_name", stockIn.itemName)
                put("quantity", stockIn.quantity)
                put("cost_price", stockIn.costPrice)
                put("cost_missing", stockIn.costMissing)
                if (stockIn.supplier != null) put("supplier", stockIn.supplier)
                put("timestamp", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                    it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(stockIn.timestamp)))
            }
            val resCode = upsertToRestApi("/rest/v1/stock_in", payload)
            if (resCode in 200..299) {
                Log.i(TAG, "✅ Synced stock-in for '${stockIn.itemName}' to Supabase cloud")
                true
            } else {
                Log.w(TAG, "❌ Stock-in sync HTTP $resCode for '${stockIn.itemName}'")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync stock-in record to cloud: ${e.message}")
            false
        }
    }

    /**
     * Syncs one append-only stock movement to Supabase 'stock_ledger' table (Docs/remaining_work_plan.md §1.2).
     */
    suspend fun syncStockLedgerEntryToCloud(entry: StockLedgerEntry): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", entry.id)
                put("shop_id", entry.shopId)
                put("item_id", entry.itemId)
                put("item_name", entry.itemName)
                put("delta_qty", entry.deltaQty)
                put("reason", entry.reason.name)
                if (entry.unitCost != null) put("unit_cost", entry.unitCost)
                if (entry.refId != null) put("ref_id", entry.refId)
                if (entry.batchId != null) put("batch_id", entry.batchId)
                if (entry.note != null) put("note", entry.note)
                put("occurred_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                    it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(entry.occurredAtMs)))
            }
            val resCode = upsertToRestApi("/rest/v1/stock_ledger", payload)
            if (resCode in 200..299) {
                Log.i(TAG, "✅ Synced stock_ledger entry for '${entry.itemName}' (${entry.reason}) to Supabase cloud")
                true
            } else {
                Log.w(TAG, "❌ stock_ledger sync HTTP $resCode for '${entry.itemName}'")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync stock_ledger entry to cloud: ${e.message}")
            false
        }
    }

    /**
     * Syncs one expiry-tracked stock batch to Supabase 'stock_batches' table.
     */
    suspend fun syncStockBatchToCloud(batch: StockBatch): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", batch.id)
                put("shop_id", batch.shopId)
                put("item_id", batch.itemId)
                put("item_name", batch.itemName)
                put("received_qty", batch.receivedQty)
                put("remaining_qty", batch.remainingQty)
                if (batch.unitCost != null) put("unit_cost", batch.unitCost)
                if (batch.expiryDateMs != null) {
                    put("expiry_date", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                        it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.format(java.util.Date(batch.expiryDateMs)))
                }
                put("received_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                    it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(batch.receivedAtMs)))
            }
            val resCode = upsertToRestApi("/rest/v1/stock_batches", payload)
            if (resCode in 200..299) {
                Log.i(TAG, "✅ Synced stock_batch for '${batch.itemName}' to Supabase cloud")
                true
            } else {
                Log.w(TAG, "❌ stock_batches sync HTTP $resCode for '${batch.itemName}'")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync stock_batch to cloud: ${e.message}")
            false
        }
    }

    /**
     * Syncs one Udhaar repayment to Supabase 'customer_payments' table.
     */
    suspend fun syncCustomerPaymentToCloud(payment: CustomerPayment): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", payment.id)
                put("shop_id", payment.shopId)
                put("customer_id", payment.customerId)
                put("customer_name", payment.customerName)
                put("amount", payment.amount)
                put("mode", payment.mode.name)
                if (payment.note != null) put("note", payment.note)
                if (payment.jobId != null) put("job_id", payment.jobId)
                put("received_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                    it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(payment.receivedAtMs)))
            }
            val resCode = upsertToRestApi("/rest/v1/customer_payments", payload)
            if (resCode in 200..299) {
                Log.i(TAG, "✅ Synced customer_payment for '${payment.customerName}' to Supabase cloud")
                true
            } else {
                Log.w(TAG, "❌ customer_payments sync HTTP $resCode for '${payment.customerName}'")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync customer_payment to cloud: ${e.message}")
            false
        }
    }

    /**
     * Syncs one per-shop learning entry to Supabase 'shop_learning' table. Keyed on the
     * composite (shop_id, kind, key) -- there is no surrogate id locally, so the upsert's
     * on_conflict target is the same triple.
     */
    suspend fun syncShopLearningToCloud(entry: ShopLearning): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("shop_id", entry.shopId)
                put("kind", entry.kind.name)
                put("key", entry.key)
                put("value", entry.value)
                put("hit_count", entry.hitCount)
                put("last_used_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                    it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(entry.lastUsedAtMs)))
                put("confidence", entry.confidence)
            }
            val resCode = upsertToRestApi("/rest/v1/shop_learning", payload, onConflictColumn = "shop_id,kind,key")
            if (resCode in 200..299) {
                Log.i(TAG, "✅ Synced shop_learning entry '${entry.kind}:${entry.key}' to Supabase cloud")
                true
            } else {
                Log.w(TAG, "❌ shop_learning sync HTTP $resCode for '${entry.kind}:${entry.key}'")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync shop_learning entry to cloud: ${e.message}")
            false
        }
    }

    /**
     * Syncs an unmatched/review queue item to Supabase 'unmatched_queue' table.
     */
    suspend fun syncReviewItemToCloud(item: UnmatchedQueueItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("job_id", item.id)
                val safeShopId = SupabaseConfig.getNullSafeShopId(item.shopId)
                if (safeShopId != null) {
                    put("shop_id", safeShopId)
                }
                if (item.resolvedItemId != null && item.resolvedItemId.isNotBlank()) {
                    put("resolved_item_id", item.resolvedItemId)
                }
                put("raw_transcript", item.rawTranscript)
                put("audio_ref", item.audioRef ?: "")
                put("status", item.status.name)
                put("timestamp", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).also {
                    it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(item.timestamp)))
            }
            val resCode = upsertToRestApi("/rest/v1/unmatched_queue", payload, onConflictColumn = "job_id")
            if (resCode in 200..299) {
                Log.i(TAG, "Synced review item '${item.rawTranscript}' to Supabase cloud.")
                true
            } else {
                Log.w(TAG, "❌ unmatched_queue sync HTTP $resCode for '${item.rawTranscript}'")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync review item to cloud: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────────

    private fun uploadAudioToSupabaseStorage(jobId: String, audioFile: File) {
        try {
            // Supabase Storage PUT for upsert
            val urlString = "$supabaseUrl/storage/v1/object/voice-recordings/$jobId.wav"
            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 30000
                readTimeout = 30000
                setRequestProperty("Authorization", "Bearer $anonKey")
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Content-Type", "audio/wav")
                setRequestProperty("x-upsert", "true")
                setFixedLengthStreamingMode(audioFile.length())
            }

            FileInputStream(audioFile).use { inputStream ->
                connection.outputStream.use { outputStream ->
                    inputStream.copyTo(outputStream, bufferSize = 8192)
                }
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                Log.i(TAG, "✅ Uploaded audio for job $jobId to Supabase Storage (${audioFile.length() / 1024}KB)")
            } else {
                val body = connection.errorStream?.bufferedReader()?.readText() ?: "no body"
                Log.w(TAG, "❌ Storage upload HTTP $responseCode: $body")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upload audio: ${e.message}")
        }
    }

    private fun postTraceLogToSupabaseDatabase(job: SttJobRecord, traceObj: JSONObject): Boolean {
        try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val payload = JSONObject().apply {
                put("job_id", job.id)
                put("recorded_at_ms", job.recordedAtMs)
                put("created_at", sdf.format(java.util.Date(job.recordedAtMs)))
                put("hold_duration_ms", job.holdDurationMs)
                put("status", job.status.name)
                put("parsed_item_name", job.parsedItemName ?: "")
                put("parsed_qty", job.parsedQty)
                put("parsed_unit", job.parsedUnit ?: "")
                put("parsed_total", job.parsedTotal)
                put("is_sanity_flagged", job.isSanityFlagged)
                put("error_message", job.errorMessage ?: "")
                // ISSUE-044: a blank/empty client-side trace must never clobber a
                // populated server trace on this upsert -- omitting the key leaves
                // whatever the server already wrote for this job_id untouched, instead
                // of upserting "{}" over it. See Docs/assistant_speed_and_pipeline_fix_plan.md §1.2.
                val traceStr = traceObj.toString()
                if (traceStr.isNotBlank() && traceStr != "{}") {
                    put("diagnostic_trace_json", traceStr)
                }
                if (job.rawTranscript.isNotBlank()) {
                    put("raw_transcript", job.rawTranscript)
                }
                put("audio_cloud_url", "$supabaseUrl/storage/v1/object/public/voice-recordings/${job.id}.wav")
            }

            val responseCode = upsertToRestApi("/rest/v1/stt_job_logs", payload, onConflictColumn = "job_id")
            if (responseCode in 200..299) {
                Log.i(TAG, "✅ Posted job trace log ${job.id} to Supabase stt_job_logs")
                return true
            } else {
                Log.w(TAG, "❌ stt_job_logs insert HTTP $responseCode for job ${job.id}")
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post trace log: ${e.message}")
            return false
        }
    }

    private fun upsertToRestApi(path: String, payload: JSONObject, onConflictColumn: String? = "id"): Int {
        val fullPath = if (onConflictColumn != null) {
            if (path.contains("?")) "$path&on_conflict=$onConflictColumn" else "$path?on_conflict=$onConflictColumn"
        } else path

        val url = URL("$supabaseUrl$fullPath")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $anonKey")
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        DataOutputStream(connection.outputStream).use { os ->
            os.write(bytes)
            os.flush()
        }
        return connection.responseCode
    }

    /**
     * Fetches the shop's active catalog FROM Supabase — the only server→client read path in
     * the app.
     *
     * Everything else here is push-only (see `SyncEngine`): the cloud is a mirror, not a
     * second source of truth. This one exception exists because two server-side code paths
     * write `catalog_items` directly and the phone had no way to ever learn about them:
     *
     *  1. Catalog-Learning-From-History (ISSUE-033) auto-adds a recurring unmatched item at
     *     price 0, so the item stops being "unknown" — but only server-side. Without this
     *     pull, the phone kept treating it as unknown forever, which defeats the feature.
     *  2. A spoken RATE_UPDATE applies `catalog_items.price` server-side (ISSUE-026). The
     *     phone's local price silently stayed stale — a pre-existing bug this also fixes.
     *
     * Returns null on any failure (offline, HTTP error, malformed body) so the caller can
     * distinguish "fetch failed, change nothing" from "server genuinely has zero items" —
     * conflating those would let a transient network error look like an empty catalog.
     */
    /**
     * Reads this shop's recent job logs back from `stt_job_logs` for DISPLAY ONLY.
     *
     * Sync in this app is push-only, so the Diagnostic Logs screen could previously show
     * nothing but recordings made on this handset -- a shop that reinstalled, or a job the
     * server processed while the app was closed, was simply invisible. Observed 2026-08-06:
     * 250+ jobs on the server while the phone's screen was empty, which reads as "logging is
     * broken" when it is working perfectly.
     *
     * The returned records are deliberately NOT written to Room. `stt_jobs` doubles as the
     * work queue -- `SttWorker` drains rows in QUEUED/TRANSCRIBING -- so persisting a remote
     * row would hand the worker a job whose audio this device does not have. Display-only
     * keeps the queue's meaning intact.
     */
    suspend fun fetchJobLogsFromCloud(shopId: String?, limit: Int = 100): List<SttJobRecord>? =
        withContext(Dispatchers.IO) {
            try {
                val safeShopId = SupabaseConfig.getNullSafeShopId(shopId)
                val shopFilter = safeShopId?.let { "&shop_id=eq.$it" } ?: ""
                val url = URL(
                    "$supabaseUrl/rest/v1/stt_job_logs?select=job_id,status,raw_transcript," +
                        "parsed_item_name,parsed_qty,parsed_unit,parsed_total,is_sanity_flagged," +
                        "error_message,recorded_at_ms,hold_duration_ms,diagnostic_trace_json," +
                        "line_count$shopFilter&order=recorded_at_ms.desc&limit=$limit"
                )
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Authorization", "Bearer $anonKey")
                    setRequestProperty("apikey", anonKey)
                    setRequestProperty("Accept", "application/json")
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "❌ Job log pull HTTP $code")
                    return@withContext null
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val arr = org.json.JSONArray(body)
                val out = ArrayList<SttJobRecord>(arr.length())
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONObject(i) ?: continue
                    val id = row.optString("job_id", "")
                    if (id.isBlank()) continue
                    val status = runCatching {
                        SttJobStatus.valueOf(row.optString("status", "PARSED"))
                    }.getOrDefault(SttJobStatus.PARSED)
                    out.add(
                        SttJobRecord(
                            id = id,
                            // Remote jobs have no local audio file; the card falls back to the
                            // cloud URL for playback.
                            audioFilePath = "",
                            status = status,
                            rawTranscript = row.optString("raw_transcript", ""),
                            parsedItemName = row.optString("parsed_item_name", ""),
                            parsedQty = row.optDouble("parsed_qty", 1.0),
                            parsedUnit = row.optString("parsed_unit", "PACKET"),
                            parsedTotal = row.optDouble("parsed_total", 0.0),
                            isSanityFlagged = row.optBoolean("is_sanity_flagged", false),
                            errorMessage = row.optString("error_message", ""),
                            recordedAtMs = row.optLong("recorded_at_ms", 0L),
                            holdDurationMs = row.optLong("hold_duration_ms", 0L),
                            diagnosticTraceJson = row.optString("diagnostic_trace_json", ""),
                            lineCount = row.optInt("line_count", 0),
                            synced = true
                        )
                    )
                }
                Log.i(TAG, "⬇️ Pulled ${out.size} job log(s) from cloud for display")
                out
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pull job logs from cloud: ${e.message}")
                null
            }
        }

    suspend fun fetchCatalogFromCloud(): List<CatalogItem>? = withContext(Dispatchers.IO) {
        try {
            // Scoped to THIS shop. The pull previously asked for every active row in the table
            // regardless of tenant, so the first time a second shop existed this device would
            // have merged that shop's items (and prices) straight into its own catalog.
            val shopFilter = SupabaseConfig.getNullSafeShopId(
                com.voicetoinvoice.app.data.ShopContext.shopIdOrNull()
            )?.let { "&shop_id=eq.$it" } ?: ""
            val url = URL(
                "$supabaseUrl/rest/v1/catalog_items?active=eq.true$shopFilter" +
                    "&select=id,name,unit_id,price,active,image_url,updated_at"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Authorization", "Bearer $anonKey")
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Accept", "application/json")
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "❌ Catalog pull HTTP $code")
                return@withContext null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val arr = org.json.JSONArray(body)
            val out = ArrayList<CatalogItem>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val id = row.optString("id", "")
                val name = row.optString("name", "")
                if (id.isBlank() || name.isBlank()) continue
                val rawImageUrl = row.optString("image_url", "").ifBlank { null }
                out.add(
                    CatalogItem(
                        id = id,
                        name = name,
                        unitId = row.optString("unit_id", "KG").ifBlank { "KG" },
                        price = row.optDouble("price", 0.0),
                        active = row.optBoolean("active", true),
                        imageUrl = rawImageUrl,
                        updatedAt = parseIsoTimestamp(row.optString("updated_at", "")),
                        // Straight from the server, so by definition already in sync —
                        // marking it unsynced would bounce it right back on the next push.
                        synced = true
                    )
                )
            }
            Log.i(TAG, "⬇️ Pulled ${out.size} catalog item(s) from Supabase")
            out
        } catch (e: Exception) {
            Log.w(TAG, "Catalog pull failed (offline or error): ${e.message}")
            null
        }
    }

    /**
     * Parses PostgREST's `timestamptz` rendering to epoch millis, falling back to 0 rather
     * than to "now" — a row whose timestamp we cannot read must never win a
     * last-write-wins comparison against a local edit it might actually be older than.
     */
    private fun parseIsoTimestamp(raw: String): Long {
        if (raw.isBlank()) return 0L
        // Postgres emits a variable number of fractional-second digits and either a "Z" or
        // a "+00:00" offset, so try the realistic shapes rather than assuming one.
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (p in patterns) {
            try {
                val sdf = java.text.SimpleDateFormat(p, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                return sdf.parse(raw)?.time ?: continue
            } catch (_: Exception) { /* try the next shape */ }
        }
        Log.w(TAG, "Unparseable updated_at '$raw' — treating as epoch 0")
        return 0L
    }

    private fun String?.isNull_or_empty(): Boolean = this == null || this.trim().isEmpty()
}

