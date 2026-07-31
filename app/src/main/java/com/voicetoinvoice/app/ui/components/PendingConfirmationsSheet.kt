package com.voicetoinvoice.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
import com.voicetoinvoice.app.utils.AudioFileManager
import org.json.JSONArray

/** One line of a (possibly multi-item) voice recording, as sent by the server in
 *  parsedItemsJson (finalParsedItems / step_4_grok_ai_interpretation, verbatim). */
data class PendingLine(
    val lineNo: Int,
    val itemName: String,
    /** Catalog row this line resolved to, or null when the item isn't stocked yet.
     *  A null here is the single most common reason a line is pending — see ISSUE-030. */
    val itemId: String?,
    val quantity: Double,
    val unit: String,
    val priceAtSale: Double,
    val total: Double,
    val priceIntent: String,
    val implausibilityReason: String?,
    /** Already written to the ledger (transactions) or applied to the catalog
     *  (RATE_UPDATE) by the server/SttWorker -- shown as a fact, not actionable. */
    val committed: Boolean,
    /** Resolved by the shopkeeper in THIS UI (confirmed or discarded) -- also no
     *  longer actionable, persisted back into parsedItemsJson so it survives
     *  recomposition and app restarts. */
    val resolved: Boolean,
    val captureIntent: com.voicetoinvoice.app.data.local.entity.CaptureIntent = com.voicetoinvoice.app.data.local.entity.CaptureIntent.SALE
)

/** Parses a job's per-line breakdown, falling back to a single line built from the
 *  scalar parsed* columns for jobs recorded before parsedItemsJson existed. */
