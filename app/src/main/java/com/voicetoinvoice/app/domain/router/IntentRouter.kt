package com.voicetoinvoice.app.domain.router

import com.voicetoinvoice.app.data.local.entity.CaptureIntent
import com.voicetoinvoice.app.domain.parser.OrderingSegmenter
import com.voicetoinvoice.app.domain.parser.PhoneticKey
import org.json.JSONArray

enum class AssistantIntent {
    SALE,
    CREDIT_SALE,
    PAYMENT_RECEIVED,
    STOCK_IN,
    RETURN,
    WASTE,
    EXPIRY_WRITEOFF,
    PRICE_UPDATE,
    VOID_LAST,
    READ_QUERY,
    ACTION_COMMAND,
    UNKNOWN
}

data class IntentClassification(
    val intent: AssistantIntent,
    val captureIntent: CaptureIntent,
    val confidence: Double,
    val isReadOnly: Boolean,
    /** Per-intent scores, for the diagnostic trace. Misroutes are undebuggable without this. */
    val scores: Map<AssistantIntent, Double> = emptyMap(),
    val runnerUp: AssistantIntent? = null,
    /** True when confidence is mid-band and an AI arbitration call is warranted. */
    val needsArbitration: Boolean = false
) {
    val intentName: String get() = intent.name
}

/**
 * Classifies a transcript into an actionable intent.
 *
 * ## What this replaces
 *
 * The previous implementation was a Devanagari-only substring if-ladder with four structural
 * problems (see Docs/master_build_plan.md §2.1):
 *
 *  1. **Devanagari-only.** STT frequently returns Latin (ISSUE-004/020), so *"aaj ki sale kitni
 *     hui"* matched nothing and returned UNKNOWN. Matching now happens in [PhoneticKey] space,
 *     which is script-agnostic by construction.
 *  2. **`ACTION_COMMAND` was unreachable.** `ACTION_WORDS` was declared and never read; no branch
 *     could return it. Combined with a zero-call-site `ActionExecutor`, that was the
 *     "doesn't send anything anywhere" bug.
 *  3. **Missing intents**: no PRICE_UPDATE, RETURN, PAYMENT_RECEIVED or VOID_LAST.
 *  4. **First-match-wins with no confidence.** `"खराब आलू का उधार लिख दो"` hit WASTE first and
 *     never considered CREDIT_SALE. Every intent is now scored and the margin between the top two
 *     becomes the confidence.
 */
object IntentRouter {

    /** At or above this, route straight through. */
    const val DIRECT_ROUTE_CONFIDENCE = 0.75

    /** Below this, give up and ask rather than guess. */
    const val ARBITRATION_FLOOR = 0.45

    fun classify(transcript: String, parsedItems: JSONArray = JSONArray()): IntentClassification {
        val clean = transcript.trim()
        if (clean.isBlank()) return unknown()

        val hasItemLines = hasUsableItemLines(parsedItems)
        val ngrams = buildNgramKeys(clean)
        if (ngrams.isEmpty()) return unknown()

        // --- score every intent independently ---
        val scores = mutableMapOf<AssistantIntent, Double>()
        for ((intent, triggers) in IntentLexicon.BY_INTENT) {
            var intentScore = 0.0
            for (trigger in triggers) {
                if (trigger.requiresItemLines != null && trigger.requiresItemLines != hasItemLines) continue
                val best = bestTriggerQuality(trigger, ngrams)
                if (best > 0.0) intentScore += trigger.weight * best
            }
            if (intentScore > 0.0) scores[intent] = intentScore
        }

        applyStructuralGates(scores, clean, ngrams, parsedItems, hasItemLines)

        // A plain item list with no keyword at all is a cash sale -- the single most common
        // utterance in the shop, and it has no trigger word by nature.
        if (hasItemLines) {
            scores[AssistantIntent.SALE] = (scores[AssistantIntent.SALE] ?: 0.0) + BASE_SALE_SCORE
        }

        if (scores.isEmpty()) return unknown()

        val ranked = scores.entries.sortedByDescending { it.value }
        val top = ranked[0]
        val second = ranked.getOrNull(1)
        val topScore = top.value
        val secondScore = second?.value ?: 0.0

        // Confidence is the margin between the best two readings, not the raw score: a
        // transcript matching two intents equally well is genuinely ambiguous no matter how
        // strongly it matched either.
        val confidence = if (topScore + secondScore <= 0.0) 0.0
        else (topScore / (topScore + secondScore)).coerceIn(0.0, 1.0)

        if (confidence < ARBITRATION_FLOOR) {
            return IntentClassification(
                intent = AssistantIntent.UNKNOWN,
                captureIntent = CaptureIntent.ASSISTANT,
                confidence = confidence,
                isReadOnly = false,
                scores = scores,
                runnerUp = second?.key,
                needsArbitration = false
            )
        }

        return IntentClassification(
            intent = top.key,
            captureIntent = captureIntentFor(top.key),
            confidence = confidence,
            isReadOnly = top.key == AssistantIntent.READ_QUERY,
            scores = scores,
            runnerUp = second?.key,
            needsArbitration = confidence < DIRECT_ROUTE_CONFIDENCE
        )
    }

