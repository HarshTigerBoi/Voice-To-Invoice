# Assistant answers the wrong question — understand the sentence, then query the ledger

**Job:** `769cebef-436d-4cff-93b9-4767470786d7` (2026-08-11 17:41:28 UTC)
**Asked:** "आज उधार पर कितना सामान बेचा" (*how much did I sell on credit today*)
**Answered:** "आज Baingan नहीं बिका" (*no Baingan sold today*)

**Issues to open:** ISSUE-125 (assistant read-path has no semantic layer), ISSUE-126 (numeral
rejoin merges ordinary words), ISSUE-127 (assistant captures file review rows). Highest
existing is ISSUE-124.

**Scope decided with the user, 2026-08-11:**

- **Questions only this pass.** The sale-booking path keeps its deterministic parser. It gets
  exactly one narrow guard (Part C) and nothing else. Sales get their own pass later.
- **The keyword template tier is deleted, not repaired.** One path: understand the question, or
  say so. No silently-wrong fallback.

---

## 1. Evidence

Marked **verified** (checked against the live system or run through the real code just now),
**inferred** (follows from code read, not executed), or **assumed**.

### 1.1 Transcription was perfect. Nothing downstream tried to understand it.

**Verified** — the `stt_job_logs` row:

```
raw_transcript   = "आज उधार पर कितना सामान बेचा"      <-- correct, word for word
parsed_item_name = "Baingan"   parsed_qty = 74   parsed_total = 2960
```

Both STT engines agreed and both were right; all three adaptive re-decode passes returned the
same correct sentence. **STT is not implicated.** The client reads that column
(`SttWorker.kt:797`, `:820`) and passes it to `handleAssistantJob`, so the wrong answer was
produced *from the correct sentence*.

### 1.2 The only model prompt in the pipeline extracts groceries. There is no "what does this mean" prompt.

**Verified** — the system prompt at `index.ts:1520-1592` is a sale line-item extractor, and its
entire output schema is:

```
{ "parsed_items": [ { item_name, quantity, unit, price_at_sale, total, price_intent, confidence, matched_catalog } ] }
```

An interrogative sentence was handed to that prompt. It returned nothing usable, so
`parseSource` fell to `segmenter_fallback` (`index.ts:1761-1764`) and the deterministic
**sales** segmenter parsed the question as merchandise: 74 kg Baingan, ₹2960. The question then
travelled to the client and hit keyword templates.

At no point did anything in the pipeline ask what the sentence *meant*.

### 1.3 The system flagged its own uncertainty and had nowhere to send it.

**Verified** — the trace records `needsArbitration: true`, `confidence: 0.5`, with `READ_QUERY`
and `CREDIT_SALE` tied at 1.0. Grepping both sides, `needsArbitration` has **zero consumers**:

```
server  intent_router.ts:409   assigned
        index.ts:2418          written to the trace
client  IntentRouter.kt:125    assigned
        SttWorker.kt:609-610   classify() called, .intent switched on — confidence never read
```

A 0.50 coin-flip is routed identically to a 0.95 certainty. The arbitration stage the field is
named for does not exist.

### 1.4 What the template tier actually did

`QuestionTemplates.answerQuestion` is a first-match-wins cascade over phonetic keyword lists.
Running the production phone-key implementation over the real transcript and the real catalog
reproduces the production answer exactly (**verified**):

```
utterance : आज उधार पर कितना सामान बेचा
candidate : "पर सामान"      <-- उधार stripped as a stopword; बेचा stripped as ≈बचा (d=0.125)
resolved  : Baingan @ 0.3333  (runner-up Lahsun @ 0.3889 — margin 0.056, no margin guard exists)
answer    : formatItemSales("Baingan", 0.0, 0.0) -> "आज Baingan नहीं बिका"
```

Row 1 below is **verified** end to end. Rows 2-4 are **inferred**: each entity resolution was run
through the real matcher and is verified; the resulting spoken string follows from reading the
branch order and was not reproduced on a device.

| Utterance | Template tier answers | Correct |
|---|---|---|
| आज उधार पर कितना सामान बेचा | "आज Baingan नहीं बिका" | credit sales today |
| आज कुल उधार कितना है | `कुल` → **kela @0.25** → "आज kela नहीं बिका" | total receivables |
| आलू कितना बचा है (*how much is LEFT*) | how much Aaloo **sold** | Aaloo **stock** |
| रमेश का उधार कितना है | whole-shop revenue | Ramesh's balance |

The receivables, per-customer and stock branches are unreachable for any question containing
`कितना`. There has also never been a template for "how much did I sell on credit in a period" —
the asked question had no answer to route to. **This is why the tier is being deleted rather
than tuned: the failure is that intent was being inferred from keyword distances at all.**

### 1.5 Server defect — the rejoin invented a quantity from two ordinary words

**Verified** — reproduced locally against deployed source, identical to the production trace to
the last decimal:

```
$ deno run --allow-read repro.ts   # segmentTranscript("आज उधार पर कितना सामान बेचा", catalog)
numeralRejoins: [{ leftToken: "आज", rightToken: "उधार", mergedSurface: "chauhattar",
                   value: 74, matchNorm: 0.14285714285714285,
                   valueMargin: 0.07142857142857142, lowMargin: true }]
normalizeTranscript: "chauhattar कितना कितना सामान बेचा"
```

`rejoinFragmentedNumerals` merged **आज** + **उधार** into **चौहत्तर = 74**. Its left-token guard
rejects only numbers, digits, units, distance units, rupee words and catalog surfaces — ordinary
vocabulary passes, and the joined key `ACAOTAL` sits 0.143 from `chauhattar`'s `CAOATAL`, inside
`MERGE_MAX_NORM = 0.22`. The right-hand token is never checked at all.

