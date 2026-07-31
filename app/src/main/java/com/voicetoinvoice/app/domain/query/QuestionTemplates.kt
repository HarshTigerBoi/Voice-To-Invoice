package com.voicetoinvoice.app.domain.query

import com.voicetoinvoice.app.domain.parser.PhoneticKey
import com.voicetoinvoice.app.domain.voice.ResponseComposer
import java.util.Calendar

/**
 * Answers a READ_QUERY -- deterministic templates only, no AI. Every number here comes from a
 * SQL/Kotlin aggregate; this class only decides which one to speak and phrases it. See
 * Docs/master_build_plan.md §4.1: "AI never invents a number" is a hard rule, and this template
 * tier is meant to answer the large majority of questions for free before any model call is
 * even considered.
 *
 * ## Why topic matching moved off Devanagari substrings
 *
 * `IntentRouter` classifies an utterance as READ_QUERY across Devanagari/Hinglish/English (its
 * lexicon is matched in [PhoneticKey] space -- see its docs for why). This class used to decide
 * WHICH question with plain Devanagari `String.contains` checks (`REVENUE_WORDS = setOf("कमाई",
 * "बिक्री", ...)`), so a question the router correctly identified as a READ_QUERY -- e.g. "aaj ki
 * sale kitni hui" -- would still fall through every topic branch here (none of them matched a
 * Latin string) and land on `formatUnrecognized()`. The router's trilingual fix was being
 * silently undone one step later. Topics are now matched the same way the router matches
 * intents: n-grams of the transcript, in phone-key space, against phrase lists in all three
 * scripts.
 */
class QuestionTemplates(private val ledgerQueries: LedgerQueries) {

    private fun buildNgramKeys(transcript: String): List<String> {
        val words = transcript.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val keys = LinkedHashSet<String>()
        for (i in words.indices) {
            PhoneticKey.of(words[i]).takeIf { it.isNotBlank() }?.let { keys.add(it) }
            if (i + 1 < words.size) {
                PhoneticKey.of(words[i] + words[i + 1]).takeIf { it.isNotBlank() }?.let { keys.add(it) }
            }
        }
        return keys.toList()
    }

    /** How well [transcript] discusses [topicPhrases], 0..1. Mirrors `IntentRouter
     *  .bestTriggerQuality` -- kept local rather than shared, since sharing would couple this
     *  class to the router's `IntentTrigger` data shape for no real benefit. */
    private fun topicScore(ngrams: List<String>, topicPhrases: List<String>): Double {
        var best = 0.0
        for (phrase in topicPhrases) {
            val key = PhoneticKey.of(phrase)
            if (key.isBlank()) continue
            for (ng in ngrams) {
                val d = PhoneticKey.normalizedDistance(ng, key)
                if (d <= MAX_DISTANCE) {
                    val q = 1.0 - d / MAX_DISTANCE
                    if (q > best) best = q
                }
            }
        }
        return best
    }

    private fun matches(ngrams: List<String>, topicPhrases: List<String>): Boolean =
        topicScore(ngrams, topicPhrases) > 0.0

    /** Phonetic-key match against a name -> value map, so "आलू" resolves against a
     *  catalog entry spoken with any of the STT's usual mis-hearings instead of
     *  requiring an exact substring hit. Returns the best match under a generous
     *  threshold, or null. */
    private fun <T> bestPhoneticMatch(candidate: String, byName: Map<String, T>): Pair<String, T>? {
        if (candidate.isBlank() || byName.isEmpty()) return null
        val candidateKey = PhoneticKey.of(candidate)
        if (candidateKey.isEmpty()) return null
        return byName.entries
            .map { (name, value) -> Triple(name, value, PhoneticKey.normalizedDistance(candidateKey, PhoneticKey.of(name))) }
            .filter { it.third <= 0.34 }
            .minByOrNull { it.third }
            ?.let { it.first to it.second }
    }

