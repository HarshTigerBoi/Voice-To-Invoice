package com.voicetoinvoice.app.domain.voice

import android.content.Context
import com.voicetoinvoice.app.audio.RollingAudioBuffer
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CaptureIntent
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
import com.voicetoinvoice.app.data.local.entity.SttJobStatus
import com.voicetoinvoice.app.domain.query.LedgerQueries
import com.voicetoinvoice.app.domain.query.QuestionTemplates
import com.voicetoinvoice.app.domain.router.AssistantIntent
import com.voicetoinvoice.app.domain.router.IntentRouter
import org.json.JSONArray
import org.json.JSONObject

/**
 * The assistant's instant path: on-device STT only, answered entirely from local data,
 * spoken with the offline TTS engine -- no network call of any kind. Target is under
 * 1.5s press-to-speech (measured 41s before this existed -- see
 * Docs/assistant_speed_and_pipeline_fix_plan.md §3).
 *
 * Scope: everything that can be resolved from the TRANSCRIPT ALONE, with no item-level
 * parsing. READ_QUERY, VOID_LAST, PAYMENT_RECEIVED and ACTION_COMMAND all qualify --
 * `VoiceCommandHandlers` already resolves each of them from `transcript` plus local DB
 * reads, with no dependency on audio or a segmented item list. This is exactly the same
 * handler logic `SttWorker` uses for these four, called here for its speed rather than
 * its ability to process audio.
 *
 * RETURN and PRICE_UPDATE are NOT handled here even though `VoiceCommandHandlers` has
 * logic for them, because both need to know WHICH ITEM and HOW MUCH -- that requires the
 * segmenter/AI item-parsing pipeline, which runs on audio dual-STT (`OrderingSegmenter` +
 * server AI arbitration), not on a bare on-device transcript. Guessing an item and
 * quantity from raw text with no confidence signal here would be less accurate than
 * router misclassification and risks a wrong price change or a wrong return -- both of
 * which move money. They fall through to the same "use the dedicated mic" redirect as
 * SALE/CREDIT_SALE/STOCK_IN/WASTE.
 *
 * To make on-device STT work at all, the caller must release the always-on
 * RollingAudioBuffer for the duration of the press (§4 -- Android does not reliably let
 * AudioRecord and SpeechRecognizer share the microphone, confirmed by every historical
 * trace in this app's database showing on-device STT failing 100% of the time it ran
 * concurrently with the ring buffer). That means a fast-path press captures no raw audio.
 */
object AssistantFastPath {

