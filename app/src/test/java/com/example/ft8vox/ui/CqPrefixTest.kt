package com.example.ft8vox.ui

import com.example.ft8vox.qso.CQ_PREFIX_SLOTS
import com.example.ft8vox.qso.DEFAULT_CQ_PREFIXES
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「CQ 前缀」格子规整的单测（`docs/UI-MOBILE.md` §28）。
 *
 * 弹窗「确定」提交的就是 [cleanCqPrefixes]：固定 8 格、去空格转大写、**空格子保留**（＝普通 CQ）、
 * 全空回落默认值、选中下标夹住。格子序号必须稳定——`cqPrefixIndex` 记的是下标，
 * 一旦去空让下标错位，用户选的 CQ 前缀就会莫名换成另一格。
 */
class CqPrefixTest {

    @Test
    fun trimsAndUppercasesAndPadsToEightSlots() {
        val (prefixes, index) = cleanCqPrefixes(listOf(" dx ", "Asia"), 0)
        assertEquals(CQ_PREFIX_SLOTS, prefixes.size)
        assertEquals("DX", prefixes[0])
        assertEquals("ASIA", prefixes[1])
        assertEquals("", prefixes[2])
        assertEquals(0, index)
    }

    @Test
    fun emptySlotsAreKeptInPlace() {
        // 空格子＝普通 CQ，不能被挤掉（下标即选中项）
        val (prefixes, _) = cleanCqPrefixes(listOf("", "DX", "", "", "", "", "", "POTA"), 7)
        assertEquals("", prefixes[0])
        assertEquals("DX", prefixes[1])
        assertEquals("POTA", prefixes[7])
    }

    @Test
    fun extraSlotsAreTruncated() {
        val (prefixes, _) = cleanCqPrefixes(List(12) { "P$it" }, 0)
        assertEquals(CQ_PREFIX_SLOTS, prefixes.size)
        assertEquals("P7", prefixes[7])
    }

    @Test
    fun allEmptyFallsBackToDefaults() {
        val (prefixes, _) = cleanCqPrefixes(List(CQ_PREFIX_SLOTS) { "  " }, 2)
        assertEquals(DEFAULT_CQ_PREFIXES, prefixes)
    }

    @Test
    fun selectedIndexIsClampedIntoRange() {
        assertEquals(0, cleanCqPrefixes(listOf("DX"), -3).second)
        assertEquals(CQ_PREFIX_SLOTS - 1, cleanCqPrefixes(listOf("DX"), 99).second)
    }
}
