package com.voicetoinvoice.app.domain.router

import com.voicetoinvoice.app.domain.parser.PhoneticKey

/**
 * Trigger vocabulary for intent classification, in Devanagari, Latin-Hinglish and English.
 *
 * ## Why every entry needs all three scripts
 *
 * The previous router matched Devanagari substrings only:
 * `QUESTION_WORDS = setOf("कितना", "कितने", ...)` with `clean.contains(it)`. But STT does not
 * reliably return Devanagari -- ISSUE-004/ISSUE-020 document `"तीन किलो बैंगन"` coming back as
 * `"Tinggal benggan"`. A shopkeeper saying *"aaj ki sale kitni hui"* hit zero keywords and fell
 * through to UNKNOWN. Devanagari-only matching was the single reason the app was not actually
 * trilingual.
 *
 * Triggers are compared in [PhoneticKey] space rather than by substring, so `कितना` / `kitna` /
 * `kitni` all collapse onto one key and cross-script matching happens for free. That is the same
 * mechanism ISSUE-020 used to fix item-name matching; this applies it to intents.
 *
 * ## Weights
 *
 * 1.0 = the word alone strongly implies the intent ("उधार" -> credit).
 * 0.6 = suggestive but shared across intents ("कर दो" appears in price updates AND sales), so it
 * needs corroboration from another trigger or a structural gate before it can win.
 */
data class IntentTrigger(
    val intent: AssistantIntent,
    val phrases: List<String>,
    val weight: Double = 1.0,
    /** null = don't care; true/false = only fires when item lines are present/absent. */
    val requiresItemLines: Boolean? = null
) {
    /** Phrase keys precomputed once -- classification runs on every utterance. */
    val phraseKeys: List<String> = phrases.map { PhoneticKey.of(it) }.filter { it.isNotBlank() }
}

object IntentLexicon {

    /**
     * A trigger matches a spoken n-gram when their phone keys are within this normalized
     * distance. 0.25 tolerates the vowel drift and aspiration loss STT actually produces while
     * still rejecting genuinely different words (e.g. "likh do" vs "kar do" score 0.5).
     */
    const val MAX_TRIGGER_DISTANCE = 0.25

