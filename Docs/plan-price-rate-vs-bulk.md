# Implementation Plan: Rupee-Word-Gated Rate vs. Bulk-Sale Pricing

> **Audience**: This document is written for an implementing agent (Antigravity/Gemini) that
> should execute mechanically, without re-deriving the design. All decisions below are final.
> If actual code at a referenced line differs from what's quoted here (drift since this was
> written), trust the live file and adapt the surrounding logic — don't skip the change.
>
> **Do not implement Phase 2 (server mirror) or the "Non-Goals" section unless separately asked.**

---

## 0. Problem this solves

Shopkeepers are not always technical/literate. Voice input must be able to express three
different things using natural Hinglish speech, and the app must never guess wrong and
silently record a bad number:

1. "I'm updating today's price for an item" (a rate, e.g. ₹1/kg baseline).
2. "I just sold some quantity of an item for a specific total" (a normal sale, possibly a
   bulk/discounted deal, e.g. 4kg for ₹500 — which is NOT the same as 4× the per-kg rate).
3. Anything that sounds like it might contain a price, but isn't clearly one of the above —
   must never be silently guessed. It must go to the existing Pending Confirmations review
   queue instead.

The single trigger the shopkeeper is taught to control this is one simple rule:

> **Say a rupee-word ("rupay/rupaye/rupee/rs/₹/रुपये" etc.) only when you are stating a price.
> If you don't say it, no number in your sentence will ever be treated as a price.**

Whether that price is a *rate update* or a *sale total* is then decided by one more simple,
memorable rule:

> **If you say a quantity, it's a sale (that exact total is recorded, the standing rate is left
> alone). If you don't say a quantity, it's a rate update (the standing per-unit rate changes,
> and nothing is recorded as a sale).**

---

## 1. Confirmed current behavior (baseline, verified against source)

- **`app/src/main/java/com/voicetoinvoice/app/domain/parser/VoiceParser.kt`** (`extractQtyUnitAndPrice`,
  around L268-286): a trailing number is treated as a price if (a) the *first* token is also a
  number and there are ≥3 tokens, **regardless of any rupee-word**, or (b) the string contains
  `rs`/`rupees`/`रुपये` anywhere, or (c) the number is simply `≥ 10.0`. This is the root cause of
  false-positive price detection (path (a) and (c) fire with no rupee-word at all).
- **`VoiceParser.kt` L109-114**: any detected price is always treated as the **transaction total**;
  `updatedUnitPrice = total / extractedQty` is always computed, with no distinction between a
  rate statement and a bulk sale.
- **`app/src/main/java/com/voicetoinvoice/app/MainActivity.kt` L149-179** (`onConfirmSale`, wired
  to `HomeScreen`'s manual quick-stepper and "Type sale manually" fallback path only — **not** the
  real hold-to-speak pipeline): unconditionally overwrites `CatalogItem.price` via
  `database.catalogDao().insertOrUpdate(targetItem)` whenever `priceOverridden` is true, no matter
  whether the price came from a rate statement or a bulk/discount sale. **This is the confirmed
  corruption bug** — reachable today only through manual typed-text entry, not through actual
  voice recording.
- **`app/src/main/java/com/voicetoinvoice/app/domain/processor/BackgroundSttProcessor.kt`** (the
  real mic-hold → `SttWorker` → auto-confirm pipeline, L332-373): never calls
  `catalogDao().insertOrUpdate(...)` at all. It always prices a sale from the existing
  `item.price` on the matched catalog row. **This means the real voice pipeline currently has no
  way to update a standing rate by voice, and no bulk-sale-total support either** — this is a
  feature gap, not just a bug, and this plan builds it here.
- **`app/src/main/java/com/voicetoinvoice/app/ui/screens/home/HomeScreen.kt`** L356-412
  (`onConfirmJob`, backing the Pending Confirmations review sheet): only calls
  `catalogDao().insertOrUpdate(item)` when the item didn't exist in the catalog yet (seeding a
  brand-new item's first price). This path is already safe for existing items — leave its
  seed-on-new-item behavior as is.

---

## 2. Final rule set (product decision — do not re-litigate)

