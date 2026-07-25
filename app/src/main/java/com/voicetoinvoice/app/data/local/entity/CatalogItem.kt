package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "catalog_items")
data class CatalogItem(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = "default_shop",
    val name: String, // e.g. "Tamatar", "Aaloo", "Pyaz", "Bhindi"
    val unitId: String = "KG", // Default stored unit for this catalog item
    val price: Double, // Price per default unit
    val active: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
