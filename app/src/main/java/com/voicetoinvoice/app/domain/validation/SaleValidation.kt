package com.voicetoinvoice.app.domain.validation

/**
 * Single source of truth for whether a manually-reviewed sale (e.g. from the Unmatched Queue)
 * is valid to write to the ledger. Used by both the confirm-button enabled state
 * (UnmatchedQueueScreen) and the actual DB-write gate (MainActivity) so they can't drift apart.
 *
 * This is distinct from the server's AUTO_CONFIRM confidence gate in
 * supabase/functions/process-voice-job/index.ts, which additionally requires a confidence
 * threshold and only applies to unreviewed voice parses — SttWorker.kt trusts that server
 * classification directly rather than re-deriving it.
 */
object SaleValidation {
    const val UNRECOGNIZED_ITEM_PLACEHOLDER = "Unrecognized Item"

    fun isConfirmableSale(itemName: String, price: Double, quantity: Double): Boolean {
        val total = price * quantity
        return price > 0.0 &&
                total > 0.0 &&
                itemName.trim().isNotBlank() &&
                itemName.trim() != UNRECOGNIZED_ITEM_PLACEHOLDER
    }
}
