package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One item's activity on one calendar day, incrementally maintained.
 *
 * Reports read this instead of scanning raw `transactions`/`stock_ledger` rows, so a
 * month-to-date report is a handful of rows per item (≤31) rather than every sale the shop has
 * ever made. [dayStartMs] is the device-local midnight for that day (matching how the rest of
 * the app already computes "today", e.g. `MainActivity`'s `todayMidnight`) rather than a UTC day
 * index, so a shop's "today" lines up with the shopkeeper's actual calendar day.
 *
 * Written incrementally by `DailyRollupRepository` from the same commit paths that write
 * `stock_ledger`/`transactions`. Because an incremental counter can drift if a process dies
 * mid-write, `DailyRollupRepository.repairRange()` can always recompute any window from the
 * source tables -- the rollup is a cache of ground truth, never the truth itself.
 */
@Entity(
    tableName = "daily_rollups",
    primaryKeys = ["shopId", "dayStartMs", "itemId"],
    indices = [Index("dayStartMs"), Index("itemId"), Index("shopId")]
)
data class DailyRollup(
    val shopId: String,
    val dayStartMs: Long,
    val itemId: String,
    val itemName: String,
    val qtySold: Double = 0.0,
    val revenue: Double = 0.0,
    val cogs: Double = 0.0,
    /** Revenue restricted to lines with a known cost -- the correct margin denominator. */
    val costKnownRevenue: Double = 0.0,
    val txnCount: Int = 0,
    val wasteQty: Double = 0.0,
    val wasteValue: Double = 0.0,
    val returnQty: Double = 0.0,
    val returnValue: Double = 0.0,
    val stockInQty: Double = 0.0,
    val stockInValue: Double = 0.0
)