    /**
     * Structural disambiguation that pure keyword scoring cannot do.
     *
     * These are the cases where two intents share vocabulary but mean opposite things, and where
     * getting it wrong is expensive rather than merely unhelpful.
     */
    private fun applyStructuralGates(
        scores: MutableMap<AssistantIntent, Double>,
        clean: String,
        ngrams: List<String>,
        parsedItems: JSONArray,
        hasItemLines: Boolean
    ) {
        val hasMoney = MONEY_PATTERN.containsMatchIn(clean)
        val itemCount = usableItemCount(parsedItems)

        // PAYMENT_RECEIVED vs CREDIT_SALE: money moving IN vs debt going UP, on the same ledger.
        // "Ramesh ne 500 diye" is a payment; "Ramesh ko 500 udhaar" is new debt. A payment phrase
        // with an amount and no goods is decisive, so suppress the credit reading.
        if (scores.containsKey(AssistantIntent.PAYMENT_RECEIVED) && hasMoney && !hasItemLines) {
            scores[AssistantIntent.PAYMENT_RECEIVED] = scores[AssistantIntent.PAYMENT_RECEIVED]!! + 1.0
            scores.remove(AssistantIntent.CREDIT_SALE)
        }

        // PRICE_UPDATE requires exactly one item and a price, and must not look like a bulk sale.
        // "aaloo ka rate 30 kar do" updates a rate; "5 kilo aaloo 30 ka" is a sale.
        if (scores.containsKey(AssistantIntent.PRICE_UPDATE)) {
            val singleItem = itemCount <= 1
            val qtyLooksLikeSale = maxQuantity(parsedItems) > 1.0
            if (!singleItem || qtyLooksLikeSale || !hasMoney) {
                scores.remove(AssistantIntent.PRICE_UPDATE)
            } else {
                scores[AssistantIntent.PRICE_UPDATE] = scores[AssistantIntent.PRICE_UPDATE]!! + 0.8
                // A rate update is not a sale of one unit.
                scores.remove(AssistantIntent.SALE)
            }
        }

        // A question with no goods attached is a read, and reads must never write. Boost so an
        // incidental keyword ("aaj kitna udhaar baaki hai" also matches CREDIT_SALE) cannot turn
        // a question into a ledger entry.
        //
        // Gated on a STRONG question word: the weak ones ("kya", "fayda") collide with ordinary
        // speech in phone space, and boosting on those turned "galat ho gaya cancel karo" and
        // "ramesh paid 500" into questions -- which would silently drop a correction and a
        // payment instead of booking them.
        // The boost is PROPORTIONAL to how cleanly a strong question word matched, not a flat
        // bonus past a threshold. A flat bonus has a cliff right at the accept threshold, and a
        // barely-accepted match got the full boost: "gaya" keys within exactly 0.25 of "kamaya",
        // so "galat ho gaya cancel karo" was read as "how much did I earn" and the correction was
        // dropped. Scaling by quality means a borderline hit contributes nothing.
        val strongQuestionQuality = ngrams.maxOfOrNull { ng ->
            IntentLexicon.STRONG_QUESTION_KEYS.maxOfOrNull { key ->
                val d = PhoneticKey.normalizedDistance(ng, key)
                if (d <= IntentLexicon.MAX_TRIGGER_DISTANCE) {
                    1.0 - (d / IntentLexicon.MAX_TRIGGER_DISTANCE)
                } else 0.0
            } ?: 0.0
        } ?: 0.0

        if (scores.containsKey(AssistantIntent.READ_QUERY)) {
            if (strongQuestionQuality > 0.0 && !hasItemLines) {
                scores[AssistantIntent.READ_QUERY] =
                    scores[AssistantIntent.READ_QUERY]!! + (1.5 * strongQuestionQuality)
            }
            if (strongQuestionQuality <= 0.0) {
                // Only weak question words fired -- they may support a reading, never carry it.
                scores[AssistantIntent.READ_QUERY] = scores[AssistantIntent.READ_QUERY]!! * 0.5
            }
        }

        // EXPIRY is a more specific WASTE; when both fire, the specific one wins.
        if (scores.containsKey(AssistantIntent.EXPIRY_WRITEOFF)) {
            scores.remove(AssistantIntent.WASTE)
        }

        // VOID_LAST is a correction about a previous utterance, so it carries no goods of its own.
        // "galat item daal diya" cancels; "2 kilo galat aaloo" is not a void.
        if (scores.containsKey(AssistantIntent.VOID_LAST) && hasItemLines) {
            scores[AssistantIntent.VOID_LAST] = scores[AssistantIntent.VOID_LAST]!! * 0.4
        }

        // ACTION_COMMAND ("Ramesh ko bill bhejo") carries no goods either.
        if (scores.containsKey(AssistantIntent.ACTION_COMMAND) && !hasItemLines) {
            scores[AssistantIntent.ACTION_COMMAND] = scores[AssistantIntent.ACTION_COMMAND]!! + 1.0
        }

        // STOCK_IN and RETURN both add stock back but from different directions; a return needs an
        // explicit return word, which is already weight 1.0, so only guard the reverse: a purchase
        // phrase plus goods should not read as a return.
        if (scores.containsKey(AssistantIntent.RETURN) && scores.containsKey(AssistantIntent.STOCK_IN)) {
            val returnKey = ngrams.any { ng ->
                RETURN_STRONG_KEYS.any { PhoneticKey.normalizedDistance(ng, it) <= IntentLexicon.MAX_TRIGGER_DISTANCE }
            }
            if (!returnKey) scores.remove(AssistantIntent.RETURN)
        }
    }

