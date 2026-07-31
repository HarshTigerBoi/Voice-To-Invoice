package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class PaymentMode {
    CASH, UPI, CREDIT
}

enum class TransactionSource {
    VOICE, MANUAL, UPI_NOTIFICATION
}

@Entity(
    tableName = "transactions",
    // Every read path filters on these, and the table had NO indices at all -- so today's
    // sales, an item's history, a customer's balance and the bill lookup were each a full
    // table scan. `voided` leads the composite because every query filters it first.
    indices = [
        Index(value = ["voided", "timestamp"]),
        Index("timestamp"),
        Index("itemId"),
        Index("customerId"),
        Index("billId"),
        Index("jobId"),
        Index("paymentMode"),
        Index("synced"),
        Index("shopId")
    ]
)
data class TransactionRecord(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = com.voicetoinvoice.app.data.ShopContext.currentOrLegacy(),
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
    val voidedAtMs: Long? = null,
    val customerId: String? = null,

    /**
     * Weighted-average cost snapshotted at commit time. NULL means the cost was genuinely
     * unknown at the time of sale (a `costMissing` stock-in, or an item never purchased
     * through the app), and `ProfitCalculator` deliberately excludes those lines from
     * margin rather than treating the cost as zero.
     *
     * Snapshotting matters: applying today's cost to last month's sales produces nonsense
     * for vegetables, where cost can swing 40% week to week.
     */
    val costAtSale: Double? = null,

    /** RETURN rows carry negative [quantity] and [total] and reference [returnOfTxnId]. */
    val txnType: TxnType = TxnType.SALE,

    /** Groups the lines of one recording (or one manual entry) into a single bill. */
    val billId: String? = null,

    /** For [TxnType.RETURN]: the id of the sale being returned, when it could be resolved. */
    val returnOfTxnId: String? = null
)

enum class TxnType { SALE, RETURN }