fun parsePendingLines(job: SttJobRecord): List<PendingLine> {
    if (job.parsedItemsJson.isBlank()) {
        return listOf(
            PendingLine(
                lineNo = 0,
                itemName = job.parsedItemName,
                itemId = job.parsedItemId,
                quantity = job.parsedQty,
                unit = job.parsedUnit,
                priceAtSale = if (job.parsedQty > 0) job.parsedTotal / job.parsedQty else 0.0,
                total = job.parsedTotal,
                priceIntent = "NONE",
                implausibilityReason = null,
                committed = false,
                resolved = false,
                captureIntent = job.captureIntent
            )
        )
    }
    return try {
        val arr = JSONArray(job.parsedItemsJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val priceIntent = o.optString("price_intent", "NONE")
            val confidence = o.optDouble("confidence", 0.0)
            val priceAtSale = o.optDouble("price_at_sale", 0.0)
            val total = o.optDouble("total", 0.0)
            val itemName = o.optString("item_name", "Unrecognized Item")
            val implausibility = if (o.isNull("implausibility_reason")) null else o.optString("implausibility_reason", "").ifBlank { null }
            val itemId = if (o.isNull("item_id")) null else o.optString("item_id", "").ifBlank { null }
            val isValidRateUpdate = priceIntent == "RATE_UPDATE" && itemId != null && confidence >= 0.80 && priceAtSale > 0.0 && implausibility == null
            val isCommittableSale = priceIntent != "RATE_UPDATE" && confidence >= 0.80 && priceAtSale > 0.0 && total > 0.0 &&
                itemName.isNotBlank() && itemName != "Unrecognized Item" && implausibility == null
            PendingLine(
                lineNo = i,
                itemName = itemName,
                itemId = itemId,
                quantity = o.optDouble("quantity", 1.0),
                unit = o.optString("unit", "PACKET"),
                priceAtSale = priceAtSale,
                total = total,
                priceIntent = priceIntent,
                implausibilityReason = implausibility,
                committed = isValidRateUpdate || isCommittableSale,
                resolved = o.optBoolean("client_resolved", false),
                captureIntent = job.captureIntent
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Marks one line as client_resolved inside a job's parsedItemsJson so it stops being
 *  offered again, and returns the updated JSON string. Safe no-op on parse failure. */
fun markLineResolved(job: SttJobRecord, lineNo: Int): String {
    if (job.parsedItemsJson.isBlank()) return job.parsedItemsJson
    return try {
        val arr = JSONArray(job.parsedItemsJson)
        if (lineNo in 0 until arr.length()) {
            arr.getJSONObject(lineNo).put("client_resolved", true)
        }
        arr.toString()
    } catch (_: Exception) {
        job.parsedItemsJson
    }
}

/** Collapses duplicate catalog rows for display, keeping the most recently updated one.
 *  The catalog currently holds several same-name rows (e.g. Aaloo x3), and showing all of
 *  them in a picker would be actively confusing. */
fun distinctCatalogByName(catalog: List<CatalogItem>): List<CatalogItem> =
    catalog.groupBy { it.name.trim().lowercase() }
        .mapNotNull { (_, rows) -> rows.maxByOrNull { it.updatedAt } }
        .sortedBy { it.name.lowercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingConfirmationsSheet(
    pendingJobs: List<SttJobRecord>,
    catalog: List<CatalogItem>,
    onConfirmLine: (job: SttJobRecord, line: PendingLine, isLastPendingLine: Boolean, resolvedItemId: String?, rate: Double) -> Unit,
    onDiscardLine: (job: SttJobRecord, line: PendingLine, isLastPendingLine: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val audioFileManager = remember { AudioFileManager(context) }
    var playingJobId by remember { mutableStateOf<String?>(null) }
    var editingLine by remember { mutableStateOf<Pair<SttJobRecord, PendingLine>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            audioFileManager.stopAudio()
        }
    }

    val jobsWithPendingLines = remember(pendingJobs) {
        pendingJobs.map { job -> job to parsePendingLines(job) }
            .filter { (_, lines) -> lines.isEmpty() || lines.any { !it.committed && !it.resolved } }
    }
    val totalPendingLines = jobsWithPendingLines.sumOf { (_, lines) ->
        if (lines.isEmpty()) 1 else lines.count { !it.committed && !it.resolved }
    }

    ModalBottomSheet(
        onDismissRequest = {
            audioFileManager.stopAudio()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "पेंडिंग ($totalPendingLines)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (jobsWithPendingLines.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No pending sales to confirm", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(jobsWithPendingLines, key = { it.first.id }) { (job, lines) ->
                        val isPlaying = playingJobId == job.id
                        val pendingLines = lines.filter { !it.committed && !it.resolved }
                        val committedLines = lines.filter { it.committed }

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (job.isSanityFlagged) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val intentChip = job.captureIntent.hindiLabel
                                    Text(
                                        text = "Spoken: \"${job.rawTranscript}\"",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = intentChip,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                if (pendingLines.isEmpty() && committedLines.isEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "⚠️ ${if (job.rawTranscript.isNotBlank()) job.rawTranscript else "आवाज़ समझ नहीं आई"}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.End,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                if (isPlaying) {
                                                    audioFileManager.stopAudio()
                                                    playingJobId = null
                                                } else {
                                                    playingJobId = job.id
                                                    audioFileManager.playAudio(job.audioFilePath) {
                                                        playingJobId = null
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(20.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (isPlaying) "Playing..." else "Audio", fontSize = 12.sp)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        OutlinedButton(
                                            onClick = {
                                                val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.voicetoinvoice.app.domain.processor.SttWorker>()
                                                    .setInputData(
                                                        androidx.work.workDataOf(
                                                            com.voicetoinvoice.app.domain.processor.SttWorker.KEY_JOB_ID to job.id,
                                                            com.voicetoinvoice.app.domain.processor.SttWorker.KEY_AUDIO_PATH to job.audioFilePath
                                                        )
                                                    )
                                                    .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                                    .build()
                                                androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
                                                android.widget.Toast.makeText(context, "फिर से प्रोसेस किया जा रहा है...", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(20.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("फिर कोशिश करें", fontSize = 12.sp)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        Button(
                                            onClick = {
                                                val dummyLine = PendingLine(
                                                    lineNo = 0,
                                                    itemName = "",
                                                    itemId = null,
                                                    quantity = 1.0,
                                                    unit = "PACKET",
                                                    priceAtSale = 0.0,
                                                    total = 0.0,
                                                    priceIntent = "NONE",
                                                    implausibilityReason = null,
                                                    committed = false,
                                                    resolved = false,
                                                    captureIntent = job.captureIntent
                                                )
                                                editingLine = job to dummyLine
                                            },
                                            shape = RoundedCornerShape(20.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("हाथ से भरें")
                                        }
                                    }
                                } else {
                                    if (committedLines.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        committedLines.forEach { line ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                val summary = if (line.priceIntent == "RATE_UPDATE")
                                                    "${line.itemName} rate updated to ₹${line.priceAtSale}"
                                                else
                                                    "${line.itemName} ${line.quantity} ${line.unit} • ₹${line.total} booked"
                                                Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    pendingLines.forEachIndexed { idx, line ->
                                    if (idx > 0) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    }
                                    val isLastPendingLine = pendingLines.size == 1

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = line.itemName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.sp
                                            )
                                            Text(
                                                text = "${line.quantity} ${line.unit}",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Text(
                                            text = if (line.total > 0.0) "₹${line.total}" else "₹—",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    // The server now always explains an unbookable line
                                    // (ISSUE-030); previously an unlisted item showed
                                    // "₹0" with no reason at all.
                                    if (line.implausibilityReason != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "⚠️ ${line.implausibilityReason}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.End,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (idx == 0) {
                                            OutlinedButton(
                                                onClick = {
                                                    if (isPlaying) {
                                                        audioFileManager.stopAudio()
                                                        playingJobId = null
                                                    } else {
                                                        playingJobId = job.id
                                                        audioFileManager.playAudio(job.audioFilePath) {
                                                            playingJobId = null
                                                        }
                                                    }
                                                },
                                                shape = RoundedCornerShape(20.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = "Play Audio",
                                                    tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(if (isPlaying) "Playing..." else "Audio", fontSize = 12.sp)
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }

                                        OutlinedButton(
                                            onClick = { editingLine = job to line },
                                            shape = RoundedCornerShape(20.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Edit", fontSize = 12.sp)
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        IconButton(
                                            onClick = { onDiscardLine(job, line, isLastPendingLine) },
                                            colors = IconButtonDefaults.iconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer
                                            ),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Discard", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        val isStockIntent = line.captureIntent == com.voicetoinvoice.app.data.local.entity.CaptureIntent.STOCK_IN || line.captureIntent == com.voicetoinvoice.app.data.local.entity.CaptureIntent.WASTE
                                        val isConfirmable = if (isStockIntent) line.quantity > 0.0 && line.itemName.isNotBlank() && line.itemName != "Unrecognized Item" else line.total > 0.0 && line.itemName.isNotBlank() && line.itemName != "Unrecognized Item"

                                        Button(
                                            onClick = {
                                                if (isConfirmable) {
                                                    onConfirmLine(job, line, isLastPendingLine, line.itemId, line.priceAtSale)
                                                } else {
                                                    editingLine = job to line
                                                }
                                            },
                                            shape = RoundedCornerShape(20.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (isConfirmable) "Confirm" else "भाव डालें")
                                        }
                                    }
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
    }

    editingLine?.let { (job, line) ->
        PendingLineEditDialog(
            job = job,
            line = line,
            catalog = catalog,
            onDismiss = { editingLine = null },
            onSave = { resolvedItemId, name, qty, unit, rate ->
                val pendingCount = parsePendingLines(job).count { !it.committed && !it.resolved }
                editingLine = null
                onConfirmLine(
                    job,
                    line.copy(
                        itemName = name,
                        itemId = resolvedItemId,
                        quantity = qty,
                        unit = unit,
                        priceAtSale = rate,
                        total = qty * rate,
                        implausibilityReason = null
                    ),
                    pendingCount <= 1,
                    resolvedItemId,
                    rate
                )
            }
        )
    }
}

/**
 * Edit one pending line.
 *
 * Two deliberate departures from the previous version (ISSUE-030):
 *  - The item name is a **searchable picker** over the shop's catalog, so "this is the
 *    Aaloo I already stock" is selectable rather than retyped. Free text still works and
 *    offers an explicit "add as a new item" action, which is the only way a genuinely new
 *    product enters the catalog.
 *  - The money field is the **rate**, not the total. Asking a shopkeeper for the total of
 *    6 KG at ₹80 makes them do the multiplication; asking for the rate does not, and the
 *    rate is also the value worth saving back to the catalog so the item never lands in
 *    this queue again.
 */
@Composable
private fun PendingLineEditDialog(
    job: SttJobRecord,
    line: PendingLine,
    catalog: List<CatalogItem>,
    onDismiss: () -> Unit,
    onSave: (resolvedItemId: String?, name: String, qty: Double, unit: String, rate: Double) -> Unit
) {
    val distinct = remember(catalog) { distinctCatalogByName(catalog) }

    var editName by remember(line) { mutableStateOf(line.itemName) }
    var editQty by remember(line) { mutableStateOf(trimNumber(line.quantity)) }
    var editUnit by remember(line) { mutableStateOf(line.unit) }
    var editRate by remember(line) {
        mutableStateOf(if (line.priceAtSale > 0.0) trimNumber(line.priceAtSale) else "")
    }
    var resolvedItemId by remember(line) { mutableStateOf(line.itemId) }
    var showSuggestions by remember(line) { mutableStateOf(line.itemId == null) }

    val query = editName.trim().lowercase()
    val suggestions = remember(query, distinct) {
        if (query.isBlank()) distinct.take(6)
        else distinct.filter { it.name.lowercase().contains(query) }.take(6)
    }
    val exactMatch = remember(query, distinct) {
        distinct.firstOrNull { it.name.trim().lowercase() == query }
    }

    val isStockIntent = line.captureIntent == com.voicetoinvoice.app.data.local.entity.CaptureIntent.STOCK_IN || line.captureIntent == com.voicetoinvoice.app.data.local.entity.CaptureIntent.WASTE
    val qtyVal = editQty.toDoubleOrNull() ?: line.quantity
    val rateVal = editRate.toDoubleOrNull() ?: 0.0
    val computedTotal = qtyVal * rateVal
    val canSave = if (isStockIntent) editName.isNotBlank() && qtyVal > 0.0 else editName.isNotBlank() && rateVal > 0.0 && qtyVal > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (line.itemId == null) "नया आइटम — भाव डालें" else "Edit Pending Line") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = editName,
                    onValueChange = {
                        editName = it
                        resolvedItemId = null   // typing invalidates any previous pick
                        showSuggestions = true
                    },
                    label = { Text("Item Name") },
                    singleLine = true,
                    supportingText = {
                        if (resolvedItemId != null) Text("✓ आपके कैटलॉग से")
                        else if (editName.isNotBlank()) Text("नया आइटम — कैटलॉग में जुड़ जाएगा")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (showSuggestions) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 190.dp)
                            .verticalScroll(rememberScrollState())
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp)
                            )
                    ) {
                        suggestions.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        editName = item.name
                                        editUnit = item.unitId
                                        resolvedItemId = item.id
                                        if (item.price > 0.0) editRate = trimNumber(item.price)
                                        showSuggestions = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(item.name, fontSize = 14.sp)
                                Text(
                                    "₹${trimNumber(item.price)}/${item.unitId}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Only offer "create" when the typed name isn't already stocked --
                        // otherwise this is the button that grows the duplicate rows.
                        if (editName.isNotBlank() && exactMatch == null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        resolvedItemId = null
                                        showSuggestions = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "\"${editName.trim()}\" को नया आइटम बनाएँ",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (suggestions.isEmpty() && editName.isBlank()) {
                            Text(
                                "कैटलॉग खाली है",
                                fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editQty,
                        onValueChange = { editQty = it },
                        label = { Text("Quantity") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = editUnit,
                        onValueChange = { editUnit = it },
                        label = { Text("Unit") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = editRate,
                    onValueChange = { editRate = it },
                    label = { Text(if (isStockIntent) "लागत भाव (₹ प्रति ${editUnit.ifBlank { "unit" }}) — वैकल्पिक" else "भाव (₹ प्रति ${editUnit.ifBlank { "unit" }})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = if (computedTotal > 0.0)
                        "कुल: ${trimNumber(qtyVal)} ${editUnit} × ₹${trimNumber(rateVal)} = ₹${trimNumber(computedTotal)}"
                    else
                        "भाव डालें ताकि कुल निकल सके",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (computedTotal > 0.0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = { onSave(resolvedItemId, editName.trim(), qtyVal, editUnit, rateVal) }
            ) {
                Text("Save & Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** 6.0 -> "6", 6.5 -> "6.5" — shopkeeper-facing numbers should not carry noise decimals. */
private fun trimNumber(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
