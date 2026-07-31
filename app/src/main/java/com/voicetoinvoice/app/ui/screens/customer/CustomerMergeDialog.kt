package com.voicetoinvoice.app.ui.screens.customer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetoinvoice.app.data.local.entity.CustomerRecord

@Composable
fun CustomerMergeDialog(
    pair: Pair<CustomerRecord, CustomerRecord>,
    onConfirmMerge: () -> Unit,
    onDismiss: () -> Unit
) {
    val (c1, c2) = pair

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "ये दोनों एक ही हैं? (Merge Accounts)",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "दोनों खातों का बकाया (Udhaar) और लेन-देन एक साथ जुड़ जाएगा। कम वाला कोड (#${minOf(c1.code, c2.code)}) रखा जाएगा।",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                CustomerCard(customer = c1)
                Text(
                    text = "+",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                CustomerCard(customer = c2)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmMerge,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("हाइ, दोनों एक ही हैं (Merge)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("अलग रहने दें")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}
