# Scoped plan 5/11 — C-PART-2: Assistant comprehension (ISSUE-118)

**Scope:** this file ONLY. Client (Kotlin) only. **No edge function, no server change, no model call.**

> **Deliberate narrowing vs. the parent plan.** The parent plan (`Docs/visual_ledger_and_assistant_plan.md` §4, C-PART-2) also specified a new `assistant-query` edge function as an AI fallback tier. That half is **NOT in this scoped plan** and must not be built here. Reason: the free deterministic tier below fixes the one failure actually observed in production, is offline, costs nothing, and cannot invent a number. Adding a network round trip to the assistant's answer path is a separate decision that should be made after seeing whether the deterministic tier is sufficient. Build only what is written below.

---

## 1. The bug, with live evidence

Exactly one true `READ_QUERY` exists across all 399 jobs in `stt_job_logs`:

```
job_id 472d4af1-e438-4f1f-a398-67e240d47362   2026-08-04 19:18:59Z
raw_transcript: "आज कितने आलू बिके"        ("how many potatoes sold today")
step_2b: {"intent":"READ_QUERY","confidence":0.526,...}
```

The router classified it correctly. `QuestionTemplates.answerQuestion` then answered it **wrongly**.

Trace through `domain/query/QuestionTemplates.kt` as it stands:
- branch 1 `TOP_ITEM_WORDS` — miss
- branch 2 `PROFIT_WORDS` — miss
- branch 3 `REVENUE_WORDS` — **HIT**, because that list contains `"बिका"`, which is phonetically ≈ `"बिके"`

So it returns `ResponseComposer.formatDailySales(...)` — *"आज कुल N बिक्री हुई है, कुल ₹X का व्यापार हुआ"*. The shopkeeper asked **how many potatoes** and was told **total shop revenue**.

There is **no item-scoped branch anywhere in the file**. This is worse than answering "I didn't understand": a confidently wrong number is indistinguishable from a right one.

---

## 2. C2.1 — New query method

`app/src/main/java/com/voicetoinvoice/app/domain/query/LedgerQueries.kt`

Add. Note `findCatalogItem(query)` is an existing **private** method in this same class that already does exact → substring → phonetic (≤ 0.34) resolution — reuse it, do not write a second matcher.

```kotlin
/**
 * Quantity and revenue for ONE item in a window.
 *
 * ISSUE-118: "आज कितने आलू बिके" previously fell through to the whole-shop revenue branch
 * because REVENUE_WORDS contains "बिका" (≈ "बिके"), so the shopkeeper was told total business
 * when they asked about one item. Resolution reuses [findCatalogItem] so "aaloo" / "आलू" /
 * a mis-transcribed "alu" all land on the same row.
 *
 * @return (resolvedItemName, qtySold, revenue), or null when the name matches no catalog item.
 */
suspend fun getItemSalesInPeriod(
    itemNameQuery: String,
    startMs: Long,
    endMs: Long
): Triple<String, Double, Double>? = withContext(Dispatchers.IO) {
    val matched = findCatalogItem(itemNameQuery) ?: return@withContext null
    val row = db.transactionDao().getItemSalesBetween(startMs, endMs)
        .firstOrNull { it.itemId == matched.id }
        ?: return@withContext Triple(matched.name, 0.0, 0.0)
    Triple(matched.name, row.qty, row.revenue)
}
```

Returning `(name, 0.0, 0.0)` rather than `null` when the item exists but had no sales is deliberate — "आलू आज नहीं बिका" is a correct, useful answer, whereas `null` would fall through to the wrong-answer path this issue exists to close.

## 3. C2.2 — New response formatter

`app/src/main/java/com/voicetoinvoice/app/domain/voice/ResponseComposer.kt`

```kotlin
fun formatItemSales(itemName: String, qty: Double, revenue: Double): String {
    if (qty <= 0.0) return "आज $itemName नहीं बिका"
    val qtyString = if (qty % 1.0 == 0.0) qty.toInt().toString() else qty.toString()
    return "आज $itemName $qtyString बिका, ₹${revenue.toInt()} का"
}
```

## 4. C2.3 — The branch that fixes it