| Spoken pattern | Example | Classification | Effect |
|---|---|---|---|
| item + price + rupee-word, **no quantity** | "aloo pachaas rupay" | `RATE_UPDATE` | Updates `CatalogItem.price` for that item. **No `TransactionRecord` is created** — this is not a sale. |
| quantity + item + price + rupee-word | "4 kilo aloo 500 rupay" | `BULK_SALE_TOTAL` | Records **one** `TransactionRecord` with `total = 500`, `priceAtSale = 500/4 = 125`. `CatalogItem.price` (the standing rate) is **left untouched** — even if this is later re-said, it is never remembered as a reusable deal (deliberate — see §8 Non-Goals). |
| quantity + item, **no price** | "4 kilo aloo" | `NONE` | Existing behavior: `total = qty × CatalogItem.price`. No change needed here. |
| item/qty + a price-looking number **without** an adjacent rupee-word | "4 kilo aloo 500" (no "rupay") | `AMBIGUOUS_UNTRUSTED` | The number is **not** trusted as a price at all. Route to the existing Pending Confirmations review queue (same mechanism as today's `isPendingPrice`) instead of guessing. |
| `BULK_SALE_TOTAL` or `RATE_UPDATE` where the derived per-unit rate differs from the item's existing nonzero price by **more than 50%** | rate jumps ₹20 → ₹90 in one utterance | same classification, plus `isSanityFlagged = true` | Do not auto-apply/auto-confirm silently — route to review instead, since a >50% jump is more likely a misheard number than a real price change. Shopkeeper can confirm or correct it in the queue. |
| Brand-new item never priced before (`CatalogItem.price == 0.0`, or item not yet in catalog) | any first sale of a new item, price stated | `BULK_SALE_TOTAL` still seeds the initial catalog price | Exception to "bulk never touches catalog rate" — there is no existing rate to protect, so seed it from `total / qty`. This exception already exists in `HomeScreen.kt` L358 and `MainActivity.kt` L155 — preserve it, just make sure it's the *only* case where a `BULK_SALE_TOTAL` writes to `CatalogItem.price`. |

---

## 3. `VoiceParser.kt` changes

### 3.1 New enum + fields on `ParsedVoiceSale`

Add near the top of the file, above `ParsedVoiceSale`:

```kotlin
enum class PriceIntent {
    NONE,                 // no price spoken; use existing catalog rate
    RATE_UPDATE,           // price + rupee-word, no quantity spoken
    BULK_SALE_TOTAL,       // price + rupee-word + quantity spoken
    AMBIGUOUS_UNTRUSTED    // price-looking number present, but no rupee-word adjacent to it
}
```

Add a field to `ParsedVoiceSale`:

```kotlin
val priceIntent: PriceIntent = PriceIntent.NONE,
```

Keep `priceOverridden` / `updatedUnitPrice` / `isPendingPrice` as-is for backward compatibility
with existing call sites, but their values must now be derived consistently with `priceIntent`
(see 3.3).

### 3.2 Rupee-word set

Add a constant (module-level `private val` inside `VoiceParser`, near `hindiNumberMap`):

```kotlin
private val rupeeWords: Set<String> = setOf(
    "rs", "rs.", "₹",
    "rupay", "rupaye", "rupaya", "rupaiya", "rupaiye",
    "rupee", "rupees",
    "रुपये", "रुपया", "रुपए", "रु", "रूपये", "रूपए"
)
```

### 3.3 Rewrite price detection in `extractQtyUnitAndPrice`

Replace the current "Extract Spoken Price at End" block (current L268-286) with:

1. Iterate `tokens` by index `i`. For each token, compute `num = token.toDoubleOrNull() ?:
   hindiNumberMap[token]`.
2. If `num != null`, check whether `tokens.getOrNull(i - 1)?.lowercase()` or
   `tokens.getOrNull(i + 1)?.lowercase()` is contained in `rupeeWords` (exact match, not
   substring). If so, this is a **candidate confirmed price** at index `i`, value `num`.
3. Collect all confirmed candidates; if more than one exists, take the **last** one in the
   transcript (rightmost index) as `spokenPrice`, and record its index as `spokenPriceIndex`.
   Also record the adjacent rupee-word token's index so it can be excluded from item-name
   extraction (see 3.5).
4. If **no** confirmed candidate was found, fall back to checking the *old* heuristic purely to
   set `hasAmbiguousPriceNumber = true` (do **not** set `spokenPrice`):
   - last token is numeric, and either the first token is also numeric with ≥3 tokens total, or
     the numeric value is `≥ 10.0`.
   - If this fallback matches, set `hasAmbiguousPriceNumber = true`; leave `spokenPrice = null`
     and `spokenPriceIndex = -1`.
5. Everything downstream (leading/trailing qty scanning, `i == spokenPriceIndex` exclusion, etc.)
   stays structurally the same as today, just driven off the new `spokenPriceIndex`.

Add `hasAmbiguousPriceNumber: Boolean` to the returned `ExtractedQtyTuple` data class and
propagate it up to `parseUtterance`.

### 3.4 Classify `priceIntent` in `parseUtterance`

Replace the current price block (current L104-118) with logic that computes, in order:

```kotlin
val priceIntent: PriceIntent = when {
    hasAmbiguousPriceNumber -> PriceIntent.AMBIGUOUS_UNTRUSTED
    spokenPrice != null && spokenPrice > 0.0 && !hasLeadingQty -> PriceIntent.RATE_UPDATE
    spokenPrice != null && spokenPrice > 0.0 && hasLeadingQty -> PriceIntent.BULK_SALE_TOTAL
    else -> PriceIntent.NONE
}
```

Then derive `total`, `priceOverridden`, `updatedUnitPrice`, `isPendingPrice` from `priceIntent`:

- `RATE_UPDATE`: `updatedUnitPrice = spokenPrice` (this **is** the rate directly — do not divide
  by quantity, since there is no quantity). `total = 0.0` (no sale). `priceOverridden = true`.
  `isPendingPrice = false`.
- `BULK_SALE_TOTAL`: `total = spokenPrice`. `updatedUnitPrice = if (extractedQty > 0.0) total /
  extractedQty else total` (same as today's formula — this is the derived per-unit price *for
  this transaction's `priceAtSale` field only*, never written to the catalog unless the
  brand-new-item exception in §2 applies). `priceOverridden = true`. `isPendingPrice = false`.
- `AMBIGUOUS_UNTRUSTED`: `total = 0.0`. `priceOverridden = false`. `updatedUnitPrice = 0.0`.
  `isPendingPrice = true` (reuse the existing pending-review routing — see §4).
- `NONE`: unchanged from today — `total = calculateTotalForUnits(...)` using the catalog's
  existing price if available, else `isPendingPrice = true`.

### 3.5 Sanity check for large rate jumps

Extend the existing `isSanityFlagged` computation (current L86-88) to also flag when:

```kotlin
(priceIntent == PriceIntent.RATE_UPDATE || priceIntent == PriceIntent.BULK_SALE_TOTAL) &&
catalogPrice > 0.0 &&
Math.abs(updatedUnitPrice - catalogPrice) / catalogPrice > 0.5
```

(Only meaningful for `BULK_SALE_TOTAL` when the brand-new-item exception doesn't apply — for an
existing item this still computes a useful sanity signal even though `BULK_SALE_TOTAL` won't
write it to the catalog, because a wildly-off *implied* rate on a bulk sale is also a sign the
STT mis-heard the number.)

### 3.6 Item-name extraction cleanup

In `extractRawProductName` (current L167-196), extend the token-filtering `contains` checks to
cover the full `rupeeWords` set (today it only filters `rs`/`rupees`/`रुपये`), so words like
"rupay", "rupaya", "रुपया", "रु" etc. don't leak into an extracted item name for unlisted items.

---

## 4. Consumer changes

### 4.1 `BackgroundSttProcessor.kt` (the real voice pipeline — main focus)

Around the existing "Smart Auto-Confirm Logic" block (current L332-373):

- Read `saleResult.priceIntent` (propagate through the multi-sale detector / carryover logic same
  as `matchedItem`/`estimatedTotal` already are — check `MultiSaleDetector` if it wraps
  `VoiceParser.parseUtterance` and forwards fields; add `priceIntent` there too if needed).
- **`RATE_UPDATE` branch** (new): if `item != null && !saleResult.isSanityFlagged`, directly
  update the catalog:
  ```kotlin
  db.catalogDao().insertOrUpdate(item.copy(price = saleResult.updatedUnitPrice, synced = false))
  ```
  Do **not** insert a `TransactionRecord`. Record in `itemOutcomeJson` a field
  `"outcomeType": "RATE_UPDATE"` instead of creating a sale outcome, so the diagnostic trace and
  `DiagnosticLogsScreen` can show it accurately (it is not a ledger entry).
  If `isSanityFlagged` is true, do **not** auto-apply — fall through to the same "leave as PARSED
  for review" path used for low-confidence items today (job status `PARSED`, not
  `AUTO_CONFIRMED`).