    /** Best match quality (0..1) for a trigger across every n-gram; 0 when nothing matched. */
    private fun bestTriggerQuality(trigger: IntentTrigger, ngrams: List<String>): Double {
        var best = 0.0
        for (phraseKey in trigger.phraseKeys) {
            for (ng in ngrams) {
                val d = PhoneticKey.normalizedDistance(ng, phraseKey)
                if (d <= IntentLexicon.MAX_TRIGGER_DISTANCE) {
                    val quality = 1.0 - (d / IntentLexicon.MAX_TRIGGER_DISTANCE)
                    // Exact key match still scores 1.0; a borderline match scores near 0, so
                    // fuzzy hits cannot outvote a clean one.
                    if (quality > best) best = quality
                    if (best >= 1.0) return 1.0
                }
            }
        }
        return best
    }

    /**
     * Phone keys of every numeral and unit surface. See ISSUE-120 — "चार" keys to CAL,
     * identical to the ACTION_COMMAND trigger "call" (verified distance 0.0000), which routed
     * three plain cash sales to review instead of booking them. Threshold tuning cannot fix a
     * zero distance, so quantity-only spans are excluded from trigger matching outright.
     */
    private val QUANTITY_KEYS: Set<String> =
        (OrderingSegmenter.HINDI_NUMBER_MAP.keys + OrderingSegmenter.UNIT_SET)
            .map { PhoneticKey.of(it) }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * Literal lowercased surfaces of every trigger word.
     *
     * The first version of this fix asserted "no trigger phrase is a number or a unit, so
     * filtering is safe by construction." That was WRONG and the fixture caught it: the
     * PRICE_UPDATE trigger `भाव`/`bhav` (rate) and the quantity word `पाव` (quarter) BOTH key
     * to `PAV`, so filtering by key alone deleted the `bhav` trigger and "aaloo ka bhav 30"
     * degraded from PRICE_UPDATE to SALE — turning a rate change into a fake one-unit sale.
     *
     * The asymmetry that resolves it: the trigger lexicon is the authority on what a trigger
     * word is. `bhav` is literally in the trigger list and is NOT literally a numeral surface,
     * so it is kept. `चार` is literally a numeral surface and is NOT literally a trigger word,
     * so it is still filtered and the original ISSUE-120 collision stays fixed.
     */
    private val TRIGGER_SURFACES: Set<String> =
        IntentLexicon.TRIGGERS
            .flatMap { it.phrases }
            .flatMap { it.lowercase().split(Regex("\\s+")) }
            .filter { it.isNotBlank() }
            .toSet()

