package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "suppliers")
data class SupplierRecord(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = com.voicetoinvoice.app.data.ShopContext.currentOrLegacy(),
    val name: String,
    val phone: String? = null,
    val balanceOwed: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
