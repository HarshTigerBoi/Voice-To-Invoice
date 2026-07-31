package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * What this shop, specifically, has taught the app -- the per-shop learning moat described in
 * Docs/master_build_plan.md §4.4. Voice billing itself is commoditised; what compounds over time
 * and is hard to copy is the app getting better at THIS shop's vocabulary, prices and customers.
 *
 * ## Scope of this pass
 *
 * The full design also covers unit meanings ("peti" = 20kg at this shop), default prices,
 * whole-phrase-to-intent memoization, and customer aliases, feeding into the segmenter
 * vocabulary and the Grok prompt. This pass wires the highest-value slice end to end: [ITEM_ALIAS]
 * written on every review-queue confirmation (the single highest-value training signal in the
 * system, previously thrown away entirely) and read back during item resolution, plus a decay on
 * void (the one piece of negative feedback that can contradict a wrong-but-confirmed alias). The
 * other `LearningKind` values are declared so the schema does not need to change again when they
 * are wired in, but nothing writes them yet.
 */
@Entity(
    tableName = "shop_learning",
    primaryKeys = ["shopId", "kind", "key"],
    indices = [Index("shopId"), Index(value = ["kind", "key"])]
)
data class ShopLearning(
    val shopId: String,
    val kind: LearningKind,
    /** Phonetic key of the raw token/phrase this entry was learned from. */
    val key: String,
    /** What [key] resolves to -- an item id, a numeric unit multiplier, a customer id, etc.,
     *  depending on [kind]. */
    val value: String,
    val hitCount: Int = 1,
    val lastUsedAtMs: Long = System.currentTimeMillis(),
    /** 0..1. Rises on confirmation, falls on a contradicting void. Entries at or below 0 are
     *  treated as not learned rather than actively wrong -- see `ShopLearningRepository`. */
    val confidence: Double = 0.6,
    val synced: Boolean = false
)

enum class LearningKind {
    /** This shop's word for an item: "tamatar" -> item id, a shop-specific abbreviation, etc. */
    ITEM_ALIAS,
    /** "peti" = 20 KG at THIS shop, 25 KG at another. Not yet written. */
    UNIT_MEANING,
    /** Habitual selling price for unpriced utterances. Not yet written. */
    DEFAULT_PRICE,
    /** This shopkeeper's phrasing -> intent, feeding the AI-arbitration cache. Not yet written. */
    PHRASE_INTENT,
    /** "doctor sahab" -> customer id. Not yet written. */
    CUSTOMER_ALIAS
}
