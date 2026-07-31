package com.voicetoinvoice.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.voicetoinvoice.app.data.local.entity.ShopLearning

@Dao
interface ShopLearningDao {

    /** Confirming the same alias again reinforces it (hitCount+1, confidence nudged up, capped
     *  at 1.0) rather than overwriting -- repeated confirmation is exactly the signal that should
     *  make an entry MORE trusted over time. */
    @Query(
        """
        INSERT INTO shop_learning (shopId, kind, key, value, hitCount, lastUsedAtMs, confidence, synced)
        VALUES (:shopId, :kind, :key, :value, 1, :nowMs, :initialConfidence, 0)
        ON CONFLICT(shopId, kind, key) DO UPDATE SET
            value = excluded.value,
            hitCount = hitCount + 1,
            lastUsedAtMs = excluded.lastUsedAtMs,
            confidence = MIN(1.0, confidence + 0.15),
            synced = 0
        """
    )
    suspend fun reinforce(
        shopId: String,
        kind: String,
        key: String,
        value: String,
        nowMs: Long = System.currentTimeMillis(),
        initialConfidence: Double = 0.6
    )

    @Query("SELECT * FROM shop_learning WHERE shopId = :shopId AND kind = :kind AND confidence > 0")
    suspend fun getAllOfKind(shopId: String, kind: String): List<ShopLearning>

    /** Lowers confidence on a contradicting signal (a void). Never below 0 -- a decayed-to-0
     *  entry is simply not trusted anymore, not treated as evidence of the opposite. */
    @Query(
        """
        UPDATE shop_learning SET confidence = MAX(0.0, confidence - :amount), synced = 0
        WHERE shopId = :shopId AND kind = :kind AND value = :value
        """
    )
    suspend fun decayByValue(shopId: String, kind: String, value: String, amount: Double = 0.3)

    @Query("SELECT * FROM shop_learning WHERE synced = 0 LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 200): List<ShopLearning>

    @Query("UPDATE shop_learning SET synced = 1 WHERE shopId = :shopId AND kind = :kind AND key = :key")
    suspend fun markSynced(shopId: String, kind: String, key: String)
}
