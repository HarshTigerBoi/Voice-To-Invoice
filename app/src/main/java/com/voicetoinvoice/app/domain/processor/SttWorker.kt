package com.voicetoinvoice.app.domain.processor

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CaptureIntent
import com.voicetoinvoice.app.data.local.entity.CreditRecord
import com.voicetoinvoice.app.data.local.entity.CreditStatus
import com.voicetoinvoice.app.data.local.entity.PaymentMode
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
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
    private val stockLedger = com.voicetoinvoice.app.data.repository.StockLedgerRepository(db)
    private val handlers = com.voicetoinvoice.app.domain.action.VoiceCommandHandlers(db, stockLedger)
    private val shopLearning = com.voicetoinvoice.app.data.repository.ShopLearningRepository(db)

    /**
     * WorkManager can start this worker with the Activity dead, so this is a cold entry point
     * for shop identity -- it must resolve it itself rather than assuming MainActivity already
     * did. See `ShopContext`.
     */
    private val shopId: String = com.voicetoinvoice.app.data.ShopContext.initialize(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()
        val audioPath = inputData.getString(KEY_AUDIO_PATH) ?: return@withContext Result.failure()

        Log.i(TAG, "Starting Expedited SttWorker for job $jobId ($audioPath)")

        val jobRecord = db.sttJobDao().getJobById(jobId)
            ?: return@withContext Result.failure()

        // §1.3 (Docs/assistant_speed_and_pipeline_fix_plan.md): a client-authored trace
        // that is written unconditionally, on every exit path -- unlike the server trace,
        // this can never come back empty, so a job that never reaches the server (network
        // failure, upload exception, vanished server row) still leaves a record of what
        // was actually tried.
        val clientTrace = JSONObject().apply {
            put("client_started_at", System.currentTimeMillis())
            put("capture_intent", jobRecord.captureIntent.name)
            put("audio_path", audioPath)
            put("attempt", runAttemptCount)
        }

        val audioFile = File(audioPath)
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.w(TAG, "Audio file missing or empty for job $jobId")
            clientTrace.put("outcome", "audio_file_missing")
            db.sttJobDao().updateJob(
                jobRecord.copy(
                    status = SttJobStatus.PARSED,
                    rawTranscript = "Voice Recording (Audio file missing)",
                    parsedItemName = "Unrecognized Item",
                    isSanityFlagged = true,
                    errorMessage = "Audio file missing on disk",
                    diagnosticTraceJson = mergeClientTrace(clientTrace, null)
                )
            )
            return@withContext Result.failure()
        }
        clientTrace.put("audio_bytes", audioFile.length())

        val catalog = db.catalogDao().getActiveCatalogList()
        val catalogNames = catalog.map { it.name }

        val metadataJson = JSONObject().apply {
            put("jobId", jobId)
            put("recordedAtMs", jobRecord.recordedAtMs)
            put("pressStartMs", jobRecord.pressStartMs)
            put("releaseMs", jobRecord.releaseMs)
            put("holdDurationMs", jobRecord.holdDurationMs)
            put("audioStartMs", jobRecord.audioStartMs)
            put("audioEndMs", jobRecord.audioEndMs)
            put("pressCount", jobRecord.pressCount)
            try {
                put("utteranceBoundaries", org.json.JSONArray(jobRecord.utteranceBoundariesJson))
            } catch (e: Exception) {
                put("utteranceBoundaries", org.json.JSONArray())
            }
        }

        try {
            db.sttJobDao().updateJob(jobRecord.copy(status = SttJobStatus.TRANSCRIBING))

            val boundedJob = kotlinx.coroutines.withTimeoutOrNull(1800L) {
                var current = db.sttJobDao().getJobById(jobId)
                val start = System.currentTimeMillis()
                while (current != null && current.onDeviceStatus.isBlank() && System.currentTimeMillis() - start < 1800L) {
                    kotlinx.coroutines.delay(100L)
                    current = db.sttJobDao().getJobById(jobId)
                }
                current
            } ?: jobRecord

            val uploadStartMs = System.currentTimeMillis()
            val responseString = uploadAudioToEdgeFunction(
                audioFile = audioFile,
                jobId = jobId,
                shopId = SupabaseConfig.getNullSafeShopId(com.voicetoinvoice.app.data.ShopContext.requireShopId()),
                metadataJson = metadataJson,
                catalogNames = catalogNames,
                onDeviceTranscript = boundedJob.onDeviceTranscript,
                onDeviceStatus = boundedJob.onDeviceStatus,
                previousJobId = jobRecord.previousJobId,
                precedingGapMs = jobRecord.precedingGapMs,
                captureIntent = jobRecord.captureIntent.name
            )
            clientTrace.put("upload_ms", System.currentTimeMillis() - uploadStartMs)
            clientTrace.put("upload_response_null", responseString == null)

            if (responseString != null) {
                val jsonRes = JSONObject(responseString)
                var statusStr = jsonRes.optString("status", "QUEUED")
                var rawTranscript = jsonRes.optString("raw_transcript", "")
                var traceJson = jsonRes.optString("diagnostic_trace_json", "")
                var parsedItems = jsonRes.optJSONArray("parsed_items") ?: JSONArray()
                clientTrace.put("initial_status", statusStr)

                if (statusStr == "QUEUED") {
                    val pollStartMs = System.currentTimeMillis()
                    val polled = pollForCompletion(jobId)
                    clientTrace.put("poll_ms", System.currentTimeMillis() - pollStartMs)
                    clientTrace.put("poll_final_status", polled.status)
                    clientTrace.put("poll_timed_out", polled.status == "QUEUED")
                    statusStr = polled.status
                    if (polled.rawTranscript.isNotBlank()) rawTranscript = polled.rawTranscript
                    if (polled.traceJson.isNotBlank()) traceJson = polled.traceJson
                    if (polled.parsedItems.length() > 0) parsedItems = polled.parsedItems
                }

                if (parsedItems.length() == 0 && traceJson.isNotBlank()) {
                    try {
                        val traceObj = JSONObject(traceJson)
                        if (traceObj.has("step_4_grok_ai_interpretation")) {
                            val extracted = traceObj.getJSONArray("step_4_grok_ai_interpretation")
                            if (extracted.length() > 0) parsedItems = extracted
                        }
                    } catch (_: Exception) {}
                }

                // Commands COMMIT in the order they were spoken, even though this job's own
                // network round-trip may have finished before or after a sibling recording's --
                // see CommitSequencer's header for why arrival order is not commit order.
                // Zero added latency in the common case (nothing else in flight).
                if (jobRecord.captureIntent == CaptureIntent.ASSISTANT) {
                    return@withContext com.voicetoinvoice.app.domain.processor.CommitSequencer.runInOrder(
                        db, jobRecord.recordedAtMs, clientTrace
                    ) {
                        handleAssistantJob(
                            jobRecord = jobRecord,
                            rawTranscript = rawTranscript,
                            parsedItems = parsedItems,
                            traceJson = traceJson,
                            catalog = catalog,
                            audioPath = audioPath,
                            clientTrace = clientTrace
                        )
                    }
                }

                val audioCloudUrl = "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/public/voice-recordings/$jobId.wav"
                val lineCount = parsedItems.length()

                val committedCount = com.voicetoinvoice.app.domain.processor.CommitSequencer.runInOrder(
                    db, jobRecord.recordedAtMs, clientTrace
                ) {
                    commitParsedLines(
                        jobRecord = jobRecord,
                        effectiveIntent = jobRecord.captureIntent,
                        parsedItems = parsedItems,
                        catalog = catalog,
                        rawTranscript = rawTranscript,
                        audioPath = audioPath,
                        audioCloudUrl = audioCloudUrl,
                        jobId = jobId
                    )
                }

                Log.d(TAG, "Job $jobId processed $lineCount line(s), committed=$committedCount, status=$statusStr")
                clientTrace.put("outcome", "processed")
                clientTrace.put("line_count", lineCount)
                clientTrace.put("committed_count", committedCount)

                val firstItem = if (lineCount > 0) parsedItems.getJSONObject(0) else null
                val updatedStatus = when (statusStr) {
                    "AUTO_CONFIRMED" -> SttJobStatus.AUTO_CONFIRMED
                    "PARTIALLY_CONFIRMED" -> SttJobStatus.PARTIALLY_CONFIRMED
                    "RATE_UPDATED" -> SttJobStatus.RATE_UPDATED
                    else -> SttJobStatus.PARSED
                }

                db.sttJobDao().updateJob(
                    jobRecord.copy(
                        status = updatedStatus,
                        rawTranscript = rawTranscript,
                        parsedItemName = firstItem?.optString("item_name") ?: "Unrecognized Item",
                        parsedQty = firstItem?.optDouble("quantity") ?: 1.0,
                        parsedUnit = firstItem?.optString("unit") ?: "PACKET",
                        parsedTotal = firstItem?.optDouble("total") ?: 0.0,
                        isSanityFlagged = updatedStatus != SttJobStatus.AUTO_CONFIRMED && updatedStatus != SttJobStatus.RATE_UPDATED,
                        diagnosticTraceJson = mergeClientTrace(clientTrace, traceJson),
                        parsedItemsJson = parsedItems.toString(),
                        lineCount = lineCount,
                        synced = true
                    )
                )

                LocalStorageCleaner.cleanOldRecordingsIfExceedsLimit(context)

                return@withContext Result.success()
            } else {
                Log.e(TAG, "Server returned error response")
                clientTrace.put("outcome", "upload_returned_null")
                db.sttJobDao().updateJob(
                    jobRecord.copy(
                        status = SttJobStatus.TRANSCRIBING,
                        diagnosticTraceJson = mergeClientTrace(clientTrace, null)
                    )
                )
                return@withContext Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "SttWorker network execution failed", e)
            clientTrace.put("outcome", "exception")
            clientTrace.put("exception_class", e.javaClass.simpleName)
            clientTrace.put("exception_message", e.message ?: "")
            try {
                db.sttJobDao().updateJob(
                    jobRecord.copy(
                        status = SttJobStatus.TRANSCRIBING,
                        diagnosticTraceJson = mergeClientTrace(clientTrace, null)
                    )
                )
            } catch (persistErr: Exception) {
                Log.e(TAG, "Failed to persist client trace after exception", persistErr)
            }
            return@withContext Result.retry()
        }
    }

    /** Merges the client-authored trace (always present, §1.3) with the server's own
     *  step_1..step_6 trace (often present, occasionally blank when the server round-trip
     *  failed) under separate top-level keys, so neither ever silently overwrites the
     *  other -- see Docs/assistant_speed_and_pipeline_fix_plan.md §1. */
    private fun mergeClientTrace(clientTrace: JSONObject, serverTraceJson: String?): String {
        val merged = JSONObject()
        merged.put("client", clientTrace)
        if (!serverTraceJson.isNullOrBlank()) {
            try {
                merged.put("server", JSONObject(serverTraceJson))
            } catch (_: Exception) {
                merged.put("server_raw", serverTraceJson)
            }
        }
        return merged.toString()
    }

    /** Books one job's parsed lines. `effectiveIntent` is jobRecord.captureIntent for a
     *  normal mic press, and the assistant's routed intent for an ASSISTANT job.
     *  Returns the number of lines that were committed (not sent to review). */
    private suspend fun commitParsedLines(
        jobRecord: SttJobRecord,
        effectiveIntent: CaptureIntent,
        parsedItems: JSONArray,
        catalog: List<com.voicetoinvoice.app.data.local.entity.CatalogItem>,
        rawTranscript: String,
        audioPath: String,
        audioCloudUrl: String,
        jobId: String
    ): Int {
        var committedCount = 0
        val lineCount = parsedItems.length()
        for (i in 0 until lineCount) {
            val itemObj = parsedItems.getJSONObject(i)
            val itemName = itemObj.optString("item_name", "Unrecognized Item")
            val qty = itemObj.optDouble("quantity", 1.0)
            val unit = itemObj.optString("unit", "PACKET")
            val priceAtSale = itemObj.optDouble("price_at_sale", 0.0)
            val total = itemObj.optDouble("total", 0.0)
            val priceIntent = itemObj.optString("price_intent", "NONE")
            val confidence = itemObj.optDouble("confidence", 0.0)
            val itemId = if (itemObj.isNull("item_id")) null else itemObj.optString("item_id", "").ifBlank { null }
            val implausibilityReason = if (itemObj.isNull("implausibility_reason")) null else itemObj.optString("implausibility_reason", "").ifBlank { null }

            val isValidRateUpdate = priceIntent == "RATE_UPDATE" &&
                itemId != null && confidence >= 0.80 && priceAtSale > 0.0 && implausibilityReason == null
            val isCommittableSale = priceIntent != "RATE_UPDATE" &&
                confidence >= 0.80 && priceAtSale > 0.0 && total > 0.0 &&
                itemName.isNotBlank() && itemName != "Unrecognized Item" && implausibilityReason == null

            val isCommittableStock = confidence >= 0.80 && qty > 0.0 &&
                itemName.isNotBlank() && itemName != "Unrecognized Item" && implausibilityReason == null
            val isStockIntent = effectiveIntent == CaptureIntent.STOCK_IN || effectiveIntent == CaptureIntent.WASTE

            when {
                isValidRateUpdate -> {
                    db.catalogDao().updatePrice(itemId, priceAtSale)
                    committedCount++
                }
                isStockIntent && isCommittableStock -> {
                    val matchedCatalog = catalog.find { it.id == itemId }
                        ?: catalog.find { it.name.equals(itemName, ignoreCase = true) }
                        // This shop's own confirmed vocabulary, e.g. it previously confirmed
                        // that "TMTR" means this catalog row even though the name never
                        // matches by substring. The highest-value training signal in the
                        // system (a review-queue confirmation) was previously thrown away;
                        // this is where it gets read back. See ShopLearningRepository.
                        ?: shopLearning.resolveItemAlias(itemName)?.let { aliasId -> catalog.find { c -> c.id == aliasId } }
                    val resolvedItemId = matchedCatalog?.id ?: itemId ?: "unlisted-$jobId"
                    val resolvedItemName = matchedCatalog?.name ?: itemName
                    val isWaste = effectiveIntent == CaptureIntent.WASTE

                    if (isWaste) {
                        // Spoilage is NOT a purchase. It used to be written as a negative
                        // `stock_in.quantity`, which made "bought" and "spoiled" indistinguishable
                        // in one column and destroyed both purchase totals and waste reporting.
                        // It is now purely a stock movement.
                        stockLedger.record(
                            itemId = resolvedItemId,
                            itemName = resolvedItemName,
                            deltaQty = -Math.abs(qty),
                            reason = com.voicetoinvoice.app.data.local.entity.StockReason.WASTE,
                            refId = "$jobId-$i",
                            note = "voice: ${rawTranscript.take(120)}"
                        )
                    } else {
                        val stockRecord = com.voicetoinvoice.app.data.local.entity.StockInRecord(
                            shopId = shopId,
                            itemId = resolvedItemId,
                            itemName = resolvedItemName,
                            quantity = Math.abs(qty),
                            costPrice = if (priceAtSale > 0.0) priceAtSale else 0.0,
                            costMissing = !(priceAtSale > 0.0)
                        )
                        db.stockInDao().insert(stockRecord)
                        // Purchase history stays in `stock_in`; the quantity effect and the
                        // weighted-average cost update happen in the ledger.
                        stockLedger.record(
                            itemId = resolvedItemId,
                            itemName = resolvedItemName,
                            deltaQty = Math.abs(qty),
                            reason = com.voicetoinvoice.app.data.local.entity.StockReason.STOCK_IN,
                            unitCost = priceAtSale.takeIf { it > 0.0 },
                            refId = stockRecord.id
                        )
                        cloudSyncManager.syncStockInToCloud(stockRecord)
                    }
                    committedCount++
                }
                !isStockIntent && isCommittableSale -> {
                    val matchedCatalog = catalog.find { it.id == itemId }
                        ?: catalog.find { it.name.equals(itemName, ignoreCase = true) }
                        // This shop's own confirmed vocabulary, e.g. it previously confirmed
                        // that "TMTR" means this catalog row even though the name never
                        // matches by substring. The highest-value training signal in the
                        // system (a review-queue confirmation) was previously thrown away;
                        // this is where it gets read back. See ShopLearningRepository.
                        ?: shopLearning.resolveItemAlias(itemName)?.let { aliasId -> catalog.find { c -> c.id == aliasId } }
                    val resolvedItemId = matchedCatalog?.id ?: itemId ?: "unlisted-$jobId"
                    val resolvedItemName = matchedCatalog?.name ?: itemName
                    val isCreditSale = effectiveIntent == CaptureIntent.CREDIT_SALE
                    val txRecord = TransactionRecord(
                        shopId = shopId,
                        itemId = resolvedItemId,
                        itemName = resolvedItemName,
                        quantity = qty,
                        priceAtSale = priceAtSale,
                        total = total,
                        paymentMode = if (isCreditSale) PaymentMode.CREDIT else PaymentMode.CASH,
                        source = TransactionSource.VOICE,
                        rawTranscript = rawTranscript,
                        audioFilePath = audioPath,
                        jobId = jobId,
                        lineNo = i,
                        audioCloudUrl = audioCloudUrl,
                        // COGS snapshot at commit time. Null when this item has no known cost --
                        // ProfitCalculator excludes those lines instead of assuming zero cost,
                        // which would fabricate 100% margin.
                        costAtSale = matchedCatalog?.avgCostPrice
                            ?: db.catalogDao().getById(resolvedItemId)?.avgCostPrice,
                        // One recording is one bill, so its lines share a billId.
                        billId = jobId
                    )
                    db.transactionDao().insert(txRecord)
                    // Deduct stock. refId makes this idempotent: a WorkManager retry that
                    // re-delivers the same job (ISSUE-045 saw a recording reach the server twice)
                    // must not deduct twice.
                    stockLedger.recordSale(
                        itemId = resolvedItemId,
                        itemName = resolvedItemName,
                        qty = qty,
                        transactionId = txRecord.id,
                        occurredAtMs = txRecord.timestamp,
                        total = txRecord.total,
                        costAtSale = txRecord.costAtSale
                    )
                    cloudSyncManager.syncTransactionToCloud(txRecord)
                    committedCount++

                    if (isCreditSale) {
                        var creditRecord = CreditRecord(
                            shopId = shopId,
                            customerName = "अज्ञात",
                            customerId = null,
                            amount = total,
                            status = CreditStatus.PENDING,
                            linkedTransactionId = txRecord.id
                        )
                        db.creditDao().insertOrUpdate(creditRecord)

                        val activeCustomers = db.customerDao().getActiveCustomersList()
                        if (activeCustomers.isNotEmpty()) {
                            val resolution = com.voicetoinvoice.app.domain.resolver.EntityResolver<com.voicetoinvoice.app.data.local.entity.CustomerRecord>()
                                .resolve(rawTranscript, activeCustomers)
                            if (resolution.decision == com.voicetoinvoice.app.domain.resolver.Decision.AUTO_ASSIGN) {
                                val matched = resolution.candidates.first().item
                                db.customerDao().assignCustomerToCredit(creditRecord, matched.id)
                                creditRecord = creditRecord.copy(customerId = matched.id, customerName = matched.name)
                            }
                        }
                        cloudSyncManager.syncCreditToCloud(creditRecord)
                    }
                }
                else -> {
                    val unmatchedItem = com.voicetoinvoice.app.data.local.entity.UnmatchedQueueItem(
                        id = "$jobId#$i",
                        shopId = SupabaseConfig.getNullSafeShopId(com.voicetoinvoice.app.data.ShopContext.requireShopId()),
                        audioRef = audioCloudUrl,
                        rawTranscript = rawTranscript,
                        status = com.voicetoinvoice.app.data.local.entity.UnmatchedStatus.PENDING,
                        timestamp = System.currentTimeMillis()
                    )
                    db.unmatchedQueueDao().insert(unmatchedItem)
                    cloudSyncManager.syncReviewItemToCloud(unmatchedItem)
                }
            }
        }

        if (lineCount == 0) {
            val unmatchedItem = com.voicetoinvoice.app.data.local.entity.UnmatchedQueueItem(
                id = "$jobId#0",
                shopId = SupabaseConfig.getNullSafeShopId(com.voicetoinvoice.app.data.ShopContext.requireShopId()),
                audioRef = audioCloudUrl,
                rawTranscript = rawTranscript,
                status = com.voicetoinvoice.app.data.local.entity.UnmatchedStatus.PENDING,
                timestamp = System.currentTimeMillis()
            )
            db.unmatchedQueueDao().insert(unmatchedItem)
            cloudSyncManager.syncReviewItemToCloud(unmatchedItem)
        }

        return committedCount
    }

    private suspend fun handleAssistantJob(
        jobRecord: SttJobRecord,
        rawTranscript: String,
        parsedItems: JSONArray,
        traceJson: String,
        catalog: List<com.voicetoinvoice.app.data.local.entity.CatalogItem>,
        audioPath: String,
        clientTrace: JSONObject
    ): Result {
        val audioCloudUrl = "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/public/voice-recordings/${jobRecord.id}.wav"
        val speechOutput = com.voicetoinvoice.app.domain.voice.SpeechOutput(
            context,
            com.voicetoinvoice.app.audio.RollingAudioBuffer.getSharedInstance(context)
        )
        val answer: String
        val cleanTranscript = rawTranscript.trim()

        var finalStatus: SttJobStatus = SttJobStatus.AUTO_CONFIRMED

        if (cleanTranscript.isBlank()) {
            answer = com.voicetoinvoice.app.domain.voice.ResponseComposer.formatUnrecognized()
            finalStatus = SttJobStatus.FAILED
        } else {
            val classification = com.voicetoinvoice.app.domain.router.IntentRouter.classify(cleanTranscript, parsedItems)
            when (classification.intent) {
                com.voicetoinvoice.app.domain.router.AssistantIntent.READ_QUERY -> {
                    val ledgerQueries = com.voicetoinvoice.app.domain.query.LedgerQueries(db)
                    answer = com.voicetoinvoice.app.domain.query.QuestionTemplates(ledgerQueries).answerQuestion(cleanTranscript)
                    finalStatus = SttJobStatus.AUTO_CONFIRMED
                }
                com.voicetoinvoice.app.domain.router.AssistantIntent.SALE,
                com.voicetoinvoice.app.domain.router.AssistantIntent.CREDIT_SALE,
                com.voicetoinvoice.app.domain.router.AssistantIntent.STOCK_IN,
                com.voicetoinvoice.app.domain.router.AssistantIntent.WASTE -> {
                    val committed = commitParsedLines(
                        jobRecord = jobRecord,
                        effectiveIntent = classification.captureIntent,
                        parsedItems = parsedItems,
                        catalog = catalog,
                        rawTranscript = rawTranscript,
                        audioPath = audioPath,
                        audioCloudUrl = audioCloudUrl,
                        jobId = jobRecord.id
                    )

                    val firstItem = if (parsedItems.length() > 0) parsedItems.optJSONObject(0) else null
                    val name = firstItem?.optString("item_name", "").orEmpty()
                    val qty = firstItem?.optDouble("quantity", 0.0) ?: 0.0
                    val unit = firstItem?.optString("unit", "").orEmpty()
                    val total = firstItem?.optDouble("total", 0.0) ?: 0.0

                    if (committed == 0) {
                        answer = "समझ नहीं आया — कृपया दोबारा बोलिए"
                        finalStatus = SttJobStatus.PARSED
                    } else {
                        answer = when (classification.intent) {
                            com.voicetoinvoice.app.domain.router.AssistantIntent.SALE ->
                                com.voicetoinvoice.app.domain.voice.ResponseComposer.formatSaleConfirmed(name, qty, unit, total)
                            com.voicetoinvoice.app.domain.router.AssistantIntent.CREDIT_SALE -> {
                                val activeCustomers = db.customerDao().getActiveCustomersList()
                                val res = com.voicetoinvoice.app.domain.resolver.EntityResolver<com.voicetoinvoice.app.data.local.entity.CustomerRecord>()
                                    .resolve(rawTranscript, activeCustomers)
                                if (res.decision == com.voicetoinvoice.app.domain.resolver.Decision.AUTO_ASSIGN) {
                                    val matchedCust = res.candidates.first().item
                                    com.voicetoinvoice.app.domain.voice.ResponseComposer.formatUdhaarConfirmed(matchedCust.name, matchedCust.code, total)
                                } else {
                                    "₹${total.toInt()} उधार दर्ज हो गया, किसका?"
                                }
                            }
                            com.voicetoinvoice.app.domain.router.AssistantIntent.STOCK_IN ->
                                "$qty $unit $name स्टॉक में जोड़ा गया"
                            com.voicetoinvoice.app.domain.router.AssistantIntent.WASTE ->
                                "$qty $unit $name खराब दर्ज हुआ"
                            else -> "दर्ज हो गया"
                        }

                        finalStatus = when {
                            committed == parsedItems.length() -> SttJobStatus.AUTO_CONFIRMED
                            committed > 0 -> SttJobStatus.PARTIALLY_CONFIRMED
                            else -> SttJobStatus.PARSED
                        }
                    }
                }
                // Everything below used to be unreachable or a dead end. ACTION_COMMAND in
                // particular answered "यह काम अभी नहीं कर सकता" while a complete
                // ActionExecutor.sendWhatsAppReminder sat at zero call sites -- that pair was the
                // "assistant doesn't send anything anywhere" bug.
                com.voicetoinvoice.app.domain.router.AssistantIntent.PRICE_UPDATE -> {
                    val outcome = handlers.handlePriceUpdate(parsedItems, cleanTranscript)
                    answer = outcome.spokenReply
                    finalStatus = if (outcome.committed > 0) SttJobStatus.AUTO_CONFIRMED else SttJobStatus.PARSED
                }
                com.voicetoinvoice.app.domain.router.AssistantIntent.RETURN -> {
                    val outcome = handlers.handleReturn(parsedItems, cleanTranscript, jobRecord.id)
                    answer = outcome.spokenReply
                    finalStatus = if (outcome.committed > 0) SttJobStatus.AUTO_CONFIRMED else SttJobStatus.PARSED
                }
                com.voicetoinvoice.app.domain.router.AssistantIntent.PAYMENT_RECEIVED -> {
                    val outcome = handlers.handlePaymentReceived(cleanTranscript, jobRecord.id)
                    answer = outcome.spokenReply
                    // A payment we could not attribute must stay in review rather than be dropped:
                    // crediting the wrong customer creates two wrong balances at once.
                    finalStatus = if (outcome.committed > 0) SttJobStatus.AUTO_CONFIRMED else SttJobStatus.PARSED
                }
                com.voicetoinvoice.app.domain.router.AssistantIntent.VOID_LAST -> {
                    val outcome = handlers.handleVoidLast()
                    answer = outcome.spokenReply
                    finalStatus = if (outcome.committed > 0) SttJobStatus.AUTO_CONFIRMED else SttJobStatus.PARSED
                }
                com.voicetoinvoice.app.domain.router.AssistantIntent.EXPIRY_WRITEOFF -> {
                    val committed = commitParsedLines(
                        jobRecord = jobRecord,
                        effectiveIntent = com.voicetoinvoice.app.data.local.entity.CaptureIntent.WASTE,
                        parsedItems = parsedItems,
                        catalog = catalog,
                        rawTranscript = rawTranscript,
                        audioPath = audioPath,
                        audioCloudUrl = audioCloudUrl,
                        jobId = jobRecord.id
                    )
                    val firstItem = if (parsedItems.length() > 0) parsedItems.optJSONObject(0) else null
                    answer = if (committed > 0) {
                        "${firstItem?.optDouble("quantity", 0.0) ?: 0.0} " +
                            "${firstItem?.optString("item_name", "").orEmpty()} एक्सपायरी में हटाया"
                    } else "क्या एक्सपायर हुआ? दोबारा बोलिए"
                    finalStatus = if (committed > 0) SttJobStatus.AUTO_CONFIRMED else SttJobStatus.PARSED
                }
                com.voicetoinvoice.app.domain.router.AssistantIntent.ACTION_COMMAND -> {
                    val outcome = handlers.handleActionCommand(cleanTranscript)
                    answer = outcome.spokenReply
                    // The intent itself must be launched from an Activity (WhatsApp needs a UI
                    // context, and the shopkeeper's own tap on Send is the consent checkpoint), so
                    // the resolved action is queued for the UI to pick up.
                    (outcome.followUp as? com.voicetoinvoice.app.domain.action.FollowUp.LaunchAction)
                        ?.let { com.voicetoinvoice.app.domain.action.PendingActions.enqueue(it) }
                    finalStatus = if (outcome.committed > 0) SttJobStatus.AUTO_CONFIRMED else SttJobStatus.PARSED
                }
                else -> {
                    answer = com.voicetoinvoice.app.domain.voice.ResponseComposer.formatUnrecognized()
                    finalStatus = SttJobStatus.PARSED
                }
            }
        }

        try {
            // preferOffline=true: this is every assistant reply that reaches the full
            // pipeline (all of PRICE_UPDATE/RETURN/PAYMENT_RECEIVED/VOID_LAST/
            // ACTION_COMMAND/EXPIRY_WRITEOFF land here, plus READ_QUERY answers when the
            // on-device fast path didn't resolve it). The default `speak()` call tries
            // Grok TTS first -- measured 1.3-2.4s in production edge logs -- purely for a
            // higher-quality voice. For the assistant specifically, responsiveness is the
            // whole point (the shopkeeper is mid-task and waiting), so it takes the same
            // instant on-device path AssistantFastPath already uses rather than paying that
            // round trip on every single voice command.
            speechOutput.speak(answer, preferOffline = true)
        } catch (e: Exception) {
            Log.w(TAG, "Assistant TTS failed for job ${jobRecord.id}: ${e.message}")
        }

        val firstItem = if (parsedItems.length() > 0) parsedItems.optJSONObject(0) else null
        clientTrace.put("outcome", "assistant_answered")
        clientTrace.put("assistant_answer", answer)

        db.sttJobDao().updateJob(
            jobRecord.copy(
                status = finalStatus,
                rawTranscript = rawTranscript,
                assistantAnswer = answer,
                parsedItemName = firstItem?.optString("item_name") ?: "",
                parsedQty = firstItem?.optDouble("quantity") ?: 0.0,
                parsedUnit = firstItem?.optString("unit") ?: "",
                parsedTotal = firstItem?.optDouble("total") ?: 0.0,
                parsedItemsJson = parsedItems.toString(),
                lineCount = parsedItems.length(),
                diagnosticTraceJson = mergeClientTrace(clientTrace, traceJson),
                isSanityFlagged = finalStatus == SttJobStatus.PARSED,
                synced = false
            )
        )

        return if (cleanTranscript.isBlank()) Result.failure() else Result.success()
    }

    /** Everything the server's finished stt_job_logs row carries back to the client. */
    private data class PolledResult(
        val status: String,
        val rawTranscript: String,
        val traceJson: String,
        val parsedItems: JSONArray
    )

    /** process-voice-job answers 202/QUEUED immediately and finishes the real
     *  transcribe+parse in the background (EdgeRuntime.waitUntil) -- the initial HTTP
     *  response never carries raw_transcript/parsed_items for a new job. This polls
     *  stt_job_logs (the row the background task writes) until status leaves QUEUED,
     *  for up to 20s (tightened from 30s -- §2.2, server work measures 2-4s and the
     *  inline response in process-voice-job/index.ts now covers the common case, so
     *  this path is the rare exception, not the norm). Restores the polling this file
     *  had before the SttWorker refactor accidentally dropped it (see
     *  Docs/sttworker_regression_correction.md), and this time also carries the trace
     *  back -- the previous version silently dropped it (Docs/assistant_speed_and_pipeline_fix_plan.md §1.1). */
    private suspend fun pollForCompletion(jobId: String): PolledResult {
        var traceJson = ""
        var rawTranscript = ""
        var parsedItems = JSONArray()
        var statusStr = "QUEUED"

        val pollUrl = "${SupabaseConfig.SUPABASE_URL}/rest/v1/stt_job_logs?job_id=eq.$jobId&select=status,raw_transcript,parsed_item_name,parsed_qty,parsed_unit,parsed_total,diagnostic_trace_json"
        val maxPollMs = 20000L
        val pollIntervalMs = 750L
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
                            rawTranscript = row.optString("raw_transcript", rawTranscript)
                            traceJson = row.optString("diagnostic_trace_json", traceJson)

                            if (traceJson.isNotBlank()) {
                                try {
                                    val traceObj = JSONObject(traceJson)
                                    if (traceObj.has("step_4_grok_ai_interpretation")) {
                                        parsedItems = traceObj.getJSONArray("step_4_grok_ai_interpretation")
                                    }
                                } catch (_: Exception) {}
                            }

                            if (parsedItems.length() == 0 && row.has("parsed_item_name")) {
                                val fallbackQty = row.optDouble("parsed_qty", 1.0)
                                val fallbackTotal = row.optDouble("parsed_total", 0.0)
                                val singleItem = JSONObject().apply {
                                    put("item_name", row.optString("parsed_item_name", "Unrecognized Item"))
                                    put("quantity", fallbackQty)
                                    put("unit", row.optString("parsed_unit", "PACKET"))
                                    put("price_at_sale", if (fallbackQty > 0) fallbackTotal / fallbackQty else fallbackTotal)
                                    put("total", fallbackTotal)
                                    put("price_intent", "NONE")
                                    put("confidence", if (statusStr == "AUTO_CONFIRMED") 1.0 else 0.0)
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

        return PolledResult(statusStr, rawTranscript, traceJson, parsedItems)
    }

    private suspend fun uploadAudioToEdgeFunction(
        audioFile: File,
        jobId: String,
        shopId: String?,
        metadataJson: JSONObject,
        catalogNames: List<String>,
        onDeviceTranscript: String,
        onDeviceStatus: String,
        previousJobId: String?,
        precedingGapMs: Long,
        captureIntent: String
    ): String? {
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

            writeString("$twoHyphens$boundary$lineEnd")
            writeString("Content-Disposition: form-data; name=\"jobId\"$lineEnd$lineEnd")
            writeString("$jobId$lineEnd")

            if (catalogNames.isNotEmpty()) {
                writeString("$twoHyphens$boundary$lineEnd")
                writeString("Content-Disposition: form-data; name=\"catalogNames\"$lineEnd$lineEnd")
                writeString("${JSONArray(catalogNames)}$lineEnd")
            }

            writeString("$twoHyphens$boundary$lineEnd")
            writeString("Content-Disposition: form-data; name=\"metadata\"$lineEnd$lineEnd")
            writeString("$metadataJson$lineEnd")

            writeString("$twoHyphens$boundary$lineEnd")
            writeString("Content-Disposition: form-data; name=\"shopId\"$lineEnd$lineEnd")
            writeString("$shopId$lineEnd")

            if (onDeviceTranscript.isNotBlank()) {
                writeString("$twoHyphens$boundary$lineEnd")
                writeString("Content-Disposition: form-data; name=\"onDeviceTranscript\"$lineEnd$lineEnd")
                writeString("$onDeviceTranscript$lineEnd")
            }

            if (onDeviceStatus.isNotBlank()) {
                writeString("$twoHyphens$boundary$lineEnd")
                writeString("Content-Disposition: form-data; name=\"onDeviceStatus\"$lineEnd$lineEnd")
                writeString("$onDeviceStatus$lineEnd")
            }

            if (previousJobId != null) {
                writeString("$twoHyphens$boundary$lineEnd")
                writeString("Content-Disposition: form-data; name=\"previousJobId\"$lineEnd$lineEnd")
                writeString("$previousJobId$lineEnd")
            }

            if (precedingGapMs >= 0L) {
                writeString("$twoHyphens$boundary$lineEnd")
                writeString("Content-Disposition: form-data; name=\"precedingGapMs\"$lineEnd$lineEnd")
                writeString("$precedingGapMs$lineEnd")
            }

            writeString("$twoHyphens$boundary$lineEnd")
            writeString("Content-Disposition: form-data; name=\"captureIntent\"$lineEnd$lineEnd")
            writeString("$captureIntent$lineEnd")

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
        return if (responseCode in 200..299 && responseString.isNotBlank()) responseString else null
    }
}
