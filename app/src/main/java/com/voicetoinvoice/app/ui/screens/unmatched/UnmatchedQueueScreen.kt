package com.voicetoinvoice.app.ui.screens.unmatched

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
import com.voicetoinvoice.app.data.local.entity.UnmatchedQueueItem
import com.voicetoinvoice.app.domain.validation.SaleValidation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnmatchedQueueScreen(
    unmatchedItems: List<UnmatchedQueueItem>,
    sttJobs: List<SttJobRecord>,
    catalog: List<CatalogItem>,
    onConfirmSale: (unmatchedId: String, itemName: String, qty: Double, unit: String, price: Double, matchedItem: CatalogItem?) -> Unit,
    onDiscardItem: (UnmatchedQueueItem) -> Unit,
    onNavigateBack: () -> Unit
) {
    var selectedItemForCatalogAssign by remember { mutableStateOf<UnmatchedQueueItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Unmatched Voice Queue (${unmatchedItems.size})", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }
            )
        }
    ) { padding ->
        if (unmatchedItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("All voice sales matched!", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(unmatchedItems, key = { it.id }) { queueItem ->
                    val jobRecord = sttJobs.find { it.id == queueItem.id }
                    
                    val initialName = when {
                        !jobRecord?.parsedItemName.isNull_or_empty() -> jobRecord!!.parsedItemName
                        else -> queueItem.rawTranscript
                    }
                    val initialQty = jobRecord?.parsedQty ?: 1.0
                    val initialUnit = jobRecord?.parsedUnit ?: "PACKET"
                    val initialPrice = if (jobRecord != null && jobRecord.parsedTotal > 0.0) {
                        jobRecord.parsedTotal / initialQty
                    } else 0.0

                    UnmatchedItemCard(
                        queueItem = queueItem,
                        initialName = initialName,
                        initialQty = initialQty,
                        initialUnit = initialUnit,
                        initialPrice = initialPrice,
                        catalog = catalog,
                        onConfirm = { name, qty, unit, price, matchedCatalog ->
                            onConfirmSale(queueItem.id, name, qty, unit, price, matchedCatalog)
                        },
                        onAssignCatalog = {
                            selectedItemForCatalogAssign = queueItem
                        },
                        onDiscard = {
                            onDiscardItem(queueItem)
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }

    // Catalog Picker Dialog for Assigning SKU
    if (selectedItemForCatalogAssign != null) {
        val queueItem = selectedItemForCatalogAssign!!
        val jobRecord = sttJobs.find { it.id == queueItem.id }
        val curQty = jobRecord?.parsedQty ?: 1.0

        AlertDialog(
            onDismissRequest = { selectedItemForCatalogAssign = null },
            title = { Text("Assign Catalog SKU") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                    Text("Select matching SKU for \"${queueItem.rawTranscript}\":", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn {
                        items(catalog) { catItem ->
                            Surface(
                                onClick = {
                                    onConfirmSale(queueItem.id, catItem.name, curQty, catItem.unitId, catItem.price, catItem)
                                    selectedItemForCatalogAssign = null
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(catItem.name, fontWeight = FontWeight.Bold)
                                        Text("${catItem.unitId} • ₹${catItem.price}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("Select", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedItemForCatalogAssign = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun UnmatchedItemCard(
    queueItem: UnmatchedQueueItem,
    initialName: String,
    initialQty: Double,
    initialUnit: String,
    initialPrice: Double,
    catalog: List<CatalogItem>,
    onConfirm: (name: String, qty: Double, unit: String, price: Double, matchedItem: CatalogItem?) -> Unit,
    onAssignCatalog: () -> Unit,
    onDiscard: () -> Unit
) {
    var nameState by remember(queueItem.id) { mutableStateOf(initialName) }
    var qtyState by remember(queueItem.id) { mutableStateOf(if (initialQty > 0) initialQty.toString() else "1") }
    var unitState by remember(queueItem.id) { mutableStateOf(initialUnit) }
    var priceState by remember(queueItem.id) { mutableStateOf(if (initialPrice > 0) initialPrice.toString() else "") }

    val parsedQty = qtyState.toDoubleOrNull() ?: 0.0
    val parsedPrice = priceState.toDoubleOrNull() ?: 0.0
    val calculatedTotal = parsedQty * parsedPrice

    val matchedCatalogItem = catalog.find { it.name.equals(nameState.trim(), ignoreCase = true) }
    val hasCatalogMatch = matchedCatalogItem != null

    val isConfirmEnabled = SaleValidation.isConfirmableSale(nameState, parsedPrice, parsedQty)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Secondary Raw Transcript
            Text(
                text = "\"${queueItem.rawTranscript}\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Editable Item Name, Qty & Unit
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = nameState,
                    onValueChange = { nameState = it },
                    label = { Text("Item Name") },
                    modifier = Modifier.weight(1.5f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = qtyState,
                    onValueChange = { qtyState = it },
                    label = { Text("Qty") },
                    modifier = Modifier.weight(0.8f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = unitState,
                    onValueChange = { unitState = it },
                    label = { Text("Unit") },
                    modifier = Modifier.weight(0.9f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Catalog Match Warning Pill (Only if matchedCatalogItem is null)
            if (!hasCatalogMatch) {
                Surface(
                    color = Color(0xFFFFF3CD),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "⚠ No catalog match",
                        color = Color(0xFF856404),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Price & Calculated Total Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Price: ₹", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = priceState,
                        onValueChange = { priceState = it },
                        placeholder = { Text("______") },
                        modifier = Modifier.width(100.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    Text(" × $parsedQty = ", modifier = Modifier.padding(start = 4.dp))
                    Text(
                        text = "₹${calculatedTotal.toInt()}",
                        fontWeight = FontWeight.Bold,
                        color = if (calculatedTotal > 0) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onConfirm(nameState.trim(), parsedQty, unitState.trim(), parsedPrice, matchedCatalogItem) },
                    enabled = isConfirmEnabled,
                    modifier = Modifier.weight(1.2f)
                ) {
                    Text("Confirm to Ledger", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onAssignCatalog,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Assign SKU", fontSize = 12.sp)
                }

                TextButton(
                    onClick = onDiscard,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Discard", fontSize = 12.sp)
                }
            }
        }
    }
}

private fun String?.isNull_or_empty(): Boolean = this == null || this.trim().isEmpty()
