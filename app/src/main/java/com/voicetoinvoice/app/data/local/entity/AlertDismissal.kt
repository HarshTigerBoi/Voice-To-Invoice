package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Suppression record for one alert identity. Alerts themselves stay derived (see AlertEngine) --
 * this table only remembers "the shopkeeper has already seen and actioned this one."
 *
 * Keyed on (shopId, type, itemId) rather than a generated id because an alert has no stable id of
 * its own: it is recomputed from scratch each time, so the identity IS the type+item pair. Shop-wide
 * alerts (UDHAAR_OVERDUE, MISSING_COST) carry no item and use the empty string, not null, so the
 * primary key stays non-null.
 */
@Entity(
    tableName = "alert_dismissals",
    primaryKeys = ["shopId", "type", "itemId"],
    indices = [Index("shopId"), Index("snoozedUntilMs")]
)
data class AlertDismissal(
    val shopId: String,
    val type: String,
    /** Empty string for shop-wide alerts that carry no item. */
    val itemId: String,
    /** Absolute ms. `Long.MAX_VALUE` means dismissed permanently, not snoozed. */
    val snoozedUntilMs: Long,
    val dismissedAtMs: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
