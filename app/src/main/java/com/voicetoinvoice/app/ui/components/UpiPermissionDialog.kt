package com.voicetoinvoice.app.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun UpiPermissionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable Auto UPI Detection") },
        text = {
            Text(
                "Voice Shop Ledger uses Notification Access ONLY to detect incoming UPI payment alerts from Paytm, PhonePe, and Google Pay.\n\n" +
                "• What is read: Payment amounts (e.g. ₹50 received)\n" +
                "• Privacy Guarantee: Non-payment notifications are discarded immediately and never stored or sent anywhere."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Open Settings & Allow")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now")
            }
        }
    )
}
