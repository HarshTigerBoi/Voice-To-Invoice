package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records a migration step that failed.
 *
 * Every `ALTER TABLE` in this project's migrations is wrapped in try/catch (the established
 * pattern -- it makes re-running a partially-applied migration safe). The cost is that a
 * genuine failure is invisible. For the stock-ledger backfill that is dangerous: a partial
 * backfill yields on-hand quantities that look plausible and are wrong.
 *
 * So failures land here instead of only `printStackTrace()`. A non-empty table means the shop's
 * stock numbers should be regenerated with `StockLedgerRepository.rebuildFromLedger()`.
 */
@Entity(tableName = "migration_status")
data class MigrationStatus(
    @PrimaryKey val id: String,
    val migration: String,
    val step: String,
    val error: String,
    val atMs: Long
)
