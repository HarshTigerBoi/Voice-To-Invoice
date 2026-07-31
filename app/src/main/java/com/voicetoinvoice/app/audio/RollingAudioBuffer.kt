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
    private val bufferDurationSeconds = 120
    private val bufferCapacity = bytesPerSecond * bufferDurationSeconds // 3,840,000 bytes (~3.84 MB RAM)

    private val ringBuffer = ByteArray(bufferCapacity)
    private var writeHead = 0
    private var isRecordingRunning = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private val isSuppressed = AtomicBoolean(false)
    @Volatile private var recordingStartedAtMs: Long = 0L
    @Volatile private var totalBytesWritten: Long = 0L

    /** While true the ring buffer keeps advancing but stores silence, so TTS playback
     *  never lands in a window the next PTT press extracts. */
    fun setSuppressed(suppressed: Boolean) { isSuppressed.set(suppressed) }

    fun getRecordingStartedAtMs(): Long = recordingStartedAtMs
    fun getBufferDurationSeconds(): Int = bufferDurationSeconds

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
                        if (isSuppressed.get()) {
                            java.util.Arrays.fill(chunk, 0, bytesRead, 0.toByte())
                        }
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
     * writing a valid WAV file. Absolute-time addressing prevents scheduled drift and
     * floorStartMs prevents silent backward expansion into preceding recordings.
     */
    fun extractAudioWindow(startMs: Long, endMs: Long, outputFile: File, floorStartMs: Long = startMs): File? {
        return try {
            val recStarted = recordingStartedAtMs
            if (recStarted <= 0L) {
                Log.w("RollingAudioBuffer", "Buffer recording has not started yet.")
                return null
            }

            val effectiveStartMs = Math.max(startMs, floorStartMs)
            if (endMs <= effectiveStartMs) {
                Log.w("RollingAudioBuffer", "endMs ($endMs) <= effectiveStartMs ($effectiveStartMs)")
                return null
            }

            synchronized(ringBuffer) {
                val totalWritten = totalBytesWritten
                if (totalWritten <= 0) {
                    Log.w("RollingAudioBuffer", "No valid PCM audio written to buffer yet.")
                    return null
                }

                // Absolute byte offsets since recordingStartedAtMs
                val startByteOffset = Math.max(0L, (effectiveStartMs - recStarted) * bytesPerSecond.toLong() / 1000L)
                val endByteOffset = Math.max(startByteOffset, (endMs - recStarted) * bytesPerSecond.toLong() / 1000L)
                val requestedBytes = (endByteOffset - startByteOffset).toInt()

                val minStartAllowed = Math.max(0L, totalWritten - bufferCapacity)
                if (startByteOffset < minStartAllowed) {
                    Log.w("RollingAudioBuffer", "Requested start time was overwritten in ring buffer (start: $startByteOffset, minAllowed: $minStartAllowed)")
                    return null
                }

                val actualEndOffset = Math.min(endByteOffset, totalWritten)
                val actualBytesToExtract = (actualEndOffset - startByteOffset).toInt()

                // Minimum ~300ms window threshold (9600 bytes at 32,000 bytes/sec)
                if (actualBytesToExtract < 9600) {
                    Log.w("RollingAudioBuffer", "Audio window too short after clamping ($actualBytesToExtract bytes < 9600)")
                    return null
                }

                val extractedBytes = ByteArray(actualBytesToExtract)
                val startRingIndex = (startByteOffset % bufferCapacity).toInt()

                for (i in 0 until actualBytesToExtract) {
                    extractedBytes[i] = ringBuffer[(startRingIndex + i) % bufferCapacity]
                }

                AudioWavWriter.writePcmToWav(extractedBytes, outputFile, sampleRate = sampleRate)
                outputFile
            }
        } catch (e: Exception) {
            Log.e("RollingAudioBuffer", "Failed to extract audio window safely", e)
            null
        }
    }

    companion object {
        @Volatile
        private var instance: RollingAudioBuffer? = null

        fun getSharedInstance(context: Context): RollingAudioBuffer {
            return instance ?: synchronized(this) {
                instance ?: RollingAudioBuffer(context.applicationContext).also { instance = it }
            }
        }
    }
}
