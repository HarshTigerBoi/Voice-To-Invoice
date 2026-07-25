package com.voicetoinvoice.app.data.local

import androidx.room.TypeConverter
import com.voicetoinvoice.app.data.local.entity.*

class Converters {

    @TypeConverter
    fun fromPaymentMode(value: PaymentMode): String = value.name

    @TypeConverter
    fun toPaymentMode(value: String): PaymentMode = PaymentMode.valueOf(value)

    @TypeConverter
    fun fromTransactionSource(value: TransactionSource): String = value.name

    @TypeConverter
    fun toTransactionSource(value: String): TransactionSource = TransactionSource.valueOf(value)

    @TypeConverter
    fun fromCreditStatus(value: CreditStatus): String = value.name

    @TypeConverter
    fun toCreditStatus(value: String): CreditStatus = CreditStatus.valueOf(value)

    @TypeConverter
    fun fromUnmatchedStatus(value: UnmatchedStatus): String = value.name

    @TypeConverter
    fun toUnmatchedStatus(value: String): UnmatchedStatus = UnmatchedStatus.valueOf(value)

    @TypeConverter
    fun fromSyncAction(value: SyncAction): String = value.name

    @TypeConverter
    fun toSyncAction(value: String): SyncAction = SyncAction.valueOf(value)
}
