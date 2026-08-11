import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'
import {
  segmentTranscript,
  normalizeTranscript,
  phoneticKey,
  normalizedDistance,
  normalizedLiteralDistance,
  normalizeUnit,
  baseUnitOf,
  unitMultiplier,
  parseHindiOrNumericValue,
  DEFAULT_ITEM_VOCAB,
  HINDI_NUMBER_MAP,
  UNIT_SET,
  DISCOURSE_PARTICLES,
  type RawItemSegment,
  type NumeralRejoin,
} from './phonetic.ts'
import {
  detectPriceIntent,
  classifySegmentPriceIntent,
  implausibilityReason,
  extractSpokenNumbers,
  type PriceIntent,
} from './price_intent.ts'
import { canonicalOf, getQualifiers } from './lexicon.ts'
import { resolveItemName, unpricedLineReason, assessAiNameEvidence } from './item_resolution.ts'
import { conceptOfSpoken, conceptOfSku, resolveConceptToSkus, ambiguousConceptReason } from './concepts.ts'
import { classifyIntent, captureIntentFor } from './intent_router.ts'

// Deno Deploy / Supabase Edge Runtime global declaration
declare const EdgeRuntime: { waitUntil: (promise: Promise<unknown>) => void }

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

// Catalog-Learning-From-History (ISSUE-033): how many distinct recordings the SAME item
// name (by phonetic key) must recur UNMATCHED before it is auto-added to catalog_items at
// price 0. Mirrors the learned_parses promotion pattern (ISSUE-031) but for catalog
// membership rather than parse interpretation — see record_unmatched_item_observation in
// migration 20260728020000_unmatched_item_catalog_learning.sql.
const CATALOG_LEARNING_THRESHOLD = 3

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
  'grok-4.20-0309-non-reasoning',  // ISSUE-117: step 4 is structured extraction, not
                                   // reasoning. Measured 3849ms of a 5523ms job on
                                   // grok-4.5 (trace 54e7fe50). Also cheaper:
                                   // $1.25/$2.50 vs $2.00/$6.00 per 1M.
  'grok-4.5',
  'grok-4.3',
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
const supportsReasoningEffort = (model: string) =>
  model.startsWith('grok-4') && !model.includes('non-reasoning')

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
    b.includes('invalid model') || b.includes('no longer') ||
    b.includes('unsupported parameter') || b.includes('unknown field') ||
    b.includes('unrecognized') || b.includes('invalid_request_error')
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
const RELIABLE_KEY_PHONES = 4

/**
 * Maps normalized phonetic distance onto confidence, scaled by information content.
 *
 * A distance-0 hit on a 2-phone key ("AN" from "हाँ") is not the same evidence as one on
 * a 7-phone key ("TANATAL"). When literalExact is false (the spoken surface form is not
 * literally equal to the catalog name), the quality term is scaled by keyLength / 4.
 */
function confidenceFromMatchNorm(
  norm: number | null | undefined,
  keyLength: number = RELIABLE_KEY_PHONES,
  literalExact: boolean = false
): number {
  if (norm === null || norm === undefined) return 0.60
  const clamped = Math.max(0, Math.min(norm, MATCH_NORM_REJECT))
  const infoFactor = literalExact ? 1.0 : Math.min(1, keyLength / RELIABLE_KEY_PHONES)
  const quality = (1 - clamped / MATCH_NORM_REJECT) * infoFactor
  return CONFIDENCE_AT_WORST_MATCH +
    (CONFIDENCE_AT_EXACT_MATCH - CONFIDENCE_AT_WORST_MATCH) * quality
}

/** Grams-per-unit / ml-per-unit for the two dimensions this catalog uses. Anything not
 *  listed (PACKET/PIECE/DOZEN) is a count, not a weight/volume, and is never converted. */
const WEIGHT_BASE_UNITS: Record<string, number> = { GRAM: 1, KG: 1000 }
const VOLUME_BASE_UNITS: Record<string, number> = { ML: 1, LITRE: 1000 }

/**
 * Returns the multiplier to apply to a spoken quantity so that `qty * factor * catalogPrice`
 * is correct regardless of which of GRAM/KG (or ML/LITRE) the shopkeeper said versus which
 * one the catalog row is priced in. Same-unit and cross-dimension (e.g. spoken PACKET
 * against a KG-priced row) both return 1 -- the latter is a real mismatch this function
 * cannot resolve, and is left to implausibilityReason's total-magnitude check (§B.2) rather
 * than silently guessing.
 */
function unitConversionFactor(spokenUnit: string, catalogUnit: string): number {
  const s = (spokenUnit || '').toUpperCase()
  const c = (catalogUnit || '').toUpperCase()
  if (s === c) return 1
  if (s in WEIGHT_BASE_UNITS && c in WEIGHT_BASE_UNITS) return WEIGHT_BASE_UNITS[s] / WEIGHT_BASE_UNITS[c]
  if (s in VOLUME_BASE_UNITS && c in VOLUME_BASE_UNITS) return VOLUME_BASE_UNITS[s] / VOLUME_BASE_UNITS[c]
  return 1
}

/**
 * True when an "item name" is really a leftover quantity — a bare number word, or a phrase
 * opening with one ("अठारह के लोग", "सत्रह की").
 *
 * Mirrors `FuzzyCatalogMatcher.isQuantityPhrase` on the client (see that doc comment for the
 * observed catalog pollution this prevents). Both sides key off HINDI_NUMBER_MAP so the two
 * definitions cannot drift as numerals are added. Only the leading token is tested, so a real
 * product name containing a number ("Amul Gold 500") still promotes normally.
 */
function isQuantityPhrase(name: string): boolean {
  const firstToken = name.toLowerCase().trim().split(/\s+/)[0]
  if (!firstToken) return true
  if (/^[\d.]+$/.test(firstToken)) return true
  if (HINDI_NUMBER_MAP[firstToken] !== undefined) return true
  return DISCOURSE_PARTICLES.has(firstToken)
}

/** Smallest sale value we will auto-confirm without a human glance. */
const MIN_PLAUSIBLE_SALE_VALUE = 5.0

/** Budget for the step-4 chat call. Env-tunable because it trades latency against how
 *  often the pipeline loses its only arbitration stage. */
// A3 (ISSUE-112): 45s was a tail nobody can wait through. Verified 2026-08-09, job
// 76892c70-6d5f-4d87-b105-6bf7bdb08a07: a SUCCESSFUL grok-4.5 chat call took 27.2s
// (sttResolvedAtMs 1288 -> parseResolvedAtMs 28497) with re-decode off, so this is the chat
// call's own tail and not an STT artifact. Past 12s the segmenter fallback below
// (parseSource='segmenter_fallback') is strictly better than making the shopkeeper wait:
// it still books to the review queue instead of vanishing. Env-tunable if this proves tight.
const AI_CHAT_TIMEOUT_MS = Number(Deno.env.get('AI_CHAT_TIMEOUT_MS') || '12000')

/** Minimum transcript score for the STT race to consider answering on one provider alone.
 *  5 = one recognised catalog item (3) + an explicit non-default unit (2). */
const FAST_STT_MIN_SCORE = Number(Deno.env.get('FAST_STT_MIN_SCORE') || '5')
/** Kill switch. Set to '1' to restore the unconditional Promise.all. */
const DISABLE_STT_RACE = Deno.env.get('DISABLE_STT_RACE') === '1'
/** Share of AI-skipped jobs that get a shadow Grok verification. 1.0 during rollout;
 *  drop to ~0.15 once the disagreement rate is established. Costs an AI call, never
 *  latency -- it runs after the response is sent and changes nothing. */
const INSPECTOR_RATE = Number(Deno.env.get('PARSE_INSPECTOR_RATE') || '1.0')




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
  const trimmed = shopIdRaw?.trim()
  if (!trimmed || trimmed.length === 0 || trimmed === '11111111-1111-1111-1111-111111111111'
      || trimmed === 'null' || trimmed === 'undefined') {
    return null
  }
  return trimmed
}

// -----------------------------------------------------------------------------
// LEARNED PARSE MEMORY (ISSUE-031)
//
// Memoizes a PROVEN Grok interpretation (item_name/quantity/unit/price_intent ONLY --
// never price/total/confidence, which are recomputed live from catalog_items on every
// hit) per shop, keyed on the phonetic signature of the transcript. See
// supabase/migrations/20260728010000_learned_parses_and_void.sql for the promotion/
// demotion rules this reads and writes against.
// -----------------------------------------------------------------------------

/** Cheap, dependency-free string hash -- good enough to detect "the catalog changed",
 *  not used for anything security-sensitive. */
function fnv1aHash(str: string): string {
  let hash = 0x811c9dc5
  for (let i = 0; i < str.length; i++) {
    hash ^= str.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0).toString(16)
}

function computeCatalogFingerprint(catalogNames: string[]): string {
  return fnv1aHash(catalogNames.map(n => n.toLowerCase().trim()).sort().join('|'))
}

/**
 * ISSUE-114: fingerprint only the catalog entries a memo actually references, not the whole
 * catalog. The whole-catalog hash meant adding one unrelated item reset every memo in the shop
 * — verified 2026-08-09: 108 rows across 8 fingerprints, 0 lifetime hits, avg observations 0.9.
 * Item names are matched case/whitespace-insensitively and sorted so the hash is order-stable.
 * A memo item absent from the catalog contributes the literal token `absent:<name>`, so a memo
 * whose item was deleted still invalidates — which is the one case the old hash got right.
 */
function computeScopedCatalogFingerprint(memoItemNames: string[], catalogNames: string[]): string {
  const norm = (n: string) => n.toLowerCase().trim()
  const catalog = new Set(catalogNames.map(norm))
  const parts = Array.from(new Set(memoItemNames.map(norm)))
    .sort()
    .map(n => (catalog.has(n) ? n : `absent:${n}`))
  return fnv1aHash(parts.join('|'))
}

/** Verified against live production data (2026-07-28): public.shops is empty and every
 *  existing catalog_items/transactions row has shop_id NULL -- this deployment runs
 *  single-tenant, with shop_id not actually populated end-to-end despite schema.sql
 *  declaring it NOT NULL. learned_parses.shop_id has a NOT NULL FK to shops, so without
 *  a sentinel to coalesce onto, the memory feature would never activate for any real
 *  traffic. See the seed row in migrations/20260728010000_learned_parses_and_void.sql. */
const DEFAULT_LEARNED_PARSE_SHOP_ID = '00000000-0000-0000-0000-000000000001'

interface MemoItemShape {
  item_name: string
  quantity: number
  unit: string
  price_intent: string
}

/** Strips a parsed item down to the pre-catalog-match interpretation this system is
 *  allowed to cache -- price/total/confidence must always be recomputed fresh. */
function toMemoShape(items: any[]): MemoItemShape[] {
  return items.map(it => ({
    item_name: (it.item_name || '').trim(),
    quantity: typeof it.quantity === 'number' ? it.quantity : 1.0,
    unit: it.unit || 'PACKET',
    price_intent: it.price_intent || 'NONE',
  }))
}

/**
 * The safety belt that makes a shorter (2-observation) promotion warm-up safe: a memo
 * is only ever trusted when the deterministic phonetic segmenter -- an entirely
 * independent engine -- resolves to the SAME item identities on THIS recording. A
 * systematic phonetic misread would have to fool both engines identically to ever pass
 * this check. Deliberately strict (item counts must match exactly) so the failure mode
 * of a corroboration miss is just "call Grok anyway", never a wrong silent skip.
 */
function itemsCorroboratedBySegmenter(items: { item_name: string }[], segments: RawItemSegment[]): boolean {
  if (!items || !segments || items.length === 0 || items.length !== segments.length) return false
  for (let i = 0; i < items.length; i++) {
    const a = phoneticKey(items[i].item_name || '')
    const b = phoneticKey(segments[i].itemTokens.join(' '))
    if (!a || !b) return false
    if (normalizedDistance(a, b) > 0.30) return false
  }
  return true
}

/** Strict signature comparison used to decide whether a canary Grok call agrees with
 *  the memo it is verifying -- any difference in item identity, quantity, or unit
 *  counts as a mismatch (favors demoting a good memo over keeping a bad one). */
function canonicalSignature(items: MemoItemShape[]): string {
  return items.map(it => `${phoneticKey(it.item_name)}:${it.quantity}:${it.unit}:${it.price_intent}`).join('|')
}

function itemsMatchCanonical(freshItems: any[], memoItems: MemoItemShape[]): boolean {
  if (!freshItems || freshItems.length !== memoItems.length) return false
  return canonicalSignature(toMemoShape(freshItems)) === canonicalSignature(memoItems)
}

/** Walks the Grok chat model chain exactly as the inline call used to -- extracted so
 *  the canary re-verification path (below) can issue the identical call without
 *  duplicating the request-building/parsing/fallback logic. */
async function callGrokChatInterpretation(
  xaiApiKey: string,
  systemPrompt: string,
  userPrompt: string,
  getKnownGood: () => string | null,
  setKnownGood: (m: string) => void,
): Promise<{ items: any[]; error: string | null; model: string | null }> {
  let items: any[] = []
  let error: string | null = null
  let model: string | null = null

  for (const m of modelChain(XAI_CHAT_MODELS, getKnownGood())) {
    const chatController = new AbortController()
    const chatTimeoutId = setTimeout(() => chatController.abort(), AI_CHAT_TIMEOUT_MS)
    try {
      const grokResp = await fetch('https://api.x.ai/v1/chat/completions', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${xaiApiKey}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          model: m,
          messages: [
            { role: 'system', content: systemPrompt },
            { role: 'user', content: userPrompt },
          ],
          response_format: { type: 'json_object' },
          temperature: 0,
          // Bounded: this is pure structured extraction over a handful of items, never
          // a long-form answer, so an unbounded budget only pays for runaway output.
          max_tokens: 1024,
          ...(supportsReasoningEffort(m) ? { reasoning_effort: XAI_REASONING_EFFORT } : {}),
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
            items = parsedJson.parsed_items
          }
        }
        setKnownGood(m)
        model = m
        error = null
        break
      }

      const body = await grokResp.text().catch(() => '')
      error = `HTTP ${grokResp.status}: ${body.slice(0, 300)}`
      if (!isModelUnavailableError(grokResp.status, body)) {
        console.error('Grok AI chat failed:', error)
        break
      }
      console.warn(`Grok chat model "${m}" unavailable, trying next: ${error}`)
    } catch (aiErr: any) {
      clearTimeout(chatTimeoutId)
      error = aiErr?.name === 'AbortError' ? `AI Timeout (${AI_CHAT_TIMEOUT_MS}ms limit)` : (aiErr?.message || String(aiErr))
      console.error('Grok AI multi-item interpretation error:', error)
      break
    }
  }
  return { items, error, model }
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