The low-margin flag held the line (confidence 0.30, no auto-confirm) and **verified** no
`transactions` row was written — a near miss, not a loss. On a `SALE` capture the same merge
yields a plausible quantity with no such tell.

**Verified, and my first draft got this wrong:** this function is **mirrored in Kotlin** at
`OrderingSegmenter.kt:734-818`. A server-only fix leaves the client half live. The two have also
already drifted — the Kotlin guard omits `RUPEE_WORDS`, which the TypeScript one has.

### 1.6 Server defect — every spoken question files a review row

**Verified** — `unmatched_queue`, last five rows; three are questions:

```
job 769cebef  "आज उधार पर कितना सामान बेचा"     item Baingan  qty 74  total 2960  PENDING
job c6fc330a  "आज टोटल सेल्स कितने की हुईं?"      item आज       qty 1   total 0     PENDING
job e7ee6c85  "आज बैंगन कितने बिके"              item Baingan  qty 1   total 0     PENDING
reason: "Safety fallback: job produced 0 ledger/review rows (intent: ASSISTANT, status: PARSED)"
```

`index.ts:2693-2712` files a fallback row whenever a job books nothing. For an `ASSISTANT`
capture, booking nothing is the correct outcome.

**Verified, and it bounds the severity:** these rows are server-side only. Sync is push-only, so
they never reach Room; `HealthScore.kt:97` counts the *local* queue and is unaffected;
`MainActivity.kt:269` collects `unmatchedState` and never uses it. Supabase-side pollution and
misleading analytics — **not** a one-tap wrong sale.

### 1.7 A diagnostic trap, so nobody re-derives it

`step_2_stt_proxy_response.rawTranscript` in the trace reads `"chauhattar कितना कितना सामान बेचा"`.
That is **not** raw STT output — it is `normalizeTranscript()`, defined as *segment the transcript
and re-join the segments* (`phonetic.ts:1235-1239`), so it carries the rejoin corruption. The
genuine engine outputs are `grokTranscript` / `sarvamTranscript`. Reading that field as "what the
mic heard" sends you hunting an STT bug that does not exist.

---

## 2. Architecture

