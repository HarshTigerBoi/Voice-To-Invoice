package com.voicetoinvoice.app.domain.parser

/**
 * Script-agnostic phonetic keying for Hindi / Hinglish / English shopkeeper speech.
 *
 * Why this exists: every matcher in this pipeline used to compare STT output against
 * vocabulary with plain orthographic edit distance. That silently fails in two ways
 * that dominate real production traces:
 *
 *  1. CROSS-SCRIPT BLINDNESS. STT is not guaranteed to return Devanagari even when
 *     asked for `hi` — it routinely returns romanized text, and occasionally decodes
 *     Hindi phonetics into a different language's lexicon entirely. A Latin-script
 *     token shares *zero characters* with a Devanagari vocabulary entry, so edit
 *     distance is effectively infinite and no match can ever be found. Observed:
 *     "तीन किलो सेब" transcribed as "tinggal sebab" (Malay), which scored distance 7
 *     against "तीन" under a budget of 1 — so the number, the unit, AND the item were
 *     all lost at once. See ISSUE-020 in Docs/audit.md.
 *
 *  2. SPELLING DISTANCE IS THE WRONG METRIC. Even with romanized vocabulary added,
 *     editDistance("tinggal", "teen") = 3 — still no match, because orthographic
 *     distance cannot model g↔k devoicing or vowel elision. The errors STT actually
 *     makes are *phonetic*, so the comparison has to happen in phone space.
 *
 * The fix is to project both sides — transcript token and vocabulary entry — into one
 * common phone alphabet, then collapse exactly the distinctions Hindi STT is known to
 * lose, and only then measure distance. `तीन`, `teen`, and the `tin-` of `tinggal` all
 * key to `TIN`.
 */
object PhoneticKey {

    private val DEVA_CONSONANTS: Map<Char, Char> = mapOf(
        'क' to 'k', 'ख' to 'k', 'ग' to 'g', 'घ' to 'g', 'ङ' to 'n',
        'च' to 'c', 'छ' to 'c', 'ज' to 'j', 'झ' to 'j', 'ञ' to 'n',
        'ट' to 't', 'ठ' to 't', 'ड' to 'd', 'ढ' to 'd', 'ण' to 'n',
        'त' to 't', 'थ' to 't', 'द' to 'd', 'ध' to 'd', 'न' to 'n',
        'प' to 'p', 'फ' to 'f', 'ब' to 'b', 'भ' to 'b', 'म' to 'm',
        'य' to 'y', 'र' to 'r', 'ल' to 'l', 'व' to 'v',
        'श' to 's', 'ष' to 's', 'स' to 's', 'ह' to 'h'
        // Nukta forms (क़ ख़ ग़ ज़ ड़ ढ़ फ़) need no entries: they are the base consonant
        // followed by U+093C, and the nukta is dropped below, so they fold onto the
        // base consonant automatically. Listing them here is impossible anyway —
        // decomposed, they are two code points and not valid Char literals.
    )

    private val DEVA_VOWELS: Map<Char, Char> = mapOf(
        'अ' to 'a', 'आ' to 'a', 'इ' to 'i', 'ई' to 'i', 'उ' to 'u', 'ऊ' to 'u',
        'ए' to 'e', 'ऐ' to 'e', 'ओ' to 'o', 'औ' to 'o', 'ऋ' to 'r'
    )

    private val DEVA_MATRAS: Map<Char, Char> = mapOf(
        'ा' to 'a', 'ि' to 'i', 'ी' to 'i', 'ु' to 'u', 'ू' to 'u',
        'े' to 'e', 'ै' to 'e', 'ो' to 'o', 'ौ' to 'o', 'ॉ' to 'o', 'ृ' to 'r'
    )

    private const val VIRAMA = '्'
    private const val ANUSVARA = 'ं'
    private const val CHANDRABINDU = 'ँ'
    private const val VISARGA = 'ः'
    private const val NUKTA = '़'

    /** Vowel phone classes — substituting one for another is cheap (see [distance]). */
    private val VOWEL_CLASSES = setOf('A', 'I', 'O')

    private fun isDevanagari(s: String): Boolean = s.any { it in 'ऀ'..'ॿ' }

    /**
     * Devanagari is an abugida: a bare consonant carries an inherent 'a' unless it is
     * killed by a virama or overridden by a matra. Expanding that here is what lets a
     * Devanagari word and its romanization converge on the same key.
     */
    private fun devanagariToLatin(s: String): String {
        val out = StringBuilder()
        for (i in s.indices) {
            val ch = s[i]
            val next = if (i + 1 < s.length) s[i + 1] else null
            when {
                DEVA_CONSONANTS.containsKey(ch) -> {
                    out.append(DEVA_CONSONANTS[ch])
                    // Schwa deletion: Hindi drops the word-final inherent vowel, so
                    // "तीन" is /tiːn/ not /tiːnə/. Without this the Devanagari key
                    // ("TINA") never converges with the romanized one ("TIN") and the
                    // whole cross-script premise fails.
                    val suppressed = next == null || next == VIRAMA || next == NUKTA ||
                        DEVA_MATRAS.containsKey(next)
                    if (!suppressed) out.append('a')
                }
                DEVA_VOWELS.containsKey(ch) -> out.append(DEVA_VOWELS[ch])
                DEVA_MATRAS.containsKey(ch) -> out.append(DEVA_MATRAS[ch])
                ch == ANUSVARA || ch == CHANDRABINDU -> out.append('n')
                ch == VISARGA -> out.append('h')
                ch == VIRAMA || ch == NUKTA -> Unit
                ch.isLetterOrDigit() -> out.append(ch.lowercaseChar())
            }
        }
        return out.toString()
    }

