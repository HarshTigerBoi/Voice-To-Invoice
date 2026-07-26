package com.voicetoinvoice.app.domain.processor

import android.content.Context
import android.util.Log
import com.voicetoinvoice.app.audio.RollingAudioBuffer
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.PaymentMode
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
import com.voicetoinvoice.app.data.local.entity.SttJobStatus
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.data.local.entity.TransactionSource
import com.voicetoinvoice.app.domain.parser.MultiSaleDetector
import com.voicetoinvoice.app.domain.parser.OrderingSegmenter
import com.voicetoinvoice.app.domain.parser.ParsedVoiceSale
import com.voicetoinvoice.app.domain.parser.SalePlausibility
import com.voicetoinvoice.app.domain.parser.VoiceParser
import com.voicetoinvoice.app.network.CloudSyncManager
import com.voicetoinvoice.app.network.SttProxyClient
import com.voicetoinvoice.app.network.SttResult
import com.voicetoinvoice.app.network.SupabaseConfig
import com.voicetoinvoice.app.network.TermInterpreterClient
import com.voicetoinvoice.app.util.LocalStorageCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BackgroundSttProcessor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val rollingAudioBuffer: RollingAudioBuffer, // Injected active RollingAudioBuffer instance (Fixes BUG-1)
    private val sttProxyClient: SttProxyClient = SttProxyClient(),
    private val termInterpreterClient: TermInterpreterClient = TermInterpreterClient(),
    private val cloudSyncManager: CloudSyncManager = CloudSyncManager(),
    private val voiceParser: VoiceParser = VoiceParser(),
    private val multiSaleDetector: MultiSaleDetector = MultiSaleDetector(),
    private val orderingSegmenter: OrderingSegmenter = OrderingSegmenter()
) {

    private val db = AppDatabase.getInstance(context)
    private var processingJob: Job? = null

    fun triggerQueueProcessing() {
        if (processingJob?.isActive == true) return

        processingJob = scope.launch(Dispatchers.IO) {
            val queuedJobs = db.sttJobDao().getQueuedJobsList()
            for (job in queuedJobs) {
                try {
                    val workRequest = androidx.work.OneTimeWorkRequestBuilder<SttWorker>()
                        .setInputData(
                            androidx.work.workDataOf(
                                SttWorker.KEY_JOB_ID to job.id,
                                SttWorker.KEY_AUDIO_PATH to job.audioFilePath
                            )
                        )
                        .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                    androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
                } catch (e: Exception) {
                    Log.e("BackgroundSttProcessor", "Failed to enqueue SttWorker for job ${job.id}", e)
                }
            }
        }
    }

    private suspend fun processSingleJob(job: SttJobRecord, catalog: List<CatalogItem>, catalogNames: List<String>) {
        var pendingCarryoverQty: Double? = null
        var pendingCarryoverUnit: String? = null
        var updatedJobRecord: SttJobRecord = job

        val traceObj = JSONObject()
        val expansionPassesJson = JSONArray()

        try {
            db.sttJobDao().updateJob(job.copy(status = SttJobStatus.TRANSCRIBING))

            // Step 1: Recording & PTT Metadata Trace
            val timingJson = JSONObject().apply {
                put("jobId", job.id)
                put("recordedAtMs", job.recordedAtMs)
                put("pressStartMs", job.pressStartMs)
                put("releaseMs", job.releaseMs)
                put("holdDurationMs", job.holdDurationMs)
                put("audioStartMs", job.audioStartMs)
                put("audioEndMs", job.audioEndMs)
                put("audioFilePath", job.audioFilePath)
            }
            traceObj.put("step_1_ptt_recording_metadata", timingJson)

            val audioFile = File(job.audioFilePath)
            if (!audioFile.exists()) {
                traceObj.put("error", "Audio file missing on device disk")
                updatedJobRecord = job.copy(
                    status = SttJobStatus.PARSED,
                    rawTranscript = "Voice Recording (Audio file missing)",
                    parsedItemName = "Unrecognized Item",
                    isSanityFlagged = true,
                    errorMessage = "Audio file missing",
                    diagnosticTraceJson = traceObj.toString(2)
                )
                db.sttJobDao().updateJob(updatedJobRecord)
                
                val finalJob = updatedJobRecord
                scope.launch(Dispatchers.IO) {
                    try { cloudSyncManager.syncJobTraceAndAudioToCloud(finalJob, traceObj) } catch (e: Exception) {}
                }
                return
            }

            // Step 2: STT Proxy Transcription
            var sttResult = sttProxyClient.transcribeAudioProxy(audioFile, catalogNames)

            when (sttResult) {
                is SttResult.Success -> {
                    var transcript = sttResult.transcript
                    val sttJson = JSONObject().apply {
                        put("rawTranscript", transcript)
                        put("audioFileSize", audioFile.length())
                        put("catalogKeytermsSentCount", catalogNames.size)
                    }
                    traceObj.put("step_2_stt_proxy_response", sttJson)

                    // Step 3: Deterministic OrderingSegmenter
                    // Pass the live catalog so the phonetic lattice can use this shop's
                    // own item names as split targets, not just the built-in vocabulary.
                    val segResult = orderingSegmenter.segmentTranscript(transcript, pendingCarryoverQty, catalogNames)
                    pendingCarryoverQty = segResult.carryoverQty
                    val deterministicSegments = segResult.segments
                    val segJson = JSONObject().apply {
                        put("carryoverQty", pendingCarryoverQty ?: JSONObject.NULL)
                        val segArr = JSONArray()
                        for (seg in deterministicSegments) {
                            segArr.put(JSONObject().apply {
                                put("rawSegmentText", seg.rawSegmentText)
                                put("quantity", seg.quantity ?: JSONObject.NULL)
                                put("unit", seg.unit ?: JSONObject.NULL)
                                put("itemTokens", JSONArray(seg.itemTokens))
                                put("isSanityFlagged", seg.isSanityFlagged)
                            })
                        }
                        put("segments", segArr)
                    }
                    traceObj.put("step_3_deterministic_ordering_segmenter", segJson)

                    // Step 4: Grok AI Fallback / Structuring
                    val aiMultiList = if (deterministicSegments.any { it.isSanityFlagged }) {
                        val aiRes = termInterpreterClient.interpretMultiItemUtterance(transcript, catalogNames)
                        val aiArr = JSONArray()
                        for (ai in aiRes) {
                            aiArr.put(JSONObject().apply {
                                put("itemName", ai.itemName)
                                put("quantity", ai.quantity)
                                put("unit", ai.unit)
                                put("price", ai.price)
                            })
                        }
                        traceObj.put("step_4_grok_ai_interpretation", aiArr)
                        aiRes
                    } else {
                        traceObj.put("step_4_grok_ai_interpretation", JSONArray())
                        emptyList()
                    }

                    var multiParsed: List<ParsedVoiceSale> = if (aiMultiList.isNotEmpty()) {
                        aiMultiList.mapIndexed { idx, ai ->
                            val matched = catalog.find { it.name.equals(ai.itemName, ignoreCase = true) }
                            val fallbackSeg = deterministicSegments.getOrNull(idx)

                            val finalQty = if (ai.quantity > 0.0) ai.quantity else (fallbackSeg?.quantity ?: 1.0)
                            val finalUnit = if (ai.unit.isNotBlank() && ai.unit != "PACKET") ai.unit else (fallbackSeg?.unit ?: "PACKET")
                            val item = matched ?: CatalogItem(name = ai.itemName, unitId = finalUnit, price = ai.price)

                            ParsedVoiceSale(
                                matchedItem = item,
                                rawTranscript = transcript,
                                quantity = finalQty,
                                unit = finalUnit,
                                estimatedTotal = if (ai.price > 0) finalQty * ai.price else item.price * finalQty,
                                confidence = if (matched != null) 0.90f else 0.70f,
                                hasLeadingQty = finalQty > 0.0 && (ai.quantity > 0.0 || fallbackSeg?.quantity != null)
                            )
                        }
                    } else {
                        val localMulti = multiSaleDetector.detectMultipleSales(transcript, catalog, pendingCarryoverQty)
                        if (localMulti.isNotEmpty()) {
                            localMulti
                        } else {
                            val singleParsed = voiceParser.parseUtterance(transcript, catalog)
                            val fallbackSeg = deterministicSegments.firstOrNull()
                            if (singleParsed.matchedItem != null || singleParsed.quantity > 0 || fallbackSeg != null) {
                                val finalQty = if (singleParsed.quantity > 0.0) singleParsed.quantity else (fallbackSeg?.quantity ?: 1.0)
                                val finalUnit = if (singleParsed.unit.isNotBlank() && singleParsed.unit != "PACKET") singleParsed.unit else (fallbackSeg?.unit ?: "PACKET")
                                val finalItem = singleParsed.matchedItem ?: CatalogItem(name = fallbackSeg?.itemTokens?.joinToString(" ") ?: "Unrecognized Item", unitId = finalUnit, price = 0.0)
                                listOf(
                                    singleParsed.copy(
                                        matchedItem = finalItem,
                                        quantity = finalQty,
                                        unit = finalUnit,
                                        estimatedTotal = finalItem.price * finalQty,
                                        hasLeadingQty = finalQty > 0.0 && fallbackSeg?.quantity != null
                                    )
                                )
                            } else {
                                listOf(
                                    ParsedVoiceSale(
                                        matchedItem = null,
                                        rawTranscript = transcript,
                                        quantity = 1.0,
                                        unit = "PACKET",
                                        estimatedTotal = 0.0,
                                        confidence = 0.0f,
                                        isSanityFlagged = true
                                    )
                                )
                            }
                        }
                    }

                    // Step 5: Adaptive Audio Expansion Engine
                    var currentStartMs = if (job.audioStartMs > 0L) job.audioStartMs else (job.recordedAtMs - 2000L)
                    var currentEndMs = if (job.audioEndMs > 0L) job.audioEndMs else (job.recordedAtMs + job.holdDurationMs + 800L)

                    var pass = 0
                    val maxPasses = 3

                    while (pass < maxPasses) {
                        val needsLeftQtyExpand = multiParsed.isEmpty() || multiParsed.any { !it.hasLeadingQty && (it.confidence < 0.80f || it.isSanityFlagged || it.matchedItem == null) }
                        val needsRightItemExpand = multiParsed.isEmpty() || multiParsed.any { it.matchedItem == null || it.confidence < 0.80f }

                        if (!needsLeftQtyExpand && !needsRightItemExpand) {
                            break // Confidence >= 80% and full clarity achieved!
                        }

                        pass++
                        val expandFile = File.createTempFile("adaptive_expand_${pass}_", ".wav", context.cacheDir)
                        if (needsLeftQtyExpand) currentStartMs -= 100L
                        if (needsRightItemExpand) currentEndMs += 100L

                        val expandedAudio = rollingAudioBuffer.extractAudioWindow(
                            startMs = currentStartMs,
                            endMs = currentEndMs,
                            outputFile = expandFile
                        ) ?: break

                        val retryStt = sttProxyClient.transcribeAudioProxy(expandedAudio, catalogNames)
                        val passObj = JSONObject().apply {
                            put("passNumber", pass)
                            put("expandedStartMs", currentStartMs)
                            put("expandedEndMs", currentEndMs)
                            put("expandedStartDeltaMs", -(pass * 100))
                            put("expandedEndDeltaMs", pass * 100)
                        }

                        if (retryStt is SttResult.Success && retryStt.transcript.isNotBlank()) {
                            transcript = retryStt.transcript
                            passObj.put("retriedTranscript", transcript)

                            val retriedAiMulti = termInterpreterClient.interpretMultiItemUtterance(transcript, catalogNames)
                            if (retriedAiMulti.isNotEmpty()) {
                                multiParsed = retriedAiMulti.map { ai ->
                                    val matched = catalog.find { it.name.equals(ai.itemName, ignoreCase = true) }
                                    val item = matched ?: CatalogItem(name = ai.itemName, unitId = ai.unit, price = ai.price)
                                    ParsedVoiceSale(
                                        matchedItem = item,
                                        rawTranscript = transcript,
                                        quantity = ai.quantity,
                                        unit = ai.unit,
                                        estimatedTotal = if (ai.price > 0) ai.quantity * ai.price else item.price * ai.quantity,
                                        confidence = if (matched != null) 0.90f else 0.75f,
                                        hasLeadingQty = ai.quantity > 0.0
                                    )
                                }
                            }
                        }
                        expansionPassesJson.put(passObj)
                    }
                    traceObj.put("step_5_adaptive_audio_expansion_engine", JSONObject().apply {
                        put("passesExecuted", pass)
                        put("passesDetail", expansionPassesJson)
                    })

                    // Process each parsed sale and record trace
                    val outcomeArray = JSONArray()
                    for (parsedSale in multiParsed) {
                        var saleResult = parsedSale

                        // Carryover Quantity Assignment
                        if (pendingCarryoverQty != null && !saleResult.hasLeadingQty) {
                            val carryQty = pendingCarryoverQty!!
                            val carryUnit = pendingCarryoverUnit ?: saleResult.unit
                            val item = saleResult.matchedItem
                            val unitPrice = item?.price ?: 0.0
                            val newTotal = if (unitPrice > 0.0) carryQty * unitPrice else 0.0

                            saleResult = saleResult.copy(
                                quantity = carryQty,
                                unit = carryUnit,
                                estimatedTotal = newTotal
                            )
                            pendingCarryoverQty = null
                            pendingCarryoverUnit = null
                        }

                        if (saleResult.trailingQty != null && saleResult.hasLeadingQty) {
                            pendingCarryoverQty = saleResult.trailingQty
                            pendingCarryoverUnit = saleResult.trailingUnit
                        }

                        // 3-Pass Verification Guard for Suspicious / Unrecognized Items
                        if (saleResult.matchedItem == null || saleResult.confidence < 0.85f) {
                            val deepAiParsed = termInterpreterClient.reanalyzeAmbiguousSale(saleResult.rawTranscript, catalogNames)
                            if (deepAiParsed != null) {
                                val matched = catalog.find { it.name.equals(deepAiParsed.itemName, ignoreCase = true) }
                                val resolvedItem = matched ?: CatalogItem(
                                    name = deepAiParsed.itemName,
                                    unitId = deepAiParsed.unit,
                                    price = deepAiParsed.price
                                )
                                saleResult = saleResult.copy(
                                    matchedItem = resolvedItem,
                                    quantity = if (deepAiParsed.quantity > 0) deepAiParsed.quantity else saleResult.quantity,
                                    unit = deepAiParsed.unit,
                                    estimatedTotal = if (deepAiParsed.price > 0) deepAiParsed.quantity * deepAiParsed.price else resolvedItem.price * deepAiParsed.quantity,
                                    confidence = if (matched != null) 0.90f else 0.75f,
                                    isSanityFlagged = matched == null
                                )
                            }
                        }

                        val item = saleResult.matchedItem

                        // RATE_UPDATE vs BULK_SALE_TOTAL vs NONE Auto-Confirm Logic
                        if (saleResult.priceIntent == com.voicetoinvoice.app.domain.parser.PriceIntent.RATE_UPDATE) {
                            val isRateUpdateValid = item != null && !saleResult.isSanityFlagged && saleResult.updatedUnitPrice > 0.0

                            val itemOutcomeJson = JSONObject().apply {
                                put("itemName", item?.name ?: "Unrecognized Item")
                                put("matchedCatalogId", item?.id ?: JSONObject.NULL)
                                put("outcomeType", "RATE_UPDATE")
                                put("updatedUnitPrice", saleResult.updatedUnitPrice)
                                put("confidence", saleResult.confidence)
                                put("isSanityFlagged", saleResult.isSanityFlagged)
                                put("autoConfirmedToLedger", false)
                            }
                            outcomeArray.put(itemOutcomeJson)

                            if (isRateUpdateValid && item != null) {
                                db.catalogDao().insertOrUpdate(item.copy(price = saleResult.updatedUnitPrice, synced = false))
                            }
                        } else {
                            // Domain sanity, checked independently of transcript
                            // confidence: a perfectly-matched item can still be an
                            // obvious mis-parse in context. "7 GRAM Jeera for ₹2.80"
                            // (ISSUE-022) was booked at 0.95 confidence because nothing
                            // ever asked whether the sale made sense.
                            val implausibility = SalePlausibility.reason(
                                saleResult.unit, saleResult.quantity, saleResult.estimatedTotal
                            )
                            val isAutoConfirmable = item != null &&
                                    saleResult.confidence >= 0.80f &&
                                    !saleResult.isPendingPrice &&
                                    saleResult.estimatedTotal > 0.0 &&
                                    !saleResult.isSanityFlagged &&
                                    implausibility == null

                            val itemOutcomeJson = JSONObject().apply {
                                put("itemName", item?.name ?: "Unrecognized Item")
                                put("matchedCatalogId", item?.id ?: JSONObject.NULL)
                                put("quantity", saleResult.quantity)
                                put("unit", saleResult.unit)
                                put("estimatedTotal", saleResult.estimatedTotal)
                                put("confidence", saleResult.confidence)
                                put("isSanityFlagged", saleResult.isSanityFlagged)
                                put("implausibilityReason", implausibility ?: JSONObject.NULL)
                                put("autoConfirmedToLedger", isAutoConfirmable)
                            }
                            outcomeArray.put(itemOutcomeJson)

                            if (isAutoConfirmable && item != null) {
                                val effectiveUnitPrice = if (saleResult.priceOverridden && saleResult.updatedUnitPrice > 0.0) saleResult.updatedUnitPrice else item.price

                                val txRecord = TransactionRecord(
                                    itemId = item.id,
                                    itemName = item.name,
                                    quantity = saleResult.quantity,
                                    priceAtSale = effectiveUnitPrice,
                                    total = saleResult.estimatedTotal,
                                    paymentMode = PaymentMode.CASH,
                                    source = TransactionSource.VOICE,
                                    rawTranscript = saleResult.rawTranscript,
                                    audioFilePath = job.audioFilePath,
                                    jobId = job.id,
                                    audioCloudUrl = "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/public/voice-recordings/${job.id}.wav"
                                )
                                db.transactionDao().insert(txRecord)

                                // Brand-new item exception: seed initial catalog price if standing catalog price was 0.0
                                if (item.price == 0.0 && effectiveUnitPrice > 0.0) {
                                    db.catalogDao().insertOrUpdate(item.copy(price = effectiveUnitPrice, synced = false))
                                }

                                // Async cloud sync transaction
                                scope.launch(Dispatchers.IO) {
                                    try { cloudSyncManager.syncTransactionToCloud(txRecord) } catch (e: Exception) {}
                                }
                            }
                        }
                    }

                    traceObj.put("step_6_final_outcome", outcomeArray)

                    // Keep job in stt_jobs table with status AUTO_CONFIRMED or PARSED for full trace logging!
                    val finalStatus = if (multiParsed.all { it.matchedItem != null && it.confidence >= 0.80f && !it.isSanityFlagged }) {
                        SttJobStatus.AUTO_CONFIRMED
                    } else {
                        SttJobStatus.PARSED
                    }

                    val firstSale = multiParsed.firstOrNull()
                    val firstItem = firstSale?.matchedItem

                    updatedJobRecord = job.copy(
                        status = finalStatus,
                        rawTranscript = transcript,
                        parsedItemId = firstItem?.id,
                        parsedItemName = firstItem?.name ?: "Unrecognized Item",
                        parsedQty = firstSale?.quantity ?: 1.0,
                        parsedUnit = firstSale?.unit ?: "PACKET",
                        parsedTotal = firstSale?.estimatedTotal ?: 0.0,
                        parsedIsPendingPrice = firstSale?.isPendingPrice ?: false,
                        isSanityFlagged = multiParsed.any { it.isSanityFlagged },
                        diagnosticTraceJson = traceObj.toString(2)
                    )
                    db.sttJobDao().updateJob(updatedJobRecord)
                }
                is SttResult.Error -> {
                    traceObj.put("stt_error", sttResult.message)
                    updatedJobRecord = job.copy(
                        status = SttJobStatus.PARSED, // Route to Pending Review Tray so recording is NEVER lost!
                        rawTranscript = if (job.rawTranscript.isNotBlank()) job.rawTranscript else "Voice Recording (${sttResult.message})",
                        parsedItemName = "Unrecognized Item (Tap to review)",
                        parsedQty = 1.0,
                        parsedUnit = "PACKET",
                        parsedTotal = 0.0,
                        isSanityFlagged = true,
                        errorMessage = sttResult.message,
                        diagnosticTraceJson = traceObj.toString(2)
                    )
                    db.sttJobDao().updateJob(updatedJobRecord)
                }
            }
        } catch (e: Exception) {
            Log.e("BackgroundSttProcessor", "Error processing job ${job.id}", e)
            traceObj.put("unhandled_exception", e.message ?: e.toString())
            updatedJobRecord = job.copy(
                status = SttJobStatus.PARSED,
                rawTranscript = if (job.rawTranscript.isNotBlank()) job.rawTranscript else "Voice Recording (Processing exception: ${e.message})",
                parsedItemName = "Unrecognized Item (Tap to review)",
                parsedQty = 1.0,
                parsedUnit = "PACKET",
                parsedTotal = 0.0,
                isSanityFlagged = true,
                errorMessage = e.message ?: "Processing exception",
                diagnosticTraceJson = traceObj.toString(2)
            )
            db.sttJobDao().updateJob(updatedJobRecord)
        }

        // Automatic background cloud upload of audio & diagnostic trace (Asynchronous & non-blocking!)
        val finalJobToSync = updatedJobRecord
        val finalTraceSnapshot = JSONObject(traceObj.toString())
        scope.launch(Dispatchers.IO) {
            try {
                cloudSyncManager.syncJobTraceAndAudioToCloud(finalJobToSync, finalTraceSnapshot)
            } catch (e: Exception) {
                Log.w("BackgroundSttProcessor", "Deferred cloud sync: ${e.message}")
            }
        }

        // Automatic local storage cleanup (Enforces 500 MB limit requested by user)
        LocalStorageCleaner.cleanOldRecordingsIfExceedsLimit(context)
    }
}
