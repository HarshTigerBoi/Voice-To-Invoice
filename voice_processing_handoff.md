# Voice-to-Invoice Pipeline: Technical Developer Handoff & Full Source Code Reference

This document provides a complete, self-contained technical specification, diagnostic toolkit, and **full source code listing** for developers working on the Voice-to-Invoice processing pipeline in **Voice To Invoice**.

---

## 1. System Architecture Diagram

```
 [User Presses Mic Button (PTT)]
                 │
                 ▼
 ┌──────────────────────────────────────┐
 │ 30-Sec Rolling Audio PCM Buffer      │
 │ (RollingAudioBuffer.kt)              │
 │ (totalBytesWritten Ring Buffer Math) │
 └──────────────────────────────────────┘
                 │
                 ▼  (Captures non-overlapping audioStartMs / audioEndMs)
 ┌──────────────────────────────────────┐
 │ SttJobRecord Created in Room DB (v6) │
 │ (Status: QUEUED)                     │
 └──────────────────────────────────────┘
                 │
                 ▼
 ┌────────────────────────────────────────┐
 │ Background Worker Drain Loop           │
 │ (BackgroundSttProcessor.kt)            │
 │ Generates Full JSON Diagnostic Trace   │
 └────────────────────────────────────────┘
                 │
                 ├─────────────────────────────────────────┐
                 ▼                                         ▼
 ┌──────────────────────────────────────┐   ┌─────────────────────────────┐
 │ 1. STT Proxy (Sarvam/Grok STT)       │   │ Automatic Background Cloud  │
 │    Supabase /functions/v1/stt-proxy   │   │ Sync (CloudSyncManager.kt)  │
 └──────────────────────────────────────┘   │ - Uploads .wav to Supabase  │
                 │                          │   Storage                   │
                 ▼                          │ - Posts trace to Supabase DB│
 ┌──────────────────────────────────────┐   └─────────────────────────────┐
 │ 2. Deterministic OrderingSegmenter   │
 └──────────────────────────────────────┘
                 │
                 ▼
 ┌──────────────────────────────────────┐
 │ 3. Grok AI Multi-Item Interpreter    │
 └──────────────────────────────────────┘
                 │
                 ▼
 ┌────────────────────────────────────────────────────────┐
 │ 4. Adaptive Audio Expansion Engine                     │
 └────────────────────────────────────────────────────────┘
                 │
                 ▼
 ┌────────────────────────────────────────────────────────┐
 │ 5. Local Storage Self-Cleaner (LocalStorageCleaner.kt) │
 │    - Auto-deletes oldest .wav files if storage > 500MB │
 └────────────────────────────────────────────────────────┘
```

---

## 2. Cloud Server Endpoint Status (`v45`)

- **Deployed Edge Function:** `https://lyowklxsbfznnqridtgr.supabase.co/functions/v1/stt-proxy`
- **Automatic Cloud Log Endpoint:** Receives `application/json` payloads and stores them directly in Supabase Cloud DB.
- **Verified Status:** Returning `200 OK` (`"status": "logged_successfully"`).

---

## 3. How to Verify Recordings On the App (`VoiceToInvoice_v45.apk`)

1. Install `VoiceToInvoice_v45.apk` on your phone.
2. Hold mic button and record a sale.
3. Open **Logs**:
   - Tap **"▶ Play Recorded Audio"** to hear the exact sound clip recorded by the mic.
   - Tap **"Copy JSON"** or **Share** to view Grok's raw output, timestamps, and ledger summary.