- **`BULK_SALE_TOTAL` branch**: existing `isAutoConfirmable` logic runs mostly as-is
  (`saleResult.estimatedTotal > 0.0`, confidence gate, etc.) and inserts the `TransactionRecord`
  with `priceAtSale = saleResult.updatedUnitPrice` (the per-transaction derived rate) — **do not**
  call `catalogDao().insertOrUpdate` for this branch at all, *except* when `item.price == 0.0`
  (brand-new item, per §2's exception), in which case also update the catalog price the same way
  as the `RATE_UPDATE` branch does.
- **`AMBIGUOUS_UNTRUSTED` branch**: `isAutoConfirmable` must already evaluate to `false` here
  because `isPendingPrice == true` (existing gate at current L337 `!saleResult.isPendingPrice`
  already handles this once §3.4 sets `isPendingPrice = true` correctly — verify this, no other
  change should be needed) — confirm the job lands with status `PARSED` and shows up in
  `PendingConfirmationsSheet` same as today's low-confidence items.
- **`NONE`**: unchanged, existing behavior.

### 4.2 `MainActivity.kt` `onConfirmSale` (L149-179, manual quick-stepper + typed-text fallback)

Replace the `finalPrice` computation (current L153-159) with the same branch logic as §4.1:
- `RATE_UPDATE` → update `targetItem.price = parsedSale.updatedUnitPrice`, but **do not** create a
  `TransactionRecord` at all — early-return after persisting the catalog update (add a UI toast
  such as `"${targetItem.name} rate updated to ₹${finalPrice}"` if easy to wire from this call
  site; otherwise skip the toast, it's not required for correctness).
- `BULK_SALE_TOTAL` → keep existing transaction-record creation, but only call
  `catalogDao().insertOrUpdate` when `targetItem.price == 0.0` (brand-new item). Otherwise skip
  the catalog write entirely (this removes the corruption bug for this path).
- `AMBIGUOUS_UNTRUSTED` → this path is reached via the manual "Type sale manually" dialog
  (`ConfirmSaleDialog`), which already lets the user directly type/confirm values, so treat it the
  same as `NONE` here (user is explicitly confirming through a UI form, not blind voice trust) —
  no special handling needed beyond not crashing.

### 4.3 `HomeScreen.kt` `onConfirmJob` (L356-412, Pending Confirmations sheet)

No structural change required — this path already only writes to `catalogDao()` when the item
didn't previously exist (safe, matches §2's exception). Leave as-is. Items that land here via the
new `AMBIGUOUS_UNTRUSTED` classification will display using `job.parsedItemName` /
`job.parsedQty` / `job.parsedTotal` same as any other pending job today — no UI change needed.

---

## 5. Test plan

Add these cases to `app/src/test/java/com/voicetoinvoice/app/VoiceParserTest.kt` (follow the
existing test style — see e.g. `testTier1_HindiNumberWordParsing`, `testSmartUnitFallback_NoExplicitUnit`
in that file for the pattern of asserting `matchedItem?.name`, `quantity`, `unit`, `estimatedTotal`).

| Test name | Input | Assert |
|---|---|---|
| `testPriceIntent_RateUpdate_NoQuantity` | `"aloo pachaas rupay"` | `priceIntent == RATE_UPDATE`, `updatedUnitPrice == 50.0`, `estimatedTotal == 0.0` |
| `testPriceIntent_RateUpdate_ExplicitRsKeyword` | `"aloo 20 rs"` | `priceIntent == RATE_UPDATE`, `updatedUnitPrice == 20.0` |
| `testPriceIntent_BulkSaleTotal` | `"4 kilo aloo 500 rupay"` | `priceIntent == BULK_SALE_TOTAL`, `quantity == 4.0`, `estimatedTotal == 500.0`, `updatedUnitPrice == 125.0` |
| `testPriceIntent_BulkSaleTotal_QtyOne` | `"ek kilo aloo pachaas rupay"` | `priceIntent == BULK_SALE_TOTAL` (quantity was spoken, so it's a sale, not a rate update, even though total==rate numerically — this is intentional, see §2) |
| `testPriceIntent_AmbiguousNoRupeeWord` | `"4 kilo aloo 500"` (no rupee word) | `priceIntent == AMBIGUOUS_UNTRUSTED`, `isPendingPrice == true`, `estimatedTotal == 0.0` |
| `testPriceIntent_None_NoPriceSpoken` | `"4 kilo aloo"` (catalog price prepopulated at ₹20/kg) | `priceIntent == NONE`, `estimatedTotal == 80.0` |
| `testPriceIntent_SanityFlag_LargeRateJump` | catalog price ₹20/kg, input `"aloo 90 rupay"` | `isSanityFlagged == true` |
| `testItemNameExtraction_ExcludesRupeeWordVariants` | unlisted item utterance using `"rupaya"` instead of `"rupees"` | extracted item name does not contain `"rupaya"` |

Run with:

```bash
./gradlew test --tests "com.voicetoinvoice.app.VoiceParserTest"
```

Also do one manual on-device/emulator pass per CLAUDE.md's UI-testing guidance (this is a voice
feature — typing the same phrases into the "Type sale manually" fallback box is an acceptable
substitute for a full mic pass, but at least one real mic-hold recording of a `RATE_UPDATE`
phrase and one `BULK_SALE_TOTAL` phrase should be tested against a running emulator/device to
confirm the real `BackgroundSttProcessor` path behaves as designed, not just the parser unit
tests).

---

## 6. Non-Goals (explicitly rejected — do not build)

- **Reusable/remembered bulk-price tiers** (e.g. storing "4kg → ₹500" as a standing rule so it
  auto-applies next time 4kg is sold with no price spoken). Rejected because vegetable/mandi
  prices change daily and bulk deals are usually customer-specific/negotiated, not a fixed catalog
  policy — remembering a stale bulk price would reintroduce silent wrong-price risk, just delayed
  by days instead of one sale. No `CatalogItem` schema change is needed for this plan.
- Any new settings/management UI for bulk pricing.

---

## 7. Phase 2 (deferred — do not implement unless asked)

`supabase/functions/process-voice-job/index.ts` currently has no deterministic price parser at
all (see `list_tables`/code around L714-739) — pricing there is always `matched.price × qty`
using the existing catalog row, with the AI step (Grok, step 4) as the only source of any
alternative price. Mirroring this plan server-side means updating the Grok prompt to encode the
same rupee-word-gate + rate-vs-bulk distinction, and gating `catalog_items` UPDATE statements the
same way `RATE_UPDATE` is gated client-side. This is materially different work (prompt engineering
vs. deterministic parsing) and should be scoped separately after the client-side behavior above is
verified and stable.

---

## 8. Required repo housekeeping (per this repo's `CLAUDE.md`)

Once implemented and verified (at minimum: unit tests pass, one manual emulator pass done), add a
new entry to `Docs/audit.md` under "🟢 RESOLVED ISSUES" using the next sequential `ISSUE-NNN`
number (check the highest existing number in that file first — as of this writing the highest is
`ISSUE-011`, so this would be `ISSUE-012`), dated with the date the change actually lands, in the
exact Symptom/Root Cause/Resolution/Verification Date format already used by every other entry in
that file. State plainly which parts were actually verified (unit tests run? emulator tested?) vs.
not, per that document's existing convention — do not imply testing that didn't happen.

If `AppDatabase` version or any confidence/threshold constant listed in `Docs/audit.md` §1
("Ground-Truth Source-Code Verified Constants") changes as part of this work, update that table
too — nothing in this plan requires a schema/version bump, so that table likely doesn't need
changes, but verify before finishing.
