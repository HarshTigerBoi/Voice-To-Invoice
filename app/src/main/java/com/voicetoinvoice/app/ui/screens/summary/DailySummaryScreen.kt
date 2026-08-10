package com.voicetoinvoice.app.ui.screens.summary

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.dao.FilteredTotals
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.PaymentMode
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.data.local.entity.TxnType
import com.voicetoinvoice.app.ui.components.ItemIcon
import com.voicetoinvoice.app.ui.theme.LedgerColors
import com.voicetoinvoice.app.utils.AudioFileManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class RangeMode { DAY, WEEK, MONTH }

data class LedgerFilter(
    val itemId: String? = null,
    val paymentMode: PaymentMode? = null,
    val txnType: TxnType? = null,
    val fromHour: Int? = null,   // 0..23 local, inclusive
    val toHour: Int? = null      // 0..23 local, inclusive
) {
    val isActive: Boolean
        get() = itemId != null || paymentMode != null || txnType != null ||
                fromHour != null || toHour != null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailySummaryScreen(
    rangeTransactions: List<TransactionRecord>,
    rangeMode: RangeMode,
    onRangeModeChange: (RangeMode) -> Unit,
    catalog: List<CatalogItem> = emptyList(),
    onUpdateTxPrice: (TransactionRecord, Double) -> Unit = { _, _ -> },
    onVoidTransaction: (TransactionRecord) -> Unit = {},
    onNavigateBack: () -> Unit,
    onNavigateToReports: () -> Unit = {}
) {
    val context = LocalContext.current
    val audioFileManager = remember { AudioFileManager(context) }
    var playingTxId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            audioFileManager.stopAudio()
        }
    }

    var filter by remember { mutableStateOf(LedgerFilter()) }

    var showItemSheet by remember { mutableStateOf(false) }
    var showTimeSheet by remember { mutableStateOf(false) }
    var showPaymentSheet by remember { mutableStateOf(false) }
    var showTypeSheet by remember { mutableStateOf(false) }

    val catalogMap = remember(catalog) { catalog.associateBy { it.id } }

    val filteredTransactions = remember(rangeTransactions, filter) {
        // Snapshot the delegated `filter` into locals before the predicate: `filter` is a
        // `by remember { mutableStateOf(...) }` delegate, so its properties cannot be smart-cast
        // to non-null inside the lambda (Kotlin cannot prove a delegate is stable between reads).
        val fItemId = filter.itemId
        val fPaymentMode = filter.paymentMode
        val fTxnType = filter.txnType
        val fFromHour = filter.fromHour
        val fToHour = filter.toHour
        rangeTransactions.filter { tx ->
            if (fItemId != null && tx.itemId != fItemId) return@filter false
            if (fPaymentMode != null && tx.paymentMode != fPaymentMode) return@filter false
            if (fTxnType != null && tx.txnType != fTxnType) return@filter false
            if (fFromHour != null || fToHour != null) {
                val cal = Calendar.getInstance().apply { timeInMillis = tx.timestamp }
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                if (fFromHour != null && hour < fFromHour) return@filter false
                if (fToHour != null && hour > fToHour) return@filter false
            }
            true
        }
    }

    val filteredTotals = remember(filteredTransactions) {
        FilteredTotals(
            revenue = filteredTransactions.sumOf { it.total },
            cash = filteredTransactions.filter { it.paymentMode == PaymentMode.CASH }.sumOf { it.total },
            upi = filteredTransactions.filter { it.paymentMode == PaymentMode.UPI }.sumOf { it.total },
            credit = filteredTransactions.filter { it.paymentMode == PaymentMode.CREDIT }.sumOf { it.total },
            qty = filteredTransactions.sumOf { it.quantity },
            lineCount = filteredTransactions.size
        )
    }

    val totalRevenue = rangeTransactions.sumOf { it.total }
    val cashTotal = rangeTransactions.filter { it.paymentMode == PaymentMode.CASH }.sumOf { it.total }
    val upiTotal = rangeTransactions.filter { it.paymentMode == PaymentMode.UPI }.sumOf { it.total }
    val creditTotal = rangeTransactions.filter { it.paymentMode == PaymentMode.CREDIT }.sumOf { it.total }

    // Honest margin: only over lines whose cost was actually known AT THE TIME OF SALE
    val costedTx = rangeTransactions.filter { it.costAtSale != null }
    val revenueWithCost = costedTx.sumOf { it.total }
    val cogs = costedTx.sumOf { it.quantity * it.costAtSale!! }
    val totalMargin = revenueWithCost - cogs
    val costCoveragePct = if (totalRevenue > 0.0) (revenueWithCost / totalRevenue) * 100.0 else 0.0

    val pendingPriceItems = filteredTransactions.filter { it.total == 0.0 || it.priceAtSale == 0.0 }
    var showExportSafeguardDialog by remember { mutableStateOf(false) }

    // Dialog for setting pending price
    var selectedTxForPriceSet by remember { mutableStateOf<TransactionRecord?>(null) }
    var priceInputText by remember { mutableStateOf("") }

    // Swipe-to-void: a wrongly recorded sale is voided (soft deleted), never hard deleted
    var txPendingVoid by remember { mutableStateOf<TransactionRecord?>(null) }

    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    fun performCopy(allowPending: Boolean) {
        val sb = StringBuilder()
        if (filter.isActive) {
            val filterParts = mutableListOf<String>()
            filter.itemId?.let { id ->
                val name = catalogMap[id]?.name ?: id
                filterParts.add("item=$name")
            }
            filter.paymentMode?.let { mode ->
                filterParts.add("payment=$mode")
            }
            filter.txnType?.let { type ->
                filterParts.add("type=$type")
            }
            if (filter.fromHour != null && filter.toHour != null) {
                filterParts.add(String.format(Locale.getDefault(), "%02d:00-%02d:00", filter.fromHour, filter.toHour))
            }
            sb.append("Filter: ${filterParts.joinToString(", ")}\n")
        }
        sb.append("Time\tSpoken Voice / Transcript\tItem Name\tQuantity\tPrice at Sale\tTotal (₹)\tPayment Mode\n")

        for (tx in filteredTransactions) {
            val timeStr = timeFormat.format(Date(tx.timestamp))
            val priceStr = if (tx.total == 0.0) "₹0* (PENDING)" else "₹${tx.total}"
            val transcriptStr = if (tx.rawTranscript.isNotBlank()) tx.rawTranscript else tx.itemName
            sb.append("${timeStr}\t${transcriptStr}\t${tx.itemName}\t${tx.quantity}\t₹${tx.priceAtSale}\t${priceStr}\t${tx.paymentMode}\n")
        }

        sb.append("\nTOTAL REVENUE:\t\t\t\t\t₹${filteredTotals.revenue.toInt()}\t\n")
        sb.append("Cash Total:\t\t\t\t\t₹${filteredTotals.cash.toInt()}\t\n")
        sb.append("UPI Total:\t\t\t\t\t₹${filteredTotals.upi.toInt()}\t\n")
        sb.append("Udhaar Total:\t\t\t\t\t₹${filteredTotals.credit.toInt()}\t\n")
        sb.append("Est. Margin:\t\t\t\t\t₹${totalMargin.toInt()} (${costCoveragePct.toInt()}% cost-covered)\t\n")

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
                    TextButton(onClick = onNavigateToReports) { Text("रिपोर्ट") }
                    IconButton(onClick = { handleExportRequest() }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Table for Excel")
                    }
                }
            )
        },
        bottomBar = {
            LedgerTotalBar(
                totals = filteredTotals,
                filter = filter,
                onClearFilter = { filter = LedgerFilter() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Filter Bar: Range Selector Toggle Chips & Feature Filters
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Date range chips
                FilterChip(
                    selected = rangeMode == RangeMode.DAY,
                    onClick = { onRangeModeChange(RangeMode.DAY) },
                    label = { Text("Today") }
                )
                FilterChip(
                    selected = rangeMode == RangeMode.WEEK,
                    onClick = { onRangeModeChange(RangeMode.WEEK) },
                    label = { Text("7 Days") }
                )
                FilterChip(
                    selected = rangeMode == RangeMode.MONTH,
                    onClick = { onRangeModeChange(RangeMode.MONTH) },
                    label = { Text("30 Days") }
                )

                // Item filter chip
                val itemLabel = filter.itemId?.let { catalogMap[it]?.name ?: "सामान" } ?: "सामान"
                FilterChip(
                    selected = filter.itemId != null,
                    onClick = { showItemSheet = true },
                    label = { Text(itemLabel) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )

                // Time filter chip
                val timeLabel = when {
                    filter.fromHour == 6 && filter.toHour == 11 -> "सुबह (6-11)"
                    filter.fromHour == 12 && filter.toHour == 16 -> "दोपहर (12-16)"
                    filter.fromHour == 17 && filter.toHour == 21 -> "शाम (17-21)"
                    filter.fromHour == 22 && filter.toHour == 23 -> "रात (22-23)"
                    filter.fromHour != null -> "${filter.fromHour}-${filter.toHour}"
                    else -> "समय"
                }
                FilterChip(
                    selected = filter.fromHour != null || filter.toHour != null,
                    onClick = { showTimeSheet = true },
                    label = { Text(timeLabel) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )

                // Payment mode filter chip
                val paymentLabel = when (filter.paymentMode) {
                    PaymentMode.CASH -> "नकद"
                    PaymentMode.UPI -> "UPI"
                    PaymentMode.CREDIT -> "उधार"
                    null -> "भुगतान"
                }
                FilterChip(
                    selected = filter.paymentMode != null,
                    onClick = { showPaymentSheet = true },
                    label = { Text(paymentLabel) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (filter.paymentMode) {
                            PaymentMode.CASH -> LedgerColors.MoneyIn
                            PaymentMode.UPI -> LedgerColors.Upi
                            PaymentMode.CREDIT -> LedgerColors.Udhaar
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                    )
                )

                // TxnType filter chip
                val typeLabel = when (filter.txnType) {
                    TxnType.SALE -> "बिक्री"
                    TxnType.RETURN -> "वापसी"
                    null -> "प्रकार"
                }
                FilterChip(
                    selected = filter.txnType != null,
                    onClick = { showTypeSheet = true },
                    label = { Text(typeLabel) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (filter.txnType) {
                            TxnType.SALE -> LedgerColors.MoneyIn
                            TxnType.RETURN -> LedgerColors.Udhaar
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                    )
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
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "📈 मुनाफ़ा: ₹${totalMargin.toInt()}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                            if (costedTx.isNotEmpty() && costCoveragePct < 99.5) {
                                Text(
                                    text = "(${costCoveragePct.toInt()}% बिक्री का हिसाब)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (costedTx.isEmpty()) {
                                Text(
                                    text = "(खरीद भाव दर्ज नहीं)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
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
            Text("Itemized Sales Breakdown (${filteredTransactions.size} items)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (filteredTransactions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (filter.isActive) "No sales match current filter." else "No sales logged in this range.",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn {
                    items(filteredTransactions, key = { it.id }) { tx ->
                        val isPending = tx.total == 0.0 || tx.priceAtSale == 0.0
                        val isPlaying = playingTxId == tx.id
                        val hasAudio = tx.audioFilePath.isNotBlank() && File(tx.audioFilePath).exists()

                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                                    txPendingVoid = tx
                                }
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
                                    val m = catalogMap[tx.itemId]
                                    ItemIcon(itemName = tx.itemName, imageUrl = m?.imageUrl, imagePath = m?.imagePath, size = 40.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
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

    // Item Selection Modal Sheet
    if (showItemSheet) {
        ModalBottomSheet(
            onDismissRequest = { showItemSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("सामान से फ़िल्टर करें", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        filter = filter.copy(itemId = null)
                        showItemSheet = false
                    }) {
                        Text("सभी सामान")
                    }
                }
                Spacer(Modifier.height(12.dp))

                val activeCatalog = catalog.filter { it.active }
                if (activeCatalog.isEmpty()) {
                    Text("कोई सामान उपलब्ध नहीं है", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        items(activeCatalog, key = { it.id }) { item ->
                            val isSelected = filter.itemId == item.id
                            Surface(
                                selected = isSelected,
                                onClick = {
                                    filter = filter.copy(itemId = if (isSelected) null else item.id)
                                    showItemSheet = false
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(8.dp)
                                ) {
                                    ItemIcon(
                                        itemName = item.name,
                                        imageUrl = item.imageUrl,
                                        imagePath = item.imagePath,
                                        size = 64.dp
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Time Selection Dialog
    if (showTimeSheet) {
        AlertDialog(
            onDismissRequest = { showTimeSheet = false },
            title = { Text("समय से फ़िल्टर करें") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            filter = filter.copy(fromHour = null, toHour = null)
                            showTimeSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("सभी समय (All Times)", fontWeight = if (filter.fromHour == null) FontWeight.Bold else FontWeight.Normal)
                    }
                    TextButton(
                        onClick = {
                            filter = filter.copy(fromHour = 6, toHour = 11)
                            showTimeSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🌅 सुबह (6:00 AM - 11:00 AM)")
                    }
                    TextButton(
                        onClick = {
                            filter = filter.copy(fromHour = 12, toHour = 16)
                            showTimeSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("☀️ दोपहर (12:00 PM - 4:00 PM)")
                    }
                    TextButton(
                        onClick = {
                            filter = filter.copy(fromHour = 17, toHour = 21)
                            showTimeSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🌆 शाम (5:00 PM - 9:00 PM)")
                    }
                    TextButton(
                        onClick = {
                            // रात wraps midnight (22..5) which a single >= AND <= pair cannot express —
                            // for रात only, set fromHour = 22, toHour = 23 and state in a code comment that the
                            // 0-5 half is deliberately excluded for now rather than silently returning nothing.
                            filter = filter.copy(fromHour = 22, toHour = 23)
                            showTimeSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🌙 रात (10:00 PM - 11:00 PM)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTimeSheet = false }) { Text("रद्द करें") }
            }
        )
    }

    // Payment Mode Selection Dialog
    if (showPaymentSheet) {
        AlertDialog(
            onDismissRequest = { showPaymentSheet = false },
            title = { Text("भुगतान के तरीके से फ़िल्टर करें") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            filter = filter.copy(paymentMode = null)
                            showPaymentSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("सभी भुगतान (All Modes)")
                    }
                    TextButton(
                        onClick = {
                            filter = filter.copy(paymentMode = PaymentMode.CASH)
                            showPaymentSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("💵 नकद (Cash)")
                    }
                    TextButton(
                        onClick = {
                            filter = filter.copy(paymentMode = PaymentMode.UPI)
                            showPaymentSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📲 UPI")
                    }
                    TextButton(
                        onClick = {
                            filter = filter.copy(paymentMode = PaymentMode.CREDIT)
                            showPaymentSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📜 उधार (Udhaar)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPaymentSheet = false }) { Text("रद्द करें") }
            }
        )
    }

    // Transaction Type Selection Dialog
    if (showTypeSheet) {
        AlertDialog(
            onDismissRequest = { showTypeSheet = false },
            title = { Text("लेनदेन के प्रकार से फ़िल्टर करें") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            filter = filter.copy(txnType = null)
                            showTypeSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("सभी प्रकार (All Types)")
                    }
                    TextButton(
                        onClick = {
                            filter = filter.copy(txnType = TxnType.SALE)
                            showTypeSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("बिक्री (Sale)")
                    }
                    TextButton(
                        onClick = {
                            filter = filter.copy(txnType = TxnType.RETURN)
                            showTypeSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("वापसी (Return)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTypeSheet = false }) { Text("रद्द करें") }
            }
        )
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

    // Void Confirmation Dialog
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

@Composable
private fun LedgerTotalBar(
    totals: FilteredTotals,
    filter: LedgerFilter,
    onClearFilter: () -> Unit
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (filter.isActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "फ़िल्टर लगा है",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    FilterChip(
                        selected = true,
                        onClick = onClearFilter,
                        label = { Text("फ़िल्टर हटाएँ") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    )
                }
            }

            Text(
                text = "₹${totals.revenue.toInt()}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "💵 ₹${totals.cash.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LedgerColors.MoneyIn,
                    fontWeight = FontWeight.SemiBold
                )
                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                Text(
                    text = "📲 ₹${totals.upi.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LedgerColors.Upi,
                    fontWeight = FontWeight.SemiBold
                )
                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                Text(
                    text = "📜 ₹${totals.credit.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LedgerColors.Udhaar,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = "${totals.lineCount} लाइन · ${if (totals.qty % 1.0 == 0.0) totals.qty.toInt().toString() else totals.qty} मात्रा",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
