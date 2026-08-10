package com.voicetoinvoice.app.ui.screens.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.voicetoinvoice.app.ui.theme.LedgerColors
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
import com.voicetoinvoice.app.data.local.entity.SttJobStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticLogsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    val localLogs by db.sttJobDao().getAllJobsTraceLogsFlow().collectAsState(initial = emptyList())
    var showClearDialog by remember { mutableStateOf(false) }

    // Server-side jobs, pulled for display only (see CloudSyncManager.fetchJobLogsFromCloud).
    // Sync is push-only, so without this the screen shows only what THIS handset recorded --
    // which looked like "logging is broken" while 250+ jobs sat on the server.
    var cloudLogs by remember { mutableStateOf<List<SttJobRecord>>(emptyList()) }
    var cloudState by remember { mutableStateOf("loading") }
    var refreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(refreshTick) {
        cloudState = "loading"
        val fetched = com.voicetoinvoice.app.network.CloudSyncManager()
            .fetchJobLogsFromCloud(com.voicetoinvoice.app.data.ShopContext.shopIdOrNull())
        cloudLogs = fetched ?: emptyList()
        cloudState = if (fetched == null) "error" else "ok"
    }

    // Local rows win on id: they carry the on-device audio path, so playback keeps working.
    val localIds = remember(localLogs) { localLogs.map { it.id }.toSet() }
    val traceLogs = remember(localLogs, cloudLogs) {
        // `distinctBy` is load-bearing, not tidiness: the LazyColumn below keys on `it.id`, and
        // a repeated key makes Compose throw rather than merely render oddly. `stt_job_logs` has
        // no unique constraint on job_id (verified 2026-08-06: 123 rows / 123 distinct for this
        // shop -- clean today, not guaranteed tomorrow), so one retry that double-logs a job
        // would take the whole screen down.
        (localLogs + cloudLogs.filterNot { it.id in localIds })
            .distinctBy { it.id }
            .sortedByDescending { it.recordedAtMs }
    }
    val cloudOnlyIds = remember(traceLogs, localIds) {
        traceLogs.map { it.id }.filterNot { it in localIds }.toSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Voice & System Processing Logs")
                        Text(
                            "build ${com.voicetoinvoice.app.BuildConfig.BUILD_STAMP} · ${com.voicetoinvoice.app.BuildConfig.GIT_SHA}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            exportAllLogsToFile(context, traceLogs)
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export All Logs")
                    }
                    IconButton(
                        onClick = { showClearDialog = true }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
                    }
                }
            )
        }
    ) { padding ->
        if (traceLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No Voice Recording Logs Yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Hold the mic button on the home screen to record a sale.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(traceLogs, key = { it.id }) { log ->
                    DiagnosticLogItemCard(log = log, isCloudOnly = log.id in cloudOnlyIds)
                }
                // Same reservation as HomeScreen: the assistant FAB is a BottomEnd overlay on
                // every screen, and without this it sits on top of the last card's badges.
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Diagnostic Logs?") },
            text = { Text("This will erase all recorded voice trace logs from device storage.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        scope.launch(Dispatchers.IO) {
                            db.sttJobDao().clearAllJobsLogs()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DiagnosticLogItemCard(log: SttJobRecord, isCloudOnly: Boolean = false) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    var isPlayingAudio by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    val formattedTime = remember(log.recordedAtMs) {
        SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault()).format(Date(log.recordedAtMs))
    }

    /**
     * Which interpretation path produced this job, read straight from the trace the server
     * wrote (`step_4_fast_path` / `step_4_interpretation_source`).
     *
     * ⚡ FAST means the deterministic segmenter resolved every line exactly and the Grok chat
     * call was skipped -- measured at ~3.5s end-to-end against ~8.9s when the AI runs. Seeing
     * at a glance which recordings took the slow path is the whole point: the trace also
     * carries `skipReason`, shown in the expanded view, naming the gate that rejected it.
     */
    val pathBadge: Pair<String, Color>? = remember(log.diagnosticTraceJson) {
        runCatching {
            if (log.diagnosticTraceJson.isBlank()) return@runCatching null
            val root = JSONObject(log.diagnosticTraceJson)
            val serverTrace = root.optJSONObject("server") ?: root
            val fp = serverTrace.optJSONObject("step_4_fast_path")
            val src = serverTrace.optString("step_4_interpretation_source", "")
            val aiModel = serverTrace.optString("step_4_ai_model", "").takeIf { it.isNotBlank() && it != "null" && it != "fast_path" }
            when {
                fp?.optBoolean("used") == true -> "⚡ FAST" to Color(0xFF00796B)
                src == "memory" -> "🧠 MEMO" to Color(0xFF5E35B1)
                src == "segmenter_fallback" -> "📐 RULES" to LedgerColors.Neutral
                (src == "grok_ai" || src == "forced_ai_fallback") && aiModel != null -> "🤖 $aiModel" to Color(0xFF8D6E63)
                src == "grok_ai" || src == "forced_ai_fallback" -> "🤖 AI" to Color(0xFF8D6E63)
                else -> null
            }
        }.getOrNull()
    }

    /** Server-side warnings/errors recorded for this job (step_0_server_diagnostics). */
    val serverIssues: List<String> = remember(log.diagnosticTraceJson) {
        runCatching {
            if (log.diagnosticTraceJson.isBlank()) return@runCatching emptyList()
            val root = JSONObject(log.diagnosticTraceJson)
            val serverTrace = root.optJSONObject("server") ?: root
            val arr = serverTrace.optJSONArray("step_0_server_diagnostics")
                ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { "[${it.optString("level")}] ${it.optString("stage")}: ${it.optString("message")}" }
            }
        }.getOrDefault(emptyList())
    }

    val fastPathSkipReason: String? = remember(log.diagnosticTraceJson) {
        runCatching {
            val root = JSONObject(log.diagnosticTraceJson)
            val serverTrace = root.optJSONObject("server") ?: root
            serverTrace.optJSONObject("step_4_fast_path")
                ?.optString("skipReason")?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
    }

    val (statusLabel, statusColor) = if (log.captureIntent == com.voicetoinvoice.app.data.local.entity.CaptureIntent.ASSISTANT) {
        "ASSISTANT" to Color(0xFF7B1FA2)
    } else {
        when (log.status) {
            SttJobStatus.AUTO_CONFIRMED -> "AUTO-CONFIRMED" to LedgerColors.MoneyIn
            SttJobStatus.CONFIRMED -> "CONFIRMED" to LedgerColors.MoneyIn
            SttJobStatus.PARSED -> if (log.isSanityFlagged) "REVIEW NEEDED" to Color(0xFFEF6C00) else "PARSED" to LedgerColors.Upi
            SttJobStatus.PARTIALLY_CONFIRMED -> "PARTIALLY CONFIRMED" to Color(0xFFEF6C00)
            SttJobStatus.RATE_UPDATED -> "RATE UPDATED" to LedgerColors.MoneyIn
            SttJobStatus.FAILED -> "FAILED" to LedgerColors.MoneyOut
            SttJobStatus.TRANSCRIBING -> "PROCESSING" to Color(0xFF1565C0)
            SttJobStatus.QUEUED -> "QUEUED" to LedgerColors.Neutral
        }
    }

    val audioFileExists = remember(log.audioFilePath) {
        log.audioFilePath.isNotBlank() && File(log.audioFilePath).exists()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Timestamp, Hold Duration, Intent Chip, Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "(${log.holdDurationMs} ms hold)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val intentChip = log.captureIntent.hindiLabel
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = intentChip,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = statusLabel,
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Engine / origin badges get their OWN row: the header already carries the intent
            // chip and the status badge, and cramming four chips onto one non-wrapping Row
            // clipped them off the right edge on a 1080px screen.
            if (pathBadge != null || isCloudOnly) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    pathBadge?.let { (label, colour) ->
                        Surface(color = colour, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                text = label,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    if (isCloudOnly) {
                        Surface(color = Color(0xFF455A64), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                text = "☁ SERVER",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Main Spoken Raw Transcript Text
            Text(
                text = when {
                    log.rawTranscript.isNotBlank() -> "\"${log.rawTranscript}\""
                    log.errorMessage.isNotBlank() -> "⚠️ ${log.errorMessage}"
                    log.status == SttJobStatus.FAILED -> "⚠️ Recording failed / Unrecognized"
                    else -> "No transcript recorded"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(6.dp))

            // Matched Item / Assistant Answer Summary Row
            if (log.captureIntent == com.voicetoinvoice.app.data.local.entity.CaptureIntent.ASSISTANT) {
                if (log.assistantAnswer.isNotBlank()) {
                    Text(
                        text = "🤖 जवाब: ${log.assistantAnswer}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Item: ${log.parsedItemName.ifBlank { "Unrecognized" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${log.parsedQty} ${log.parsedUnit} • ₹${log.parsedTotal.toInt()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (log.errorMessage.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Error: ${log.errorMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(10.dp))

            // Audio Player Controls Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (audioFileExists) {
                    FilledTonalButton(
                        onClick = {
                            if (isPlayingAudio) {
                                mediaPlayer?.pause()
                                isPlayingAudio = false
                            } else {
                                try {
                                    mediaPlayer?.release()
                                    val player = MediaPlayer().apply {
                                        setDataSource(log.audioFilePath)
                                        prepare()
                                        setOnCompletionListener {
                                            isPlayingAudio = false
                                        }
                                        start()
                                    }
                                    mediaPlayer = player
                                    isPlayingAudio = true
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to play audio: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlayingAudio) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isPlayingAudio) "Pause Audio" else "Play Recorded Audio", style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = {
                            shareAudioFile(context, File(log.audioFilePath))
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Audio", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (log.captureIntent == com.voicetoinvoice.app.data.local.entity.CaptureIntent.ASSISTANT) {
                    Text(
                        text = "☁️ Audio processed via cloud (local copy unavailable)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = "Audio file unavailable",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Expandable Technical Trace Log Section
            // Server-side failures are shown COLLAPSED, not hidden behind a tap: the whole
            // reason these exist is that the catalog-fetch 400 stayed invisible for weeks.
            if (serverIssues.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = LedgerColors.MoneyOut.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "⚠ Server issue${if (serverIssues.size > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = LedgerColors.MoneyOut
                        )
                        serverIssues.forEach {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = LedgerColors.MoneyOut
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    // Names the exact gate that sent this recording down the slow AI path.
                    fastPathSkipReason?.let {
                        Text(
                            text = "AI call made — fast path skipped: $it",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Text(
                        text = "End-to-End Processing Technical Trace",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(6.dp))

                    // Audio Buffer Window Details
                    Text(
                        text = "🎙️ PTT Recording Timestamps:\n  - Press Start: ${log.pressStartMs} ms\n  - Release: ${log.releaseMs} ms\n  - Audio Extraction Window: ${log.audioStartMs} ms -> ${log.audioEndMs} ms",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))

                    // Full Diagnostic JSON Box
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (log.diagnosticTraceJson.isNotBlank()) log.diagnosticTraceJson else "No detailed JSON trace available for this job.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Quick Action Buttons (Copy JSON & Share JSON)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (log.diagnosticTraceJson.isBlank()) {
                                    Toast.makeText(context, "इस job का trace उपलब्ध नहीं है", Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Voice Trace JSON", log.diagnosticTraceJson)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied ${log.diagnosticTraceJson.length} chars to clipboard", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Clipboard failed (${e.javaClass.simpleName}) — use Share JSON", Toast.LENGTH_LONG).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy JSON", style = MaterialTheme.typography.labelMedium)
                        }

                        Spacer(Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = {
                                shareTraceJson(context, log.id, log.diagnosticTraceJson)
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Share JSON", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun shareAudioFile(context: Context, file: File) {
    try {
        if (!file.exists()) {
            Toast.makeText(context, "Audio file does not exist", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Voice Recording"))
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to share audio: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun exportAllLogsToFile(context: Context, logs: List<SttJobRecord>) {
    try {
        val db = AppDatabase.getInstance(context)
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)

        scope.launch {
            val transactions = db.transactionDao().getAllTransactionsList()
            val catalog = db.catalogDao().getActiveCatalogList()
            val unmatched = db.unmatchedQueueDao().getPendingItemsList()
            val credits = db.creditDao().getAllCreditsList()
            val stockIn = db.stockInDao().getAllStockInRecordsList()

            val bundleObj = JSONObject()

            // 1. Voice STT Jobs & Diagnostic Traces
            val jobsArray = JSONArray()
            logs.forEach { log ->
                val logObj = JSONObject().apply {
                    put("id", log.id)
                    put("recordedAtMs", log.recordedAtMs)
                    put("pressStartMs", log.pressStartMs)
                    put("releaseMs", log.releaseMs)
                    put("audioStartMs", log.audioStartMs)
                    put("audioEndMs", log.audioEndMs)
                    put("holdDurationMs", log.holdDurationMs)
                    put("status", log.status.name)
                    put("rawTranscript", log.rawTranscript)
                    put("parsedItemName", log.parsedItemName)
                    put("parsedQty", log.parsedQty)
                    put("parsedUnit", log.parsedUnit)
                    put("parsedTotal", log.parsedTotal)
                    put("isSanityFlagged", log.isSanityFlagged)
                    put("audioFilePath", log.audioFilePath)
                    put("audioFileExists", log.audioFilePath.isNotBlank() && File(log.audioFilePath).exists())
                    put("errorMessage", log.errorMessage)
                    put("diagnosticTraceJson", log.diagnosticTraceJson)
                }
                jobsArray.put(logObj)
            }
            bundleObj.put("stt_jobs_voice_recordings", jobsArray)

            // 2. Finalized Summary Transactions Ledger
            val txArray = JSONArray()
            transactions.forEach { tx ->
                txArray.put(JSONObject().apply {
                    put("id", tx.id)
                    put("itemId", tx.itemId)
                    put("itemName", tx.itemName)
                    put("quantity", tx.quantity)
                    put("priceAtSale", tx.priceAtSale)
                    put("total", tx.total)
                    put("paymentMode", tx.paymentMode.name)
                    put("source", tx.source.name)
                    put("rawTranscript", tx.rawTranscript)
                    put("audioFilePath", tx.audioFilePath)
                    put("timestamp", tx.timestamp)
                })
            }
            bundleObj.put("summary_finalized_transactions", txArray)

            // 3. Catalog Items
            val catArray = JSONArray()
            catalog.forEach { item ->
                catArray.put(JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("unitId", item.unitId)
                    put("price", item.price)
                })
            }
            bundleObj.put("master_catalog", catArray)

            // 4. Pending Review Queue Items
            val reviewArray = JSONArray()
            unmatched.forEach { u ->
                reviewArray.put(JSONObject().apply {
                    put("id", u.id)
                    put("rawTranscript", u.rawTranscript)
                    put("audioRef", u.audioRef ?: JSONObject.NULL)
                    put("status", u.status.name)
                    put("timestamp", u.timestamp)
                })
            }
            bundleObj.put("review_queue_pending", reviewArray)

            // 5. Credits & Stock-In
            val creditArray = JSONArray()
            credits.forEach { c ->
                creditArray.put(JSONObject().apply {
                    put("id", c.id)
                    put("customerName", c.customerName)
                    put("amount", c.amount)
                    put("status", c.status.name)
                })
            }
            bundleObj.put("credits_udhaar", creditArray)

            val exportFile = File(context.cacheDir, "voice_to_invoice_full_diagnostic_bundle.json")
            exportFile.writeText(bundleObj.toString(2))

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                exportFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, "Share Full Voice-to-Invoice App Diagnostics"))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Failed to export diagnostic bundle: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun shareTraceJson(context: Context, jobId: String, json: String) {
    try {
        if (json.isBlank()) {
            Toast.makeText(context, "इस job का trace उपलब्ध नहीं है", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(context.cacheDir, "traces").apply { mkdirs() }
        val file = File(dir, "trace_${jobId}.json")
        file.writeText(json)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Voice Trace JSON"))
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to share trace: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
