package com.voicetoinvoice.app.domain.action

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders an itemized bill to a PNG a shopkeeper can send on WhatsApp.
 *
 * An image rather than text because a text bill loses its column alignment in the WhatsApp
 * renderer on most Android fonts, and a misaligned bill reads as unprofessional to the customer
 * receiving it -- which is the entire point of sending one.
 *
 * Drawn with plain Canvas rather than a Compose screenshot: this must work from a background
 * coroutine with no Activity attached (the voice path reaches it from SttWorker), and capturing a
 * composable requires a live window.
 */
object BillBuilder {

    private const val WIDTH = 720
    private const val PADDING = 40f
    private const val LINE_HEIGHT = 44f

    /**
     * @return a content:// Uri for the rendered bill, or null when rendering failed. Callers must
     *   fall back to `ActionExecutor.sendBill` (text) rather than showing an error -- a text bill
     *   is far better than no bill.
     */
    fun render(
        context: Context,
        shopName: String,
        customerName: String?,
        lines: List<TransactionRecord>,
        previousBalance: Double? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Uri? {
        if (lines.isEmpty()) return null
        return try {
            val lineCount = lines.size
            val extraLines = if (previousBalance != null) 9 else 7
            val height = (PADDING * 2 + LINE_HEIGHT * (lineCount + extraLines)).toInt()

            val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }

            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 32f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }

            val textPaint = Paint().apply {
                color = Color.DKGRAY
                textSize = 24f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
            }

            val boldPaint = Paint().apply {
                color = Color.BLACK
                textSize = 26f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }

            val linePaint = Paint().apply {
                color = Color.LTGRAY
                strokeWidth = 2f
            }

            var y = PADDING + 32f

            // Header: Shop Name & Date
            val displayShopName = shopName.ifBlank { "Voice To Invoice Shop" }
            canvas.drawText(displayShopName, PADDING, y, titlePaint)
            y += LINE_HEIGHT

            val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(nowMs))
            canvas.drawText(dateStr, PADDING, y, textPaint)
            y += LINE_HEIGHT

            if (!customerName.isNullOrBlank()) {
                canvas.drawText("ग्राहक: $customerName", PADDING, y, textPaint)
                y += LINE_HEIGHT
            }

            y += 10f
            canvas.drawLine(PADDING, y, WIDTH - PADDING, y, linePaint)
            y += 24f

            // Table Header
            canvas.drawText("सामान (Item)", PADDING, y, boldPaint)
            val totalHeader = "रकम (Total)"
            val totalHeaderWidth = boldPaint.measureText(totalHeader)
            canvas.drawText(totalHeader, WIDTH - PADDING - totalHeaderWidth, y, boldPaint)
            y += LINE_HEIGHT

            canvas.drawLine(PADDING, y, WIDTH - PADDING, y, linePaint)
            y += 20f

            // Line items
            var grandTotal = 0.0
            for (line in lines) {
                val qtyStr = fmtQty(line.quantity)
                val itemDesc = "$qtyStr ${line.itemName}"
                canvas.drawText(itemDesc, PADDING, y, textPaint)

                val amountStr = "₹${IndianCurrency.format(line.total)}"
                val amountWidth = textPaint.measureText(amountStr)
                canvas.drawText(amountStr, WIDTH - PADDING - amountWidth, y, textPaint)

                grandTotal += line.total
                y += LINE_HEIGHT
            }

            y += 10f
            canvas.drawLine(PADDING, y, WIDTH - PADDING, y, linePaint)
            y += 24f

            // Subtotal
            val subtotalStr = "बिक्री कुल: ₹${IndianCurrency.format(grandTotal)}"
            val subtotalWidth = boldPaint.measureText(subtotalStr)
            canvas.drawText(subtotalStr, WIDTH - PADDING - subtotalWidth, y, boldPaint)
            y += LINE_HEIGHT

            if (previousBalance != null) {
                val prevStr = "पिछला बाकी: ₹${IndianCurrency.format(previousBalance)}"
                val prevWidth = textPaint.measureText(prevStr)
                canvas.drawText(prevStr, WIDTH - PADDING - prevWidth, y, textPaint)
                y += LINE_HEIGHT

                val finalTotal = grandTotal + previousBalance
                val finalStr = "कुल देय: ₹${IndianCurrency.format(finalTotal)}"
                val finalWidth = titlePaint.measureText(finalStr)
                canvas.drawText(finalStr, WIDTH - PADDING - finalWidth, y, titlePaint)
                y += LINE_HEIGHT
            }

            val dir = File(context.cacheDir, "bills").apply { mkdirs() }
            val file = File(dir, "bill_${nowMs}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            android.util.Log.w("BillBuilder", "Bill render failed, caller should fall back to text", e)
            null
        }
    }

    /** Deletes bills older than a day. Call on launch; these are cache files, not records --
     *  the transactions themselves are the durable copy. */
    fun purgeOld(context: Context, olderThanMs: Long = 24 * 60 * 60 * 1000L) {
        try {
            val dir = File(context.cacheDir, "bills")
            if (!dir.exists()) return
            val cutoff = System.currentTimeMillis() - olderThanMs
            dir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("BillBuilder", "Failed to purge old bills", e)
        }
    }

    private fun fmtQty(q: Double): String = if (q % 1.0 == 0.0) q.toInt().toString() else "%.1f".format(q)
}
