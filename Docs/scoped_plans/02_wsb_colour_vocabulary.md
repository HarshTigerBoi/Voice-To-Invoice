# Scoped plan 2/11 — WS-B: Semantic colour vocabulary (ISSUE-116)

**Source:** extracted verbatim from `Docs/visual_ledger_and_assistant_plan.md` §3, WS-B.
**Scope:** this file ONLY. Do not read or implement any other section of the parent plan.
Pure refactor — no behaviour change, no new feature. Client (Kotlin) only.

---

### B1. Single source of truth (new file)

`app/src/main/java/com/voicetoinvoice/app/ui/theme/LedgerColors.kt`

```kotlin
package com.voicetoinvoice.app.ui.theme

/**
 * The app's colour *vocabulary*. One meaning per colour, app-wide, never reused for a
 * second meaning — this is the only channel a shopkeeper who cannot read the label has.
 */
object LedgerColors {
    val MoneyIn      = Color(0xFF2E7D32)  // cash/UPI received, profit, growth, fast movers
    val MoneyOut     = Color(0xFFC62828)  // waste, loss, overdue receivables, shrink
    val Udhaar       = Color(0xFFF9A825)  // credit given — owed TO the shop, not yet money
    val Upi          = Color(0xFF0288D1)  // UPI specifically, where split from cash matters
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
```

(Add the `androidx.compose.ui.graphics.Color` import.)

### B2. Replace every hardcoded colour literal

Delete `private val Color0xFF2E7D32` at `ui/screens/reports/ReportsScreen.kt:510` and the `healthColor` function at :467-471; route both through `LedgerColors`.

Then sweep for remaining literals and replace by meaning:

```bash
grep -rn "Color(0xFF" app/src/main/java/com/voicetoinvoice/app/ui/
```

Mapping rules — apply these, do not invent new ones:
- `0xFF2E7D32` (green) → `LedgerColors.MoneyIn`
- `0xFFC62828` / `MaterialTheme.colorScheme.error` **where it means money lost** → `LedgerColors.MoneyOut`. Leave `colorScheme.error` alone where it means "UI validation error."
- `0xFFF9A825` (amber) → `LedgerColors.Udhaar`
- `0xFF0288D1` (blue) → `LedgerColors.Upi`
- `0xFF616161` (grey) → `LedgerColors.Neutral`

`ui/components/ItemIcon.kt:78-88` (`getCategoryBackgroundColor`) is a **category** palette, not a money palette. Leave it alone — it is a different vocabulary and conflating them would break this workstream's premise.

### B3. Fix the one existing collision

`ReportsScreen.MoversCard` (:286) paints "तेज़ 🔥" (fast-moving) green and "धीमा 🐢" (slow) with `colorScheme.error` red. Under the vocabulary, red = *money out*, and a slow-moving item is not a loss. Change slow/dead to `LedgerColors.Udhaar` (amber = attention, no loss claimed). Fast stays `MoneyIn`.

---

## Verification (do this yourself before reporting done)

`grep -rn "Color(0xFF" app/src/main/java/com/voicetoinvoice/app/ui/` should return hits only inside `ItemIcon.kt` (the category palette, deliberately untouched) and `LedgerColors.kt` itself. Any other hit means a literal was missed.

## Audit log

Add a 🟢 RESOLVED entry for ISSUE-116 in `Docs/audit.md`'s single "### 🟢 RESOLVED ISSUES" section (there is only one such section now — do not create a second). Follow the existing entry format (Symptom / Root Cause / Resolution / Verification Date / Status).

## Deviations

End with a "Deviations" section. If none, say "None."
