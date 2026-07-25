package com.voicetoinvoice.app.utils

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import java.io.File

class AudioFileManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null

    fun getRecordingsDir(): File {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun playAudio(filePath: String, onCompletion: () -> Unit = {}) {
        stopAudio()
        try {
            val file = File(filePath)
            if (!file.exists()) return

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                prepare()
                setOnCompletionListener {
                    stopAudio()
                    onCompletion()
                }
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopAudio()
        }
    }

    fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaPlayer = null
        }
    }

    fun pruneOldRecordings(maxAgeDays: Int = 30) {
        val dir = getRecordingsDir()
        val cutoff = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
