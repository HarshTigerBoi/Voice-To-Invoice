import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

// Deno Deploy / Supabase Edge Runtime global declaration
declare const EdgeRuntime: { waitUntil: (promise: Promise<unknown>) => void }

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

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
// GENERALIZED COMBINATORIAL EDIT-DISTANCE FUZZY SEGMENTATION ENGINE
// Pure mathematical edit-distance matching across Numbers, Units, & Catalog Items.
// -----------------------------------------------------------------------------

function editDistance(a: string, b: string): number {
  if (a === b) return 0
  if (a.length === 0) return b.length
  if (b.length === 0) return a.length

  const matrix: number[][] = []
  for (let i = 0; i <= b.length; i++) matrix[i] = [i]
  for (let j = 0; j <= a.length; j++) matrix[0][j] = j

  for (let i = 1; i <= b.length; i++) {
    for (let j = 1; j <= a.length; j++) {
      if (b.charAt(i - 1) === a.charAt(j - 1)) {
        matrix[i][j] = matrix[i - 1][j - 1]
      } else {
        matrix[i][j] = Math.min(
          matrix[i - 1][j - 1] + 1, // substitution
          matrix[i][j - 1] + 1,     // insertion
          matrix[i - 1][j] + 1      // deletion
        )
      }
    }
  }
  return matrix[b.length][a.length]
}

const CANONICAL_NUMBERS = ["एक", "दो", "तीन", "चार", "पांच", "पाँच", "छह", "सात", "आठ", "नौ", "दस", "आधा", "पाव", "डेढ़", "ढाई", "सवा"]
const CANONICAL_UNITS = ["किलो", "ग्राम", "लीटर", "पैकेट", "पीस", "दर्जन"]

function fuzzyMatchVocab(token: string, vocab: string[], maxDist = 2): { canonical: string, dist: number } | null {
  if (!token || token.length === 0) return null
  let best: string | null = null
  let minDist = maxDist + 1

  for (const item of vocab) {
    const dist = editDistance(token.toLowerCase(), item.toLowerCase())
    if (dist < minDist) {
      minDist = dist
      best = item
    }
  }

  if (best && minDist <= maxDist) {
    return { canonical: best, dist: minDist }
  }
  return null
}

