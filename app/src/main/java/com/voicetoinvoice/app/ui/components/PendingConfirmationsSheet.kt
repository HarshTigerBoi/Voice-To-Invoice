package com.voicetoinvoice.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
import com.voicetoinvoice.app.utils.AudioFileManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingConfirmationsSheet(
    pendingJobs: List<SttJobRecord>,
    onConfirmJob: (SttJobRecord) -> Unit,
    onDiscardJob: (SttJobRecord) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val audioFileManager = remember { AudioFileManager(context) }
    var playingJobId by remember { mutableStateOf<String?>(null) }
    var editingJob by remember { mutableStateOf<SttJobRecord?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            audioFileManager.stopAudio()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            audioFileManager.stopAudio()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Pending Voice Sales (${pendingJobs.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (pendingJobs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No pending sales to confirm", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(pendingJobs, key = { it.id }) { job ->
                        val isPlaying = playingJobId == job.id

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (job.isSanityFlagged) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = job.parsedItemName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp
                                        )
                                        Text(
                                            text = "${job.parsedQty} ${job.parsedUnit} • Spoken: \"${job.rawTranscript}\"",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Text(
                                        text = "₹${job.parsedTotal}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                if (job.isSanityFlagged) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "⚠️ Quantity is high. Please verify before confirming.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    // Playback Button
                                    OutlinedButton(
                                        onClick = {
                                            if (isPlaying) {
                                                audioFileManager.stopAudio()
                                                playingJobId = null
                                            } else {
                                                playingJobId = job.id
                                                audioFileManager.playAudio(job.audioFilePath) {
                                                    playingJobId = null
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play Audio",
                                            tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isPlaying) "Playing..." else "Audio", fontSize = 12.sp)
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    // Edit Button
                                    OutlinedButton(
                                        onClick = { editingJob = job },
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Edit", fontSize = 12.sp)
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    IconButton(
                                        onClick = { onDiscardJob(job) },
                                        colors = IconButtonDefaults.iconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer
                                        ),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Discard", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    val isConfirmable = job.parsedTotal > 0.0 && job.parsedItemName.isNotBlank() && job.parsedItemName != "Unrecognized Item"

                                    Button(
                                        onClick = {
                                            if (isConfirmable) {
                                                onConfirmJob(job)
                                            } else {
                                                editingJob = job
                                            }
                                        },
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isConfirmable) "Confirm" else "Set Price")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Inline Edit Pending Job Dialog
    editingJob?.let { job ->
        var editName by remember { mutableStateOf(job.parsedItemName) }
        var editQty by remember { mutableStateOf(job.parsedQty.toString()) }
        var editUnit by remember { mutableStateOf(job.parsedUnit) }
        var editTotal by remember { mutableStateOf(job.parsedTotal.toString()) }

        AlertDialog(
            onDismissRequest = { editingJob = null },
            title = { Text("Edit Pending Sale") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Item Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editQty,
                            onValueChange = { editQty = it },
                            label = { Text("Quantity") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editUnit,
                            onValueChange = { editUnit = it },
                            label = { Text("Unit") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = editTotal,
                        onValueChange = { editTotal = it },
                        label = { Text("Total Price (₹)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val qtyVal = editQty.toDoubleOrNull() ?: job.parsedQty
                        val totalVal = editTotal.toDoubleOrNull() ?: job.parsedTotal
                        val updatedJob = job.copy(
                            parsedItemName = editName,
                            parsedQty = qtyVal,
                            parsedUnit = editUnit,
                            parsedTotal = totalVal,
                            isSanityFlagged = false
                        )
                        editingJob = null
                        onConfirmJob(updatedJob)
                    }
                ) {
                    Text("Save & Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingJob = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
