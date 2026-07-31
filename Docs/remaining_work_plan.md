# Remaining Work Plan — handoff

**Written**: 2026-07-31 · **Last build**: `VoiceToInvoice_v93.apk` · **Tests**: 20 instrumented + full JVM suite, all green on the real device (`23049PCD8I`, Android 15)

This is the to-do list for everything NOT finished. For what *is* done, see the status block at the top of [master_build_plan.md](master_build_plan.md) and ISSUE-049…064 in [audit.md](audit.md).

---

## 0. State of the repo right now

Everything below is committed to the working tree, compiles, and is verified on your phone:

| Area | Status |
|---|---|
| Stock ledger, WAC costing, COGS snapshot, expiry batches | Done, 13 tests |
| Trilingual `IntentRouter` (12 intents, phonetic) | Done, 20 tests + 51-case Node check |
| Voice handlers: price / return / payment / void / expiry / action | Done, wired into `SttWorker` + fast path |
| `ActionExecutor` (WhatsApp bills, reminders, calls) | Done, 6 real call sites |
| `CommitSequencer` (spoken-order commits) | Done, 6 tests |
| Command Feed UI (status per command + retry) | Done |
| Daily rollups + Reports screen + health score + alerts | Done, 7 tests, verified on device |
| `QuestionTemplates` trilingual rewrite | Done, 8 tests |
| `ReorderAdvisor`, `DemandForecast`, `ShopLearning` | **Logic done + tested, NOT surfaced in any UI** |
| DB indices on all 5 hot tables | Done |

**Room schema is at v22.** Migrations 17→22 all verified against your real phone's existing database.

---

## 1. Highest priority — correctness gaps

### 1.1 Server mirror wired and deployed ✅ (2026-07-31, ISSUE-058)
`index.ts` now imports `classifyIntent`/`captureIntentFor` from `./intent_router.ts` and classifies every `ASSISTANT`-captured job before deciding what to write. `SALE`/`CREDIT_SALE`/`STOCK_IN`/`WASTE`/`PRICE_UPDATE` book through the existing (already-tested) commit paths; `RETURN`/`PAYMENT_RECEIVED`/`VOID_LAST`/`EXPIRY_WRITEOFF`/`ACTION_COMMAND`/`UNKNOWN` route to `unmatched_queue` instead of being silently dropped — the plan's stated minimum bar, not the full client-parity mirror (that would need `VoiceCommandHandlers`-equivalent customer-resolution/ledger-reversal logic server-side, judged too risky to write and deploy untested in one pass). `step_2b_intent_classification` added to the trace. Deployed to `lyowklxsbfznnqridtgr`; live bundle re-fetched and grepped for `classifyIntent`/`shouldBookSale`/`step_2b_intent_classification` to confirm the deploy shipped. See ISSUE-058 in `audit.md` for full detail.

**Still open**: an actual RETURN/PAYMENT_RECEIVED/PRICE_UPDATE/VOID_LAST utterance has not been verified end-to-end against the live DB with the app closed (no easy way to trigger that scenario without a physical device test). If a shopkeeper reports a "closed-app" review-queue item they didn't expect, check `unmatched_queue.implausibility_reason` for the `ASSISTANT intent=...` note this now writes.

### 1.2 New tables sync to Supabase ✅ (2026-07-31, ISSUE-059)
`stock_ledger`/`stock_batches`/`customer_payments`/`shop_learning` now have server tables (migration `20260731010000_...`), `CloudSyncManager` methods, and `SyncEngine` sweeps wired into `syncAllUnsynced()`. `daily_rollups` intentionally still skipped (derived cache, no server reader needs it).

**Bonus find while doing this**: the `customers` table migration (written earlier, for the Udhaar/customer-resolution work) had never actually been deployed — `syncCustomerToCloud` had been silently 404ing in production. Applied it live along with `stock_in.cost_missing`. See ISSUE-059 in `audit.md` for full detail.

**Still open**: no end-to-end device test yet of an actual row syncing through any of the 4 new paths — only schema + compile-time wiring verified.

