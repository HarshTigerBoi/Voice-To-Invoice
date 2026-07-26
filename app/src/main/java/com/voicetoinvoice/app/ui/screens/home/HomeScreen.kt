package com.voicetoinvoice.app.ui.screens.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.voicetoinvoice.app.audio.AudioRecorder
import com.voicetoinvoice.app.audio.OnDeviceSpeechRecognizer
import com.voicetoinvoice.app.audio.PttWindowLedger
import com.voicetoinvoice.app.audio.RollingAudioBuffer
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
import com.voicetoinvoice.app.data.local.entity.SttJobStatus
import com.voicetoinvoice.app.domain.parser.ParsedVoiceSale
import com.voicetoinvoice.app.domain.parser.VoiceParser
import com.voicetoinvoice.app.domain.processor.SttWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.voicetoinvoice.app.domain.processor.BackgroundSttProcessor
import com.voicetoinvoice.app.ui.components.ConfirmSaleDialog
import com.voicetoinvoice.app.ui.components.ManualStepperComponent
import com.voicetoinvoice.app.ui.components.PendingConfirmationsBar
import com.voicetoinvoice.app.ui.components.PendingConfirmationsSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Audio captured before the button-down event, to recover the leading consonant lost
 *  to input latency and speech onset preceding the press. */
private const val PRE_ROLL_MS = 300L

