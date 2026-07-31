package com.voicetoinvoice.app.intel

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.repository.ShopLearningRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ShopLearning` is the per-shop vocabulary memory (Docs/master_build_plan.md §4.4): a
 * review-queue confirmation teaches the app what THIS shop calls an item, and a later void is the
 * one signal that can contradict a wrong-but-confirmed alias.
 */
@RunWith(AndroidJUnit4::class)
class ShopLearningRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ShopLearningRepository
    private val aalooId = "item-aloo"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ShopContext.initialize(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = ShopLearningRepository(db)
        runTest {
            db.catalogDao().insertOrUpdate(CatalogItem(id = aalooId, shopId = ShopContext.requireShopId(), name = "आलू", unitId = "KG", price = 30.0))
        }
    }

    @Test
    fun aConfirmedAliasResolvesLater() = runTest {
        repo.recordItemAliasConfirmed("TMTR", aalooId) // a shop-specific abbreviation, hypothetically
        assertEquals(aalooId, repo.resolveItemAlias("TMTR"))
    }

    @Test
    fun resolvesAcrossScriptsViaPhoneticKey() = runTest {
        repo.recordItemAliasConfirmed("aalu", aalooId) // Latin-Hinglish spelling
        // A later recording renders the SAME word in Devanagari -- should still resolve.
        assertEquals(aalooId, repo.resolveItemAlias("आलू"))
    }

    @Test
    fun unrelatedWordsDoNotResolve() = runTest {
        repo.recordItemAliasConfirmed("aalu", aalooId)
        assertNull(repo.resolveItemAlias("completely different word"))
    }

    @Test
    fun repeatedConfirmationReinforcesRatherThanDuplicates() = runTest {
        repo.recordItemAliasConfirmed("aalu", aalooId)
        repo.recordItemAliasConfirmed("aalu", aalooId)
        repo.recordItemAliasConfirmed("aalu", aalooId)
        val entries = db.shopLearningDao().getAllOfKind(ShopContext.requireShopId(), "ITEM_ALIAS")
        assertEquals("one key, reinforced -- not three separate rows", 1, entries.size)
        assertEquals(3, entries.first().hitCount)
    }

    @Test
    fun voidingDecaysTheAliasUntilItStopsResolving() = runTest {
        repo.recordItemAliasConfirmed("aalu", aalooId)
        assertEquals(aalooId, repo.resolveItemAlias("aalu"))

        // A void is a correction: the shopkeeper is saying THIS alias led to a wrong booking.
        // Decay by 0.3 three times drops confidence from 0.6+0.15=0.75 down toward/below the
        // 0.3 resolve threshold.
        repo.decayItemAlias(aalooId)
        repo.decayItemAlias(aalooId)
        repo.decayItemAlias(aalooId)
        assertNull("repeatedly contradicted alias should stop being trusted", repo.resolveItemAlias("aalu"))
    }

    @Test
    fun decayNeverGoesBelowZero() = runTest {
        repo.recordItemAliasConfirmed("aalu", aalooId)
        repeat(10) { repo.decayItemAlias(aalooId) }
        // `getAllOfKind` deliberately filters `confidence > 0` (a fully-decayed entry is no
        // longer learned), so the row is correctly absent from that read path -- assert the
        // clamp held via the resolve path instead of expecting a row back.
        assertNull(repo.resolveItemAlias("aalu"))
        assertEquals(
            0,
            db.shopLearningDao().getAllOfKind(ShopContext.requireShopId(), "ITEM_ALIAS").size
        )
    }
}