    /** Strips topic/question words in all three scripts so what remains is (usually) the
     *  item or customer name the question is actually about. */
    private fun extractCandidateName(transcript: String): String {
        val tokens = transcript.trim().lowercase().split(Regex("\\s+"))
        val filtered = tokens.filter { token ->
            val key = PhoneticKey.of(token)
            key.isNotBlank() && STOPWORD_KEYS.none { PhoneticKey.normalizedDistance(key, it) <= 0.15 }
        }
        return filtered.joinToString(" ").trim()
    }

    private val STOPWORD_PHRASES = listOf(
        "का", "की", "के", "कितना", "कितने", "कितनी", "है", "बचा", "बाकी", "उधार",
        "स्टॉक", "मेरा", "अभी", "आज", "बताओ", "बता", "दो", "कमाई", "कमाया",
        "ka", "ki", "ke", "kitna", "kitne", "kitni", "hai", "bacha", "baki", "udhaar", "udhar",
        "stock", "mera", "abhi", "aaj", "batao", "bata", "do", "kamai", "kamaya",
        "how", "much", "is", "left", "today", "owes", "owe", "tell", "me", "what", "the"
    )
    private val STOPWORD_KEYS = STOPWORD_PHRASES.map { PhoneticKey.of(it) }.filter { it.isNotBlank() }

    // Every topic is listed in Devanagari, Latin-Hinglish and English -- the same trilingual
    // requirement IntentRouter's lexicon exists for.
    private val REVENUE_WORDS = listOf(
        "कमाई", "कमाया", "बिक्री", "बिका", "हिसाब", "कुल", "व्यापार", "सेल", "टोटल सेल",
        "kamai", "kamaya", "bikri", "bika", "hisaab", "kul", "vyapar", "sale", "total sale",
        "revenue", "earning", "earned", "business", "turnover"
    )
    private val PROFIT_WORDS = listOf(
        "मुनाफ़ा", "मुनाफा", "प्रॉफिट", "फायदा",
        "munafa", "profit", "fayda", "faayda"
    )
    private val TOP_ITEM_WORDS = listOf(
        "सबसे ज्यादा", "सबसे अच्छा", "टॉप", "ज्यादा बिका",
        "sabse zyada", "sabse jyada", "top item", "zyada bika",
        "best selling", "top selling", "most sold"
    )
    private val UDHAAR_WORDS = listOf(
        "उधार", "बाकी", "खाता", "देना", "बकाया",
        "udhaar", "udhar", "baki", "khata", "dena", "bakaya",
        "credit", "owes", "owe", "due", "outstanding"
    )
    private val RECEIVABLES_TOTAL_WORDS = listOf(
        "कुल उधार", "टोटल उधार", "सबका उधार",
        "kul udhaar", "total udhaar", "sabka udhaar",
        "total credit", "total receivable", "total due", "total outstanding"
    )
    private val STOCK_WORDS = listOf(
        "स्टॉक", "बचा", "माल",
        "stock", "bacha", "bacha hai", "maal",
        "left", "remaining", "how much stock"
    )
    private val WASTE_WORDS = listOf(
        "खराब", "बर्बाद", "वेस्ट",
        "kharab", "barbaad", "waste",
        "spoiled", "spoilage", "wasted"
    )
    private val GENERIC_QUESTION_WORDS = listOf(
        "कितना", "कैसा", "कैसे", "kitna", "kaisa", "kaise", "how"
    )

    private val MAX_DISTANCE = 0.25

