package com.voicetoinvoice.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.voicetoinvoice.app.audio.AudioRecorder
import com.voicetoinvoice.app.audio.OnDeviceSpeechRecognizer
import com.voicetoinvoice.app.audio.PttBurstCoalescer
import com.voicetoinvoice.app.audio.PttWindowLedger
import com.voicetoinvoice.app.audio.RollingAudioBuffer
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CaptureIntent
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
import com.voicetoinvoice.app.data.local.entity.SttJobStatus
import com.voicetoinvoice.app.domain.processor.BackgroundSttProcessor
import com.voicetoinvoice.app.domain.processor.SttWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PttMicButton(
    intent: CaptureIntent,
    label: String,
    db: AppDatabase,
    rollingAudioBuffer: RollingAudioBuffer,
    audioRecorder: AudioRecorder,
    pttBurstCoalescer: PttBurstCoalescer,
    pttWindowLedger: PttWindowLedger,
    onDeviceRecognizer: OnDeviceSpeechRecognizer,
    backgroundProcessor: BackgroundSttProcessor,
    permissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    onRecordingStateChange: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var isRecording by remember { mutableStateOf(false) }
    var pressTimestamp by remember { mutableStateOf(0L) }

    val LONG_HOLD_WARNING_MS = 20_000L

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .background(
                    color = if (isRecording) MaterialTheme.colorScheme.error else containerColor,
                    shape = CircleShape
                )
                .pointerInput(intent) {
                    detectTapGestures(
                        onPress = {
                            val micGranted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            if (!micGranted) {
                                val activity = context as? Activity
                                val canAskAgain = activity?.let {
                                    ActivityCompat.shouldShowRequestPermissionRationale(
                                        it, Manifest.permission.RECORD_AUDIO
                                    )
                                } ?: true

                                if (canAskAgain) {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Microphone access is blocked. Please enable it in App Settings.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(settingsIntent)
                                }
                                return@detectTapGestures
                            }

                            try {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            pressTimestamp = System.currentTimeMillis()
                            isRecording = true
                            onRecordingStateChange?.invoke(true)
                            audioRecorder.triggerHapticVibration()

                            // Assistant fast path (Docs/assistant_speed_and_pipeline_fix_plan.md
                            // §3/§4): entirely separate from the recording/upload flow below.
                            // The always-on RollingAudioBuffer is released for the duration of
                            // this press so the on-device recognizer gets exclusive mic access --
                            // every historical trace in this app shows on-device STT failing
                            // 100% of the time it ran concurrently with the ring buffer's
                            // AudioRecord. No audio is captured for this press; see
                            // AssistantFastPath's doc comment for why that trade is acceptable
                            // for a question mic.
                            if (intent == CaptureIntent.ASSISTANT) {
                                pttWindowLedger.recordPress(pressTimestamp)
                                try { onDeviceRecognizer.startListening("hi-IN") } catch (e: Exception) {}

                                tryAwaitRelease()

                                val assistantReleaseTs = System.currentTimeMillis()
                                val holdDurationMs = Math.max(assistantReleaseTs - pressTimestamp, 100L)

                                if (holdDurationMs >= LONG_HOLD_WARNING_MS) {
                                    Toast.makeText(
                                        context,
                                        "बहुत लंबी रिकॉर्डिंग — कृपया थोड़े आइटम एक बार में बोलें",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                                try {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }

                                isRecording = false
                                onRecordingStateChange?.invoke(false)
                                try { onDeviceRecognizer.finishListening() } catch (e: Exception) {}

                                val assistantPressTs = pressTimestamp
                                scope.launch(Dispatchers.IO) {
                                    val result = onDeviceRecognizer.awaitResult(2500L)
                                    if (result.transcript.isNotBlank()) {
                                        com.voicetoinvoice.app.domain.voice.AssistantFastPath.handle(
                                            context = context,
                                            db = db,
                                            rollingAudioBuffer = rollingAudioBuffer,
                                            transcript = result.transcript,
                                            pressStartMs = assistantPressTs,
                                            releaseMs = assistantReleaseTs
                                        )
                                    } else {
                                        val flushed = pttBurstCoalescer.recordPressRelease(
                                            assistantPressTs, assistantReleaseTs, pttWindowLedger.lastConsumedEndMs()
                                        )
                                        val group = flushed ?: pttBurstCoalescer.forceFlush(pttWindowLedger.lastConsumedEndMs())
                                        if (group != null) {
                                            val targetFile = File.createTempFile("voice_record_", ".wav", context.cacheDir)
                                            val extractedAudio = rollingAudioBuffer.extractAudioWindow(
                                                startMs = group.startMs,
                                                endMs = group.endMs,
                                                outputFile = targetFile,
                                                floorStartMs = group.startMs
                                            )
                                            if (extractedAudio != null && extractedAudio.length() > 0) {
                                                pttWindowLedger.commitWindow(group.startMs, group.endMs)
                                                val job = SttJobRecord(
                                                    audioFilePath = extractedAudio.absolutePath,
                                                    status = SttJobStatus.QUEUED,
                                                    pressStartMs = group.firstPressMs,
                                                    releaseMs = group.lastReleaseMs,
                                                    audioStartMs = group.startMs,
                                                    audioEndMs = group.endMs,
                                                    utteranceBoundariesJson = group.utteranceBoundariesJson(),
                                                    pressCount = group.pressCount,
                                                    captureIntent = CaptureIntent.ASSISTANT
                                                )
                                                db.sttJobDao().insertJob(job)
                                                val workRequest = OneTimeWorkRequestBuilder<SttWorker>()
                                                    .setInputData(
                                                        workDataOf(
                                                            SttWorker.KEY_JOB_ID to job.id,
                                                            SttWorker.KEY_AUDIO_PATH to extractedAudio.absolutePath
                                                        )
                                                    )
                                                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                                    .build()
                                                WorkManager.getInstance(context).enqueue(workRequest)
                                            }
                                        }
                                    }
                                }
                                return@detectTapGestures
                            }

                            pttWindowLedger.recordPress(pressTimestamp)
                            try { onDeviceRecognizer.startListening("hi-IN") } catch (e: Exception) {}

                            tryAwaitRelease()

                            val releaseTimestamp = System.currentTimeMillis()
                            val holdDurationMs = Math.max(releaseTimestamp - pressTimestamp, 100L)

                            if (holdDurationMs >= LONG_HOLD_WARNING_MS) {
                                Toast.makeText(
                                    context,
                                    "बहुत लंबी रिकॉर्डिंग — कृपया थोड़े आइटम एक बार में बोलें",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            try {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            isRecording = false
                            onRecordingStateChange?.invoke(false)
                            try { onDeviceRecognizer.finishListening() } catch (e: Exception) {}

                            val pressTs = pressTimestamp
                            val releaseTs = releaseTimestamp

                            val processGroup: (com.voicetoinvoice.app.audio.CoalescedBurstGroup) -> Unit = { burstGroup ->
                                scope.launch(Dispatchers.IO) {
                                    val lastJob = db.sttJobDao().getLatestJob()
                                    val prevId = lastJob?.id
                                    val gapMs = if (lastJob != null && lastJob.audioEndMs > 0L) Math.max(0L, burstGroup.firstPressMs - lastJob.audioEndMs) else -1L

                                    val targetFile = File.createTempFile("voice_record_", ".wav", context.cacheDir)
                                    val extractedAudio = rollingAudioBuffer.extractAudioWindow(
                                        startMs = burstGroup.startMs,
                                        endMs = burstGroup.endMs,
                                        outputFile = targetFile,
                                        floorStartMs = burstGroup.startMs
                                    )

                                    if (extractedAudio != null && extractedAudio.length() > 0) {
                                        pttWindowLedger.commitWindow(burstGroup.startMs, burstGroup.endMs)

                                        val job = SttJobRecord(
                                            audioFilePath = extractedAudio.absolutePath,
                                            status = SttJobStatus.QUEUED,
                                            holdDurationMs = Math.max(100L, burstGroup.lastReleaseMs - burstGroup.firstPressMs),
                                            pressStartMs = burstGroup.firstPressMs,
                                            releaseMs = burstGroup.lastReleaseMs,
                                            audioStartMs = burstGroup.startMs,
                                            audioEndMs = burstGroup.endMs,
                                            previousJobId = prevId,
                                            precedingGapMs = gapMs,
                                            utteranceBoundariesJson = burstGroup.utteranceBoundariesJson(),
                                            pressCount = burstGroup.pressCount,
                                            captureIntent = intent
                                        )
                                        db.sttJobDao().insertJob(job)

                                        // Backfill on-device speech transcript asynchronously
                                        scope.launch(Dispatchers.IO) {
                                            val res = onDeviceRecognizer.awaitResult(4000L)
                                            db.sttJobDao().getJobById(job.id)?.let { currentJob ->
                                                db.sttJobDao().updateJob(
                                                    currentJob.copy(
                                                        onDeviceTranscript = res.transcript,
                                                        onDeviceStatus = res.status
                                                    )
                                                )
                                            }
                                        }

                                        // Expedited WorkManager execution for closed-app / server-first processing
                                        try {
                                            val workRequest = OneTimeWorkRequestBuilder<SttWorker>()
                                                .setInputData(
                                                    workDataOf(
                                                        SttWorker.KEY_JOB_ID to job.id,
                                                        SttWorker.KEY_AUDIO_PATH to extractedAudio.absolutePath
                                                    )
                                                )
                                                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                                .build()

                                            WorkManager.getInstance(context).enqueue(workRequest)
                                        } catch (e: Exception) {
                                            backgroundProcessor.triggerQueueProcessing()
                                        }
                                    }
                                }
                            }

                            val immediateFlushed = pttBurstCoalescer.recordPressRelease(pressTs, releaseTs, pttWindowLedger.lastConsumedEndMs())
                            if (immediateFlushed != null) {
                                processGroup(immediateFlushed)
                            }

                            scope.launch(Dispatchers.IO) {
                                delay(pttBurstCoalescer.gapThresholdMs)
                                val idleFlushed = pttBurstCoalescer.checkAndFlushIfIdle(
                                    lastReleaseMs = releaseTs,
                                    nowMs = System.currentTimeMillis(),
                                    lastConsumedEndMs = pttWindowLedger.lastConsumedEndMs()
                                )
                                if (idleFlushed != null) {
                                    processGroup(idleFlushed)
                                }
                            }
                        }
                    )
                }
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(size * 0.45f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isRecording) "बोलिए..." else label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
