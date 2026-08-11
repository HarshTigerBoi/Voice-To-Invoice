# Home Screen UI Cleanup — Plan

## Context / evidence

Screenshot supplied by the user (2026-08-11, real device, dark theme) shows three concrete
defects on `HomeScreen`, verified against source below — not guessed from the screenshot alone:

1. **The health-score chip and "हाल की गतिविधि" chip render on top of the two PTT mic
   circles.** `स्कोर 66 · 2 अलर्ट` overlaps the green नकद बेचो mic; `हाल की गतिविधि` overlaps the
   orange उधार बेचो mic.
2. **The purple assistant FAB (बिल वाले, 64dp) renders on top of the Quick Manual Stepper
   grid**, covering the Adrak card's `Add ₹120` button.
3. **The stepper grid's later rows (drink icons, cart icon "वाले") are clipped/squeezed** right
   above the bottom nav bar instead of being reachable.

### Root cause (read in `HomeScreen.kt`, `MainActivity.kt`, `ManualStepperComponent.kt`)

- `HomeScreen.kt:269-447` lays out the screen as one `Box(fillMaxSize)` containing two things
  that are positioned independently of each other:
  - The primary content `Column` (`HomeScreen.kt:274-358`) is **not scrollable** and centers
    itself using `Spacer(Modifier.weight(0.5f))` above and below the mic row
    (`HomeScreen.kt:282`, `:341`). Its vertical position is a function of total content height
    vs. available screen height — it moves.
  - The health-score / activity `Row` (`HomeScreen.kt:374-416`) is a **second, absolutely
    positioned child** of the same `Box`, anchored `Alignment.TopCenter` with a **fixed**
    `padding(top = if (pendingLineCount > 0) 72.dp else 16.dp)` (`HomeScreen.kt:377`).
  - Because one is flow-and-content-dependent and the other is a fixed pixel offset from the
    top, they collide whenever the flex spacers resolve small (exactly what the screenshot
    shows). The comment at `HomeScreen.kt:369-373` justifies top-anchoring by claiming "the top
    ... has no such variable-height content beneath it" — that reasoning is false: the mic row
    itself is the variable-height content, since its position depends on the weighted spacers.
- `MainActivity.kt:868-884` places `AssistantFloatingButton` at `Alignment.BottomEnd` of the
  full-screen content `Box`, `padding(24.dp)`, on top of every screen. `HomeScreen.kt:352-357`
  tries to reserve clearance for it with a **fixed 96dp `Spacer`** at the bottom of its own
  non-scrollable `Column`. Since that `Column` cannot scroll and `ManualStepperComponent`'s
  internal `LazyVerticalGrid` is capped at `heightIn(max = 320.dp)`
  (`ManualStepperComponent.kt:31`), whenever mic row + fallback button + stepper card height
  exceeds the visible screen height, the 96dp reserve spacer gets pushed off-screen instead of
  guaranteeing clearance — the FAB's fixed on-screen position then lands directly on the
  stepper's last visible row, and the row above it is squeezed against the nav bar.

Both are structural layout bugs, not just "spacing looks off" — fixing them requires changing
how the screen is composed, not tweaking a padding value. The plan below fixes the structural
bug first, then does a scoped visual cleanup on top of the now-correct layout.

## Goals

1. Nothing overlaps, on any screen height. No absolutely-positioned status chip may share
   screen space with the mic row; the FAB must never sit on top of unreachable content.
2. All content is reachable — the whole Home body scrolls, so the stepper grid's last row and
   the FAB never fight over the same fixed screen position.
3. A cleaner visual hierarchy: fewer competing elements at the top, consistent card/button
   styling in the stepper grid, larger touch targets on the +/- steppers.
4. Zero behavior change. Every `onClick`, state variable, DB write, and voice-capture code path
   in `HomeScreen.kt` stays exactly as is — this is a layout-and-styling-only pass.

## Non-goals

