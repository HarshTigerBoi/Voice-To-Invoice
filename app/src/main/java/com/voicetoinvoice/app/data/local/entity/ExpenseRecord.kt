package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class ExpenseCategory { RENT, ELECTRICITY, SALARY, TRANSPORT, SUPPLIES, TEA, OTHER }

/**
 * A shop expense. Deliberately separate from `transactions`: an expense is not a sale with a
 * negative sign — it has no item, no quantity, no customer, and must never reach any
 * item-level or revenue aggregate.
 *
 * Soft-delete only (`voided`), matching the append-only rule the ledger already follows.
 */
@Entity(
    tableName = "expenses",
    indices = [Index("timestamp"), Index("category"), Index("synced"), Index("shopId")]
)
data class ExpenseRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopId: String = com.voicetoinvoice.app.data.ShopContext.currentOrLegacy(),
    val category: ExpenseCategory,
    val amount: Double,
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val source: TransactionSource = TransactionSource.MANUAL,
    val voided: Boolean = false,
    val synced: Boolean = false
)
