package com.voicetoinvoice.app.util

import android.content.Context
import android.util.Log
import java.io.File

object LocalStorageCleaner {

    private const val MAX_STORAGE_BYTES = 500L * 1024L * 1024L // 500 MB Limit requested by user
    private const val TARGET_CLEANED_BYTES = 350L * 1024L * 1024L // Clean down to 350 MB to leave buffer

    /**
     * Inspects local app cache directory and deletes the oldest .wav audio recordings
     * if the total storage exceeds 500 MB.
     */
    fun cleanOldRecordingsIfExceedsLimit(context: Context) {
        try {
            val cacheDir = context.cacheDir
            val audioFiles = cacheDir.listFiles { file ->
                file.isFile && (file.extension.equals("wav", ignoreCase = true) || file.name.contains("voice_record_") || file.name.contains("adaptive_expand_"))
            } ?: return

            var totalBytes = audioFiles.sumOf { it.length() }
            val totalMb = totalBytes / (1024L * 1024L)

            Log.d("LocalStorageCleaner", "Current audio recordings storage usage: $totalMb MB / 500 MB limit (${audioFiles.size} files)")

            if (totalBytes > MAX_STORAGE_BYTES) {
                Log.w("LocalStorageCleaner", "Storage threshold exceeded ($totalMb MB > 500 MB). Starting automatic cleanup of oldest recordings...")

                // Sort files oldest to newest
                val sortedFiles = audioFiles.sortedBy { it.lastModified() }
                var bytesDeleted = 0L
                var filesDeletedCount = 0

                for (file in sortedFiles) {
                    val fileSize = file.length()
                    if (file.delete()) {
                        totalBytes -= fileSize
                        bytesDeleted += fileSize
                        filesDeletedCount++
                    }

                    if (totalBytes <= TARGET_CLEANED_BYTES) {
                        break
                    }
                }

                val deletedMb = bytesDeleted / (1024L * 1024L)
                val remainingMb = totalBytes / (1024L * 1024L)
                Log.i("LocalStorageCleaner", "Cleanup complete: Deleted $filesDeletedCount files ($deletedMb MB). Remaining storage: $remainingMb MB.")
            }
        } catch (e: Exception) {
            Log.e("LocalStorageCleaner", "Failed to run audio storage cleanup", e)
        }
    }
}
