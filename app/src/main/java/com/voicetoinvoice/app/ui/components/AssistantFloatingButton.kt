package com.voicetoinvoice.app.ui.components

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetoinvoice.app.audio.AudioRecorder
import com.voicetoinvoice.app.audio.OnDeviceSpeechRecognizer
import com.voicetoinvoice.app.audio.PttBurstCoalescer
import com.voicetoinvoice.app.audio.PttWindowLedger
import com.voicetoinvoice.app.audio.RollingAudioBuffer
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CaptureIntent
import com.voicetoinvoice.app.domain.processor.BackgroundSttProcessor

@Composable
fun AssistantFloatingButton(
    db: AppDatabase,
    rollingAudioBuffer: RollingAudioBuffer,
    audioRecorder: AudioRecorder,
    pttBurstCoalescer: PttBurstCoalescer,
    pttWindowLedger: PttWindowLedger,
    onDeviceRecognizer: OnDeviceSpeechRecognizer,
    backgroundProcessor: BackgroundSttProcessor,
    permissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 12.dp,
        color = Color.Transparent,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF6366F1))))
                .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(28.dp))
                .padding(end = 14.dp, top = 2.dp, bottom = 2.dp)
        ) {
            PttMicButton(
                intent = CaptureIntent.ASSISTANT,
                label = "बिल वाले",
                size = 48.dp,
                containerColor = Color.Transparent,
                showLabelBelow = false,
                db = db,
                rollingAudioBuffer = rollingAudioBuffer,
                audioRecorder = audioRecorder,
                pttBurstCoalescer = pttBurstCoalescer,
                pttWindowLedger = pttWindowLedger,
                onDeviceRecognizer = onDeviceRecognizer,
                backgroundProcessor = backgroundProcessor,
                permissionLauncher = permissionLauncher
            )
            Spacer(Modifier.width(4.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = "AI Assistant",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "बिल वाले",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

