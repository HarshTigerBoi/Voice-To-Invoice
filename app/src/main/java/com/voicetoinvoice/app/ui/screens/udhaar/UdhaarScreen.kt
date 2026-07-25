package com.voicetoinvoice.app.ui.screens.udhaar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.entity.CreditRecord
import com.voicetoinvoice.app.data.local.entity.CreditStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UdhaarScreen(
    credits: List<CreditRecord>,
    onAddCredit: (String, Double) -> Unit,
    onMarkPaid: (CreditRecord) -> Unit,
    onSendReminder: (CreditRecord) -> Unit = {},
    onNavigateBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    val filteredCredits = credits.filter { it.customerName.contains(searchQuery, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Customer Udhaar Ledger") },
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
                label = { Text("Search Customer Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn {
                items(filteredCredits) { credit ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(credit.customerName, style = MaterialTheme.typography.titleMedium)
                                Text("Owed: ₹${credit.amount.toInt()}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                            }
                            if (credit.status == CreditStatus.PENDING) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onSendReminder(credit) }) {
                                        Text("Remind")
                                    }
                                    Button(onClick = { onMarkPaid(credit) }) {
                                        Text("Mark Paid")
                                    }
                                }
                            } else {
                                Text("PAID", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
