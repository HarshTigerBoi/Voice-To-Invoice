package com.voicetoinvoice.app.network

import android.util.Log
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.CreditRecord
import com.voicetoinvoice.app.data.local.entity.StockInRecord
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
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
                if (!transaction.jobId.isNull_or_empty()) put("job_id", transaction.jobId)
                if (!transaction.audioCloudUrl.isNull_or_empty()) {
                    put("audio_cloud_url", transaction.audioCloudUrl)
                } else if (!transaction.jobId.isNull_or_empty()) {
                    put("audio_cloud_url", "$supabaseUrl/storage/v1/object/public/voice-recordings/${transaction.jobId}.wav")
                }
            }
            val resCode = upsertToRestApi("/rest/v1/transactions", payload)
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
                put("name", item.name)
                put("unit_id", item.unitId)
                put("price", item.price)
                put("active", item.active)
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
     * Syncs a stock-in record to Supabase 'stock_in' table.
     */
    suspend fun syncStockInToCloud(stockIn: StockInRecord): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", stockIn.id)
                put("item_name", stockIn.itemName)
                put("quantity", stockIn.quantity)
                put("cost_price", stockIn.costPrice)
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
                put("raw_transcript", job.rawTranscript ?: "")
                put("parsed_item_name", job.parsedItemName ?: "")
                put("parsed_qty", job.parsedQty)
                put("parsed_unit", job.parsedUnit ?: "")
                put("parsed_total", job.parsedTotal)
                put("is_sanity_flagged", job.isSanityFlagged)
                put("error_message", job.errorMessage ?: "")
                put("diagnostic_trace_json", traceObj.toString())
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

    private fun String?.isNull_or_empty(): Boolean = this == null || this.trim().isEmpty()
}

