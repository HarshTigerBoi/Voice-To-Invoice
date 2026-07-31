package com.voicetoinvoice.app.ui.components

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
    PttMicButton(
        intent = CaptureIntent.ASSISTANT,
        label = "बिल वाले",
        size = 64.dp,
        containerColor = Color(0xFF673AB7), // Deep Purple
        db = db,
        rollingAudioBuffer = rollingAudioBuffer,
        audioRecorder = audioRecorder,
        pttBurstCoalescer = pttBurstCoalescer,
        pttWindowLedger = pttWindowLedger,
        onDeviceRecognizer = onDeviceRecognizer,
        backgroundProcessor = backgroundProcessor,
        permissionLauncher = permissionLauncher,
        modifier = modifier
    )
}
