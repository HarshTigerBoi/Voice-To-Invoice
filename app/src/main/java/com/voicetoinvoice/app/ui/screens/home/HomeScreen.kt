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
import androidx.compose.material.icons.filled.CheckCircle
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
import com.voicetoinvoice.app.audio.CoalescedBurstGroup
import com.voicetoinvoice.app.audio.OnDeviceSpeechRecognizer
import com.voicetoinvoice.app.audio.PttBurstCoalescer
import com.voicetoinvoice.app.audio.PttWindowLedger
import com.voicetoinvoice.app.audio.RollingAudioBuffer
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.CaptureIntent
import com.voicetoinvoice.app.data.local.entity.CustomerRecord
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
import com.voicetoinvoice.app.data.local.entity.SttJobStatus
import com.voicetoinvoice.app.domain.parser.ParsedVoiceSale
import com.voicetoinvoice.app.domain.parser.VoiceParser
import com.voicetoinvoice.app.domain.processor.SttWorker
import com.voicetoinvoice.app.domain.resolver.EntityResolver
import com.voicetoinvoice.app.ui.components.PttMicButton
import com.voicetoinvoice.app.ui.screens.customer.UdhaarPickerOverlay
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Audio captured before the first button-down event of a group, to recover leading consonant lost
 *  to input latency and speech onset preceding the press. */
private const val PRE_ROLL_MS = 300L

/** Audio captured after release, to recover trailing phonemes. */
private const val POST_ROLL_MS = 300L

/** RollingAudioBuffer holds 120s of PCM; a hold approaching that silently starts losing
 *  its OWN leading audio (minStartAllowed = totalWritten - bufferCapacity truncates the
 *  window front with no error). Warn before that happens instead of silently booking a
 *  wrong/incomplete order for a long multi-item recitation. */
