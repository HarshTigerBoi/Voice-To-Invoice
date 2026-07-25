package com.voicetoinvoice.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.io.File

class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentOutputFile: File? = null

    fun startRecording(): File? {
        stopRecording() // Clean up any existing recording session

        return try {
            val outputDir = context.cacheDir
            val outputFile = File.createTempFile("ptt_voice_", ".m4a", outputDir)
            currentOutputFile = outputFile

            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            recorder = mediaRecorder

            // Strong 70ms haptic vibration using Android API 31+ VibratorManager / Vibrator API
            triggerHapticVibration()

            outputFile
        } catch (e: Exception) {
            Log.e("AudioRecorder", "MediaRecorder prepare/start failed", e)
            recorder?.release()
            recorder = null
            currentOutputFile = null
            null
        }
    }

    fun triggerHapticVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(70)
            }
        } catch (e: Exception) {
            Log.w("AudioRecorder", "Haptic vibration failed", e)
        }
    }

    fun stopRecording(): File? {
        return try {
            recorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.w("AudioRecorder", "MediaRecorder stop failed (short recording?)", e)
                }
                release()
            }
            recorder = null
            val file = currentOutputFile
            currentOutputFile = null
            file
        } catch (e: Exception) {
            Log.e("AudioRecorder", "MediaRecorder release failed", e)
            recorder = null
            currentOutputFile = null
            null
        }
    }
}
