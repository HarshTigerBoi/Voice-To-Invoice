---
description: Issue description in, installed APK out — diagnose, plan, implement, build, install on the phone, log it.
---

Take this issue and carry it all the way to an APK running on the phone. Do not stop to ask
for approval between stages — the user has authorized the whole chain. Only stop if you hit a
genuine ambiguity where proceeding either way would produce wrong work.

**Issue:** $ARGUMENTS

Work through these stages in order.

## 0. Which machine is this session running on?

Everything below assumes the laptop: PowerShell, `tools/vti-implement.ps1`, `agy.exe`, wireless
adb to the physical phone. None of that exists in a cloud session (opened from claude.ai/code or
the mobile app, no access to the local Windows filesystem or a phone over adb). If this is a
cloud session, use the cloud path instead — see steps 3 and 4 below for both.

## 1. Diagnose against the live system first

Per CLAUDE.md's diagnosis discipline, the FIRST action is live data, not source reading. Query
`stt_job_logs` via the Supabase MCP tools (project ref `lyowklxsbfznnqridtgr`) before forming
any hypothesis:

```sql
SELECT job_id, status, raw_transcript, parsed_item_name, parsed_qty, parsed_total,
       length(diagnostic_trace_json) AS trace_len, created_at
FROM stt_job_logs ORDER BY created_at DESC LIMIT 20;
```

`trace_len` ~186 bytes means the client wrote the row and the server persisted nothing; a real
server trace is 3–10 KB. Before building on a hypothesis, state the competing explanation and
the specific check that rules it out. If you cannot name a disconfirming check you actually
ran, label it a guess.

## 2. Write the plan

Write it to `Docs/<feature>_plan.md`, matching the existing `Docs/*_plan.md` style. Exact file
paths, function names, constant values, migration numbers, step order. State explicitly whether
mirrored logic changes on one side or both (`domain/parser/` ↔
`supabase/functions/process-voice-job/index.ts`).

## 3. Implement it

**On the laptop** — hand off to the Antigravity CLI:

```
.\tools\vti-implement.ps1 Docs\<feature>_plan.md
```

Do NOT write the diff yourself. `agy.exe` (Antigravity CLI 1.1.11) runs the implementation
headlessly on the Google AI Pro quota, which is the whole point of the planner/implementer
split — Claude's quota goes on diagnosis and design.

The script blocks until the agent finishes and then verifies by effect: it diffs the working
tree and **exits 2 if nothing changed**. That check exists because `agy` exits 0 and prints a
confident summary regardless, and without `--add-dir` it silently writes into a sandbox at
`%USERPROFILE%\.gemini\antigravity-cli\brain\<uuid>\` while claiming it wrote to the repo.

**If it exits 2, the plan was not implemented.** Do not proceed to build. Read what the agent
said, and either fix the plan's ambiguity and re-run, or implement it yourself and say plainly
that you did.

Review the resulting diff before shipping. If the agent deviated from the plan or the plan
turned out to be wrong, fix it and update the plan file so it stays an accurate record.

(The `antigravity-ide chat` subcommand is a different, broken thing — it discards prompts
silently. Never use it. See `tools/vti-handoff.ps1`.)

**In a cloud session** — there's no Antigravity to hand off to. Implement the plan yourself
directly, per CLAUDE.md's cloud-session exception. Same standard as above: match the plan's file
paths, names, and constants; if you deviate, say so and update the plan file.

## 4. Ship it

**On the laptop:**

```
.\tools\vti-ship.ps1
```

Builds, verifies the artifact is fresh, archives it as the next `VoiceToInvoice_v<N>.apk`,
finds the phone over the hotspot, installs silently. It self-heals from Kotlin daemon crashes,
hotspot subnet changes, and `tcpip` mode resetting after a phone reboot.

If it reports no device over wireless or USB, the phone has rebooted and needs the cable once —
say so plainly and stop there rather than reporting success.

**In a cloud session** — there's no adb path to the phone, so commit and push instead:

```
git add -A && git commit -m "..." && git push
```

pushing to any branch triggers `.github/workflows/build-apk.yml`, which builds the debug APK and
attaches it to a GitHub Release (tag `apk-<run_number>`). Watch it with
`gh run watch --exit-status` (poll `gh run list --workflow=build-apk.yml` for the run ID if it
didn't just print one), then confirm the release actually has the APK asset — `gh release view
apk-<n> --json assets` — before calling it done. Tell the user the release URL; they install it
by opening that link on the phone and tapping the APK.

## 5. Verify by effect, not by build

"BUILD SUCCESSFUL" and "Success" prove nothing about the bug. A differing md5 only proves the
artifact is fresh, not that the fix works — debug APKs embed timestamps, so the hash changes on
every recompile. Where the change is observable server-side, re-query `stt_job_logs` for a row
created AFTER the install and quote it. If no such row exists yet, say plainly that behavioural
verification is still pending and what the user should speak into the app to produce it.

## 6. Log it

Add an `ISSUE-NNN` entry under "🟢 RESOLVED ISSUES" in `Docs/audit.md` using the next sequential
number, with Symptom / Root Cause / Resolution / Verification Date. Be honest in Verification
Date about what was actually verified versus what is still pending.

## Report

Close with: what changed, the APK version installed, and — separately and explicitly — what is
verified versus what is still unverified.
