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
    @Volatile private var suppressedAtMs: Long = 0L
    @Volatile private var recordingStartedAtMs: Long = 0L
    @Volatile private var totalBytesWritten: Long = 0L
    @Volatile private var lastWriteAtMs: Long = 0L

    /** While true the ring buffer keeps advancing but stores silence, so TTS playback
     *  never lands in a window the next PTT press extracts. */
    fun setSuppressed(suppressed: Boolean) {
        isSuppressed.set(suppressed)
        suppressedAtMs = if (suppressed) System.currentTimeMillis() else 0L
    }

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
        synchronized(ringBuffer) {
            totalBytesWritten = 0L
            writeHead = 0
            lastWriteAtMs = 0L
            java.util.Arrays.fill(ringBuffer, 0.toByte())
        }
        isSuppressed.set(false)

        recordingThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            var audioRecord: AudioRecord? = null
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    Math.max(minBufferSize, 4096)
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("RollingAudioBuffer", "AudioRecord state not initialized")
                    isRecordingRunning.set(false)
                    return@Thread
                }

                audioRecord.startRecording()
                val chunk = ByteArray(2048)

                while (isRecordingRunning.get()) {
                    val bytesRead = audioRecord.read(chunk, 0, chunk.size)
                    if (bytesRead > 0) {
                        if (isSuppressed.get()) {
                            if (suppressedAtMs > 0L && System.currentTimeMillis() - suppressedAtMs > MAX_SUPPRESSION_MS) {
                                Log.w("RollingAudioBuffer", "Suppression exceeded ${MAX_SUPPRESSION_MS}ms; force-clearing")
                                setSuppressed(false)
                            } else {
                                java.util.Arrays.fill(chunk, 0, bytesRead, 0.toByte())
                            }
                        }
                        synchronized(ringBuffer) {
                            for (i in 0 until bytesRead) {
                                ringBuffer[writeHead] = chunk[i]
                                writeHead = (writeHead + 1) % bufferCapacity
                            }
                            totalBytesWritten += bytesRead
                            lastWriteAtMs = System.currentTimeMillis()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("RollingAudioBuffer", "Error in audio recording loop", e)
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) {
                    Log.e("RollingAudioBuffer", "Error releasing AudioRecord", e)
                }
                isRecordingRunning.set(false)
            }
        }
        recordingThread?.start()
    }

    /**
     * Blocks until the capture thread has really exited and AudioRecord.release() has run.
     * Without the join, this returned while AudioRecord still held the microphone, so a
     * SpeechRecognizer started immediately afterwards failed to open it -- BUG-B, the cause of
     * "the assistant records nothing". The timeout is a safety valve: a wedged capture thread
     * must not freeze the UI thread.
     */
    fun stopRollingBuffer() {
        isRecordingRunning.set(false)
        val thread = recordingThread
        recordingThread = null
        try {
            thread?.join(500L)
            if (thread?.isAlive == true) {
                Log.w("RollingAudioBuffer", "Capture thread did not exit within 500ms; mic may still be held")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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

                val anchor = if (lastWriteAtMs > 0L) lastWriteAtMs else System.currentTimeMillis()
                val byteRange = resolveWindowBytes(
                    startMs = effectiveStartMs,
                    endMs = endMs,
                    anchorMs = anchor,
                    totalWritten = totalWritten,
                    bufferCapacity = bufferCapacity,
                    bytesPerSecond = bytesPerSecond
                ) ?: run {
                    Log.w("RollingAudioBuffer", "Window resolution returned null for [$effectiveStartMs, $endMs]")
                    return null
                }

                val actualBytesToExtract = byteRange.last - byteRange.first + 1
                val extractedBytes = ByteArray(actualBytesToExtract)
                val startRingIndex = (byteRange.first.toLong() % bufferCapacity).toInt()

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
        const val MIN_WINDOW_BYTES = 9600
        const val MAX_SUPPRESSION_MS = 20_000L

        @Volatile
        private var instance: RollingAudioBuffer? = null

        fun getSharedInstance(context: Context): RollingAudioBuffer {
            return instance ?: synchronized(this) {
                instance ?: RollingAudioBuffer(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Pure form of the window->byte-range mapping, factored out so it is unit-testable without a
         * microphone. `anchorMs` is the wall-clock time corresponding to byte offset `totalWritten`.
         * Returns null when the requested window is unrecoverable (already overwritten, or shorter
         * than [MIN_WINDOW_BYTES] after clamping).
         */
        fun resolveWindowBytes(
            startMs: Long, endMs: Long, anchorMs: Long,
            totalWritten: Long, bufferCapacity: Int, bytesPerSecond: Int
        ): IntRange? {
            if (totalWritten <= 0L || endMs <= startMs) return null

            fun byteOffsetFor(tsMs: Long): Long =
                totalWritten - (anchorMs - tsMs) * bytesPerSecond.toLong() / 1000L

            val startByteOffset = Math.max(0L, byteOffsetFor(startMs))
            val endByteOffset = Math.max(startByteOffset, byteOffsetFor(endMs))
            val minStartAllowed = Math.max(0L, totalWritten - bufferCapacity)

            if (startByteOffset < minStartAllowed) return null

            val actualEndOffset = Math.min(endByteOffset, totalWritten)
            val actualBytesToExtract = (actualEndOffset - startByteOffset).toInt()

            if (actualBytesToExtract < MIN_WINDOW_BYTES) return null

            val startIdx = startByteOffset.toInt()
            val endIdx = startIdx + actualBytesToExtract - 1
            return startIdx..endIdx
        }
    }
}