---

## 2. Not yet built (free, no blocker) — ALL DONE ✅ (2026-07-31, ISSUE-060)

### 2.1 Surface the intelligence features ✅ (ISSUE-060)
`ReorderAdvisor`, `DemandForecast`, `MoverBuckets` (fast/steady/slow/dead) and `AlertEngine` cards are now surfaced on `ReportsScreen.kt`.

### 2.2 Phase 5 — bills and the remaining WhatsApp entry points ✅ (ISSUE-060)
- Created `BillBuilder.kt` for rendering PNG bills onto Canvas with FileProvider Uri.
- Added "बिल भेजें" button to `CommandFeedSheet` and share icon to `CustomerDetailScreen.kt`.
- Built `RepeatOrderSheet.kt` with pure price resolution (`buildRepeatLines`) pricing today's rate vs previous rate and wired it into `CustomerDetailScreen` and `MainActivity`.

### 2.3 Alert dismiss/snooze persistence ✅ (ISSUE-060)
Added `alert_dismissals` table (`AlertDismissal` entity, `AlertDismissalDao`, Room v23 migration). `AlertEngine` now filters snoozed and permanently dismissed alerts, with expired purge on launch.

### 2.4 Expiry UI & Write-off ✅ (ISSUE-060)
Added expiry date picker dialog in `StockInScreen.kt` for `trackExpiry` items. Added `writeOffExpired` in `AlertEngine` recording negative stock with `StockReason.EXPIRY`.

---

## 3. Resting — needs money or a decision

### 3.1 Phone-OTP auth + server RLS (ISSUE-032) — **blocks multi-shop rollout**
This is the one thing that makes it unsafe to give the app to a second shop. RLS is still disabled on `transactions`, `unmatched_queue`, `stt_job_logs`, and `shop_id` is NULL on every existing server row. Anyone with the client-embedded anon key can read every shop's ledger.

The **local** half is done: `ShopContext` gives every row a real per-installation shop id, and `ShopContext.bindAuthenticatedShopId()` is the single swap point. What's left needs a paid SMS provider (MSG91/Twilio):
1. Configure the provider in Supabase Auth.
2. `AuthManager` (sendOtp / verifyOtp / refresh), tokens in `EncryptedSharedPreferences`.
3. `ShopProfile` entity + blocking onboarding flow.
4. Switch `CloudSyncManager` from anon key to `Bearer <access_token>`.
5. Server migration: create `shops`, backfill `shop_id` on all existing rows, **then** enable RLS with `USING (shop_id = auth.uid())`.
6. Make `learned_parses` + `term_aliases` shop-scoped — right now they're global, so one shop's learned mistakes would leak into another's.

**Do step 5 in that order.** Enabling RLS before backfilling makes every existing row invisible to its own owner.

### 3.2 AI tier-2 Q&A + intent arbitration
Per-utterance LLM cost. The free template tier already covers the common questions, and `IntentRouter` already returns `needsArbitration` for mid-confidence cases — the hook exists, nothing calls it. When you do wire it: the fact-sheet + numeric-grounding-validator design in master_build_plan.md §4.1 is the important part. **Never let the model produce a number that isn't in the fact sheet.**

---

## 4. Known quirks worth remembering

- `./gradlew test --tests <class>` **does not work** (the aggregate task rejects `--tests`). Use `./gradlew testDebugUnitTest --tests "..."`. CLAUDE.md's documented form is wrong.
- The build directory now lives at `%LOCALAPPDATA%\VoiceToInvoiceBuild`, outside OneDrive. This permanently fixed the "Unable to delete directory" failures that used to hit every few builds. Override with `VTI_BUILD_DIR` if needed.
- Deno isn't installed on this machine; the edge-function fixture check runs via `npx tsx` against the real `.ts` file instead.
- `fallbackToDestructiveMigration()` was removed deliberately. If a migration fails now the app will crash rather than silently wipe the ledger — that's intentional; check the `migration_status` table.
