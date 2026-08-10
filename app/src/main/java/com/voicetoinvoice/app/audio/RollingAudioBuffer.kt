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
    @Volatile private var stoppedAtMs: Long = 0L

    /**
     * One contiguous run of captured PCM. The wall-clock -> byte-offset mapping is valid
     * ONLY inside a segment; between two segments the microphone was off and the ring still
     * holds older audio at those positions. Every previous "wrong time audio" bug came from
     * a single global anchor extrapolating straight through such a hole.
     */
    private class CaptureSegment(val startWallMs: Long, val startByteOffset: Long) {
        @Volatile var endWallMs: Long = startWallMs
        @Volatile var endByteOffset: Long = startByteOffset
    }

    /** Guarded by `ringBuffer`. Newest last. */
    private val segments = ArrayList<CaptureSegment>()
    /** Guarded by `ringBuffer`. Non-null only while the capture thread is writing. */
    private var currentSegment: CaptureSegment? = null
    /** Incremented by startRollingBuffer() only -- a cold start invalidates all timestamps. */
    @Volatile private var captureEpoch: Int = 0

    fun getCaptureEpoch(): Int = captureEpoch

    /** While true the ring buffer keeps advancing but stores silence, so TTS playback
     *  never lands in a window the next PTT press extracts. */
    fun setSuppressed(suppressed: Boolean) {
        isSuppressed.set(suppressed)
        suppressedAtMs = if (suppressed) System.currentTimeMillis() else 0L
    }

    fun getRecordingStartedAtMs(): Long = recordingStartedAtMs
    fun getBufferDurationSeconds(): Int = bufferDurationSeconds

    private fun appendChunk(chunk: ByteArray, bytesRead: Int) {
        val now = System.currentTimeMillis()
        val chunkDurationMs = bytesRead * 1000L / bytesPerSecond
        synchronized(ringBuffer) {
            var seg = currentSegment
            if (seg == null) {
                // The chunk we are about to store was captured over the PRECEDING
                // chunkDurationMs, so the segment starts before `now`.
                seg = CaptureSegment(now - chunkDurationMs, totalBytesWritten)
                segments.add(seg)
                currentSegment = seg
            }
            for (i in 0 until bytesRead) {
                ringBuffer[writeHead] = chunk[i]
                writeHead = (writeHead + 1) % bufferCapacity
            }
            totalBytesWritten += bytesRead
            seg.endWallMs = now
            seg.endByteOffset = totalBytesWritten
            lastWriteAtMs = now
            // Drop segments whose bytes have all been overwritten in the ring.
            val oldest = totalBytesWritten - bufferCapacity
            while (segments.size > 1 && segments[0].endByteOffset <= oldest) segments.removeAt(0)
        }
    }

    private fun closeCurrentSegment() {
        synchronized(ringBuffer) { currentSegment = null }
    }

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
        stoppedAtMs = 0L
        synchronized(ringBuffer) {
            totalBytesWritten = 0L
            writeHead = 0
            lastWriteAtMs = 0L
            segments.clear()
            currentSegment = null
            captureEpoch++
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
                        appendChunk(chunk, bytesRead)
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
                closeCurrentSegment()
                isRecordingRunning.set(false)
            }
        }
        recordingThread?.start()
    }

    /**
     * Blocks until the capture thread has really exited and AudioRecord.release() has run.
     */
    fun stopRollingBuffer() {
        isRecordingRunning.set(false)
        val now = System.currentTimeMillis()
        stoppedAtMs = now
        val thread = recordingThread
        recordingThread = null
        try {
            thread?.join(1500L)
            if (thread?.isAlive == true) {
                Log.w("RollingAudioBuffer", "Capture thread did not exit within 1500ms; mic may still be held")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            closeCurrentSegment()
        }
    }

    /**
     * Called on every ON_START lifecycle event. Cold-starts the buffer on first launch;
     * on subsequent foregrounds (buffer was stopped by ON_STOP), resumes without destroying
     * the timing coordinate system. Returns true if cold-started, false if resumed or did nothing.
     */
    fun smartStart(): Boolean {
        if (isRecordingRunning.get()) return false
        val gapMs = if (stoppedAtMs > 0L) System.currentTimeMillis() - stoppedAtMs else Long.MAX_VALUE
        return if (totalBytesWritten == 0L || gapMs > RESUME_MAX_GAP_MS) {
            startRollingBuffer()
            true
        } else {
            resumeRollingBuffer()
            false
        }
    }

    /**
     * Resumes recording after a stopRollingBuffer() call without resetting the timing
     * coordinate system.
     */
    fun resumeRollingBuffer() {
        if (isRecordingRunning.get()) return

        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            Log.w("RollingAudioBuffer", "RECORD_AUDIO permission not granted -- cannot resume.")
            return
        }

        isRecordingRunning.set(true)

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
                    Log.e("RollingAudioBuffer", "AudioRecord not initialized on resume — falling back to cold start")
                    isRecordingRunning.set(false)
                    // Post to main thread so we don't call startRollingBuffer() from inside its own thread
                    android.os.Handler(android.os.Looper.getMainLooper()).post { startRollingBuffer() }
                    return@Thread
                }
                audioRecord.startRecording()
                val chunk = ByteArray(2048)
                while (isRecordingRunning.get()) {
                    val bytesRead = audioRecord.read(chunk, 0, chunk.size)
                    if (bytesRead > 0) {
                        if (isSuppressed.get()) {
                            if (suppressedAtMs > 0L && System.currentTimeMillis() - suppressedAtMs > MAX_SUPPRESSION_MS) {
                                setSuppressed(false)
                            } else {
                                java.util.Arrays.fill(chunk, 0, bytesRead, 0.toByte())
                            }
                        }
                        appendChunk(chunk, bytesRead)
                    }
                }
            } catch (e: Exception) {
                Log.e("RollingAudioBuffer", "Error in resumed audio recording loop", e)
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) {
                    Log.e("RollingAudioBuffer", "Error releasing AudioRecord on resume", e)
                }
                closeCurrentSegment()
                isRecordingRunning.set(false)
            }
        }
        recordingThread?.start()
    }

    sealed class ExtractionResult {
        data class Success(val file: File, val clampedToSegmentStart: Boolean, val bytes: Int) : ExtractionResult()
        data class Failure(val reason: String) : ExtractionResult()
    }

    fun extractAudioWindowDetailed(startMs: Long, endMs: Long, outputFile: File): ExtractionResult {
        return try {
            synchronized(ringBuffer) {
                if (totalBytesWritten <= 0L) return ExtractionResult.Failure("buffer_never_wrote")
                // Newest segment that overlaps the requested window. Audio for a just-released
                // press is always in the newest segment; anything older is a stale request.
                val seg = segments.lastOrNull { it.startWallMs < endMs && it.endWallMs > startMs }
                    ?: return ExtractionResult.Failure("no_segment_overlaps_window")

                when (val r = resolveSegmentWindowBytes(
                    startMs, endMs,
                    seg.startWallMs, seg.startByteOffset,
                    seg.endWallMs, seg.endByteOffset,
                    totalBytesWritten, bufferCapacity, bytesPerSecond
                )) {
                    is WindowResolution.Failed -> ExtractionResult.Failure(r.reason)
                    is WindowResolution.Ok -> {
                        val n = (r.endByte - r.startByte).toInt()
                        val out = ByteArray(n)
                        val startRingIndex = (r.startByte % bufferCapacity).toInt()
                        for (i in 0 until n) out[i] = ringBuffer[(startRingIndex + i) % bufferCapacity]
                        AudioWavWriter.writePcmToWav(out, outputFile, sampleRate = sampleRate)
                        ExtractionResult.Success(outputFile, r.clampedToSegmentStart, n)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RollingAudioBuffer", "extractAudioWindowDetailed failed", e)
            ExtractionResult.Failure("exception_${e.javaClass.simpleName}")
        }
    }

    fun extractAudioWindow(startMs: Long, endMs: Long, outputFile: File): File? =
        (extractAudioWindowDetailed(startMs, endMs, outputFile) as? ExtractionResult.Success)?.file

    sealed class WindowResolution {
        data class Ok(
            val startByte: Long,
            val endByte: Long,
            val clampedToSegmentStart: Boolean
        ) : WindowResolution()
        data class Failed(val reason: String) : WindowResolution()
    }

    companion object {
        const val MIN_WINDOW_BYTES = 9600
        const val MAX_SUPPRESSION_MS = 20_000L
        const val RESUME_MAX_GAP_MS = 10_000L

        @Volatile
        private var instance: RollingAudioBuffer? = null

        fun setSharedInstance(buffer: RollingAudioBuffer) {
            instance = buffer
        }

        fun getSharedInstance(context: Context): RollingAudioBuffer {
            return instance ?: synchronized(this) {
                instance ?: RollingAudioBuffer(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Maps [startMs, endMs] onto absolute byte offsets INSIDE one capture segment.
         * `segEndByteOffset` is the byte offset that corresponds to wall-clock `segEndWallMs`.
         * Never extrapolates outside the segment: audio that was not captured cannot be returned.
         */
        fun resolveSegmentWindowBytes(
            startMs: Long, endMs: Long,
            segStartWallMs: Long, segStartByteOffset: Long,
            segEndWallMs: Long, segEndByteOffset: Long,
            totalWritten: Long, bufferCapacity: Int, bytesPerSecond: Int
        ): WindowResolution {
            if (endMs <= startMs) return WindowResolution.Failed("end_before_start")
            if (segEndByteOffset <= segStartByteOffset) return WindowResolution.Failed("empty_segment")
            if (endMs <= segStartWallMs) return WindowResolution.Failed("window_before_segment")
            if (startMs >= segEndWallMs) return WindowResolution.Failed("window_after_segment")

            val clampedToSegmentStart = startMs < segStartWallMs
            val effStart = Math.max(startMs, segStartWallMs)
            val effEnd = Math.min(endMs, segEndWallMs)

            fun byteFor(t: Long): Long =
                segEndByteOffset - (segEndWallMs - t) * bytesPerSecond.toLong() / 1000L

            var startByte = byteFor(effStart).coerceIn(segStartByteOffset, segEndByteOffset)
            val endByte = byteFor(effEnd).coerceIn(startByte, segEndByteOffset)

            val oldestAvailable = Math.max(0L, totalWritten - bufferCapacity)
            if (endByte <= oldestAvailable) return WindowResolution.Failed("window_overwritten")
            if (startByte < oldestAvailable) startByte = oldestAvailable

            if (endByte - startByte < MIN_WINDOW_BYTES) {
                return WindowResolution.Failed("window_too_small_${endByte - startByte}")
            }
            return WindowResolution.Ok(startByte, endByte, clampedToSegmentStart)
        }
    }
}
