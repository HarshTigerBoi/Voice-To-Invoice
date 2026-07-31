package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class CreditStatus {
    PENDING, PAID, PARTIAL
}

@Entity(
    tableName = "credits",
    indices = [Index("customerId"), Index("status"), Index("synced"), Index("shopId")]
)
data class CreditRecord(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = com.voicetoinvoice.app.data.ShopContext.currentOrLegacy(),
    val customerName: String,
    val customerId: String? = null,
    val amount: Double,
    val dueDate: Long? = null,
    val status: CreditStatus = CreditStatus.PENDING,
    val linkedTransactionId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
