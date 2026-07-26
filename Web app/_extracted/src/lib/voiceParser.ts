import type { Item, MatchResult, ParsedUtterance, Unit } from "../types";

/** Hindi number words → value */
const HINDI_NUMBERS: Record<string, number> = {
  एक: 1, एकदम: 1,
  दो: 2,
  तीन: 3,
  चार: 4,
  पाँच: 5, पांच: 5,
  छह: 6, छः: 6,
  सात: 7,
  आठ: 8,
  नौ: 9,
  दस: 10,
  ग्यारह: 11,
  बारह: 12,
  तेरह: 13,
  चौदह: 14,
  पंद्रह: 15, पन्द्रह: 15,
  सोलह: 16,
  बीस: 20,
  पच्चीस: 25, पचीस: 25,
  तीस: 30,
  चालीस: 40,
  पचास: 50,
  साठ: 60,
};

/** Special fraction words */
const FRACTION_WORDS: Record<string, number> = {
  आधा: 0.5, आधी: 0.5,
  डेढ़: 1.5, डेड: 1.5, देढ़: 1.5,
  सवा: 1.25,
};

/** Unit words → normalized unit */
const UNIT_WORDS: Record<string, Unit> = {
  किलो: "kg", किलोग्राम: "kg", केजी: "kg", kilo: "kg", kilos: "kg", kg: "kg", kgs: "kg",
  ग्राम: "g", gram: "g", grams: "g", gm: "g", g: "g",
  लीटर: "L", लिटर: "L", liter: "L", litre: "L", ltr: "L", l: "L",
  मिली: "ml", मिलीलीटर: "ml", ml: "ml", milliliter: "ml", millilitre: "ml",
  पैकेट: "pkt", पैकेट्स: "pkt", packet: "pkt", packets: "pkt", pkt: "pkt", pack: "pkt",
  पीस: "pc", piece: "pc", pieces: "pc", pcs: "pc", pc: "pc", नग: "pc",
  बोतल: "btl", बोतलें: "btl", bottle: "btl", bottles: "btl", btl: "btl",
  दर्जन: "dozen", dozen: "dozen", दर्जनों: "dozen",
  कट्टा: "bag", बोरी: "bag", bag: "bag", bags: "bag", sack: "bag",
  डिब्बा: "pc", टिन: "pc", tin: "pc",
};

/** Words that never belong to an item name */
const FILLER_WORDS = new Set([
  "मुझे", "मुझको", "चाहिए", "चाहिये", "कृपया", "जल्दी", "भाई", "भैया", "जी",
  "का", "की", "के", "को", "से", "में", "मिलेगा", "मिलेगी", "देना", "दे", "दीजिए",
  "please", "give", "me", "i", "want", "need", "the", "of", "and", "do", "dena",
  "mujhe", "chahiye", "aur", "bhai",
]);

function levenshtein(a: string, b: string): number {
  const m = a.length, n = b.length;
  if (!m) return n;
  if (!n) return m;
  const dp = new Array(n + 1);
  for (let j = 0; j <= n; j++) dp[j] = j;
  for (let i = 1; i <= m; i++) {
    let prev = dp[0];
    dp[0] = i;
    for (let j = 1; j <= n; j++) {
      const tmp = dp[j];
      dp[j] = Math.min(
        dp[j] + 1,
        dp[j - 1] + 1,
        prev + (a[i - 1] === b[j - 1] ? 0 : 1)
      );
      prev = tmp;
    }
  }
  return dp[n];
}

const normalize = (s: string) =>
  s
    .toLowerCase()
    .replace(/[।,.!?;:]+/g, "")
    .replace(/\s+/g, "")
    .trim();

/** Parse "चीनी दो किलो" / "rice 5 kg" / "दूध तीन पैकेट" into structured data */
export function parseUtterance(input: string): ParsedUtterance {
  const text = input.toLowerCase().replace(/[।,!?;:]/g, " ").trim();
  const tokens = text.split(/\s+/).filter(Boolean);

  let qty: number | null = null;
  let unit: Unit | null = null;
  const nameTokens: string[] = [];
  const used = new Set<number>();

  // Pass 1: special fraction words
  tokens.forEach((t, i) => {
    if (t in FRACTION_WORDS && qty === null) {
      qty = FRACTION_WORDS[t];
      used.add(i);
    }
  });

  // Pass 2: numeric digits
  if (qty === null) {
    for (let i = 0; i < tokens.length; i++) {
      const m = tokens[i].match(/^(\d+(?:\.\d+)?)$/);
      if (m) {
        qty = parseFloat(m[1]);
        used.add(i);
        break;
      }
    }
  }

  // Pass 3: Hindi number words
  if (qty === null) {
    for (let i = 0; i < tokens.length; i++) {
      if (tokens[i] in HINDI_NUMBERS) {
        qty = HINDI_NUMBERS[tokens[i]];
        used.add(i);
        break;
      }
    }
  }

  // Pass 4: "पौने X" → X - 0.25 (पौने तीन = 2.75)
  for (let i = 0; i < tokens.length; i++) {
    if (tokens[i] === "पौने" && i + 1 < tokens.length) {
      const next = tokens[i + 1];
      const base = HINDI_NUMBERS[next];
      if (base) {
        qty = base - 0.25;
        used.add(i);
        used.add(i + 1);
        break;
      }
    }
  }

  // Pass 5: unit words
  for (let i = 0; i < tokens.length; i++) {
    const u = UNIT_WORDS[tokens[i].replace(/s$/, "")] ?? UNIT_WORDS[tokens[i]];
    if (u) {
      unit = u;
      used.add(i);
      break;
    }
  }

  // Remaining non-filler tokens form the item name
  tokens.forEach((t, i) => {
    if (!used.has(i) && !FILLER_WORDS.has(t)) nameTokens.push(t);
  });

  return {
    qty: qty ?? 1,
    qtyFound: qty !== null,
    unit,
    rawName: nameTokens.join(" "),
  };
}

/** Match parsed name against inventory (exact, alias, fuzzy) */
export function matchItem(name: string, items: Item[]): MatchResult {
  const n = normalize(name);
  if (!n) return { item: null, suggestions: [], confidence: "none" };

  // Exact match on normalized Hindi name or alias
  const exact = items.find(
    (it) => normalize(it.name) === n || normalize(it.altName) === n
  );
  if (exact) return { item: exact, suggestions: [], confidence: "exact" };

  // Score fuzzy candidates
  const scored = items
    .map((it) => {
      const a = normalize(it.name);
      const b = normalize(it.altName);
      const distA = a && Math.abs(a.length - n.length) < 4 ? levenshtein(a, n) : 99;
      const distB = b && Math.abs(b.length - n.length) < 4 ? levenshtein(b, n) : 99;
      let score = Math.min(distA, distB);
      if (a.startsWith(n) || b.startsWith(n) || n.startsWith(a) || n.startsWith(b))
        score = Math.min(score, 1);
      return { it, score };
    })
    .filter((s) => s.score <= 2)
    .sort((x, y) => x.score - y.score);

  if (scored.length > 0 && scored[0].score <= 1) {
    return {
      item: scored[0].it,
      suggestions: scored.slice(1, 4).map((s) => s.it),
      confidence: "fuzzy",
    };
  }

  return {
    item: null,
    suggestions: scored.slice(0, 3).map((s) => s.it),
    confidence: "none",
  };
}
