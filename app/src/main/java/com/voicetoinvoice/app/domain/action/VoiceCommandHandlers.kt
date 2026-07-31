package com.voicetoinvoice.app.domain.action

import android.util.Log
import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CustomerPayment
import com.voicetoinvoice.app.data.local.entity.CustomerRecord
import com.voicetoinvoice.app.data.local.entity.PaymentMode
import com.voicetoinvoice.app.data.local.entity.StockReason
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.data.local.entity.TransactionSource
import com.voicetoinvoice.app.data.local.entity.TxnType
import com.voicetoinvoice.app.data.repository.StockLedgerRepository
import com.voicetoinvoice.app.domain.resolver.Decision
import com.voicetoinvoice.app.domain.resolver.EntityResolver
import org.json.JSONArray

/**
 * Outcome of a voice command: what changed, and what to say back.
 *
 * [committed] drives the job's terminal status, so a handler that could not act MUST report
 * `committed = 0`. Reporting success for a no-op is how the assistant ended up appearing to work
 * while changing nothing.
 */
data class CommandOutcome(
    val committed: Int,
    val spokenReply: String,
    /** Set when the handler needs the UI to finish the job (missing phone, ambiguous customer). */
    val followUp: FollowUp? = null
)

sealed interface FollowUp {
    /** Ask the shopkeeper which customer was meant. */
    data class DisambiguateCustomer(val candidates: List<CustomerRecord>, val amount: Double) : FollowUp
    /** A WhatsApp/dial action is ready but needs the Activity to launch the intent. */
    data class LaunchAction(val kind: ActionKind, val customer: CustomerRecord, val amount: Double) : FollowUp
    /** The named customer has no phone number on file. */
    data class MissingPhone(val customer: CustomerRecord) : FollowUp
}

enum class ActionKind { SEND_BILL, SEND_REMINDER, CALL }

/**
 * Handlers for the voice commands that previously had nowhere to go.
 *
 * `IntentRouter` could not even produce `ACTION_COMMAND` (its `ACTION_WORDS` set was declared and
 * never read), and `SttWorker` answered every one with "यह काम अभी नहीं कर सकता" while a complete
 * `ActionExecutor.sendWhatsAppReminder` sat at zero call sites. These handlers are the missing
 * middle: each one commits a real change and returns what to say about it.
 */
