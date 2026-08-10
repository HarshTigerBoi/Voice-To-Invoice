package com.voicetoinvoice.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's colour *vocabulary*. One meaning per colour, app-wide, never reused for a
 * second meaning — this is the only channel a shopkeeper who cannot read the label has.
 *
 * Delegates to the values already defined in `Color.kt` (`Money`/`Owed`/`Danger`/`Info`,
 * wired into `Theme.kt`'s MaterialTheme scheme and already live in `PriceUpdateScreen.kt`)
 * rather than defining new literals -- an earlier pass here introduced a second, differently
 * -valued green/amber/red/blue instead of discovering that one already existed, which would
 * have left two "money-in greens" in the same app. `Neutral` has no existing equivalent.
 */
object LedgerColors {
    val MoneyIn      = Money    // cash/UPI received, profit, growth, fast movers
    val MoneyOut     = Danger   // waste, loss, overdue receivables, shrink
    val Udhaar       = Owed     // credit given — owed TO the shop, not yet money
    val Upi          = Info     // UPI specifically, where split from cash matters
    val Neutral      = Color(0xFF616161)  // no judgement attached

    /** Positive-is-good delta colouring. */
    fun forDelta(delta: Double): Color = if (delta >= 0) MoneyIn else MoneyOut

    /** Health-score banding — thresholds unchanged from ReportsScreen.healthColor. */
    fun forScore(score: Int): Color = when {
        score >= 70 -> MoneyIn
        score >= 40 -> Udhaar
        else        -> MoneyOut
    }
}
