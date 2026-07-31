package com.voicetoinvoice.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetoinvoice.app.data.local.entity.CaptureIntent
import com.voicetoinvoice.app.data.local.entity.SttJobRecord
import com.voicetoinvoice.app.data.local.entity.SttJobStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Every spoken command from the last 24h, with a clear status per row -- the "clear status for
 * every command (Processing / Success / Failed)" item from the feature brief, and the visible
 * half of `CommitSequencer`'s ordering guarantee (the app can now show, not just enforce, that
 * commands land in the order they were spoken).
 *
 * Deliberately additive rather than a replacement for [PendingConfirmationsSheet]: that sheet's
 * item-resolution UI (picking/creating a catalog match, editing a wrongly-heard quantity) is
 * substantial, tested, working code. Reviewing a line here hands off to it rather than
 * duplicating it -- one row here, [onReviewJob], opens the existing sheet.
 */
import androidx.compose.material.icons.filled.Share

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandFeedSheet(
    jobs: List<SttJobRecord>,
    onDismiss: () -> Unit,
    onReviewJob: (SttJobRecord) -> Unit,
    onRetryJob: (SttJobRecord) -> Unit,
    onSendBill: (SttJobRecord) -> Unit = {},
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "हाल की गतिविधि (Recent Activity)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (jobs.isEmpty()) {
                Text(
                    "अभी कोई गतिविधि नहीं",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 480.dp)
                ) {
                    items(jobs, key = { it.id }) { job ->
                        CommandFeedRow(job, onReviewJob, onRetryJob, onSendBill)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CommandFeedRow(
    job: SttJobRecord,
    onReviewJob: (SttJobRecord) -> Unit,
    onRetryJob: (SttJobRecord) -> Unit,
    onSendBill: (SttJobRecord) -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val audioExists = remember(job.id) { job.audioFilePath.isNotBlank() && File(job.audioFilePath).exists() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = job.status in REVIEWABLE_STATUSES) { onReviewJob(job) }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIcon(job.status)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    describeJob(job),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Row {
                    Text(
                        timeFmt.format(java.util.Date(job.recordedAtMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (job.captureIntent != CaptureIntent.ASSISTANT) {
                        Text(
                            " · ${job.captureIntent.hindiLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            val isCommittedSale = job.status in setOf(SttJobStatus.AUTO_CONFIRMED, SttJobStatus.CONFIRMED, SttJobStatus.PARTIALLY_CONFIRMED) &&
                    job.captureIntent !in setOf(CaptureIntent.STOCK_IN, CaptureIntent.WASTE)
            if (isCommittedSale) {
                TextButton(onClick = { onSendBill(job) }) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("बिल भेजें", fontSize = 12.sp)
                }
            }
            if (job.status == SttJobStatus.FAILED && audioExists) {
                TextButton(onClick = { onRetryJob(job) }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("फिर कोशिश करें", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: SttJobStatus) {
    when (status) {
        SttJobStatus.QUEUED, SttJobStatus.TRANSCRIBING -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp
        )
        SttJobStatus.AUTO_CONFIRMED, SttJobStatus.RATE_UPDATED, SttJobStatus.CONFIRMED -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = "Confirmed",
            tint = Color(0xFF2E7D32)
        )
        SttJobStatus.PARTIALLY_CONFIRMED -> Icon(
            Icons.Default.Warning,
            contentDescription = "Partially confirmed",
            tint = Color(0xFFF9A825)
        )
        SttJobStatus.PARSED -> Icon(
            Icons.Default.HelpOutline,
            contentDescription = "Needs review",
            tint = MaterialTheme.colorScheme.primary
        )
        SttJobStatus.FAILED -> Icon(
            Icons.Default.Error,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error
        )
    }
}

private val REVIEWABLE_STATUSES = setOf(SttJobStatus.PARSED, SttJobStatus.PARTIALLY_CONFIRMED)

/** One-line summary of what a job did (or is doing), in Hindi, matching the phrasing the
 *  assistant would have spoken. */
private fun describeJob(job: SttJobRecord): String = when (job.status) {
    SttJobStatus.QUEUED -> "प्रोसेस होने का इंतज़ार..."
    SttJobStatus.TRANSCRIBING -> if (job.onDeviceTranscript.isNotBlank()) {
        "\"${job.onDeviceTranscript}\" — प्रोसेस हो रहा है..."
    } else "प्रोसेस हो रहा है..."

    SttJobStatus.AUTO_CONFIRMED, SttJobStatus.CONFIRMED -> when {
        job.assistantAnswer.isNotBlank() -> job.assistantAnswer
        job.parsedItemName.isNotBlank() && job.parsedItemName != "Unrecognized Item" -> {
            val qty = fmtQty(job.parsedQty)
            "$qty ${job.parsedUnit} ${job.parsedItemName} — ₹${job.parsedTotal.toInt()}"
        }
        else -> "दर्ज हो गया"
    }

    SttJobStatus.RATE_UPDATED -> job.assistantAnswer.ifBlank {
        "${job.parsedItemName} का रेट अपडेट हुआ"
    }

    SttJobStatus.PARTIALLY_CONFIRMED -> {
        val lines = parsePendingLines(job)
        val committed = lines.count { it.committed }
        "${lines.size} में से $committed लाइन दर्ज — बाकी की समीक्षा करें"
    }

    SttJobStatus.PARSED -> if (job.isSanityFlagged) "समीक्षा चाहिए — टैप करें" else "प्रोसेस हो गया"

    SttJobStatus.FAILED -> job.errorMessage.ifBlank { "यह कमांड पूरी नहीं हो सकी" }
}

private fun fmtQty(q: Double): String =
    if (q % 1.0 == 0.0) q.toInt().toString() else q.toString()
