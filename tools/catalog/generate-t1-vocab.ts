import { phoneticKey } from '../../supabase/functions/process-voice-job/phonetic.ts'

export interface CategorySpine {
  vertical: string
  categories: string[]
}

export interface GeneratedEntry {
  canonical: string
  vertical: string
  category_key: string
  default_unit: 'KG' | 'GRAM' | 'LITRE' | 'ML' | 'PACKET' | 'PIECE' | 'DOZEN' | 'BOX'
  aliases: {
    devanagari: string[]
    hinglish: string[]
    english: string[]
  }
  size_modifiers_apply: boolean
}

export interface RejectedEntry {
  canonical: string
  category_key: string
  reason: string // 'fewer_than_2_aliases' | 'cross_category_collision' | 'off_list dup'
}

export interface OutputEntry extends GeneratedEntry {
  off_verified: boolean
}

export interface DuplicateEntry {
  canonical: string
  category_key: string
  kept_in_category: string
}

export function filterFewerThan2Aliases(entries: GeneratedEntry[]): {
  surviving: GeneratedEntry[]
  rejected: RejectedEntry[]
} {
  const surviving: GeneratedEntry[] = []
  const rejected: RejectedEntry[] = []

  for (const entry of entries) {
    const dev = entry.aliases?.devanagari?.length || 0
    const hin = entry.aliases?.hinglish?.length || 0
    const eng = entry.aliases?.english?.length || 0
    const total = dev + hin + eng

    if (total < 2) {
      rejected.push({
        canonical: entry.canonical,
        category_key: entry.category_key,
        reason: 'fewer_than_2_aliases',
      })
    } else {
      surviving.push(entry)
    }
  }

  return { surviving, rejected }
}

export function dedupeCanonical(entries: GeneratedEntry[]): {
  surviving: GeneratedEntry[]
  duplicates: DuplicateEntry[]
} {
  const surviving: GeneratedEntry[] = []
  const duplicates: DuplicateEntry[] = []
  const seenMap = new Map<string, string>() // canonical -> category_key

  for (const entry of entries) {
    const key = entry.canonical.trim()
    if (seenMap.has(key)) {
      duplicates.push({
        canonical: entry.canonical,
        category_key: entry.category_key,
        kept_in_category: seenMap.get(key)!,
      })
    } else {
      seenMap.set(key, entry.category_key)
      surviving.push(entry)
    }
  }

  return { surviving, duplicates }
}

export function processPhoneticCollisions(entries: GeneratedEntry[]): {
  surviving: GeneratedEntry[]
  rejected: RejectedEntry[]
  collisions: string[]
} {
  const keyMap = new Map<string, { entry: GeneratedEntry; alias: string }[]>()

  for (const entry of entries) {
    const allAliases = [
      ...(entry.aliases?.devanagari || []),
      ...(entry.aliases?.hinglish || []),
      ...(entry.aliases?.english || []),
    ]

    for (const alias of allAliases) {
      if (!alias || !alias.trim()) continue
      const pk = phoneticKey(alias)
      if (!pk) continue

      if (!keyMap.has(pk)) {
        keyMap.set(pk, [])
      }
      keyMap.get(pk)!.push({ entry, alias })
    }
  }

  const rejectedCanonicalSet = new Set<string>()
  const rejectedEntries: RejectedEntry[] = []
  const collisionLinesSet = new Set<string>()

  for (const [pk, occurrences] of keyMap.entries()) {
    const categories = new Set(occurrences.map(o => o.entry.category_key))
    if (categories.size > 1) {
      const uniqueEntriesInGroup: GeneratedEntry[] = []
      const seenInGroup = new Set<string>()

      for (const occ of occurrences) {
        const id = `${occ.entry.category_key}::${occ.entry.canonical}`
        if (!seenInGroup.has(id)) {
          seenInGroup.add(id)
          uniqueEntriesInGroup.push(occ.entry)
        }

        if (!rejectedCanonicalSet.has(id)) {
          rejectedCanonicalSet.add(id)
          rejectedEntries.push({
            canonical: occ.entry.canonical,
            category_key: occ.entry.category_key,
            reason: 'cross_category_collision',
          })
        }
      }

      for (let i = 0; i < uniqueEntriesInGroup.length; i++) {
        for (let j = i + 1; j < uniqueEntriesInGroup.length; j++) {
          const entryA = uniqueEntriesInGroup[i]
          const entryB = uniqueEntriesInGroup[j]
          if (entryA.category_key !== entryB.category_key) {
            let catA = entryA.category_key
            let canA = entryA.canonical
            let catB = entryB.category_key
            let canB = entryB.canonical

            if (catA > catB || (catA === catB && canA > canB)) {
              const tmpCat = catA
              const tmpCan = canA
              catA = catB
              canA = canB
              catB = tmpCat
              canB = tmpCan
            }

            const tsvLine = `${pk}\t${catA}\t${canA}\t${catB}\t${canB}`
            collisionLinesSet.add(tsvLine)
          }
        }
      }
    }
  }

  const surviving = entries.filter(
    e => !rejectedCanonicalSet.has(`${e.category_key}::${e.canonical}`)
  )

  const collisions = Array.from(collisionLinesSet).sort()

  return { surviving, rejected: rejectedEntries, collisions }
}

export async function verifyOpenFoodFacts(canonical: string): Promise<boolean> {
  try {
    const url = `https://in.openfoodfacts.org/cgi/search.pl?search_terms=${encodeURIComponent(canonical.toLowerCase())}&json=1&page_size=1`
    const res = await fetch(url)
    if (!res.ok) return false
    const data = await res.json()
    return Array.isArray(data.products) && data.products.length > 0
  } catch {
    return false
  }
}

