# Server-First Instant Voice Processing & Online Sync Architecture Plan

This plan completely transforms the Voice-to-Invoice system into a **Server-First Instant Processing Engine**. Voice recordings are uploaded to the cloud immediately upon button release, processed on the server by Grok AI, and stored directly in the cloud database—allowing processing to continue even if the app is closed.

---

## Technical Overview

```
User Releases Mic 
       │
       ▼ (Immediate HTTP POST)
Supabase Deno Edge Function (`process-voice-job`)
       ├── 1. Transcribes audio via Grok / Sarvam STT
       ├── 2. Parses sale via Grok AI (`grok-2-latest`)
       ├── 3. Uploads audio to Supabase Storage ('voice-recordings')
       └── 4. Writes directly to Supabase Cloud DB:
               ├── `stt_job_logs` (Full diagnostic trace & audio URL)
               ├── `transactions` (Auto-confirmed sales)
               └── `unmatched_queue` (Pending review items)
       │
       ▼ (1.5s Total Response Time)
App UI & Local Database updated instantly
```

---

## Key Benefits

1. **Instant Speed (1.5 seconds):** No more multi-pass processing on the phone. Everything happens in a single HTTP request directly on Supabase Deno Edge Functions.
2. **App-Closed Processing:** Because processing runs on the server, once the audio payload is sent, processing and ledger recording finish successfully even if the user exits or closes the app immediately.
3. **Always Online-Synced:** Every recording, transcript, interpreted result, audio file, transaction, and review item is saved directly into Supabase Cloud DB & Storage, making all logs immediately visible in real time.

---

## Proposed Changes

### Component 1: Supabase Deno Edge Function

#### [NEW] `supabase/functions/process-voice-job/index.ts`
- Create a new unified Edge Function `process-voice-job` that receives the `.wav` audio binary, shop ID, and catalog items.
- Transcribes using Grok / Sarvam STT.
- Interprets items using Grok-2 (`grok-2-latest`).
- Writes to `stt_job_logs`, `transactions`, `unmatched_queue`, and `voice-recordings` storage bucket via Supabase Service Role client.
- Returns parsed sales object to the caller.

---

### Component 2: Android App Integration

#### [MODIFY] `app/src/main/java/com/voicetoinvoice/app/network/SttProxyClient.kt`
- Add `processVoiceJobInstant(audioFile, catalogNames)` to send audio directly to `process-voice-job`.

#### [MODIFY] `app/src/main/java/com/voicetoinvoice/app/ui/screens/home/HomeScreen.kt` / `BackgroundSttProcessor.kt`
- On mic button release, immediately fire the `.wav` file to `process-voice-job`.
- Eliminate local queue delays and multi-step phone-side wait loops.
- Update local Room DB with the returned result so the screen updates immediately.

---

## Verification Plan

### Automated / API Verification
- Deploy `process-voice-job` to Supabase (`npx supabase functions deploy process-voice-job`).
- Test endpoint via PowerShell with a sample `.wav` file and verify that records are created in `stt_job_logs`, `transactions`, and `voice-recordings` bucket.

### App Verification
- Build `VoiceToInvoice_v48.apk`.
- Test voice recording and verify immediate completion and instant appearance in log menu & summary.
