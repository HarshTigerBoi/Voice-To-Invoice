package com.voicetoinvoice.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Camera capture + downscale for item and customer identity photos.
 *
 * Camera output is 4-12 MB; nothing in this app renders these above ~96.dp, so every capture
 * is immediately downscaled to [MAX_DIM] and re-encoded. Storing originals would bloat the
 * app's data directory by two orders of magnitude for no visible gain.
 */
object PhotoCapture {
    private const val MAX_DIM = 512
    private const val JPEG_QUALITY = 80
    private const val DIR = "photos"

    /** Creates the parent dir and returns an empty target file. */
    fun newPhotoFile(context: Context, prefix: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, "${prefix}_${UUID.randomUUID()}.jpg")
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Downscales [file] in place so its longest edge is at most [MAX_DIM].
     * Uses inSampleSize on a bounds-only first pass so a 12 MP original is never fully
     * decoded into memory — doing so on a low-RAM shop phone is an OOM.
     */
    fun compressInPlace(file: File) {
        try {
            if (!file.exists() || file.length() == 0L) return
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return

            var sample = 1
            while (bounds.outWidth / sample > MAX_DIM * 2 || bounds.outHeight / sample > MAX_DIM * 2) {
                sample *= 2
            }
            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return

            val longest = maxOf(decoded.width, decoded.height)
            val scaled = if (longest > MAX_DIM) {
                val ratio = MAX_DIM.toFloat() / longest
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else decoded

            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        } catch (e: Exception) {
            android.util.Log.w("PhotoCapture", "compressInPlace failed for ${file.name}: ${e.message}")
        }
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }
}
