package com.voicetoinvoice.app.action

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.domain.action.BillBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BillBuilderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun sampleLine(name: String, qty: Double, price: Double) = TransactionRecord(
        itemId = "item-$name",
        itemName = name,
        quantity = qty,
        priceAtSale = price,
        total = qty * price
    )

    @Test
    fun rendersNonNullUriForValidLines() {
        val lines = listOf(
            sampleLine("आलू", 2.0, 30.0),
            sampleLine("प्याज", 1.0, 40.0),
            sampleLine("टमाटर", 1.5, 50.0)
        )

        val uri = BillBuilder.render(context, "Test Shop", "Ramesh", lines)
        assertNotNull(uri)

        val billsDir = File(context.cacheDir, "bills")
        val files = billsDir.listFiles() ?: emptyArray()
        assertTrue(files.isNotEmpty())
        assertTrue(files.any { it.length() > 0 })
    }

    @Test
    fun emptyLinesReturnsNull() {
        val uri = BillBuilder.render(context, "Test Shop", "Ramesh", emptyList())
        assertNull(uri)
    }

    @Test
    fun purgeOldRemovesStaleBillsOnly() {
        val billsDir = File(context.cacheDir, "bills").apply { mkdirs() }
        val now = System.currentTimeMillis()

        val oldFile = File(billsDir, "bill_old.png").apply {
            createNewFile()
            setLastModified(now - 48 * 60 * 60 * 1000L)
        }
        val newFile = File(billsDir, "bill_new.png").apply {
            createNewFile()
            setLastModified(now)
        }

        BillBuilder.purgeOld(context, 24 * 60 * 60 * 1000L)

        assertTrue(!oldFile.exists())
        assertTrue(newFile.exists())
    }

    @Test
    fun renderedFileIsUnderCacheDir() {
        val lines = listOf(sampleLine("आलू", 1.0, 30.0))
        val uri = BillBuilder.render(context, "Test Shop", null, lines)
        assertNotNull(uri)

        val billsDir = File(context.cacheDir, "bills")
        val files = billsDir.listFiles() ?: emptyArray()
        assertTrue(files.any { it.absolutePath.startsWith(context.cacheDir.absolutePath) })
    }
}
