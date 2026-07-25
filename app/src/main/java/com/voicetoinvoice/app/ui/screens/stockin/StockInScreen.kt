package com.voicetoinvoice.app.ui.screens.stockin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.entity.CatalogItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockInScreen(
    catalog: List<CatalogItem>,
    onAddStockIn: (CatalogItem, Double, Double, String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var selectedItem by remember { mutableStateOf<CatalogItem?>(catalog.firstOrNull()) }
    var qtyText by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    var supplierText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stock-In / Purchase Log") },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Log Incoming Produce Stock", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = qtyText,
                onValueChange = { qtyText = it },
                label = { Text("Quantity Received (KG/Pcs)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = costText,
                onValueChange = { costText = it },
                label = { Text("Total Cost Price (₹)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = supplierText,
                onValueChange = { supplierText = it },
                label = { Text("Supplier Name (Optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val item = selectedItem
                    val qty = qtyText.toDoubleOrNull()
                    val cost = costText.toDoubleOrNull()
                    if (item != null && qty != null && cost != null) {
                        onAddStockIn(item, qty, cost, supplierText)
                        onNavigateBack()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Stock Entry")
            }
        }
    }
}