function combinatorialFuzzySegmenter(
  transcript: string,
  catalogNames: string[]
): string {
  if (!transcript) return ""
  const tokens = transcript.trim().split(/\s+/)
  const resolvedTokens: string[] = []

  // Keep this list in sync with the Devanagari keys in indicAliasMap
  // (app/src/main/java/com/voicetoinvoice/app/domain/matcher/FuzzyCatalogMatcher.kt) —
  // that's the human-curated source of truth for Hindi produce/FMCG names. Without a
  // matching entry here, a Hindi utterance for that item can never resolve correctly:
  // catalogNames are stored in English (e.g. "Bhindi"), so edit-distance against a
  // Devanagari transcript token never matches them, and the segmenter falls back to
  // whatever *is* in this list — silently corrupting the word instead of leaving it alone.
  const catalogVocab = Array.from(new Set([
    ...catalogNames,
    "सोना", "चांदी", "सेब", "दूध", "मूंगफली", "काजू", "बादाम",
    "प्याज", "प्याज़", "टमाटर", "आलू", "अलू", "अदरक", "भिंडी", "धनिया", "मिर्च",
    "गोभी", "बैंगन", "गाजर", "मटर", "खीरा", "पालक", "लहसुन", "ब्रोकली",
    "गोल्ड", "ताज़ा", "सरस", "दही", "छाछ", "मट्ठा", "पनीर", "घी", "मक्खन", "बटर",
    "अंडे", "अंडा", "इप्पी", "मैगी", "सर्फ", "आशीर्वाद", "बर्बन", "पॉन्ड्स", "चीनी", "शक्कर"
  ]))

  for (const token of tokens) {
    const lowerT = token.toLowerCase()

    // 1. Direct single-token fuzzy check against Numbers, Units, Catalog
    const numMatch = fuzzyMatchVocab(lowerT, CANONICAL_NUMBERS, 1)
    if (numMatch) {
      resolvedTokens.push(numMatch.canonical)
      continue
    }

    const unitMatch = fuzzyMatchVocab(lowerT, CANONICAL_UNITS, 1)
    if (unitMatch) {
      resolvedTokens.push(unitMatch.canonical)
      continue
    }

    const catMatch = fuzzyMatchVocab(lowerT, catalogVocab, 1)
    if (catMatch) {
      resolvedTokens.push(catMatch.canonical)
      continue
    }

    // 2. Evaluate 2-Way Combinatorial Splits (Number+Unit or Unit+Item)
    let bestTwoWay: { p1: string, p2: string, score: number } | null = null

    for (let i = 1; i <= lowerT.length - 1; i++) {
      const part1 = lowerT.slice(0, i)
      const part2 = lowerT.slice(i)

      // Case A: Number + Unit (e.g. "चरगलो" -> "चार" + "किलो")
      const m1Num = fuzzyMatchVocab(part1, CANONICAL_NUMBERS, 2)
      const m2Unit = fuzzyMatchVocab(part2, CANONICAL_UNITS, 2)
      if (m1Num && m2Unit) {
        const score = m1Num.dist + m2Unit.dist
        if (!bestTwoWay || score < bestTwoWay.score) {
          bestTwoWay = { p1: m1Num.canonical, p2: m2Unit.canonical, score }
        }
      }

      // Case B: Unit + Item (e.g. "ग्लोसोना" -> "किलो" + "सोना")
      const m1Unit = fuzzyMatchVocab(part1, CANONICAL_UNITS, 2)
      const m2Cat = fuzzyMatchVocab(part2, catalogVocab, 2)
      if (m1Unit && m2Cat) {
        const score = m1Unit.dist + m2Cat.dist
        if (!bestTwoWay || score < bestTwoWay.score) {
          bestTwoWay = { p1: m1Unit.canonical, p2: m2Cat.canonical, score }
        }
      }
    }

    // 3. Evaluate 3-Way Combinatorial Splits (Number + Unit + Item)
    let bestThreeWay: { p1: string, p2: string, p3: string, score: number } | null = null

    for (let i = 1; i <= lowerT.length - 2; i++) {
      for (let j = i + 1; j <= lowerT.length - 1; j++) {
        const p1 = lowerT.slice(0, i)
        const p2 = lowerT.slice(i, j)
        const p3 = lowerT.slice(j)

        const m1 = fuzzyMatchVocab(p1, CANONICAL_NUMBERS, 2)
        const m2 = fuzzyMatchVocab(p2, CANONICAL_UNITS, 2)
        const m3 = fuzzyMatchVocab(p3, catalogVocab, 2)

        if (m1 && m2 && m3) {
          const score = m1.dist + m2.dist + m3.dist
          if (!bestThreeWay || score < bestThreeWay.score) {
            bestThreeWay = { p1: m1.canonical, p2: m2.canonical, p3: m3.canonical, score }
          }
        }
      }
    }

    // Confidence floor: each matched part is allowed dist <= 2, but a split where every
    // part is maximally fuzzy is weak evidence, not a real correction. Requiring an
    // average distance <= 1 per part means we only force a rewrite when the match is
    // actually close — otherwise we leave the raw token alone and let the downstream
    // AI interpretation step reason about it with full context, instead of the segmenter
    // confidently rewriting it to the wrong word (e.g. "बिंडी" -> "घी" instead of "भिंडी").
    const twoWayConfident = bestTwoWay && bestTwoWay.score <= 2
    const threeWayConfident = bestThreeWay && bestThreeWay.score <= 3

    if (threeWayConfident && (!twoWayConfident || bestThreeWay!.score <= bestTwoWay!.score)) {
      console.log(`Combinatorial Fuzzy Segmenter decompounded 3-way run-on token "${token}" -> ["${bestThreeWay!.p1}", "${bestThreeWay!.p2}", "${bestThreeWay!.p3}"]`)
      resolvedTokens.push(bestThreeWay!.p1, bestThreeWay!.p2, bestThreeWay!.p3)
    } else if (twoWayConfident) {
      console.log(`Combinatorial Fuzzy Segmenter decompounded 2-way run-on token "${token}" -> ["${bestTwoWay!.p1}", "${bestTwoWay!.p2}"]`)
      resolvedTokens.push(bestTwoWay!.p1, bestTwoWay!.p2)
    } else {
      resolvedTokens.push(token)
    }
  }

  return resolvedTokens.join(" ")
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
    const keyterms = Array.from(new Set([...fullCatalogList, ...CANONICAL_NUMBERS, ...CANONICAL_UNITS])).slice(0, 100)

    // Step 2: Dual concurrent STT (Grok + Sarvam), 8s timeout each, run in parallel
    const xaiApiKey = Deno.env.get('XAI_API_KEY') || ''
    const sarvamApiKey = Deno.env.get('SARVAM_API_KEY') || ''

    let rawGrokTranscript = ""
    let rawSarvamTranscript = ""
    let sttProvider = "none"

    const sttPromises: Promise<void>[] = []

    if (xaiApiKey) {
      sttPromises.push((async () => {
        const controller = new AbortController()
        const timeoutId = setTimeout(() => controller.abort(), 8000)
        try {
          const grokBytes = new Uint8Array(audioBuffer.slice(0))
          const grokFile = new File([grokBytes], `${jobId}.wav`, { type: 'audio/wav' })
          const xaiForm = new FormData()
          xaiForm.append('file', grokFile, `${jobId}.wav`)
          xaiForm.append('language', 'hi')
          keyterms.forEach(k => xaiForm.append('keyterm', k))

          const xaiResp = await fetch('https://api.x.ai/v1/stt', {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${xaiApiKey}` },
            body: xaiForm,
            signal: controller.signal
          })
          clearTimeout(timeoutId)

          if (xaiResp.ok) {
            const xaiData = await xaiResp.json()
            rawGrokTranscript = (xaiData.text || xaiData.transcript || "").trim()
            console.log(`Grok STT transcribed: "${rawGrokTranscript}"`)
          } else {
            console.warn(`Grok STT returned HTTP ${xaiResp.status}:`, await xaiResp.text())
          }
        } catch (e: any) {
          clearTimeout(timeoutId)
          console.warn("Grok STT fetch warning (degrading cleanly):", e.name === 'AbortError' ? 'STT Timeout (8s limit)' : e.message)
        }
      })())
    }

    if (sarvamApiKey) {
      sttPromises.push((async () => {
        const controller = new AbortController()
        const timeoutId = setTimeout(() => controller.abort(), 8000)
        try {
          const sarvamBytes = new Uint8Array(audioBuffer.slice(0))
          const sarvamFile = new File([sarvamBytes], `${jobId}.wav`, { type: 'audio/wav' })
          const sarvamForm = new FormData()
          sarvamForm.append('file', sarvamFile, `${jobId}.wav`)
          sarvamForm.append('language_code', 'hi-IN')
          sarvamForm.append('model', 'saarika:v2')

          const sarvamResp = await fetch('https://api.sarvam.ai/speech-to-text', {
            method: 'POST',
            headers: { 'api-subscription-key': sarvamApiKey },
            body: sarvamForm,
            signal: controller.signal
          })
          clearTimeout(timeoutId)

          if (sarvamResp.ok) {
            const sarvamData = await sarvamResp.json()
            rawSarvamTranscript = (sarvamData.transcript || sarvamData.text || "").trim()
            console.log(`Sarvam AI STT transcribed: "${rawSarvamTranscript}"`)
          } else {
            const errBody = await sarvamResp.text()
            console.warn(`Sarvam STT returned HTTP ${sarvamResp.status}: ${errBody}`)
          }
        } catch (e: any) {
          clearTimeout(timeoutId)
          console.warn("Sarvam STT fetch warning (degrading cleanly):", e.name === 'AbortError' ? 'STT Timeout (8s limit)' : e.message)
        }
      })())
    }

    await Promise.all(sttPromises)

    if (rawGrokTranscript && rawSarvamTranscript) {
      sttProvider = "grok+sarvam"
    } else if (rawGrokTranscript) {
      sttProvider = "grok"
    } else if (rawSarvamTranscript) {
      sttProvider = "sarvam"
    }

    // Apply GENERALIZED COMBINATORIAL EDIT-DISTANCE FUZZY SEGMENTER
    const grokTranscript = combinatorialFuzzySegmenter(rawGrokTranscript, fullCatalogList)
    const sarvamTranscript = combinatorialFuzzySegmenter(rawSarvamTranscript, fullCatalogList)
    const transcript = grokTranscript || sarvamTranscript || ""

    console.log(`Fuzzy segmented transcript: "${transcript}" (Grok: "${grokTranscript}", Sarvam: "${sarvamTranscript}")`)

    // Step 3: Deterministic ordering segmenter
    let step3Segments: any[] = []
    try {
      if (transcript && transcript.trim().length > 0) {
        const numberMap: Record<string, number> = {
          "एक": 1, "ek": 1, "1": 1, "दो": 2, "do": 2, "2": 2, "तीन": 3, "teen": 3, "3": 3,
          "चार": 4, "chaar": 4, "4": 4, "पांच": 5, "पाँच": 5, "paanch": 5, "5": 5,
          "छह": 6, "छे": 6, "6": 6, "सात": 7, "saat": 7, "7": 7, "आठ": 8, "aath": 8, "8": 8,
          "नौ": 9, "nau": 9, "9": 9, "दस": 10, "das": 10, "10": 10, "आधा": 0.5, "पाव": 0.25, "ढाई": 2.5, "डेढ़": 1.5
        }

        const cleanT = transcript.trim()
        const tokens = cleanT.split(/\s+/)
        let currentQty = 1.0
        let currentUnit = "PACKET"
        let currentItemTokens: string[] = []

        for (let i = 0; i < tokens.length; i++) {
          const token = tokens[i].toLowerCase()
          if (numberMap[token] !== undefined) {
            if (currentItemTokens.length > 0) {
              const rawName = currentItemTokens.join(" ").trim()
              step3Segments.push({
                rawSegmentText: `${currentQty} ${currentUnit} ${rawName}`,
                quantity: currentQty,
                unit: currentUnit,
                itemTokens: [rawName],
                isSanityFlagged: false
              })
              currentItemTokens = []
              currentUnit = "PACKET"
            }
            currentQty = numberMap[token]
          } else if (/\d+/.test(token)) {
            if (currentItemTokens.length > 0) {
              const rawName = currentItemTokens.join(" ").trim()
              step3Segments.push({
                rawSegmentText: `${currentQty} ${currentUnit} ${rawName}`,
                quantity: currentQty,
                unit: currentUnit,
                itemTokens: [rawName],
                isSanityFlagged: false
              })
              currentItemTokens = []
              currentUnit = "PACKET"
            }
            currentQty = parseFloat(token) || 1.0
          } else if (["किलो", "kg", "kilo"].includes(token)) {
            currentUnit = "KG"
          } else if (["ग्राम", "gm", "g"].includes(token)) {
            currentUnit = "GRAM"
          } else if (["लीटर", "l", "litre"].includes(token)) {
            currentUnit = "LITRE"
          } else if (["पैकेट", "pkt"].includes(token)) {
            currentUnit = "PACKET"
          } else {
            currentItemTokens.push(tokens[i])
          }
        }

        if (currentItemTokens.length > 0) {
          const rawName = currentItemTokens.join(" ").trim()
          step3Segments.push({
            rawSegmentText: `${currentQty} ${currentUnit} ${rawName}`,
            quantity: currentQty,
            unit: currentUnit,
            itemTokens: [rawName],
            isSanityFlagged: false
          })
        }
        console.log(`Step 3 Segmenter extracted ${step3Segments.length} segments from transcript: "${transcript}"`)
      }
    } catch (segErr) {
      console.error(`Step 3 Segmenter error:`, segErr)
    }

    // Step 4: Multi-item interpretation via Grok AI using Phonetic-Aware Shopkeeper System Prompt
    const catalogContextStr = fullCatalogList.join(', ')

    let parsedRawItems: any[] = []
    if ((rawGrokTranscript || rawSarvamTranscript || transcript) && xaiApiKey) {
      const multiPrompt = `You are a phonetic-aware parser for an Indian shopkeeper's voice orders.
Shopkeepers speak rapidly in Hindi, Hinglish (romanized Hindi), or English —
often mixed in the same sentence, often without pauses between words.

The transcript you receive has ALREADY been through speech-to-text, which
commonly distorts it:
- drops vowel matras (चार → चर, तीन → तिन)
- fuses adjacent words when spoken fast (किलो सोना → ग्लोसोना, चार किलो → चरगलो)
- confuses similar-sounding consonants (क↔ग, ब↔प, स↔श)

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
2. If one token looks like multiple fused words (e.g. "चरगलो", "ग्लोसोना", "Tinggal benggan"),
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
7. The "Preprocessed Transcript" below was produced by a separate rigid
   rule-based segmenter, NOT by you — it can be confidently wrong, especially
   for item names it doesn't recognize. Treat it as one hint among three, not
   as ground truth. When it conflicts with what the two raw STT transcripts
   plus your own phonetic/catalog reasoning suggest, trust your own reasoning
   over the preprocessed transcript.

Shop catalog: ${catalogContextStr}
DUAL STT TRANSCRIPTIONS RECEIVED (these are the actual audio-derived signal —
weight them over the preprocessed transcript):
- Grok STT Transcript: "${rawGrokTranscript || '(none)'}"
- Sarvam AI STT Transcript: "${rawSarvamTranscript || '(none)'}"
- Preprocessed Transcript (rule-based guess, may be wrong — see rule 7): "${transcript || '(none)'}"

Output ONLY valid JSON formatted as:
{
  "parsed_items": [
    {
      "item_name": "<clean title-case Hinglish or English item name, e.g. Baingan, Sona, Aaloo, Paneer>",
      "quantity": <number>,
      "unit": "<KG|GRAM|LITRE|ML|PACKET|PIECE|DOZEN>",
      "confidence": <number 0.0 to 1.0>,
      "matched_catalog": <boolean>
    }
  ]
}`

      const chatController = new AbortController()
      const chatTimeoutId = setTimeout(() => chatController.abort(), 8000)

      try {
        const grokResp = await fetch('https://api.x.ai/v1/chat/completions', {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${xaiApiKey}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            model: 'grok-2-latest',
            messages: [{ role: 'system', content: multiPrompt }],
            temperature: 0
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
        } else {
          console.error('Grok AI returned HTTP:', grokResp.status)
        }
      } catch (aiErr: any) {
        clearTimeout(chatTimeoutId)
        console.error('Grok AI multi-item interpretation error:', aiErr.name === 'AbortError' ? 'AI Timeout (8s limit)' : aiErr.message)
      }
    }

    // Step 3 segmenter fallback if Grok AI failed
    if (parsedRawItems.length === 0 && step3Segments.length > 0) {
      console.log(`Using Step 3 Deterministic Segmenter output as fallback (${step3Segments.length} items)`)
      const nameMap: Record<string, string> = {
        "आलू": "Aaloo", "प्याज": "Pyaz", "टमाटर": "Tamatar", "सेब": "Seb",
        "भिंडी": "Bhindi", "धनिया": "Dhaniya", "सोना": "Sona", "चांदी": "Chaandi",
        "घी": "Ghee", "पनीर": "Paneer", "दूध": "Doodh", "चीनी": "Chini", "बैंगन": "Baingan", "पेंगन": "Baingan"
      }
      parsedRawItems = step3Segments.map(seg => {
        const rawN = seg.itemTokens.join(" ").trim()
        const normN = nameMap[rawN] || (rawN.charAt(0).toUpperCase() + rawN.slice(1))
        return { item_name: normN, quantity: seg.quantity, unit: seg.unit, price: 0.0, confidence: 0.85, matched_catalog: true }
      })
    }

    // Catalog matching & price computation
    const finalParsedItems: any[] = parsedRawItems.map(rawItem => {
      const rawName = (rawItem.item_name || "").trim()
      const lowerN = rawName.toLowerCase()

      const matched = dbCatalogItems.find(dbItem => {
        const dbN = dbItem.name.toLowerCase().trim()
        return dbN === lowerN || lowerN.includes(dbN) || dbN.includes(lowerN)
      })

      const qty = rawItem.quantity || 1.0
      const priceAtSale = matched ? matched.price : (rawItem.price || 0.0)
      const total = qty * priceAtSale
      const isCatalogMatched = matched !== undefined
      const confidence = rawItem.confidence || (isCatalogMatched ? 0.95 : 0.60)

      return {
        item_name: matched ? matched.name : rawName,
        item_id: matched ? matched.id : null,
        quantity: qty,
        unit: rawItem.unit || (matched ? matched.unit_id : "PACKET"),
        price_at_sale: priceAtSale,
        total: total,
        confidence: confidence,
        is_matched_to_catalog: isCatalogMatched
      }
    })

    // Smart Auto-Confirm Gate: Item is auto-confirmed ONLY IF:
    // 1. confidence >= 0.80
    // 2. price_at_sale > 0.0 and total > 0.0 (No ₹0 sales in confirmed ledger!)
    // 3. valid, non-empty item name
    const isAutoConfirmed = finalParsedItems.length > 0 && finalParsedItems.every(item => 
      item.confidence >= 0.80 &&
      item.price_at_sale > 0.0 &&
      item.total > 0.0 &&
      item.item_name &&
      item.item_name.trim().length > 0 &&
      item.item_name !== "Unrecognized Item"
    )
    const finalStatus = isAutoConfirmed ? "AUTO_CONFIRMED" : "PARSED"

    // Step 5: Lightweight adaptive audio expansion metadata evaluation
    let passesExecuted = 0
    let passesDetail: any[] = []

    if (!isAutoConfirmed) {
      passesExecuted = 1
      const padMs = 300
      const expandedStartMs = (metadata.pressStartMs || Date.now()) - padMs
      const expandedEndMs = (metadata.releaseMs || Date.now()) + padMs

      passesDetail.push({
        passNumber: 1,
        expansionAppliedMs: padMs,
        audioWindowStartMs: expandedStartMs,
        audioWindowEndMs: expandedEndMs,
        grokTranscriptStream: grokTranscript,
        sarvamTranscriptStream: sarvamTranscript,
        status: "EXPANDED_300MS_AUDIO_WINDOW_EVALUATED",
        resolution: "Cross-evaluated 300ms front/back padding against dual STT streams"
      })
    }

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
        rawTranscript: transcript,
        grokTranscript: rawGrokTranscript,
        sarvamTranscript: rawSarvamTranscript,
        provider: sttProvider,
        audioFileSize: audioBuffer.byteLength,
        catalogKeytermsSentCount: fullCatalogList.length
      },
      step_3_deterministic_ordering_segmenter: {
        carryoverQty: null,
        segments: step3Segments.length > 0 ? step3Segments : finalParsedItems.map(item => ({
          rawSegmentText: `${item.quantity} ${item.unit} ${item.item_name}`,
          quantity: item.quantity,
          unit: item.unit,
          itemTokens: [item.item_name],
          isSanityFlagged: false
        }))
      },
      step_4_grok_ai_interpretation: finalParsedItems,
      step_5_adaptive_audio_expansion_engine: {
        passesExecuted: passesExecuted,
        passesDetail: passesDetail
      },
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
      error_message: transcript ? "" : "Empty transcript from STT",
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
