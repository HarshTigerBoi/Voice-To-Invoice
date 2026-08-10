# ISSUE-103 — Short phonetic keys collide with common Hindi words and auto-book them

**Status:** plan, not yet implemented
**Trace:** job `11f98d55-a2d7-42aa-86d6-0a3aebe4f92f`, 2026-08-08 17:35:27 UTC
**Spoken:** "2 kilo aauuaauuauuu" (deliberate gibberish) → **booked 2 KG Aam @ ₹120 = ₹240, AUTO_CONFIRMED**

---

## 1. What actually happened (verified)

Sarvam transcribed the gibberish as **"दो किलो हाँ"** — a real, grammatical Hindi
phrase ("two kilo yes"). Everything downstream then behaved *correctly by its own
rules* and still booked a sale.

The mechanism, reproduced by running the live `phonetic.ts` against this shop's real
catalog (probe output quoted below, **verified** — not inferred from reading code):

```
हाँ  -> phoneticKey "AN"
आम   -> phoneticKey "AN"     ← identical
Aam  -> phoneticKey "AN"
```

`phoneticKey` drops `h` unconditionally (`if (c === 'h') continue`, phonetic.ts:112)
and merges `n`/`m` → `N`. So `हाँ` → `han` → `AN`, and `आम` → `am` → `AN`. The two
words are **the same key**, hence `distance: 0`, hence "exact match".

Every gate in `buildFastPathFrom` (index.ts:649) then passed legitimately:

| Gate | Value | Passed |
|---|---|---|
| `resolutionKind !== 'MATCH'` | `MATCH` | ✅ |
| `isSanityFlagged` | `false` | ✅ |
| `itemMatchNorm !== 0` | `0` | ✅ |
| `spokenPrice`/`rupeeWordPresent` | none | ✅ |
| `hasLeadingQty` | `true` | ✅ |
| `hits.length !== 1` | 1 (`Aam`) | ✅ |
| `price > 0` | ₹120 | ✅ |

→ `confidenceFromMatchNorm(0)` = **0.95** → clears the 0.80 gate → ledger.

**The contradiction worth pulling:** `itemMargin: 0.25` was recorded and ignored. The
runner-up (`हींग`, key `IN`) is *one vowel substitution* away. A margin that small on
a 2-phone key is a loud ambiguity signal — the system logged it and had no rule that
could act on it (see §3, defect C).

---

## 2. Blast radius (verified against the live catalog)

This is not a one-off. Running the real matcher over common Hindi discourse words
against shop `2f992a33`'s **actual** catalog, these silently auto-book today:

```
"हाँ" / "हां" / "haan" / "han" / "अम" / "हम्म" / "आँ"  (key AN) -> Aam  @ ₹120/unit
"की" / "के"                                            (key KI) -> घी   @ ₹1200/unit
```

**`की` and `के` are Hindi genitive postpositions** — among the highest-frequency words
in the language. "दो किलो के…" books **2 KG of ghee at ₹2400**. This is a live
financial-corruption path, not a theoretical one.

Corroborating evidence already in `stt_job_logs`: job `2561aeed` parsed
`"तो ये है"` → item name **`है`**, qty 2. And the live `catalog_items` table has
accumulated ₹0 junk rows named `है`-class fragments: `"बचा रहा"`, `"सत्रह की"`,
`"अठारह के लोग"`, `"पंद्रह"`, `"सत्ताईस"`. Discourse particles have been leaking into
the ITEM slot for some time; this trace is the first one that *also* landed on a
priced catalog row and therefore reached the ledger.

**What is NOT broken** (checked, so it is not fenced off by assumption): genuinely
unrecognizable audio is handled correctly — `"aauuaauuauuu"` and `"आ ऊ आ ऊ"` both
resolve to `resolutionKind: UNKNOWN` and do not auto-confirm. The failure is
specific to STT producing a *real, short, common word*.

---

## 3. Root causes (three, ranked)

**A — `itemMatchNorm === 0` is treated as "exact match" when it only means "identical
after a lossy collapse".** For a 2-phone key the collapse has thrown away nearly all
information; a distance of 0 on `AN` is far weaker evidence than a distance of 0 on
a 7-phone key. Nothing anywhere scales confidence by the *information content* of the
key. This is the class-level defect.

**B — Discourse particles are never excluded from the ITEM slot.** The segmenter's
job is "which catalog item is nearest?" — it always answers with *something* inside
0.25. Nobody ever asks "is this a product word at all?". `हाँ`, `है`, `के`, `की`,
`ये`, `तो` are not products in any shop.

**C — The `TAU_MARGIN = 0.08` ambiguity gate is unreachable for short keys — dead code
exactly where ambiguity is worst.** Margin granularity is ~`0.5 / keyLength`, so
`margin < 0.08` requires a key of ≥ 7 phones. Measured minimum non-zero margins:

```
key "AN"      (len 2): 0.2500   ← 3x the threshold; can never be flagged
key "KI"      (len 2): 0.3750
key "PALAK"   (len 5): 0.2500
key "TANATAL" (len 7): 0.1429
```