private const val LONG_HOLD_WARNING_MS = 115000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    todayTotalSales: Double,
    catalog: List<CatalogItem>,
    voiceParser: VoiceParser = remember { VoiceParser() },
    onNavigateToLogs: () -> Unit = {},
    onNavigateToSummary: () -> Unit = {},
    onNavigateToReports: () -> Unit = {},
    onAddNewCustomerForCredit: (creditId: String) -> Unit = {},
    // Fix 7: shared app-wide audio pipeline (MainActivity owns the single RollingAudioBuffer
    // instance so the assistant mic doesn't fight this screen's mics over the microphone).
    // Defaults create a standalone instance so this composable still works if previewed or
    // tested on its own.
    sharedAudioRecorder: AudioRecorder? = null,
    sharedRollingAudioBuffer: RollingAudioBuffer? = null,
    sharedPttBurstCoalescer: PttBurstCoalescer? = null,
    sharedOnDeviceRecognizer: OnDeviceSpeechRecognizer? = null,
    sharedBackgroundProcessor: BackgroundSttProcessor? = null,
    onConfirmSale: (ParsedVoiceSale) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val audioRecorder = sharedAudioRecorder ?: remember { AudioRecorder(context) }
    val rollingAudioBuffer = sharedRollingAudioBuffer ?: remember { RollingAudioBuffer(context) }
    val pttWindowLedger = remember { PttWindowLedger.getInstance() }
    val pttBurstCoalescer = sharedPttBurstCoalescer ?: remember(rollingAudioBuffer) {
        PttBurstCoalescer(PRE_ROLL_MS, POST_ROLL_MS, (rollingAudioBuffer.getBufferDurationSeconds() - 5) * 1000L)
    }
    val onDeviceRecognizer = sharedOnDeviceRecognizer ?: remember { OnDeviceSpeechRecognizer(context) }

    // Only start/stop the rolling buffer here when this screen owns it (no shared instance
    // was supplied) -- when MainActivity supplies a shared buffer, IT owns the start/stop
    // lifecycle for the whole app session.
    if (sharedRollingAudioBuffer == null) {
        DisposableEffect(Unit) {
            rollingAudioBuffer.startRollingBuffer()
            onDispose {
                rollingAudioBuffer.stopRollingBuffer()
                onDeviceRecognizer.release()
            }
        }
    }

    // Enqueues QUEUED jobs onto WorkManager/SttWorker -- the actual parse pipeline is
    // server-side (process-voice-job), so this no longer needs the RollingAudioBuffer.
    val backgroundProcessor = sharedBackgroundProcessor ?: remember { BackgroundSttProcessor(context, scope) }

    // Auto-cleanup any jobs that got stuck in processing from a previous interrupted session
    LaunchedEffect(Unit) {
        val thresholdMs = System.currentTimeMillis() - 60000L // 1 minute ago
        db.sttJobDao().markStuckJobsAsFailed(thresholdMs)
    }

    // Collect pending parsed jobs asynchronously
    val pendingJobs by db.sttJobDao().getParsedJobsFlow().collectAsState(initial = emptyList())
    // Count PENDING LINES, not jobs -- a single multi-item recording can have 3 lines
    // where 2 already booked and 1 needs review; the pill should say "1", not "1 job".
    val pendingLineCount = remember(pendingJobs) {
        pendingJobs.sumOf { job ->
            com.voicetoinvoice.app.ui.components.parsePendingLines(job).count { !it.committed && !it.resolved }
        }
    }

    // Udhaar customer picker (Fix 2 / ISSUE-039): credit sales that SttWorker couldn't
    // silently auto-assign a customer to. Non-blocking by design -- pressing any mic
    // collapses this to a badge instead of blocking the next recording (principle #2).
    val unassignedCredits by db.customerDao().getUnassignedCredits().collectAsState(initial = emptyList())
    val activeCustomers by db.customerDao().getActiveCustomers().collectAsState(initial = emptyList())
    val oldestUnassignedCredit = unassignedCredits.firstOrNull()
    var pickerDismissedToBadge by remember { mutableStateOf(false) }
    LaunchedEffect(oldestUnassignedCredit?.id) {
        // A new unassigned credit (different from whatever badge state was showing) should
        // re-surface the picker rather than staying silently minimized forever.
        pickerDismissedToBadge = false
    }
    val rankedCustomerCandidates = remember(oldestUnassignedCredit?.id, activeCustomers) {
        if (oldestUnassignedCredit != null) {
            EntityResolver<CustomerRecord>().resolve("", activeCustomers).candidates
        } else emptyList()
    }
    val onAnyMicPressed: (Boolean) -> Unit = { recording -> if (recording) pickerDismissedToBadge = true }

    var isRecording by remember { mutableStateOf(false) }
    var activeParsedSale by remember { mutableStateOf<ParsedVoiceSale?>(null) }
    var showPendingSheet by remember { mutableStateOf(false) }
    var showCommandFeed by remember { mutableStateOf(false) }

    // Command Feed (Docs/master_build_plan.md §2.5): every spoken command from the last 24h
    // with a clear status, so "confirm only when unsure" doesn't leave the shopkeeper wondering
    // what happened to everything ELSE they said.
    val allRecentJobs by db.sttJobDao().getAllJobsTraceLogsFlow().collectAsState(initial = emptyList())
    val commandFeedJobs = remember(allRecentJobs) {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        allRecentJobs.filter { it.recordedAtMs >= since }
    }
    val inFlightCount = remember(commandFeedJobs) {
        commandFeedJobs.count {
            it.status == com.voicetoinvoice.app.data.local.entity.SttJobStatus.QUEUED ||
                it.status == com.voicetoinvoice.app.data.local.entity.SttJobStatus.TRANSCRIBING
        }
    }

    // Health score + alerts: a one-glance signal on Home, with the full breakdown one tap away
    // on Reports. Loaded once per Home visit rather than kept live -- these touch several
    // queries each, and a shopkeeper glancing at the home screen doesn't need it to recompute
    // on every recomposition (Reports always shows a fresh read).
    var topAlertCount by remember { mutableStateOf(0) }
    var topAlertMessage by remember { mutableStateOf<String?>(null) }
    var healthScoreValue by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val alerts = com.voicetoinvoice.app.domain.alert.AlertEngine(db).computeAlerts()
            topAlertCount = alerts.size
            topAlertMessage = alerts.firstOrNull()?.message
            healthScoreValue = com.voicetoinvoice.app.domain.query.HealthScore(db).compute().score
        }
    }

    // Fallback Manual Text Input Dialog state
    var showManualTextDialog by remember { mutableStateOf(false) }
    var manualInputText by remember { mutableStateOf("") }

    // Timestamp tracking for individual press-to-talk jobs
    var pressTimestamp by remember { mutableLongStateOf(0L) }

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
                    IconButton(onClick = onNavigateToSummary) {
                        Icon(Icons.Default.Receipt, contentDescription = "Summary")
                    }
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
                // Fix 4+5 (gap_analysis_and_fix_plan.md): this row used to overlap the
                // PendingConfirmationsBar banner and was the only way to reach Summary from
                // the tab bar. Udhaar/Suppliers/Prices/Summary/Logs now live as quick-link
                // icons in the हिसाब tab's top bar instead -- see CustomerListScreen.
                Spacer(modifier = Modifier.weight(0.5f))

                // Main Push-to-Talk (PTT) Mic Buttons: Cash (Green) & Udhaar (Amber)
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 1. Cash Sale Mic (Large, Green)
                    PttMicButton(
                        intent = CaptureIntent.SALE,
                        label = "नकद बेचो",
                        size = 150.dp,
                        containerColor = Color(0xFF2E7D32),
                        db = db,
                        rollingAudioBuffer = rollingAudioBuffer,
                        audioRecorder = audioRecorder,
                        pttBurstCoalescer = pttBurstCoalescer,
                        pttWindowLedger = pttWindowLedger,
                        onDeviceRecognizer = onDeviceRecognizer,
                        backgroundProcessor = backgroundProcessor,
                        permissionLauncher = permissionLauncher,
                        onRecordingStateChange = onAnyMicPressed
                    )

                    Spacer(modifier = Modifier.width(24.dp))

                    // 2. Udhaar Credit Sale Mic (Medium, Amber)
                    PttMicButton(
                        intent = CaptureIntent.CREDIT_SALE,
                        label = "उधार बेचो",
                        size = 120.dp,
                        containerColor = Color(0xFFE65100),
                        db = db,
                        rollingAudioBuffer = rollingAudioBuffer,
                        audioRecorder = audioRecorder,
                        pttBurstCoalescer = pttBurstCoalescer,
                        pttWindowLedger = pttWindowLedger,
                        onDeviceRecognizer = onDeviceRecognizer,
                        backgroundProcessor = backgroundProcessor,
                        permissionLauncher = permissionLauncher,
                        onRecordingStateChange = onAnyMicPressed
                    )
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
                pendingCount = pendingLineCount,
                onClick = { showPendingSheet = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            )

            // Both status badges anchor to the TOP, not the bottom: the mic area's enclosing Box
            // fills the whole screen, and the Quick Manual Stepper section below the mics grows
            // with the catalog -- a bottom-anchored badge collided with its cards on a real
            // device (a 150dp stepper grid reaching within 96dp of the screen bottom). The top,
            // just below PendingConfirmationsBar, has no such variable-height content beneath it.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (pendingLineCount > 0) 72.dp else 16.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Business health at a glance -- full breakdown and every alert live on Reports;
                // this is deliberately just a number and the single most urgent alert, so Home
                // stays about the mic, not about analytics.
                healthScoreValue?.let { score ->
                    AssistChip(
                        onClick = onNavigateToReports,
                        label = {
                            Text(
                                if (topAlertCount > 0) "स्कोर $score · $topAlertCount अलर्ट" else "स्कोर $score"
                            )
                        }
                    )
                } ?: Spacer(Modifier.width(1.dp))

                // Command Feed entry point -- lives near the mic per the brief's "clear status
                // for every command". Only visible once there is something to show, so it
                // doesn't clutter a brand-new shop's home screen.
                if (commandFeedJobs.isNotEmpty()) {
                    AssistChip(
                        onClick = { showCommandFeed = true },
                        label = {
                            Text(
                                if (inFlightCount > 0) "प्रोसेस हो रहा ($inFlightCount)" else "हाल की गतिविधि"
                            )
                        },
                        leadingIcon = {
                            if (inFlightCount > 0) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    )
                }
            }

            // Udhaar customer picker (Fix 2 / ISSUE-039) -- anchored to the bottom so it
            // never collides with the pending-sales bar above. Pressing either mic
            // collapses this to the small badge instead of blocking the next recording.
            if (oldestUnassignedCredit != null) {
                if (pickerDismissedToBadge) {
                    AssistChip(
                        onClick = { pickerDismissedToBadge = false },
                        label = { Text("${unassignedCredits.size} उधार — किसका?") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                    )
                } else {
                    UdhaarPickerOverlay(
                        unassignedAmount = oldestUnassignedCredit.amount,
                        rankedCandidates = rankedCustomerCandidates,
                        onCustomerSelected = { customer ->
                            scope.launch(Dispatchers.IO) {
                                db.customerDao().assignCustomerToCredit(oldestUnassignedCredit, customer.id)
                            }
                        },
                        onAddNewCustomer = { onAddNewCustomerForCredit(oldestUnassignedCredit.id) },
                        onSkip = { pickerDismissedToBadge = true },
                        onDismissToBadge = { pickerDismissedToBadge = true },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }

    // Modal Bottom Sheet for Pending Confirmations
    if (showPendingSheet) {
        PendingConfirmationsSheet(
            pendingJobs = pendingJobs,
            catalog = catalog,
            onConfirmLine = { job, line, isLastPendingLine, resolvedItemId, rate ->
                // Resolution order (ISSUE-030): trust an explicit catalog pick first: fall
                // back to a case-insensitive name lookup before ever creating a new row --
                // blind-inserting on every confirm is how the catalog accumulated
                // duplicate rows (e.g. Aaloo x3 at two different prices) in the first
                // place. Only create when genuinely nothing matches.
                val isStockIntent = job.captureIntent == CaptureIntent.STOCK_IN || job.captureIntent == CaptureIntent.WASTE
                val isCreditSale = job.captureIntent == CaptureIntent.CREDIT_SALE
                val byId = resolvedItemId?.let { id -> catalog.find { it.id == id } }
                val byName = byId ?: catalog.find { it.name.equals(line.itemName, ignoreCase = true) }
                val isNewItem = byName == null
                val item = byName ?: CatalogItem(name = line.itemName, unitId = line.unit, price = rate)
                val total = line.quantity * rate

                scope.launch(Dispatchers.IO) {
                    if (isNewItem) {
                        db.catalogDao().insertOrUpdate(item)
                    } else if (!isStockIntent && rate > 0.0 && rate != item.price) {
                        // The shopkeeper corrected the rate while confirming -- teach the
                        // catalog so this item doesn't need a rate every time.
                        db.catalogDao().updatePrice(item.id, rate)
                    }

                    // The shopkeeper just told the app, explicitly, that "line.itemName" (what
                    // was parsed/heard) means THIS catalog item -- the single highest-value
                    // training signal in the system, previously thrown away entirely. Only worth
                    // learning when the spoken name DIFFERS from the catalog's own name -- an
                    // exact match already resolves fine without help. See ShopLearningRepository
                    // and its read side in SttWorker.commitParsedLines.
                    if (!isNewItem && !item.name.equals(line.itemName, ignoreCase = true)) {
                        com.voicetoinvoice.app.data.repository.ShopLearningRepository(db)
                            .recordItemAliasConfirmed(line.itemName, item.id)
                    }

                    // Same ledger contract as SttWorker.commitParsedLines -- this manual
                    // confirm path is the second writer of stock, and if it bypassed
                    // StockLedgerRepository the materialized stockQty would silently drift
                    // from the ledger for every review-queue confirmation.
                    val stockLedger = com.voicetoinvoice.app.data.repository.StockLedgerRepository(db)
                    val shopId = com.voicetoinvoice.app.data.ShopContext.requireShopId()

                    if (isStockIntent) {
                        val isWaste = job.captureIntent == CaptureIntent.WASTE
                        if (isWaste) {
                            // Spoilage is a stock movement, never a purchase row.
                            stockLedger.record(
                                itemId = item.id,
                                itemName = item.name,
                                deltaQty = -Math.abs(line.quantity),
                                reason = com.voicetoinvoice.app.data.local.entity.StockReason.WASTE,
                                refId = "${job.id}-${line.lineNo}",
                                note = "confirmed from review queue"
                            )
                        } else {
                            val stockRecord = com.voicetoinvoice.app.data.local.entity.StockInRecord(
                                shopId = shopId,
                                itemId = item.id,
                                itemName = item.name,
                                quantity = Math.abs(line.quantity),
                                costPrice = rate,
                                costMissing = rate <= 0.0
                            )
                            db.stockInDao().insert(stockRecord)
                            stockLedger.record(
                                itemId = item.id,
                                itemName = item.name,
                                deltaQty = Math.abs(line.quantity),
                                reason = com.voicetoinvoice.app.data.local.entity.StockReason.STOCK_IN,
                                unitCost = rate.takeIf { it > 0.0 },
                                refId = stockRecord.id
                            )
                        }
                    } else {
                        val txRecord = com.voicetoinvoice.app.data.local.entity.TransactionRecord(
                            shopId = shopId,
                            itemId = item.id,
                            itemName = item.name,
                            quantity = line.quantity,
                            priceAtSale = rate,
                            total = total,
                            paymentMode = if (isCreditSale) com.voicetoinvoice.app.data.local.entity.PaymentMode.CREDIT else com.voicetoinvoice.app.data.local.entity.PaymentMode.CASH,
                            source = com.voicetoinvoice.app.data.local.entity.TransactionSource.VOICE,
                            rawTranscript = job.rawTranscript,
                            audioFilePath = job.audioFilePath,
                            jobId = job.id,
                            lineNo = line.lineNo,
                            audioCloudUrl = "${com.voicetoinvoice.app.network.SupabaseConfig.SUPABASE_URL}/storage/v1/object/public/voice-recordings/${job.id}.wav",
                            synced = false,
                            costAtSale = db.catalogDao().getById(item.id)?.avgCostPrice,
                            billId = job.id
                        )
                        db.transactionDao().insert(txRecord)
                        stockLedger.recordSale(
                            itemId = item.id,
                            itemName = item.name,
                            qty = line.quantity,
                            transactionId = txRecord.id,
                            occurredAtMs = txRecord.timestamp,
                            total = txRecord.total,
                            costAtSale = txRecord.costAtSale
                        )

                        if (isCreditSale) {
                            val creditRecord = com.voicetoinvoice.app.data.local.entity.CreditRecord(
                                shopId = shopId,
                                customerName = "अज्ञात",
                                customerId = null,
                                amount = total,
                                status = com.voicetoinvoice.app.data.local.entity.CreditStatus.PENDING,
                                linkedTransactionId = txRecord.id
                            )
                            db.creditDao().insertOrUpdate(creditRecord)
                        }
                    }

                    // Merge this line's outcome into the trace instead of overwriting the
                    // whole array -- a multi-item job's other lines' outcomes must survive.
                    val traceObj = try { JSONObject(job.diagnosticTraceJson) } catch (_: Exception) { JSONObject() }
                    val outcomeArr = traceObj.optJSONArray("step_6_final_outcome") ?: JSONArray()
                    val lineOutcome = JSONObject().apply {
                        put("lineNo", line.lineNo)
                        put("itemName", item.name)
                        put("matchedCatalogId", item.id)
                        put("quantity", line.quantity)
                        put("unit", line.unit)
                        put("priceAtSale", rate)
                        put("estimatedTotal", total)
                        put("confidence", 1.0)
                        put("isSanityFlagged", false)
                        put("autoConfirmedToLedger", true)
                        put("userResolved", true)
                    }
                    if (line.lineNo < outcomeArr.length()) outcomeArr.put(line.lineNo, lineOutcome) else outcomeArr.put(lineOutcome)
                    traceObj.put("step_6_final_outcome", outcomeArr)

                    val updatedItemsJson = com.voicetoinvoice.app.ui.components.markLineResolved(job, line.lineNo)
                    val updatedJob = if (isLastPendingLine) {
                        job.copy(
                            status = com.voicetoinvoice.app.data.local.entity.SttJobStatus.CONFIRMED,
                            parsedItemName = if (line.lineNo == 0) item.name else job.parsedItemName,
                            parsedQty = if (line.lineNo == 0) line.quantity else job.parsedQty,
                            parsedUnit = if (line.lineNo == 0) line.unit else job.parsedUnit,
                            parsedTotal = if (line.lineNo == 0) total else job.parsedTotal,
                            isSanityFlagged = false,
                            diagnosticTraceJson = traceObj.toString(),
                            parsedItemsJson = updatedItemsJson,
                            synced = false
                        )
                    } else {
                        job.copy(
                            diagnosticTraceJson = traceObj.toString(),
                            parsedItemsJson = updatedItemsJson,
                            synced = false
                        )
                    }
                    db.sttJobDao().updateJob(updatedJob)

                    val syncEngine = com.voicetoinvoice.app.data.sync.SyncEngine(db.transactionDao(), db.stockInDao(), db.catalogDao(), db.creditDao(), db.sttJobDao(), db.supplierDao(), db.customerDao(), db.stockLedgerDao(), db.stockBatchDao(), db.customerPaymentDao(), db.shopLearningDao())
                    syncEngine.syncAllUnsynced()
                }
            },
            onDiscardLine = { job, line, isLastPendingLine ->
                scope.launch(Dispatchers.IO) {
                    val updatedItemsJson = com.voicetoinvoice.app.ui.components.markLineResolved(job, line.lineNo)
                    val updatedJob = if (isLastPendingLine) {
                        job.copy(
                            status = com.voicetoinvoice.app.data.local.entity.SttJobStatus.FAILED,
                            errorMessage = "Discarded by User",
                            parsedItemsJson = updatedItemsJson,
                            synced = false
                        )
                    } else {
                        job.copy(parsedItemsJson = updatedItemsJson, synced = false)
                    }
                    db.sttJobDao().updateJob(updatedJob)

                    val syncEngine = com.voicetoinvoice.app.data.sync.SyncEngine(db.transactionDao(), db.stockInDao(), db.catalogDao(), db.creditDao(), db.sttJobDao(), db.supplierDao(), db.customerDao(), db.stockLedgerDao(), db.stockBatchDao(), db.customerPaymentDao(), db.shopLearningDao())
                    syncEngine.syncAllUnsynced()
                }
            },
            onDismiss = { showPendingSheet = false }
        )
    }

    if (showCommandFeed) {
        com.voicetoinvoice.app.ui.components.CommandFeedSheet(
            jobs = commandFeedJobs,
            onDismiss = { showCommandFeed = false },
            onReviewJob = {
                // One review UI for "needs attention" jobs -- hands off to the same sheet
                // that already resolves items/quantities, rather than duplicating that logic.
                showCommandFeed = false
                showPendingSheet = true
            },
            onRetryJob = { job ->
                scope.launch(Dispatchers.IO) {
                    db.sttJobDao().updateJob(
                        job.copy(status = com.voicetoinvoice.app.data.local.entity.SttJobStatus.QUEUED)
                    )
                    try {
                        val workRequest = OneTimeWorkRequestBuilder<SttWorker>()
                            .setInputData(
                                androidx.work.workDataOf(
                                    SttWorker.KEY_JOB_ID to job.id,
                                    SttWorker.KEY_AUDIO_PATH to job.audioFilePath
                                )
                            )
                            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .build()
                        WorkManager.getInstance(context).enqueue(workRequest)
                    } catch (e: Exception) {
                        android.util.Log.e("HomeScreen", "Failed to re-enqueue retry for job ${job.id}", e)
                    }
                }
            },
            onSendBill = { job ->
                scope.launch(Dispatchers.IO) {
                    val lines = db.transactionDao().getByBillId(job.id).ifEmpty {
                        db.transactionDao().getByJobId(job.id)
                    }
                    val customerId = lines.firstOrNull { it.customerId != null }?.customerId
                    val customer = if (customerId != null) db.customerDao().getById(customerId) else null
                    val previousBalance = if (customer != null) com.voicetoinvoice.app.domain.query.CustomerBalance(db).balanceFor(customer.id) else null

                    val uri = com.voicetoinvoice.app.domain.action.BillBuilder.render(
                        context = context,
                        shopName = "",
                        customerName = customer?.name,
                        lines = lines,
                        previousBalance = previousBalance
                    )
                    val result = if (uri != null) {
                        com.voicetoinvoice.app.domain.action.ActionExecutor.sendBillImage(context, uri, customer?.phone)
                    } else {
                        com.voicetoinvoice.app.domain.action.ActionExecutor.sendBill(context, customer, lines)
                    }
                    if (result is com.voicetoinvoice.app.domain.action.ActionExecutor.Result.AppMissing) {
                        withContext(Dispatchers.Main) {
                            snackbarHostState.showSnackbar(result.message)
                        }
                    } else if (result is com.voicetoinvoice.app.domain.action.ActionExecutor.Result.Failed) {
                        withContext(Dispatchers.Main) {
                            snackbarHostState.showSnackbar(result.message)
                        }
                    }
                }
            }
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