export async function callGrokForCategory(
  category: string,
  vertical: string,
  apiKey: string
): Promise<GeneratedEntry[]> {
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

  const res = await fetch('https://api.x.ai/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: 'grok-4.20-0309-non-reasoning',
      messages: [{ role: 'user', content: prompt }],
      response_format: { type: 'json_object' },
    }),
  })

  if (!res.ok) {
    const errText = await res.text()
    throw new Error(`Grok API request failed for category "${category}" (${res.status}): ${errText}`)
  }

  const data = await res.json()
  const contentStr = data.choices?.[0]?.message?.content
  if (!contentStr) {
    throw new Error(`No content returned from Grok for category "${category}"`)
  }

  const parsed = JSON.parse(contentStr)
  const entries: GeneratedEntry[] = parsed.entries || []
  return entries.map(e => ({
    canonical: e.canonical || '',
    vertical: vertical,
    category_key: category,
    default_unit: e.default_unit || 'PACKET',
    aliases: {
      devanagari: Array.isArray(e.aliases?.devanagari) ? e.aliases.devanagari : [],
      hinglish: Array.isArray(e.aliases?.hinglish) ? e.aliases.hinglish : [],
      english: Array.isArray(e.aliases?.english) ? e.aliases.english : [],
    },
    size_modifiers_apply: Boolean(e.size_modifiers_apply),
  }))
}

async function processInBatches<T, R>(
  items: T[],
  batchSize: number,
  fn: (item: T) => Promise<R>
): Promise<R[]> {
  const results: R[] = []
  for (let i = 0; i < items.length; i += batchSize) {
    const batch = items.slice(i, i + batchSize)
    const batchResults = await Promise.all(batch.map(fn))
    results.push(...batchResults)
  }
  return results
}

export async function main() {
  const apiKey = Deno.env.get('XAI_API_KEY')
  if (!apiKey) {
    console.error(
      'Error: XAI_API_KEY environment variable is unset.\n' +
      'Precondition failed: Please set $env:XAI_API_KEY before running this tool.'
    )
    Deno.exit(1)
  }

  const spineText = await Deno.readTextFile('Docs/data/category_spine.json')
  const spine: CategorySpine = JSON.parse(spineText)

  console.log(`Generating T1 vocabulary for vertical "${spine.vertical}" across ${spine.categories.length} categories...`)

  const rawResults = await processInBatches(spine.categories, 8, cat =>
    callGrokForCategory(cat, spine.vertical, apiKey)
  )

  const allRawEntries = rawResults.flat()
  const totalGenerated = allRawEntries.length

  const step1 = filterFewerThan2Aliases(allRawEntries)
  const step2 = dedupeCanonical(step1.surviving)
  const step3 = processPhoneticCollisions(step2.surviving)

  const allRejections = [...step1.rejected, ...step3.rejected]

  console.log(`Running OFF verification on ${step3.surviving.length} surviving entries...`)
  const offResults = await processInBatches(step3.surviving, 8, async entry => {
    const verified = await verifyOpenFoodFacts(entry.canonical)
    return {
      ...entry,
      off_verified: verified,
    } as OutputEntry
  })

  const finalEntries = offResults.sort((a, b) => {
    if (a.category_key !== b.category_key) return a.category_key.localeCompare(b.category_key)
    return a.canonical.localeCompare(b.canonical)
  })

  const finalRejected = allRejections.sort((a, b) => {
    if (a.category_key !== b.category_key) return a.category_key.localeCompare(b.category_key)
    if (a.canonical !== b.canonical) return a.canonical.localeCompare(b.canonical)
    return a.reason.localeCompare(b.reason)
  })

  const countOffVerified = finalEntries.filter(e => e.off_verified).length

  const outputData = {
    generated_at: new Date().toISOString(),
    model: 'grok-4.20-0309-non-reasoning',
    vertical: spine.vertical,
    entry_count: finalEntries.length,
    rejected_count: finalRejected.length,
    entries: finalEntries,
    rejected: finalRejected,
    ...(step2.duplicates.length > 0 ? { duplicates: step2.duplicates } : {}),
  }

  await Deno.writeTextFile(
    'Docs/data/master_catalog_t1_seed_kirana.json',
    JSON.stringify(outputData, null, 2)
  )

  if (step3.collisions.length > 0) {
    const header = 'phonetic_key\tcategory_a\tcanonical_a\tcategory_b\tcanonical_b\n'
    await Deno.writeTextFile('tools/catalog/collisions.tsv', header + step3.collisions.join('\n') + '\n')
  }

  const fewerThan2 = allRejections.filter(r => r.reason === 'fewer_than_2_aliases').length
  const crossCategory = allRejections.filter(r => r.reason === 'cross_category_collision').length

  console.log('\n--- Summary ---')
  console.log(`Total entries generated: ${totalGenerated}`)
  console.log(`Total rejected: ${finalRejected.length} (fewer_than_2_aliases: ${fewerThan2}, cross_category_collision: ${crossCategory})`)
  console.log(`Total duplicates deduped: ${step2.duplicates.length}`)
  console.log(`Total cross-category collisions written to collisions.tsv: ${step3.collisions.length}`)
  console.log(`Total off_verified: true: ${countOffVerified}`)
}

if (import.meta.main) {
  await main()
}
