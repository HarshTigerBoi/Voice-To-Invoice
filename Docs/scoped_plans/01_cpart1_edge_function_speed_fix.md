# Scoped plan 1/11 — C-PART-1: Assistant speed fix (ISSUE-117)

**Source:** extracted verbatim from `Docs/visual_ledger_and_assistant_plan.md` §4, C-PART-1.
**Scope:** this file ONLY. Do not read or implement any other section of the parent plan.
File touched: `supabase/functions/process-voice-job/index.ts`. No client (Kotlin) changes.

---

`supabase/functions/process-voice-job/index.ts`

**C1.1** Put the fast model at the head of the chain. Lines 57-62 become:

```typescript
const XAI_CHAT_MODELS: string[] = [
  Deno.env.get('XAI_CHAT_MODEL') || '',
  'grok-4.20-0309-non-reasoning',  // ISSUE-117: step 4 is structured extraction, not
                                   // reasoning. Measured 3849ms of a 5523ms job on
                                   // grok-4.5 (trace 54e7fe50). Also cheaper:
                                   // $1.25/$2.50 vs $2.00/$6.00 per 1M.
  'grok-4.5',
  'grok-4.3',
  'grok-4',
].filter(Boolean)
```

**C1.2 — the trap that will otherwise take the whole AI stage down.** Line 87 is:

```typescript
const supportsReasoningEffort = (model: string) => model.startsWith('grok-4')
```

`'grok-4.20-0309-non-reasoning'.startsWith('grok-4')` is **`true`**, so the current code would send `reasoning_effort: 'low'` to a model that has no reasoning to configure. If xAI answers 400 for the unsupported parameter, `isModelUnavailableError` (line 97-103) does **not** match it — it only matches deprecation wording — so `callGrokChatInterpretation` hits line 409 and **`break`s out of the chain entirely**. No fallback to grok-4.5. Step 4 dies silently on every job, which is precisely the ISSUE-021 failure mode the chain was built to prevent.

Change line 87 to:

```typescript
const supportsReasoningEffort = (model: string) =>
  model.startsWith('grok-4') && !model.includes('non-reasoning')
```

**C1.3 — defence in depth.** Widen `isModelUnavailableError` (line 97-103) so a *parameter* rejection advances the chain instead of killing it. Add to the returned disjunction:

```typescript
    b.includes('unsupported parameter') || b.includes('unknown field') ||
    b.includes('unrecognized') || b.includes('invalid_request_error')
```

**C1.4** Deploy immediately (standing authorisation, per CLAUDE.md):

```bash
npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr
```

Then re-fetch the live bundle and grep for `grok-4.20-0309-non-reasoning` and `non-reasoning'` in the `supportsReasoningEffort` body. This project has a history of silent partial deploys — the grep is not optional.

---

## Deviations

End with a "Deviations" section. If none, say "None."
