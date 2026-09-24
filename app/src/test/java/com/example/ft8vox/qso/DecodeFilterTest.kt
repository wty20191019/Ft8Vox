package com.example.ft8vox.qso

import com.example.ft8vox.data.settings.CallFirstMode
import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 显示过滤与 Call 1st 选台的 JVM 单测。 */
class DecodeFilterTest {

    private fun decoded(text: String, snr: Int = -10, df: Int = 1000) = DecodeResult(
        text = text,
        snr = snr,
        dt = 0f,
        df = df,
        score = 20,
        slotUtcMs = 0L,
    )

    // ---- DecodeFilter ----

    @Test
    fun cqOnlyKeepsOnlyCq() {
        val f = DecodeFilterState(cqOnly = true)
        assertEquals(true, DecodeFilter.matches("CQ JA1ABC PM95", f))
        assertEquals(false, DecodeFilter.matches("F4FSY JA1ABC -08", f))
    }

    @Test
    fun excludeWorkedFiltersKnownCall() {
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val f = DecodeFilterState(excludeWorked = true)
        assertEquals(false, DecodeFilter.matches("CQ JA1ABC PM95", f, worked))
        assertEquals(true, DecodeFilter.matches("CQ W1AW FN42", f, worked))
    }

    @Test
    fun queryMatchesCallOrPrefix() {
        val f = DecodeFilterState(query = "ja, w1")
        assertEquals(true, DecodeFilter.matches("CQ JA1ABC PM95", f))
        assertEquals(true, DecodeFilter.matches("CQ W1AW FN42", f))
        assertEquals(false, DecodeFilter.matches("CQ DL1ABC JN48", f))
    }

    @Test
    fun queryMatchesGrid() {
        val f = DecodeFilterState(query = "PM95")
        assertEquals(true, DecodeFilter.matches("CQ JA1ABC PM95", f))
        assertEquals(false, DecodeFilter.matches("CQ W1AW FN42", f))
    }

    @Test
    fun filtersCombine() {
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val f = DecodeFilterState(cqOnly = true, excludeWorked = true, query = "ja")
        // 是 CQ、匹配前缀，但已通联 → 被排除
        assertEquals(false, DecodeFilter.matches("CQ JA1ABC PM95", f, worked))
    }

    // ---- CallFirstSelector ----

    @Test
    fun callFirstOffReturnsNull() {
        val picked = CallFirstSelector.pick(
            listOf(decoded("CQ JA1ABC PM95", snr = -5)),
            CallFirstMode.OFF,
        )
        assertNull(picked)
    }

    @Test
    fun strongestPicksHighestSnr() {
        val picked = CallFirstSelector.pick(
            listOf(
                decoded("CQ JA1ABC PM95", snr = -12),
                decoded("CQ W1AW FN42", snr = -3),
                decoded("CQ DL1ABC JN48", snr = -8),
            ),
            CallFirstMode.STRONGEST,
        )
        assertEquals("W1AW", picked?.call)
        assertEquals(-3, picked?.snr)
    }

    @Test
    fun firstPicksFirstCq() {
        val picked = CallFirstSelector.pick(
            listOf(
                decoded("CQ JA1ABC PM95", snr = -12),
                decoded("CQ W1AW FN42", snr = -3),
            ),
            CallFirstMode.FIRST,
        )
        assertEquals("JA1ABC", picked?.call)
    }

    @Test
    fun ignoresNonCqAndOwnCall() {
        val picked = CallFirstSelector.pick(
            listOf(
                decoded("CQ F4FSY JN25", snr = 0), // 自己
                decoded("F4FSY JA1ABC -08"), // 不是 CQ
            ),
            CallFirstMode.STRONGEST,
            myCall = "F4FSY",
        )
        assertNull(picked)
    }

    @Test
    fun respectsExcludeWorkedAndFilter() {
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val filter = DecodeFilterState(cqOnly = true, excludeWorked = true, query = "ja")
        val picked = CallFirstSelector.pick(
            listOf(
                decoded("CQ JA1ABC PM95", snr = -2), // 已通联，排除
                decoded("CQ JA2XYZ PM96", snr = -9), // 通过
            ),
            CallFirstMode.STRONGEST,
            filter,
            worked,
        )
        assertEquals("JA2XYZ", picked?.call)
    }

    @Test
    fun dedupesSameCallKeepingFirst() {
        val picked = CallFirstSelector.pick(
            listOf(
                decoded("CQ JA1ABC PM95", snr = -9),
                decoded("CQ JA1ABC PM95", snr = -3),
            ),
            CallFirstMode.STRONGEST,
        )
        // 同呼号按时隙内首次出现去重
        assertEquals("JA1ABC", picked?.call)
        assertEquals(-9, picked?.snr)
    }

    @Test
    fun carriesGridAndDf() {
        val picked = CallFirstSelector.pick(
            listOf(decoded("CQ JA1ABC PM95", snr = -4, df = 1521)),
            CallFirstMode.FIRST,
        )
        assertEquals("PM95", picked?.grid)
        assertEquals(1521, picked?.df)
    }
}
