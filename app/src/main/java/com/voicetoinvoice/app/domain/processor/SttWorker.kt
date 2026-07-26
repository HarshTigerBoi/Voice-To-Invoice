package com.voicetoinvoice.app.domain.processor

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.PaymentMode
import com.voicetoinvoice.app.data.local.entity.SttJobStatus
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.data.local.entity.TransactionSource
import com.voicetoinvoice.app.network.CloudSyncManager
import com.voicetoinvoice.app.network.SupabaseConfig
import com.voicetoinvoice.app.util.LocalStorageCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

class SttWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_AUDIO_PATH = "audio_path"
        private const val TAG = "SttWorker"
    }

    private val db = AppDatabase.getInstance(context)
    private val cloudSyncManager = CloudSyncManager()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()
        val audioPath = inputData.getString(KEY_AUDIO_PATH) ?: return@withContext Result.failure()

        Log.i(TAG, "Starting Expedited SttWorker for job $jobId ($audioPath)")

        val jobRecord = db.sttJobDao().getJobById(jobId)
            ?: return@withContext Result.failure()

        val audioFile = File(audioPath)
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.w(TAG, "Audio file missing or empty for job $jobId")
            db.sttJobDao().updateJob(
                jobRecord.copy(
                    status = SttJobStatus.PARSED,
                    rawTranscript = "Voice Recording (Audio file missing)",
                    parsedItemName = "Unrecognized Item",
                    isSanityFlagged = true,
                    errorMessage = "Audio file missing on disk"
                )
            )
            return@withContext Result.failure()
        }

        // Fetch active shop catalog names
        val catalog = db.catalogDao().getActiveCatalogList()
        val catalogNames = catalog.map { it.name }

        // Construct PTT Metadata JSON
        val metadataJson = JSONObject().apply {
            put("jobId", jobId)
            put("recordedAtMs", jobRecord.recordedAtMs)
            put("pressStartMs", jobRecord.pressStartMs)
            put("releaseMs", jobRecord.releaseMs)
            put("holdDurationMs", jobRecord.holdDurationMs)
            put("audioStartMs", jobRecord.audioStartMs)
            put("audioEndMs", jobRecord.audioEndMs)
        }

        // HTTP Multipart POST to /functions/v1/process-voice-job
        try {
            db.sttJobDao().updateJob(jobRecord.copy(status = SttJobStatus.TRANSCRIBING))

            // Re-read job record with bounded timeout to allow onDeviceTranscript backfill if pending
            val updatedJob = kotlinx.coroutines.withTimeoutOrNull(1000L) {
                var current = db.sttJobDao().getJobById(jobId)
                val start = System.currentTimeMillis()
                while (current != null && current.onDeviceTranscript.isBlank() && System.currentTimeMillis() - start < 1000L) {
                    kotlinx.coroutines.delay(100L)
                    current = db.sttJobDao().getJobById(jobId)
                }
                current
            } ?: jobRecord

            val endpointUrl = "${SupabaseConfig.SUPABASE_URL}/functions/v1/process-voice-job"
            val boundary = "Boundary-${System.currentTimeMillis()}"
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val url = URL(endpointUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                doInput = true
                doOutput = true
                useCaches = false
                requestMethod = "POST"
                connectTimeout = 30000
                readTimeout = 60000
                setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
                setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
                setRequestProperty("Connection", "Keep-Alive")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(connection.outputStream).use { output ->
                fun writeString(str: String) {
                    output.write(str.toByteArray(Charsets.UTF_8))
                }

                // Field 1: jobId
                writeString("$twoHyphens$boundary$lineEnd")
                writeString("Content-Disposition: form-data; name=\"jobId\"$lineEnd$lineEnd")
                writeString("$jobId$lineEnd")

                // Field 2: catalogNames
                if (catalogNames.isNotEmpty()) {
                    writeString("$twoHyphens$boundary$lineEnd")
                    writeString("Content-Disposition: form-data; name=\"catalogNames\"$lineEnd$lineEnd")
                    writeString("${JSONArray(catalogNames)}$lineEnd")
                }

                // Field 3: metadata
                writeString("$twoHyphens$boundary$lineEnd")
                writeString("Content-Disposition: form-data; name=\"metadata\"$lineEnd$lineEnd")
                writeString("${metadataJson}$lineEnd")

                // Field 3.5: shopId
                writeString("$twoHyphens$boundary$lineEnd")
                writeString("Content-Disposition: form-data; name=\"shopId\"$lineEnd$lineEnd")
                writeString("11111111-1111-1111-1111-111111111111$lineEnd")

                // Field 3.6: onDeviceTranscript
                if (updatedJob.onDeviceTranscript.isNotBlank()) {
                    writeString("$twoHyphens$boundary$lineEnd")
                    writeString("Content-Disposition: form-data; name=\"onDeviceTranscript\"$lineEnd$lineEnd")
                    writeString("${updatedJob.onDeviceTranscript}$lineEnd")
                }

                // Field 3.7: previousJobId
                if (updatedJob.previousJobId != null) {
                    writeString("$twoHyphens$boundary$lineEnd")
                    writeString("Content-Disposition: form-data; name=\"previousJobId\"$lineEnd$lineEnd")
                    writeString("${updatedJob.previousJobId}$lineEnd")
                }

                // Field 3.8: precedingGapMs
                if (updatedJob.precedingGapMs >= 0L) {
                    writeString("$twoHyphens$boundary$lineEnd")
                    writeString("Content-Disposition: form-data; name=\"precedingGapMs\"$lineEnd$lineEnd")
                    writeString("${updatedJob.precedingGapMs}$lineEnd")
                }

                // Field 4: file
                writeString("$twoHyphens$boundary$lineEnd")
                writeString("Content-Disposition: form-data; name=\"file\"; filename=\"$jobId.wav\"$lineEnd")
                writeString("Content-Type: audio/wav$lineEnd$lineEnd")

                FileInputStream(audioFile).use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
                writeString(lineEnd)
                writeString("$twoHyphens$boundary$twoHyphens$lineEnd")
                output.flush()
            }

            val responseCode = connection.responseCode
            val responseString = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            Log.d(TAG, "Server response code: $responseCode, Body: $responseString")

            if (responseCode in 200..299 && responseString.isNotBlank()) {
                val json = JSONObject(responseString)
                var statusStr = json.optString("status", "QUEUED")
                var rawTranscript = json.optString("raw_transcript", "")
                var traceJson = json.optString("diagnostic_trace_json", "")
                var parsedItems = json.optJSONArray("parsed_items") ?: JSONArray()

                // If job was QUEUED by server, poll stt_job_logs for up to 30s until background task completes
                if (statusStr == "QUEUED") {
                    val pollUrl = "${SupabaseConfig.SUPABASE_URL}/rest/v1/stt_job_logs?job_id=eq.$jobId&select=status,raw_transcript,parsed_item_name,parsed_qty,parsed_unit,parsed_total,diagnostic_trace_json"
                    val maxPollMs = 30000L
                    val pollIntervalMs = 2000L
                    val startTime = System.currentTimeMillis()

                    while (System.currentTimeMillis() - startTime < maxPollMs) {
                        kotlinx.coroutines.delay(pollIntervalMs)
                        try {
                            val pollConn = (URL(pollUrl).openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                connectTimeout = 5000
                                readTimeout = 5000
                                setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
                                setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
                            }
                            if (pollConn.responseCode in 200..299) {
                                val pollBody = pollConn.inputStream.bufferedReader().use { it.readText() }
                                val pollArr = JSONArray(pollBody)
                                if (pollArr.length() > 0) {
                                    val row = pollArr.getJSONObject(0)
                                    val polledStatus = row.optString("status", "QUEUED")
                                    if (polledStatus != "QUEUED") {
                                        statusStr = polledStatus
                                        rawTranscript = row.optString("raw_transcript", "")
                                        traceJson = row.optString("diagnostic_trace_json", "")

                                        // Extract parsed_items from diagnostic_trace_json if available
                                        if (traceJson.isNotBlank()) {
                                            try {
                                                val traceObj = JSONObject(traceJson)
                                                if (traceObj.has("step_4_grok_ai_interpretation")) {
                                                    parsedItems = traceObj.getJSONArray("step_4_grok_ai_interpretation")
                                                }
                                            } catch (_: Exception) {}
                                        }

                                        // Fallback if step_4_grok_ai_interpretation was empty but row has parsed columns
                                        if (parsedItems.length() == 0 && row.has("parsed_item_name")) {
                                            val singleItem = JSONObject().apply {
                                                put("item_name", row.optString("parsed_item_name", "Unrecognized Item"))
                                                put("quantity", row.optDouble("parsed_qty", 1.0))
                                                put("unit", row.optString("parsed_unit", "PACKET"))
                                                put("price", row.optDouble("parsed_total", 0.0))
                                            }
                                            parsedItems = JSONArray().put(singleItem)
                                        }

                                        Log.d(TAG, "Polled final resolution for job $jobId: status=$statusStr, items=${parsedItems.length()}")
                                        break
                                    }
                                }
                            }
                        } catch (pollErr: Exception) {
                            Log.w(TAG, "Polling exception for job $jobId: ${pollErr.message}")
                        }
                    }
                }

                // Recover items from diagnostic_trace_json when the server responded via its
                // "already processed" cache path (hit on any WorkManager retry for a jobId the
                // server finished before this attempt). That response has no top-level
                // parsed_items and statusStr != "QUEUED", so the polling block above never runs
                // and parsedItems is left empty here — without this, an AUTO_CONFIRMED job would
                // record zero transactions and the sale would silently vanish from the ledger.
                if (parsedItems.length() == 0 && traceJson.isNotBlank()) {
                    try {
                        val traceObj = JSONObject(traceJson)
                        if (traceObj.has("step_4_grok_ai_interpretation")) {
                            val extracted = traceObj.getJSONArray("step_4_grok_ai_interpretation")
                            if (extracted.length() > 0) parsedItems = extracted
                        }
                    } catch (_: Exception) {}
                }

                val isAutoConfirmed = statusStr == "AUTO_CONFIRMED"

                // Save transactions locally to Room DB ONLY if auto-confirmed (confidence >= 0.80)
                if (isAutoConfirmed) {
                    for (i in 0 until parsedItems.length()) {
                        val itemObj = parsedItems.getJSONObject(i)
                        val itemName = itemObj.optString("item_name", "Unrecognized Item")
                        val qty = itemObj.optDouble("quantity", 1.0)
                        val unit = itemObj.optString("unit", "PACKET")
                        val price = itemObj.optDouble("price_at_sale", itemObj.optDouble("price", 0.0))

                        val matchedCatalog = catalog.find { it.name.equals(itemName, ignoreCase = true) }

                        val txRecord = TransactionRecord(
                            itemId = matchedCatalog?.id ?: "unlisted-$jobId",
                            itemName = matchedCatalog?.name ?: itemName,
                            quantity = qty,
                            priceAtSale = matchedCatalog?.price ?: price,
                            total = qty * (matchedCatalog?.price ?: price),
                            paymentMode = PaymentMode.CASH,
                            source = TransactionSource.VOICE,
                            rawTranscript = rawTranscript,
                            audioFilePath = audioPath,
                            jobId = jobId,
                            audioCloudUrl = "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/public/voice-recordings/$jobId.wav"
                        )
                        db.transactionDao().insert(txRecord)
                        cloudSyncManager.syncTransactionToCloud(txRecord)
                    }
                } else {
                    // Insert into local Room unmatched_queue table so PARSED items appear in Unmatched Queue UI
                    val unmatchedItem = com.voicetoinvoice.app.data.local.entity.UnmatchedQueueItem(
                        id = jobId,
                        shopId = SupabaseConfig.getNullSafeShopId(null),
                        audioRef = "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/public/voice-recordings/$jobId.wav",
                        rawTranscript = rawTranscript,
                        status = com.voicetoinvoice.app.data.local.entity.UnmatchedStatus.PENDING,
                        timestamp = System.currentTimeMillis()
                    )
                    db.unmatchedQueueDao().insert(unmatchedItem)
                    cloudSyncManager.syncReviewItemToCloud(unmatchedItem)
                    Log.d(TAG, "Inserted pending review item into local unmatched_queue for job $jobId")
                }

                val firstItem = if (parsedItems.length() > 0) parsedItems.getJSONObject(0) else null
                val updatedStatus = if (isAutoConfirmed) SttJobStatus.AUTO_CONFIRMED else SttJobStatus.PARSED

                db.sttJobDao().updateJob(
                    jobRecord.copy(
                        status = updatedStatus,
                        rawTranscript = rawTranscript,
                        parsedItemName = firstItem?.optString("item_name") ?: "Unrecognized Item",
                        parsedQty = firstItem?.optDouble("quantity") ?: 1.0,
                        parsedUnit = firstItem?.optString("unit") ?: "PACKET",
                        parsedTotal = firstItem?.optDouble("price_at_sale") ?: firstItem?.optDouble("price") ?: 0.0,
                        isSanityFlagged = !isAutoConfirmed,
                        diagnosticTraceJson = traceJson,
                        synced = true
                    )
                )

                // Keep audio file on phone for review, governed by 500 MB storage cap
                LocalStorageCleaner.cleanOldRecordingsIfExceedsLimit(context)

                return@withContext Result.success()
            } else {
                Log.e(TAG, "Server returned error $responseCode: $responseString")
                return@withContext Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "SttWorker network execution failed", e)
            return@withContext Result.retry()
        }
    }
}
