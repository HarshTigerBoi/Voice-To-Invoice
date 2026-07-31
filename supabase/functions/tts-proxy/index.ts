import { serve } from "https://deno.land/std@0.168.0/http/server.ts"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    const authHeader = req.headers.get('Authorization')
    if (!authHeader) {
      return new Response(JSON.stringify({ error: 'Missing Authorization header' }), {
        status: 401,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      })
    }

    const { text, language } = await req.json().catch(() => ({}))
    if (!text || typeof text !== 'string') {
      return new Response(JSON.stringify({ error: 'Missing text parameter' }), {
        status: 400,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      })
    }

    const xaiApiKey = Deno.env.get('XAI_API_KEY')
    if (!xaiApiKey) {
      console.error('XAI_API_KEY environment variable not configured')
      return new Response(JSON.stringify({ error: 'XAI_API_KEY environment variable not configured' }), {
        status: 500,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      })
    }

    const ttsResponse = await fetch('https://api.x.ai/v1/tts', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${xaiApiKey}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        text,
        language: language || 'hi'
      })
    })

    if (!ttsResponse.ok) {
      const errorText = await ttsResponse.text()
      console.error('xAI TTS API error:', ttsResponse.status, errorText)
      return new Response(JSON.stringify({ error: `xAI TTS failed: ${errorText}` }), {
        status: ttsResponse.status,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      })
    }

    const audioBytes = await ttsResponse.arrayBuffer()
    const contentType = ttsResponse.headers.get('content-type') || 'audio/mpeg'

    return new Response(audioBytes, {
      status: 200,
      headers: {
        ...corsHeaders,
        'Content-Type': contentType
      }
    })
  } catch (err: any) {
    console.error('tts-proxy unhandled exception:', err)
    return new Response(JSON.stringify({ error: err.message || 'Internal proxy server error' }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    })
  }
})
