import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const CLOSED_MODIFIER_SET = [
  "किलो", "kilo", "kg", "घी", "ghee", "आधा", "aadha", "aadhi", "aadi", 
  "पाव", "paao", "pao", "सवा", "sawa", "डेढ़", "dedh", "ढाई", "dhai", 
  "ग्राम", "gram", "gm", "लीटर", "litre", "ml", "पैकेट", "packet", "दर्जन", "dozen"
]

const NUMERIC_DISAMBIGUATION_SET = [
  "सत्तर", "sattar", "सत्रह", "satrah", "17", "70",
  "पचास", "pachaas", "pachas", "पाँच", "पांच", "paanch", "50", "5",
  "तीस", "tees", "तीन", "teen", "30", "3",
  "साठ", "saath", "सात", "saat", "60", "7",
  "अस्सी", "assi", "आठ", "aath", "80", "8",
  "नब्बे", "nabbe", "नौ", "nau", "90", "9",
  "बीस", "bees", "चालीस", "chalees", "सौ", "sau"
]

serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', {
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
      }
    })
  }

  try {
    const authHeader = req.headers.get('Authorization')
    if (!authHeader) {
      return new Response(JSON.stringify({ error: 'Missing Authorization header' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' }
      })
    }

    const contentType = req.headers.get('content-type') || ''

    // Diagnostic Log Sync Handler
    if (contentType.includes('application/json')) {
      const logBody = await req.json()
      console.log('Received Diagnostic Log Sync from App:', JSON.stringify(logBody))

      try {
        const supabaseUrl = Deno.env.get('SUPABASE_URL') || ''
        const supabaseAnonKey = Deno.env.get('SUPABASE_ANON_KEY') || ''
        if (supabaseUrl && supabaseAnonKey) {
          const supabase = createClient(supabaseUrl, supabaseAnonKey)
          await supabase.from('stt_job_logs').insert([logBody])
        }
      } catch (logErr) {
        console.warn('Diagnostic log DB insert warning:', logErr)
      }

      return new Response(JSON.stringify({ status: 'logged_successfully' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    }

    if (!contentType.includes('multipart/form-data')) {
      return new Response(JSON.stringify({ error: 'Content-Type must be multipart/form-data or application/json' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' }
      })
    }

    const sttProvider = (Deno.env.get('STT_PROVIDER') || 'grok').toLowerCase().trim()
    console.log(`STT Proxy processing audio via Provider: "${sttProvider}" with dynamic keyterm biasing`)

    const formData = await req.formData()
    const audioFile = formData.get('file') as File | null
    const catalogNamesRaw = formData.get('catalogNames') as string | null
    const shopId = formData.get('shopId') as string | null

    if (!audioFile) {
      return new Response(JSON.stringify({ error: 'Missing audio file in request body' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' }
      })
    }

    // 1. Gather Catalog Items for Keyterm Biasing
    let keytermsList: string[] = [...CLOSED_MODIFIER_SET, ...NUMERIC_DISAMBIGUATION_SET]

    if (catalogNamesRaw) {
      try {
        const names: string[] = JSON.parse(catalogNamesRaw)
        if (Array.isArray(names)) {
          keytermsList.push(...names)
        }
      } catch (e) {
        console.warn("Failed to parse catalogNames JSON param:", e)
      }
    }

    // Attempt Supabase DB fetch if keytermsList is short
    if (keytermsList.length <= CLOSED_MODIFIER_SET.length + NUMERIC_DISAMBIGUATION_SET.length) {
      try {
        const supabaseUrl = Deno.env.get('SUPABASE_URL') || ''
        const supabaseAnonKey = Deno.env.get('SUPABASE_ANON_KEY') || ''
        if (supabaseUrl && supabaseAnonKey) {
          const supabase = createClient(supabaseUrl, supabaseAnonKey)
          let query = supabase.from('catalog_items').select('name').limit(100)
          if (shopId) {
            query = query.eq('shop_id', shopId)
          }
          const { data, error } = await query
          if (!error && data) {
            data.forEach(item => {
              if (item.name && typeof item.name === 'string') {
                keytermsList.push(item.name.trim())
              }
            })
          }
        }
      } catch (dbErr) {
        console.warn("Supabase catalog fetch fallback error:", dbErr)
      }
    }

    // Deduplicate and cap keyterms
    const uniqueKeyterms = Array.from(new Set(keytermsList.filter(k => k && k.length > 0))).slice(0, 80)

    if (sttProvider === 'xai' || sttProvider === 'grok') {
      // Branch 1: xAI Grok STT endpoint with keyterm biasing
      const xaiApiKey = Deno.env.get('XAI_API_KEY')
      if (!xaiApiKey) {
        return new Response(JSON.stringify({ error: 'XAI_API_KEY environment variable not configured' }), {
          status: 500,
          headers: { 'Content-Type': 'application/json' }
        })
      }

      const xaiFormData = new FormData()
      xaiFormData.append('file', audioFile, audioFile.name || 'audio.wav')
      xaiFormData.append('language', 'hi')
      xaiFormData.append('format', 'true')

      // Add repeatable keyterm parameters
      uniqueKeyterms.forEach(term => {
        xaiFormData.append('keyterm', term)
      })

      const xaiResponse = await fetch('https://api.x.ai/v1/stt', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${xaiApiKey}`
        },
        body: xaiFormData
      })

      if (!xaiResponse.ok) {
        const errorText = await xaiResponse.text()
        console.error('xAI STT API error:', xaiResponse.status, errorText)
        return new Response(JSON.stringify({ error: `xAI STT failed: ${errorText}` }), {
          status: xaiResponse.status,
          headers: { 'Content-Type': 'application/json' }
        })
      }

      const xaiData = await xaiResponse.json()
      const rawText = xaiData.text || xaiData.transcript || ""

      return new Response(JSON.stringify({
        transcript: rawText,
        provider: 'xai',
        keytermsAppliedCount: uniqueKeyterms.length
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    } else {
      // Branch 2: Sarvam AI STT endpoint (Default fallback)
      const sarvamApiKey = Deno.env.get('SARVAM_API_KEY')
      if (!sarvamApiKey) {
        return new Response(JSON.stringify({ error: 'SARVAM_API_KEY environment variable not configured' }), {
          status: 500,
          headers: { 'Content-Type': 'application/json' }
        })
      }

      const sarvamFormData = new FormData()
      sarvamFormData.append('file', audioFile, audioFile.name || 'audio.wav')
      sarvamFormData.append('language_code', 'hi-IN')
      sarvamFormData.append('model', 'saarika:v2')

      if (uniqueKeyterms.length > 0) {
        sarvamFormData.append('prompt', uniqueKeyterms.join(", "))
      }

      const sarvamResponse = await fetch('https://api.sarvam.ai/speech-to-text', {
        method: 'POST',
        headers: {
          'api-subscription-key': sarvamApiKey
        },
        body: sarvamFormData
      })

      if (!sarvamResponse.ok) {
        const errorText = await sarvamResponse.text()
        console.error('Sarvam STT API error:', sarvamResponse.status, errorText)
        return new Response(JSON.stringify({ error: `Sarvam STT failed: ${errorText}` }), {
          status: sarvamResponse.status,
          headers: { 'Content-Type': 'application/json' }
        })
      }

      const sarvamData = await sarvamResponse.json()
      const rawText = sarvamData.transcript || sarvamData.text || ""

      return new Response(JSON.stringify({
        transcript: rawText,
        provider: 'sarvam',
        keytermsAppliedCount: uniqueKeyterms.length
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    }
  } catch (err: any) {
    console.error('stt-proxy unhandled exception:', err)
    return new Response(JSON.stringify({ error: err.message || 'Internal proxy server error' }), {
      status: 500,
      headers: { 'Content-Type': 'application/json' }
    })
  }
})
