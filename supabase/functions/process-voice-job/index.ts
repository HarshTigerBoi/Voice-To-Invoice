import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'
import {
  segmentTranscript,
  normalizeTranscript,
  phoneticKey,
  DEFAULT_ITEM_VOCAB,
  HINDI_NUMBER_MAP,
  UNIT_SET,
  type RawItemSegment,
} from './phonetic.ts'
import {
  detectPriceIntent,
  parseCompoundNumberSequence,
  implausibilityReason,
  type PriceIntent,
} from './price_intent.ts'

// Deno Deploy / Supabase Edge Runtime global declaration
declare const EdgeRuntime: { waitUntil: (promise: Promise<unknown>) => void }

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

// Model ids are env-configurable AND fall back down a chain at runtime.
//
// Pinning a single default is what caused ISSUE-021: `saarika:v2` was deprecated and
// `grok-2-latest` was retired, so BOTH the second STT opinion and the entire step-4 AI
// interpretation returned hard errors on every single job — silently, for an unknown
// number of days, because nothing treats "this pipeline stage has never once succeeded"
// as an alarm. Provider deprecations are routine and will happen again; they must
// degrade to the next model, not kill a stage.
//
// Order matters: first entry is preferred, later entries are older fallbacks. The env
// var (if set) is always tried first.
const XAI_CHAT_MODELS: string[] = [
  Deno.env.get('XAI_CHAT_MODEL') || '',
  'grok-4.5',   // current flagship as of 2026-07
  'grok-4.3',   // what the retired grok-4/grok-3 families now redirect to
  'grok-4',
].filter(Boolean)

const SARVAM_STT_MODELS: string[] = [
  Deno.env.get('SARVAM_STT_MODEL') || '',
  'saaras:v3',     // current flagship; 19.31% WER on IndicVoices vs ~22% for the v2 line
  'saarika:v2.5',  // v2 deprecated; the API's own 400 body names v2.5 as the successor
  'saarika:v2',
].filter(Boolean)

// saaras:v3 accepts a `mode` parameter that older models reject, so it is only sent for
// that model. VERBATIM, not TRANSLATE: translate mode emits English ("आलू" -> "potato"),
// which would strip out exactly the Hindi phonetics that PhoneticKey and the segmenter
// vocabulary are built to match against. Verbatim is word-for-word with no
// normalization — the closest thing to raw phones this API exposes.
const SARVAM_STT_MODE = Deno.env.get('SARVAM_STT_MODE') || 'verbatim'
const supportsModeParam = (model: string) => model.startsWith('saaras:v3')

// grok-4.x defaults reasoning_effort to 'high' when the parameter is omitted. That is
// almost certainly why step 4 kept timing out even after the budget was raised
// 8s -> 20s in ISSUE-022 (trace e0b68f80) and STILL timed out at 20s in the very next
// trace (0df2895e) — a reasoning pass over a short JSON-extraction prompt has no reason
// to need 20+ seconds unless the model is doing multi-step reasoning it wasn't asked to
// do. This is a pure structured-extraction task (pick the closest of N known words),
// not a task that benefits from deep reasoning, so force it low. See ISSUE-023.
const XAI_REASONING_EFFORT = Deno.env.get('XAI_REASONING_EFFORT') || 'low'
const supportsReasoningEffort = (model: string) => model.startsWith('grok-4')

// Once a model id is known to work in this isolate, stop paying for the discovery
// round-trips. Reset naturally on cold start, which is how a newly-set env var or a
// freshly-restored model gets picked up without a redeploy.
let knownGoodChatModel: string | null = null
let knownGoodSarvamModel: string | null = null

/** True when an HTTP error body indicates "this model id is wrong/gone" rather than a
 *  transient fault — the only case where advancing down the chain is meaningful. */
function isModelUnavailableError(status: number | null, body: string): boolean {
  if (status !== 400 && status !== 404 && status !== 422) return false
  const b = (body || '').toLowerCase()
  return b.includes('deprecat') || b.includes('model not found') ||
    b.includes('does not exist') || b.includes('unknown model') ||
    b.includes('invalid model') || b.includes('no longer')
}

/** Candidate order for this invocation: the known-good model first if we have one. */
function modelChain(all: string[], knownGood: string | null): string[] {
  if (!knownGood) return all
  return [knownGood, ...all.filter(m => m !== knownGood)]
}

// -----------------------------------------------------------------------------
// CONFIDENCE & PLAUSIBILITY
//
// The root cause of ISSUE-022: confidence was `isCatalogMatched ? 0.95 : 0.60`, a
// yes/no derived from *whether* an item matched while discarding *how well* it
// matched. The phonetic matcher computes a normalized distance for every hit and then
// threw it away, so a token sitting exactly on the reject line (0.250, the loosest
// match `WHOLE_TOKEN_MAX_NORM` permits) was indistinguishable from an exact hit and
// sailed through the 0.80 auto-confirm gate straight into the ledger.
//
// Every previous fix in this pipeline improved RECALL — finding a match at all. None
// measured PRECISION. That is why each improvement made the system more confidently
// wrong rather than more correct.
// -----------------------------------------------------------------------------

/** Must equal `WHOLE_TOKEN_MAX_NORM` in phonetic.ts / OrderingSegmenter.kt. */
const MATCH_NORM_REJECT = 0.25
const CONFIDENCE_AT_EXACT_MATCH = 0.95
const CONFIDENCE_AT_WORST_MATCH = 0.50
/** Anything the plausibility rules reject is held below the auto-confirm gate. */
const IMPLAUSIBLE_CONFIDENCE_CAP = 0.55

/**
 * Maps normalized phonetic distance onto confidence, linearly.
 *
 * `null` (the token matched nothing and survives as a literal new item name) returns
 * the documented 0.60 unmatched floor. With the constants above, only matches inside
 * ~0.075 normalized distance clear the 0.80 gate — i.e. essentially exact ones. That
 * is deliberate for a financial ledger: a mis-parse routed to review costs one tap, a
 * mis-parse booked silently corrupts the shopkeeper's books and their trust.
 */
function confidenceFromMatchNorm(norm: number | null | undefined): number {
  if (norm === null || norm === undefined) return 0.60
  const clamped = Math.max(0, Math.min(norm, MATCH_NORM_REJECT))
  const quality = 1 - clamped / MATCH_NORM_REJECT // 1.0 = exact, 0.0 = on the reject line
  return CONFIDENCE_AT_WORST_MATCH +
    (CONFIDENCE_AT_EXACT_MATCH - CONFIDENCE_AT_WORST_MATCH) * quality
}

/** Smallest sale value we will auto-confirm without a human glance. */
const MIN_PLAUSIBLE_SALE_VALUE = 5.0

/** Budget for the step-4 chat call. Env-tunable because it trades latency against how
 *  often the pipeline loses its only arbitration stage. */
