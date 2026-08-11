package com.voicetoinvoice.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.voicetoinvoice.app.audio.BurstFlush
import com.voicetoinvoice.app.audio.OnDeviceSpeechRecognizer
import com.voicetoinvoice.app.audio.PttBurstCoalescer
import com.voicetoinvoice.app.audio.PttCaptureScope
import com.voicetoinvoice.app.audio.PttWindowLedger
import com.voicetoinvoice.app.audio.RollingAudioBuffer
import kotlinx.coroutines.withContext
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
    showLabelBelow: Boolean = true,
    onRecordingStateChange: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var isRecording by remember { mutableStateOf(false) }
    var pressTimestamp by remember { mutableStateOf(0L) }

    val scale by animateFloatAsState(
        targetValue = if (isRecording) 1.08f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "micScale"
    )

    val errorColor = MaterialTheme.colorScheme.error
    val animatedBgColor by animateColorAsState(
        targetValue = if (isRecording) errorColor else containerColor,
        animationSpec = tween(durationMillis = 150),
        label = "micBgColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000)
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000)
        ),
        label = "pulseAlpha"
    )

    val LONG_HOLD_WARNING_MS = 20_000L
    val SHORT_HOLD_ADVISORY_MS = 1000L

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size * 1.3f)
        ) {
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .size(size)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                            alpha = pulseAlpha
                        }
                        .background(
                            color = errorColor,
                            shape = CircleShape
                        )
                )
            }

            val buttonBrush = remember(isRecording, intent, animatedBgColor) {
                if (isRecording) {
                    Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFDC2626)))
                } else when (intent) {
                    CaptureIntent.SALE -> Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF047857)))
                    CaptureIntent.CREDIT_SALE -> Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFB45309)))
                    CaptureIntent.ASSISTANT -> Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF4F46E5)))
                    else -> Brush.linearGradient(listOf(animatedBgColor, animatedBgColor))
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(
                        brush = buttonBrush,
                        shape = CircleShape
                    )
                    .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape)
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

                            pttWindowLedger.recordPress(pressTimestamp)

                            tryAwaitRelease()

                            val releaseTimestamp = System.currentTimeMillis()
                            val holdDurationMs = Math.max(releaseTimestamp - pressTimestamp, 100L)

                            if (holdDurationMs >= LONG_HOLD_WARNING_MS) {
                                Toast.makeText(
                                    context,
                                    "बहुत लंबी रिकॉर्डिंग — कृपया थोड़े आइटम एक बार में बोलें",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else if (holdDurationMs < SHORT_HOLD_ADVISORY_MS) {
                                Toast.makeText(
                                    context,
                                    "बहुत छोटी रिकॉर्डिंग हो सकती है — ज़रूरत हो तो दोबारा बोलिए",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            try {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            isRecording = false
                            onRecordingStateChange?.invoke(false)

                            val pressTs = pressTimestamp
                            val releaseTs = releaseTimestamp

                            val processGroup: (com.voicetoinvoice.app.audio.CoalescedBurstGroup) -> Unit = { burstGroup ->
                                PttCaptureScope.scope.launch {
                                    val lastJob = db.sttJobDao().getLatestJob()
                                    val prevId = lastJob?.id
                                    val gapMs = if (lastJob != null && lastJob.audioEndMs > 0L) Math.max(0L, burstGroup.firstPressMs - lastJob.audioEndMs) else -1L

                                    val targetFile = File.createTempFile("voice_record_", ".wav", context.cacheDir)
                                    val result = rollingAudioBuffer.extractAudioWindowDetailed(
                                        startMs = burstGroup.startMs,
                                        endMs = burstGroup.endMs,
                                        outputFile = targetFile
                                    )

                                    when (result) {
                                        is RollingAudioBuffer.ExtractionResult.Success -> {
                                            val extractedAudio = result.file
                                            if (extractedAudio.length() > 0) {
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
                                        is RollingAudioBuffer.ExtractionResult.Failure -> {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "रिकॉर्डिंग नहीं हुई — दोबारा बोलिए", Toast.LENGTH_SHORT).show()
                                            }
                                            db.sttJobDao().insertJob(
                                                SttJobRecord(
                                                    audioFilePath = "",
                                                    status = SttJobStatus.FAILED,
                                                    captureIntent = intent,
                                                    rawTranscript = "extraction_null",
                                                    pressStartMs = burstGroup.firstPressMs,
                                                    releaseMs = burstGroup.lastReleaseMs,
                                                    diagnosticTraceJson = org.json.JSONObject().apply {
                                                        put("client", org.json.JSONObject().apply {
                                                            put("extraction_null", true)
                                                            put("reason", result.reason)
                                                            put("burst_start_ms", burstGroup.startMs)
                                                            put("burst_end_ms", burstGroup.endMs)
                                                            put("capture_epoch", rollingAudioBuffer.getCaptureEpoch())
                                                        })
                                                    }.toString(),
                                                    synced = false
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            val handleFlush: (BurstFlush?) -> Unit = { flush ->
                                when (flush) {
                                    is BurstFlush.Ready -> processGroup(flush.group)
                                    is BurstFlush.Dropped -> PttCaptureScope.scope.launch {
                                        db.sttJobDao().insertJob(
                                            SttJobRecord(
                                                audioFilePath = "",
                                                status = SttJobStatus.FAILED,
                                                captureIntent = intent,
                                                rawTranscript = "burst_dropped",
                                                pressStartMs = flush.firstPressMs,
                                                releaseMs = flush.lastReleaseMs,
                                                diagnosticTraceJson = org.json.JSONObject().apply {
                                                    put("client", org.json.JSONObject().apply {
                                                        put("burst_dropped", true)
                                                        put("reason", flush.reason)
                                                        put("first_press_ms", flush.firstPressMs)
                                                        put("last_release_ms", flush.lastReleaseMs)
                                                    })
                                                }.toString(),
                                                synced = false
                                            )
                                        )
                                    }
                                    null -> Unit
                                }
                            }

                            handleFlush(pttBurstCoalescer.recordPressRelease(pressTs, releaseTs, pttWindowLedger.lastConsumedEndMs()))

                            PttCaptureScope.scope.launch {
                                delay(pttBurstCoalescer.gapThresholdMs)
                                handleFlush(
                                    pttBurstCoalescer.checkAndFlushIfIdle(
                                        lastReleaseMs = releaseTs,
                                        nowMs = System.currentTimeMillis(),
                                        lastConsumedEndMs = pttWindowLedger.lastConsumedEndMs()
                                    )
                                )
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
        }

        if (showLabelBelow) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isRecording) "बोलिए..." else label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