    suspend fun answerQuestion(transcript: String): String {
        val clean = transcript.trim()
        val ngrams = buildNgramKeys(clean)

        val todayMidnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val nowMs = System.currentTimeMillis()

        // 1. Top selling item -- checked before revenue so "सबसे ज्यादा क्या बिका" / "top
        //    selling item" (which also matches "बिका"/"sold") doesn't get swallowed by the
        //    total-sales branch.
        if (matches(ngrams, TOP_ITEM_WORDS)) {
            val topItem = if (LedgerSnapshot.isFresh()) LedgerSnapshot.topItemToday
            else ledgerQueries.getTopSellingItem(todayMidnight, nowMs)
            return if (topItem != null) "आज सबसे ज्यादा $topItem बिका" else "आज अभी तक कुछ नहीं बिका"
        }

        // 2. Profit -- checked before plain revenue so "आज का मुनाफ़ा" doesn't answer with
        //    total sales instead. Always paired with its coverage caveat (see ProfitCalculator):
        //    a confidently wrong profit figure costs a shopkeeper's trust permanently.
        if (matches(ngrams, PROFIT_WORDS)) {
            val profit = ledgerQueries.getProfit(todayMidnight, nowMs)
            if (profit.isTooSparseToReport) {
                return "अभी मुनाफ़ा बताने के लिए पर्याप्त खरीद भाव दर्ज नहीं है"
            }
            val coverageNote = if (!profit.isFullyCosted) " (${profit.costCoveragePct.toInt()}% बिक्री का हिसाब)" else ""
            return "आज का मुनाफ़ा ₹${profit.grossProfit.toInt()}$coverageNote"
        }

        // 3. Today's total sales
        if (matches(ngrams, REVENUE_WORDS)) {
            val (total, count) = if (LedgerSnapshot.isFresh()) {
                LedgerSnapshot.todayTotal to LedgerSnapshot.todayCount
            } else {
                ledgerQueries.getTodaySalesSummary(todayMidnight)
            }
            return ResponseComposer.formatDailySales(total, count)
        }

        // 4. Total receivables (checked before per-customer Udhaar so "कुल उधार कितना है"
        //    doesn't get misread as a customer-name lookup for "कुल").
        if (matches(ngrams, RECEIVABLES_TOTAL_WORDS)) {
            val total = ledgerQueries.getTotalReceivables()
            return if (total > 0.0) "कुल ₹${total.toInt()} का उधार बाकी है" else "कोई उधार बाकी नहीं है"
        }

        // 5. Per-customer balance inquiry
        if (matches(ngrams, UDHAAR_WORDS)) {
            val candidate = extractCandidateName(clean)
            if (candidate.isNotBlank()) {
                if (LedgerSnapshot.isFresh()) {
                    val match = bestPhoneticMatch(candidate, LedgerSnapshot.outstandingByCustomerName)
                    if (match != null) return "${match.first} का बकाया ₹${match.second.toInt()} है"
                }
                val balance = ledgerQueries.getCustomerBalance(candidate)
                if (balance != null) return "$candidate का बकाया ₹${balance.toInt()} है"
                return "$candidate नाम का कोई ग्राहक नहीं मिला"
            }
        }

        // 6. Waste this period
        if (matches(ngrams, WASTE_WORDS)) {
            val waste = ledgerQueries.getWasteValue(todayMidnight, nowMs)
            return if (waste > 0.0) "आज ₹${waste.toInt()} का माल खराब हुआ" else "आज कोई माल खराब नहीं हुआ"
        }

        // 7. Stock inquiry
        if (matches(ngrams, STOCK_WORDS)) {
            val candidate = extractCandidateName(clean)
            if (candidate.isNotBlank()) {
                if (LedgerSnapshot.isFresh()) {
                    val match = bestPhoneticMatch(candidate, LedgerSnapshot.stockByItemName)
                    if (match != null) return ResponseComposer.formatStockReport(match.first, match.second, "इकाई")
                }
                val resolved = ledgerQueries.getStockLevelWithName(candidate)
                if (resolved != null) return ResponseComposer.formatStockReport(resolved.first, resolved.second, "इकाई")
                return "$candidate नाम का कोई आइटम नहीं मिला"
            }
        }

        // 8. A clearly interrogative utterance that named no specific topic/entity is, in
        //    practice, almost always "how's business today" -- answer that instead of a flat
        //    "I didn't understand".
        if (matches(ngrams, GENERIC_QUESTION_WORDS)) {
            val (total, count) = if (LedgerSnapshot.isFresh()) {
                LedgerSnapshot.todayTotal to LedgerSnapshot.todayCount
            } else {
                ledgerQueries.getTodaySalesSummary(todayMidnight)
            }
            return ResponseComposer.formatDailySales(total, count)
        }

        return ResponseComposer.formatUnrecognized()
    }
}
