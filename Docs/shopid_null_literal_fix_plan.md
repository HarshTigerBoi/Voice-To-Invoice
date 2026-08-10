# `shopId` Serialized as the Literal Text "null" — Diagnosis & Fix Plan

**Written**: 2026-07-31 (Claude Code) · **For**: Antigravity · **Severity**: P0 — a correctly-transcribed, correctly-parsed sale is silently vanishing from Supabase and getting misreported as "item not in catalog"

## 1. What the evidence actually shows (verified against the live DB, not inferred)

Job `a27fb69a-af53-47d8-af29-5dfc762ad31a` ("एक किलो आलू" — a completely ordinary, previously-working phrase):

```sql
SELECT count(*) FROM stt_job_logs WHERE job_id = 'a27fb69a-af53-47d8-af29-5dfc762ad31a';    -- 0
SELECT count(*) FROM unmatched_queue WHERE job_id = 'a27fb69a-af53-47d8-af29-5dfc762ad31a';  -- 0
```

**Zero rows in either table** — this job left no trace in Supabase at all, despite the client-side trace showing a full, apparently-successful pipeline run. The trace itself explains why:

```json
"step_7_persistence": {
  "ensureShopId": "null",
  "sttJobLogs":        {"ok": false, "code": "22P02", "message": "invalid input syntax for type uuid: \"null\""},
  "unmatchedQueue":    {"ok": false, "code": "22P02", "message": "invalid input syntax for type uuid: \"null\""},
  "unmatchedQueueFallback": {"ok": false, "code": "22P02", "message": "invalid input syntax for type uuid: \"null\""}
}
```

`"ensureShopId":"null"` is a **4-character JSON string**, not JSON `null` — if the server held an actual null/undefined, `JSON.stringify` would emit the bare token `null`, no quotes. The quotes prove the server's `resolvedShopId` variable literally contained the text `n-u-l-l`, and every write that needs a real UUID for `shop_id` failed Postgres's type check on it.

### The bug is a missing null-guard, confirmed by reading the exact line

`app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt:754-824` builds the multipart upload by hand. Every optional field has a guard before it's written — `onDeviceTranscript` (`:808`, `if (onDeviceTranscript.isNotBlank())`), `onDeviceStatus` (`:814`), `previousJobId` (`:820`, `if (previousJobId != null)`) — **except `shopId`**:

```kotlin
// SttWorker.kt:804-806 — no guard, unlike every sibling field above/below it
writeString("$twoHyphens$boundary$lineEnd")
writeString("Content-Disposition: form-data; name=\"shopId\"$lineEnd$lineEnd")
writeString("$shopId$lineEnd")
```

`shopId` here is `String?` (`:757`). Kotlin string templates render a null reference as the four characters `"null"` — this is the well-known Kotlin/JVM template behavior, not a hypothesis. When `shopId` is Kotlin `null` for a given upload, the wire body literally contains the text `null` as the form value.

Server-side, `index.ts:635` does `const shopId = formData.get('shopId') as string | null` — it reads whatever text arrived, and `getNullSafeShopId` (`index.ts:164-169`) only rejects `!shopIdRaw` (empty), a blank string, or the one legacy sentinel UUID — **it has no check for the literal string `"null"`**, so it passes straight through as if it were a real shop id.

### One root cause, two symptoms that looked unrelated

That laundered `"null"` string then gets used in two places, which is why this looked like two separate bugs:

