import { serve } from "https://deno.land/std@0.168.0/http/server.ts"

// Temporary tooling proxy for tools/catalog/generate-t1-vocab.ts (Docs/master_catalog_stage_a_plan.md).
// XAI_API_KEY is a Supabase secret and is never exposed to the local generation script; this
// function holds it and does the actual Grok call on the script's behalf. Not part of the app's
// runtime voice pipeline -- callable only with the project's own anon/service key, same as every
// other function here.

const XAI_CHAT_MODEL = 'grok-4.20-0309-non-reasoning'

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
    const { category, vertical } = await req.json()
    if (!category || !vertical) {
      return new Response(JSON.stringify({ error: 'Missing category or vertical' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
      })
    }

    const apiKey = Deno.env.get('XAI_API_KEY') || ''
    if (!apiKey) {
      return new Response(JSON.stringify({ error: 'XAI_API_KEY not configured on this function' }), {
        status: 500,
        headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
      })
    }

    const prompt = `You are listing real products Indian kirana (small neighbourhood grocery) shops sell in the category "${category}". Vertical: ${vertical}.

For each DISTINCT product a shopkeeper would actually stock (not every SKU size — one entry per product, e.g. one entry for "Good Day", not one per pack size), return an object with:
- canonical: the standard display name (English, brand name if it's a branded product; a plain generic name like "Chawal" for unbranded commodities)
- category_key: "${category}"
- default_unit: the unit this shop most commonly sells it in — one of KG, GRAM, LITRE, ML, PACKET, PIECE, DOZEN, BOX
- aliases.devanagari: how this is written in Hindi/Devanagari script, including common spelling variants. Empty array if there is no natural Devanagari form (e.g. an English-only brand name nobody writes in Devanagari).
- aliases.hinglish: how an Indian shopkeeper would SAY this in a voice note using Latin script — phonetic spellings, not the formal English name. Include common short forms.
- aliases.english: the formal English/brand name(s), if different from canonical.
- size_modifiers_apply: true if this product commonly comes in multiple sizes a shopkeeper would call "chhota"/"bada" (small/big) rather than requiring an exact size every time.

Only include real products actually sold in Indian kirana shops. Do not invent brand names. If you are not confident a brand exists in the Indian market, omit it rather than guess.

Return JSON: { "entries": [ ...objects as described above... ] }

Return 15-40 entries for this category, covering the products an actual shopkeeper in this category would name if asked to list everything they stock.`

    const resp = await fetch('https://api.x.ai/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: XAI_CHAT_MODEL,
        messages: [{ role: 'user', content: prompt }],
        response_format: { type: 'json_object' },
      }),
    })

    if (!resp.ok) {
      const errText = await resp.text()
      return new Response(JSON.stringify({ error: `xAI request failed (${resp.status}): ${errText}` }), {
        status: 502,
        headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
      })
    }

    const data = await resp.json()
    const contentStr = data.choices?.[0]?.message?.content || '{"entries":[]}'

    return new Response(contentStr, {
      status: 200,
      headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
    })
  } catch (err: any) {
    return new Response(JSON.stringify({ error: err.message || 'Internal Server Error' }), {
      status: 500,
      headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
    })
  }
})
