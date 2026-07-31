package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Money a customer paid against their Udhaar balance.
 *
 * Before this existed there was no way to record a repayment at all: credit only ever went up.
 * `LedgerQueries.getCustomerBalance` read `CustomerDao.getOutstandingFor` and then *fell back* to
 * the legacy `credits` table, so two different screens could show two different numbers for the
 * same customer -- and a shopkeeper who cannot trust the Udhaar figure will not trust the app.
 *
 * The one balance definition now lives in `CustomerBalance`:
 *   balance = Σ(credit sales) − Σ(returns against credit) − Σ(CustomerPayment)
 */
@Entity(
    tableName = "customer_payments",
    indices = [Index("customerId"), Index("receivedAtMs"), Index("shopId")]
)
data class CustomerPayment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopId: String,
    val customerId: String,
    val customerName: String,
    val amount: Double,
    val mode: PaymentMode = PaymentMode.CASH,
    val note: String? = null,
    /** The voice job this payment came from, when it was captured by voice. */
    val jobId: String? = null,
    val receivedAtMs: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
