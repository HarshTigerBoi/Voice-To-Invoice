package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.voicetoinvoice.app.domain.resolver.Resolvable
import java.util.UUID

@Entity(
    tableName = "customers",
    indices = [Index("phoneticKey"), Index("code", unique = true), Index("phone")]
)
data class CustomerRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopId: String = com.voicetoinvoice.app.data.ShopContext.currentOrLegacy(),
    val code: Int,                     // 1,2,3… NEVER reused after delete/merge
    val name: String,
    val keyword: String? = null,       // "दोसे वाले", "बिजली वाला", "डॉक्टर"
    val phone: String? = null,
    val photoPath: String? = null,
    val phoneticKey: String,           // PhoneticKey.of(name)
    val keywordPhoneticKey: String? = null,
    override val lastSeenMs: Long = 0L,         // recency prior
    val txnCount: Int = 0,             // frequency prior
    val mergedIntoId: String? = null,  // tombstone; excluded from all active queries
    val createdAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
) : Resolvable {
    // Computed, getter-only (no backing field) -- Room's entity processor only maps
    // primary-constructor properties to columns, so these need no @Ignore (Kotlin also
    // rejects @Ignore on a property with no backing field/delegate).
    override val resolverId: String get() = id
    override val primaryPhoneticKey: String get() = phoneticKey
    override val altPhoneticKeys: List<String> get() = listOfNotNull(keywordPhoneticKey)
    override val exactTokens: List<String> get() = listOfNotNull(code.toString(), phone)
    override val useCount: Int get() = txnCount
}
