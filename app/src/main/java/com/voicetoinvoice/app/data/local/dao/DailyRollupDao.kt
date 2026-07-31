package com.voicetoinvoice.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.voicetoinvoice.app.data.local.entity.DailyRollup

/** One row per item across a date range -- the shape `getTopItems`/`getAllForRange` return. */
data class RollupItemTotal(
    val itemId: String,
    val itemName: String,
    val qtySold: Double,
    val revenue: Double,
    val cogs: Double,
    val costKnownRevenue: Double,
    val txnCount: Int,
    val wasteQty: Double,
    val wasteValue: Double,
    val returnQty: Double,
    val returnValue: Double,
    val stockInQty: Double,
    val stockInValue: Double
)

/** Whole-shop total across a date range -- what `ReportsScreen`'s headline numbers read. */
data class RollupTotals(
    val revenue: Double,
    val cogs: Double,
    val costKnownRevenue: Double,
    val txnCount: Int,
    val wasteValue: Double,
    val returnValue: Double,
    val stockInValue: Double
)

@Dao
interface DailyRollupDao {

    /**
     * The only write path. Every delta parameter defaults to 0 so a single call can increment
     * just the fields relevant to one event (a sale increments qtySold/revenue/cogs/txnCount and
     * leaves the rest untouched) without clobbering the day's other accumulated fields -- which
     * is why this is a raw upsert-with-addition rather than `@Insert(onConflict = REPLACE)`
     * (REPLACE overwrites the whole row, discarding whatever was already accumulated that day).
     */
    @Query(
        """
        INSERT INTO daily_rollups
            (shopId, dayStartMs, itemId, itemName, qtySold, revenue, cogs, costKnownRevenue,
             txnCount, wasteQty, wasteValue, returnQty, returnValue, stockInQty, stockInValue)
        VALUES
            (:shopId, :dayStartMs, :itemId, :itemName, :qtySold, :revenue, :cogs, :costKnownRevenue,
             :txnCount, :wasteQty, :wasteValue, :returnQty, :returnValue, :stockInQty, :stockInValue)
        ON CONFLICT(shopId, dayStartMs, itemId) DO UPDATE SET
            itemName = excluded.itemName,
            qtySold = qtySold + excluded.qtySold,
            revenue = revenue + excluded.revenue,
            cogs = cogs + excluded.cogs,
            costKnownRevenue = costKnownRevenue + excluded.costKnownRevenue,
            txnCount = txnCount + excluded.txnCount,
            wasteQty = wasteQty + excluded.wasteQty,
            wasteValue = wasteValue + excluded.wasteValue,
            returnQty = returnQty + excluded.returnQty,
            returnValue = returnValue + excluded.returnValue,
            stockInQty = stockInQty + excluded.stockInQty,
            stockInValue = stockInValue + excluded.stockInValue
        """
    )
    suspend fun increment(
        shopId: String,
        dayStartMs: Long,
        itemId: String,
        itemName: String,
        qtySold: Double = 0.0,
        revenue: Double = 0.0,
        cogs: Double = 0.0,
        costKnownRevenue: Double = 0.0,
        txnCount: Int = 0,
        wasteQty: Double = 0.0,
        wasteValue: Double = 0.0,
        returnQty: Double = 0.0,
        returnValue: Double = 0.0,
        stockInQty: Double = 0.0,
        stockInValue: Double = 0.0
    )

    @Query(
        """
        SELECT
            COALESCE(SUM(revenue), 0.0) AS revenue,
            COALESCE(SUM(cogs), 0.0) AS cogs,
            COALESCE(SUM(costKnownRevenue), 0.0) AS costKnownRevenue,
            COALESCE(SUM(txnCount), 0) AS txnCount,
            COALESCE(SUM(wasteValue), 0.0) AS wasteValue,
            COALESCE(SUM(returnValue), 0.0) AS returnValue,
            COALESCE(SUM(stockInValue), 0.0) AS stockInValue
        FROM daily_rollups
        WHERE dayStartMs >= :startMs AND dayStartMs < :endMs
        """
    )
    suspend fun getTotals(startMs: Long, endMs: Long): RollupTotals

    @Query(
        """
        SELECT itemId, itemName,
            SUM(qtySold) AS qtySold, SUM(revenue) AS revenue, SUM(cogs) AS cogs,
            SUM(costKnownRevenue) AS costKnownRevenue, SUM(txnCount) AS txnCount,
            SUM(wasteQty) AS wasteQty, SUM(wasteValue) AS wasteValue,
            SUM(returnQty) AS returnQty, SUM(returnValue) AS returnValue,
            SUM(stockInQty) AS stockInQty, SUM(stockInValue) AS stockInValue
        FROM daily_rollups
        WHERE dayStartMs >= :startMs AND dayStartMs < :endMs
        GROUP BY itemId
        ORDER BY revenue DESC
        """
    )
    suspend fun getItemTotalsByRevenue(startMs: Long, endMs: Long): List<RollupItemTotal>

    @Query(
        """
        SELECT itemId, itemName,
            SUM(qtySold) AS qtySold, SUM(revenue) AS revenue, SUM(cogs) AS cogs,
            SUM(costKnownRevenue) AS costKnownRevenue, SUM(txnCount) AS txnCount,
            SUM(wasteQty) AS wasteQty, SUM(wasteValue) AS wasteValue,
            SUM(returnQty) AS returnQty, SUM(returnValue) AS returnValue,
            SUM(stockInQty) AS stockInQty, SUM(stockInValue) AS stockInValue
        FROM daily_rollups
        WHERE dayStartMs >= :startMs AND dayStartMs < :endMs
        GROUP BY itemId
        ORDER BY qtySold DESC
        """
    )
    suspend fun getItemTotalsByQuantity(startMs: Long, endMs: Long): List<RollupItemTotal>

    /** Per-day revenue for a range -- feeds the reports screen's trend sparkline and the
     *  shop-wide day-of-week seasonality factor `DemandForecast` computes. */
    @Query(
        """
        SELECT dayStartMs, SUM(revenue) AS revenue
        FROM daily_rollups WHERE dayStartMs >= :startMs AND dayStartMs < :endMs
        GROUP BY dayStartMs ORDER BY dayStartMs ASC
        """
    )
    suspend fun getDailyRevenueSeries(startMs: Long, endMs: Long): List<DayRevenue>

    /** One item's daily quantity series -- the baseline `DemandForecast` runs its EWMA over. */
    @Query(
        """
        SELECT dayStartMs, SUM(qtySold) AS qty
        FROM daily_rollups WHERE itemId = :itemId AND dayStartMs >= :startMs AND dayStartMs < :endMs
        GROUP BY dayStartMs ORDER BY dayStartMs ASC
        """
    )
    suspend fun getItemDailyQtySeries(itemId: String, startMs: Long, endMs: Long): List<DayQty>

    /** Earliest day an item has any rollup activity -- bounds how much history is genuinely
     *  available, so a forecast can honestly report LOW confidence for a new item/shop. */
    @Query("SELECT MIN(dayStartMs) FROM daily_rollups WHERE itemId = :itemId")
    suspend fun getEarliestDayForItem(itemId: String): Long?

    @Query("DELETE FROM daily_rollups WHERE dayStartMs >= :startMs AND dayStartMs < :endMs")
    suspend fun deleteRange(startMs: Long, endMs: Long)
}

data class DayRevenue(val dayStartMs: Long, val revenue: Double)
data class DayQty(val dayStartMs: Long, val qty: Double)