    suspend fun handle(
        context: Context,
        db: AppDatabase,
        rollingAudioBuffer: RollingAudioBuffer,
        transcript: String,
        pressStartMs: Long,
        releaseMs: Long
    ) {
        val speechOutput = SpeechOutput(context, rollingAudioBuffer)
        val clean = transcript.trim()
        val clientTrace = JSONObject().apply {
            put("fast_path", true)
            put("on_device_transcript", clean)
            put("press_start_ms", pressStartMs)
            put("release_ms", releaseMs)
        }

        val answer: String
        var finalStatus: SttJobStatus

        if (clean.isBlank()) {
            answer = ResponseComposer.formatUnrecognized()
            finalStatus = SttJobStatus.FAILED
            clientTrace.put("outcome", "blank_transcript")
        } else {
            val classification = IntentRouter.classify(clean, JSONArray())
            clientTrace.put("classified_intent", classification.intent.name)

            when (classification.intent) {
                AssistantIntent.READ_QUERY -> {
                    val ledgerQueries = LedgerQueries(db)
                    answer = QuestionTemplates(ledgerQueries).answerQuestion(clean)
                    finalStatus = SttJobStatus.AUTO_CONFIRMED
                    clientTrace.put("outcome", "answered_from_ledger")
                }
                AssistantIntent.VOID_LAST -> {
                    val stockLedger = com.voicetoinvoice.app.data.repository.StockLedgerRepository(db)
                    val outcome = com.voicetoinvoice.app.domain.action.VoiceCommandHandlers(db, stockLedger)
                        .handleVoidLast()
                    answer = outcome.spokenReply
                    finalStatus = if (outcome.committed > 0) SttJobStatus.AUTO_CONFIRMED else SttJobStatus.PARSED
                    clientTrace.put("outcome", "void_last")
                }
                AssistantIntent.PAYMENT_RECEIVED -> {
                    val stockLedger = com.voicetoinvoice.app.data.repository.StockLedgerRepository(db)
                    val jobId = java.util.UUID.randomUUID().toString()
                    val outcome = com.voicetoinvoice.app.domain.action.VoiceCommandHandlers(db, stockLedger)
                        .handlePaymentReceived(clean, jobId)
                    answer = outcome.spokenReply
                    // Ambiguous/unattributed payments must stay reviewable, never silently
                    // dropped -- crediting the wrong customer creates two wrong balances.
                    finalStatus = if (outcome.committed > 0) SttJobStatus.AUTO_CONFIRMED else SttJobStatus.PARSED
                    clientTrace.put("outcome", "payment_received")
                }
                AssistantIntent.ACTION_COMMAND -> {
                    val stockLedger = com.voicetoinvoice.app.data.repository.StockLedgerRepository(db)
                    val outcome = com.voicetoinvoice.app.domain.action.VoiceCommandHandlers(db, stockLedger)
                        .handleActionCommand(clean)
                    answer = outcome.spokenReply
                    (outcome.followUp as? com.voicetoinvoice.app.domain.action.FollowUp.LaunchAction)
                        ?.let { com.voicetoinvoice.app.domain.action.PendingActions.enqueue(it) }
                    finalStatus = if (outcome.committed > 0) SttJobStatus.AUTO_CONFIRMED else SttJobStatus.PARSED
                    clientTrace.put("outcome", "action_command")
                }
                AssistantIntent.SALE, AssistantIntent.CREDIT_SALE,
                AssistantIntent.STOCK_IN, AssistantIntent.WASTE,
                AssistantIntent.RETURN, AssistantIntent.PRICE_UPDATE,
                AssistantIntent.EXPIRY_WRITEOFF -> {
                    // No audio was captured during this press (mic was handed to the
                    // on-device recognizer) -- cannot route this to commitParsedLines or
                    // any item-level handler, which needs the server's audio-based parse.
                    // Redirect instead of silently doing nothing or guessing.
                    answer = "यह बिक्री जैसा लगा। कृपया नकद, उधार या माल बटन दबाकर बोलिए।"
                    finalStatus = SttJobStatus.PARSED
                    clientTrace.put("outcome", "redirected_write_shaped_no_audio")
                }
                else -> {
                    answer = ResponseComposer.formatUnrecognized()
                    finalStatus = SttJobStatus.PARSED
                    clientTrace.put("outcome", "unclassified")
                }
            }
        }

        try {
            // preferOffline=true: this path exists specifically to avoid the Grok TTS
            // network round trip (measured 1.3-2.4s) -- an assistant that answers
            // instantly from memory but then waits on the network to speak would still
            // fail the actual goal.
            speechOutput.speak(answer, preferOffline = true)
        } catch (e: Exception) {
            android.util.Log.w("AssistantFastPath", "TTS failed: ${e.message}")
        }

        clientTrace.put("answer", answer)

        db.sttJobDao().insertJob(
            SttJobRecord(
                audioFilePath = "",
                status = finalStatus,
                rawTranscript = clean,
                assistantAnswer = answer,
                isSanityFlagged = finalStatus == SttJobStatus.PARSED,
                recordedAtMs = pressStartMs,
                pressStartMs = pressStartMs,
                releaseMs = releaseMs,
                holdDurationMs = maxOf(releaseMs - pressStartMs, 0L),
                diagnosticTraceJson = JSONObject().apply { put("client", clientTrace) }.toString(),
                captureIntent = CaptureIntent.ASSISTANT,
                synced = false
            )
        )
    }
}
