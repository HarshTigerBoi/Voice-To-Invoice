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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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

    val traceLogs by db.sttJobDao().getAllJobsTraceLogsFlow().collectAsState(initial = emptyList())
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice & System Processing Logs") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                    DiagnosticLogItemCard(log = log)
                }
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
fun DiagnosticLogItemCard(log: SttJobRecord) {
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

    val (statusLabel, statusColor) = when (log.status) {
        SttJobStatus.AUTO_CONFIRMED -> "AUTO-CONFIRMED" to Color(0xFF2E7D32)
        SttJobStatus.CONFIRMED -> "CONFIRMED" to Color(0xFF2E7D32)
        SttJobStatus.PARSED -> if (log.isSanityFlagged) "REVIEW NEEDED" to Color(0xFFEF6C00) else "PARSED" to Color(0xFF0288D1)
        SttJobStatus.PARTIALLY_CONFIRMED -> "PARTIALLY CONFIRMED" to Color(0xFFEF6C00)
        SttJobStatus.RATE_UPDATED -> "RATE UPDATED" to Color(0xFF2E7D32)
        SttJobStatus.FAILED -> "FAILED" to Color(0xFFC62828)
        SttJobStatus.TRANSCRIBING -> "PROCESSING" to Color(0xFF1565C0)
        SttJobStatus.QUEUED -> "QUEUED" to Color(0xFF616161)
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
            // Header Row: Timestamp, Hold Duration, Status Badge
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
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "(${log.holdDurationMs} ms hold)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

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

            Spacer(Modifier.height(10.dp))

            // Main Spoken Raw Transcript Text
            Text(
                text = if (log.rawTranscript.isNotBlank()) "\"${log.rawTranscript}\"" else "No transcript recorded",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(6.dp))

            // Matched Item Summary Row
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
                } else {
                    Text(
                        text = "Audio file unavailable",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Expandable Technical Trace Log Section
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

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

                    // Quick Action Buttons (Copy JSON)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Voice Trace JSON", log.diagnosticTraceJson)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied JSON trace to clipboard!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy JSON", style = MaterialTheme.typography.labelMedium)
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