class VoiceCommandHandlers(
    private val db: AppDatabase,
    private val stockLedger: StockLedgerRepository
) {

    private val shopId: String get() = ShopContext.requireShopId()

    // ------------------------------------------------------------------ PRICE_UPDATE

    /** "आलू का रेट 30 कर दो" -> update the catalog rate, and remember the change. */
    suspend fun handlePriceUpdate(parsedItems: JSONArray, transcript: String): CommandOutcome {
        val catalog = db.catalogDao().getActiveCatalogList()
        var updated = 0
        var lastName = ""
        var lastPrice = 0.0

        for (i in 0 until parsedItems.length()) {
            val obj = parsedItems.optJSONObject(i) ?: continue
            val name = obj.optString("item_name", "").trim()
            val price = obj.optDouble("price_at_sale", 0.0).takeIf { it > 0.0 }
                ?: obj.optDouble("total", 0.0)
            if (name.isBlank() || name == "Unrecognized Item" || price <= 0.0) continue

            val itemId = obj.optString("item_id", "").ifBlank { null }
            val match = itemId?.let { id -> catalog.find { it.id == id } }
                ?: catalog.find { it.name.equals(name, ignoreCase = true) }
                ?: continue

            db.catalogDao().updatePrice(match.id, price)
            updated++
            lastName = match.name
            lastPrice = price
        }

        return if (updated == 0) {
            CommandOutcome(0, "किस चीज़ का रेट बदलना है? दोबारा बोलिए")
        } else {
            CommandOutcome(updated, "$lastName का रेट ₹${lastPrice.toInt()} कर दिया")
        }
    }

    // ------------------------------------------------------------------ RETURN

    /**
     * "दो किलो आलू वापस कर दिया" -> a negative RETURN transaction plus stock back on the shelf.
     *
     * A return is booked as its own append-only row rather than by mutating the original sale, so
     * the ledger stays an event log and today's report still shows what was sold today.
     */
    suspend fun handleReturn(
        parsedItems: JSONArray,
        transcript: String,
        jobId: String
    ): CommandOutcome {
        val catalog = db.catalogDao().getActiveCatalogList()
        var committed = 0
        var lastName = ""
        var lastQty = 0.0
        var refundTotal = 0.0

        for (i in 0 until parsedItems.length()) {
            val obj = parsedItems.optJSONObject(i) ?: continue
            val name = obj.optString("item_name", "").trim()
            val qty = obj.optDouble("quantity", 0.0)
            if (name.isBlank() || name == "Unrecognized Item" || qty <= 0.0) continue

            val itemId = obj.optString("item_id", "").ifBlank { null }
            val match = itemId?.let { id -> catalog.find { it.id == id } }
                ?: catalog.find { it.name.equals(name, ignoreCase = true) }
            val resolvedId = match?.id ?: itemId ?: "unlisted-$jobId"
            val resolvedName = match?.name ?: name

            // Refund at the price the goods were actually sold at when we can find that sale,
            // otherwise at today's catalog price. Refunding at a stale or guessed price is a
            // direct cash error, so prefer the original.
            val original = db.transactionDao().getRecentTransactionsByAmount(
                amount = obj.optDouble("total", -1.0),
                sinceMs = System.currentTimeMillis() - RETURN_LOOKBACK_MS
            ).firstOrNull { it.itemId == resolvedId }

            val unitPrice = original?.priceAtSale
                ?: obj.optDouble("price_at_sale", 0.0).takeIf { it > 0.0 }
                ?: match?.price
                ?: 0.0
            val lineTotal = unitPrice * qty

            val returnTx = TransactionRecord(
                shopId = shopId,
                itemId = resolvedId,
                itemName = resolvedName,
                quantity = -qty,
                priceAtSale = unitPrice,
                total = -lineTotal,
                paymentMode = original?.paymentMode ?: PaymentMode.CASH,
                source = TransactionSource.VOICE,
                rawTranscript = transcript,
                jobId = jobId,
                lineNo = i,
                txnType = TxnType.RETURN,
                returnOfTxnId = original?.id,
                costAtSale = original?.costAtSale ?: match?.avgCostPrice,
                billId = jobId
            )
            db.transactionDao().insert(returnTx)
            stockLedger.recordReturnIn(
                itemId = resolvedId,
                itemName = resolvedName,
                qty = qty,
                returnTransactionId = returnTx.id,
                unitCost = returnTx.costAtSale,
                returnValue = lineTotal
            )

            committed++
            lastName = resolvedName
            lastQty = qty
            refundTotal += lineTotal
        }

        return if (committed == 0) {
            CommandOutcome(0, "क्या वापस आया? दोबारा बोलिए")
        } else {
            CommandOutcome(
                committed,
                "$lastQty $lastName वापस लिया, ₹${refundTotal.toInt()} कम किए"
            )
        }
    }

    // ------------------------------------------------------------------ PAYMENT_RECEIVED

    /** "रमेश ने 500 दिए" -> record the repayment and read back the remaining balance. */
    suspend fun handlePaymentReceived(
        transcript: String,
        jobId: String
    ): CommandOutcome {
        val amount = extractAmount(transcript)
        if (amount == null || amount <= 0.0) {
            return CommandOutcome(0, "कितने रुपये आए? दोबारा बोलिए")
        }

        val customers = db.customerDao().getActiveCustomersList()
        if (customers.isEmpty()) {
            return CommandOutcome(0, "पहले ग्राहक जोड़िए, फिर भुगतान दर्ज होगा")
        }

        val resolution = EntityResolver<CustomerRecord>().resolve(transcript, customers)
        if (resolution.decision != Decision.AUTO_ASSIGN) {
            // Do NOT guess: crediting the wrong customer creates two wrong balances at once.
            return CommandOutcome(
                committed = 0,
                spokenReply = "₹${amount.toInt()} किसने दिए?",
                followUp = FollowUp.DisambiguateCustomer(
                    resolution.candidates.take(3).map { it.item },
                    amount
                )
            )
        }

        val customer = resolution.candidates.first().item
        val payment = CustomerPayment(
            shopId = shopId,
            customerId = customer.id,
            customerName = customer.name,
            amount = amount,
            mode = PaymentMode.CASH,
            jobId = jobId,
            note = transcript.take(140)
        )
        db.customerPaymentDao().insert(payment)

        val remaining = com.voicetoinvoice.app.domain.query.CustomerBalance(db).balanceFor(customer.id)
        val tail = if (remaining > 0.0) ", अब ₹${remaining.toInt()} बाकी" else ", पूरा हिसाब साफ़"
        return CommandOutcome(1, "${customer.name} ने ₹${amount.toInt()} दिए$tail")
    }

    // ------------------------------------------------------------------ VOID_LAST

    /**
     * "गलत हो गया, कैंसिल करो" -> void the newest sale, bounded to the last 10 minutes.
     *
     * The window matters: an unbounded voice "delete" over an append-only ledger is how a
     * misheard word erases settled history.
     */
    suspend fun handleVoidLast(): CommandOutcome {
        val since = System.currentTimeMillis() - VOID_WINDOW_MS
        val target = db.transactionDao().getMostRecentSaleSince(since)
            ?: return CommandOutcome(0, "पिछले 10 मिनट में कोई एंट्री नहीं मिली")

        val ok = stockLedger.voidSale(target.id)
        return if (!ok) {
            CommandOutcome(0, "एंट्री हटाई नहीं जा सकी")
        } else {
            CommandOutcome(
                1,
                "पिछली एंट्री हटा दी — ${fmtQty(target.quantity)} ${target.itemName} ₹${target.total.toInt()}"
            )
        }
    }

    // ------------------------------------------------------------------ ACTION_COMMAND

    /**
     * "रमेश को बिल भेजो" / "याद दिलाओ" / "कॉल करो".
     *
     * This is the handler whose absence WAS the bug. It resolves the customer and returns a
     * [FollowUp.LaunchAction] for the Activity to fire, because launching a WhatsApp intent needs
     * a UI context and the shopkeeper's own tap is the send confirmation -- there is no silent-send
     * API for a non-Business-API app, and auto-messaging customers from a shopkeeper's number is
     * not something to do without them pressing send.
     */
    suspend fun handleActionCommand(transcript: String): CommandOutcome {
        val kind = classifyAction(transcript)
        val customers = db.customerDao().getActiveCustomersList()
        if (customers.isEmpty()) {
            return CommandOutcome(0, "पहले ग्राहक जोड़िए")
        }

        val resolution = EntityResolver<CustomerRecord>().resolve(transcript, customers)
        if (resolution.decision != Decision.AUTO_ASSIGN) {
            return CommandOutcome(
                committed = 0,
                spokenReply = "किसको भेजना है?",
                followUp = FollowUp.DisambiguateCustomer(resolution.candidates.take(3).map { it.item }, 0.0)
            )
        }

        val customer = resolution.candidates.first().item
        if (customer.phone.isNullOrBlank()) {
            // Say so out loud instead of failing silently -- the old code's bare printStackTrace
            // made "no phone number" indistinguishable from success.
            return CommandOutcome(
                committed = 0,
                spokenReply = "${customer.name} का नंबर नहीं है — जोड़ दें?",
                followUp = FollowUp.MissingPhone(customer)
            )
        }

        val balance = com.voicetoinvoice.app.domain.query.CustomerBalance(db).balanceFor(customer.id)
        val reply = when (kind) {
            ActionKind.SEND_BILL -> "${customer.name} को बिल भेजने के लिए तैयार है"
            ActionKind.SEND_REMINDER -> "${customer.name} को ₹${balance.toInt()} का रिमाइंडर तैयार है"
            ActionKind.CALL -> "${customer.name} को कॉल लगा रहे हैं"
        }
        Log.i(TAG, "ACTION_COMMAND resolved: $kind for ${customer.name}")
        return CommandOutcome(
            committed = 1,
            spokenReply = reply,
            followUp = FollowUp.LaunchAction(kind, customer, balance)
        )
    }

    private fun classifyAction(transcript: String): ActionKind {
        val keys = transcript.lowercase()
        val phonetic = com.voicetoinvoice.app.domain.parser.PhoneticKey.of(keys)
        return when {
            listOf("call", "कॉल", "phone", "फ़ोन", "dial", "डायल").any {
                keys.contains(it) ||
                    com.voicetoinvoice.app.domain.parser.PhoneticKey.normalizedDistance(
                        phonetic, com.voicetoinvoice.app.domain.parser.PhoneticKey.of(it)
                    ) <= 0.2
            } -> ActionKind.CALL

            listOf("yaad", "याद", "remind", "रिमाइंडर", "reminder", "baki", "बाकी").any { keys.contains(it) } ->
                ActionKind.SEND_REMINDER

            else -> ActionKind.SEND_BILL
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Pulls a rupee amount out of an utterance.
     *
     * Takes the LARGEST number rather than the first: "Ramesh ne 2 baar mein 500 diye" should book
     * 500, not 2. Digits only -- spoken-word numerals are already converted upstream by the
     * compound-number engine in `price_intent.ts`.
     */
    private fun extractAmount(transcript: String): Double? =
        AMOUNT_PATTERN.findAll(transcript)
            .mapNotNull { it.value.replace(Regex("[^0-9.]"), "").toDoubleOrNull() }
            .filter { it > 0.0 }
            .maxOrNull()

    private fun fmtQty(q: Double): String {
        val abs = kotlin.math.abs(q)
        return if (abs % 1.0 == 0.0) abs.toInt().toString() else abs.toString()
    }

    companion object {
        private const val TAG = "VoiceCommandHandlers"

        /** Voice void only reaches back this far. */
        const val VOID_WINDOW_MS = 10 * 60 * 1000L

        /** How far back to look for the original sale when pricing a return. */
        const val RETURN_LOOKBACK_MS = 7 * 24 * 60 * 60 * 1000L

        private val AMOUNT_PATTERN = Regex("\\d+(?:\\.\\d+)?")
    }
}
