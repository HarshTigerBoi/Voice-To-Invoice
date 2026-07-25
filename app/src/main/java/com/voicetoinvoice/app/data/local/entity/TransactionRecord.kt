package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class PaymentMode {
    CASH, UPI, CREDIT
}

enum class TransactionSource {
    VOICE, MANUAL, UPI_NOTIFICATION
}

@Entity(tableName = "transactions")
data class TransactionRecord(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = "default_shop",
    val itemId: String,
    val itemName: String,
    val quantity: Double, // Double quantity
    val priceAtSale: Double,
    val total: Double,
    val paymentMode: PaymentMode = PaymentMode.CASH,
    val timestamp: Long = System.currentTimeMillis(),
    val source: TransactionSource = TransactionSource.VOICE,
    val deviceId: String = "device_1",
    val synced: Boolean = false,
    val rawTranscript: String = "",
    val audioFilePath: String = "",
    val jobId: String? = null,
    val audioCloudUrl: String? = null
)
