package com.voicetoinvoice.app.data.repository

import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.LearningKind
import com.voicetoinvoice.app.domain.parser.PhoneticKey

/**
 * Read/write surface for `shop_learning` -- see the entity doc for scope. Matching happens in
 * [PhoneticKey] space, the same mechanism every other cross-script lookup in this app uses
 * (`IntentRouter`, `QuestionTemplates`, `EntityResolver`), so a shop that taught the app "TMTR"
 * means Tamatar keyed in Devanagari still resolves it when a later recording renders it in Latin.
 */
class ShopLearningRepository(private val db: AppDatabase) {

    private val dao = db.shopLearningDao()

    /** A confirmed catalog match teaches the app that this shop's spoken word for the item is
     *  [rawToken]. Called from the review-queue confirm path -- the single highest-value
     *  training signal in the system, previously discarded entirely. */
    suspend fun recordItemAliasConfirmed(rawToken: String, itemId: String) {
        val key = PhoneticKey.of(rawToken)
        if (key.isBlank()) return
        dao.reinforce(ShopContext.requireShopId(), LearningKind.ITEM_ALIAS.name, key, itemId)
    }

    /** A void is the one signal that can CONTRADICT an already-confirmed alias -- the shopkeeper
     *  is telling the app the booked item was wrong. Every alias entry pointing at this item gets
     *  less trusted, not deleted outright, since the same item may also have other, correct,
     *  aliases. */
    suspend fun decayItemAlias(itemId: String) {
        dao.decayByValue(ShopContext.requireShopId(), LearningKind.ITEM_ALIAS.name, itemId)
    }

    /** Resolves a spoken token to a previously-confirmed item id, or null when nothing this shop
     *  has taught the app matches closely enough, or matches but is no longer trusted
     *  (decayed at/near 0 by repeated voids). */
    suspend fun resolveItemAlias(rawToken: String, minConfidence: Double = 0.3): String? {
        val key = PhoneticKey.of(rawToken)
        if (key.isBlank()) return null
        val candidates = dao.getAllOfKind(ShopContext.requireShopId(), LearningKind.ITEM_ALIAS.name)
        if (candidates.isEmpty()) return null

        return candidates
            .map { it to PhoneticKey.normalizedDistance(key, it.key) }
            .filter { (entry, distance) -> distance <= 0.2 && entry.confidence >= minConfidence }
            .minByOrNull { it.second }
            ?.first?.value
    }
}
