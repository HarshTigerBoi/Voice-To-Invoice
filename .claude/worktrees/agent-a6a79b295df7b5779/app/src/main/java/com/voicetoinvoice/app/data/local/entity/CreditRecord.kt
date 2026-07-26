package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class CreditStatus {
    PENDING, PAID, PARTIAL
}

@Entity(tableName = "credits")
data class CreditRecord(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = "default_shop",
    val customerName: String,
    val amount: Double,
    val dueDate: Long? = null,
    val status: CreditStatus = CreditStatus.PENDING,
    val linkedTransactionId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
