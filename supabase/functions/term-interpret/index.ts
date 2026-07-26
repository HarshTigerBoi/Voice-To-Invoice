import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

// Was hardcoded to `grok-2-latest` in three places, which xAI has since retired — every
// call here returned "Model not found" until ISSUE-021. Keep it env-configurable and in
// one place so the next deprecation is a secret change, not a code change.
const XAI_CHAT_MODEL = Deno.env.get('XAI_CHAT_MODEL') || 'grok-2-latest'

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

    const body = await req.json()
    const { raw_term, context, shop_id, confirm_action, full_utterance, catalog_names, parse_mode } = body

    const xaiApiKey = Deno.env.get('XAI_API_KEY') || ''
    const sarvamApiKey = Deno.env.get('SARVAM_API_KEY') || ''

    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? ''
    const serviceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    const supabase = createClient(supabaseUrl, serviceRoleKey)

    // Mode 1: Confirmation Action (Atomic UPSERT with conflict handling)
    if (confirm_action && confirm_action.canonical_value && raw_term) {
      const activeShopId = shop_id || 'default_shop'
      const canonicalVal = confirm_action.canonical_value

      const { data: upsertData, error: upsertErr } = await supabase
        .from('term_aliases')
        .upsert(
          { raw_term: raw_term.trim().toLowerCase(), canonical_value: canonicalVal, domain: 'modifier' },
          { onConflict: 'raw_term' }
        )
        .select()
        .single()

      if (upsertErr || !upsertData) {
        return new Response(JSON.stringify({ error: upsertErr?.message || 'UPSERT failed' }), {
          status: 500,
          headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
        })
      }

      const termId = upsertData.id

      await supabase
        .from('term_alias_confirmations')
        .upsert(
          { term_id: termId, shop_id: activeShopId },
          { onConflict: 'term_id,shop_id' }
        )

      const { count } = await supabase
        .from('term_alias_confirmations')
        .select('shop_id', { count: 'exact', head: true })
        .eq('term_id', termId)

      if (count && count > 0) {
        await supabase
          .from('term_aliases')
          .update({ distinct_shop_count: count })
          .eq('id', termId)
      }

      return new Response(JSON.stringify({ success: true, term_id: termId, canonical_value: canonicalVal }), {
        status: 200,
        headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
      })
    }

    // Mode 2: Contextual Full Utterance Interpretation (resolves mishearings like "18 ke log" -> "Desi Ghee")
    if (full_utterance) {
      const catalogContext = (Array.isArray(catalog_names) && catalog_names.length > 0)
        ? `Shop Catalog Items: ${catalog_names.join(', ')}.`
        : 'Shop Catalog Items: Pyaz, Tamatar, Aaloo, Desi Ghee, Amul Gold Milk, Sugar, Paneer, Butter, Maggi, Atta, Fortune Oil, Seb, Bhindi, Dhaniya.'

      if (parse_mode === 'multi_item') {
        const multiPrompt = `You are an expert AI listing assistant for an Indian Kirana Store & Sabji Mandi shopkeeper.
A shopkeeper spoke retail items continuously in Hindi/Hinglish (e.g. "2 किलो सेब 3 किलो आलू 4 किलो भिंडी 5 किलो धनिया").

COMPULSORY & STRICT ORDERING RULE (NO EXCEPTIONS):
1. The spoken order for EVERY item is STRICTLY: [QUANTITY] [UNIT] [ITEM NAME].
2. The FIRST thing spoken for an item is ALWAYS the QUANTITY.
3. The SECOND thing spoken is ALWAYS the ITEM NAME.
4. ABSOLUTE RULE: Any quantity or number spoken AFTER an item name NEVER belongs to that item! It strictly belongs to the NEXT item spoken. (e.g., in "सेब 3 किलो आलू", the "3 किलो" belongs ONLY to "आलू", NOT "सेब").
5. If no quantity precedes an item name, default its quantity to 1.0.
6. Extract EVERY distinct item spoken into the "parsed_items" array cleanly without mixing quantities across items.
7. Hindi number mapping: एक=1, दो=2, तीन=3, चार=4, पांच/पाँच=5, छह=6, सात=7, आठ=8, नौ=9, दस=10, आधा=0.5, पाव=0.25, ढाई=2.5, डेढ़=1.5.
8. Normalize item names to clean English/Hinglish catalog names (e.g. "सेब"->"Seb", "आलू"->"Aaloo", "भिंडी"/"बैंडी"->"Bhindi", "धनिया"->"Dhaniya", "प्याज"->"Pyaz", "टमाटर"->"Tamatar").
9. Match closest item from catalog names if available:
${catalogContext}

Input Transcript: "${full_utterance}"

Example:
Input: "2 किलो सेब 3 किलो आलू 4 किलो बैंडी 5 किलो धनिया"
Output:
{
  "parsed_items": [
    {"item_name": "Seb", "quantity": 2.0, "unit": "KG", "price": 0.0},
    {"item_name": "Aaloo", "quantity": 3.0, "unit": "KG", "price": 0.0},
    {"item_name": "Bhindi", "quantity": 4.0, "unit": "KG", "price": 0.0},
    {"item_name": "Dhaniya", "quantity": 5.0, "unit": "KG", "price": 0.0}
  ]
}

Output ONLY valid JSON formatted as:
{
  "parsed_items": [
    {
      "item_name": "<item name>",
      "quantity": <number>,
      "unit": "<KG|GRAM|LITRE|ML|PACKET|PIECE|DOZEN>",
      "price": <number or 0.0>
    }
  ]
}`

        let multiOutput = '{}'
        if (xaiApiKey) {
          try {
            const resp = await fetch('https://api.x.ai/v1/chat/completions', {
              method: 'POST',
              headers: {
                'Authorization': `Bearer ${xaiApiKey}`,
                'Content-Type': 'application/json'
              },
              body: JSON.stringify({
                model: XAI_CHAT_MODEL,
                messages: [{ role: 'system', content: multiPrompt }],
                temperature: 0
              })
            })
            if (resp.ok) {
              const data = await resp.json()
              multiOutput = data.choices?.[0]?.message?.content || '{}'
            }
          } catch (e) {
            console.error('xAI Grok multi_item error:', e)
          }
        }

        let parsedItems: any[] = []
        try {
          const match = multiOutput.match(/\{[\s\S]*\}/)
          if (match) {
            const json = JSON.parse(match[0])
            if (Array.isArray(json.parsed_items)) {
              parsedItems = json.parsed_items
            }
          }
        } catch (e) {
          parsedItems = []
        }

        return new Response(JSON.stringify({ full_utterance, parsed_items: parsedItems }), {
          status: 200,
          headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
        })
      }

      const fullUtterancePrompt = `You are an AI assistant for an Indian Kirana shop app.
A shopkeeper spoke a sale in Hindi or Hinglish:

STRICT ORDERING RULE:
- Quantity is ALWAYS spoken FIRST, Item Name SECOND.
- Any quantity after an item name belongs strictly to the NEXT item spoken.

${catalogContext}

Input Transcript: "${full_utterance}"

Extract the sale into JSON format:
{
  "item_name": "<Match closest catalog item name or clean product name>",
  "quantity": <number e.g. 18.0>,
  "unit": "<KG|GRAM|LITRE|ML|PACKET|PIECE|DOZEN>",
  "price": <number or 0.0>
}`

      let fullOutput = '{}'
      if (xaiApiKey) {
        try {
          const resp = await fetch('https://api.x.ai/v1/chat/completions', {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${xaiApiKey}`,
              'Content-Type': 'application/json'
            },
            body: JSON.stringify({
              model: XAI_CHAT_MODEL,
              messages: [{ role: 'system', content: fullUtterancePrompt }],
              temperature: 0
            })
          })
          if (resp.ok) {
            const data = await resp.json()
            fullOutput = data.choices?.[0]?.message?.content || '{}'
          }
        } catch (e) {
          console.error('xAI Grok full utterance error:', e)
        }
      }

      let parsedUtterance
      try {
        const match = fullOutput.match(/\{[\s\S]*\}/)
        parsedUtterance = match ? JSON.parse(match[0]) : null
      } catch (e) {
        parsedUtterance = null
      }

      return new Response(JSON.stringify({ full_utterance, parsed: parsedUtterance }), {
        status: 200,
        headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
      })
    }

    // Mode 3: Closed Enumerated Single-Token Classification
    if (!raw_term) {
      return new Response(JSON.stringify({ error: 'Missing raw_term or full_utterance parameter' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
      })
    }

    const systemPrompt = `You are a strict classifier for an Indian Kirana shop app. You will receive ONE ambiguous token plus its surrounding transcript. Your only job is to map it to exactly one value from this closed list — never invent a new value:

MODIFIERS: "آधा"(0.5), "पाव"(0.25), "सवा"(1.25), "डेढ़"(1.5), "ढाई"(2.5)
UNITS: "KG", "GRAM", "LITRE", "ML", "PACKET", "PIECE"

Strict Rules:
- "किलो", "किलोग्राम", "kilo", "kg" -> always "KG". Never output "KILOMETER" — that unit does not exist in this domain and must never appear in your output.
- If the token could plausibly be an item name (e.g. घी/ghee) rather than a unit or modifier, output ITEM_CANDIDATE, not a guessed unit.
- If you are not at least highly confident, output AMBIGUOUS. Never guess.

Output ONLY valid JSON format:
{"type": "MODIFIER|UNIT|ITEM_CANDIDATE|AMBIGUOUS", "value": "..."}`

    const userPrompt = `Input Token: "${raw_term}", Surrounding Context: "${context || ''}"`

    let llmOutput = '{}'
    if (xaiApiKey && xaiApiKey.startsWith('xai-')) {
      try {
        const resp = await fetch('https://api.x.ai/v1/chat/completions', {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${xaiApiKey}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            model: XAI_CHAT_MODEL,
            messages: [
              { role: 'system', content: systemPrompt },
              { role: 'user', scope: userPrompt }
            ],
            temperature: 0
          })
        })
        if (resp.ok) {
          const data = await resp.json()
          llmOutput = data.choices?.[0]?.message?.content || '{}'
        }
      } catch (e) {
        console.error('xAI error:', e)
      }
    }

    let parsedResult
    try {
      const match = llmOutput.match(/\{[\s\S]*\}/)
      if (match) {
        parsedResult = JSON.parse(match[0])
      } else {
        parsedResult = { type: 'AMBIGUOUS', value: 'UNKNOWN' }
      }
    } catch (e) {
      parsedResult = { type: 'AMBIGUOUS', value: 'UNKNOWN' }
    }

    return new Response(JSON.stringify({ raw_term, classification: parsedResult }), {
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