`app/src/main/java/com/voicetoinvoice/app/domain/query/QuestionTemplates.kt`

Insert a new branch **immediately before the existing `// 3. Today's total sales` / `REVENUE_WORDS` branch** (currently around line 173). Ordering is the entire fix — placed after, it can never fire.

```kotlin
// 3. Item-scoped sales: "आज कितने आलू बिके".
//    MUST precede the REVENUE branch: REVENUE_WORDS contains "बिका", which phone-matches
//    "बिके", so this question was previously swallowed and answered with whole-shop revenue
//    (job 472d4af1, 2026-08-04) -- a confidently wrong number. ISSUE-118.
//    Only fires when a NAMED item actually resolves; otherwise falls through untouched, so
//    a plain "आज कितना बिका" still reaches the revenue branch below.
if (matches(ngrams, REVENUE_WORDS) || matches(ngrams, GENERIC_QUESTION_WORDS)) {
    val candidate = extractCandidateName(clean)
    if (candidate.isNotBlank()) {
        val hit = ledgerQueries.getItemSalesInPeriod(candidate, todayMidnight, nowMs)
        if (hit != null) {
            return ResponseComposer.formatItemSales(hit.first, hit.second, hit.third)
        }
    }
}
```

Then renumber the existing branch comments below it (`// 3.` → `// 4.` … through the end) so the file's numbering stays sequential. Do not change any other branch's logic, order, wording, or thresholds.

**Why this is safe for the common case:** `extractCandidateName` strips question/topic stopwords in all three scripts. For a bare *"आज कितना बिका"* every token is a stopword, so `candidate` is blank and the branch falls through to the existing revenue answer unchanged.

## 5. C2.4 — Trace which tier answered

`app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt`, in `handleAssistantJob`, in the `READ_QUERY` branch (~line 610-613).

`clientTrace` already receives `outcome` and `assistant_answer` further down (~line 745). Add alongside those:

```kotlin
clientTrace.put("assistant_tier", assistantTier)
```

where `assistantTier` is a `var assistantTier = "none"` declared next to `var finalStatus`, set to `"template"` inside the `READ_QUERY` branch. Without this there is no way to tell later which branch produced an answer.

## 6. Regression test

`app/src/test/java/com/voicetoinvoice/app/` — add `QuestionTemplatesItemSalesTest.kt` (or extend an existing QuestionTemplates test if one is already there; check first with a glob for `*QuestionTemplates*`).

Assert, with a fake/in-memory catalog containing "Aaloo":
1. `"आज कितने आलू बिके"` → the answer contains the item name and **not** the whole-shop revenue phrasing (`"का व्यापार हुआ"`).
2. `"आज कितना बिका"` (no item named) → still returns the whole-shop revenue answer, i.e. **contains** `"का व्यापार हुआ"`. This is the regression guard for the fall-through.
3. `"aaj kitne aaloo bike"` (Latin) → same as (1).

If constructing `LedgerQueries` in a unit test requires a real `AppDatabase`, use Room's `inMemoryDatabaseBuilder` in the test rather than mocking, so the phonetic resolution path is genuinely exercised.

---

## Verification (by effect — a passing test is not the bar)

1. `./gradlew.bat test --tests "*QuestionTemplates*"` passes.
2. `./gradlew.bat assembleDebug` compiles.
3. State plainly that the **spoken** end-to-end path was not exercised (no device attached). The real acceptance test, to be run later on the phone, is: say **"आज कितने आलू बिके"** and confirm the reply names the item, with `"assistant_tier":"template"` in the job's client trace.

## Audit log

Add a 🟢 RESOLVED entry for **ISSUE-118** in `Docs/audit.md`'s single `### 🟢 RESOLVED ISSUES` section (there is exactly one — do not create another). Quote job `472d4af1` as the evidence. State explicitly:
- what this fixes (item-scoped "how much of X sold" questions),
- what it does **not** fix (any question shape outside the nine deterministic branches still returns `formatUnrecognized()`; the AI fallback tier was deliberately not built),
- and that on-device verification is still outstanding.

## Deviations

End with a "Deviations" section. If none, say "None."