    /**
     * Projects a token (either script) onto the collapsed phone alphabet.
     *
     * The collapse set is not generic "soundex for Hindi" — each rule targets a
     * confusion that shows up in this app's own production traces:
     *  - aspirated/unaspirated (kh→k, bh→b, …): the single most common Hindi STT error
     *  - voicing (k↔g, t↔d, p↔b): "kilo" heard as the "-ggal" of "tinggal"
     *  - vowel length (aa→a, ee→i, oo→u): matras are dropped constantly in fast speech
     *  - nasal merge (n↔m), sibilant merge (s↔z), l↔r, v↔w
     *  - degemination: STT doubles consonants at fused word boundaries
     */
    fun of(raw: String): String {
        var s = raw.lowercase().trim()
        if (isDevanagari(s)) s = devanagariToLatin(s)
        s = s.replace(Regex("[^a-z0-9]"), "")
        if (s.isEmpty()) return ""

        // Digraphs first — must run before single-char collapse or "sh" becomes "s"+"h".
        s = s.replace("ph", "f").replace("kh", "k").replace("gh", "g")
            .replace("ch", "c").replace("jh", "j").replace("th", "t")
            .replace("dh", "d").replace("bh", "b").replace("sh", "s")
            .replace("ng", "n")

        // Vowel length.
        s = s.replace("aa", "a").replace("ee", "i").replace("ii", "i")
            .replace("oo", "u").replace("uu", "u")

        val sb = StringBuilder(s.length)
        for (c in s) {
            // /h/ survives so inconsistently in STT output (and is already folded into
            // the aspirated digraphs above) that keeping it only adds noise.
            if (c == 'h') continue
            sb.append(
                when (c) {
                    'k', 'g' -> 'K'
                    't', 'd' -> 'T'
                    'p', 'b', 'f' -> 'P'
                    'c', 'j' -> 'C'
                    's', 'z' -> 'S'
                    'n', 'm' -> 'N'
                    'v', 'w' -> 'V'
                    'l', 'r' -> 'L'
                    'i', 'e', 'y' -> 'I'
                    'o', 'u' -> 'O'
                    'a' -> 'A'
                    else -> c
                }
            )
        }

        // Degeminate: collapse runs of the same phone class to one.
        val out = StringBuilder(sb.length)
        for (c in sb) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }

    /**
     * Edit distance over phone keys, with vowel operations discounted to 0.5.
     *
     * STT reconstructs consonant skeletons far more reliably than vowels, so a
     * vowel-only discrepancy is weak evidence of a genuinely different word while a
     * consonant discrepancy is strong evidence. Uniform-cost edit distance treats
     * them identically and therefore ranks candidates badly.
     */
    fun distance(a: String, b: String): Double {
        if (a == b) return 0.0
        if (a.isEmpty()) return b.sumOf { if (it in VOWEL_CLASSES) 0.5 else 1.0 }
        if (b.isEmpty()) return a.sumOf { if (it in VOWEL_CLASSES) 0.5 else 1.0 }

        val dp = Array(a.length + 1) { DoubleArray(b.length + 1) }
        for (i in 1..a.length) dp[i][0] = dp[i - 1][0] + if (a[i - 1] in VOWEL_CLASSES) 0.5 else 1.0
        for (j in 1..b.length) dp[0][j] = dp[0][j - 1] + if (b[j - 1] in VOWEL_CLASSES) 0.5 else 1.0

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val aVowel = a[i - 1] in VOWEL_CLASSES
                val bVowel = b[j - 1] in VOWEL_CLASSES
                val sub = when {
                    a[i - 1] == b[j - 1] -> 0.0
                    aVowel && bVowel -> 0.5
                    else -> 1.0
                }
                dp[i][j] = minOf(
                    dp[i - 1][j - 1] + sub,
                    dp[i - 1][j] + if (aVowel) 0.5 else 1.0,
                    dp[i][j - 1] + if (bVowel) 0.5 else 1.0
                )
            }
        }
        return dp[a.length][b.length]
    }

    /**
     * Distance per phone. Raw distance alone lets a 2-phone fragment cheaply claim a
     * 5-phone word, which is how a naive splitter hallucinates items — an early build
     * of this decoder split "tinggal" into "teen"+"kg"+"Aaloo", inventing Aaloo out of
     * the "-lo" of "kilo". Normalizing by the longer side makes short-fragment matches
     * pay proportionally and kills that class of false positive.
     */
    fun normalizedDistance(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        return distance(a, b) / maxOf(a.length, b.length).toDouble()
    }
}