1. **Every UUID-typed write fails** (`ensure_shop` RPC, `stt_job_logs` insert, `unmatched_queue` insert) — Postgres correctly refuses to cast `"null"` to `uuid` (`22P02`). This is why the job is invisible in Supabase and why it *feels* like nothing was recorded, even though the client-side pipeline ran to completion.
2. **The catalog lookup used to match parsed items is shop-scoped** (`index.ts:895-896`):
   ```ts
   let query = supabase.from('catalog_items').select('id, name, price, unit_id').eq('active', true)
   if (resolvedShopId) query = query.eq('shop_id', resolvedShopId)   // resolvedShopId = "null" here
   ```
   `if (resolvedShopId)` is true for the *string* `"null"` (it's truthy, non-empty) — so this filters `WHERE shop_id = 'null'`, which cannot match any real row and returns nothing. That's why Grok's interpretation step reported `is_matched_to_catalog: false` and `implausibility_reason: "'Aaloo' is not in your catalog yet — set a rate to book it"` — a flatly wrong message. Aaloo demonstrably has a catalog entry with a working rate; the 2026-07-30 job `cd683ace-...` for `"आलू बीस किलो"` matched it and `AUTO_CONFIRMED` at ₹50/kg. This job's confidence (0.55) landed below the 0.80 auto-confirm gate specifically *because* the catalog match failed for this reason, not because the parse was actually ambiguous.

So: this was never a review-worthy sale. STT was exact (Sarvam scored 5, transcript verbatim "एक किलो आलू"), the segmenter matched perfectly (`itemMatchNorm: 0`), and the only thing that kept it out of the ledger and out of Supabase entirely was the laundered shop id.

### Open question — why was the client's `shopId` null for this call?

`SupabaseConfig.getNullSafeShopId(shopId: String?)` (`network/SupabaseConfig.kt:8-13`) returns null when `shopId == null`, is blank, equals `"default_shop"`, or `length != 36`. `ShopContext.requireShopId()` (`data/ShopContext.kt:71-76`) never returns null itself (it throws instead) and, on a healthy install, always returns a fresh 36-character `UUID.randomUUID().toString()`. For `getNullSafeShopId` to reject it, the value read from `ShopContext` must not have been a well-formed 36-char UUID at the moment of this upload.

**I have not verified what that value actually was** — that requires on-device inspection, which this session can't do. Do not guess a fix for the origin; instead, Step 3 below adds one line that makes the next occurrence self-diagnosing instead of a repeat guessing exercise. If you have adb access, this single check would settle it immediately:
```bash
adb shell run-as com.voicetoinvoice.app cat /data/data/com.voicetoinvoice.app/shared_prefs/shop_context.xml
```
If that file's `shop_id` value is anything other than a proper 36-character UUID (in particular, if it is itself the 4-character string `"null"`), that confirms a self-perpetuating corruption: `ShopContext.initialize()` (`ShopContext.kt:54`, `existing.isNullOrBlank()`) treats a stored `"null"` string as a valid pre-existing id forever, because `"null"` is non-null and non-blank as a Kotlin string.

## 2. Fix plan

### Step 1 — Client: never let a null shopId reach the wire as text

`SttWorker.kt:806`, change:
```kotlin
writeString("$shopId$lineEnd")
```
to:
```kotlin
writeString("${shopId ?: ""}$lineEnd")
```
This makes the field empty (matching what `getNullSafeShopId` already intends — "no valid shop id") instead of the misleading literal text `"null"`. Do not add a guard that skips writing the field entirely (unlike `previousJobId`'s `if != null` pattern) — the server's multipart parser (`index.ts:635`, `formData.get('shopId')`) should keep receiving a `shopId` key; it just needs to be empty rather than the string `"null"`.

### Step 2 — Server: reject the literal sentinels defensively, not just blank

`index.ts:164-169`, change:
```ts
function getNullSafeShopId(shopIdRaw: string | null): string | null {
  if (!shopIdRaw || shopIdRaw.trim().length === 0 || shopIdRaw === '11111111-1111-1111-1111-111111111111') {
    return null
  }
  return shopIdRaw
}
```
to:
```ts
function getNullSafeShopId(shopIdRaw: string | null): string | null {
  const trimmed = shopIdRaw?.trim()
  if (!trimmed || trimmed.length === 0 || trimmed === '11111111-1111-1111-1111-111111111111'
      || trimmed === 'null' || trimmed === 'undefined') {
    return null
  }
  return trimmed
}
```
This is defense-in-depth: Step 1 fixes the one known cause, but any future client bug that lands a stringified null/undefined here should never again reach a UUID column and produce a 22P02 cascade. Deploy this immediately per the standing edge-function deploy authorization once Step 1 and Step 2 are both verified locally.

### Step 3 — Make the next occurrence self-diagnosing (does not fix the root cause, makes it observable)

In `SttWorker.kt`, immediately before building `metadataJson` for upload (near where `shopId` is computed, `:128`), add the raw value into the diagnostic trace client-side so a future occurrence shows the *actual* pre-`getNullSafeShopId` value instead of requiring a fresh adb pull each time:
```kotlin
clientTrace.put("shop_id_raw_len", com.voicetoinvoice.app.data.ShopContext.requireShopId().length)
```
(length only — never log the raw UUID itself into a trace that syncs to a shared table). If this next fires with a job whose `shop_id_raw_len` isn't 36, that confirms the corrupted-`SharedPreferences` theory from §1 without needing device access, and the fix becomes: in `ShopContext.initialize()` (`ShopContext.kt:54`), also treat a stored value that isn't a well-formed UUID (not just blank) as "needs regeneration" — but do **not** make that change now without the confirming data; a wrong fix here (regenerating a real shopkeeper's id) discards their existing ledger's tenant tag.

## 3. Scope boundaries

- **Do not touch the segmenter, Grok interpretation, or confidence thresholds.** Verified: the parse itself was correct (`itemMatchNorm: 0`, transcript exact). The catalog-match failure was a shop-id filtering artifact, not a parsing or threshold problem — confirmed by the 2026-07-30 job matching the same item successfully under a valid shop_id.
- **Do not regenerate or reset `ShopContext`'s stored id** as part of this fix. If Step 3's diagnostic confirms corruption, that is a separate, higher-stakes fix (touches every existing local row's tenant tag) and needs its own plan and explicit user sign-off before touching a real shopkeeper's identity.
- **Do not add a guard that skips the `shopId` form field when null** (see Step 1) — the server expects the key to exist; only the value changes.

## 4. Verification

1. Confirm Step 1 and Step 2 changes compile/build (`./gradlew.bat testDebugUnitTest`, `./gradlew.bat assembleDebug`).
2. Deploy the edge function per standing authorization: `npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr`. Re-fetch the live bundle afterward and grep for `'undefined'` inside `getNullSafeShopId` to confirm the deploy carried the change (this project has a history of partial deploys going live silently).
3. Record `"एक किलो आलू"` again on the device. Query:
   ```sql
   SELECT job_id, status, raw_transcript, parsed_item_name, parsed_total,
          diagnostic_trace_json::jsonb -> 'step_7_persistence' AS persistence
   FROM stt_job_logs ORDER BY created_at DESC LIMIT 1;
   ```
   Expect: a row actually exists (not zero rows like `a27fb69a`), `persistence.sttJobLogs.ok = true`, `parsed_item_name = 'Aaloo'`, `parsed_total = 50` (1kg × ₹50), and — if the shop id is genuinely healthy on this device — `status = 'AUTO_CONFIRMED'`.
4. If it still isn't `AUTO_CONFIRMED`, check `shop_id_raw_len` in the new client trace field from Step 3 and report it — do not guess further; that number tells us whether §1's open question is confirmed.
5. Log this in `Docs/audit.md` under RESOLVED (check the current highest `ISSUE-0NN` first) — this is a distinct root cause from ISSUE-065/068 (those were about reachability/UX for zero-line jobs; this is a data-corruption-on-the-wire bug that also happened to produce a zero-committed-line job, for an unrelated reason).
6. End with a **Deviations** section, and explicitly state what `shop_id_raw_len` showed if Step 3 fired during testing.