/** Audio captured after release, to recover trailing phonemes. */
private const val POST_ROLL_MS = 300L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    todayTotalSales: Double,
    catalog: List<CatalogItem>,
    voiceParser: VoiceParser = remember { VoiceParser() },
    onNavigateToUdhaar: () -> Unit,
    onNavigateToSuppliers: () -> Unit = {},
    onNavigateToPriceUpdate: () -> Unit,
    onNavigateToLogs: () -> Unit = {},
    onNavigateToSummary: () -> Unit = {},
    onConfirmSale: (ParsedVoiceSale) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val audioRecorder = remember { AudioRecorder(context) }
    val rollingAudioBuffer = remember { RollingAudioBuffer(context) }
    val pttWindowLedger = remember { PttWindowLedger() }
    val onDeviceRecognizer = remember { OnDeviceSpeechRecognizer(context) }

    // Start background circular audio buffer on screen launch for zero mic-warmup latency
    DisposableEffect(Unit) {
        rollingAudioBuffer.startRollingBuffer()
        onDispose {
            rollingAudioBuffer.stopRollingBuffer()
            onDeviceRecognizer.release()
        }
    }

    // BUG-1 Fix: Inject the SAME active RollingAudioBuffer instance into BackgroundSttProcessor
    val backgroundProcessor = remember { BackgroundSttProcessor(context, scope, rollingAudioBuffer) }

    // Auto-cleanup any jobs that got stuck in processing from a previous interrupted session
    LaunchedEffect(Unit) {
        val thresholdMs = System.currentTimeMillis() - 60000L // 1 minute ago
        db.sttJobDao().markStuckJobsAsFailed(thresholdMs)
    }

    // Collect pending parsed jobs asynchronously
    val pendingJobs by db.sttJobDao().getParsedJobsFlow().collectAsState(initial = emptyList())

    var isRecording by remember { mutableStateOf(false) }
    var activeParsedSale by remember { mutableStateOf<ParsedVoiceSale?>(null) }
    var showPendingSheet by remember { mutableStateOf(false) }

    // Fallback Manual Text Input Dialog state
    var showManualTextDialog by remember { mutableStateOf(false) }
    var manualInputText by remember { mutableStateOf("") }

    // Timestamp tracking for individual press-to-talk jobs
    var pressTimestamp by remember { mutableLongStateOf(0L) }
    // Fix 1: Track last job's audio end boundary to prevent window overlap across rapid holds
    var lastAudioEndMs by remember { mutableLongStateOf(0L) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Permission launcher for RECORD_AUDIO
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Microphone permission granted.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Microphone permission is required for voice recording.", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Shop Ledger") },
                actions = {
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.Default.Info, contentDescription = "Voice Processing Logs")
                    }
                    Text(
                        text = "Today: ₹${todayTotalSales.toInt()}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Secondary Quick Action Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(onClick = onNavigateToUdhaar) {
                        Icon(Icons.Default.Person, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Udhaar")
                    }
                    OutlinedButton(onClick = onNavigateToSuppliers) {
                        Icon(Icons.Default.Person, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Suppliers")
                    }
                    OutlinedButton(onClick = onNavigateToPriceUpdate) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Prices")
                    }
                    OutlinedButton(onClick = onNavigateToSummary) {
                        Icon(Icons.Default.Receipt, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Summary")
                    }
                    OutlinedButton(onClick = onNavigateToLogs) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Logs")
                    }
                }

                Spacer(modifier = Modifier.weight(0.5f))

                // Main Push-to-Talk (PTT) Button Container with Fallback Text Button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(180.dp)
                            .background(
                                color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        val micGranted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED

                                        // BUG-2 Fix: Handle Permanent Mic Permission Denial UX
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
                                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                    data = Uri.fromParts("package", context.packageName, null)
                                                }
                                                context.startActivity(intent)
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
                                        audioRecorder.triggerHapticVibration()

                                        pttWindowLedger.recordPress(pressTimestamp)
                                        try { onDeviceRecognizer.startListening("hi-IN") } catch (e: Exception) {}

                                        tryAwaitRelease()

                                        val releaseTimestamp = System.currentTimeMillis()
                                        val holdDurationMs = Math.max(releaseTimestamp - pressTimestamp, 100L)

                                        try {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }

                                        isRecording = false
                                        try { onDeviceRecognizer.finishListening() } catch (e: Exception) {}

                                        val pressTs = pressTimestamp
                                        val releaseTs = releaseTimestamp

                                        scope.launch(Dispatchers.IO) {
                                            delay(POST_ROLL_MS) // let the tail phonemes land in the ring buffer

                                            val lastEnd = pttWindowLedger.lastConsumedEndMs()
                                            val nextPress = pttWindowLedger.nextPressAfter(releaseTs)
                                            val clampedStartMs = Math.max(pressTs - PRE_ROLL_MS, lastEnd)
                                            val rawEndMs = releaseTs + POST_ROLL_MS
                                            val clampedEndMs = if (nextPress != null) Math.min(rawEndMs, nextPress) else rawEndMs

                                            val lastJob = db.sttJobDao().getLatestJob()
                                            val prevId = lastJob?.id
                                            val gapMs = if (lastJob != null && lastJob.audioEndMs > 0L) Math.max(0L, pressTs - lastJob.audioEndMs) else -1L

                                            val targetFile = File.createTempFile("voice_record_", ".wav", context.cacheDir)
                                            val extractedAudio = rollingAudioBuffer.extractAudioWindow(
                                                startMs = clampedStartMs,
                                                endMs = clampedEndMs,
                                                outputFile = targetFile,
                                                floorStartMs = clampedStartMs
                                            )

                                            if (extractedAudio != null && extractedAudio.length() > 0) {
                                                pttWindowLedger.commitWindow(clampedStartMs, clampedEndMs)

                                                val job = SttJobRecord(
                                                    audioFilePath = extractedAudio.absolutePath,
                                                    status = SttJobStatus.QUEUED,
                                                    holdDurationMs = holdDurationMs,
                                                    pressStartMs = pressTs,
                                                    releaseMs = releaseTs,
                                                    audioStartMs = clampedStartMs,
                                                    audioEndMs = clampedEndMs,
                                                    previousJobId = prevId,
                                                    precedingGapMs = gapMs
                                                )
                                                db.sttJobDao().insertJob(job)

                                                // Backfill on-device speech transcript asynchronously
                                                scope.launch(Dispatchers.IO) {
                                                    val text = onDeviceRecognizer.awaitTranscript(4000L)
                                                    if (text.isNotBlank()) {
                                                        db.sttJobDao().getJobById(job.id)?.let { currentJob ->
                                                            db.sttJobDao().updateJob(currentJob.copy(onDeviceTranscript = text))
                                                        }
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
                                                    // Fallback to in-app processor if WorkManager fails
                                                    backgroundProcessor.triggerQueueProcessing()
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Push to Talk",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isRecording) "Listening..." else "HOLD TO SPEAK",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Direct Fallback Button: Type Text
                    TextButton(
                        onClick = {
                            manualInputText = ""
                            showManualTextDialog = true
                        }
                    ) {
                        Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Type sale manually (Fallback)", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(modifier = Modifier.weight(0.5f))

                // Quick Manual Stepper Grid
                ManualStepperComponent(
                    topItems = catalog.take(4),
                    onAddSale = { item, qty ->
                        val sale = ParsedVoiceSale(item, "Manual Entry", qty, item.unitId, qty * item.price, 1.0f)
                        onConfirmSale(sale)
                    }
                )
            }

            // Floating Pending Sales Pill Badge
            PendingConfirmationsBar(
                pendingCount = pendingJobs.size,
                onClick = { showPendingSheet = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            )
        }
    }

    // Modal Bottom Sheet for Pending Confirmations
    if (showPendingSheet) {
        PendingConfirmationsSheet(
            pendingJobs = pendingJobs,
            onConfirmJob = { job ->
                val matched = catalog.find { it.id == job.parsedItemId || it.name.equals(job.parsedItemName, ignoreCase = true) }
                val item = matched ?: CatalogItem(name = job.parsedItemName, unitId = job.parsedUnit, price = if (job.parsedQty > 0) job.parsedTotal / job.parsedQty else 0.0)

                scope.launch(Dispatchers.IO) {
                    if (matched == null) {
                        // Persist newly-recognized items so future sales of this item match
                        // automatically instead of landing back in Pending Confirmations at ₹0 every time.
                        db.catalogDao().insertOrUpdate(item)
                    }

                    // Rebuild step_6_final_outcome so the synced diagnostic trace reflects the
                    // confirmed item/price instead of staying stuck at the original PARSED values.
                    val traceObj = try { JSONObject(job.diagnosticTraceJson) } catch (_: Exception) { JSONObject() }
                    traceObj.put("step_6_final_outcome", JSONArray().put(JSONObject().apply {
                        put("itemName", item.name)
                        put("matchedCatalogId", item.id)
                        put("quantity", job.parsedQty)
                        put("unit", job.parsedUnit)
                        put("priceAtSale", if (job.parsedQty > 0) job.parsedTotal / job.parsedQty else item.price)
                        put("estimatedTotal", job.parsedTotal)
                        put("confidence", 1.0)
                        put("isSanityFlagged", false)
                        put("autoConfirmedToLedger", true)
                        put("userResolved", true)
                    }))

                    val updatedJob = job.copy(
                        status = com.voicetoinvoice.app.data.local.entity.SttJobStatus.CONFIRMED,
                        parsedItemName = item.name,
                        parsedQty = job.parsedQty,
                        parsedUnit = job.parsedUnit,
                        parsedTotal = job.parsedTotal,
                        isSanityFlagged = false,
                        diagnosticTraceJson = traceObj.toString(),
                        synced = false
                    )
                    db.sttJobDao().updateJob(updatedJob)

                    val txRecord = com.voicetoinvoice.app.data.local.entity.TransactionRecord(
                        itemId = item.id,
                        itemName = item.name,
                        quantity = job.parsedQty,
                        priceAtSale = if (job.parsedQty > 0) job.parsedTotal / job.parsedQty else item.price,
                        total = job.parsedTotal,
                        source = com.voicetoinvoice.app.data.local.entity.TransactionSource.VOICE,
                        rawTranscript = job.rawTranscript,
                        audioFilePath = job.audioFilePath,
                        jobId = job.id,
                        audioCloudUrl = "${com.voicetoinvoice.app.network.SupabaseConfig.SUPABASE_URL}/storage/v1/object/public/voice-recordings/${job.id}.wav",
                        synced = false
                    )
                    db.transactionDao().insert(txRecord)

                    val syncEngine = com.voicetoinvoice.app.data.sync.SyncEngine(db.transactionDao(), db.stockInDao(), db.catalogDao(), db.creditDao(), db.sttJobDao(), db.supplierDao())
                    syncEngine.syncAllUnsynced()
                }
            },
            onDiscardJob = { job ->
                scope.launch(Dispatchers.IO) {
                    val discardedJob = job.copy(
                        status = com.voicetoinvoice.app.data.local.entity.SttJobStatus.FAILED,
                        errorMessage = "Discarded by User",
                        synced = false
                    )
                    db.sttJobDao().updateJob(discardedJob)

                    val syncEngine = com.voicetoinvoice.app.data.sync.SyncEngine(db.transactionDao(), db.stockInDao(), db.catalogDao(), db.creditDao(), db.sttJobDao(), db.supplierDao())
                    syncEngine.syncAllUnsynced()
                }
            },
            onDismiss = { showPendingSheet = false }
        )
    }

    // Active Confirm Sale Modal (if editing single sale)
    activeParsedSale?.let { sale ->
        ConfirmSaleDialog(
            parsedSale = sale,
            onConfirm = { confirmed ->
                onConfirmSale(confirmed)
                activeParsedSale = null
            },
            onEdit = {
                activeParsedSale = null
                manualInputText = sale.rawTranscript
                showManualTextDialog = true
            },
            onDismiss = {
                activeParsedSale = null
            }
        )
    }

    // Manual Text Fallback Dialog
    if (showManualTextDialog) {
        AlertDialog(
            onDismissRequest = { showManualTextDialog = false },
            title = { Text("Manual Sale Entry") },
            text = {
                Column {
                    Text("Type sale details (e.g. '2 kg tamatar', '50 rs aaloo', '1 paao adrak'):")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualInputText,
                        onValueChange = { manualInputText = it },
                        placeholder = { Text("e.g. 2 kg tamatar") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (manualInputText.isNotBlank()) {
                            showManualTextDialog = false
                            val parsedResult = voiceParser.parseUtterance(manualInputText.trim(), catalog)
                            activeParsedSale = parsedResult
                        }
                    }
                ) {
                    Text("Parse Sale")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualTextDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
