package com.voicetoinvoice.app.ui.screens.supplier

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.entity.SupplierRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierScreen(
    suppliers: List<SupplierRecord>,
    onAddSupplier: (String, String?) -> Unit,
    onSettleBalance: (SupplierRecord) -> Unit,
    onNavigateBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var supplierNameInput by remember { mutableStateOf("") }
    var supplierPhoneInput by remember { mutableStateOf("") }

    val filteredSuppliers = suppliers.filter { it.name.contains(searchQuery, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Supplier Ledger") },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Text("+ New")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Supplier Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn {
                items(filteredSuppliers) { supplier ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(supplier.name, style = MaterialTheme.typography.titleMedium)
                                if (!supplier.phone.isNull_or_empty()) {
                                    Text("Phone: ${supplier.phone}", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "Balance Owed: ₹${supplier.balanceOwed.toInt()}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (supplier.balanceOwed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                            if (supplier.balanceOwed > 0) {
                                Button(onClick = { onSettleBalance(supplier) }) {
                                    Text("Settle")
                                }
                            } else {
                                Text("SETTLED", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Supplier") },
            text = {
                Column {
                    OutlinedTextField(
                        value = supplierNameInput,
                        onValueChange = { supplierNameInput = it },
                        label = { Text("Supplier Name") }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = supplierPhoneInput,
                        onValueChange = { supplierPhoneInput = it },
                        label = { Text("Phone Number (Optional)") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (supplierNameInput.isNotBlank()) {
                        onAddSupplier(
                            supplierNameInput,
                            supplierPhoneInput.ifBlank { null }
                        )
                        supplierNameInput = ""
                        supplierPhoneInput = ""
                        showAddDialog = false
                    }
                }) {
                    Text("Add Supplier")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun String?.isNull_or_empty(): Boolean = this == null || this.trim().isEmpty()
