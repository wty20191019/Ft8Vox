package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 显示过滤、chip 选择与 Call 1st 选台的 JVM 单测。 */
class DecodeFilterTest {

    private fun decoded(text: String, snr: Int = -10, df: Int = 1000) = DecodeResult(
        text = text,
        snr = snr,
        dt = 0f,
        df = df,
        score = 20,
        slotUtcMs = 0L,
    )

    // ---- DecodeFilterState.toggle（new_ui §3.2） ----

    @Test
    fun allIsExclusive() {
        val f = DecodeFilterState()
        assertTrue(f.isSelected(DecodeFilterTag.ALL))
        // 选中「全部」时点 CQ → 只保留 CQ
        val onlyCq = f.toggle(DecodeFilterTag.CQ)
        assertEquals(setOf(DecodeFilterTag.CQ), onlyCq.tags)
        // 再点 CQ → 清空
        assertTrue(onlyCq.toggle(DecodeFilterTag.CQ).isEmptySelection)
    }

    @Test
    fun othersAreMultiSelect() {
        val f = DecodeFilterState(tags = setOf(DecodeFilterTag.CQ))
        val both = f.toggle(DecodeFilterTag.TO_ME)
        assertEquals(setOf(DecodeFilterTag.CQ, DecodeFilterTag.TO_ME), both.tags)
    }

    @Test
    fun selectingAllClearsOthers() {
        val f = DecodeFilterState(tags = setOf(DecodeFilterTag.CQ, DecodeFilterTag.REPLY))
        assertEquals(setOf(DecodeFilterTag.ALL), f.toggle(DecodeFilterTag.ALL).tags)
    }

    @Test
    fun emptySelectionMatchesNothing() {
        val f = DecodeFilterState(tags = emptySet())
        assertTrue(f.isEmptySelection)
        assertFalse(DecodeFilter.matches("CQ JA1ABC PM95", f))
    }

    // ---- DecodeFilter.matches ----

    @Test
    fun cqTagKeepsOnlyCq() {
        val f = DecodeFilterState(tags = setOf(DecodeFilterTag.CQ))
        assertTrue(DecodeFilter.matches("CQ JA1ABC PM95", f))
        assertFalse(DecodeFilter.matches("F4FSY JA1ABC -08", f))
    }

    @Test
    fun toMeTagKeepsMessagesAddressedToMe() {
        val f = DecodeFilterState(tags = setOf(DecodeFilterTag.TO_ME))
        assertTrue(DecodeFilter.matches("F4FSY JA1ABC -08", f, myCall = "F4FSY"))
        assertFalse(DecodeFilter.matches("W1AW JA1ABC -08", f, myCall = "F4FSY"))
    }

    @Test
    fun replyAnd73TagsAreDistinct() {
        val reply = DecodeFilterState(tags = setOf(DecodeFilterTag.REPLY))
        assertTrue(DecodeFilter.matches("W1AW JA1ABC -08", reply))
        assertFalse(DecodeFilter.matches("W1AW JA1ABC RR73", reply))

        val s73 = DecodeFilterState(tags = setOf(DecodeFilterTag.SEVENTY_THREE))
        assertTrue(DecodeFilter.matches("W1AW JA1ABC RR73", s73))
        assertTrue(DecodeFilter.matches("W1AW JA1ABC 73", s73))
        assertFalse(DecodeFilter.matches("W1AW JA1ABC -08", s73))
    }

    @Test
    fun workedTagKeepsWorkedCalls() {
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val f = DecodeFilterState(tags = setOf(DecodeFilterTag.WORKED))
        assertTrue(DecodeFilter.matches("CQ JA1ABC PM95", f, worked))
        assertFalse(DecodeFilter.matches("CQ W1AW FN42", f, worked))
    }

    @Test
    fun multiSelectIsUnion() {
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val f = DecodeFilterState(tags = setOf(DecodeFilterTag.CQ, DecodeFilterTag.WORKED))
        assertTrue(DecodeFilter.matches("CQ W1AW FN42", f, worked))
        assertTrue(DecodeFilter.matches("CQ JA1ABC PM95", f, worked))
        assertFalse(DecodeFilter.matches("W1AW DL1ABC -08", f, worked))
    }

    @Test
    fun ignoredCallsAreExcluded() {
        val f = DecodeFilterState(tags = setOf(DecodeFilterTag.ALL), ignoredCalls = setOf("JA1ABC"))
        assertFalse(DecodeFilter.matches("CQ JA1ABC PM95", f))
        assertTrue(DecodeFilter.matches("CQ W1AW FN42", f))
    }

    @Test
    fun queryMatchesCallOrPrefix() {
        val f = DecodeFilterState(query = "ja, w1")
        assertTrue(DecodeFilter.matches("CQ JA1ABC PM95", f))
        assertTrue(DecodeFilter.matches("CQ W1AW FN42", f))
        assertFalse(DecodeFilter.matches("CQ DL1ABC JN48", f))
    }

    @Test
    fun queryMatchesGrid() {
        val f = DecodeFilterState(query = "PM95")
        assertTrue(DecodeFilter.matches("CQ JA1ABC PM95", f))
        assertFalse(DecodeFilter.matches("CQ W1AW FN42", f))
    }

    @Test
    fun countsPerTag() {
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val msgs = listOf(
            decoded("CQ W1AW FN42"),
            decoded("CQ JA1ABC PM95"),
            decoded("F4FSY JA2XYZ -08"),
            decoded("W1AW JA2XYZ RR73"),
        )
        val counts = DecodeFilter.counts(msgs, worked, myCall = "F4FSY")
        assertEquals(4, counts[DecodeFilterTag.ALL])
        assertEquals(2, counts[DecodeFilterTag.CQ])
        assertEquals(1, counts[DecodeFilterTag.TO_ME])
        assertEquals(1, counts[DecodeFilterTag.REPLY])
        assertEquals(1, counts[DecodeFilterTag.SEVENTY_THREE])
        assertEquals(1, counts[DecodeFilterTag.WORKED])
    }

    @Test
    fun countsSkipIgnoredCalls() {
        val msgs = listOf(decoded("CQ W1AW FN42"), decoded("CQ JA1ABC PM95"))
        val counts = DecodeFilter.counts(msgs, ignoredCalls = setOf("JA1ABC"))
        assertEquals(1, counts[DecodeFilterTag.ALL])
        assertEquals(1, counts[DecodeFilterTag.CQ])
    }

}
