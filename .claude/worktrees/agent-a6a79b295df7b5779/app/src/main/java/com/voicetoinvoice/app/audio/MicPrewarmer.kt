package com.voicetoinvoice.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MicPrewarmer {

    suspend fun warmup(context: Context) = withContext(Dispatchers.IO) {
        try {
            val micGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!micGranted) return@withContext

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            if (minBufferSize > 0) {
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufferSize
                )

                if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.startRecording()
                    // Read a tiny buffer to initialize audio hardware pipeline
                    val buffer = ByteArray(minBufferSize)
                    audioRecord.read(buffer, 0, buffer.size)
                    audioRecord.stop()
                    audioRecord.release()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