- No changes to `PttMicButton.kt`'s recording/audio logic, `ManualStepperComponent`'s
  `onAddSale` contract, or any DAO/repository code.
- No changes to `MainActivity.kt`'s `NavigationBar` (bottom tab bar) — it is not part of the
  reported defects.
- No changes to other screens (`StockInScreen`, `CustomerListScreen`, etc.) — scope is
  `HomeScreen.kt` + `ManualStepperComponent.kt` only, unless a step below says otherwise.

---

## Step 1 — Make the Home body a single scrollable flow (fixes defects 1–3)

File: `app/src/main/java/com/voicetoinvoice/app/ui/screens/home/HomeScreen.kt`

1.1. In the `Box` at `HomeScreen.kt:269-273`, change the inner `Column`
(`HomeScreen.kt:274-277`) to be vertically scrollable and remove the weight-based centering:

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally
) {
```

Add the import `androidx.compose.foundation.verticalScroll` and
`androidx.compose.foundation.rememberScrollState` (or rely on the existing
`androidx.compose.foundation.layout.*` wildcard import plus these two explicit imports — both
live in `androidx.compose.foundation`, not `.layout`, so they must be added explicitly).

**Compose pitfall to avoid**: a `Column` with `Modifier.verticalScroll(...)` measures its
children with an unbounded (infinite) height constraint. Any child using `Modifier.weight(...)`
inside such a Column throws `IllegalStateException` at runtime. Both weighted spacers must be
replaced with fixed-height spacers:

- `HomeScreen.kt:282` — replace `Spacer(modifier = Modifier.weight(0.5f))` with
  `Spacer(modifier = Modifier.height(24.dp))`.
- `HomeScreen.kt:341` — replace `Spacer(modifier = Modifier.weight(0.5f))` with
  `Spacer(modifier = Modifier.height(24.dp))`.

1.2. Move the `PendingConfirmationsBar` call (`HomeScreen.kt:360-367`) out of the absolutely
positioned overlay and into the top of the scrollable `Column`, immediately after the opening
brace (i.e. as the Column's first child, before the current `Spacer(0.5f)` that Step 1.1 already
turned into a fixed spacer). Drop the `.align(Alignment.TopCenter)` modifier — flow position
replaces it. Keep `pendingCount`, `onClick`, and the `padding(top = 16.dp)` unchanged (the
`AnimatedVisibility` inside `PendingConfirmationsBar` still handles show/hide when
`pendingCount == 0`).

1.3. Move the health-score / activity `Row` (currently `HomeScreen.kt:374-416`, the
`AssistChip` pair) out of the absolute overlay and into the scrollable `Column`, placed
immediately after the `PendingConfirmationsBar` call from Step 1.2. Remove
`.align(Alignment.TopCenter)` and the conditional `padding(top = if (pendingLineCount > 0) 72.dp
else 16.dp)` — replace with a fixed `padding(vertical = 8.dp)` since flow position already
places it correctly below the banner without needing to guess the banner's height. Keep every
other line inside the `Row` (the `healthScoreValue?.let { ... }` block, the
`commandFeedJobs.isNotEmpty()` block, `onNavigateToReports`, `showCommandFeed = true`)
byte-for-byte identical — only the wrapping `Modifier` and position in the file change.

1.4. The `Box` at `HomeScreen.kt:269` still wraps the scrollable `Column` — it is still needed
for the `UdhaarPickerOverlay` / badge block (`HomeScreen.kt:418-446`), which stays exactly where
it is, still `.align(Alignment.BottomCenter)` on the outer `Box`. This one is legitimately a
modal-like overlay triggered by an event (an unassigned credit), not static content, so it is
fine for it to float above the scrollable body.

1.5. After Steps 1.2–1.3, the scrollable `Column`'s child order is: `PendingConfirmationsBar` →
status chip `Row` → fixed `Spacer(24.dp)` → mic `Row` (`नकद बेचो` / `उधार बेचो`, unchanged) →
fixed `Spacer(16.dp)` (unchanged, was already fixed) → "Type sale manually" `TextButton`
(unchanged) → fixed `Spacer(24.dp)` (was the second `weight(0.5f)`) → `ManualStepperComponent`
call (unchanged) → bottom clearance `Spacer`.

1.6. Bottom clearance: change the existing `Spacer(modifier = Modifier.height(96.dp))` at
`HomeScreen.kt:357` to `Spacer(modifier = Modifier.height(88.dp))` — 64dp FAB + 24dp padding
(`MainActivity.kt:882`) = 88dp is the FAB's actual footprint; 96dp was already close but pick
the exact number so the comment above it (`HomeScreen.kt:352-356`) stays accurate. This spacer
now works correctly because the Column scrolls: once the user scrolls to the bottom, the last
stepper row has real clearance above the FAB instead of the FAB permanently covering it.

---

## Step 2 — Stop the stepper grid from double-scrolling

File: `app/src/main/java/com/voicetoinvoice/app/ui/components/ManualStepperComponent.kt`

With the outer `Column` now scrollable (Step 1.1), a `LazyVerticalGrid` nested inside it must
not also be independently scrollable — that produces the classic Compose nested-scroll conflict
(gestures fight, inner grid intercepts drags meant for the outer scroll).

2.1. At `ManualStepperComponent.kt:29-34`, change:

```kotlin
LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 108.dp),
    modifier = Modifier.heightIn(max = 320.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
```

to:

```kotlin
LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 108.dp),
    modifier = Modifier.heightIn(max = 2000.dp),
    userScrollEnabled = false,
    verticalArrangement = Arrangement.spacedBy(10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp)
) {
```

`heightIn(max = 2000.dp)` (rather than removing the constraint entirely) keeps `LazyVerticalGrid`
happy about being given effectively-unbounded height inside a scrollable parent without any
realistic catalog size hitting the cap. `userScrollEnabled = false` hands all scroll gestures to
the outer `Column` — the grid still lays out and renders every item, it just doesn't compete for
drag gestures.

---

## Step 3 — Visual cleanup (scoped, concrete — do exactly this, nothing else)

### 3a. Top app bar — reduce icon clutter

File: `HomeScreen.kt:247-267`

Replace the three separate `IconButton`s (`List`, `Receipt`, `Info`) with a single overflow
menu, keeping every existing callback wired to the same destinations:

```kotlin
topBar = {
    TopAppBar(
        title = { Text("Shop Ledger") },
        actions = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(
                    text = "आज: ₹${todayTotalSales.toInt()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            var menuExpanded by remember { mutableStateOf(false) }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "और विकल्प")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("समीक्षा (Pending Review)") },
                    leadingIcon = { Icon(Icons.Default.List, contentDescription = null) },
                    onClick = { menuExpanded = false; showPendingSheet = true }
                )
                DropdownMenuItem(
                    text = { Text("Summary") },
                    leadingIcon = { Icon(Icons.Default.Receipt, contentDescription = null) },
                    onClick = { menuExpanded = false; onNavigateToSummary() }
                )
                DropdownMenuItem(
                    text = { Text("Voice Processing Logs") },
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                    onClick = { menuExpanded = false; onNavigateToLogs() }
                )
            }
        }
    )
}
```

Add import `androidx.compose.material.icons.filled.MoreVert` and
`androidx.compose.ui.graphics.RoundedCornerShape` is already available via
`androidx.compose.foundation.shape.RoundedCornerShape` (already imported at `HomeScreen.kt:15`).
`DropdownMenu`/`DropdownMenuItem` come from the existing `androidx.compose.material3.*` wildcard
import — no new import needed for those two.

### 3b. Stepper card — bigger touch targets, consistent shape

File: `ManualStepperComponent.kt:37-72`

- Change the card shape to rounded: `Card(shape = RoundedCornerShape(16.dp), elevation =
  CardDefaults.cardElevation(defaultElevation = 2.dp))`. Add import
  `androidx.compose.foundation.shape.RoundedCornerShape`.
- Replace the bare-text stepper buttons at `ManualStepperComponent.kt:61,63` with icon buttons
  using `Icons.Default.Remove` / `Icons.Default.Add` at a fixed 36dp size so they read as
  controls, not stray characters:

```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    FilledTonalIconButton(
        onClick = { if (qty > 0.5) qty -= 0.5 },
        modifier = Modifier.size(36.dp)
    ) { Icon(Icons.Default.Remove, contentDescription = "घटाएं", modifier = Modifier.size(18.dp)) }
    Text(
        "$qty",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 10.dp)
    )
    FilledTonalIconButton(
        onClick = { qty += 0.5 },
        modifier = Modifier.size(36.dp)
    ) { Icon(Icons.Default.Add, contentDescription = "बढ़ाएं", modifier = Modifier.size(18.dp)) }
}
```

Add imports `androidx.compose.material.icons.filled.Remove` and
`androidx.compose.material.icons.filled.Add`.

- No change to the `Button(onClick = { onAddSale(item, qty) })` call itself — its label logic
  (`"Add ₹${(qty * item.price).toInt()}"`) is correct as-is (shows the live total, not just the
  unit price) and stays untouched.

### 3c. Status chip row spacing

File: `HomeScreen.kt` (the `Row` moved in Step 1.3)

No structural change beyond what Step 1.3 already specifies. Confirm the `Row`'s
`horizontalArrangement = Arrangement.SpaceBetween` still reads correctly once both chips are
present — if `healthScoreValue` is `null` the `Spacer(Modifier.width(1.dp))` placeholder at
`HomeScreen.kt:394` keeps the activity chip from jumping to the left edge; leave that placeholder
as-is.

---

## Verification (what Antigravity must check before calling this done)

Build: `./gradlew.bat assembleDebug` must succeed with zero new warnings about unused imports
(remove the wildcard-covered ones you no longer need after adding explicit imports).

Manual, on the physical test phone (`tools/vti-ship.ps1`):

1. Open Home screen. Confirm the health-score chip and activity chip render **below** the
   pending-confirmation banner (if present) and **above** the mic buttons, with visible gaps —
   not overlapping the green/orange mic circles, at both:
   - a normal scroll position (top of screen)
   - immediately after a voice recording completes and the pending-confirmation banner appears
     (this is the state that produced 72dp of top padding before — confirm no regression now
     that it's inline).
2. Scroll to the bottom of the Home screen. Confirm every stepper card (including the last row —
   drink icons, "वाले" cart icon) is fully visible and its `Add` button is tappable without the
   purple assistant FAB covering it. Confirm tapping `Add` on the last visible card still calls
   `onConfirmSale` (check a `TransactionRecord` lands via the same path as before — behavior is
   unchanged, this is a regression check).
3. Confirm the outer scroll and the stepper grid do not fight — dragging inside the stepper grid
   area scrolls the whole Home screen (grid itself no longer scrolls independently, per Step 2).
4. Tap the new overflow (⋮) menu in the top bar; confirm all three items still navigate/open
   correctly (`समीक्षा` → pending sheet, `Summary` → `onNavigateToSummary`, `Voice Processing
   Logs` → `onNavigateToLogs`).
5. Confirm the `+`/`-` steppers in a stepper card still adjust `qty` correctly and the `Add ₹`
   label updates live.
6. Rotate/resize if possible, or test on the smallest available screen height, to confirm no
   overlap reappears — the whole point of Step 1 is that the fix no longer depends on screen
   height.

## Deviations

If any step's exact Kotlin snippet doesn't compile against the current file content (e.g. an
import already exists, a symbol was renamed since this plan was written), fix the mismatch
directly and note it under "Deviations" in the implementation report — do not silently change
the layout order or skip a verification step.
