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
    val audioCloudUrl: String? = null,
    // Position of this line within its voice recording -- one job/recording can now
    // produce multiple transaction rows (multi-item capture). See ISSUE-029.
    val lineNo: Int = 0,
    // Correction signal for the server-side Learned Parse Memory (ISSUE-031): voiding a
    // wrongly-booked sale is the only ground truth that can contradict a self-consistent
    // -but-wrong memoized parse. Soft delete only -- the ledger stays append-only.
    val voided: Boolean = false,
    val voidedAtMs: Long? = null
)
