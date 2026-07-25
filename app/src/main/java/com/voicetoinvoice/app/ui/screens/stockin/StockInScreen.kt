package com.voicetoinvoice.app.ui.screens.stockin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.SupplierRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockInScreen(
    catalog: List<CatalogItem>,
    suppliers: List<SupplierRecord> = emptyList(),
    onAddStockIn: (CatalogItem, Double, Double, String, String?) -> Unit,
    onNavigateBack: () -> Unit
) {
    var selectedItem by remember { mutableStateOf<CatalogItem?>(catalog.firstOrNull()) }
    var itemDropdownExpanded by remember { mutableStateOf(false) }

    var selectedSupplier by remember { mutableStateOf<SupplierRecord?>(null) }
    var supplierDropdownExpanded by remember { mutableStateOf(false) }

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

            // Catalog Item Selector Dropdown
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = selectedItem?.name ?: "Select Item",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Catalog Item") },
                    modifier = Modifier.fillMaxWidth().clickable { itemDropdownExpanded = true }
                )
                DropdownMenu(
                    expanded = itemDropdownExpanded,
                    onDismissRequest = { itemDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    catalog.forEach { item ->
                        DropdownMenuItem(
                            text = { Text("${item.name} (₹${item.price}/${item.unitId})") },
                            onClick = {
                                selectedItem = item
                                itemDropdownExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

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

            // Supplier Picker Dropdown
            if (suppliers.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedSupplier?.name ?: "Select Saved Supplier (Optional)",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Saved Supplier Ledger") },
                        modifier = Modifier.fillMaxWidth().clickable { supplierDropdownExpanded = true }
                    )
                    DropdownMenu(
                        expanded = supplierDropdownExpanded,
                        onDismissRequest = { supplierDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("None (Use Free Text / Cash)") },
                            onClick = {
                                selectedSupplier = null
                                supplierDropdownExpanded = false
                            }
                        )
                        suppliers.forEach { supp ->
                            DropdownMenuItem(
                                text = { Text("${supp.name} (Owed: ₹${supp.balanceOwed.toInt()})") },
                                onClick = {
                                    selectedSupplier = supp
                                    supplierText = supp.name
                                    supplierDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = supplierText,
                onValueChange = {
                    supplierText = it
                    if (selectedSupplier != null && selectedSupplier?.name != it) {
                        selectedSupplier = null
                    }
                },
                label = { Text("Supplier Name (Free Text)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val item = selectedItem
                    val qty = qtyText.toDoubleOrNull()
                    val cost = costText.toDoubleOrNull()
                    if (item != null && qty != null && cost != null) {
                        onAddStockIn(item, qty, cost, supplierText, selectedSupplier?.id)
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