Constants live in `phonetic.ts:967` (server) and `OrderingSegmenter.kt:520` (client).

---

## 4. The fix — one concept: **low-information match**

Rather than three overlapping patches, introduce a single predicate used by the gates
that already exist.

> A match is **low-information** when the phone key is short *and* the winning margin
> is small relative to key length *and* the spoken surface is not literally the
> catalog name.

Low-information matches are: never fast-path eligible, classified `AMBIGUOUS`, and
confidence-capped below the 0.80 auto-confirm gate.

### Why the literal-surface escape hatch matters

Without it, every legitimate mango sale needs a confirm tap. Measured literal
distances (`normalizedLiteralDistance`, already in phonetic.ts:211):

```
आम  vs Aam   = 0.000  ← real sale, must stay fast
आम  vs आम    = 0.000  ← real sale, must stay fast
घी  vs घी    = 0.000  ← real sale, must stay fast
हाँ vs Aam   = 0.667  ← blocked
की  vs घी    = 0.500  ← blocked
के  vs घी    = 1.000  ← blocked
आँ  vs Aam   = 0.500  ← blocked
अम  vs Aam   = 0.000  ← LEAKS through this test; caught by the stoplist (Step 2)
```

So the escape hatch preserves the common case and still blocks the observed
collisions. **I checked whether literal distance alone could be the whole fix — it
cannot** (`अम` vs `Aam` = 0.000), which is why Step 2 is required rather than optional.

---

## 5. Implementation steps

Mirrored logic — **every change below lands on both sides**:
`supabase/functions/process-voice-job/phonetic.ts` ↔
`app/src/main/java/com/voicetoinvoice/app/domain/parser/OrderingSegmenter.kt`
(and `PhoneticKey.kt`, verified behaviourally identical: `h` dropped at
PhoneticKey.kt:129, `n`/`m` → `N` at :137).

### Step 1 — length-aware ambiguity gate (replaces the dead `TAU_MARGIN`)

In `phonetic.ts`, in `closeSegment()` (~line 966), and the Kotlin mirror
`OrderingSegmenter.kt:744`:

Add next to the existing constants:
```ts
/** A runner-up within one consonant edit of the winner is ambiguous regardless of key
 *  length. Replaces TAU_MARGIN = 0.08, which was unreachable for keys <= 6 phones
 *  (margin granularity is ~0.5/keyLength) and therefore never fired. See ISSUE-103. */
const MIN_MARGIN_PHONE_EDITS = 1.0
```

Replace the margin test `bestItemMargin < 0.08` with:
```ts
bestItemMargin !== null && itemKeyLength > 0 &&
  (bestItemMargin * itemKeyLength) < MIN_MARGIN_PHONE_EDITS
```
where `itemKeyLength = phoneticKey(currentItemTokens.join(' ')).length`. Track it
alongside `worstItemNorm`/`bestItemMargin` and reset it in `closeSegment()`.

Verified behaviour of this rule against measured data:
`AN` → 0.25×2 = 0.5 → AMBIGUOUS ✅ · `KI` → 0.375×2 = 0.75 → AMBIGUOUS ✅ ·
`PALAK` → 0.25×5 = 1.25 → MATCH ✅ · `TANATAL` → 0.1429×7 = 1.0 → MATCH ✅

Keep `TAU_MARGIN` deleted, not merely bypassed, so it cannot drift back into use.

### Step 2 — discourse-particle stoplist

Extend the **existing** denylist idiom rather than inventing a new one — server
`isQuantityPhrase` (index.ts:176), client `isBlacklistedItemName`
(FuzzyCatalogMatcher.kt:30).

Add to `phonetic.ts` and `OrderingSegmenter.kt`:
```ts
/** Hindi/Hinglish discourse particles, fillers and postpositions. Never a product in
 *  any shop, but short enough that the lossy phone key collides them with real catalog
 *  items -- "हाँ" and "आम" are both key "AN". See ISSUE-103. */
export const DISCOURSE_PARTICLES: Set<string> = new Set([
  'हाँ','हां','हा','ना','नहीं','है','हैं','ये','यह','वो','वह','अच्छा','ठीक','ओके','जी',
  'अरे','बस','और','तो','भी','का','की','के','में','से','पर','अब','क्या','अम','उम','हम्म','आँ',
  'haan','han','haa','hai','ye','wo','achha','theek','ji','bas','aur','ok','okay','hmm','umm',
])
```

In `segmentTranscript`, **before** tokenizing, drop any token that is in
`DISCOURSE_PARTICLES` **and** is not an exact surface form in the item vocabulary or
the shop catalog (so a shop that genuinely stocks something named like a particle is
unaffected).

Effect on the reported trace: `"दो किलो हाँ"` → `"दो किलो"` → no item segment →
`carryoverQty: 2`, nothing booked. Correct outcome.

This also stops the ongoing catalog pollution (the ₹0 `बचा रहा` / `सत्रह की` rows).

### Step 3 — information-content-aware confidence (the class-level fix)

