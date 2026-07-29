package com.voicetoinvoice.app.ui.screens.summary

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.entity.PaymentMode
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.utils.AudioFileManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class RangeMode { DAY, WEEK, MONTH }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailySummaryScreen(
    rangeTransactions: List<TransactionRecord>,
    rangeMode: RangeMode,
    onRangeModeChange: (RangeMode) -> Unit,
    costPriceByItemId: Map<String, Double> = emptyMap(),
    onUpdateTxPrice: (TransactionRecord, Double) -> Unit = { _, _ -> },
    onVoidTransaction: (TransactionRecord) -> Unit = {},
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val audioFileManager = remember { AudioFileManager(context) }
    var playingTxId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            audioFileManager.stopAudio()
        }
    }

    val totalRevenue = rangeTransactions.sumOf { it.total }
    val cashTotal = rangeTransactions.filter { it.paymentMode == PaymentMode.CASH }.sumOf { it.total }
    val upiTotal = rangeTransactions.filter { it.paymentMode == PaymentMode.UPI }.sumOf { it.total }
    val creditTotal = rangeTransactions.filter { it.paymentMode == PaymentMode.CREDIT }.sumOf { it.total }

    val totalMargin = rangeTransactions.sumOf { tx ->
        val cost = costPriceByItemId[tx.itemId] ?: 0.0
        tx.total - (cost * tx.quantity)
    }

    val pendingPriceItems = rangeTransactions.filter { it.total == 0.0 || it.priceAtSale == 0.0 }
    var showExportSafeguardDialog by remember { mutableStateOf(false) }

    // Dialog for setting pending price
    var selectedTxForPriceSet by remember { mutableStateOf<TransactionRecord?>(null) }
    var priceInputText by remember { mutableStateOf("") }

    // Swipe-to-void: a wrongly recorded sale is voided (soft deleted), never hard
    // deleted -- this is also the correction signal the server-side Learned Parse
    // Memory relies on to demote a self-consistent-but-wrong memoized parse.
    var txPendingVoid by remember { mutableStateOf<TransactionRecord?>(null) }

    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    fun performCopy(allowPending: Boolean) {
        val sb = StringBuilder()
        sb.append("Time\tSpoken Voice / Transcript\tItem Name\tQuantity\tPrice at Sale\tTotal (₹)\tPayment Mode\n")

        for (tx in rangeTransactions) {
            val timeStr = timeFormat.format(Date(tx.timestamp))
            val priceStr = if (tx.total == 0.0) "₹0* (PENDING)" else "₹${tx.total}"
            val transcriptStr = if (tx.rawTranscript.isNotBlank()) tx.rawTranscript else tx.itemName
            sb.append("${timeStr}\t${transcriptStr}\t${tx.itemName}\t${tx.quantity}\t₹${tx.priceAtSale}\t${priceStr}\t${tx.paymentMode}\n")
        }

        sb.append("\nTOTAL REVENUE:\t\t\t\t\t₹${totalRevenue.toInt()}\t\n")
        sb.append("Cash Total:\t\t\t\t\t₹${cashTotal.toInt()}\t\n")
        sb.append("UPI Total:\t\t\t\t\t₹${upiTotal.toInt()}\t\n")
        sb.append("Udhaar Total:\t\t\t\t\t₹${creditTotal.toInt()}\t\n")
        sb.append("Est. Margin:\t\t\t\t\t₹${totalMargin.toInt()}\t\n")

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Sales Ledger Table", sb.toString())
        clipboard.setPrimaryClip(clip)

        Toast.makeText(context, "Sales table with Spoken Transcripts copied!", Toast.LENGTH_LONG).show()
    }

    fun handleExportRequest() {
        if (pendingPriceItems.isNotEmpty()) {
            showExportSafeguardDialog = true
        } else {
            performCopy(allowPending = false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sales Summary & Ledger") },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } },
                actions = {
                    IconButton(onClick = { handleExportRequest() }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Table for Excel")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Range Selector Toggle Chips (Day / Week / Month)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = rangeMode == RangeMode.DAY,
                    onClick = { onRangeModeChange(RangeMode.DAY) },
                    label = { Text("Today") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = rangeMode == RangeMode.WEEK,
                    onClick = { onRangeModeChange(RangeMode.WEEK) },
                    label = { Text("7 Days") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = rangeMode == RangeMode.MONTH,
                    onClick = { onRangeModeChange(RangeMode.MONTH) },
                    label = { Text("30 Days") },
                    modifier = Modifier.weight(1f)
                )
            }

            // Mandatory Warning Banner if Pending Price items exist
            if (pendingPriceItems.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "⚠️ ${pendingPriceItems.size} Sales Pending Price (₹0). Tap item to set price.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Revenue: ₹${totalRevenue.toInt()}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "📈 Est. Margin: ₹${totalMargin.toInt()}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("💵 Cash: ₹${cashTotal.toInt()}", style = MaterialTheme.typography.bodyMedium)
                        Text("📲 UPI: ₹${upiTotal.toInt()}", style = MaterialTheme.typography.bodyMedium)
                        Text("📜 Udhaar: ₹${creditTotal.toInt()}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { handleExportRequest() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Table for Excel")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Itemized Sales Breakdown (${rangeTransactions.size} items)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (rangeTransactions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No sales logged in this range.", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn {
                    items(rangeTransactions, key = { it.id }) { tx ->
                        val isPending = tx.total == 0.0 || tx.priceAtSale == 0.0
                        val isPlaying = playingTxId == tx.id
                        val hasAudio = tx.audioFilePath.isNotBlank() && File(tx.audioFilePath).exists()

                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                                    txPendingVoid = tx
                                }
                                // Never let the framework auto-commit the dismiss -- voiding
                                // only actually happens if the confirm dialog below is accepted.
                                false
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            modifier = Modifier.padding(vertical = 4.dp),
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Void sale",
                                        tint = Color.White
                                    )
                                }
                            }
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedTxForPriceSet = tx
                                        priceInputText = if (tx.priceAtSale > 0) tx.priceAtSale.toString() else ""
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isPending) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = tx.itemName,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        if (tx.rawTranscript.isNotBlank()) {
                                            Text(
                                                text = "🎙️ Spoken: \"${tx.rawTranscript}\"",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Text(
                                            text = if (isPending) "${tx.quantity} qty • PENDING PRICE (Tap to set)"
                                            else "${tx.quantity} @ ₹${tx.priceAtSale} • ${timeFormat.format(Date(tx.timestamp))}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isPending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (hasAudio) {
                                            IconButton(
                                                onClick = {
                                                    if (isPlaying) {
                                                        audioFileManager.stopAudio()
                                                        playingTxId = null
                                                    } else {
                                                        playingTxId = tx.id
                                                        audioFileManager.playAudio(tx.audioFilePath) {
                                                            playingTxId = null
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = "Play Recording",
                                                    tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(4.dp))
                                        }

                                        Text(
                                            text = if (isPending) "Set Price" else "₹${tx.total.toInt()}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (isPending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Export Safeguard Dialog
    if (showExportSafeguardDialog) {
        AlertDialog(
            onDismissRequest = { showExportSafeguardDialog = false },
            title = { Text("Pending Prices Warning") },
            text = {
                Text("${pendingPriceItems.size} items have missing prices (₹0). Set prices before copying, or copy anyway with PENDING tags?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportSafeguardDialog = false
                        performCopy(allowPending = true)
                    }
                ) {
                    Text("Copy Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportSafeguardDialog = false }) {
                    Text("Cancel & Fix Prices")
                }
            }
        )
    }

    // Set Price Modal
    selectedTxForPriceSet?.let { tx ->
        AlertDialog(
            onDismissRequest = { selectedTxForPriceSet = null },
            title = { Text("Set Price for ${tx.itemName}") },
            text = {
                Column {
                    Text("Enter unit price per ${tx.itemName} (Total will be calculated as price * ${tx.quantity}):")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = priceInputText,
                        onValueChange = { priceInputText = it },
                        placeholder = { Text("e.g. 35.0") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newPrice = priceInputText.toDoubleOrNull()
                        if (newPrice != null && newPrice > 0.0) {
                            onUpdateTxPrice(tx, newPrice)
                            selectedTxForPriceSet = null
                        }
                    }
                ) {
                    Text("Save Price")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedTxForPriceSet = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Void Confirmation Dialog -- swiping a row never voids it directly, so an
    // accidental swipe can't silently delete a real sale.
    txPendingVoid?.let { tx ->
        AlertDialog(
            onDismissRequest = { txPendingVoid = null },
            title = { Text("Void this sale?") },
            text = {
                Text("\"${tx.itemName}\" (${tx.quantity} @ ₹${tx.priceAtSale} = ₹${tx.total.toInt()}) will be removed from the ledger. This cannot be undone from here.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onVoidTransaction(tx)
                        txPendingVoid = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Void Sale")
                }
            },
            dismissButton = {
                TextButton(onClick = { txPendingVoid = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