The read path becomes four stages with one hard rule: **the model produces a query, never a
number.** Every figure spoken comes from Room. This is `master_build_plan.md` §4.1 ("AI never
invents a number") applied to reads, and it is what makes handing intent to a model safe.

```
transcript ──► UNDERSTAND (server, one Grok call)
                 └─► LedgerQuery { metric, period, item?, customer?, confidence }   no figures
                       │
                       ├─► BIND (client)      spoken surface → catalog / customer row, phonetically
                       ├─► EXECUTE (client)   LedgerQueries → Room/SQL. every number originates here
                       └─► COMPOSE (client)   ResponseComposer, naming what was answered
```

Three consequences worth stating up front:

- **Phonetics keeps the job it is good at** — matching a mis-heard *item name* to a catalog row,
  a genuinely acoustic problem — and stops being asked to infer intent, which it was never
  suited for.
- **Execution stays on-device.** Room is the source of truth and Supabase is a mirror
  (CLAUDE.md), so the server must not compute the answer even though it does the understanding.
- **Periods come free.** "इस हफ्ते", "पिछले महीने", "कल" become a `period` field instead of every
  template hardcoding today.

### Where understanding runs, and what it costs

Server-side in `process-voice-job`, inside the round trip the client already waits on. For an
`ASSISTANT` capture that resolves to a question, the semantic call **replaces** the item-
interpretation call rather than adding to it — extracting groceries from a question is wasted
work (§1.2 shows it returned nothing). Expected net latency change ≈ 0; to be confirmed by
`step_8_timings` after deploy, not assumed.

### What happens when the model is unavailable

The assistant says "समझ नहीं आया, फिर से बोलिए" and answers nothing. This is the deliberate
consequence of deleting the template tier. Note the fallback was worth less than it looks:
transcription itself is server-side (on-device STT reported `unavailable` in this trace), so
"offline with a transcript" is already rare. The real exposure is a slow or failed model call,
which is why this call gets its own **6 s** timeout instead of the shared 12 s
(`AI_CHAT_TIMEOUT_MS`) — a question that fails fast is much better than one that hangs.

---

## 3. The `LedgerQuery` contract

Closed vocabulary. The executor switches on `metric` exhaustively; an unknown value is a bug,
not a fallback.

```jsonc
{
  "kind": "QUERY" | "NOT_A_QUERY",
  // QUERY only:
  "metric": "SALES_TOTAL" | "ITEM_SALES" | "CREDIT_SALES" | "RECEIVABLES_TOTAL"
          | "CUSTOMER_BALANCE" | "STOCK_ON_HAND" | "LOW_STOCK" | "STOCK_VALUE"
          | "PROFIT" | "WASTE_VALUE" | "TOP_ITEM" | "SLOWEST_ITEM" | "UNSUPPORTED",
  "period": { "kind": "TODAY"|"YESTERDAY"|"THIS_WEEK"|"THIS_MONTH"|"LAST_N_DAYS"|"ALL_TIME",
              "n": <integer, only for LAST_N_DAYS> },
  "item":     "<item name exactly as spoken, or null>",
  "customer": "<customer name exactly as spoken, or null>",
  "confidence": <0.0-1.0>,
  "unsupported_reason": "<short phrase, only when metric is UNSUPPORTED>",
  // NOT_A_QUERY only:
  "booking_intent": "SALE"|"CREDIT_SALE"|"PAYMENT_RECEIVED"|"STOCK_IN"|"RETURN"|"WASTE"
                  |"EXPIRY_WRITEOFF"|"PRICE_UPDATE"|"VOID_LAST"|"ACTION_COMMAND"|"UNKNOWN"
}
```

Rules the prompt must enforce, each earning its place:

1. **No figures anywhere except `period.n`.** Not a total, not a balance, not a quantity.
2. **`item` / `customer` are the spoken surface, verbatim.** The model must not guess a catalog
   name — binding is the client's job, against the real catalog.
3. **`UNSUPPORTED` is a first-class answer.** A question the ledger genuinely cannot answer must
   come back as `UNSUPPORTED` with a reason, never as the nearest metric that happens to fit.
4. **`NOT_A_QUERY` when the shopkeeper is recording a transaction**, not asking about one.

### Metric → executor mapping

Every metric already has a method. No new aggregate is needed except credit sales, and even that
reuses an existing DAO query.

| metric | `LedgerQueries` call | composer |
|---|---|---|
| `SALES_TOTAL` | `getSalesBetween(start, end)` | `formatDailySales` |
| `ITEM_SALES` | `getItemSalesInPeriod(item, start, end)` | `formatItemSales` |
| `CREDIT_SALES` | **new** `getCreditSalesInPeriod(start, end)` | **new** `formatCreditSales` |
| `RECEIVABLES_TOTAL` | `getTotalReceivables()` | inline |
| `CUSTOMER_BALANCE` | **new** `getCustomerBalanceWithName(customer)` | inline |
| `STOCK_ON_HAND` | `getStockLevelWithName(item)` | `formatStockReport` |
| `LOW_STOCK` | `getLowStockItems()` | inline |
| `STOCK_VALUE` | `getTotalStockValue()` | inline |
| `PROFIT` | `getProfit(start, end)` | inline, **keep the coverage caveat** |
| `WASTE_VALUE` | `getWasteValue(start, end)` | inline |
| `TOP_ITEM` | `getTopSellingItem(start, end)` | inline |
| `SLOWEST_ITEM` | `getSlowestSellingItem(start, end)` | inline |

`RECEIVABLES_TOTAL`, `LOW_STOCK` and `STOCK_VALUE` are point-in-time; the executor ignores
`period` for them.

---

## 4. Part A — server: understand the question

`supabase/functions/process-voice-job/index.ts`

**A1 — generalise the Grok JSON call.** `callGrokChatInterpretation` (line 358-427) hardcodes
`parsedJson.parsed_items`. Extract the model-chain / timeout / fallback body into:

```ts
async function callGrokJson(
  xaiApiKey: string,
  systemPrompt: string,
  userPrompt: string,
  getKnownGood: () => string | null,
  setKnownGood: (m: string) => void,
  timeoutMs: number = AI_CHAT_TIMEOUT_MS,
): Promise<{ json: any | null; error: string | null; model: string | null }>
```

Move the existing body verbatim, replacing the `parsed_items` extraction with `json = parsedJson`
and `AI_CHAT_TIMEOUT_MS` with the `timeoutMs` parameter. Then reduce
`callGrokChatInterpretation` to a wrapper:

```ts
async function callGrokChatInterpretation(...same args...) {
  const r = await callGrokJson(xaiApiKey, systemPrompt, userPrompt, getKnownGood, setKnownGood)
  return { items: Array.isArray(r.json?.parsed_items) ? r.json.parsed_items : [], error: r.error, model: r.model }
}
```

Every existing call site (lines 1685, 1729, 1797, 2783) is unchanged. The model chain, the
known-good cache, `response_format: json_object`, `temperature: 0` and the reasoning-effort
handling are all preserved by moving the body rather than rewriting it.

**A2 — add the constant** next to `AI_CHAT_TIMEOUT_MS` (line 222):

```ts
/** The assistant is a person mid-task waiting for an answer. A question that fails in 6s and
 *  says so beats one that hangs for 12s — and with the template tier deleted there is no
 *  second-best answer to wait for. */
const ASSISTANT_QUERY_TIMEOUT_MS = Number(Deno.env.get('ASSISTANT_QUERY_TIMEOUT_MS') || '6000')
```

**A3 — the prompt.** Add near the other prompt constants:

```ts
const ASSISTANT_QUERY_SYSTEM_PROMPT = `You read one sentence spoken by an Indian kirana shopkeeper
in Hindi, Hinglish or English, and decide what they are asking their own shop ledger.

You NEVER answer the question. You NEVER produce a total, a balance, a quantity, a price or any
other figure — the app computes every number from its own database. You only say WHAT to look up.

Return "kind": "NOT_A_QUERY" when the shopkeeper is RECORDING something (selling, receiving
stock, taking payment, writing off, changing a price, cancelling) rather than ASKING about it.

Otherwise return "kind": "QUERY" and choose exactly one metric:
- SALES_TOTAL       total money sold in a period        ("आज कितना बिका", "total sales today")
- ITEM_SALES        one named item sold in a period     ("आज कितने आलू बिके")
- CREDIT_SALES      sold ON CREDIT in a period          ("आज उधार पर कितना बेचा")
- RECEIVABLES_TOTAL everything customers still owe      ("कुल उधार कितना है")
- CUSTOMER_BALANCE  what ONE named customer owes        ("रमेश का उधार कितना")
- STOCK_ON_HAND     how much of a named item is LEFT    ("आलू कितना बचा है")
- LOW_STOCK         which items are running out
- STOCK_VALUE       value of all stock on hand
- PROFIT            profit in a period
- WASTE_VALUE       value spoiled in a period
- TOP_ITEM          best seller in a period
- SLOWEST_ITEM      worst seller in a period
- UNSUPPORTED       a real question this ledger cannot answer — say so, with a short reason.
                    NEVER pick a nearby metric to avoid using this.

CREDIT_SALES vs RECEIVABLES_TOTAL is the distinction shopkeepers make constantly and the one you
must get right: "how much did I SELL on credit today" is CREDIT_SALES (a period's sales),
"how much credit is OUTSTANDING" is RECEIVABLES_TOTAL (a standing balance). "उधार पर बेचा" is
always CREDIT_SALES.

"item" and "customer" must be copied EXACTLY as spoken. Do not translate them, do not correct
them, do not guess a catalog name — the app matches them against the real catalog itself.

Set confidence below 0.6 when you are genuinely unsure. A low confidence makes the app ask the
shopkeeper to repeat, which is always better than confidently looking up the wrong thing.

Output ONLY valid JSON in exactly this shape:
{"kind":"QUERY","metric":"...","period":{"kind":"TODAY","n":null},"item":null,"customer":null,
 "confidence":0.0,"unsupported_reason":null,"booking_intent":null}`
```

**A4 — call it.** Place immediately after the transcript is final (after the adaptive re-decode
block ends, ~line 1460, where `chosenRaw` stops changing) and **before** the item-interpretation
block at 1470:

```ts
    let semanticQuery: any = null
    let semanticQueryError: string | null = null
    let semanticQueryModel: string | null = null
    if (isAssistant && xaiApiKey && chosenRaw.trim()) {
      const sq = await callGrokJson(
        xaiApiKey, ASSISTANT_QUERY_SYSTEM_PROMPT,
        `Shopkeeper said: "${chosenRaw}"`,
        () => knownGoodChatModel, (m) => { knownGoodChatModel = m },
        ASSISTANT_QUERY_TIMEOUT_MS,
      )
      semanticQuery = sq.json
      semanticQueryError = sq.error
      semanticQueryModel = sq.model
    }
    const isAnsweredQuestion = semanticQuery?.kind === 'QUERY'
```

Then gate the item-interpretation block (`const hasAiInput = ...`, line 1470) on
`&& !isAnsweredQuestion`. A question needs no grocery extraction — this is what keeps the added
latency near zero.

**Do not skip the deterministic segmenter (`step3Segments`).** It still feeds the trace and the
`NOT_A_QUERY` path, and disabling it here would be an unverified change to the booking path,
which is out of scope this pass. Part C is what stops it corrupting transcripts.

**A5 — write it to the trace.** Add to the trace object built around line 2418:

```ts
      step_2c_semantic_query: {
        attempted: isAssistant && !!xaiApiKey,
        query: semanticQuery,
        error: semanticQueryError,
        model: semanticQueryModel,
        skippedItemInterpretation: isAnsweredQuestion,
        // Shadow mode: the deterministic router still owns booking this pass. Recording
        // where the two disagree is the evidence base for handing it over next pass.
        deterministicIntent: assistantClassification?.intent ?? null,
        deterministicConfidence: assistantClassification?.confidence ?? null,
        agreesWithDeterministic: semanticQuery?.kind === 'QUERY'
          ? assistantClassification?.intent === 'READ_QUERY'
          : semanticQuery?.booking_intent === assistantClassification?.intent,
      },
```

The trace is the transport. The client already reads structured control data out of it —
`step_4_grok_ai_interpretation` is pulled from `diagnostic_trace_json` in both the inline path
(`SttWorker.kt:190-198`) and the poll path (`:822-828`). Following that precedent means **no
Postgres migration, no new column, no change to the poll's select list**. It is not a beautiful
transport; it is the one this codebase already uses for exactly this, and inventing a second one
for a single field is the worse trade.

**A6 — stop filing review rows for assistant captures.** At the safety-net fallback (line 2696):

```ts
    // An ASSISTANT capture that books nothing is the correct outcome for a question, not a
    // failure to book. Filing a fallback row put the shopkeeper's own questions in the review
    // queue with a fabricated line ("आज उधार पर कितना सामान बेचा" -> Baingan 74 KG ₹2960,
    // job 769cebef). ISSUE-127.
    const assistantAnsweredNothingToBook = isAssistant && !assistantNeedsReview
    if (committedCount === 0 && unmatchedRowsWritten === 0 && !assistantAnsweredNothingToBook) {
```

`assistantNeedsReview` already exists at line 2300. The safety net stays fully intact for every
non-assistant capture and for assistant captures classified into a booking intent needing review.

Then, per the standing authorization in CLAUDE.md, deploy immediately and re-verify the live
bundle:

```bash
npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr
```

---

## 5. Part B — client: bind and execute

### B1. Delete `app/src/main/java/com/voicetoinvoice/app/domain/query/QuestionTemplates.kt`

The whole file. Its only call site is `SttWorker.kt:613`, replaced below. Delete
`ResponseComposer.formatUnrecognized`'s *keyword-tier* callers, not the function — the executor
still uses it.

### B2. New — `app/src/main/java/com/voicetoinvoice/app/domain/query/LedgerQuery.kt`

Pure data + parsing, no Android and no database dependency, so it is unit-testable:

```kotlin
package com.voicetoinvoice.app.domain.query

enum class QueryMetric {
    SALES_TOTAL, ITEM_SALES, CREDIT_SALES, RECEIVABLES_TOTAL, CUSTOMER_BALANCE,
    STOCK_ON_HAND, LOW_STOCK, STOCK_VALUE, PROFIT, WASTE_VALUE, TOP_ITEM, SLOWEST_ITEM,
    UNSUPPORTED
}

enum class PeriodKind { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, LAST_N_DAYS, ALL_TIME }

data class QueryPeriod(val kind: PeriodKind, val n: Int? = null) {
    /** Resolved against the device clock at answer time, never the server's. */
    fun windowMs(nowMs: Long = System.currentTimeMillis()): Pair<Long, Long> { /* Calendar math */ }
}

data class LedgerQuery(
    val metric: QueryMetric,
    val period: QueryPeriod,
    val item: String?,
    val customer: String?,
    val confidence: Double,
    val unsupportedReason: String?,
) {
    companion object {
        /** Below this the assistant asks the shopkeeper to repeat rather than look up
         *  something it is guessing at. Mirrors the prompt's own instruction in §4 A3. */
        const val MIN_CONFIDENCE = 0.6

        /** Reads `step_2c_semantic_query.query` out of the merged trace. Returns null for a
         *  missing key, `kind != "QUERY"`, an unknown metric, or malformed JSON — every one
         *  of those means "we did not understand", which is an honest answer. */
        fun fromTraceJson(traceJson: String): LedgerQuery?
    }
}
```

`fromTraceJson` must use `enumValueOf` inside a `try/catch`, never a `when(string)` with an
`else ->` that silently picks a metric. An unrecognised metric is "not understood".

### B3. New — `app/src/main/java/com/voicetoinvoice/app/domain/query/LedgerQueryExecutor.kt`

```kotlin
class LedgerQueryExecutor(private val ledgerQueries: LedgerQueries) {
    /** Every number spoken by the assistant originates in this method, from Room. The model
     *  chose WHICH question; it never supplied an answer. */
    suspend fun execute(query: LedgerQuery): String
}
```

Dispatch exhaustively on `QueryMetric` per the §3 table (a `when` with no `else`, so a new
metric fails the build instead of falling through). Rules:

- **Below `MIN_CONFIDENCE`** → `ResponseComposer.formatUnrecognized()`. Do not look up.
- **`UNSUPPORTED`** → `"यह मैं अभी नहीं बता सकता"`. Honest, and distinct from "I didn't hear you".
- **`ITEM_SALES` / `STOCK_ON_HAND` with an unresolvable item** → `"$item नाम का कोई आइटम नहीं मिला"`.
  Safe to echo here, unlike in the deleted tier: the surface comes from a model reading a correct
  transcript, not from stopword debris.
- **`CUSTOMER_BALANCE` with an unresolvable customer** → `"$customer नाम का कोई ग्राहक नहीं मिला"`.
- **`PROFIT`** keeps `ProfitCalculator`'s coverage caveat verbatim — a confidently wrong profit
  costs a shopkeeper's trust permanently.
- **Keep the `LedgerSnapshot` fast path** where it already exists (`isFresh()` → `todayTotal`,
  `topItemToday`, `stockByItemName`, `outstandingByCustomerName`) and only when
  `period.kind == TODAY`. The snapshot is today-scoped; using it for any other window is wrong.

### B4. `LedgerQueries.kt` — binding guards and one new aggregate

**B4.1 — margin guard on `findCatalogItem`** (lines 133-147). Today it returns the nearest
neighbour under 0.34 with no notion of how close the runner-up was, which is how `पर सामान`
became Baingan. Binding a model-supplied surface has the same exposure.

```kotlin
    private suspend fun findCatalogItem(query: String): CatalogItem? {
        val catalog = db.catalogDao().getActiveCatalogList()
        if (catalog.isEmpty()) return null
        catalog.find { it.name.equals(query, ignoreCase = true) }?.let { return it }
        // Substring only for queries long enough to be a name: a 2-char query is a substring
        // of half the catalog.
        if (query.length >= MIN_SUBSTRING_QUERY_LEN) {
            catalog.find { it.name.contains(query, ignoreCase = true) }?.let { return it }
        }
        val queryKey = PhoneticKey.of(query)
        if (queryKey.length < MIN_QUERY_PHONES) return null
        val ranked = catalog
            .map { it to PhoneticKey.normalizedDistance(queryKey, PhoneticKey.of(it.name)) }
            .sortedBy { it.second }
        val best = ranked.first()
        if (best.second > MAX_ENTITY_DISTANCE) return null
        // Margin against the nearest DIFFERENTLY-NAMED rival. Comparing against ranked[1]
        // blindly would return null for every duplicated catalog name.
        val rival = ranked.firstOrNull { !it.first.name.equals(best.first.name, ignoreCase = true) }
        val margin = if (rival != null) rival.second - best.second else 1.0
        if (margin < MIN_ENTITY_MARGIN) return null
        return best.first
    }
```

**B4.2 — the constants**, in a `companion object` on `LedgerQueries`:

```kotlin
    companion object {
        /** Nearest-neighbour ceiling. Unchanged — the ceiling was never the problem. */
        private const val MAX_ENTITY_DISTANCE = 0.34
        /** How far clear the winner must be. `पर सामान` matched Baingan at 0.3333 with Lahsun
         *  0.056 behind it, and `कुल` tied kela and Kheera at 0.25 — both junk, both under the
         *  ceiling. Measured against 7 genuine item questions the smallest true-positive margin
         *  is टमाटर→Tamatar at 0.143, so 0.08 clears every real match with room to spare. */
        private const val MIN_ENTITY_MARGIN = 0.08
        private const val MIN_QUERY_PHONES = 3
        private const val MIN_SUBSTRING_QUERY_LEN = 3
    }
```

**B4.3 — same guards for customers, and return the canonical name** (replacing lines 95-103):

```kotlin
    suspend fun getCustomerBalance(customerNameQuery: String): Double? =
        getCustomerBalanceWithName(customerNameQuery)?.second

    /** Resolved customer plus balance, so the answer speaks the customer's real name rather
     *  than echoing back whatever was heard. Same margin guard as [findCatalogItem]. */
    suspend fun getCustomerBalanceWithName(customerNameQuery: String): Pair<String, Double>? =
        withContext(Dispatchers.IO) {
            val customers = db.customerDao().getActiveCustomersList()
            if (customers.isEmpty()) return@withContext null
            val queryKey = PhoneticKey.of(customerNameQuery)
            if (queryKey.length < MIN_QUERY_PHONES) return@withContext null
            val ranked = customers
                .map { it to PhoneticKey.normalizedDistance(queryKey, it.phoneticKey) }
                .sortedBy { it.second }
            val best = ranked.first()
            if (best.second > MAX_ENTITY_DISTANCE) return@withContext null
            val rival = ranked.firstOrNull { !it.first.name.equals(best.first.name, ignoreCase = true) }
            val margin = if (rival != null) rival.second - best.second else 1.0
            if (margin < MIN_ENTITY_MARGIN) return@withContext null
            best.first.name to CustomerBalance(db).balanceFor(best.first.id)
        }
```

**B4.4 — the one new aggregate**, next to `getSalesBetween` (line 33):

```kotlin
    /** Revenue booked on credit in a window — "आज उधार पर कितना बेचा". Distinct from
     *  [getTotalReceivables], which is the standing balance across all time. RETURN rows carry
     *  a negative `total`, so the sum nets returns-against-credit without a special case. */
    suspend fun getCreditSalesInPeriod(startMs: Long, endMs: Long): Double = withContext(Dispatchers.IO) {
        db.transactionDao().getTotalByPaymentMode("CREDIT", startMs, endMs)
    }
```

`getTotalByPaymentMode` already exists (`TransactionDao.kt:141`) and is already called with the
literal `"CREDIT"` by `ReportsScreen.kt:109`; `Converters.fromPaymentMode` stores the enum by
`.name`. **No DAO change, no Room migration, no `AppDatabase` version bump.**

### B5. `ResponseComposer.kt`

```kotlin
    fun formatCreditSales(totalAmount: Double): String {
        val amountInt = totalAmount.toInt()
        return if (amountInt > 0) "आज ₹$amountInt का सामान उधार पर बिका"
        else "आज उधार पर कुछ नहीं बिका"
    }
```

Every composer string must **name what it answered** ("उधार पर", "स्टॉक", the item name). A
mis-chosen metric is the residual risk of this design (§8), and phrasing is what makes it audible
to the shopkeeper instead of silent.

### B6. `SttWorker.handleAssistantJob` (lines 585-769)

Replace the `READ_QUERY` branch (609-616):

```kotlin
            val semanticQuery = com.voicetoinvoice.app.domain.query.LedgerQuery.fromTraceJson(traceJson)
            if (semanticQuery != null) {
                val ledgerQueries = com.voicetoinvoice.app.domain.query.LedgerQueries(db)
                answer = com.voicetoinvoice.app.domain.query.LedgerQueryExecutor(ledgerQueries).execute(semanticQuery)
                assistantTier = "semantic"
                finalStatus = SttJobStatus.AUTO_CONFIRMED
            } else {
                val classification = IntentRouter.classify(cleanTranscript, parsedItems)
                when (classification.intent) {
                    AssistantIntent.READ_QUERY -> {
                        // The template tier is gone (ISSUE-125). A question we could not
                        // understand gets an honest answer, not a keyword guess.
                        answer = ResponseComposer.formatUnrecognized()
                        assistantTier = "none"
                        finalStatus = SttJobStatus.PARSED
                    }
                    // ... every other branch UNCHANGED ...
                }
            }
```

`traceJson` is already a parameter of `handleAssistantJob` (line 589) and is already populated in
both the inline and poll paths. **No new plumbing.**

Also add to the client trace next to `assistant_tier` (line 748):
`clientTrace.put("assistant_metric", semanticQuery?.metric?.name ?: "none")`.

**Booking intents stay on the deterministic router this pass.** `semanticQuery` is null whenever
`kind == NOT_A_QUERY`, so those utterances take the existing path byte-for-byte. The model's
opinion is recorded in the trace (§4 A5) and acted on in a later pass, once the agreement rate is
measurable. This is what "questions only" means in code.

---

## 6. Part C — both sides: stop merging ordinary words into numbers

This is the one sale-path change in scope. **It must land on both sides** — the function is
mirrored, and the halves have already drifted (§1.5).

### C1. Server — `supabase/functions/process-voice-job/phonetic.ts`

Add next to `DISCOURSE_PARTICLES` (line 615):

```ts
/** Words that are never half of a fragmented numeral. The rejoin refused only a LEFT token that
 *  was already a number, unit, rupee word or catalog surface — ordinary vocabulary sailed
 *  through, and "आज"+"उधार" merged into "चौहत्तर" (74) at norm 0.143, turning a question into a
 *  74 kg / ₹2960 line. ISSUE-126. Mirrored in OrderingSegmenter.kt — change both. */
export const NEVER_NUMERAL_FRAGMENT: Set<string> = new Set([
  'आज','कल','परसों','अभी','अब','रोज','कितना','कितने','कितनी','क्या','कौन','कब','कहाँ','कहां',
  'उधार','उधारी','खाता','बकाया','बचा','बाकी','सामान','माल','कुल','टोटल','हिसाब','स्टॉक',
  'बेचा','बेची','बिका','बिके','बिकी','मुनाफा','मुनाफ़ा','कमाई','खराब',
  'aaj','kal','abhi','ab','roz','kitna','kitne','kitni','kya','kaun','kab','kahan',
  'udhaar','udhar','udhari','khata','bakaya','bacha','baki','saman','samaan','maal',
  'kul','total','hisaab','stock','becha','bika','bike','munafa','kamai','kharab',
])
```

In `rejoinFragmentedNumerals` (lines 866-877) extend the left guard and add the missing right
guard:

```ts
    if (
      HINDI_NUMBER_MAP[leftLower] !== undefined ||
      /^\d+(\.\d+)?$/.test(leftLower) ||
      UNIT_SET.includes(leftLower) ||
      DISTANCE_UNIT_TOKENS.includes(leftLower) ||
      RUPEE_WORDS.has(leftLower) ||
      itemSurfaceSet.has(leftLower) ||
      NEVER_NUMERAL_FRAGMENT.has(leftLower) ||
      DISCOURSE_PARTICLES.has(leftLower)
    ) { out.push(left); continue }

    // The RIGHT half was never checked at all. A known word is a word, not the tail of a broken
    // numeral — this is the half that turned "उधार" into "-हत्तर".
    const rightLower = right.toLowerCase()
    if (
      NEVER_NUMERAL_FRAGMENT.has(rightLower) ||
      DISCOURSE_PARTICLES.has(rightLower) ||
      itemSurfaceSet.has(rightLower)
    ) { out.push(left); continue }
```

`MERGE_MAX_NORM` stays **0.22** and `MERGE_MIN_VALUE_MARGIN` stays **0.10**. Both are measured
optima documented at `phonetic.ts:607-611`; this narrows *what may be merged*, not how close the
merge must be. Do not touch them.

### C2. Client — `app/src/main/java/com/voicetoinvoice/app/domain/parser/OrderingSegmenter.kt`

Add the identical set beside `DISCOURSE_PARTICLES` (line 589) as
`val NEVER_NUMERAL_FRAGMENT: Set<String> = setOf(...)` — same members, same order, so the two
files diff cleanly. Then in `rejoinFragmentedNumerals` (lines 754-765) apply the same two guards,
**and add the `RUPEE_WORDS` check the Kotlin side is currently missing**:

```kotlin
                if (
                    HINDI_NUMBER_MAP[leftLower] != null ||
                    leftLower.matches(Regex("^\\d+(\\.\\d+)?$")) ||
                    UNIT_SET.contains(leftLower) ||
                    DISTANCE_UNIT_TOKENS.contains(leftLower) ||
                    RUPEE_WORDS.contains(leftLower) ||          // drift fix: TS had this, Kotlin didn't
                    itemSurfaceSet.contains(leftLower) ||
                    NEVER_NUMERAL_FRAGMENT.contains(leftLower) ||
                    DISCOURSE_PARTICLES.contains(leftLower)
                ) { out.add(left); i++; continue }

                val rightLower = right.lowercase()
                if (
                    NEVER_NUMERAL_FRAGMENT.contains(rightLower) ||
                    DISCOURSE_PARTICLES.contains(rightLower) ||
                    itemSurfaceSet.contains(rightLower)
                ) { out.add(left); i++; continue }
```

If `RUPEE_WORDS` does not exist in `OrderingSegmenter.kt`, say so and stop rather than inventing
one — that drift is a finding in its own right.

**Considered and rejected:** requiring the right token to be in `HINDI_NUMBER_MAP`. It kills this
bug outright and preserves every documented ISSUE-106 true positive (`ते`+`तीस`, `दही`+`तीस`,
`हर्ष`+`दस` — the right half is a listed number in all three), but it also blocks a genuine split
like `चौ`+`हत्तर` where neither half is listed. The word-list guard is the conservative choice.

---

## 7. Tests

### 7.1 New — `app/src/test/.../domain/query/LedgerQueryParsingTest.kt`

`LedgerQuery.fromTraceJson` is pure, so this needs no database:

- The real job's trace shape parses to `metric = CREDIT_SALES`, `period.kind = TODAY`,
  `item = null`, `customer = null`.
- `kind: "NOT_A_QUERY"` → `null`.
- Unknown metric string → `null`, **not** a defaulted metric.
- Missing `step_2c_semantic_query` → `null`.
- Malformed JSON → `null`, no throw.
- `QueryPeriod.windowMs` for each `PeriodKind` against a fixed clock — `TODAY` starts at local
  midnight, `LAST_N_DAYS(7)` spans 7 days, `ALL_TIME` starts at 0.

### 7.2 New — `app/src/test/.../domain/query/EntityBindingTest.kt`

The margin guard, over a fixture catalog (`Baingan`, `Lahsun`, `Palak`, `kela`, `Kheera`,
`Aaloo`, `Tamatar`, `Paneer`). These distances were measured against the production phone-key
implementation:

- `"पर सामान"` → no match (best 0.3333, margin 0.056)
- `"कुल"` → no match (kela and Kheera tie at 0.25)
- `"आलू"` → Aaloo · `"बैंगन"` → Baingan · `"टमाटर"` → Tamatar (margin 0.143, the tightest true
  positive measured) · `"पनीर"` → Paneer

### 7.3 New case in `supabase/functions/process-voice-job/phonetic_test.ts`

```ts
Deno.test("rejoinFragmentedNumerals does not merge ordinary words into a numeral", () => {
  const r = segmentTranscript("आज उधार पर कितना सामान बेचा", ["Aaloo","Baingan","Pyaz","Poha","Dhaniya"])
  assertEquals(r.numeralRejoins.length, 0)
})
```

**Verified**: before the fix this fails with `आज`+`उधार` → `chauhattar` (74) at
`matchNorm 0.14285714285714285`. Mirror it in `OrderingSegmenterTest.kt` for the Kotlin half, and
keep every existing ISSUE-106 case green in the same run.

```bash
npx deno test --allow-read supabase/functions/process-voice-job/
```

### 7.4 Existing suites

```bash
./gradlew.bat test
```

`intent_router_test.ts` and `IntentRouterFixtureTest` must both stay green — the deterministic
router is unchanged this pass and its 60-phrase shared fixture is the drift guard.

---

## 8. Verification — by effect, not by build

"BUILD SUCCESSFUL" and "deployed" prove nothing. Deploy the function, ship with
`.\tools\vti-ship.ps1`, then speak each of these and read the result out of the database.

**Understanding:**

```sql
SELECT job_id, raw_transcript,
       diagnostic_trace_json::json #> '{server,step_2c_semantic_query,query}'    AS query,
       diagnostic_trace_json::json #> '{server,step_2c_semantic_query,error}'    AS err,
       diagnostic_trace_json::json #> '{client,assistant_answer}'                AS answer,
       diagnostic_trace_json::json #> '{server,step_8_timings}'                  AS timings
FROM stt_job_logs ORDER BY created_at DESC LIMIT 10;
```

| Say | `metric` must be | Answer must be |
|---|---|---|
| आज उधार पर कितना सामान बेचा | `CREDIT_SALES` | ₹ credit sold today |
| आज कुल उधार कितना है | `RECEIVABLES_TOTAL` | total outstanding |
| रमेश का उधार कितना है | `CUSTOMER_BALANCE` | Ramesh's balance |
| आलू कितना बचा है | `STOCK_ON_HAND` | Aaloo stock, **not** sales |
| आज कितने आलू बिके | `ITEM_SALES` | Aaloo sold today |
| इस हफ्ते कितना बिका | `SALES_TOTAL`, period `THIS_WEEK` | the week's revenue |
| आज का मुनाफ़ा | `PROFIT` | profit **with** its coverage caveat |

Cross-check the credit figure against the ledger — this is the point of the whole design, so
prove the number came from Room:

```sql
SELECT COALESCE(SUM(total),0) FROM transactions
WHERE voided = false AND payment_mode = 'CREDIT' AND timestamp >= '<today 00:00 IST>';
```

**Latency (§2 claims ≈ 0 net):** compare `step_8_timings.totalMs` on a new question against the
5.4 s recorded for job `769cebef`. If it regressed, say so — the claim was a prediction, not a
measurement.

**Part C:** for a question job created *after* the deploy,
`step_3_deterministic_ordering_segmenter.numeralRejoins` must be `[]`, and
`SELECT count(*) FROM unmatched_queue WHERE job_id = '<that job>'` must be **0**.

**Shadow mode:** after a day of use, read `agreesWithDeterministic` across assistant jobs. That
disagreement rate is the evidence for whether the model should own booking intent next pass.

If no post-change row exists, the verification did not happen — say so rather than reporting
success.

---

## 9. Instance vs class

- **The read path's class is eliminated.** Intent is no longer inferred from phonetic keyword
  distance, so the entire family of failures in §1.4 — credit-vs-item, stock-vs-sales,
  customer-vs-shop, unreachable branches, no-template-exists — cannot recur in that form. Periods
  come free with it.
- **The residual risk moves, it does not vanish.** The new failure mode is *the model picks the
  wrong metric*. It is bounded three ways: no number is ever model-supplied, so a wrong metric
  gives a real figure for the wrong question rather than a fabricated one; every answer names
  what it answered, so it is audible; and below 0.6 confidence the assistant declines instead of
  guessing. It is **not** eliminated, and §8's metric table is the ongoing check.
- **Part C patches the instance and narrows the class.** The word lists block the observed merge
  and its obvious neighbours; any Hindi word absent from the lists can still merge with a
  neighbour inside 0.22 of a numeral. Only the right-token-must-be-a-numeral rule would close the
  class, and §6 explains why it was rejected.
- **The booking path is untouched and uncleared.** The segmenter still guesses items
  phonetically on sale captures — the same disease, in the path that writes money. That is the
  next pass, by explicit decision, not because evidence shows it healthy.
- **Part A6 eliminates its class** — no assistant capture can file a fallback review row.

## 10. Open questions

1. **`getTotalByPaymentMode` does not filter `txnType`.** Credit RETURN rows carry a negative
   `total` and net down the credit-sales figure. That is arithmetically right for "how much did I
   sell on credit today" — flagged so it is a decision, not an accident.
2. **Catalog pollution.** `catalog_items` holds rows created by past mis-parses: `पंद्रह`
   (fifteen), `सत्रह की`, `सत्ताईस`, `बचा रहा`, `अठारह के लोग`, `आदा नीला`, `March`, `सिंगर`.
   These are live decoys for the binding step this plan hardens. Not touched here — needs its own
   cleanup plus a guard on catalog auto-creation.
3. **`needsArbitration` remains dead** after this pass. The semantic layer supersedes it for
   questions; for booking intents it is still computed and ignored. Either wire it to the model or
   delete the field — leaving a flag that names a stage which does not exist is how §1.3 happened.

## 11. Log it

Before the turn ends, add ISSUE-125, ISSUE-126 and ISSUE-127 to `Docs/audit.md` §2 under
"🟢 RESOLVED ISSUES" in the existing format (Symptom with job id, numbered Root Cause, numbered
Resolution naming exact files, Verification Date stating plainly what was verified and what was
not). ISSUE-125's Resolution must record that `QuestionTemplates.kt` was **deleted**, not fixed,
and why. Reference the issue numbers in the commit message.
