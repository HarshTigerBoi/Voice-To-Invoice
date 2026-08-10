package com.voicetoinvoice.app.ui.screens.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.entity.CatalogItem

import androidx.compose.ui.platform.LocalContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.ui.components.ItemIcon
import com.voicetoinvoice.app.ui.components.PhotoCaptureButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogManagementScreen(
    catalog: List<CatalogItem>,
    stockLevels: Map<String, Double> = emptyMap(),
    onAddItem: (String, String, Double) -> Unit,
    onSetThreshold: (CatalogItem, Double?) -> Unit = { _, _ -> },
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    var reloadToken by remember { mutableStateOf(0) }

    var showAddDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var newItemPrice by remember { mutableStateOf("") }
    var newItemImageUrl by remember { mutableStateOf("") }

    var selectedItemForThreshold by remember { mutableStateOf<CatalogItem?>(null) }
    var thresholdInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shop Catalog & Stock Level") },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Text("+ Item")
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            items(catalog, key = { "${it.id}_$reloadToken" }) { item ->
                val onHand = stockLevels[item.id] ?: 0.0
                val isLowStock = item.lowStockThreshold != null && onHand <= item.lowStockThreshold

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            selectedItemForThreshold = item
                            thresholdInput = item.lowStockThreshold?.toString() ?: ""
                        },
                    colors = if (isLowStock) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                    } else {
                        CardDefaults.cardColors()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            PhotoCaptureButton(
                                currentPath = item.imagePath,
                                filePrefix = "item",
                                onCaptured = { path ->
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            db.catalogDao().updateImagePath(item.id, path)
                                        }
                                        reloadToken++
                                    }
                                },
                                size = 48.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text("Unit: ${item.unitId} • On hand: ${onHand} ${item.unitId}", style = MaterialTheme.typography.bodySmall)
                                if (item.lowStockThreshold != null) {
                                    Text("Low stock threshold: ${item.lowStockThreshold} ${item.unitId}", style = MaterialTheme.typography.labelSmall, color = if (isLowStock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("₹${item.price}/${item.unitId}", style = MaterialTheme.typography.titleMedium)
                            Text("Tap to set alert", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Custom Catalog Item") },
            text = {
                Column {
                    OutlinedTextField(value = newItemName, onValueChange = { newItemName = it }, label = { Text("Item Name") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = newItemPrice, onValueChange = { newItemPrice = it }, label = { Text("Price per KG/Pcs") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newItemImageUrl,
                        onValueChange = { newItemImageUrl = it },
                        label = { Text("Icon / Image Link (Optional)") },
                        placeholder = { Text("https://... or Vecteezy link") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val price = newItemPrice.toDoubleOrNull()
                    if (newItemName.isNotBlank() && price != null) {
                        onAddItem(newItemName, "KG", price)
                        showAddDialog = false
                    }
                }) {
                    Text("Add Item")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (selectedItemForThreshold != null) {
        val targetItem = selectedItemForThreshold!!
        AlertDialog(
            onDismissRequest = { selectedItemForThreshold = null },
            title = { Text("Low Stock Alert Threshold") },
            text = {
                Column {
                    Text("Set minimum on-hand stock alert for ${targetItem.name}:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = thresholdInput,
                        onValueChange = { thresholdInput = it },
                        label = { Text("Low Stock Threshold (${targetItem.unitId})") },
                        placeholder = { Text("e.g. 5.0 (leave blank to remove)") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val parsedThreshold = thresholdInput.toDoubleOrNull()
                    onSetThreshold(targetItem, parsedThreshold)
                    selectedItemForThreshold = null
                }) {
                    Text("Save Alert")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedItemForThreshold = null }) { Text("Cancel") }
            }
        )
    }
}