    /** True when a word is a bare number, a number word, or a unit — and is not itself a trigger word. */
    private fun isQuantityToken(word: String): Boolean {
        val lower = word.lowercase().trim()
        if (lower.isEmpty()) return true
        // A literal trigger word is never a quantity, whatever it happens to key to.
        if (lower in TRIGGER_SURFACES) return false
        if (lower.toDoubleOrNull() != null) return true
        val key = PhoneticKey.of(lower)
        return key.isNotBlank() && key in QUANTITY_KEYS
    }

    /**
     * Unigram + bigram + trigram phone keys.
     *
     * Bigrams and trigrams are required because most trigger phrases are multi-word ("de diye",
     * "kar do", "how much", "tareekh nikal gayi") and a unigram-only comparison could never match
     * them.
     */
    private fun buildNgramKeys(transcript: String): List<String> {
        val words = transcript.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val isQty = words.map { isQuantityToken(it) }
        val keys = LinkedHashSet<String>()
        for (i in words.indices) {
            if (!isQty[i]) {
                PhoneticKey.of(words[i]).takeIf { it.isNotBlank() }?.let { keys.add(it) }
            }
            if (i + 1 < words.size && !(isQty[i] && isQty[i + 1])) {
                PhoneticKey.of(words[i] + words[i + 1]).takeIf { it.isNotBlank() }?.let { keys.add(it) }
            }
            if (i + 2 < words.size && !(isQty[i] && isQty[i + 1] && isQty[i + 2])) {
                PhoneticKey.of(words[i] + words[i + 1] + words[i + 2]).takeIf { it.isNotBlank() }
                    ?.let { keys.add(it) }
            }
        }
        return keys.toList()
    }

    /** Mirrors the previous implementation's definition so sale detection does not regress. */
    private fun hasUsableItemLines(parsedItems: JSONArray): Boolean = usableItemCount(parsedItems) > 0

    private fun usableItemCount(parsedItems: JSONArray): Int {
        var count = 0
        for (i in 0 until parsedItems.length()) {
            val item = parsedItems.optJSONObject(i) ?: continue
            val qty = item.optDouble("quantity", 0.0)
            val name = item.optString("item_name", "").trim()
            if (qty > 0.0 && name.isNotBlank() && name != "Unrecognized Item") count++
        }
        return count
    }

    private fun maxQuantity(parsedItems: JSONArray): Double {
        var max = 0.0
        for (i in 0 until parsedItems.length()) {
            val q = parsedItems.optJSONObject(i)?.optDouble("quantity", 0.0) ?: 0.0
            if (q > max) max = q
        }
        return max
    }

    fun captureIntentFor(intent: AssistantIntent): CaptureIntent = when (intent) {
        AssistantIntent.SALE -> CaptureIntent.SALE
        AssistantIntent.CREDIT_SALE -> CaptureIntent.CREDIT_SALE
        AssistantIntent.STOCK_IN -> CaptureIntent.STOCK_IN
        AssistantIntent.WASTE -> CaptureIntent.WASTE
        AssistantIntent.EXPIRY_WRITEOFF -> CaptureIntent.EXPIRY_WRITEOFF
        AssistantIntent.RETURN -> CaptureIntent.RETURN
        AssistantIntent.PAYMENT_RECEIVED -> CaptureIntent.PAYMENT_RECEIVED
        AssistantIntent.PRICE_UPDATE -> CaptureIntent.PRICE_UPDATE
        AssistantIntent.VOID_LAST -> CaptureIntent.VOID_LAST
        AssistantIntent.READ_QUERY, AssistantIntent.ACTION_COMMAND, AssistantIntent.UNKNOWN ->
            CaptureIntent.ASSISTANT
    }

    private fun unknown() = IntentClassification(
        intent = AssistantIntent.UNKNOWN,
        captureIntent = CaptureIntent.ASSISTANT,
        confidence = 0.0,
        isReadOnly = false
    )

    /**
     * Baseline score for "there are goods here". Deliberately below a full keyword hit (1.0) so an
     * explicit trigger word always beats the bare-sale reading, but high enough that a plain
     * `"पाँच किलो आलू"` with no keyword still lands on SALE with usable confidence.
     */
    private const val BASE_SALE_SCORE = 0.9

    /** A rupee amount or a bare number -- used to tell rate updates and payments from questions. */
    private val MONEY_PATTERN = Regex("(₹|rs\\.?|rupee|रुपये|रुपए)?\\s*\\d+(\\.\\d+)?", RegexOption.IGNORE_CASE)

    private val RETURN_STRONG_KEYS = listOf("वापस", "wapas", "return", "लौटा", "lauta")
        .map { PhoneticKey.of(it) }
}