const AI_CHAT_TIMEOUT_MS = Number(Deno.env.get('AI_CHAT_TIMEOUT_MS') || '45000')



function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms)
    promise.then(
      (v) => { clearTimeout(t); resolve(v) },
      (e) => { clearTimeout(t); reject(e) }
    )
  })
}

function getNullSafeShopId(shopIdRaw: string | null): string | null {
  if (!shopIdRaw || shopIdRaw.trim().length === 0 || shopIdRaw === '11111111-1111-1111-1111-111111111111') {
    return null
  }
  return shopIdRaw
}

// -----------------------------------------------------------------------------
// STT CALLS
//
// Both providers report a structured outcome rather than just a string. The previous
// version swallowed every failure into an empty transcript, so a wrong model name, an
// auth failure, a timeout, and genuine silence were all indistinguishable in the
// diagnostic trace — the Sarvam leg of the ISSUE-020 trace came back "" with no
// recorded reason anywhere. Never collapse an error into "" again.
// -----------------------------------------------------------------------------

interface SttOutcome {
  transcript: string
  error: string | null
  httpStatus: number | null
  latencyMs: number
  /** Which model id actually served this call. Recorded in the trace so a silent
   *  fallback down the chain is visible instead of looking like the pinned default. */
  model?: string
}

const EMPTY_STT: SttOutcome = { transcript: '', error: 'not attempted (missing API key)', httpStatus: null, latencyMs: 0 }

