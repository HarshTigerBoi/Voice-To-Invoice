package com.voicetoinvoice.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class RollingAudioBuffer(private val context: Context) {

    private val sampleRate = 16000
    private val bytesPerSecond = sampleRate * 2 // 16-bit Mono = 32,000 bytes/sec
    private val bufferDurationSeconds = 30
    private val bufferCapacity = bytesPerSecond * bufferDurationSeconds // 960,000 bytes (~960 KB RAM)

    private val ringBuffer = ByteArray(bufferCapacity)
    private var writeHead = 0
    private var isRecordingRunning = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    @Volatile private var recordingStartedAtMs: Long = 0L
    @Volatile private var totalBytesWritten: Long = 0L

    fun getRecordingStartedAtMs(): Long = recordingStartedAtMs

    fun startRollingBuffer() {
        if (isRecordingRunning.get()) return

        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!micGranted) {
            Log.w("RollingAudioBuffer", "RECORD_AUDIO permission not granted.")
            return
        }

        isRecordingRunning.set(true)
        recordingStartedAtMs = System.currentTimeMillis()
        totalBytesWritten = 0L

        recordingThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    Math.max(minBufferSize, 4096)
                )
            } catch (e: Exception) {
                Log.e("RollingAudioBuffer", "Failed to create AudioRecord", e)
                isRecordingRunning.set(false)
                return@Thread
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("RollingAudioBuffer", "AudioRecord state not initialized")
                isRecordingRunning.set(false)
                return@Thread
            }

            try {
                audioRecord.startRecording()
                val chunk = ByteArray(2048)

                while (isRecordingRunning.get()) {
                    val bytesRead = audioRecord.read(chunk, 0, chunk.size)
                    if (bytesRead > 0) {
                        synchronized(ringBuffer) {
                            for (i in 0 until bytesRead) {
                                ringBuffer[writeHead] = chunk[i]
                                writeHead = (writeHead + 1) % bufferCapacity
                            }
                            totalBytesWritten += bytesRead
                        }
                    }
                }
                audioRecord.stop()
                audioRecord.release()
            } catch (e: Exception) {
                Log.e("RollingAudioBuffer", "Error in audio recording loop", e)
            } finally {
                isRecordingRunning.set(false)
            }
        }
        recordingThread?.start()
    }

    fun stopRollingBuffer() {
        isRecordingRunning.set(false)
        recordingThread?.interrupt()
        recordingThread = null
    }

    /**
     * Extracts exact audio window between startMs and endMs from circular ring buffer,
     * writing a valid WAV file. Safely guarded against reading unwritten buffer regions.
     */
    fun extractAudioWindow(startMs: Long, endMs: Long, outputFile: File): File? {
        return try {
            val durationMs = Math.max(endMs - startMs, 500L)
            var extractedBytes: ByteArray

            synchronized(ringBuffer) {
                val availableBytes = Math.min(totalBytesWritten, bufferCapacity.toLong()).toInt()
                if (availableBytes <= 0) {
                    Log.w("RollingAudioBuffer", "No valid PCM audio recorded in ring buffer yet.")
                    return null
                }

                val now = System.currentTimeMillis()
                val bytesBack = (Math.max(now - endMs, 0L) * bytesPerSecond / 1000L).toInt()
                val clampedBytesBack = Math.min(bytesBack, Math.max(availableBytes - 16000, 0))

                val requestedBytes = (durationMs * bytesPerSecond / 1000L).toInt()
                val actualBytesToExtract = Math.min(Math.max(requestedBytes, 16000), availableBytes - clampedBytesBack)

                if (actualBytesToExtract <= 0) {
                    Log.w("RollingAudioBuffer", "actualBytesToExtract <= 0 (availableBytes: $availableBytes)")
                    return null
                }

                extractedBytes = ByteArray(actualBytesToExtract)
                var readStart = (writeHead - clampedBytesBack - actualBytesToExtract) % bufferCapacity
                if (readStart < 0) readStart += bufferCapacity

                for (i in 0 until actualBytesToExtract) {
                    extractedBytes[i] = ringBuffer[(readStart + i) % bufferCapacity]
                }
            }

            AudioWavWriter.writePcmToWav(extractedBytes, outputFile, sampleRate = sampleRate)
            outputFile
        } catch (e: Exception) {
            Log.e("RollingAudioBuffer", "Failed to extract audio window safely", e)
            null
        }
    }
}
