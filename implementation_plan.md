# Audio Recording Buffer & Rapid-Press Gap Handling Strategy

## Executive Summary & Problem Definition

When a shopkeeper uses the mic button to record sales, two major audio capture challenges arise:

1. **Pre & Post Padding (Pre-roll / Post-roll):**
   - **Pre-roll:** Human reaction time to press down on the mic button is typically ~200–400ms *after* the mouth begins speaking. Without pre-roll, initial syllables (e.g., "चा" in "चार किलो आलू") get cut off.
   - **Post-roll:** Shopkeepers often lift their finger off the screen button *during* or immediately as they finish the last word. Without post-roll, trailing speech sounds get clipped.

2. **Rapid Sequential Pressing ("Record -> Release -> Record -> Release"):**
   - A shopkeeper may rapidly tap record for Item 1 ("4 kg Aloo"), release, and within 200–800ms tap record again for Item 2 ("2 kg Tamatar").
   - **The Dilemma:** Speech spoken during the short gap between button presses could belong to the previous recording (tail speech) or the upcoming recording (lead speech).
     - *If we duplicate audio in both windows (overlapping pre/post roll):* The same spoken words could appear in **BOTH** recordings, causing **DUPLICATE SALES** to be booked into the ledger.
     - *If we discard audio in the gap:* Spoken words in the gap are **LOST FOREVER**.

---

## 4-Layer Architectural Plan to Solve All Present & Future Issues

```
Continuous Audio Ring Buffer (30s PCM in RAM)
─────────────────────────────────────────────────────────────────────────────► Time
       [ Speech 1: "चार किलो आलू" ]        (Gap 300ms)    [ Speech 2: "दो किलो टमाटर" ]
  │─────── Pre-roll ───────│── Hold 1 ──│── Midpoint ──│── Hold 2 ──│─────── Post-roll ───────│
  ▲                        ▲            ▲     Split    ▲            ▲                         ▲
  Rec 1 Start             Press 1     Release 1        Press 2    Release 2                  Rec 2 End
  (Clamped Start)                                                                           (Clamped End)
```

---

## Technical Details: The 4 Layers

### Layer 1: Mathematical Non-Overlapping Midpoint Partitioning (`PttWindowLedger`)

To guarantee **zero lost audio** and **zero duplicate audio**, the app dynamically partitions the gap between consecutive recordings using `PttWindowLedger`.

#### Default Settings:
- `PRE_ROLL_MS = 600 ms`
- `POST_ROLL_MS = 600 ms`

#### Dynamic Boundary Logic:
When Recording #2 occurs within `Gap` milliseconds of Recording #1 (where `Gap = PressTimestamp_2 - ReleaseTimestamp_1`):

1. **Large Gap (`Gap >= 1200 ms`):**
   - Recording #1 gets full `+600 ms` post-roll.
   - Recording #2 gets full `-600 ms` pre-roll.
   - Both recordings operate as completely independent windows.

2. **Small Gap (`Gap < 1200 ms`, e.g. 300 ms gap):**
   - The available gap audio is divided equally at the **midpoint**:
     $$\text{PostRoll}_{\text{Rec1}} = \min\left(600\text{ ms},\, \frac{\text{Gap}}{2}\right)$$
     $$\text{PreRoll}_{\text{Rec2}} = \min\left(600\text{ ms},\, \frac{\text{Gap}}{2}\right)$$
   - Recording #1's end audio time is clamped to `ReleaseTimestamp_1 + (Gap / 2)`.
   - Recording #2's start audio time is clamped to `PressTimestamp_2 - (Gap / 2)`.

> [!IMPORTANT]
> **Why this solves future issues:** 
> 1. **No Duplicate Bookings:** Audio frames from the ring buffer are assigned to **exactly one** recording file. It is mathematically impossible for the same audio frame to exist in two `.wav` files.
> 2. **No Lost Speech:** Every single sample written during the gap is included in either Recording #1 or Recording #2.

---

### Layer 2: Gap & Sequential Context Metadata

Every recording sent to Supabase Edge Function (`process-voice-job`) includes sequential context metadata:
- `precedingGapMs`: Time elapsed since the previous recording released.
- `previousJobId`: ID of the preceding recording job.

#### AI Prompting Context:
When `precedingGapMs < 1000 ms`, Grok AI receives explicit context:
> *"Context Notice: This recording was captured 250ms after Job #123. If the audio begins mid-phrase or mid-sentence, treat it as a rapid sequential entry. Do not fail parsing due to missing leading context."*

---

### Layer 3: Server-Side Deduplication & Idempotency Check

Even with clean audio partitioning, if a shopkeeper repeats the same item phrase across rapid presses (e.g. says "दो किलो टमाटर" in Rec #1 and repeats "दो किलो टमाटर" in Rec #2 out of habit), the backend enforces **Temporal Deduplication**:

1. **Transaction Hash:** `Hash(ShopID + ItemID + Quantity + Unit)`
2. **Time-Window Lock:** If Job #2 attempts to auto-confirm an identical transaction within **3 seconds** of Job #1, the server flags Job #2 with status `POSSIBLE_DUPLICATE_REVIEW` rather than auto-confirming.

---

### Layer 4: Multi-Item Support in Single & Split Holds

- If the shopkeeper holds the button and lists 5 items continuously, Grok AI parses all 5 items from the single audio clip into an array.
- If the shopkeeper taps 5 times for 5 items, each item is cleanly partitioned by Layer 1 and processed individually.
- **Result:** Whether the user is a "long presser" or a "rapid tapper", the system handles both with equal precision.

---

## Verification Plan

### Automated JVM Tests
- Unit test `PttWindowLedgerTest` verifying window clamping for:
  - Gap = 0 ms (back-to-back rapid press)
  - Gap = 300 ms (short gap)
  - Gap = 2000 ms (normal gap)

### Manual App Verification
- Build debug APK (`VoiceToInvoice_v80.apk`).
- Perform rapid button presses with < 300ms gaps speaking consecutive sales ("4 kg Aloo", "2 kg Tamatar").
- Inspect diagnostic traces in app logs to verify clean `audioStartMs` / `audioEndMs` boundaries with no overlap.
