package com.voicetoinvoice.app.ui.screens.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetoinvoice.app.data.local.entity.CustomerRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerListScreen(
    customers: List<CustomerRecord>,
    outstandingMap: Map<String, Double> = emptyMap(),
    duplicates: List<Pair<CustomerRecord, CustomerRecord>> = emptyList(),
    onAddCustomerClick: () -> Unit,
    onCustomerClick: (CustomerRecord) -> Unit,
    onMergeClick: (Pair<CustomerRecord, CustomerRecord>) -> Unit,
    // Fix 4 (gap_analysis_and_fix_plan.md): Summary was unreachable from the tab bar --
    // these quick-links make हिसाब the one place all "look things up" screens live.
    onNavigateToSummary: () -> Unit = {},
    onNavigateToSuppliers: () -> Unit = {},
    onNavigateToPriceUpdate: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedDuplicatePair by remember { mutableStateOf<Pair<CustomerRecord, CustomerRecord>?>(null) }

    val sortedCustomers = remember(customers, outstandingMap) {
        customers.sortedWith(
            compareByDescending<CustomerRecord> { outstandingMap[it.id] ?: 0.0 }
                .thenByDescending { it.lastSeenMs }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("हिसाब", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToSummary) {
                        Icon(Icons.Default.Receipt, contentDescription = "सारांश (Summary)")
                    }
                    IconButton(onClick = onNavigateToSuppliers) {
                        Icon(Icons.Default.LocalShipping, contentDescription = "आपूर्तिकर्ता (Suppliers)")
                    }
                    IconButton(onClick = onNavigateToPriceUpdate) {
                        Icon(Icons.Default.Edit, contentDescription = "भाव (Prices)")
                    }
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.Default.Info, contentDescription = "Logs")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddCustomerClick,
                icon = { Icon(Icons.Default.Add, contentDescription = "Add Customer") },
                text = { Text("नया ग्राहक") },
                containerColor = MaterialTheme.colorScheme.primary
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Duplicate-suspect banner at top if duplicates exist
            if (duplicates.isNotEmpty()) {
                val pair = duplicates.first()
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clickable { selectedDuplicatePair = pair },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Duplicate Suspect",
                            tint = Color(0xFFE65100),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "संभावित एक जैसे खाते detected!",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100),
                                fontSize = 14.sp
                            )
                            Text(
                                text = "'${pair.first.name}' और '${pair.second.name}' को जोड़ें (Merge)",
                                fontSize = 12.sp,
                                color = Color(0xFFBF360C)
                            )
                        }
                        TextButton(onClick = { selectedDuplicatePair = pair }) {
                            Text("देखें", fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                        }
                    }
                }
            }

            if (sortedCustomers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "कोई ग्राहक नहीं मिला। '+ नया ग्राहक' पर टैप करके जोड़ें।",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(sortedCustomers, key = { it.id }) { customer ->
                        CustomerCard(
                            customer = customer,
                            outstandingAmount = outstandingMap[customer.id] ?: 0.0,
                            onClick = { onCustomerClick(customer) }
                        )
                    }
                }
            }
        }

        selectedDuplicatePair?.let { pair ->
            CustomerMergeDialog(
                pair = pair,
                onConfirmMerge = {
                    onMergeClick(pair)
                    selectedDuplicatePair = null
                },
                onDismiss = { selectedDuplicatePair = null }
            )
        }
    }
}
