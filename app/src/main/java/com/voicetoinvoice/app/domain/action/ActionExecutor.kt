package com.voicetoinvoice.app.domain.action

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.voicetoinvoice.app.data.local.entity.CreditRecord
import com.voicetoinvoice.app.data.local.entity.CustomerRecord
import com.voicetoinvoice.app.data.local.entity.TransactionRecord

/**
 * Launches outward-facing actions: WhatsApp bills and reminders, and phone calls.
 *
 * ## How sending works, and why it is not automatic
 *
 * Everything is composed here and WhatsApp opens PREFILLED, but the shopkeeper presses Send.
 * There is no API for a normal (non-Business-API) app to send a WhatsApp message without the user
 * confirming, and building something that merely looked automatic would break the moment WhatsApp
 * tightened its intent handling. The tap is also the consent checkpoint: messaging a shopkeeper's
 * customers from their own number should not happen without them seeing it.
 *
 * ## Failures are surfaced, not swallowed
 *
 * Every method used to end in a bare `e.printStackTrace()`, which made "WhatsApp isn't installed"
 * indistinguishable from success. Each now returns a [Result] the caller must show.
 */
object ActionExecutor {

    private const val TAG = "ActionExecutor"

    sealed interface Result {
        data object Launched : Result
        /** WhatsApp (or a dialer) is not installed. Caller should offer SMS/copy instead. */
        data class AppMissing(val message: String) : Result
        data class Failed(val message: String) : Result
    }

    // ---------------------------------------------------------------- reminders

    fun sendWhatsAppReminder(context: Context, credit: CreditRecord, phone: String?): Result {
        val message = buildString {
            append("नमस्ते ${credit.customerName} जी 🙏\n")
            append("आपका ₹${fmt(credit.amount)} का बकाया (Udhaar) बाकी है।\n")
            append("कृपया भुगतान करें। धन्यवाद!")
        }
        return openWhatsApp(context, phone, message)
    }

    fun sendPaymentReminder(
        context: Context,
        customer: CustomerRecord,
        balance: Double,
        shopName: String = "",
        agingDays: Int = 0
    ): Result {
        val message = buildString {
            append("नमस्ते ${customer.name} जी 🙏\n")
            if (shopName.isNotBlank()) append("$shopName\n")
            append("\nआपका बकाया: ₹${fmt(balance)}\n")
            if (agingDays > 30) append("(लगभग $agingDays दिन से)\n")
            append("\nकृपया सुविधा अनुसार भुगतान कर दें। धन्यवाद!")
        }
        return openWhatsApp(context, customer.phone, message)
    }

    // ---------------------------------------------------------------- bills

    /**
     * Sends an itemized bill. [lines] are the transactions sharing one `billId`.
     */
    fun sendBill(
        context: Context,
        customer: CustomerRecord?,
        lines: List<TransactionRecord>,
        shopName: String = "",
        previousBalance: Double? = null
    ): Result {
        if (lines.isEmpty()) return Result.Failed("इस बिल में कोई आइटम नहीं है")

        val subtotal = lines.sumOf { it.total }
        val message = buildString {
            if (customer != null) append("नमस्ते ${customer.name} जी 🙏\n")
            if (shopName.isNotBlank()) append("$shopName\n")
            append("\n")
            for (line in lines) {
                append("${fmtQty(line.quantity)} ${line.itemName} — ₹${fmt(line.total)}\n")
            }
            append("\nकुल: ₹${fmt(subtotal)}")
            if (previousBalance != null && previousBalance > 0.0) {
                append("\nपिछला बकाया: ₹${fmt(previousBalance)}")
                append("\nअब कुल बकाया: ₹${fmt(previousBalance + subtotal)}")
            }
            append("\n\nधन्यवाद!")
        }
        return openWhatsApp(context, customer?.phone, message)
    }

    /** Shares a rendered bill image. The file must live under the app cache dir. */
    fun sendBillImage(context: Context, imageUri: Uri, phone: String?, caption: String = ""): Result {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            if (caption.isNotBlank()) putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!phone.isNullOrBlank()) setPackage(WHATSAPP_PACKAGE)
        }
        return launch(context, intent, "WhatsApp")
    }

    // ---------------------------------------------------------------- calls

    fun dialCustomer(context: Context, phone: String): Result {
        if (phone.isBlank()) return Result.Failed("नंबर नहीं है")
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${normalize(phone)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return launch(context, intent, "Dialer")
    }

    // ---------------------------------------------------------------- internals

    private fun openWhatsApp(context: Context, phone: String?, message: String): Result {
        val encoded = Uri.encode(message)
        val normalized = phone?.let { normalize(it) }.orEmpty()
        val url = if (normalized.isNotBlank()) {
            "https://api.whatsapp.com/send?phone=${withCountryCode(normalized)}&text=$encoded"
        } else {
            "https://api.whatsapp.com/send?text=$encoded"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return launch(context, intent, "WhatsApp")
    }

    private fun launch(context: Context, intent: Intent, appLabel: String): Result = try {
        context.startActivity(intent)
        Result.Launched
    } catch (e: android.content.ActivityNotFoundException) {
        Log.w(TAG, "$appLabel not available: ${e.message}")
        Result.AppMissing("$appLabel इस फ़ोन में नहीं मिला")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to launch $appLabel", e)
        Result.Failed(e.message ?: "भेजा नहीं जा सका")
    }

    private fun normalize(phone: String) = phone.filter { it.isDigit() || it == '+' }

    /**
     * WhatsApp's `send` endpoint needs a country code. Indian shopkeepers save 10-digit local
     * numbers, which silently open an empty chat without one.
     */
    private fun withCountryCode(phone: String): String {
        val digits = phone.removePrefix("+")
        return when {
            phone.startsWith("+") -> digits
            digits.length == 10 -> "91$digits"
            else -> digits
        }
    }

    private fun fmt(amount: Double): String = IndianCurrency.format(amount)

    private fun fmtQty(q: Double): String {
        val abs = kotlin.math.abs(q)
        return if (abs % 1.0 == 0.0) abs.toInt().toString() else abs.toString()
    }

    private const val WHATSAPP_PACKAGE = "com.whatsapp"
}

