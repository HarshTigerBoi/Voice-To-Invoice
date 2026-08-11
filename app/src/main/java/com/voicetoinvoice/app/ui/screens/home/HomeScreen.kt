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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.voicetoinvoice.app.audio.AudioRecorder
import com.voicetoinvoice.app.ui.theme.LedgerColors
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
        val thresholdMs = System.currentTimeMillis() - 25000L // 25 seconds ago (aligned to INLINE_BUDGET_MS)
        db.sttJobDao().markStuckJobsAsFailed(thresholdMs)
    }

    // Collect pending parsed jobs asynchronously
    val pendingJobs by db.sttJobDao().getParsedJobsFlow().collectAsState(initial = emptyList())
    // Count PENDING LINES, not jobs -- a single multi-item recording can have 3 lines
    // where 2 already booked and 1 needs review; the pill should say "1", not "1 job".
    val pendingLineCount = remember(pendingJobs) {
        pendingJobs.sumOf { job ->
            val lines = com.voicetoinvoice.app.ui.components.parsePendingLines(job)
            if (lines.isEmpty()) 1 else lines.count { !it.committed && !it.resolved }
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
    // Bounded at the query, not in memory (ISSUE-113). `since` is remembered so the flow key
    // is stable across recompositions — recomputing System.currentTimeMillis() inline would
    // build a new Flow on every recomposition.
    val commandFeedSince = remember { System.currentTimeMillis() - 24 * 60 * 60 * 1000L }
    val commandFeedJobs by remember(commandFeedSince) {
        db.sttJobDao().getJobsSinceFlow(commandFeedSince)
    }.collectAsState(initial = emptyList())
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

    // Immediate feedback snackbar when a voice job resolves to 0 lines (Docs/voice_capture_feedback_fix_plan.md Step 2)
    val latestZeroLineJob by db.sttJobDao().getLatestZeroLineJobFlow().collectAsState(initial = null)
    LaunchedEffect(latestZeroLineJob?.id) {
        latestZeroLineJob?.let { job ->
            if (System.currentTimeMillis() - job.recordedAtMs < 60_000L) {
                val message = if (job.rawTranscript.isNotBlank() && job.rawTranscript != "Voice Recording (Pending Review)") {
                    "\"${job.rawTranscript}\" समझ नहीं आया — समीक्षा में देखें"
                } else {
                    "रिकॉर्डिंग समझ नहीं आई — समीक्षा में देखें"
                }
                snackbarHostState.showSnackbar(message)
            }
        }
    }

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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF10B981),
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Voice Ledger",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f)),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = "आज: ₹${todayTotalSales.toInt()}",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = Color(0xFF34D399)
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "और विकल्प")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("समीक्षा (Pending Review)") },
                            leadingIcon = { Icon(Icons.Default.List, contentDescription = null) },
                            onClick = { menuExpanded = false; showPendingSheet = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Summary") },
                            leadingIcon = { Icon(Icons.Default.Receipt, contentDescription = null) },
                            onClick = { menuExpanded = false; onNavigateToSummary() }
                        )
                        DropdownMenuItem(
                            text = { Text("Voice Processing Logs") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = { menuExpanded = false; onNavigateToLogs() }
                        )
                    }
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
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Floating Pending Sales Pill Badge
                PendingConfirmationsBar(
                    pendingCount = pendingLineCount,
                    onClick = { showPendingSheet = true },
                    modifier = Modifier.padding(top = 16.dp)
                )

                // Business health and activity status chips
                Row(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    healthScoreValue?.let { score ->
                        AssistChip(
                            onClick = onNavigateToReports,
                            label = {
                                Text(
                                    if (topAlertCount > 0) "Score $score · $topAlertCount alert" else "Score $score",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFF1E293B)
                            ),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                        )
                    } ?: Spacer(Modifier.width(1.dp))

                    if (commandFeedJobs.isNotEmpty()) {
                        AssistChip(
                            onClick = { showCommandFeed = true },
                            label = {
                                Text(
                                    if (inFlightCount > 0) "Processing ($inFlightCount)" else "Recent Activity",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFF1E293B)
                            ),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                            leadingIcon = {
                                if (inFlightCount > 0) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF60A5FA))
                                } else {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(16.dp))
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Main Push-to-Talk (PTT) Voice Hub Card
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = Color(0xFF131722),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(26.dp))
                        .padding(vertical = 16.dp, horizontal = 12.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚡ VOICE ACTION HUB",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = Color(0xFF94A3B8),
                                letterSpacing = 1.2.sp
                            ),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // 1. Cash Sale Mic (Large, Green)
                            PttMicButton(
                                intent = CaptureIntent.SALE,
                                label = "नकद बेचो",
                                size = 124.dp,
                                containerColor = LedgerColors.MoneyIn,
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

                            // 2. Udhaar Credit Sale Mic (Medium, Amber)
                            PttMicButton(
                                intent = CaptureIntent.CREDIT_SALE,
                                label = "उधार बेचो",
                                size = 104.dp,
                                containerColor = LedgerColors.Udhaar,
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
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Direct Fallback Button: Type Text
                OutlinedButton(
                    onClick = {
                        manualInputText = ""
                        showManualTextDialog = true
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF94A3B8)
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("⌨ Type sale manually", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Quick Manual Stepper Grid
                ManualStepperComponent(
                    topItems = catalog.sortedByDescending { it.lastSoldAtMs ?: 0L },
                    onAddSale = { item, qty ->
                        val sale = ParsedVoiceSale(item, "Manual Entry", qty, item.unitId, qty * item.price, 1.0f)
                        onConfirmSale(sale)
                    }
                )

                // Reserves space for Assistant FAB to prevent overlap
                Spacer(modifier = Modifier.height(100.dp))
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
                val spokenBase = com.voicetoinvoice.app.domain.lexicon.ItemLexicon.baseUnitOf(line.unit)
                fun CatalogItem.baseOf() =
                    if (baseUnit.isNotBlank()) baseUnit
                    else com.voicetoinvoice.app.domain.lexicon.ItemLexicon.baseUnitOf(unitId)

                // An explicit pick only counts when it is a price line for the unit that was spoken.
                val byId   = resolvedItemId?.let { id -> catalog.find { it.id == id && it.baseOf() == spokenBase } }
                val byName = byId ?: catalog.find {
                    it.name.equals(line.itemName, ignoreCase = true) && it.baseOf() == spokenBase
                }
                val isNewItem = byName == null

                // A sibling row under a different unit is the template for the new price line: it
                // already carries the canonicalKey that identity resolution depends on.
                val sibling = catalog.find { it.name.equals(line.itemName, ignoreCase = true) }
                val item = byName ?: CatalogItem(
                    name         = sibling?.name ?: line.itemName,
                    unitId       = line.unit,
                    price        = rate,
                    canonicalKey = sibling?.canonicalKey ?: "",
                    baseUnit     = spokenBase
                )
                val total = line.quantity * rate

                scope.launch(Dispatchers.IO) {
                    val shopId = com.voicetoinvoice.app.data.ShopContext.requireShopId()
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
                        try {
                            com.voicetoinvoice.app.network.TermInterpreterClient().confirmTermAlias(
                                rawTerm = line.itemName,
                                canonicalValue = item.name,
                                shopId = shopId
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("HomeScreen", "Failed to propagate term alias: ${e.message}", e)
                        }
                    }

                    // Same ledger contract as SttWorker.commitParsedLines -- this manual
                    // confirm path is the second writer of stock, and if it bypassed
                    // StockLedgerRepository the materialized stockQty would silently drift
                    // from the ledger for every review-queue confirmation.
                    val stockLedger = com.voicetoinvoice.app.data.repository.StockLedgerRepository(db)

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
