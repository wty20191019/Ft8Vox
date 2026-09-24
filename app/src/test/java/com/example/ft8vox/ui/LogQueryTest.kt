package com.example.ft8vox.ui

import com.example.ft8vox.data.log.QsoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 日志筛选与统计单测。 */
class LogQueryTest {

    private val base = 1_700_000_000_000L

    private fun entity(
        call: String,
        grid: String?,
        band: String = "20m",
        mode: String = "FT8",
        days: Long = 0,
        qsl: String? = null,
    ) = QsoEntity(
        theirCall = call,
        theirGrid = grid,
        utcMs = base + days * 86_400_000L,
        band = band,
        mode = mode,
        qslRcvd = qsl,
    )

    @Test
    fun filtersByQueryOnCallAndGrid() {
        val list = listOf(entity("JA1ABC", "PM95"), entity("W1AW", "FN42"))
        assertEquals(
            listOf("JA1ABC"),
            LogQuery.applyFilter(list, LogFilter(query = "ja1")).map { it.theirCall },
        )
        assertEquals(
            listOf("W1AW"),
            LogQuery.applyFilter(list, LogFilter(query = "FN")).map { it.theirCall },
        )
        assertEquals(2, LogQuery.applyFilter(list, LogFilter()).size)
    }

    @Test
    fun filtersByBandAndMode() {
        val list = listOf(
            entity("A", "AA00", band = "20m", mode = "FT8"),
            entity("B", "BB00", band = "40m", mode = "FT4"),
        )
        assertEquals(listOf("A"), LogQuery.applyFilter(list, LogFilter(band = "20m")).map { it.theirCall })
        assertEquals(listOf("B"), LogQuery.applyFilter(list, LogFilter(mode = "FT4")).map { it.theirCall })
    }

    @Test
    fun filtersByDateRange() {
        val list = listOf(entity("A", "AA00", days = 0), entity("B", "BB00", days = 5))
        assertEquals(
            listOf("B"),
            LogQuery.applyFilter(list, LogFilter(fromMs = base + 86_400_000L)).map { it.theirCall },
        )
        assertEquals(
            listOf("A"),
            LogQuery.applyFilter(list, LogFilter(toMs = base + 86_400_000L)).map { it.theirCall },
        )
    }

    @Test
    fun computesStats() {
        val list = listOf(
            entity("JA1ABC", "PM95", qsl = "Y"),
            entity("JA1ABC", "PM95"),
            entity("W1AW", "FN42", mode = "FT4"),
            entity("W1AW", null, band = "40m"),
        )
        val stats = LogQuery.computeStats(list)
        assertEquals(4, stats.total)
        assertEquals(2, stats.uniqueCalls)
        assertEquals(2, stats.uniqueGrids)
        assertEquals(1, stats.confirmed)
        // 波段按频率从低到高排序：40m 在 20m 之前
        assertEquals(listOf("40m" to 1, "20m" to 3), stats.byBand)
        assertEquals(3, stats.byMode.first { it.first == "FT8" }.second)
    }

    @Test
    fun emptyStats() {
        val stats = LogQuery.computeStats(emptyList())
        assertEquals(0, stats.total)
        assertEquals(0, stats.confirmed)
        assertTrue(stats.byBand.isEmpty())
        assertTrue(stats.byMode.isEmpty())
    }
}