function scoreTranscript(transcript: string, catalogNames: string[], aliases: Map<string, string> = new Map()): { score: number; segments: RawItemSegment[]; numeralRejoins: NumeralRejoin[] } {
  if (!transcript || !transcript.trim()) return { score: 0, segments: [], numeralRejoins: [] }
  const { segments, numeralRejoins } = segmentTranscript(transcript, catalogNames, null, aliases)
  if (!segments.length) return { score: 0, segments: [], numeralRejoins: numeralRejoins ?? [] }

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

  return { score, segments, numeralRejoins: numeralRejoins ?? [] }
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

/**
 * Aligns each AI-parsed item to the deterministic segmenter's own per-item segment,
 * so per-item price intent (see classifySegmentPriceIntent) can be applied to the
 * right item instead of guessed positionally when counts disagree.
 *
 * When the AI returned exactly as many items as the segmenter found, pairing is
 * positional -- this is the overwhelmingly common case and phonetic matching adds
 * risk (a near-miss match could pair the wrong two items) for no benefit. When counts
 * differ (the AI split/merged something the segmenter didn't), fall back to a
 * best-effort phonetic-key match so at least the items that DID align 1:1 still get
 * their own price; anything left unmatched gets `null` and falls through to the AI's
 * own self-reported price_intent instead of a wrong deterministic override.
 */
function alignSegmentsToItems(parsedItems: any[], segments: RawItemSegment[]): (RawItemSegment | null)[] {
  if (parsedItems.length === segments.length) {
    return segments.map(s => s)
  }
  const used = new Set<number>()
  return parsedItems.map(rawItem => {
    const rawKey = phoneticKey((rawItem.item_name || '').trim())
    if (!rawKey) return null
    let bestIdx = -1
    let bestDist = Infinity
    segments.forEach((seg, idx) => {
      if (used.has(idx)) return
      const segKey = phoneticKey(seg.itemTokens.join(' '))
      if (!segKey) return
      const dist = normalizedDistance(rawKey, segKey)
      if (dist < bestDist) { bestDist = dist; bestIdx = idx }
    })
    if (bestIdx !== -1 && bestDist <= 0.35) {
      used.add(bestIdx)
      return segments[bestIdx]
    }
    return null
  })
}

/**
 * Defends against the AI leaving a quantity/unit word inside item_name instead of the
 * quantity/unit fields (prompt rule 9 asks it not to, but a real production trace
 * still returned {item_name:"पांच किलो बैंगन", quantity:1, unit:"PACKET"} for job
 * 47e1ee0b -- see ISSUE-029). Strips leading NUM/UNIT tokens from item_name and, when
 * the AI's own quantity/unit still look defaulted, substitutes the stripped values.
 */
function stripLeadingQtyUnitFromItemName(
  rawName: string,
  aiQuantity: number | undefined,
  aiUnit: string | undefined
): { name: string; quantity: number | undefined; unit: string | undefined } {
  const tokens = rawName.trim().split(/\s+/).filter(Boolean)
  let strippedQty: number | null = null
  let strippedUnit: string | null = null
  let i = 0
  while (i < tokens.length) {
    const tok = tokens[i].toLowerCase()
    const numVal = parseHindiOrNumericValue(tok)
    if (numVal !== null && numVal > 0 && strippedQty === null) {
      strippedQty = numVal
      i++
      continue
    }
    if (UNIT_SET.includes(tok) && strippedUnit === null) {
      strippedUnit = normalizeUnit(tok)
      i++
      continue
    }
    break
  }
  if (i === 0) return { name: rawName, quantity: aiQuantity, unit: aiUnit }

  const cleanedName = tokens.slice(i).join(' ').trim() || rawName
  const aiQtyLooksDefaulted = aiQuantity === undefined || aiQuantity === null || aiQuantity === 1.0
  const aiUnitLooksDefaulted = !aiUnit || aiUnit === 'PACKET'
  return {
    name: cleanedName,
    quantity: strippedQty !== null && aiQtyLooksDefaulted ? strippedQty : aiQuantity,
    unit: strippedUnit !== null && aiUnitLooksDefaulted ? strippedUnit : aiUnit,
  }
}

function buildFastPathFrom(args: {
  segments: RawItemSegment[]
  chosenRaw: string
  dbCatalogItems: Array<{ id: string, name: string, price: number, unit_id: string, image_url?: string | null, concept?: string | null }>
  isAssistant: boolean
}): { eligible: boolean, items: any[], skipReason: string | null } {
  const { segments, chosenRaw, dbCatalogItems, isAssistant } = args
  const no = (r: string) => ({ eligible: false, items: [], skipReason: r })
  if (Deno.env.get('DISABLE_FAST_PATH') === '1') return no('disabled_by_env')
  if (isAssistant) return no('assistant_intent_needs_classification')
  if (dbCatalogItems.length === 0) return no('empty_catalog')
  if (segments.length === 0) return no('no_segments')
  const lowerRaw = (chosenRaw || '').toLowerCase()
  if (['कुल', 'total', 'सब', 'मिलाकर', 'सबका'].some(w => lowerRaw.includes(w))) {
    return no('basket_total_word')
  }

  const items: any[] = []
  for (const seg of segments) {
    if (seg.resolutionKind !== 'MATCH') return no('segment_not_matched')
    if (seg.isSanityFlagged) return no('segment_sanity_flagged')
    if ((seg.itemMatchNorm ?? 1) !== 0) return no('inexact_phonetic_match')
    // A2 (ISSUE-111): a spoken price no longer forces the AI round-trip when the price is
    // deterministically unambiguous. Narrow on purpose — single segment, leading quantity,
    // an explicit rupee word, a positive parsed price, and the whole-utterance detector
    // reporting no ambiguous number. That is exactly BULK_SALE_TOTAL and nothing else:
    // RATE_UPDATE is excluded by the hasLeadingQty check below, and AMBIGUOUS_UNTRUSTED is
    // excluded by requiring rupeeWordPresent.
    if (seg.spokenPrice != null || seg.rupeeWordPresent) {
      const whole = detectPriceIntent(chosenRaw)
      const deterministicBulkTotal =
        segments.length === 1 &&
        seg.hasLeadingQty === true &&
        seg.rupeeWordPresent === true &&
        typeof seg.spokenPrice === 'number' && seg.spokenPrice > 0 &&
        typeof seg.quantity === 'number' && seg.quantity > 0 &&
        whole.priceIntent === 'BULK_SALE_TOTAL' &&
        whole.hasAmbiguousPriceNumber === false
      if (!deterministicBulkTotal) return no('spoken_price_present')
    }
    if (!seg.hasLeadingQty) return no('no_leading_quantity')

    const rawName = (seg.itemTokens || []).join(' ').trim()
    if (!rawName) return no('empty_item_name')
    if (isQuantityPhrase(rawName)) return no('item_name_is_quantity')

    const lineIdentity = canonicalOf(rawName)
    const spokenUnit = seg.unit
    const lineBaseUnit = baseUnitOf(spokenUnit)

    let hits = dbCatalogItems.filter(ci => {
      const ciCanonical = ci.canonical_key || canonicalOf(ci.name)
      const ciBase = ci.base_unit || baseUnitOf(ci.unit_id)
      return ciCanonical === lineIdentity && ciBase === lineBaseUnit
    })

    if (hits.length === 0) {
      const key = phoneticKey(rawName)
      const FAST_PATH_KEY_MAX_NORM = 0.10
      hits = dbCatalogItems.filter(ci => {
        const ciBase = ci.base_unit || baseUnitOf(ci.unit_id)
        if (ciBase !== lineBaseUnit) return false
        return phoneticKey(ci.name) === key || normalizedDistance(phoneticKey(ci.name), key) <= FAST_PATH_KEY_MAX_NORM
      })
    }

    if (hits.length === 0) {
      const identityHits = dbCatalogItems.filter(ci => (ci.canonical_key || canonicalOf(ci.name)) === lineIdentity)
      if (identityHits.length > 0) {
        return no('no_price_for_spoken_unit')
      }
      return no('item_not_in_catalog')
    }

    if (hits.length > 1) {
      const canonicals = new Set(hits.map(ci => ci.canonical_key || canonicalOf(ci.name)))
      if (canonicals.size === 1) {
        hits = [hits.reduce((prev, curr) => (curr.price > prev.price ? curr : prev))]
      }
    }
    if (hits.length !== 1) return no(hits.length === 0 ? 'item_not_in_catalog' : 'ambiguous_catalog_match')
    if (!(hits[0].price > 0)) return no('catalog_item_unpriced')

    const spokenSurface = rawName
    const matchedName = hits[0].name
    const itemKeyLen = phoneticKey(matchedName).length
    const isLitExact = normalizedLiteralDistance(spokenSurface, matchedName) === 0
    const fastConfidence = confidenceFromMatchNorm(seg.itemMatchNorm, itemKeyLen, isLitExact)
    if (fastConfidence < 0.80) return no('low_information_match')

    const { brands, varieties } = getQualifiers(rawName)
    const spokenMult = unitMultiplier(spokenUnit)
    const rowMult = unitMultiplier(hits[0].unit_id)
    const priceForSpokenUnit = hits[0].price * (spokenMult / rowMult)

    items.push({
      item_name: hits[0].name,
      quantity: seg.quantity,
      unit: seg.unit,
      price: (seg.spokenPrice != null && seg.quantity > 0)
        ? seg.spokenPrice / seg.quantity
        : priceForSpokenUnit,
      total: (seg.spokenPrice != null && seg.quantity > 0) ? seg.spokenPrice : undefined,
      price_intent: (seg.spokenPrice != null && seg.quantity > 0) ? 'BULK_SALE_TOTAL' : 'NONE',
      confidence: undefined,
      matched_catalog: true,
      item_match_norm: seg.itemMatchNorm,
      item_margin: seg.itemMargin ?? null,
      top3_candidates: seg.top3Candidates ?? [],
      source: 'segmenter' as const,
      identityResolution: {
        spokenSurface,
        identity: lineIdentity,
        brands,
        varieties,
        spokenUnit,
        baseUnit: lineBaseUnit,
        priceRowUnit: hits[0].unit_id,
        converted: spokenMult !== rowMult
      }
    })
  }
  return { eligible: items.length > 0, items, skipReason: null }
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
    const onDeviceTranscript = (formData.get('onDeviceTranscript') as string | null) || ''
    const onDeviceStatus = (formData.get('onDeviceStatus') as string | null) || ''
    const previousJobId = formData.get('previousJobId') as string | null
    const precedingGapMsRaw = formData.get('precedingGapMs') as string | null
    const precedingGapMs = precedingGapMsRaw ? parseInt(precedingGapMsRaw, 10) : -1
    // ISSUE-039: which mic recorded this job -- CREDIT_SALE must book payment_mode=CREDIT
    // and open a credits row. Server-first processing (this function can finish a job even
    // if the app was closed) means this decision cannot live client-side only.
    const captureIntent = (formData.get('captureIntent') as string | null) || 'SALE'

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

    if (resolvedShopId) {
      const { error: shopErr } = await supabase.rpc('ensure_shop', { p_shop_id: resolvedShopId })
      if (shopErr) console.error(`ensure_shop failed for ${resolvedShopId}:`, shopErr.message)
    }

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

    // Step 1: Upload audio to Storage FIRST (deferred off critical path)
    const audioBuffer = await audioFile.arrayBuffer()
    const storagePath = `${jobId}.wav`

    const uploadStartedMs = Date.now()
    const uploadPromise = withTimeout(
      supabase.storage.from('voice-recordings').upload(storagePath, audioBuffer, {
        contentType: 'audio/wav', upsert: true,
      }),
      8000,
      'Storage upload'
    ).then(({ error }: any) => {
      if (error) console.warn(`Audio storage upload warning for job ${jobId}:`, error.message)
      return Date.now() - uploadStartedMs
    }).catch((e: any) => {
      console.warn(`Audio storage upload failed/timed out for job ${jobId}:`, e?.message ?? e)
      return -1
    })
    EdgeRuntime.waitUntil(uploadPromise)

    const audioCloudUrl = `${supabaseUrl}/storage/v1/object/public/voice-recordings/${storagePath}`

    // Step 1B: Immediately mark the job QUEUED. Errors on this write were previously
    // unchecked -- if it silently failed, a job could be processed and finish (or be
    // abandoned) with literally zero row ever existing for it. See ISSUE-044/§2.3.
    const { error: queuedErr } = await supabase.from('stt_job_logs').upsert([{
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
    if (queuedErr) {
      console.error(`Failed to write QUEUED placeholder for job ${jobId}:`, queuedErr.message)
    }

    // §2.1 (Docs/assistant_speed_and_pipeline_fix_plan.md): the pipeline measurably
    // finishes in 2-4s (Grok/Sarvam STT + parse), but the client used to poll for up to
    // 30s no matter what because this endpoint always returned 202/QUEUED immediately
    // and did the real work in the background. Await the work directly and return the
    // real result inline; only fall back to the old 202+background behavior if it
    // genuinely runs long, so a slow job still finishes even if the phone gives up.
    const work = processVoiceJob({
      jobId,
      shopId: resolvedShopId,
      metadata,
      audioBuffer,
      audioCloudUrl,
      catalogNamesRaw,
      onDeviceTranscript,
      onDeviceStatus,
      previousJobId,
      precedingGapMs,
      captureIntent,
      uploadPromise,
      supabase
    })

    const INLINE_BUDGET_MS = 20000
    // §Race-condition-fix: processVoiceJob returns its result directly so the inline
    // response path does NOT re-read from DB. The previous DB read-back raced against
    // the QUEUED placeholder written at step 1B and reliably returned stale empty
    // values (raw_transcript:"", parsed_items:[]) even when the pipeline succeeded.
    type PipelineResult = { status: string; rawTranscript: string; parsedItems: any[]; diagnosticTraceJson: string } | null
    let pipelineResult: PipelineResult = null

    const raceResult = await Promise.race([
      work.then((r) => { pipelineResult = r as PipelineResult; return 'done' as const }),
      new Promise<'timeout'>((resolve) => setTimeout(() => resolve('timeout'), INLINE_BUDGET_MS))
    ])

    if (raceResult === 'timeout') {
      EdgeRuntime.waitUntil(work)
      return new Response(JSON.stringify({
        status: 'QUEUED',
        job_id: jobId,
        audio_cloud_url: audioCloudUrl,
        cached: false,
        message: 'Still processing — poll stt_job_logs for this job_id.'
      }), {
        status: 202,
        headers: { 'Content-Type': 'application/json', ...CORS_HEADERS }
      })
    }

    // Use the in-memory result returned directly from processVoiceJob. Fall back to a
    // DB read-back only if the result is null (pipeline threw before returning).
    let inlineStatus = pipelineResult?.status || 'PARSED'
    let inlineRawTranscript = pipelineResult?.rawTranscript || ''
    let inlineParsedItems: any[] = pipelineResult?.parsedItems || []
    let inlineDiagnosticTrace = pipelineResult?.diagnosticTraceJson || ''

    if (!pipelineResult) {
      // Pipeline threw an exception before returning — read back from DB as fallback.
      const { data: finishedRow } = await supabase
        .from('stt_job_logs')
        .select('status,raw_transcript,diagnostic_trace_json')
        .eq('job_id', jobId)
        .maybeSingle()
      inlineStatus = finishedRow?.status || 'PARSED'
      inlineRawTranscript = finishedRow?.raw_transcript || ''
      inlineDiagnosticTrace = finishedRow?.diagnostic_trace_json || ''
      try {
        const t = JSON.parse(inlineDiagnosticTrace || '{}')
        if (Array.isArray(t.step_4_grok_ai_interpretation)) inlineParsedItems = t.step_4_grok_ai_interpretation
      } catch (_) {}
    }

    return new Response(JSON.stringify({
      status: inlineStatus,
      job_id: jobId,
      raw_transcript: inlineRawTranscript,
      diagnostic_trace_json: inlineDiagnosticTrace,
      parsed_items: inlineParsedItems,
      audio_cloud_url: audioCloudUrl,
      cached: false
    }), {
      status: 200,
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
// Returns its final result directly so the inline response path can avoid a DB read-back
// that races against the QUEUED placeholder and reliably returns stale data.
interface ProcessVoiceJobResult {
  status: string
  rawTranscript: string
  parsedItems: any[]
  diagnosticTraceJson: string
}
async function processVoiceJob(args: {
  jobId: string
  shopId: string | null
  metadata: any
  audioBuffer: ArrayBuffer
  audioCloudUrl: string
  catalogNamesRaw: string | null
  onDeviceTranscript?: string
  onDeviceStatus?: string
  previousJobId?: string | null
  precedingGapMs?: number
  captureIntent?: string
  uploadPromise?: Promise<number>
  supabase: ReturnType<typeof createClient>
}): Promise<ProcessVoiceJobResult | null> {
  const { jobId, shopId, metadata, audioBuffer, audioCloudUrl, catalogNamesRaw, onDeviceTranscript = '', onDeviceStatus = '', previousJobId = null, precedingGapMs = -1, captureIntent = 'SALE', uploadPromise, supabase } = args
  const t0 = Date.now()
  const mark = (): number => Date.now() - t0
  const timings: Record<string, number> = {}
  const isCreditSale = captureIntent === 'CREDIT_SALE'
  // Fix 6: माल आया / खराब mics -- these describe inventory changes, not sales. Writing
  // them as CASH transactions (the old default) would corrupt every revenue and profit
  // figure the assistant later reports. WASTE books a negative stock_in row instead of a
  // fake ₹0 sale -- see Docs/gap_analysis_and_fix_plan.md Fix 6.
  const isStockCapture = captureIntent === 'STOCK_IN' || captureIntent === 'WASTE'
  const isWaste = captureIntent === 'WASTE'
  // ASSISTANT jobs get the full transcribe + parse pipeline but write no ledger rows
  // here. The client routes the parse to the right pipeline after intent classification
  // (SttWorker.handleAssistantJob), so a server-side write would double-book it.
  const isAssistant = captureIntent === 'ASSISTANT'
  const resolvedShopId = getNullSafeShopId(shopId)
  const pressCount = (metadata && metadata.pressCount) ? Number(metadata.pressCount) : 1
  const utteranceBoundaries = (metadata && Array.isArray(metadata.utteranceBoundaries)) ? metadata.utteranceBoundaries : []

  try {
    /**
     * Server-side problems, recorded INTO THE TRACE rather than only to console.
     *
     * `console.*` output from this function is not retrievable through the platform logs API
     * (only invocation lines come back), so anything logged there is effectively write-only.
     * That is not theoretical: the catalog fetch was returning a hard
     * `400 column catalog_items.image_url does not exist` on every single job, and because
     * the only record of it was a console line nobody could read, it went unnoticed while
     * every sale silently priced at 0. The trace, by contrast, is persisted to
     * stt_job_logs, synced, and rendered in the app's Diagnostic Logs screen.
     *
     * Rule of thumb: if a failure changes what the shopkeeper sees, `note()` it.
     */
    const serverDiagnostics: Array<{ stage: string, level: 'warn' | 'error', message: string }> = []
    const note = (stage: string, level: 'warn' | 'error', message: string) => {
      serverDiagnostics.push({ stage, level, message })
      const line = `[${stage}] ${message} (job ${jobId})`
      if (level === 'error') console.error(line); else console.warn(line)
    }

    let dbCatalogItems: Array<{ id: string, name: string, price: number, unit_id: string, image_url?: string | null, concept?: string | null }> = []
    // Surfaced in the trace: an empty catalog is indistinguishable from a BROKEN catalog fetch
    // from the outside, and the difference is everything.
    // A1 (ISSUE-110): Sarvam STT needs nothing but the audio, so it starts here rather than
    // after ~550ms of catalog+alias DB round-trips. Verified 2026-08-09: catalogFetchedAtMs
    // averaged 256ms and aliasesFetchedAtMs 292ms cumulative across 39 jobs, all of it ahead
    // of the first STT byte. callSarvamStt never throws (callSarvamSttOnce catches and returns
    // an SttOutcome), so holding this promise unawaited cannot produce an unhandled rejection.
    const xaiApiKey = Deno.env.get('XAI_API_KEY') || ''
    const sarvamApiKey = Deno.env.get('SARVAM_API_KEY') || ''
    const sarvamPromise: Promise<SttOutcome> = sarvamApiKey
      ? callSarvamStt(audioBuffer, jobId, sarvamApiKey)
      : Promise.resolve(EMPTY_STT)

    let catalogFetchError: string | null = null
    let catalogQuery = supabase.from('catalog_items').select('id, name, price, unit_id, image_url, concept').eq('active', true)
    if (resolvedShopId) catalogQuery = catalogQuery.eq('shop_id', resolvedShopId)

    const aliasQuery = supabase
      .from('term_aliases')
      .select('phonetic_key, canonical_value, shop_id, verified, distinct_shop_count')
      .or(`shop_id.eq.${resolvedShopId},shop_id.is.null,shop_id.eq.00000000-0000-0000-0000-000000000001`)

    const [catSettled, aliasSettled] = await Promise.allSettled([
      withTimeout(catalogQuery, 5000, 'Catalog DB fetch'),
      withTimeout(aliasQuery, 5000, 'Aliases DB fetch')
    ])

    timings.catalogFetchedAtMs = mark()
    timings.aliasesFetchedAtMs = mark()

    if (catSettled.status === 'fulfilled') {
      const { data: catData, error: catErr } = catSettled.value
      if (catErr) {
        catalogFetchError = catErr.message ?? String(catErr)
        note('catalog_fetch', 'error',
          `CATALOG FETCH FAILED for shop ${resolvedShopId}: ${catalogFetchError}. ` +
          `Pricing is DEGRADED -- every line will resolve unmatched at 0.`)
      } else if (catData && catData.length > 0) {
        dbCatalogItems = catData
        console.log(`Fetched ${dbCatalogItems.length} active catalog items from DB for price matching`)
      } else {
        note('catalog_fetch', 'warn', `Catalog fetch returned 0 rows for shop ${resolvedShopId}.`)
      }
    } else {
      const dbCatErr = catSettled.reason
      catalogFetchError = dbCatErr instanceof Error ? dbCatErr.message : String(dbCatErr)
      note('catalog_fetch', 'error', `Catalog DB fetch threw: ${catalogFetchError}`)
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

    const aliases = new Map<string, string>()
    if (aliasSettled.status === 'fulfilled') {
      const { data: aliasData, error: aliasErr } = aliasSettled.value
      if (aliasErr) {
        console.warn(`Term aliases DB fetch warning:`, aliasErr)
      } else if (aliasData && aliasData.length > 0) {
        const sorted = aliasData.sort((a, b) => {
          const scoreA = (!a.shop_id || a.shop_id === '00000000-0000-0000-0000-000000000001') ? 0 : 1
          const scoreB = (!b.shop_id || b.shop_id === '00000000-0000-0000-0000-000000000001') ? 0 : 1
          return scoreA - scoreB
        })
        for (const entry of sorted) {
          if (entry.phonetic_key && entry.canonical_value) {
            const isGlobal = !entry.shop_id || entry.shop_id === '00000000-0000-0000-0000-000000000001'
            if (isGlobal && !entry.verified && (entry.distinct_shop_count || 0) < 3) {
              continue
            }
            aliases.set(entry.phonetic_key, entry.canonical_value)
          }
        }
        console.log(`Loaded ${aliases.size} active term aliases from DB`)
      }
    } else {
      console.warn(`Term aliases DB fetch warning:`, aliasSettled.reason)
    }

    const defaultCatalogFallback = [
      "Aaloo", "Pyaz", "Tamatar", "Seb", "Desi Ghee", "Amul Gold Milk", "Sugar",
      "Paneer", "Butter", "Maggi", "Atta", "Fortune Oil", "Bhindi", "Dhaniya",
      "Baingan", "Bengan", "Sona", "Chaandi", "Moongphali", "Kaju", "Badam"
    ]

    const fullCatalogList = Array.from(new Set([...catalogNames, ...defaultCatalogFallback]))

    // ISSUE-106: the .slice(0, 100) below spent its entire budget on catalog and item
    // vocabulary, so the number words this list intends to send NEVER reached the STT
    // bias set -- which is how "तैंतीस" came back fragmented as "ते तीस". The 21-99
    // compounds are the ones that fragment (tens anchors never do), so they go first.
    const NUMERAL_KEYTERM_BUDGET = 25
    const numeralKeyterms = Object.keys(HINDI_NUMBER_MAP)
      .filter(k => /[\u0900-\u097F]/.test(k))
      .filter(k => { const v = HINDI_NUMBER_MAP[k]; return v >= 21 && v <= 99 && v % 10 !== 0 })
      .slice(0, NUMERAL_KEYTERM_BUDGET)

    const keyterms = Array.from(new Set([
      ...numeralKeyterms,
      ...fullCatalogList,
      ...DEFAULT_ITEM_VOCAB,
      ...UNIT_SET,
    ])).slice(0, 100)

    // Step 2: Dual concurrent STT (Grok + Sarvam), 8s timeout each, with fast-path race shortcut.
    // sarvamPromise was started in A1.1, before the catalog/alias fetches. Grok waits because it
    // is the only one that needs `keyterms`.
    const grokPromise: Promise<SttOutcome> = xaiApiKey
      ? callGrokStt(audioBuffer, jobId, xaiApiKey, { keyterms, language: 'hi' })
      : Promise.resolve(EMPTY_STT)

    type RaceShortcut = {
      provider: 'grok' | 'sarvam'
      outcome: SttOutcome
      scored: { score: number; segments: RawItemSegment[] }
      fastPath: { eligible: boolean; items: any[]; skipReason: string | null }
    }
    let raceShortcut: RaceShortcut | null = null
    const sttRaceTrace: Record<string, unknown> = { attempted: false, winner: null, winnerScore: null, shortcut: false, declineReason: null }

    if (!DISABLE_STT_RACE && !isAssistant && xaiApiKey && sarvamApiKey) {
      const tag = (p: Promise<SttOutcome>, provider: 'grok' | 'sarvam') => p.then(o => ({ provider, outcome: o }))
      const first = await Promise.race([tag(grokPromise, 'grok'), tag(sarvamPromise, 'sarvam')])
      const scored = scoreTranscript(first.outcome.transcript, fullCatalogList, aliases)
      sttRaceTrace.attempted = true
      sttRaceTrace.winner = first.provider
      sttRaceTrace.winnerScore = scored.score
      sttRaceTrace.winnerLatencyMs = first.outcome.latencyMs

      if (first.outcome.error) {
        sttRaceTrace.declineReason = 'winner_stt_error'
      } else if (scored.score < FAST_STT_MIN_SCORE) {
        sttRaceTrace.declineReason = `winner_score_${scored.score}_below_${FAST_STT_MIN_SCORE}`
      } else {
        const fp = buildFastPathFrom({
          segments: scored.segments, chosenRaw: first.outcome.transcript, dbCatalogItems, isAssistant,
        })
        if (fp.eligible) {
          raceShortcut = { provider: first.provider, outcome: first.outcome, scored, fastPath: fp }
          sttRaceTrace.shortcut = true
        } else {
          sttRaceTrace.declineReason = `fast_path_${fp.skipReason}`
        }
      }
    }

    let grokOutcome: SttOutcome
    let sarvamOutcome: SttOutcome
    if (raceShortcut) {
      const notAwaited: SttOutcome = { transcript: '', error: 'not awaited (STT race shortcut)', httpStatus: null, latencyMs: 0 }
      grokOutcome  = raceShortcut.provider === 'grok'   ? raceShortcut.outcome : notAwaited
      sarvamOutcome = raceShortcut.provider === 'sarvam' ? raceShortcut.outcome : notAwaited
    } else {
      ;[grokOutcome, sarvamOutcome] = await Promise.all([grokPromise, sarvamPromise])
    }
    timings.sttResolvedAtMs = mark()

    if (grokOutcome.error) note('stt_grok', 'warn', `Grok STT failed: ${grokOutcome.error}`)
    if (sarvamOutcome.error) note('stt_sarvam', 'warn', `Sarvam STT failed: ${sarvamOutcome.error}`)
    if (grokOutcome.error && sarvamOutcome.error) {
      note('stt', 'error', 'BOTH STT providers failed -- this recording cannot be transcribed.')
    }

    let rawGrokTranscript = grokOutcome.transcript
    let rawSarvamTranscript = sarvamOutcome.transcript
    let rawOnDeviceTranscript = onDeviceTranscript ? onDeviceTranscript.trim() : ''

    const providerOf = (g: string, s: string, o: string) => {
      const parts = []
      if (g) parts.push('grok')
      if (s) parts.push('sarvam')
      if (o) parts.push('ondevice')
      return parts.length > 0 ? parts.join('+') : 'none'
    }
    let sttProvider = providerOf(rawGrokTranscript, rawSarvamTranscript, rawOnDeviceTranscript)

    // Step 3: Phonetic, grammar-aware segmentation across all transcripts
    const grokScored = scoreTranscript(rawGrokTranscript, fullCatalogList, aliases)
    const sarvamScored = scoreTranscript(rawSarvamTranscript, fullCatalogList, aliases)
    const onDeviceScored = scoreTranscript(rawOnDeviceTranscript, fullCatalogList, aliases)

    let chosenRaw = ''
    const serverMaxScore = Math.max(grokScored.score, sarvamScored.score)

    if (onDeviceScored.score > serverMaxScore && rawOnDeviceTranscript) {
      const onDevNums = extractSpokenNumbers(rawOnDeviceTranscript)
      const grokNums = extractSpokenNumbers(rawGrokTranscript)
      const sarvamNums = extractSpokenNumbers(rawSarvamTranscript)
      const serverNums = Array.from(new Set([...grokNums, ...sarvamNums]))

      const hasNumericDisagreement = onDevNums.some(n => !serverNums.includes(n))
      if (!hasNumericDisagreement || serverNums.length === 0) {
        chosenRaw = rawOnDeviceTranscript
      } else {
        chosenRaw = (sarvamScored.score >= grokScored.score && rawSarvamTranscript) ? rawSarvamTranscript : (rawGrokTranscript || rawOnDeviceTranscript)
      }
    } else if (sarvamScored.score >= grokScored.score && rawSarvamTranscript) {
      chosenRaw = rawSarvamTranscript
    } else if (grokScored.score > sarvamScored.score && rawGrokTranscript) {
      chosenRaw = rawGrokTranscript
    } else {
      chosenRaw = rawSarvamTranscript || rawGrokTranscript || rawOnDeviceTranscript
    }

    let step3Segments: RawItemSegment[] =
      chosenRaw === rawSarvamTranscript ? sarvamScored.segments :
      chosenRaw === rawGrokTranscript ? grokScored.segments :
      onDeviceScored.segments

    let step3NumeralRejoins: NumeralRejoin[] =
      chosenRaw === rawSarvamTranscript ? sarvamScored.numeralRejoins :
      chosenRaw === rawGrokTranscript ? grokScored.numeralRejoins :
      onDeviceScored.numeralRejoins

    let bestScore = Math.max(grokScored.score, sarvamScored.score, onDeviceScored.score)
    let transcript = chosenRaw || ''

    if (raceShortcut) {
      chosenRaw = raceShortcut.outcome.transcript
      step3Segments = raceShortcut.scored.segments
      step3NumeralRejoins = raceShortcut.scored.numeralRejoins
      bestScore = raceShortcut.scored.score
      transcript = chosenRaw
      sttProvider = `${raceShortcut.provider}+raced`
    }

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
        const scored = scoreTranscript(c.outcome.transcript, fullCatalogList, aliases)
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
          step3NumeralRejoins = scored.numeralRejoins
          transcript = normalizeTranscript(c.outcome.transcript, fullCatalogList)
          passesDetail[passesDetail.length - 1].adopted = true
          // Surface the winning re-decode to the AI step as well.
          if (c.label.startsWith('grok')) rawGrokTranscript = c.outcome.transcript
          else rawSarvamTranscript = c.outcome.transcript
          sttProvider = providerOf(rawGrokTranscript, rawSarvamTranscript, rawOnDeviceTranscript) + '+redecode'
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
    let parseSource: 'grok_ai' | 'memory' | 'segmenter_fallback' | 'segmenter_fast_path' | 'forced_ai_fallback' = 'grok_ai'

    const hasAiInput = !!((rawGrokTranscript || rawSarvamTranscript || transcript) && xaiApiKey)

    /**
     * Decides whether this utterance can be booked from the deterministic segmenter alone,
     * skipping the Grok chat call (p50 7.4s -- ~92% of end-to-end latency).
     *
     * Every condition below is a reason the AI would have had nothing left to decide. The
     * gate is intentionally strict: this path books money without a second opinion, so it
     * only fires on an exact, unambiguous, fully-priced read. Any doubt -> pay for the AI.
     * `skipReason` is surfaced in the trace so a shop that never hits the fast path can be
     * diagnosed without re-running anything.
     */
    const fastPath = buildFastPathFrom({ segments: step3Segments, chosenRaw, dbCatalogItems, isAssistant })

    // Prompts are built unconditionally (pure string work, no network cost) so the
    // canary re-verification path below can issue the identical Grok call without
    // rebuilding them.
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
   - Catalog items: see the "Shop catalog" list in the user message below
2. If one token looks like multiple fused words (e.g. "चरगलो", "ग्लोसोना", "tinggal"),
   split it at whichever point best matches known vocabulary — approximate
   phonetic closeness counts, exact spelling match is NOT required.
3. Accept any mix of Devanagari, Hinglish, and English in the same sentence.
4. If a word still matches nothing after phonetic reasoning, keep it as a
   new item name (clean Hinglish transliteration). If — and only if — the
   item sounds are too few to identify anything at all (a bare vowel or a
   single syllable, e.g. "आ", "ऊ", "है"), return item_name exactly
   "Unrecognized Item" with confidence 0.2. Do NOT stretch one sound into a
   catalog word: "आ" is NOT "आलू". Returning "Unrecognized Item" is the
   correct answer there, not a failure.
5. Report confidence per item: high (0.85+) ONLY when the item sounds you
   heard are at least as many as the sounds in the name you are returning;
   0.4-0.7 for unlisted items; 0.2 when returning "Unrecognized Item".
   Never report 0.85+ for a name longer than what you actually heard.
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
8. RATE UPDATES vs BULK TOTAL SALES vs AMBIGUOUS NUMBERS — EACH ITEM HAS ITS OWN
   price_intent. One utterance can contain a RATE UPDATE for one item AND a STANDARD
   SALE for another — never let one item's price bleed onto a different item.
   Example: "आलू तीस रुपये किलो, दो किलो प्याज" is TWO items: Aaloo gets
   {price_intent:"RATE_UPDATE", price_at_sale:30, quantity:1, total:30} and Pyaz gets
   {price_intent:"NONE", quantity:2, unit:"KG", price_at_sale:0, total:0} — Pyaz must
   NOT also receive Aaloo's 30 rupee price, and Aaloo must NOT receive Pyaz's quantity.
   - RATE UPDATE (Item name + price number + Rupee word, e.g. "गोल्ड पचास रुपये", "आलू 50 रुपये", "प्याज 30 Rs", with NO quantity before item):
     Set "price_intent": "RATE_UPDATE", "price_at_sale": 50, "quantity": 1, "total": 50, "confidence": 0.90.
   - BULK TOTAL SALE (Quantity/unit before item + item name + total price + Rupee word at end, e.g. "पाँच पैकेट गोल्ड दो सौ पचास रुपये", "5 KG Aaloo 200 Rs"):
     Set "price_intent": "BULK_SALE_TOTAL", "quantity": 5, "unit": "PACKET", "total": 250, "price_at_sale": 50 (total / quantity), "confidence": 0.90.
   - STANDARD SALE (Quantity/unit + item name, no price or Rupee word, e.g. "पाँच आलू", "2 KG Pyaz"):
     Set "price_intent": "NONE", "quantity": 5, "unit": "PIECE", "price_at_sale": 0, "total": 0, "confidence": 0.85+.
   - AMBIGUOUS NUMBER (Number present at end without any Rupee word, e.g. "पाँच आलू पचास"):
     Set "price_intent": "AMBIGUOUS_UNTRUSTED", "confidence": 0.40.
9. "item_name" is a NAME ONLY — it must never contain a quantity or unit word.
   WRONG: {item_name:"पांच किलो बैंगन"}. RIGHT: {item_name:"Baingan", quantity:5, unit:"KG"}.
   If you catch yourself writing a number or a unit word inside item_name, move it to
   the quantity/unit fields instead.
10. One transcript below is marked [ADOPTED]. It scored highest on resolving to real
   shopkeeper vocabulary, so when the transcripts DISAGREE on an item name, the ADOPTED
   reading is the better acoustic evidence. Do NOT substitute a word that appears only
   in a non-adopted transcript unless the adopted reading matches no known vocabulary
   at all. Real failure this rule exists to prevent: the shopkeeper said "पाँच किलो अमचूर",
   Sarvam [ADOPTED] transcribed "अमचूर" correctly, Grok misheard "अंगूर" — and the parse
   came back "Angoor", corrupting a transcript that was already right.
11. FRAGMENTED NUMBERS — Hindi compound numerals 21-99 are ONE word (तैंतीस = 33,
   बावन = 52, बानवे = 92), but STT often breaks them into two tokens: "ते तीस" is
   तैंतीस (33), NOT 30; "बा वन" is बावन (52); "बान वे" is बानवे (92). When a short
   unrecognizable fragment sits immediately before a token that could complete a
   numeral, read the two together as one number and do NOT emit the fragment as an
   item name. Real failure this rule exists to prevent: "तैंतीस किलो आलू" (33 kg) was
   booked as 30 kg because "ते" was treated as a separate word.
   If you cannot tell which numeral it is, set confidence to 0.6 so a human confirms.

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

      const boundaryHintStr = (pressCount > 1 && utteranceBoundaries.length > 0)
        ? `\n- Soft Utterance Boundaries (${pressCount} rapid presses coalesced into this recording): ${utteranceBoundaries.map((b: any, idx: number) => `Press #${idx + 1}: ${b.pressOffsetMs}ms to ${b.releaseOffsetMs}ms`).join(', ')}. Prefer placing sale line splits near these boundary offsets, but cross them if grammar/quantity requires it.`
        : ''

      const userPrompt = `Shop catalog: ${catalogContextStr}

DUAL STT TRANSCRIPTIONS RECEIVED (these are the actual audio-derived signal —
weight them over the preprocessed transcript). Exactly one is marked ADOPTED: it won
a scoring pass measuring how well it resolves to real shopkeeper vocabulary, so it is
the strongest acoustic evidence available — see rule 10.
- Grok STT Transcript${chosenRaw === rawGrokTranscript && rawGrokTranscript ? ' [ADOPTED]' : ''}: "${rawGrokTranscript || '(none)'}"
- Sarvam AI STT Transcript${chosenRaw === rawSarvamTranscript && rawSarvamTranscript ? ' [ADOPTED]' : ''}: "${rawSarvamTranscript || '(none)'}"
- Preprocessed Transcript (rule-based phonetic segmenter, may be wrong — see rule 7): "${transcript || '(none)'}"${boundaryHintStr}

Parse this order.`

    // ---- Learned Parse Memory lookup (ISSUE-031) ----
    // Coalesces onto DEFAULT_LEARNED_PARSE_SHOP_ID when the request carries no real
    // shop_id (verified to be the norm in production today, not the exception -- see
    // that constant's comment) so the feature actually activates instead of silently
    // never firing.
    const memoKey = phoneticKey(normalizeTranscript(chosenRaw, fullCatalogList))
    const catalogFingerprint = computeCatalogFingerprint(fullCatalogList)
    const memoryShopId = resolvedShopId || DEFAULT_LEARNED_PARSE_SHOP_ID
    const memoryEnabled = !!memoKey && !raceShortcut

    let memoryHit: { canonical_items: MemoItemShape[] } | null = null
    if (memoryEnabled) {
      try {
        const { data: memoRow } = await withTimeout(
          supabase.from('learned_parses')
            .select('canonical_items, catalog_fingerprint, promoted, permanently_blocked')
            .eq('shop_id', memoryShopId)
            .eq('memo_key', memoKey)
            .maybeSingle(),
          3000,
          'learned_parses lookup'
        )
        if (memoRow && (memoRow as any).promoted && !(memoRow as any).permanently_blocked) {
          const memoItems = (memoRow as any).canonical_items as MemoItemShape[]
          const stored = (memoRow as any).catalog_fingerprint as string | null
          const scoped = computeScopedCatalogFingerprint(
            (memoItems || []).map(i => i.item_name), fullCatalogList
          )
          // `stored === null` is the post-migration state for rows carrying a legacy
          // whole-catalog hash (migration 20260809010000). Accepting null is safe because a
          // memo is still only ever USED when itemsCorroboratedBySegmenter agrees on this
          // recording; the next observation write below backfills the scoped value.
          if (stored === null || stored === scoped) {
            memoryHit = { canonical_items: memoItems }
          }
        }
      } catch (memoLookupErr) {
        console.warn(`learned_parses lookup failed for ${jobId}, falling back to Grok:`, memoLookupErr)
      }
    }

    // The safety belt: a memo is only ever used when the deterministic segmenter (an
    // entirely independent engine) resolves to the SAME item identities on THIS
    // recording. A miss here just means "call Grok anyway" -- never a wrong skip.
    const memoCorroborated = memoryHit ? itemsCorroboratedBySegmenter(memoryHit.canonical_items, step3Segments) : false

    // Permanent insurance premium on the promotion warm-up being only 2 observations:
    // a sample of hits still gets a real (background, non-blocking) Grok call to
    // verify against. Env-tunable; higher than the eventual steady-state rate because
    // this pipeline skipped the "watch a week of logs before enabling the skip" step.
    const CANARY_RATE = Number(Deno.env.get('LEARNED_PARSE_CANARY_RATE') || '0.25')
    const isCanarySample = !!(memoryHit && memoCorroborated) && Math.random() < CANARY_RATE

    if (memoryHit && memoCorroborated) {
      parsedRawItems = memoryHit.canonical_items.map(it => ({
        item_name: it.item_name,
        quantity: it.quantity,
        unit: it.unit,
        price_at_sale: 0,
        total: 0,
        price_intent: it.price_intent,
        confidence: undefined,
        matched_catalog: false,
        source: 'memory' as const,
      }))
      aiModelUsed = 'memory'
      aiError = null
      parseSource = 'memory'

      if (isCanarySample && xaiApiKey) {
        const canaryMemoItems = memoryHit.canonical_items
        const canaryShopId = memoryShopId
        const canaryMemoKey = memoKey
        EdgeRuntime.waitUntil((async () => {
          const canaryResult = await callGrokChatInterpretation(
            xaiApiKey, systemPrompt, userPrompt,
            () => knownGoodChatModel, (m) => { knownGoodChatModel = m }
          )
          if (canaryResult.error || canaryResult.items.length === 0) return
          if (!itemsMatchCanonical(canaryResult.items, canaryMemoItems)) {
            console.warn(`Canary mismatch for job ${jobId}, memo ${canaryMemoKey}: demoting`)
            await supabase.rpc('reset_learned_parse', {
              p_shop_id: canaryShopId,
              p_memo_key: canaryMemoKey,
              p_reason: 'canary_mismatch',
              p_increment_corrections: true,
            })
          }
        })().catch(e => console.warn(`Canary verification failed for ${jobId}:`, e)))
      }
    } else if (raceShortcut) {
      parsedRawItems = raceShortcut.fastPath.items
      aiModelUsed = 'fast_path'
      aiError = null
      parseSource = 'segmenter_fast_path'
    } else if (fastPath.eligible) {
      // Deterministic fast path -- no AI round-trip at all.
      //
      // Measured on a 67-recording replay of this shop's own audio (2026-08-06): the Grok
      // chat call was p50 7.4s of an 8.1s end-to-end, i.e. ~92% of the wait, while the
      // deterministic segmenter had ALREADY produced the same line structure in 53 of 55
      // AI jobs. The AI's real contribution on those was canonicalisation (आम -> Aam) and
      // dropping stray tokens -- both of which are catalog lookups, not reasoning.
      //
      // So when the segmenter exactly matched every segment to a PRICED catalog row, with
      // no spoken-price ambiguity and nothing sanity-flagged, the AI has nothing left to
      // decide and we skip it. Gate deliberately narrow (see buildFastPath): anything less
      // than an exact, unambiguous, fully-priced read still pays for the AI.
      parsedRawItems = fastPath.items
      aiModelUsed = 'fast_path'
      aiError = null
      parseSource = 'segmenter_fast_path'
      console.log(`Fast path for ${jobId}: ${fastPath.items.length} exact segment(s), AI skipped`)
    } else if (hasAiInput) {
      // Walk the model chain on deprecation errors, exactly as the STT calls do. This
      // stage returned "Model not found: grok-2-latest" on every job in the ISSUE-021
      // trace, which meant the app's most capable interpreter had been silently absent
      // the whole time and every sale was riding on the rule-based segmenter alone.
      const result = await callGrokChatInterpretation(
        xaiApiKey, systemPrompt, userPrompt,
        () => knownGoodChatModel, (m) => { knownGoodChatModel = m }
      )
      parsedRawItems = result.items
      aiError = result.error
      aiModelUsed = result.model
    } else if (!xaiApiKey) {
      aiError = 'XAI_API_KEY not configured'
    }
    timings.parseResolvedAtMs = mark()

    // Every genuinely fresh (non-memoized) Grok success is one observation toward
    // promotion -- see record_learned_parse_observation() in the migration for the
    // full promotion rule.
    if (memoryEnabled && parseSource === 'grok_ai' && !aiError && parsedRawItems.length > 0) {
      const corroborated = itemsCorroboratedBySegmenter(parsedRawItems, step3Segments)
      try {
        await supabase.rpc('record_learned_parse_observation', {
          p_shop_id: memoryShopId,
          p_memo_key: memoKey,
          p_canonical_items: toMemoShape(parsedRawItems),
          p_catalog_fingerprint: computeScopedCatalogFingerprint(
            toMemoShape(parsedRawItems).map(i => i.item_name), fullCatalogList
          ),
          p_job_id: jobId,
          p_corroborated: corroborated,
        })
      } catch (recordErr) {
        console.warn(`record_learned_parse_observation failed for ${jobId}:`, recordErr)
      }
    }

    // Segmenter fallback if Grok AI failed or returned nothing.
    if (parsedRawItems.length === 0 && step3Segments.length > 0) {
      console.log(`Using deterministic phonetic segmenter output as fallback (${step3Segments.length} items)`)
      parseSource = 'segmenter_fallback'
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

    // Forced AI fallback when parsedRawItems is still empty but raw transcript exists
    if (parsedRawItems.length === 0 && transcript && transcript.trim().length > 0 && xaiApiKey) {
      console.log(`parsedRawItems empty after segmenter. Forcing AI fallback for transcript: "${transcript}"`)
      const forcedResult = await callGrokChatInterpretation(
        xaiApiKey, systemPrompt, userPrompt,
        () => knownGoodChatModel, (m) => { knownGoodChatModel = m }
      )
      if (forcedResult.items.length > 0) {
        parsedRawItems = forcedResult.items
        parseSource = 'forced_ai_fallback'
        aiModelUsed = forcedResult.model
        aiError = null
      }
    }

    // Fetch previous job transcript & parsed item for A4 duplicate guard & A5 orphan suggestion
    const MAX_ADJACENT_JOB_GAP_MS = 1500
    let prevJobTranscript = ''
    let prevJobItemName = ''

    if (previousJobId && precedingGapMs >= 0 && precedingGapMs <= MAX_ADJACENT_JOB_GAP_MS) {
      try {
        const { data: prevJobLog } = await supabase
          .from('stt_job_logs')
          .select('raw_transcript, parsed_item_name')
          .eq('job_id', previousJobId)
          .maybeSingle()
        if (prevJobLog) {
          prevJobTranscript = prevJobLog.raw_transcript || ''
          prevJobItemName = prevJobLog.parsed_item_name || ''
        }
      } catch (_) {}
    }

    // Catalog matching & price computation. Matching is phonetic so a Hindi-spoken
    // item resolves to its English catalog row (सेब -> "Seb"), which plain substring
    // matching could never do.
    const catalogByKey = new Map<string, { id: string, name: string, price: number, unit_id: string }>()
    for (const dbItem of dbCatalogItems) {
      const k = phoneticKey(dbItem.name)
      if (k && !catalogByKey.has(k)) catalogByKey.set(k, dbItem)
    }

    // Per-item price intent (ISSUE-029): the deterministic segmenter attaches its own
    // spokenPrice/hasLeadingQty to EACH segment while walking the token lattice (see
    // phonetic.ts segmentTranscript), so classification below is scoped to one item
    // and cannot leak into a sibling item's price the way a single whole-utterance
    // detectPriceIntent(chosenRaw) call used to. `alignedSegments[i]` is the segment
    // paired to `parsedRawItems[i]`, or null when no confident pairing was found.
    const alignedSegments = alignSegmentsToItems(parsedRawItems, step3Segments)

    // Basket-total guard: "दो किलो आलू तीन किलो प्याज दो सौ रुपये" is genuinely
    // ambiguous -- the trailing price could be Pyaz's own bulk total, or the combined
    // total for both items, and nothing in the transcript resolves that on its own.
    // Rather than guess a split, only withhold auto-confirm when the shopkeeper
    // actually said a total-indicating word ("कुल"/"total"/"मिलाकर") alongside more
    // than one item -- an ordinary "N kg X, M kg Y ₹P" with no such word is NOT
    // flagged here; that is just an unremarkable bulk sale on the last item.
    const TOTAL_INDICATOR_WORDS = ['कुल', 'total', 'सब', 'मिलाकर', 'सबका', 'grand total']
    const hasTotalIndicatorWord = TOTAL_INDICATOR_WORDS.some(w => chosenRaw.toLowerCase().includes(w))
    const isBasketTotalAmbiguous = hasTotalIndicatorWord && step3Segments.length > 1

    const aiEvidenceItemSurfaces = new Set(
      [...DEFAULT_ITEM_VOCAB, ...fullCatalogList]
        .filter(n => n && n.trim())
        .map(n => n.trim().toLowerCase())
    )

    const finalParsedItems: any[] = parsedRawItems.map((rawItem, itemIdx) => {
      const alignedSeg = alignedSegments[itemIdx]
      const stripped = stripLeadingQtyUnitFromItemName(
        (rawItem.item_name || "").trim(),
        rawItem.quantity,
        rawItem.unit
      )
      if (stripped.name !== (rawItem.item_name || "").trim()) {
        rawItem = { ...rawItem, item_name: stripped.name, quantity: stripped.quantity, unit: stripped.unit }
      }
      const aiName = (rawItem.item_name || "").trim()
      const findCatalog = (name: string, spokenUnitStr: string) => {
        const lineIdentity = canonicalOf(name)
        const lineBaseUnit = baseUnitOf(spokenUnitStr)

        const matches = dbCatalogItems.filter(ci => {
          const itemKey = ci.canonical_key || canonicalOf(ci.name)
          const itemBase = ci.base_unit || baseUnitOf(ci.unit_id)
          return itemKey === lineIdentity && itemBase === lineBaseUnit
        })

        if (matches.length > 0) {
          return { item: matches.sort((a, b) => b.price - a.price)[0], noPriceForUnit: false }
        }

        const identityHits = dbCatalogItems.filter(ci => (ci.canonical_key || canonicalOf(ci.name)) === lineIdentity)
        if (identityHits.length > 0) {
          return { item: undefined, noPriceForUnit: true, lineIdentity, lineBaseUnit }
        }

        const lowered = name.toLowerCase().trim()
        const bySubstring = dbCatalogItems.find(dbItem => {
          const dbN = dbItem.name.toLowerCase().trim()
          return dbN === lowered || lowered.includes(dbN) || dbN.includes(lowered)
        })
        if (bySubstring) {
          const subBase = bySubstring.base_unit || baseUnitOf(bySubstring.unit_id)
          if (subBase === lineBaseUnit) return { item: bySubstring, noPriceForUnit: false }
        }

        const k = phoneticKey(name)
        if (k) {
          const candidates = dbCatalogItems.filter(dbItem => {
            const dbBase = dbItem.base_unit || baseUnitOf(dbItem.unit_id)
            return dbBase === lineBaseUnit && phoneticKey(dbItem.name) === k
          })
          if (candidates.length > 0) {
            let bestItem: typeof dbCatalogItems[0] | undefined = undefined
            let bestDist = Infinity
            for (const item of candidates) {
              const dist = normalizedLiteralDistance(name, item.name)
              if (dist < bestDist) {
                bestDist = dist
                bestItem = item
              }
            }
            if (bestItem && bestDist <= 0.15) {
              return { item: bestItem, noPriceForUnit: false }
            }
          }
        }

        return { item: undefined, noPriceForUnit: false }
      }

      // When the segmenter has a near-exact match on the ADOPTED transcript and the AI
      // named something else, the segmenter wins and the line is flagged either way.
      // See item_resolution.ts / ISSUE-030 for why this is not gated on catalog match.
      // ISSUE-126: give the arbitration a notion of what each name IS, so a translation pair
      // ("चावल" vs "Basmati Rice") is not mistaken for a disagreement. findCatalogItem is the
      // same lookup used for pricing below, so "stocked" here means genuinely bookable.
      const segNameForConcept = alignedSeg ? (alignedSeg.itemTokens || []).join(' ').trim() : ''
      const conceptArbitration = segNameForConcept
        ? {
            segConcept: conceptOfSpoken(segNameForConcept) ?? conceptOfSku(segNameForConcept),
            aiConcept: conceptOfSpoken(aiName) ?? conceptOfSku(aiName),
            aiNameIsStocked: !!findCatalog(aiName, rawItem.unit || 'PACKET').item,
          }
        : undefined

      const nameResolution = resolveItemName(aiName, alignedSeg, undefined, conceptArbitration)
      const rawName = nameResolution.name
      const sttDisagreementReason = nameResolution.disagreementReason
      const segDisagreesWithAi = nameResolution.usedSegmenterOverride
      const segMatchNorm = alignedSeg?.itemMatchNorm ?? null

      const aiEvidence = assessAiNameEvidence(rawName, alignedSeg, {
        transcript: chosenRaw,
        itemSurfaces: aiEvidenceItemSurfaces,
        segmentCount: step3Segments.length,
      })

      const spokenUnitInput = rawItem.unit || "PACKET"
      const catalogResult = aiEvidence.uncorroborated
        ? { item: undefined, noPriceForUnit: false }
        : findCatalog(rawName, spokenUnitInput)

      const matched = catalogResult.item
      const noPriceForUnit = catalogResult.noPriceForUnit

      // Price Intent Determination: this item's own segment takes precedence over the
      // LLM's guess (it saw only this item's tokens); when no segment aligned to this
      // item at all, trust the AI's self-reported per-item price_intent instead.
      const segClassification = classifySegmentPriceIntent(alignedSeg)
      const intent: PriceIntent = segClassification.priceIntent !== 'NONE'
        ? segClassification.priceIntent
        : ((rawItem.price_intent as PriceIntent) || 'NONE')

      let qty = rawItem.quantity || 1.0
      let spokenPrice = segClassification.spokenPrice ?? 0.0
      if (spokenPrice === 0.0) {
        spokenPrice = (typeof rawItem.price_at_sale === 'number' && rawItem.price_at_sale > 0)
          ? rawItem.price_at_sale
          : (typeof rawItem.price === 'number' && rawItem.price > 0)
            ? rawItem.price
            : 0.0
      }

      const isCatalogMatched = matched !== undefined
      const unit = rawItem.unit || (matched ? matched.unit_id : "PACKET")

      let priceAtSale = 0.0
      let total = 0.0

      if (intent === 'RATE_UPDATE') {
        qty = 1.0
        priceAtSale = spokenPrice
        total = spokenPrice
      } else if (intent === 'BULK_SALE_TOTAL') {
        total = spokenPrice
        priceAtSale = qty > 0 ? total / qty : total
      } else if (intent === 'AMBIGUOUS_UNTRUSTED' || noPriceForUnit) {
        priceAtSale = 0.0
        total = 0.0
      } else {
        if (spokenPrice > 0) {
          // A spoken price is already in the shopkeeper's own words for THIS quantity/unit --
          // never rescale it against the catalog's denomination.
          priceAtSale = spokenPrice
          total = qty * priceAtSale
        } else if (matched) {
          const spokenMult = unitMultiplier(unit)
          const rowMult = unitMultiplier(matched.unit_id)
          priceAtSale = matched.price * (spokenMult / rowMult)
          total = qty * priceAtSale
        } else {
          priceAtSale = 0.0
          total = 0.0
        }
      }

      // Match evidence: AI-parsed items carry none of their own (item_match_norm is only
      // ever populated on the segmenter-fallback path), so fall back to the aligned
      // segment's. Without this the distance-aware confidence model from ISSUE-022 never
      // applied to AI items at all, and a review-queue entry could not explain itself.
      const matchNorm = rawItem.item_match_norm ?? segMatchNorm ?? null
      const matchMargin = rawItem.item_margin ?? alignedSeg?.itemMargin ?? null
      const top3 = (rawItem.top3_candidates && rawItem.top3_candidates.length > 0)
        ? rawItem.top3_candidates
        : (alignedSeg?.top3Candidates ?? [])

      let confidence: number
      if (intent === 'AMBIGUOUS_UNTRUSTED') {
        confidence = 0.40
      } else if (typeof rawItem.confidence === 'number') {
        confidence = isCatalogMatched ? rawItem.confidence : Math.min(rawItem.confidence, 0.60)
      } else if (isCatalogMatched) {
        const spokenSurface = (alignedSeg?.itemTokens || []).join(' ').trim() || rawItem.item_name
        const matchedCatalogName = matched?.name || ''
        const itemKeyLength = phoneticKey(matchedCatalogName || spokenSurface).length
        const isLiteralExact = matchedCatalogName ? (normalizedLiteralDistance(spokenSurface, matchedCatalogName) === 0) : false
        confidence = confidenceFromMatchNorm(matchNorm, itemKeyLength, isLiteralExact)
      } else {
        confidence = 0.60
      }

      let implausibility = implausibilityReason(unit, qty, total, chosenRaw, priceAtSale, isStockCapture ? 'STOCK' : 'SALE')

      if (sttDisagreementReason) {
        implausibility = implausibility ? `${implausibility} | ${sttDisagreementReason}` : sttDisagreementReason
      }

      if (alignedSeg?.resolutionKind === 'AMBIGUOUS') {
        const ambigReason = `phonetic match is ambiguous (margin below threshold)`
        implausibility = implausibility ? `${implausibility} | ${ambigReason}` : ambigReason
      } else if (alignedSeg?.resolutionKind === 'UNKNOWN') {
        const unkReason = `phonetic key is unrecognized (not in vocabulary)`
        implausibility = implausibility ? `${implausibility} | ${unkReason}` : unkReason
      }

      if (alignedSeg?.numeralRejoinLowMargin) {
        const rejoinReason = `Quantity ${qty} was reconstructed from a split number word and a different number scored nearly as close -- confirm the amount`
        implausibility = implausibility ? `${implausibility} | ${rejoinReason}` : rejoinReason
      }

      // ISSUE-106: a segmenter segment that no AI item consumed means part of the
      // utterance went unexplained. Auto-confirming the rest asserts we understood the
      // whole recording when we demonstrably did not.
      if (itemIdx === 0 && step3Segments.length > parsedRawItems.length &&
          step3Segments.some(s => s.isSanityFlagged || s.resolutionKind !== 'MATCH')) {
        const orphanReason = `Part of the recording did not resolve to any item -- review before booking`
        implausibility = implausibility ? `${implausibility} | ${orphanReason}` : orphanReason
      }

      if (aiEvidence.uncorroborated) {
        const heard = aiEvidence.heardSurface
          ? `only '${aiEvidence.heardSurface}' was audible (${aiEvidence.heardPhones} sound${aiEvidence.heardPhones === 1 ? '' : 's'})`
          : `no item name was audible at all`
        const inventedReason = `couldn't make out the item — ${heard}, too little to confirm '${rawName}'`
        implausibility = implausibility ? `${implausibility} | ${inventedReason}` : inventedReason
      }

      // P0-2: Never auto-confirm a defaulted quantity on a squeezed window
      const pressStartMs = metadata.pressStartMs || 0
      const audioStartMs = metadata.audioStartMs || 0
      const preRollActualMs = (pressStartMs > 0 && audioStartMs > 0) ? (pressStartMs - audioStartMs) : 300

      const tokens = chosenRaw.trim().split(/\s+/).filter(Boolean)
      const firstTokenNorm = tokens[0] ? tokens[0].toLowerCase() : ''
      const isFirstTokenUnit = UNIT_SET.includes(firstTokenNorm)
      const transcriptHasExplicitNumber = extractSpokenNumbers(chosenRaw).length > 0 || /\d+/.test(chosenRaw) || Object.keys(HINDI_NUMBER_MAP).some(k => chosenRaw.toLowerCase().includes(k.toLowerCase()))
      const isDefaultedQty = isFirstTokenUnit || (qty === 1.0 && !transcriptHasExplicitNumber)

      if (preRollActualMs < 150 && isDefaultedQty) {
        const clipReason = `leading quantity may have been clipped (pre-roll only ${preRollActualMs}ms)`
        implausibility = implausibility ? `${implausibility} | ${clipReason}` : clipReason
      }

      // The client's RollingAudioBuffer holds exactly 30s of PCM; a hold at or beyond
      // that silently loses its OWN leading audio (minStartAllowed truncates the window
      // front with no error signal) -- a long multi-item recitation is exactly the case
      // that can hit this. Never auto-confirm a line whose recording may have been
      // truncated. See ISSUE-029.
      if ((metadata.holdDurationMs || 0) >= 29000) {
        const longHoldReason = `recording held ${metadata.holdDurationMs}ms, at/beyond the 30s rolling buffer -- leading audio may have been truncated`
        implausibility = implausibility ? `${implausibility} | ${longHoldReason}` : longHoldReason
      }

      // Burst Coalescing Cross-Check: Quantity-mention count vs segmented-line count
      const spokenNumbersCount = extractSpokenNumbers(chosenRaw).length
      const totalParsedLines = parsedRawItems ? parsedRawItems.length : 1
      if (pressCount > 1 && spokenNumbersCount > 0 && Math.abs(spokenNumbersCount - totalParsedLines) > 0) {
        const burstMismatchReason = `quantity-mention count (${spokenNumbersCount}) disagrees with segmented line count (${totalParsedLines}) on burst recording (${pressCount} presses)`
        implausibility = implausibility ? `${implausibility} | ${burstMismatchReason}` : burstMismatchReason
      }

      // P0-3: Make rawLooksUnrecognizable block auto-confirm
      if (rawLooksUnrecognizable) {
        const unrecReason = `transcript text looks unrecognized / corrupt`
        implausibility = implausibility ? `${implausibility} | ${unrecReason}` : unrecReason
      }

      if (rawLooksUnrecognizable && matched && rawName && rawName.trim().length > 0 && rawName !== matched.name) {
        const disagreeReason = `AI item '${rawName}' disagrees with segmenter match '${matched.name}' on unrecognizable raw text`
        implausibility = implausibility ? `${implausibility} | ${disagreeReason}` : disagreeReason
      }

      // A4: Cross-job duplicate number guard
      if (prevJobTranscript) {
        const prevNumbers = extractSpokenNumbers(prevJobTranscript)
        const currNumbers = extractSpokenNumbers(chosenRaw)
        const sharedNumbers = currNumbers.filter(n => prevNumbers.includes(n))

        if (sharedNumbers.length > 0) {
          const loadBearingCollision = sharedNumbers.find(n => Math.abs(qty - n) < 0.01 || Math.abs(priceAtSale - n) < 0.01 || Math.abs(total - n) < 0.01)
          if (loadBearingCollision !== undefined) {
            const dupReason = `Spoken number ${loadBearingCollision} collides with adjacent job ${previousJobId} (gap ${precedingGapMs}ms)`
            implausibility = implausibility ? `${implausibility} | ${dupReason}` : dupReason
          }
        }
      }

      if (isBasketTotalAmbiguous) {
        const basketReason = `spoken total may cover the whole basket, not just this item`
        implausibility = implausibility ? `${implausibility} | ${basketReason}` : basketReason
      }

      if (noPriceForUnit) {
        const noPriceReason = `no_price_for_spoken_unit (item '${canonicalOf(rawName)}' has no price line for unit ${unit})`
        implausibility = implausibility ? `${implausibility} | ${noPriceReason}` : noPriceReason
      }

      // ISSUE-126 Stage 1b: an item whose CONCEPT resolves to more than one stocked SKU in
      // this shop must never auto-confirm on either engine's silent pick -- neither the
      // segmenter's alias-forced guess (दूध -> Amul Gold Milk today, via the seeded
      // term_aliases row) nor the AI's ungrounded one (Saras Milk, zero evidence in job
      // b6ebbef5) is grounds to charge a specific brand's price. Route to review naming
      // every real candidate instead. Deliberately independent of `matched`/isCatalogMatched
      // -- this must catch the case where the alias mechanism ALREADY produced a confident
      // exact-distance "match" for the wrong reason. See Docs/concept_layer_plan.md.
      const finalNameConcept = conceptOfSpoken(rawName) ?? conceptOfSku(rawName)
      const conceptResolution = resolveConceptToSkus(
        finalNameConcept,
        chosenRaw,
        dbCatalogItems.map(ci => ({ id: ci.id, name: ci.name, price: ci.price, unit_id: ci.unit_id, concept: ci.concept ?? null }))
      )
      if (conceptResolution.kind === 'AMBIGUOUS') {
        const ambigReason = ambiguousConceptReason(conceptResolution.concept!, conceptResolution.candidates)
        implausibility = implausibility ? `${implausibility} | ${ambigReason}` : ambigReason
      }

      // The reason the shopkeeper actually reads on the review card. See ISSUE-030.
      const priceReason = unpricedLineReason(rawName, isCatalogMatched, priceAtSale, total, intent)
      if (priceReason) {
        implausibility = implausibility ? `${implausibility} | ${priceReason}` : priceReason
      }

      // P1: Narrowed Orphan Definition — genuine orphans are price/qty announcements with NO item name at all
      const hasItemName = rawName && rawName.trim().length > 0 && rawName !== "Unrecognized Item"
      const isOrphan = !hasItemName && (priceAtSale > 0 || total > 0) && intent !== 'RATE_UPDATE'
      if (isOrphan) {
        const orphanReason = `Price/quantity specified without any item name (orphan sale)`
        implausibility = implausibility ? `${implausibility} | ${orphanReason}` : orphanReason
      }

      if (implausibility) confidence = Math.min(confidence, IMPLAUSIBLE_CONFIDENCE_CAP)
      if (aiEvidence.uncorroborated) confidence = Math.min(confidence, 0.30)

      const { brands, varieties } = getQualifiers(rawName)
      const lineIdentity = canonicalOf(rawName)
      const lineBaseUnit = baseUnitOf(unit)
      const spokenMult = unitMultiplier(unit)
      const rowMult = matched ? unitMultiplier(matched.unit_id) : 1.0
      const isConverted = matched ? (spokenMult !== rowMult || matched.unit_id !== normalizeUnit(unit)) : false

      return {
        item_name: matched ? matched.name : (aiEvidence.uncorroborated ? "Unrecognized Item" : rawName),
        item_id: matched ? matched.id : null,
        quantity: qty,
        unit,
        price_at_sale: priceAtSale,
        total: total,
        price_intent: intent,
        confidence: confidence,
        is_matched_to_catalog: isCatalogMatched,
        is_orphan: isOrphan,
        suggested_item_name: isOrphan && prevJobItemName ? prevJobItemName : null,
        // Surfaced in the trace so a review-queue entry explains itself (Phase 0a logging).
        // These now fall back to the aligned segment's evidence for AI-parsed items,
        // which previously reported all-null here (ISSUE-030).
        item_match_norm: matchNorm,
        item_margin: matchMargin,
        top3_candidates: top3,
        ai_evidence: {
          ratio: aiEvidence.ratio,
          heardSurface: aiEvidence.heardSurface,
          heardPhones: aiEvidence.heardPhones,
          aiPhones: aiEvidence.aiPhones,
          uncorroborated: aiEvidence.uncorroborated,
          source: aiEvidence.source,
        },
        implausibility_reason: implausibility,
        parse_source: rawItem.source ?? 'ai',
        // Names the stage that decided the item name, so the "why does this say Amchoor
        // when the AI said Angoor" question is answerable from the trace alone.
        item_name_source: segDisagreesWithAi ? 'segmenter_override' : (rawItem.source ?? 'ai'),
        ai_item_name: aiName,
        identityResolution: {
          spokenSurface: rawName,
          identity: lineIdentity,
          brands,
          varieties,
          spokenUnit: unit,
          baseUnit: lineBaseUnit,
          priceRowUnit: matched ? matched.unit_id : null,
          converted: isConverted,
        },
      }
    })

    // Catalog-Learning-From-History (ISSUE-033): a genuinely unmatched item previously
    // needed a shopkeeper to set a price during review to ever enter the catalog -- skip
    // that review once and the item started from zero again on every future mention (the
    // real-world case: "Amchur" recurring unmatched with no path to ever being learned).
    // Once the SAME item name (by phonetic key) has recurred unmatched across
    // CATALOG_LEARNING_THRESHOLD distinct jobs for this shop, auto-add it to
    // catalog_items at price 0 so it starts matching immediately. This does not book any
    // money (price stays 0 until a rate is set) -- unpricedLineReason then explains the
    // line correctly as "has no price -- set a rate" instead of "not in your catalog
    // yet", and the shopkeeper only has to set a rate ONCE for it to stick going forward.
    // Guard: an EMPTY shop catalog makes every item trivially "unmatched", so the recurrence
    // signal this feature keys on carries no information -- it just mints a price-0 row for
    // whatever was said. That is not hypothetical: when catalog sync omitted shop_id, this shop
    // fetched 0 items and learning manufactured a price-0 shadow catalog ("Aaloo" at 0 alongside
    // the real Aaloo at 50), which pinned every subsequent sale to a 0 total and blocked
    // auto-confirm entirely. An empty catalog means identity/sync is broken, not that the
    // shopkeeper is naming genuinely new stock -- so learn nothing until there is a catalog.
    const catalogLearningEnabled = dbCatalogItems.length > 0
    if (!catalogLearningEnabled) {
      console.warn(
        `Skipping catalog-learning for job ${jobId}: shop ${resolvedShopId} has an empty catalog ` +
        `(likely a shop_id/sync fault, not new items).`
      )
    }
    for (const item of finalParsedItems) {
      if (!catalogLearningEnabled) break
      if (
        item.is_matched_to_catalog ||
        item.is_orphan ||
        !item.item_name ||
        item.item_name.trim().length === 0 ||
        item.item_name === "Unrecognized Item" ||
        isQuantityPhrase(item.item_name)
      ) continue

      try {
        const key = phoneticKey(item.item_name)
        const { data: obsRow, error: obsErr } = await supabase.rpc('record_unmatched_item_observation', {
          p_shop_id: resolvedShopId,
          p_phonetic_key: key,
          p_item_name: item.item_name,
          p_unit_id: item.unit,
          p_job_id: jobId,
          p_threshold: CATALOG_LEARNING_THRESHOLD,
          p_canonical_key: canonicalOf(item.item_name),
          p_base_unit: baseUnitOf(item.unit || 'PACKET'),
        })
        if (obsErr) {
          console.error(`record_unmatched_item_observation failed for '${item.item_name}' on job ${jobId}:`, obsErr.message)
          continue
        }
        const result: any = Array.isArray(obsRow) ? obsRow[0] : obsRow
        if (result?.is_in_catalog && result.catalog_item_id) {
          // Re-derive the exact "not in your catalog yet" substring unpricedLineReason
          // burned into implausibility_reason above, and swap it for the "matched but
          // unpriced" phrasing now that this item IS in the catalog -- same function,
          // just isCatalogMatched flipped, so the two strings are guaranteed to agree.
          const oldReason = unpricedLineReason(item.item_name, false, item.price_at_sale, item.total, item.price_intent)
          const newReason = unpricedLineReason(item.item_name, true, item.price_at_sale, item.total, item.price_intent)
          if (oldReason && item.implausibility_reason && item.implausibility_reason.includes(oldReason)) {
            item.implausibility_reason = newReason
              ? item.implausibility_reason.split(' | ').map((r: string) => r === oldReason ? newReason : r).join(' | ')
              : (item.implausibility_reason.split(' | ').filter((r: string) => r !== oldReason).join(' | ') || null)
          }
          item.item_id = result.catalog_item_id
          item.is_matched_to_catalog = true
          if (result.newly_promoted) {
            console.log(`Catalog learning: auto-added '${item.item_name}' to catalog (shop ${resolvedShopId}) after ${CATALOG_LEARNING_THRESHOLD} unmatched recurrences, job ${jobId}`)
          }
        }
      } catch (learnErr: any) {
        // Never let this best-effort learning step break the main response.
        console.error(`Catalog-learning observation threw for '${item.item_name}' on job ${jobId}:`, learnErr?.message ?? learnErr)
      }
    }

    // ASSISTANT jobs carry no reliable captureIntent from the client (it's a generic
    // mic press, not a specific action) -- classify here too so a recording processed
    // while the app is CLOSED doesn't silently fall back to booking everything as a
    // plain sale. Mirrors domain/router/IntentRouter.kt; see Docs/remaining_work_plan.md §1.1.
    const assistantClassification = isAssistant
      ? classifyIntent(transcript || rawGrokTranscript || rawSarvamTranscript || '', finalParsedItems)
      : null
    // Only intents whose booking logic already exists below (plain sale/credit/stock/
    // waste via committedSaleEntries, and rate updates via validRateUpdateEntries) are
    // safe to auto-book server-side. RETURN/PAYMENT_RECEIVED/VOID_LAST/EXPIRY_WRITEOFF/
    // ACTION_COMMAND need customer resolution or ledger-reversal logic that only exists
    // client-side (VoiceCommandHandlers) -- mis-booking those as a sale would be worse
    // than leaving them for review, so they route to unmatched_queue instead (below).
    const assistantEffectiveIntent = assistantClassification?.intent ?? null
    const shouldBookSale = !isAssistant ||
      assistantEffectiveIntent === 'SALE' || assistantEffectiveIntent === 'CREDIT_SALE' ||
      assistantEffectiveIntent === 'STOCK_IN' || assistantEffectiveIntent === 'WASTE'
    const shouldBookRateUpdate = !isAssistant || assistantEffectiveIntent === 'PRICE_UPDATE'
    const effIsStockCapture = isAssistant
      ? (assistantEffectiveIntent === 'STOCK_IN' || assistantEffectiveIntent === 'WASTE')
      : isStockCapture
    const effIsWaste = isAssistant ? assistantEffectiveIntent === 'WASTE' : isWaste
    const effIsCreditSale = isAssistant ? assistantEffectiveIntent === 'CREDIT_SALE' : isCreditSale
    // Everything else the classifier can name but this function can't safely act on --
    // still worth a queue row so the recording isn't silently lost when the app never
    // reopens to run the client-side handler.
    const assistantNeedsReview = isAssistant && assistantClassification !== null &&
      !shouldBookSale && !shouldBookRateUpdate &&
      assistantEffectiveIntent !== 'READ_QUERY'

    // Every item keeps the index it holds in finalParsedItems as its `lineNo` -- stable
    // across sale/rate-update/committed/pending splits below, so a `transactions` row
    // and an `unmatched_queue` row for the SAME utterance never collide on line number
    // and the shopkeeper's review UI can show pending lines in their original spoken
    // order alongside the ones that already booked. See ISSUE-029.
    const itemsWithLineNo = finalParsedItems.map((item, lineNo) => ({ item, lineNo }))

    // RATE_UPDATE items never become sale transactions — a bare "<item> <price> रुपये" with
    // no quantity is a standing-price announcement, not a sale (mirrors VoiceParser.kt / the
    // RATE_UPDATE branch in BackgroundSttProcessor.kt, which writes catalogDao().insertOrUpdate()
    // and explicitly sets autoConfirmedToLedger=false). Without this split every rate update was
    // being inserted into `transactions` as a fake qty=1 sale (see ISSUE-025 follow-up).
    const rateUpdateEntries = itemsWithLineNo.filter(e => e.item.price_intent === 'RATE_UPDATE')
    const saleEntries = itemsWithLineNo.filter(e => e.item.price_intent !== 'RATE_UPDATE')

    const isValidRateUpdate = (item: any) =>
      item.item_id != null &&
      item.confidence >= 0.80 &&
      item.price_at_sale > 0.0 &&
      item.implausibility_reason === null
    const validRateUpdateEntries = rateUpdateEntries.filter(e => isValidRateUpdate(e.item))
    const pendingRateUpdateEntries = rateUpdateEntries.filter(e => !isValidRateUpdate(e.item))
    const rateUpdatesHandled = rateUpdateEntries.length > 0 && validRateUpdateEntries.length === rateUpdateEntries.length

    // Per-item commit gate (ISSUE-029): a sale item is committed to the ledger ONLY IF
    // 1. confidence >= 0.80 (which now encodes match quality, not just match existence)
    // 2. price_at_sale > 0.0 and total > 0.0 (No ₹0 sales in confirmed ledger!)
    // 3. valid, non-empty item name
    // 4. quantity/unit/value are plausible for a kirana shop
    //
    // This used to be `saleItems.every(...)` -- one weak item in a 3-item recording
    // discarded the whole utterance, all 3 items included, with zero rows reaching the
    // ledger. Now each item is judged on its own: confident lines book immediately,
    // weak lines go to unmatched_queue for review, and the shopkeeper sees both in one
    // place instead of losing the confident ones along with the weak one.
    const isCommittable = (item: any) =>
      item.confidence >= 0.80 &&
      item.price_at_sale > 0.0 &&
      item.total > 0.0 &&
      item.item_name &&
      item.item_name.trim().length > 0 &&
      item.item_name !== "Unrecognized Item" &&
      item.implausibility_reason === null

    // A stock-in/waste line commits on quantity alone. Price is optional — the
    // shopkeeper is recording what arrived, not what it cost, and holding the stock
    // hostage to a price sends every unpriced delivery into the sales review queue.
    const isStockCommittable = (item: any) =>
      item.confidence >= 0.80 &&
      item.quantity > 0 &&
      item.item_name &&
      item.item_name.trim().length > 0 &&
      item.item_name !== "Unrecognized Item" &&
      item.implausibility_reason === null

    const committedSaleEntries = saleEntries.filter(e => effIsStockCapture ? isStockCommittable(e.item) : isCommittable(e.item))
    const pendingSaleEntries = saleEntries.filter(e => effIsStockCapture ? !isStockCommittable(e.item) : !isCommittable(e.item))
    const isAutoConfirmed = saleEntries.length > 0 && committedSaleEntries.length === saleEntries.length
    const finalStatus = isAutoConfirmed
      ? "AUTO_CONFIRMED"
      : committedSaleEntries.length > 0
        ? "PARTIALLY_CONFIRMED"
        : (rateUpdatesHandled ? "RATE_UPDATED" : "PARSED")

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
        captureIntent,
        isAssistant,
      },
      step_2b_intent_classification: isAssistant ? {
        intent: assistantClassification?.intent ?? null,
        confidence: assistantClassification?.confidence ?? null,
        scores: assistantClassification?.scores ?? {},
        runnerUp: assistantClassification?.runnerUp ?? null,
        needsArbitration: assistantClassification?.needsArbitration ?? false,
        effectiveCaptureIntent: assistantEffectiveIntent ? captureIntentFor(assistantEffectiveIntent) : null,
        bookedServerSide: shouldBookSale || shouldBookRateUpdate,
        routedToReview: assistantNeedsReview,
      } : null,
      step_2_stt_proxy_response: {
        rawTranscript: chosenRaw,
        normalizedTranscript: normalizeTranscript(chosenRaw, fullCatalogList),
        grokTranscript: rawGrokTranscript,
        sarvamTranscript: rawSarvamTranscript,
        provider: sttProvider,
        audioFileSize: audioBuffer.byteLength,
        catalogKeytermsSentCount: keyterms.length,
        // How many priced catalog rows this shop actually resolved against. A line can report
        // "not in your catalog yet" for two very different reasons -- the item is genuinely new,
        // or the shop's catalog came back EMPTY (wrong shop_id, failed/timed-out fetch) -- and
        // the trace could not previously tell those apart, which is what made the ₹0-total bug
        // so hard to place. Zero here means look at sync/identity, not at the item.
        catalogItemsFetched: dbCatalogItems.length,
        catalogFetchError,
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
        onDeviceTranscript: rawOnDeviceTranscript,
        onDeviceStt: {
          status: onDeviceStatus || (rawOnDeviceTranscript ? 'ok' : 'unavailable'),
          latencyMs: null
        },
        transcriptScores: { grok: grokScored.score, sarvam: sarvamScored.score, onDevice: onDeviceScored.score, adopted: bestScore },
        // Diagnostic-only: the OLD whole-utterance classification, kept in the trace so
        // a divergence from each item's own per-segment classification (which is what
        // actually drives price_intent below) is visible when debugging. Not used for
        // any decision since ISSUE-029.
        wholeUtterancePriceIntentLegacy: detectPriceIntent(chosenRaw),
        basketTotalAmbiguous: isBasketTotalAmbiguous,
        sttRace: sttRaceTrace,
      },
      step_3_deterministic_ordering_segmenter: {
        numeralRejoins: step3NumeralRejoins ?? [],
        carryoverQty: null,
        engine: 'phonetic_grammar_lattice_v2',
        // TRUE when `segments` below is NOT segmenter output but a synthetic echo of the AI's own
        // answer, rendered so the logs screen has rows. A reader who misses this concludes the
        // segmenter matched the item when it matched nothing at all. See ISSUE-105.
        segmentsAreSyntheticFromAi: step3Segments.length === 0,
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
      // The untouched pre-catalog-match answer (item_name/quantity/unit/price_intent
      // only) -- Phase 0 instrumentation for ISSUE-031, kept separately from
      // finalParsedItems above (which has prices/confidence/catalog-matching applied)
      // so a memory hit/miss and Grok's literal answer can be reconstructed from the
      // trace alone.
      step_4_raw_ai_items: parsedRawItems,
      // Every server-side warning/error raised while processing this job. Empty is the
      // healthy case. See `note()` for why these live here and not only in console output.
      step_0_server_diagnostics: serverDiagnostics,
      step_4_interpretation_source: parseSource,
      // Why the AI round-trip was or wasn't skipped. `skipReason` is the single most useful
      // field when a shop reports "still slow": it names the exact gate that rejected the
      // fast path, so it can be answered from the trace alone.
      step_4_fast_path: {
        used: parseSource === 'segmenter_fast_path',
        skipReason: fastPath.skipReason,
        aiCallMade: parseSource === 'grok_ai' || parseSource === 'forced_ai_fallback',
      },
      step_4_learned_parse_memory: {
        memoKey,
        catalogFingerprint,
        memoryShopId,
        enabled: memoryEnabled,
        hit: !!memoryHit,
        corroborated: memoCorroborated,
        canarySampled: isCanarySample,
        scopedFingerprintEnabled: true,
      },
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
      step_6_final_outcome: itemsWithLineNo.map(({ item, lineNo }) => {
        const isRateUpdate = item.price_intent === 'RATE_UPDATE'
        const committed = isRateUpdate ? isValidRateUpdate(item) : isCommittable(item)
        return {
          lineNo,
          itemName: item.item_name,
          matchedCatalogId: item.item_id,
          quantity: item.quantity,
          unit: item.unit,
          priceAtSale: item.price_at_sale,
          estimatedTotal: item.total,
          confidence: item.confidence,
          isSanityFlagged: !committed,
          // Rate updates are never written to the transactions ledger — see rateUpdatesHandled above.
          autoConfirmedToLedger: isRateUpdate ? false : committed
        }
      })
    }

    const firstItem = finalParsedItems[0] || null
    const lineCount = finalParsedItems.length

    const sttErrorSummary = [
      grokOutcome.error ? `grok: ${grokOutcome.error}` : null,
      sarvamOutcome.error ? `sarvam: ${sarvamOutcome.error}` : null,
      aiError ? `ai: ${aiError}` : null,
    ].filter(Boolean).join(' | ')

    const persistence: Record<string, unknown> = {
      ensureShopId: resolvedShopId ?? null,
    }
    const recordedAtMsRaw = Number(metadata?.recordedAtMs)
    const recordedAtSafe = Number.isFinite(recordedAtMsRaw) && recordedAtMsRaw > 0 ? Math.floor(recordedAtMsRaw) : Date.now()
    const createdAtIso = new Date(recordedAtSafe).toISOString()

    persistence.recordedAtMsRaw = metadata?.recordedAtMs ?? null
    persistence.recordedAtUsed = recordedAtSafe

    // Deferred write to stt_job_logs (PERMANENT STORAGE - NEVER DELETED)
    // Previously this row was written twice -- once before the ledger writes with a
    // partially-filled `persistence`, then again afterwards purely to attach the rest of it.
    // Both were on the response path. One write, deferred after the response.
    const logPayload = {
      job_id: jobId,
      shop_id: resolvedShopId,
      recorded_at_ms: recordedAtSafe,
      created_at: createdAtIso,
      hold_duration_ms: metadata.holdDurationMs || 0,
      status: finalStatus,
      raw_transcript: transcript || rawGrokTranscript || rawSarvamTranscript,
      parsed_item_name: firstItem ? firstItem.item_name : "Unrecognized Item",
      parsed_qty: firstItem ? firstItem.quantity : 1.0,
      parsed_unit: firstItem ? firstItem.unit : "PACKET",
      parsed_total: firstItem ? firstItem.total : 0.0,
      line_count: lineCount,
      is_sanity_flagged: finalStatus !== 'AUTO_CONFIRMED' && finalStatus !== 'RATE_UPDATED',
      error_message: transcript ? sttErrorSummary : (sttErrorSummary || "Empty transcript from STT"),
      diagnostic_trace_json: '',
      audio_cloud_url: audioCloudUrl
    }

    // Write to transactions for every COMMITTED sale line (RATE_UPDATE never reaches
    // here). One row per line, keyed (job_id, line_no) -- a job can now produce more
    // than one transaction row, which the old job_id-only unique index made
    // impossible (the upsert failed with 42P10 and was never checked; see ISSUE-029).
    if (shouldBookSale && effIsStockCapture && committedSaleEntries.length > 0) {
      const stockInserts = committedSaleEntries.map(({ item }) => ({
        shop_id: resolvedShopId,
        item_id: item.item_id,
        item_name: item.item_name,
        quantity: effIsWaste ? -Math.abs(item.quantity) : Math.abs(item.quantity),
        cost_price: item.price_at_sale > 0 ? item.price_at_sale : 0,
        cost_missing: !(item.price_at_sale > 0),
        timestamp: new Date().toISOString(),
      }))
      const { error: stockErr } = await supabase.from('stock_in').insert(stockInserts)
      persistence.stockIn = stockErr ? { ok: false, code: stockErr.code, message: stockErr.message } : { ok: true, count: stockInserts.length }
      if (stockErr) {
        console.error(`Failed to insert ${stockInserts.length} stock_in row(s) for job ${jobId} (intent ${captureIntent}):`, stockErr.message)
      } else {
        console.log(`Inserted ${stockInserts.length} stock_in row(s) for job ${jobId} (intent ${captureIntent})`)
      }
    } else if (shouldBookSale && !effIsStockCapture && committedSaleEntries.length > 0) {
      const txInserts = committedSaleEntries.map(({ item, lineNo }) => ({
        job_id: jobId,
        shop_id: resolvedShopId,
        item_id: item.item_id,
        audio_cloud_url: audioCloudUrl,
        raw_transcript: transcript || rawGrokTranscript || rawSarvamTranscript,
        item_name: item.item_name,
        quantity: item.quantity,
        price_at_sale: item.price_at_sale,
        total: item.total,
        payment_mode: effIsCreditSale ? 'CREDIT' : 'CASH',
        source: 'VOICE',
        timestamp: new Date().toISOString(),
        line_no: lineNo,
        line_count: lineCount,
      }))
      const { data: insertedTx, error: txErr } = await supabase
        .from('transactions')
        .upsert(txInserts, { onConflict: 'job_id,line_no' })
        .select('id, total')
      persistence.transactions = txErr ? { ok: false, code: txErr.code, message: txErr.message } : { ok: true, count: txInserts.length }
      if (txErr) {
        console.error(`Failed to insert ${txInserts.length} transaction(s) for job ${jobId}:`, txErr.message)
      } else {
        console.log(`Inserted ${txInserts.length} committed transaction(s) for job ${jobId} (${lineCount} total lines)`)

        if (effIsCreditSale && insertedTx && insertedTx.length > 0) {
          const creditInserts = insertedTx.map((tx: any) => ({
            shop_id: resolvedShopId,
            customer_name: 'अज्ञात',
            customer_id: null,
            amount: tx.total,
            status: 'PENDING',
            linked_transaction_id: tx.id,
          }))
          const { error: creditErr } = await supabase.from('credits').insert(creditInserts)
          persistence.credits = creditErr ? { ok: false, code: creditErr.code, message: creditErr.message } : { ok: true, count: creditInserts.length }
          if (creditErr) {
            console.error(`Failed to insert ${creditInserts.length} credit(s) for job ${jobId}:`, creditErr.message)
          } else {
            console.log(`Inserted ${creditInserts.length} credit(s) for job ${jobId}`)
          }
        }
      }
    }

    // RATE_UPDATE: update the catalog's standing price directly, no transaction created.
    if (shouldBookRateUpdate && validRateUpdateEntries.length > 0) {
      for (const { item } of validRateUpdateEntries) {
        const { error: rateUpdateError } = await supabase
          .from('catalog_items')
          .update({ price: item.price_at_sale, updated_at: new Date().toISOString() })
          .eq('id', item.item_id)
        if (rateUpdateError) {
          console.log(`Failed to apply rate update for catalog item ${item.item_id} on job ${jobId}: ${rateUpdateError.message}`)
        }
      }
      console.log(`Applied ${validRateUpdateEntries.length} rate update(s) to catalog_items for job ${jobId}`)
    }

    // Every line that did NOT commit (weak sale lines + unresolved rate updates) gets
    // its own unmatched_queue row, keyed (job_id, line_no)
    const pendingEntries = [...pendingSaleEntries, ...pendingRateUpdateEntries]
    let unmatchedRowsWritten = 0
    if ((shouldBookSale || shouldBookRateUpdate) && pendingEntries.length > 0) {
      const pendingInserts = pendingEntries.map(({ item, lineNo }) => ({
        job_id: jobId,
        shop_id: resolvedShopId,
        audio_ref: audioCloudUrl,
        raw_transcript: transcript || rawGrokTranscript || rawSarvamTranscript || "Voice Recording (Pending Review)",
        status: 'PENDING',
        timestamp: new Date().toISOString(),
        line_no: lineNo,
        item_name: item.item_name,
        quantity: item.quantity,
        unit: item.unit,
        price_at_sale: item.price_at_sale,
        total: item.total,
        price_intent: item.price_intent,
        implausibility_reason: item.implausibility_reason,
      }))
      const { error: uqErr } = await supabase.from('unmatched_queue').upsert(pendingInserts, { onConflict: 'job_id,line_no' })
      persistence.unmatchedQueue = uqErr ? { ok: false, code: uqErr.code, message: uqErr.message } : { ok: true, count: pendingInserts.length }
      if (uqErr) {
        console.error(`Failed to write ${pendingInserts.length} unmatched_queue row(s) for job ${jobId}:`, uqErr.message)
      } else {
        unmatchedRowsWritten = pendingInserts.length
        console.log(`Routed ${pendingInserts.length} pending line(s) of job ${jobId} to unmatched_queue (shop_id: ${resolvedShopId})`)
      }
    } else if (assistantNeedsReview) {
      const firstReviewItem = finalParsedItems[0] || null
      const { error: uqErr } = await supabase.from('unmatched_queue').upsert([{
        job_id: jobId,
        shop_id: resolvedShopId,
        audio_ref: audioCloudUrl,
        raw_transcript: transcript || rawGrokTranscript || rawSarvamTranscript || "Voice Recording (Pending Review)",
        status: 'PENDING',
        timestamp: new Date().toISOString(),
        line_no: 0,
        item_name: firstReviewItem?.item_name ?? null,
        quantity: firstReviewItem?.quantity ?? null,
        unit: firstReviewItem?.unit ?? null,
        price_at_sale: firstReviewItem?.price_at_sale ?? null,
        total: firstReviewItem?.total ?? null,
        implausibility_reason: `ASSISTANT intent=${assistantEffectiveIntent} (confidence ${(assistantClassification?.confidence ?? 0).toFixed(2)}) -- needs app review, server-side handling not implemented for this intent`,
      }], { onConflict: 'job_id,line_no' })
      persistence.unmatchedQueueAssistant = uqErr ? { ok: false, code: uqErr.code, message: uqErr.message } : { ok: true }
      if (uqErr) {
        console.error(`Failed to write assistant-review unmatched_queue row for job ${jobId} (intent ${assistantEffectiveIntent}):`, uqErr.message)
      } else {
        unmatchedRowsWritten = 1
        console.log(`Routed assistant job ${jobId} (classified ${assistantEffectiveIntent}, confidence ${assistantClassification?.confidence}) to unmatched_queue for review`)
      }
    }

    // Step 5.3: Widen safety net guard -- if no transactions and no unmatched_queue rows were written,
    // write a fallback unmatched_queue row so every job leaves a reviewable record.
    const committedCount = committedSaleEntries.length
    if (committedCount === 0 && unmatchedRowsWritten === 0) {
      const firstFallbackItem = finalParsedItems[0] || null
      const { error: uqErr } = await supabase.from('unmatched_queue').upsert([{
        job_id: jobId,
        shop_id: resolvedShopId,
        audio_ref: audioCloudUrl,
        raw_transcript: transcript || rawGrokTranscript || rawSarvamTranscript || "Voice Recording (Pending Review)",
        status: 'PENDING',
        timestamp: new Date().toISOString(),
        line_no: 0,
        item_name: firstFallbackItem?.item_name ?? null,
        quantity: firstFallbackItem?.quantity ?? null,
        unit: firstFallbackItem?.unit ?? null,
        price_at_sale: firstFallbackItem?.price_at_sale ?? null,
        total: firstFallbackItem?.total ?? null,
        implausibility_reason: `Safety fallback: job produced 0 ledger/review rows (intent: ${captureIntent}, status: ${finalStatus})`
      }], { onConflict: 'job_id,line_no' })
      persistence.unmatchedQueueFallback = uqErr ? { ok: false, code: uqErr.code, message: uqErr.message } : { ok: true }
      if (uqErr) console.error(`Failed to write fallback unmatched_queue row for job ${jobId}:`, uqErr.message)
    }

    timings.ledgerWrittenAtMs = mark()
    timings.totalMs = mark()
    traceObj.step_7_persistence = persistence
    traceObj.step_8_timings = timings

    const logPayloadFinal = { ...logPayload }
    EdgeRuntime.waitUntil((async () => {
      // §3.7: recover the abandoned STT opinion, capped, at zero cost to the caller.
      if (raceShortcut) {
        const loserPromise = raceShortcut.provider === 'grok' ? sarvamPromise : grokPromise
        const loser = await Promise.race([
          loserPromise,
          new Promise<SttOutcome>(r => setTimeout(() => r({ transcript: '', error: 'loser not settled within 1500ms', httpStatus: null, latencyMs: 0 }), 1500)),
        ])
        sttRaceTrace.loserOutcome = loser
      }
      const uploadMs = uploadPromise ? await uploadPromise : -1
      timings.uploadMs = uploadMs
      traceObj.step_8_timings = timings
      logPayloadFinal.diagnostic_trace_json = JSON.stringify(traceObj)

      const { error: logErr } = await supabase.from('stt_job_logs').upsert([logPayloadFinal], { onConflict: 'job_id' })
      if (logErr) console.error(`Failed to write stt_job_logs for job ${jobId}:`, logErr.message)
    })().catch(e => console.error(`Deferred log write failed for ${jobId}:`, e)))

    // §5.3: Parse Inspector (shadow verification)
    const inspectorEligible = (parseSource === 'segmenter_fast_path' || parseSource === 'memory')
      && !!xaiApiKey && Math.random() < INSPECTOR_RATE
    if (inspectorEligible) {
      const shipped = toMemoShape(parsedRawItems)
      EdgeRuntime.waitUntil((async () => {
        const started = Date.now()
        const res = await callGrokChatInterpretation(
          xaiApiKey, systemPrompt, userPrompt,
          () => knownGoodChatModel, (m) => { knownGoodChatModel = m },
        )
        const latency = Date.now() - started
        let agrees: boolean | null = null
        let kind: string | null = null
        if (res.error || res.items.length === 0) {
          kind = 'grok_error'
        } else {
          const fresh = toMemoShape(res.items)
          agrees = canonicalSignature(fresh) === canonicalSignature(shipped)
          if (!agrees) {
            kind = fresh.length !== shipped.length ? 'item_count'
              : fresh.some((f, i) => phoneticKey(f.item_name) !== phoneticKey(shipped[i].item_name)) ? 'item_name'
              : fresh.some((f, i) => f.quantity !== shipped[i].quantity) ? 'quantity'
              : fresh.some((f, i) => f.unit !== shipped[i].unit) ? 'unit'
              : 'price_intent'
          }
        }
        await supabase.from('parse_inspections').insert([{
          job_id: jobId, shop_id: resolvedShopId, parse_source: parseSource,
          transcript: chosenRaw, shipped_items: shipped,
          grok_items: res.items.length ? toMemoShape(res.items) : null,
          agrees, mismatch_kind: kind, grok_model: res.model,
          grok_latency_ms: latency, grok_error: res.error,
        }])
      })().catch(e => console.warn(`Parse inspector failed for ${jobId}:`, e)))
    }

    console.log(`Job ${jobId} finished background processing with status ${finalStatus} (${committedSaleEntries.length} committed / ${pendingEntries.length} pending / ${lineCount} total lines)`)

    // Return the result directly to the inline response path so it doesn't need a
    // DB read-back that races against the QUEUED placeholder.
    return {
      status: finalStatus,
      rawTranscript: transcript || rawGrokTranscript || rawSarvamTranscript || '',
      parsedItems: finalParsedItems,
      diagnosticTraceJson: JSON.stringify(traceObj),
    }

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
        timestamp: new Date().toISOString(),
        line_no: 0,
      }], { onConflict: 'job_id,line_no' })
    } catch (writeErr) {
      console.error(`Failed to write ERROR status for job ${jobId}:`, writeErr)
    }
    return null  // Return null so the inline response falls back to DB read-back
  }
}
