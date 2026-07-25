package com.voicetoinvoice.app.ui.screens.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.entity.CatalogItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogManagementScreen(
    catalog: List<CatalogItem>,
    onAddItem: (String, String, Double) -> Unit,
    onNavigateBack: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var newItemPrice by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shop Catalog Management") },
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
            items(catalog) { item ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text("Unit: ${item.unitId}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("₹${item.price}/${item.unitId}", style = MaterialTheme.typography.titleMedium)
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
}
