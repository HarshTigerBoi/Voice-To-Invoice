package com.voicetoinvoice.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class DirectAudioRecorder(private val context: Context) {

    private val sampleRate = 16000
    private var isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private val pcmStream = ByteArrayOutputStream()

    fun startRecording() {
        pcmStream.reset()
        isRecording.set(true)

        recordingThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    Math.max(minBufferSize, 4096)
                )
            } catch (e: Exception) {
                Log.e("DirectAudioRecorder", "Failed to create AudioRecord", e)
                isRecording.set(false)
                return@Thread
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("DirectAudioRecorder", "AudioRecord not initialized")
                isRecording.set(false)
                return@Thread
            }

            try {
                audioRecord.startRecording()
                val chunk = ByteArray(2048)
                while (isRecording.get()) {
                    val bytesRead = audioRecord.read(chunk, 0, chunk.size)
                    if (bytesRead > 0) {
                        synchronized(pcmStream) {
                            pcmStream.write(chunk, 0, bytesRead)
                        }
                    }
                }
                audioRecord.stop()
                audioRecord.release()
            } catch (e: Exception) {
                Log.e("DirectAudioRecorder", "Error in AudioRecord loop", e)
            } finally {
                isRecording.set(false)
            }
        }
        recordingThread?.start()
    }

    fun stopAndSaveWav(outputFile: File): File? {
        isRecording.set(false)
        try {
            recordingThread?.join(1000)
        } catch (_: Exception) {}

        val pcmData = synchronized(pcmStream) {
            pcmStream.toByteArray()
        }

        if (pcmData.isEmpty()) {
            Log.w("DirectAudioRecorder", "Recorded PCM data is empty")
            return null
        }

        AudioWavWriter.writePcmToWav(pcmData, outputFile, sampleRate = sampleRate)
        return outputFile
    }
}
