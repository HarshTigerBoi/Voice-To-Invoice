package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "stock_in")
data class StockInRecord(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = "default_shop",
    val itemId: String,
    val itemName: String,
    val quantity: Double,
    val costPrice: Double,
    val supplier: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