`index.ts:137`, `confidenceFromMatchNorm`. Steps 1–2 fix the fast path, but the **AI
path re-derives the same 0.95**: at index.ts:1804-1805, a catalog-matched AI item with
no self-reported confidence falls back to `confidenceFromMatchNorm(matchNorm)` using
the segmenter's aligned `matchNorm` — which is still `0`. Without this step the bug
class survives on the AI path.

Change the signature to `confidenceFromMatchNorm(norm, keyLength, literalExact)` and
scale the quality term by information content:

```ts
const RELIABLE_KEY_PHONES = 4
// A distance-0 hit on a 2-phone key is not the same evidence as one on a 7-phone key.
const infoFactor = literalExact ? 1.0 : Math.min(1, keyLength / RELIABLE_KEY_PHONES)
const quality = (1 - clamped / MATCH_NORM_REJECT) * infoFactor
```

Resulting confidences at `norm = 0`: len 2 → **0.725** (review queue) · len 3 → 0.838
(auto) · len 4+ → 0.95 (unchanged). `literalExact` (spoken surface literally equals the
catalog name) restores 0.95 for genuine `आम`/`घी` sales.

Update both call sites and the client mirror (`FuzzyCatalogMatcher.kt:137` hardcodes
`0.95f` on substring match — see Open Question 2).

### Step 4 — audit + deploy

- Add **ISSUE-103** to `Docs/audit.md` §2 under 🟢 RESOLVED (highest existing is
  ISSUE-102), in the established Symptom / Root Cause / Resolution / Verification
  Date format.
- Update §1 "Ground-Truth Source-Code Verified Constants" for the removed `TAU_MARGIN`
  and the new `MIN_MARGIN_PHONE_EDITS` / `RELIABLE_KEY_PHONES`.
- Deploy: `npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr`,
  then re-fetch the live bundle and grep for `DISCOURSE_PARTICLES` and
  `MIN_MARGIN_PHONE_EDITS` to confirm the deploy actually carried the change.

---

## 6. Verification — by effect, not by build

`./gradlew test` passing and `BUILD SUCCESSFUL` prove nothing here. Required:

1. **Regression suite first.** Add cases to `PhoneticSegmentationTest` (Kotlin, the
   side with the suite) and `phonetic_test.ts`: `"दो किलो हाँ"` → no item segment;
   `"दो किलो के"` → no item segment; `"दो किलो आम"` → still `MATCH` + confidence ≥ 0.80;
   `"पाँच किलो आलू"` → unchanged.
2. **Speak the original input again** after deploy and re-query:
   ```sql
   SELECT job_id, raw_transcript, parsed_item_name, status, created_at
   FROM stt_job_logs WHERE created_at > now() - interval '10 minutes'
   ORDER BY created_at DESC;
   ```
   Pass = status is **not** `AUTO_CONFIRMED` for a `हाँ`-class transcript. If no row
   recorded after the change can be found, say the verification did not happen.
3. **Confirm the real regression cost is what this plan claims** — book a genuine
   `"दो किलो आम"` and check it still auto-confirms via the `literalExact` path.

---

## 7. Instance vs. class

- Steps 1–2 eliminate **this instance** and the whole discourse-particle family.
- Step 3 addresses the **class** — "distance 0 on a low-information key is treated as
  strong evidence" — across the fast path, the AI path, and the client.
- **The class is not fully eliminated.** The deepest root cause is the `phoneticKey`
  collapse itself (unconditional `h` deletion merging `हाँ`/`आम`, `n`/`m` → `N`). I am
  **deliberately not changing it**: it is the core of the matcher, mirrored in Kotlin,
  and every distance in the system would shift. That is a separate, suite-backed
  change. Named here so it is not silently fenced off.
- Two scenarios this fix does **not** cover (per post-fix adversarial audit):
  1. A 4+ phone non-item word colliding uniquely with a catalog item still books at
     0.95 — e.g. in-vocab collisions `गोभी`/`कॉफी` → `KOPI` and `खीरा`/`केला` → `KILA`
     are caught today *only* by the `hits.length !== 1` catalog check, which fails open
     if a shop stocks just one of each pair.
  2. Grok returning its own high `confidence` bypasses Step 3 entirely
     (index.ts:1802-1803 prefers `rawItem.confidence`).

---

## 8. Open questions (stop and ask rather than guessing)

1. **`RELIABLE_KEY_PHONES = 4` costs `हींग` and `उड़द` (both 2-phone, no literal-exact
   escape when STT romanises them) a confirm tap.** Accept, or set it to 3 and accept
   a larger collision surface? I recommend 4 — the doctrine in CLAUDE.md is that a
   review tap is cheap and a silent mis-book is not.
2. **`FuzzyCatalogMatcher.kt:142` matches on bare substring containment at 0.85
   confidence for any token ≥ 2 chars** on the client offline path. That is an
   adjacent hole of the same class, but the reported trace came through the server, so
   I have **not** folded it into this plan. Fix now, or file separately?
3. Should `और`/`तो` be stoplisted? They are legitimate connectors in multi-item
   utterances ("दो किलो आलू और तीन किलो प्याज") — dropping them is safe for
   segmentation but I have not tested it against the multi-item suite.
