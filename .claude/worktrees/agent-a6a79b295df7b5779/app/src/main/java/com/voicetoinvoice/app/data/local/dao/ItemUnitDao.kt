package com.voicetoinvoice.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voicetoinvoice.app.data.local.entity.ItemUnit
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemUnitDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(units: List<ItemUnit>)

    @Query("SELECT * FROM item_units ORDER BY id ASC")
    fun getAllUnits(): Flow<List<ItemUnit>>

    @Query("SELECT * FROM item_units WHERE id = :unitId LIMIT 1")
    suspend fun getById(unitId: String): ItemUnit?
}
