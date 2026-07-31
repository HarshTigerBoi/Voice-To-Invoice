package com.voicetoinvoice.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voicetoinvoice.app.data.local.entity.AlertDismissal

@Dao
interface AlertDismissalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dismissal: AlertDismissal)

    /** Only still-active suppressions -- an expired snooze is simply absent from the result. */
    @Query("SELECT * FROM alert_dismissals WHERE shopId = :shopId AND snoozedUntilMs > :nowMs")
    suspend fun getActive(shopId: String, nowMs: Long): List<AlertDismissal>

    /** Housekeeping so expired snoozes don't accumulate forever. Safe to call on every launch. */
    @Query("DELETE FROM alert_dismissals WHERE snoozedUntilMs <= :nowMs")
    suspend fun purgeExpired(nowMs: Long)

    @Query("DELETE FROM alert_dismissals WHERE shopId = :shopId AND type = :type AND itemId = :itemId")
    suspend fun clear(shopId: String, type: String, itemId: String)
}
