package com.voicetoinvoice.app.domain.parser

/**
 * Domain sanity checks on a parsed sale, independent of how confident the transcript
 * pipeline is about it.
 *
 * Why this exists: every accuracy fix in this pipeline so far has improved *recall* —
 * whether a match is found at all. None measured whether the result makes sense. So
 * each improvement made the system more confidently wrong. In trace
 * `e0b68f80-6876-42e2-b556-2adf73ce463f` the shopkeeper said "पांच किलो अमचूर" and the
 * pipeline booked **7 GRAM of Jeera for ₹2.80** straight to the ledger at 0.95
 * confidence. No shop has ever made that sale. A rule this obvious never had to lose to
 * a phonetic score — it simply was not being asked.
 *
 * These checks never block a sale. They withhold AUTO-CONFIRM and route to the review
 * queue, where a wrong parse costs the shopkeeper one tap instead of corrupting their
 * books. Mirrored in `supabase/functions/process-voice-job/index.ts`
 * (`implausibilityReason`) — keep the two in sync.
 */
object SalePlausibility {

    /** Smallest sale value auto-confirmed without a human glance. */
    const val MIN_PLAUSIBLE_SALE_VALUE = 5.0

    /**
     * Returns a human-readable reason the sale looks implausible, or `null` when it is
     * fine. The reason string is written into the diagnostic trace so a review-queue
     * entry explains why it landed there.
     */
    fun reason(unit: String?, quantity: Double, total: Double): String? {
        if (quantity <= 0.0) return "quantity $quantity is not positive"

        when ((unit ?: "").uppercase()) {
            // Weight/volume in small units is retailed in 50/100/250/500 steps. A
            // quantity of 7 there is a mis-heard unit — almost always KG heard as GRAM,
            // exactly as in the ISSUE-022 trace where "गिलम" matched `gram` at 0.100
            // and beat `kilo` at 0.300.
            "GRAM", "ML" -> {
                if (quantity < 10) {
                    return "$quantity ${unit?.uppercase()} is below any real retail " +
                        "quantity (likely a mis-heard KG/LITRE)"
                }
                if (quantity > 5000) return "$quantity ${unit?.uppercase()} exceeds a plausible single sale"
            }
            "KG", "LITRE" -> {
                if (quantity > 200) return "$quantity ${unit?.uppercase()} exceeds a plausible single sale"
            }
            "PIECE", "PACKET", "DOZEN" -> {
                if (quantity > 500) return "$quantity ${unit?.uppercase()} exceeds a plausible single sale"
            }
        }

        if (total > 0.0 && total < MIN_PLAUSIBLE_SALE_VALUE) {
            return "sale value ₹$total is below the ₹$MIN_PLAUSIBLE_SALE_VALUE auto-confirm floor"
        }
        return null
    }

    fun isPlausible(unit: String?, quantity: Double, total: Double): Boolean =
        reason(unit, quantity, total) == null
}
