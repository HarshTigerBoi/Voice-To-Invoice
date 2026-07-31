package com.voicetoinvoice.app.ui.screens.customer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetoinvoice.app.data.local.entity.CustomerRecord
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The बही-खाता page (Fix 3 / gap_analysis_and_fix_plan.md) -- previously tapping a customer
 * discarded the customer entirely and opened the generic UDHAAR screen instead.
 */
import androidx.compose.material.icons.filled.Share

import androidx.compose.material.icons.filled.Repeat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDetailScreen(
    customer: CustomerRecord,
    outstandingAmount: Double,
    ledger: List<TransactionRecord>,
    onNavigateBack: () -> Unit,
    onSendReminder: (CustomerRecord) -> Unit = {},
    onSendBill: (CustomerRecord) -> Unit = {},
    onRepeatOrder: (List<TransactionRecord>) -> Unit = {},
    onEditClick: (CustomerRecord) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${customer.name} का खाता", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onSendBill(customer) }) {
                        Icon(Icons.Default.Share, contentDescription = "बिल भेजें")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            CustomerCard(
                customer = customer,
                outstandingAmount = outstandingAmount,
                onClick = { onEditClick(customer) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (outstandingAmount > 0.0) {
                Button(
                    onClick = { onSendReminder(customer) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("WhatsApp पर याद दिलाएं")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (ledger.isNotEmpty()) {
                OutlinedButton(
                    onClick = { onRepeatOrder(ledger) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Repeat, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("पिछला बिल दोबारा दर्ज करें")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = "खरीद इतिहास (History)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (ledger.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "अभी तक कोई खरीद दर्ज नहीं हुई।",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ledger, key = { it.id }) { tx ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "${tx.itemName} — ${tx.quantity}",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = dateFormat.format(Date(tx.timestamp)),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Text(
                                    text = "₹${tx.total.toInt()}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
