package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "item_units")
data class ItemUnit(
    @PrimaryKey
    val id: String, // e.g. "kg", "gram", "pao", "piece", "strip", "dozen"
    val colloquialTerm: String, // e.g. "पाव", "सवा", "आधा", "ग्राम"
    val multiplier: Double, // e.g. 0.25 for pao, 1.25 for sawa, 0.5 for adhao, 1.0 for kg
    val baseUnit: String // e.g. "KG", "GRAM", "PIECE"
)
