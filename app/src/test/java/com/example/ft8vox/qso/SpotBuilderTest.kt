package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 实时台站构造的 JVM 单测。 */
class SpotBuilderTest {

    private fun decoded(text: String, snr: Int = -10, df: Int = 1500, slotUtcMs: Long = 0L) = DecodeResult(
        text = text,
        snr = snr,
        dt = 0f,
        df = df,
        score = 20,
        slotUtcMs = slotUtcMs,
    )

    @Test
    fun buildsSpotFromCqWithGrid() {
        val spots = SpotBuilder.build(
            messages = listOf(decoded("CQ JA1ABC PM95", snr = -5, df = 1521, slotUtcMs = 1000)),
            myCall = "F4FSY",
        )
        assertEquals(1, spots.size)
        val s = spots[0]
        assertEquals("JA1ABC", s.call)
        assertEquals("PM95", s.grid)
        assertEquals(-5, s.snr)
        assertEquals(1521, s.df)
        assertTrue(s.lat in -90.0..90.0)
        assertTrue(s.lon in -180.0..180.0)
    }

    @Test
    fun fillsGridFromCacheWhenMessageHasNone() {
        val spots = SpotBuilder.build(
            messages = listOf(decoded("F4FSY JA1ABC -08", slotUtcMs = 1000)),
            myCall = "F4FSY",
            gridCache = mapOf("JA1ABC" to "PM95"),
        )
        assertEquals(1, spots.size)
        assertEquals("PM95", spots[0].grid)
    }

    @Test
    fun skipsWhenGridUnknown() {
        val spots = SpotBuilder.build(
            messages = listOf(decoded("F4FSY JA1ABC -08", slotUtcMs = 1000)),
            myCall = "F4FSY",
        )
        assertEquals(0, spots.size)
    }

    @Test
    fun excludesOwnCall() {
        val spots = SpotBuilder.build(
            messages = listOf(decoded("CQ F4FSY JN25", slotUtcMs = 1000)),
            myCall = "F4FSY",
        )
        assertEquals(0, spots.size)
    }

    @Test
    fun dedupesByCallKeepingLatest() {
        val spots = SpotBuilder.build(
            messages = listOf(
                decoded("CQ JA1ABC PM95", snr = -15, slotUtcMs = 1000),
                decoded("CQ JA1ABC PM95", snr = -3, slotUtcMs = 2000),
            ),
            myCall = "F4FSY",
        )
        assertEquals(1, spots.size)
        assertEquals(-3, spots[0].snr)
        assertEquals(2000L, spots[0].utcMs)
    }

    @Test
    fun windowFiltersOldMessages() {
        val spots = SpotBuilder.build(
            messages = listOf(
                decoded("CQ JA1ABC PM95", slotUtcMs = 80_000),
                decoded("CQ W1AW FN42", slotUtcMs = 95_500),
            ),
            myCall = "F4FSY",
            nowMs = 100_000,
            windowMs = 10_000,
        )
        assertEquals(1, spots.size)
        assertEquals("W1AW", spots[0].call)
    }

    @Test
    fun zeroSlotTimeBypassesWindow() {
        val spots = SpotBuilder.build(
            messages = listOf(decoded("CQ JA1ABC PM95", slotUtcMs = 0L)),
            myCall = "F4FSY",
            nowMs = 100_000,
            windowMs = 10_000,
        )
        assertEquals(1, spots.size)
    }

    @Test
    fun sortedByMostRecent() {
        val spots = SpotBuilder.build(
            messages = listOf(
                decoded("CQ JA1ABC PM95", slotUtcMs = 1000),
                decoded("CQ W1AW FN42", slotUtcMs = 3000),
                decoded("CQ DL1ABC JN48", slotUtcMs = 2000),
            ),
            myCall = "F4FSY",
        )
        assertEquals(listOf("W1AW", "DL1ABC", "JA1ABC"), spots.map { it.call })
    }

    @Test
    fun carriesHighlightRole() {
        val worked = WorkedIndex(grids = listOf("PM95"))
        val spots = SpotBuilder.build(
            messages = listOf(decoded("W1AW JA1ABC PM95", slotUtcMs = 1000)),
            worked = worked,
            myCall = "F4FSY",
        )
        // 网格已通联、但前缀未通联 → 新实体（近似新 DXCC/ITU）
        assertEquals(HighlightRole.NEW_ENTITY, spots[0].style.role)
    }
}