    val TRIGGERS: List<IntentTrigger> = listOf(

        // ---------- READ_QUERY ----------
        // Split into strong and weak deliberately. Very short question words collide badly in
        // phone space once voicing and vowel length are collapsed -- "kya" keys within 0.125 of
        // "gaya" ("galat ho gaya" read as a question), and "fayda" within 0.1 of "paid"
        // ("ramesh paid 500" read as a question about profit). Only STRONG triggers are allowed
        // to assert that an utterance is a question (see IntentRouter's READ_QUERY gate).
        IntentTrigger(
            AssistantIntent.READ_QUERY,
            listOf(
                "कितना", "कितने", "कितनी", "kitna", "kitne", "kitni", "kitna hua", "kitni hui",
                "how much", "how many", "बताओ", "बता दो", "batao", "bata do", "tell me",
                "हिसाब", "hisaab", "hisab", "कमाई", "kamai", "कमाया", "kamaya",
                "total kya", "आज की सेल", "aaj ki sale", "today sale", "todays sales",
                "स्टॉक कितना", "stock kitna", "how much stock", "बाकी कितना", "baki kitna",
                "मुनाफ़ा", "munafa", "profit"
            ),
            weight = 1.0
        ),
        IntentTrigger(
            // Ambiguous on their own; they can support a question but never establish one.
            AssistantIntent.READ_QUERY,
            listOf("क्या", "kya", "what", "कौन", "kaun", "who", "कब", "kab", "when", "फायदा", "fayda"),
            weight = 0.35
        ),

        // ---------- PAYMENT_RECEIVED ----------
        // Opposite sign to CREDIT_SALE on the same customer ledger. Confusing the two doubles
        // the error (a payment booked as new debt), so these carry full weight.
        IntentTrigger(
            AssistantIntent.PAYMENT_RECEIVED,
            listOf(
                "दे दिए", "de diye", "diye", "दिये", "paise diye", "पैसे दिए",
                "चुका दिया", "chuka diya", "chukaya", "चुकाया",
                "जमा", "jama", "jama kiye", "paid", "payment", "received",
                "बाकी चुकाया", "baki chukaya", "settle", "hisaab kar diya",
                "wapas kar diye", "लौटा दिए"
            ),
            weight = 1.0
        ),

        // ---------- RETURN ----------
        IntentTrigger(
            AssistantIntent.RETURN,
            listOf(
                "वापस", "wapas", "wapis", "return", "returned",
                "लौटा", "lauta", "lautaya", "लौटाया",
                "वापस कर दिया", "wapas kar diya", "return kar diya", "wapas le liya",
                "वापस लिया", "wapas liya", "give back", "took back"
            ),
            weight = 1.0
        ),

        // ---------- PRICE_UPDATE ----------
        IntentTrigger(
            AssistantIntent.PRICE_UPDATE,
            listOf(
                "रेट", "rate", "भाव", "bhav", "bhaav", "price", "दाम", "daam",
                "रेट बदलो", "rate badlo", "rate change", "price change", "new rate",
                "रेट कर दो", "rate kar do", "bhav kar do"
            ),
            weight = 1.0
        ),
        IntentTrigger(
            // "kar do" is shared with sales and udhaar phrasing, so it only nudges.
            AssistantIntent.PRICE_UPDATE,
            listOf("कर दो", "kar do", "kardo", "set karo", "बदल दो", "badal do"),
            weight = 0.6
        ),

        // ---------- WASTE ----------
        IntentTrigger(
            AssistantIntent.WASTE,
            listOf(
                "खराब", "kharab", "सड़ गया", "sad gaya", "sadd gaya", "गल गया", "gal gaya",
                "फेंका", "phenka", "phek diya", "फेंक दिया", "टूटा", "toota", "tuta",
                "वेस्ट", "waste", "spoiled", "damaged", "बर्बाद", "barbaad", "rotten"
            ),
            weight = 1.0
        ),

        // ---------- EXPIRY_WRITEOFF ----------
        IntentTrigger(
            AssistantIntent.EXPIRY_WRITEOFF,
            listOf(
                "एक्सपायर", "expire", "expired", "expiry",
                "तारीख निकल गई", "tareekh nikal gayi", "date nikal gayi", "date khatam",
                "out of date", "एक्सपायरी"
            ),
            weight = 1.0
        ),

        // ---------- STOCK_IN ----------
        IntentTrigger(
            AssistantIntent.STOCK_IN,
            listOf(
                "आया", "aaya", "आयी", "aayi", "आ गया", "aa gaya", "aagaya",
                "खरीदा", "kharida", "खरीद", "kharid", "लाया", "laya", "laaya",
                "माल आया", "maal aaya", "mal aaya", "stock aaya", "स्टॉक आया",
                "स्टॉक में", "stock mein", "purchase", "bought", "received stock",
                "भरा", "bhara", "मंगवाया", "mangwaya", "aa gaya hai"
            ),
            weight = 1.0
        ),

        // ---------- CREDIT_SALE ----------
        IntentTrigger(
            AssistantIntent.CREDIT_SALE,
            listOf(
                "उधार", "udhaar", "udhar", "udhaari",
                "खाते में", "khate mein", "khaate mein", "खाता", "khata", "khaata",
                "बही", "bahi", "credit", "on credit", "बाकी", "baki", "baaki",
                "लिख दो", "likh do", "likhdo", "लिख देना", "likh dena", "note kar do"
            ),
            weight = 1.0
        ),

        // ---------- VOID_LAST ----------
        IntentTrigger(
            AssistantIntent.VOID_LAST,
            listOf(
                "गलत", "galat", "ग़लत", "wrong", "mistake",
                "कैंसिल", "cancel", "cancel karo", "हटाओ", "hatao", "hata do",
                "मिटा दो", "mita do", "delete", "remove", "undo", "रद्द", "radd"
            ),
            weight = 1.0
        ),

        // ---------- ACTION_COMMAND ----------
        // Previously declared as ACTION_WORDS and never read -- no branch could return
        // ACTION_COMMAND, and SttWorker answered "यह काम अभी नहीं कर सकता". That dead end, plus a
        // zero-call-site ActionExecutor, was the whole "it doesn't send anything anywhere" bug.
        IntentTrigger(
            AssistantIntent.ACTION_COMMAND,
            listOf(
                "भेजो", "bhejo", "bhej do", "भेज दो", "send", "send karo",
                "व्हाट्सएप", "whatsapp", "whats app", "वाट्सएप",
                "बिल भेजो", "bill bhejo", "send bill", "बिल भेज दो",
                "कॉल", "call", "call karo", "फ़ोन", "phone karo", "डायल", "dial",
                "मैसेज", "message", "msg", "sms",
                "याद दिलाओ", "yaad dilao", "remind", "reminder", "रिमाइंडर"
            ),
            weight = 1.0
        )
    )

    /** Triggers grouped by intent, for scoring. */
    val BY_INTENT: Map<AssistantIntent, List<IntentTrigger>> = TRIGGERS.groupBy { it.intent }

    /**
     * Phone keys that genuinely establish "this is a question".
     *
     * Only these license the READ_QUERY boost. The weak question words are excluded because they
     * collide with ordinary shop speech ("kya"~"gaya", "fayda"~"paid"), and a false question is
     * worse than a missed one in the other direction: it silently turns a ledger write into a
     * read, so the sale is never booked.
     */
    val STRONG_QUESTION_KEYS: List<String> = TRIGGERS
        .filter { it.intent == AssistantIntent.READ_QUERY && it.weight >= 1.0 }
        .flatMap { it.phraseKeys }
        .distinct()
}
