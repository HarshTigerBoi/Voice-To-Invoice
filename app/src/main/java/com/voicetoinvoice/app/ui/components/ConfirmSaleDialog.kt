package com.voicetoinvoice.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.domain.parser.ParsedVoiceSale
import kotlinx.coroutines.delay

@Composable
fun ConfirmSaleDialog(
    parsedSale: ParsedVoiceSale,
    onConfirm: (ParsedVoiceSale) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    var timerSeconds by remember { mutableIntStateOf(2) }

    LaunchedEffect(Unit) {
        if (parsedSale.confidence >= 0.70f) {
            while (timerSeconds > 0) {
                delay(1000L)
                timerSeconds--
            }
            onConfirm(parsedSale)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Voice Sale") },
        text = {
            Column {
                Text("Spoken: \"${parsedSale.rawTranscript}\"", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val displayName = parsedSale.matchedItem?.name ?: parsedSale.rawTranscript
                    ItemIcon(
                        itemName = displayName,
                        imageUrl = parsedSale.matchedItem?.imageUrl,
                        imagePath = parsedSale.matchedItem?.imagePath,
                        size = 44.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "$displayName x ${parsedSale.quantity} ${parsedSale.unit}",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Total: ₹${parsedSale.estimatedTotal.toInt()}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (parsedSale.confidence >= 0.70f) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Auto-confirming in ${timerSeconds}s...", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(parsedSale) }) {
                Text("✅ Confirm")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onEdit) {
                Text("✏️ Edit")
            }
        }
    )
}