async function callGrokStt(
  audioBuffer: ArrayBuffer,
  jobId: string,
  apiKey: string,
  opts: { keyterms?: string[]; language?: string | null; timeoutMs?: number } = {}
): Promise<SttOutcome> {
  const started = Date.now()
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), opts.timeoutMs ?? 8000)
  try {
    const bytes = new Uint8Array(audioBuffer.slice(0))
    const file = new File([bytes], `${jobId}.wav`, { type: 'audio/wav' })
    const form = new FormData()
    form.append('file', file, `${jobId}.wav`)
    form.append('model', 'grok-stt')
    if (opts.language) form.append('language', opts.language)
    for (const k of opts.keyterms ?? []) form.append('keyterm', k)

    const resp = await fetch('https://api.x.ai/v1/stt', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${apiKey}` },
      body: form,
      signal: controller.signal,
    })
    clearTimeout(timeoutId)
    const latencyMs = Date.now() - started

    if (!resp.ok) {
      const body = await resp.text().catch(() => '')
      return { transcript: '', error: `HTTP ${resp.status}: ${body.slice(0, 300)}`, httpStatus: resp.status, latencyMs }
    }
    const data = await resp.json()
    return { transcript: (data.text || data.transcript || '').trim(), error: null, httpStatus: resp.status, latencyMs }
  } catch (e: any) {
    clearTimeout(timeoutId)
    const msg = e?.name === 'AbortError' ? `timeout after ${opts.timeoutMs ?? 8000}ms` : (e?.message || String(e))
    return { transcript: '', error: msg, httpStatus: null, latencyMs: Date.now() - started }
  }
}

async function callSarvamSttOnce(
  audioBuffer: ArrayBuffer,
  jobId: string,
  apiKey: string,
  model: string,
  opts: { languageCode?: string; timeoutMs?: number } = {}
): Promise<SttOutcome> {
  const started = Date.now()
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), opts.timeoutMs ?? 8000)
  try {
    const bytes = new Uint8Array(audioBuffer.slice(0))
    const file = new File([bytes], `${jobId}.wav`, { type: 'audio/wav' })
    const form = new FormData()
    form.append('file', file, `${jobId}.wav`)
    form.append('language_code', opts.languageCode ?? 'hi-IN')
    form.append('model', model)
    if (supportsModeParam(model)) form.append('mode', SARVAM_STT_MODE)

    const resp = await fetch('https://api.sarvam.ai/speech-to-text', {
      method: 'POST',
      headers: { 'api-subscription-key': apiKey },
      body: form,
      signal: controller.signal,
    })
    clearTimeout(timeoutId)
    const latencyMs = Date.now() - started

    if (!resp.ok) {
      const body = await resp.text().catch(() => '')
      return { transcript: '', error: `HTTP ${resp.status}: ${body.slice(0, 300)}`, httpStatus: resp.status, latencyMs, model }
    }
    const data = await resp.json()
    return { transcript: (data.transcript || data.text || '').trim(), error: null, httpStatus: resp.status, latencyMs, model }
  } catch (e: any) {
    clearTimeout(timeoutId)
    const msg = e?.name === 'AbortError' ? `timeout after ${opts.timeoutMs ?? 8000}ms` : (e?.message || String(e))
    return { transcript: '', error: msg, httpStatus: null, latencyMs: Date.now() - started, model }
  }
}

/** Sarvam STT with model-deprecation fallback. Advances down [SARVAM_STT_MODELS] only
 *  on an error that actually means "wrong model id" — a timeout or a 5xx is retried
 *  against nothing, since the next model would fail identically. */
async function callSarvamStt(
  audioBuffer: ArrayBuffer,
  jobId: string,
  apiKey: string,
  opts: { languageCode?: string; timeoutMs?: number } = {}
): Promise<SttOutcome> {
  const chain = modelChain(SARVAM_STT_MODELS, knownGoodSarvamModel)
  let last: SttOutcome = { ...EMPTY_STT, error: 'no Sarvam model configured' }
  for (const model of chain) {
    last = await callSarvamSttOnce(audioBuffer, jobId, apiKey, model, opts)
    if (!last.error) {
      knownGoodSarvamModel = model
      return last
    }
    if (!isModelUnavailableError(last.httpStatus, last.error)) return last
    console.warn(`Sarvam model "${model}" unavailable, trying next: ${last.error}`)
  }
  return last
}

// -----------------------------------------------------------------------------
// TRANSCRIPT SCORING
//
// Used to decide whether a re-decode pass actually improved on the first attempt.
// A good shopkeeper transcript resolves to at least one segment carrying a real
// quantity, a real unit, and an item the vocabulary recognizes.
// -----------------------------------------------------------------------------

function scoreTranscript(transcript: string, catalogNames: string[]): { score: number; segments: RawItemSegment[] } {
  if (!transcript || !transcript.trim()) return { score: 0, segments: [] }
  const { segments } = segmentTranscript(transcript, catalogNames)
  if (!segments.length) return { score: 0, segments: [] }

  const hasLatinScript = /[a-zA-Z]/.test(transcript)
  const hasDevanagari = /[\u0900-\u097F]/.test(transcript)

  const itemKeys = new Set([...DEFAULT_ITEM_VOCAB, ...catalogNames].map(n => phoneticKey(n)).filter(Boolean))

  let score = 0
  let recognizedCount = 0

  for (const seg of segments) {
    // A recognized item is the strongest signal that the audio was understood.
    const name = seg.itemTokens.join(' ')
    if (itemKeys.has(phoneticKey(name))) {
      score += 3
      recognizedCount++
    }
    // An explicit unit means the [QTY][UNIT][ITEM] frame was actually found, rather
    // than the segmenter falling back to its PACKET default.
    if (seg.unit && seg.unit !== 'PACKET') {
      score += 2
      recognizedCount++
    }
    if (seg.quantity && seg.quantity !== 1.0) score += 1
    if (seg.isSanityFlagged) score -= 2
  }

  // Only penalize Latin script transcripts if they contain ZERO recognized retail items or units.
  // This suppresses random STT hallucinations ("Charles Xavier", "tinggal sebab") while
  // preserving legitimate English/Hinglish orders ("4 Packet Maggie", "2 KG Sugar").
  if (hasLatinScript && !hasDevanagari && recognizedCount === 0) {
    score -= 5
  }

  return { score, segments }
}

/** True when nothing in the transcript resolves to known shopkeeper vocabulary —
 *  the signature of STT decoding the audio into the wrong language entirely
 *  ("तीन किलो सेब" -> "tinggal sebab"). */
function looksUnrecognizable(transcript: string, catalogNames: string[]): boolean {
  if (!transcript || !transcript.trim()) return true
  const known = new Set(
    [...Object.keys(HINDI_NUMBER_MAP), ...UNIT_SET, ...DEFAULT_ITEM_VOCAB, ...catalogNames]
      .map(n => phoneticKey(n)).filter(Boolean)
  )
  for (const tok of transcript.trim().split(/\s+/)) {
    if (known.has(phoneticKey(tok))) return false
  }
  return true
}

serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: CORS_HEADERS })
  }

  try {
    const authHeader = req.headers.get('Authorization')
    if (!authHeader) {
      return new Response(JSON.stringify({ error: 'Missing Authorization header' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json', ...CORS_HEADERS }
      })
    }

    const contentType = req.headers.get('content-type') || ''
    if (!contentType.includes('multipart/form-data')) {
      return new Response(JSON.stringify({ error: 'Content-Type must be multipart/form-data' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json', ...CORS_HEADERS }
      })
    }

    let formData: FormData
    try {
      formData = await req.formData()
    } catch (formErr: any) {
      console.error('Multipart form-data parse error:', formErr.message)
      return new Response(JSON.stringify({ error: `Failed to parse multipart form-data: ${formErr.message}` }), {
        status: 400,
        headers: { 'Content-Type': 'application/json', ...CORS_HEADERS }
      })
    }

    const audioFile = formData.get('file') as File | null
    const jobId = formData.get('jobId') as string | null
    const catalogNamesRaw = formData.get('catalogNames') as string | null
    const metadataRaw = formData.get('metadata') as string | null
    const shopId = formData.get('shopId') as string | null

    if (!audioFile || !jobId) {
      return new Response(JSON.stringify({ error: 'Missing required parameters (file, jobId)' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json', ...CORS_HEADERS }
      })
    }

    const supabaseUrl = Deno.env.get('SUPABASE_URL') || ''
    const supabaseAnonKey = Deno.env.get('SUPABASE_ANON_KEY') || ''
    const serviceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') || supabaseAnonKey
    const supabase = createClient(supabaseUrl, serviceRoleKey)
    const resolvedShopId = getNullSafeShopId(shopId)

    // Step 0: Idempotency check
    const { data: existingLog } = await supabase
      .from('stt_job_logs')
      .select('*')
      .eq('job_id', jobId)
      .maybeSingle()

    if (existingLog && existingLog.status === 'QUEUED') {
      console.log(`Job ${jobId} already queued/in-flight. Re-acking without reprocessing.`)
      return new Response(JSON.stringify({
        status: 'QUEUED',
        job_id: jobId,
        cached: true,
        message: 'Job already queued and processing in the background.'
      }), {
        status: 202,
        headers: { 'Content-Type': 'application/json', ...CORS_HEADERS }
      })
    }

    if (existingLog && existingLog.status !== 'QUEUED') {
      console.log(`Job ${jobId} already processed in stt_job_logs. Returning cached log.`)

      // Recover parsed_items from the stored trace so retry callers (e.g. a WorkManager
      // retry that lands after this job already finished) don't see an empty item list
      // for an AUTO_CONFIRMED job and silently drop the sale.
      let cachedParsedItems: any[] = []
      try {
        if (existingLog.diagnostic_trace_json) {
          const cachedTrace = JSON.parse(existingLog.diagnostic_trace_json)
          if (Array.isArray(cachedTrace.step_4_grok_ai_interpretation)) {
            cachedParsedItems = cachedTrace.step_4_grok_ai_interpretation
          }
        }
      } catch (_) {}

      return new Response(JSON.stringify({
        status: existingLog.status,
        raw_transcript: existingLog.raw_transcript,
        job_id: jobId,
        diagnostic_trace_json: existingLog.diagnostic_trace_json,
        parsed_items: cachedParsedItems,
        cached: true
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json', ...CORS_HEADERS }
      })
    }

    // Parse metadata
    let metadata: any = {}
    if (metadataRaw) {
      try { metadata = JSON.parse(metadataRaw) } catch (_) {}
    }

    // Step 1: Upload audio to Storage FIRST
    const audioBuffer = await audioFile.arrayBuffer()
    const storagePath = `${jobId}.wav`

    try {
      const { error: uploadErr } = await withTimeout(
        supabase.storage.from('voice-recordings').upload(storagePath, audioBuffer, {
          contentType: 'audio/wav',
          upsert: true
        }),
        8000,
        'Storage upload'
      )
      if (uploadErr) {
        console.warn(`Audio storage upload warning for job ${jobId}:`, uploadErr.message)
      }
    } catch (uploadTimeoutErr: any) {
      console.warn(`Audio storage upload timed out for job ${jobId}:`, uploadTimeoutErr.message)
    }

    const audioCloudUrl = `${supabaseUrl}/storage/v1/object/public/voice-recordings/${storagePath}`

    // Step 1B: Immediately mark the job QUEUED
    await supabase.from('stt_job_logs').upsert([{
      job_id: jobId,
      shop_id: resolvedShopId,
      recorded_at_ms: metadata.recordedAtMs || Date.now(),
      created_at: new Date(metadata.recordedAtMs || Date.now()).toISOString(),
      hold_duration_ms: metadata.holdDurationMs || 0,
      status: 'QUEUED',
      raw_transcript: '',
      is_sanity_flagged: false,
      error_message: '',
      audio_cloud_url: audioCloudUrl
    }], { onConflict: 'job_id' })

    // Kick off AI pipeline asynchronously in background
    EdgeRuntime.waitUntil(
      processVoiceJob({
        jobId,
        shopId: resolvedShopId,
        metadata,
        audioBuffer,
        audioCloudUrl,
        catalogNamesRaw,
        supabase
      })
    )

    // Respond immediately to client
    return new Response(JSON.stringify({
      status: 'QUEUED',
      job_id: jobId,
      audio_cloud_url: audioCloudUrl,
      cached: false,
      message: 'Audio received and stored. Processing in background — poll or subscribe to stt_job_logs for this job_id.'
    }), {
      status: 202,
      headers: { 'Content-Type': 'application/json', ...CORS_HEADERS }
    })

  } catch (err: any) {
    console.error('Unhandled exception in process-voice-job (request phase):', err)
    return new Response(JSON.stringify({ error: err.message || 'Internal Server Error' }), {
      status: 500,
      headers: { 'Content-Type': 'application/json', ...CORS_HEADERS }
    })
  }
})

// Background pipeline: AI interpretation & database resolution
async function processVoiceJob(args: {
  jobId: string
  shopId: string | null
  metadata: any
  audioBuffer: ArrayBuffer
  audioCloudUrl: string
  catalogNamesRaw: string | null
  supabase: ReturnType<typeof createClient>
}) {
  const { jobId, shopId, metadata, audioBuffer, audioCloudUrl, catalogNamesRaw, supabase } = args
  const resolvedShopId = getNullSafeShopId(shopId)

  try {
    let dbCatalogItems: Array<{ id: string, name: string, price: number, unit_id: string }> = []
    try {
      let query = supabase.from('catalog_items').select('id, name, price, unit_id').eq('active', true)
      if (resolvedShopId) query = query.eq('shop_id', resolvedShopId)
      const { data: catData } = await withTimeout(query, 5000, 'Catalog DB fetch')
      if (catData && catData.length > 0) {
        dbCatalogItems = catData
        console.log(`Fetched ${dbCatalogItems.length} active catalog items from DB for price matching`)
      }
    } catch (dbCatErr) {
      console.warn(`Catalog DB fetch warning (continuing with defaults):`, dbCatErr)
    }

    let catalogNames: string[] = dbCatalogItems.map(item => item.name)
    if (catalogNamesRaw) {
      try {
        const parsed = JSON.parse(catalogNamesRaw)
        if (Array.isArray(parsed)) {
          catalogNames = Array.from(new Set([...catalogNames, ...parsed]))
        }
      } catch (_) {}
    }

    const defaultCatalogFallback = [
      "Aaloo", "Pyaz", "Tamatar", "Seb", "Desi Ghee", "Amul Gold Milk", "Sugar",
      "Paneer", "Butter", "Maggi", "Atta", "Fortune Oil", "Bhindi", "Dhaniya",
      "Baingan", "Bengan", "Sona", "Chaandi", "Moongphali", "Kaju", "Badam"
    ]

    const fullCatalogList = Array.from(new Set([...catalogNames, ...defaultCatalogFallback]))

    // Keyterm biasing only works if the terms are in the script STT is going to emit.
    // Sending English-only keyterms while asking for Hindi meant the bias was largely
    // wasted, so send the Devanagari forms too (DEFAULT_ITEM_VOCAB carries both).
    const keyterms = Array.from(new Set([
      ...fullCatalogList,
      ...DEFAULT_ITEM_VOCAB,
      ...Object.keys(HINDI_NUMBER_MAP).filter(k => k.length >= 2 && !/^\d+$/.test(k)),
      ...UNIT_SET,
    ])).slice(0, 100)

    // Step 2: Dual concurrent STT (Grok + Sarvam), 8s timeout each, run in parallel
    const xaiApiKey = Deno.env.get('XAI_API_KEY') || ''
    const sarvamApiKey = Deno.env.get('SARVAM_API_KEY') || ''

    const [grokOutcome, sarvamOutcome] = await Promise.all([
      xaiApiKey
        ? callGrokStt(audioBuffer, jobId, xaiApiKey, { keyterms, language: 'hi' })
        : Promise.resolve(EMPTY_STT),
      sarvamApiKey
        ? callSarvamStt(audioBuffer, jobId, sarvamApiKey)
        : Promise.resolve(EMPTY_STT),
    ])

    if (grokOutcome.error) console.warn(`Grok STT failed for ${jobId}: ${grokOutcome.error}`)
    if (sarvamOutcome.error) console.warn(`Sarvam STT failed for ${jobId}: ${sarvamOutcome.error}`)

    let rawGrokTranscript = grokOutcome.transcript
    let rawSarvamTranscript = sarvamOutcome.transcript

    const providerOf = (g: string, s: string) =>
      g && s ? 'grok+sarvam' : g ? 'grok' : s ? 'sarvam' : 'none'
    let sttProvider = providerOf(rawGrokTranscript, rawSarvamTranscript)

    // Step 3: Phonetic, grammar-aware segmentation (replaces the old Devanagari-only
    // orthographic fuzzy segmenter — see phonetic.ts header and ISSUE-020).
    const grokScored = scoreTranscript(rawGrokTranscript, fullCatalogList)
    const sarvamScored = scoreTranscript(rawSarvamTranscript, fullCatalogList)

    // Pick whichever STT stream actually parses as a shopkeeper order. Sarvam (saaras:v3)
    // is explicitly tuned for Indic verbatim speech. When Sarvam parses a valid order
    // structure (or ties with Grok), Sarvam wins. Grok frequently introduces spurious spaces
    // ("दो किलो करों जा") which trick segmenters into scoring unlisted words as multi-tokens.
    let chosenRaw = (sarvamScored.score >= grokScored.score && rawSarvamTranscript)
      ? rawSarvamTranscript
      : (grokScored.score > sarvamScored.score && rawGrokTranscript)
        ? rawGrokTranscript
        : (rawSarvamTranscript || rawGrokTranscript)

    let step3Segments: RawItemSegment[] = chosenRaw === rawSarvamTranscript ? sarvamScored.segments : grokScored.segments
    let bestScore = Math.max(grokScored.score, sarvamScored.score)
    // IMPORTANT: transcript MUST remain the pure, untouched verbatim text from STT (chosenRaw).
    // It must NEVER be mutated or dictionary-replaced by normalizeTranscript!
    let transcript = chosenRaw || ''

    // Step 5 (runs before AI interpretation so the AI sees the best available audio
    // reading): ADAPTIVE RE-DECODE.
    //
    // This used to be a no-op that echoed the same two transcripts back alongside a
    // fabricated "EXPANDED_300MS_AUDIO_WINDOW_EVALUATED" status without ever calling
    // STT again — the trace reported a recovery pass that never happened. It now
    // genuinely re-transcribes with different decode parameters when the first pass
    // produced nothing the shopkeeper vocabulary recognizes, which is exactly the
    // ISSUE-020 signature (STT decoding Hindi audio into another language's lexicon).
    const passesDetail: any[] = []
    // Trigger on the PARSE RESULT, not on how foreign the raw text looks. "tinggal
    // sebab" reads as gibberish but now segments correctly to 3 KG Seb, and paying for
    // a second round-trip on an already-solved job would just add latency. A score
    // below 3 means we got neither a recognized item nor a usable quantity+unit frame.
    const rawLooksUnrecognizable = looksUnrecognizable(chosenRaw, fullCatalogList)
    const needsReDecode = bestScore < 3

    if (needsReDecode && (xaiApiKey || sarvamApiKey)) {
      // Each variant must be capable of producing a DIFFERENT answer, or the pass is
      // pure latency. The ISSUE-021 trace shows the old pair failing that test twice
      // over: variant 2 was the dead Sarvam model (guaranteed HTTP 400), and variant 1
      // differed from the first pass only in bias flags, so it returned the identical
      // string. Two "recovery passes", zero recovery. The three below vary the three
      // things that actually move a decode: the bias set, the language hint, and the
      // acoustic model itself.
      //
      // Tight keyterms matter more than they look: the first pass sends 100 terms
      // (catalog + numbers + units, capped), which spreads the bias so thin it barely
      // registers. A short catalog-only list concentrates it where the ambiguity
      // actually is — the item name.
      const tightKeyterms = Array.from(new Set([
        ...fullCatalogList,
        ...DEFAULT_ITEM_VOCAB,
      ])).slice(0, 25)

      const [grokUnbiased, sarvamRetry, grokTight] = await Promise.all([
        xaiApiKey
          ? callGrokStt(audioBuffer, jobId, xaiApiKey, { keyterms: [], language: null, timeoutMs: 8000 })
          : Promise.resolve(EMPTY_STT),
        sarvamApiKey
          ? callSarvamStt(audioBuffer, jobId, sarvamApiKey, { languageCode: 'unknown' })
          : Promise.resolve(EMPTY_STT),
        xaiApiKey
          ? callGrokStt(audioBuffer, jobId, xaiApiKey, { keyterms: tightKeyterms, language: 'hi', timeoutMs: 8000 })
          : Promise.resolve(EMPTY_STT),
      ])

      const candidates = [
        { label: 'grok_no_keyterms_no_language', outcome: grokUnbiased },
        { label: 'sarvam_language_autodetect', outcome: sarvamRetry },
        { label: 'grok_tight_catalog_keyterms', outcome: grokTight },
      ]

      for (const c of candidates) {
        const scored = scoreTranscript(c.outcome.transcript, fullCatalogList)
        passesDetail.push({
          passNumber: passesDetail.length + 1,
          variant: c.label,
          transcript: c.outcome.transcript,
          error: c.outcome.error,
          httpStatus: c.outcome.httpStatus,
          latencyMs: c.outcome.latencyMs,
          model: c.outcome.model ?? null,
          score: scored.score,
          adopted: false,
        })
        if (scored.score > bestScore) {
          bestScore = scored.score
          chosenRaw = c.outcome.transcript
          step3Segments = scored.segments
          transcript = normalizeTranscript(c.outcome.transcript, fullCatalogList)
          passesDetail[passesDetail.length - 1].adopted = true
          // Surface the winning re-decode to the AI step as well.
          if (c.label.startsWith('grok')) rawGrokTranscript = c.outcome.transcript
          else rawSarvamTranscript = c.outcome.transcript
          sttProvider = providerOf(rawGrokTranscript, rawSarvamTranscript) + '+redecode'
        }
      }
      console.log(`Re-decode for ${jobId}: ${passesDetail.length} variants, bestScore now ${bestScore}`)
    }

    console.log(`Segmented transcript: "${transcript}" (Grok: "${rawGrokTranscript}", Sarvam: "${rawSarvamTranscript}", score ${bestScore})`)

    // Step 4: Multi-item interpretation via Grok AI using Phonetic-Aware Shopkeeper System Prompt
    const catalogContextStr = fullCatalogList.join(', ')

    let parsedRawItems: any[] = []
    let aiError: string | null = null
    let aiModelUsed: string | null = null

    if ((rawGrokTranscript || rawSarvamTranscript || transcript) && xaiApiKey) {
      const systemPrompt = `You are a phonetic-aware parser for an Indian shopkeeper's voice orders.
Shopkeepers speak rapidly in Hindi, Hinglish (romanized Hindi), or English —
often mixed in the same sentence, often without pauses between words.

The transcript you receive has ALREADY been through speech-to-text, which
commonly distorts it:
- drops vowel matras (चार → चर, तीन → तिन)
- fuses adjacent words when spoken fast (किलो सोना → ग्लोसोना, चार किलो → चरगलो)
- confuses similar-sounding consonants (क↔ग, ब↔प, स↔श)
- sometimes decodes Hindi audio into ANOTHER LANGUAGE's spelling entirely.
  Real example: "तीन किलो सेब" (3 kg apples) came back as the Malay-looking
  "tinggal sebab". Read such output PHONETICALLY, not semantically — "tinggal"
  is "teen kilo" and "sebab" is "seb", regardless of what those strings mean
  in some other language. NEVER treat a foreign word's dictionary meaning as
  the shopkeeper's intent.

Every order ALWAYS follows this pattern, repeated per item:
[QUANTITY] [UNIT] [ITEM NAME]

Your job: recover the shopkeeper's actual intent by treating the transcript
as a phonetic approximation, not literal text.

Rules:
1. If a token doesn't exactly match a known number, unit, or catalog item,
   find the closest phonetic match among:
   - Numbers: एक/1/ek, दो/2/do, तीन/3/teen, चार/4/chaar, पांच/5/paanch, आधा/0.5, पाव/0.25, ढाई/2.5, डेढ़/1.5
   - Units: किलो/kg/kilo, ग्राम/gram/g, लीटर/litre/l, पैकेट/packet/pkt, Piece/pc/पीस, Dozen/दर्जन
   - Catalog items: ${catalogContextStr}
2. If one token looks like multiple fused words (e.g. "चरगलो", "ग्लोसोना", "tinggal"),
   split it at whichever point best matches known vocabulary — approximate
   phonetic closeness counts, exact spelling match is NOT required.
3. Accept any mix of Devanagari, Hinglish, and English in the same sentence.
4. If a word still matches nothing after phonetic reasoning, keep it as a
   new item name (clean Hinglish transliteration) — never return empty.
5. Report confidence per item: high (0.85+) for exact or phonetically matched catalog items; lower (0.4-0.7) for unlisted items.
6. STT confuses aspirated/unaspirated consonant pairs constantly — this is
   the single most common Hindi STT error, so weight it heavily when a raw
   token almost-but-not-quite matches a catalog item:
   क↔ख, ग↔घ, च↔छ, ज↔झ, ट↔ठ, ड↔ढ, त↔थ, द↔ध, प↔फ, ब↔भ.
   Example: STT token "बिंडी" (unaspirated ब) against catalog item "Bhindi"
   is a near-exact phonetic match (भिंडी, aspirated भ) — prefer it over a
   dissimilar catalog item just because that item's name happens to be
   closer in raw spelling.
7. The "Preprocessed Transcript" below was produced by a separate rule-based
   phonetic segmenter, NOT by you. It is usually right but can be confidently
   wrong, especially for item names outside its vocabulary. Treat it as one
   hint among three. When it conflicts with what the raw STT transcripts plus
   your own phonetic/catalog reasoning suggest, trust your own reasoning.
8. RATE UPDATES vs BULK TOTAL SALES vs AMBIGUOUS NUMBERS:
   - RATE UPDATE (Item name + price number + Rupee word, e.g. "गोल्ड पचास रुपये", "आलू 50 रुपये", "प्याज 30 Rs", with NO quantity before item):
     Set "price_intent": "RATE_UPDATE", "price_at_sale": 50, "quantity": 1, "total": 50, "confidence": 0.90.
   - BULK TOTAL SALE (Quantity/unit before item + item name + total price + Rupee word at end, e.g. "पाँच पैकेट गोल्ड दो सौ पचास रुपये", "5 KG Aaloo 200 Rs"):
     Set "price_intent": "BULK_SALE_TOTAL", "quantity": 5, "unit": "PACKET", "total": 250, "price_at_sale": 50 (total / quantity), "confidence": 0.90.
   - STANDARD SALE (Quantity/unit + item name, no price or Rupee word, e.g. "पाँच आलू", "2 KG Pyaz"):
     Set "price_intent": "NONE", "quantity": 5, "unit": "PIECE", "price_at_sale": 0, "total": 0, "confidence": 0.85+.
   - AMBIGUOUS NUMBER (Number present at end without any Rupee word, e.g. "पाँच आलू पचास"):
     Set "price_intent": "AMBIGUOUS_UNTRUSTED", "confidence": 0.40.

Output ONLY valid JSON, no prose, in exactly this shape:
{
  "parsed_items": [
    {
      "item_name": "<clean title-case Hinglish or English item name, e.g. Baingan, Sona, Aaloo, Paneer>",
      "quantity": <number>,
      "unit": "<KG|GRAM|LITRE|ML|PACKET|PIECE|DOZEN>",
      "price_at_sale": <number, 0 if not spoken>,
      "total": <number, 0 if not spoken>,
      "price_intent": "<NONE|RATE_UPDATE|BULK_SALE_TOTAL|AMBIGUOUS_UNTRUSTED>",
      "confidence": <number 0.0 to 1.0>,
      "matched_catalog": <boolean>
    }
  ]
}`

      const userPrompt = `Shop catalog: ${catalogContextStr}

DUAL STT TRANSCRIPTIONS RECEIVED (these are the actual audio-derived signal —
weight them over the preprocessed transcript):
- Grok STT Transcript: "${rawGrokTranscript || '(none)'}"
- Sarvam AI STT Transcript: "${rawSarvamTranscript || '(none)'}"
- Preprocessed Transcript (rule-based phonetic segmenter, may be wrong — see rule 7): "${transcript || '(none)'}"

Parse this order.`

      // Walk the model chain on deprecation errors, exactly as the STT calls do. This
      // stage returned "Model not found: grok-2-latest" on every job in the ISSUE-021
      // trace, which meant the app's most capable interpreter had been silently absent
      // the whole time and every sale was riding on the rule-based segmenter alone.
      for (const model of modelChain(XAI_CHAT_MODELS, knownGoodChatModel)) {
        const chatController = new AbortController()
        // 8s was too tight — trace e0b68f80 timed out here, which silently removed the
        // only stage capable of arbitrating between the two disagreeing transcripts and
        // left the rule-based segmenter's guess to be booked unchallenged.
        const chatTimeoutId = setTimeout(() => chatController.abort(), AI_CHAT_TIMEOUT_MS)
        try {
          const grokResp = await fetch('https://api.x.ai/v1/chat/completions', {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${xaiApiKey}`,
              'Content-Type': 'application/json'
            },
            body: JSON.stringify({
              model,
              // A real user turn plus JSON mode: the previous version put everything in
              // a single system message with no user turn and no response_format, which
              // is a weak configuration for structured extraction.
              messages: [
                { role: 'system', content: systemPrompt },
                { role: 'user', content: userPrompt },
              ],
              response_format: { type: 'json_object' },
              temperature: 0,
              ...(supportsReasoningEffort(model) ? { reasoning_effort: XAI_REASONING_EFFORT } : {}),
            }),
            signal: chatController.signal
          })
          clearTimeout(chatTimeoutId)

          if (grokResp.ok) {
            const grokData = await grokResp.json()
            const content = grokData.choices?.[0]?.message?.content || '{}'
            const cleanContent = content.replace(/```json\s*/gi, '').replace(/```\s*/g, '').trim()
            const match = cleanContent.match(/\{[\s\S]*\}/)
            if (match) {
              const parsedJson = JSON.parse(match[0])
              if (Array.isArray(parsedJson.parsed_items)) {
                parsedRawItems = parsedJson.parsed_items
              }
            }
            knownGoodChatModel = model
            aiModelUsed = model
            aiError = null
            break
          }

          const body = await grokResp.text().catch(() => '')
          aiError = `HTTP ${grokResp.status}: ${body.slice(0, 300)}`
          if (!isModelUnavailableError(grokResp.status, body)) {
            console.error('Grok AI chat failed:', aiError)
            break
          }
          console.warn(`Grok chat model "${model}" unavailable, trying next: ${aiError}`)
        } catch (aiErr: any) {
          clearTimeout(chatTimeoutId)
          aiError = aiErr?.name === 'AbortError' ? `AI Timeout (${AI_CHAT_TIMEOUT_MS}ms limit)` : (aiErr?.message || String(aiErr))
          console.error('Grok AI multi-item interpretation error:', aiError)
          break
        }
      }
    } else if (!xaiApiKey) {
      aiError = 'XAI_API_KEY not configured'
    }

    // Segmenter fallback if Grok AI failed or returned nothing.
    if (parsedRawItems.length === 0 && step3Segments.length > 0) {
      console.log(`Using deterministic phonetic segmenter output as fallback (${step3Segments.length} items)`)
      parsedRawItems = step3Segments.map(seg => {
        const rawName = seg.itemTokens.join(' ').trim()
        const normN = rawName.charAt(0).toUpperCase() + rawName.slice(1)
        // This path only runs when Grok's own multi-item interpretation was
        // unavailable, so there's no AI double-check behind it — never self-report a
        // confidence here. Leave it unset so the catalog-match step below (which knows
        // whether this item is actually recognized) computes the real number, and cap
        // it hard if the segmenter itself flagged an ambiguous double-quantity read.
        return {
          item_name: normN,
          quantity: seg.quantity,
          unit: seg.unit,
          price: 0.0,
          confidence: seg.isSanityFlagged ? 0.3 : undefined,
          matched_catalog: false,
          // Carry the phonetic match distance forward so the confidence step below can
          // tell an exact hit apart from a barely-legal one. Without this the two are
          // indistinguishable downstream — the ISSUE-022 failure.
          item_match_norm: seg.itemMatchNorm,
          item_margin: seg.itemMargin ?? null,
          top3_candidates: seg.top3Candidates ?? [],
          // Provenance: this item came from the rule-based segmenter, NOT from the AI.
          source: 'segmenter' as const,
        }
      })
    }

    // Catalog matching & price computation. Matching is phonetic so a Hindi-spoken
    // item resolves to its English catalog row (सेब -> "Seb"), which plain substring
    // matching could never do.
    const catalogByKey = new Map<string, { id: string, name: string, price: number, unit_id: string }>()
    for (const dbItem of dbCatalogItems) {
      const k = phoneticKey(dbItem.name)
      if (k && !catalogByKey.has(k)) catalogByKey.set(k, dbItem)
    }

    // Run deterministic price intent detection on raw STT transcript (server-side mirror of VoiceParser.kt)
    const deterministicPrice = detectPriceIntent(chosenRaw)

    const finalParsedItems: any[] = parsedRawItems.map(rawItem => {
      const rawName = (rawItem.item_name || "").trim()
      const lowerN = rawName.toLowerCase()

      let matched = dbCatalogItems.find(dbItem => {
        const dbN = dbItem.name.toLowerCase().trim()
        return dbN === lowerN || lowerN.includes(dbN) || dbN.includes(lowerN)
      })
      if (!matched) matched = catalogByKey.get(phoneticKey(rawName))

      // Price Intent Determination: Deterministic Pre-Check takes precedence over LLM guess
      const intent: PriceIntent = deterministicPrice.priceIntent !== 'NONE'
        ? deterministicPrice.priceIntent
        : ((rawItem.price_intent as PriceIntent) || 'NONE')

      let qty = rawItem.quantity || 1.0
      let spokenPrice = deterministicPrice.spokenPrice ?? 0.0
      if (spokenPrice === 0.0) {
        spokenPrice = (typeof rawItem.price_at_sale === 'number' && rawItem.price_at_sale > 0)
          ? rawItem.price_at_sale
          : (typeof rawItem.price === 'number' && rawItem.price > 0)
            ? rawItem.price
            : 0.0
      }

      let priceAtSale = 0.0
      let total = 0.0

      if (intent === 'RATE_UPDATE') {
        qty = 1.0
        priceAtSale = spokenPrice
        total = spokenPrice
      } else if (intent === 'BULK_SALE_TOTAL') {
        total = spokenPrice
        priceAtSale = qty > 0 ? total / qty : total
      } else if (intent === 'AMBIGUOUS_UNTRUSTED') {
        priceAtSale = 0.0
        total = 0.0
      } else {
        priceAtSale = spokenPrice > 0 ? spokenPrice : (matched ? matched.price : 0.0)
        total = qty * priceAtSale
      }

      const isCatalogMatched = matched !== undefined
      const unit = rawItem.unit || (matched ? matched.unit_id : "PACKET")

      let confidence: number
      if (intent === 'AMBIGUOUS_UNTRUSTED') {
        confidence = 0.40
      } else if (typeof rawItem.confidence === 'number') {
        confidence = isCatalogMatched ? rawItem.confidence : Math.min(rawItem.confidence, 0.60)
      } else if (isCatalogMatched) {
        confidence = confidenceFromMatchNorm(rawItem.item_match_norm)
      } else {
        confidence = 0.60
      }

      const implausibility = implausibilityReason(unit, qty, total, chosenRaw, priceAtSale)
      if (implausibility) confidence = Math.min(confidence, IMPLAUSIBLE_CONFIDENCE_CAP)

      return {
        item_name: matched ? matched.name : rawName,
        item_id: matched ? matched.id : null,
        quantity: qty,
        unit,
        price_at_sale: priceAtSale,
        total: total,
        price_intent: intent,
        confidence: confidence,
        is_matched_to_catalog: isCatalogMatched,
        // Surfaced in the trace so a review-queue entry explains itself (Phase 0a logging).
        item_match_norm: rawItem.item_match_norm ?? null,
        item_margin: rawItem.item_margin ?? null,
        top3_candidates: rawItem.top3_candidates ?? [],
        implausibility_reason: implausibility,
        parse_source: rawItem.source ?? 'ai',
      }
    })

    // Smart Auto-Confirm Gate: Item is auto-confirmed ONLY IF:
    // 1. confidence >= 0.80 (which now encodes match quality, not just match existence)
    // 2. price_at_sale > 0.0 and total > 0.0 (No ₹0 sales in confirmed ledger!)
    // 3. valid, non-empty item name
    // 4. quantity/unit/value are plausible for a kirana shop
    const isAutoConfirmed = finalParsedItems.length > 0 && finalParsedItems.every(item =>
      item.confidence >= 0.80 &&
      item.price_at_sale > 0.0 &&
      item.total > 0.0 &&
      item.item_name &&
      item.item_name.trim().length > 0 &&
      item.item_name !== "Unrecognized Item" &&
      item.implausibility_reason === null
    )
    const finalStatus = isAutoConfirmed ? "AUTO_CONFIRMED" : "PARSED"

    // Assemble diagnostic trace
    const traceObj = {
      step_1_ptt_recording_metadata: {
        jobId,
        recordedAtMs: metadata.recordedAtMs || Date.now(),
        pressStartMs: metadata.pressStartMs || 0,
        releaseMs: metadata.releaseMs || 0,
        holdDurationMs: metadata.holdDurationMs || 0,
        audioStartMs: metadata.audioStartMs || 0,
        audioEndMs: metadata.audioEndMs || 0,
      },
      step_2_stt_proxy_response: {
        rawTranscript: chosenRaw,
        normalizedTranscript: normalizeTranscript(chosenRaw, fullCatalogList),
        grokTranscript: rawGrokTranscript,
        sarvamTranscript: rawSarvamTranscript,
        provider: sttProvider,
        audioFileSize: audioBuffer.byteLength,
        catalogKeytermsSentCount: keyterms.length,
        // Failures are recorded explicitly — an empty transcript is no longer
        // indistinguishable from silence, a bad model name, or an auth failure.
        grokStt: { error: grokOutcome.error, httpStatus: grokOutcome.httpStatus, latencyMs: grokOutcome.latencyMs },
        // `model` is the id that actually served the call, not the configured default —
        // otherwise a silent fallback down the chain is invisible in the trace.
        sarvamStt: {
          error: sarvamOutcome.error,
          httpStatus: sarvamOutcome.httpStatus,
          latencyMs: sarvamOutcome.latencyMs,
          model: sarvamOutcome.model ?? null,
          mode: sarvamOutcome.model && supportsModeParam(sarvamOutcome.model) ? SARVAM_STT_MODE : null,
        },
        transcriptScores: { grok: grokScored.score, sarvam: sarvamScored.score, adopted: bestScore },
      },
      step_3_deterministic_ordering_segmenter: {
        carryoverQty: null,
        engine: 'phonetic_grammar_lattice_v2',
        segments: step3Segments.length > 0 ? step3Segments : finalParsedItems.map(item => ({
          rawSegmentText: `${item.quantity} ${item.unit} ${item.item_name}`,
          quantity: item.quantity,
          unit: item.unit,
          itemTokens: [item.item_name],
          isSanityFlagged: item.implausibility_reason !== null,
          itemMatchNorm: item.item_match_norm
        }))
      },
      // Named for the step it belongs to, but it is NOT necessarily AI output: when the
      // chat call fails or times out, `finalParsedItems` is the rule-based segmenter's
      // reading. Trace e0b68f80 showed this field populated alongside
      // `step_4_ai_error: "AI Timeout"`, which read as though the AI had agreed with a
      // parse it never saw. `parse_source` on each item now says which stage produced it.
      step_4_grok_ai_interpretation: finalParsedItems,
      step_4_interpretation_source: aiError ? 'segmenter_fallback' : 'grok_ai',
      step_5_adaptive_audio_expansion_engine: {
        // Honest reporting: this records what was actually re-run. When no re-decode
        // was needed the pass list is empty rather than claiming a fabricated pass.
        engine: 'adaptive_redecode_v2',
        triggered: needsReDecode,
        firstPassScore: bestScore,
        rawLooksUnrecognizable,
        passesExecuted: passesDetail.length,
        passesDetail,
      },
      step_4_ai_error: aiError,
      step_4_ai_model: aiModelUsed,
      step_6_final_outcome: finalParsedItems.map(item => ({
        itemName: item.item_name,
        matchedCatalogId: item.item_id,
        quantity: item.quantity,
        unit: item.unit,
        priceAtSale: item.price_at_sale,
        estimatedTotal: item.total,
        confidence: item.confidence,
        isSanityFlagged: !isAutoConfirmed,
        autoConfirmedToLedger: isAutoConfirmed
      }))
    }

    const firstItem = finalParsedItems[0] || null

    const sttErrorSummary = [
      grokOutcome.error ? `grok: ${grokOutcome.error}` : null,
      sarvamOutcome.error ? `sarvam: ${sarvamOutcome.error}` : null,
      aiError ? `ai: ${aiError}` : null,
    ].filter(Boolean).join(' | ')

    // Final write to stt_job_logs (PERMANENT STORAGE - NEVER DELETED)
    const logPayload = {
      job_id: jobId,
      shop_id: resolvedShopId,
      recorded_at_ms: metadata.recordedAtMs || Date.now(),
      created_at: new Date(metadata.recordedAtMs || Date.now()).toISOString(),
      hold_duration_ms: metadata.holdDurationMs || 0,
      status: finalStatus,
      raw_transcript: transcript || rawGrokTranscript || rawSarvamTranscript,
      parsed_item_name: firstItem ? firstItem.item_name : "Unrecognized Item",
      parsed_qty: firstItem ? firstItem.quantity : 1.0,
      parsed_unit: firstItem ? firstItem.unit : "PACKET",
      parsed_total: firstItem ? firstItem.total : 0.0,
      is_sanity_flagged: !isAutoConfirmed,
      error_message: transcript ? sttErrorSummary : (sttErrorSummary || "Empty transcript from STT"),
      diagnostic_trace_json: JSON.stringify(traceObj),
      audio_cloud_url: audioCloudUrl
    }

    await supabase.from('stt_job_logs').upsert([logPayload], { onConflict: 'job_id' })

    // Write to transactions ONLY if auto-confirmed
    if (isAutoConfirmed && finalParsedItems.length > 0) {
      const txInserts = finalParsedItems.map(item => ({
        job_id: jobId,
        shop_id: resolvedShopId,
        item_id: item.item_id,
        audio_cloud_url: audioCloudUrl,
        raw_transcript: transcript || rawGrokTranscript || rawSarvamTranscript,
        item_name: item.item_name,
        quantity: item.quantity,
        price_at_sale: item.price_at_sale,
        total: item.total,
        payment_mode: 'CASH',
        source: 'VOICE',
        timestamp: new Date().toISOString()
      }))
      await supabase.from('transactions').upsert(txInserts, { onConflict: 'job_id' })
      console.log(`Inserted ${txInserts.length} auto-confirmed transactions for job ${jobId}`)
    }

    // Always keep permanent record in unmatched_queue if not auto-confirmed (status: PENDING)
    if (!isAutoConfirmed || finalParsedItems.length === 0) {
      await supabase.from('unmatched_queue').upsert([{
        job_id: jobId,
        shop_id: resolvedShopId,
        audio_ref: audioCloudUrl,
        raw_transcript: transcript || rawGrokTranscript || rawSarvamTranscript || "Voice Recording (Pending Review)",
        status: 'PENDING',
        timestamp: new Date().toISOString()
      }], { onConflict: 'job_id' })
      console.log(`Routed unlisted voice job ${jobId} to unmatched_queue (shop_id: ${resolvedShopId})`)
    }

    console.log(`Job ${jobId} finished background processing with status ${finalStatus}`)

  } catch (err: any) {
    console.error(`Unhandled exception in background processing for job ${jobId}:`, err)
    try {
      await supabase.from('stt_job_logs').upsert([{
        job_id: jobId,
        shop_id: resolvedShopId,
        status: 'ERROR',
        is_sanity_flagged: true,
        error_message: (err && err.message) ? err.message : 'Unhandled background processing error',
        diagnostic_trace_json: JSON.stringify({ error: err?.message || String(err), stack: err?.stack || "" }),
        audio_cloud_url: audioCloudUrl
      }], { onConflict: 'job_id' })

      await supabase.from('unmatched_queue').upsert([{
        job_id: jobId,
        shop_id: resolvedShopId,
        audio_ref: audioCloudUrl,
        raw_transcript: 'Processing failed — system error',
        status: 'ERROR',
        timestamp: new Date().toISOString()
      }], { onConflict: 'job_id' })
    } catch (writeErr) {
      console.error(`Failed to write ERROR status for job ${jobId}:`, writeErr)
    }
  }
}
