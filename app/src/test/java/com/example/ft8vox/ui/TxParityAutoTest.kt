package com.example.ft8vox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 自动发射周期选择（按手机 UTC 时间取下一个来得及的时隙）的 JVM 单测。 */
class TxParityAutoTest {

    private val slot = 15_000L

    @Test
    fun picksNextSlotParity() {
        // 当前处于偶时隙（idx 10）起点后 1s，下一时隙 idx 11 为奇
        val now = 10 * slot + 1_000L
        assertEquals(1, nextSlotParity(now, slot, 0L))
    }

    @Test
    fun skipsImminentSlotWhenLeadTooLarge() {
        // 距下一时隙仅 200ms，前导余量 500ms：来不及，跳到 idx 12（偶）
        val now = 10 * slot + 14_800L
        assertEquals(0, nextSlotParity(now, slot, 500L))
    }

    @Test
    fun leadBoundaryTakesNextSlot() {
        // 距下一时隙正好 500ms，满足 >= 500：取 idx 11（奇）
        assertEquals(1, nextSlotParity(10 * slot + 14_500L, slot, 500L))
    }

    @Test
    fun zeroSlotFallsBackEven() {
        assertEquals(0, nextSlotParity(1_234L, 0L, 0L))
    }

    @Test
    fun parityLabelText() {
        assertEquals("偶数周期", parityLabel(0))
        assertEquals("奇数周期", parityLabel(1))
    }

    // 时隙约定：每一分钟 4 个 15 s 时隙，第 1、3 个为 0，第 2、4 个为 1。
    private val minute = 60_000L * 12_345

    @Test
    fun slotParityFollowsFourSlotsPerMinute() {
        assertEquals(0, slotParityOf(minute, 15_000L))
        assertEquals(1, slotParityOf(minute + 15_000L, 15_000L))
        assertEquals(0, slotParityOf(minute + 30_000L, 15_000L))
        assertEquals(1, slotParityOf(minute + 45_000L, 15_000L))
    }

    @Test
    fun slotParityWorksForFt4() {
        // FT4：每 7.5 s 一个时隙，奇偶同样逢奇为 1
        assertEquals(0, slotParityOf(minute, 7_500L))
        assertEquals(1, slotParityOf(minute + 7_500L, 7_500L))
        assertEquals(0, slotParityOf(minute + 15_000L, 7_500L))
        assertEquals(1, slotParityOf(minute + 22_500L, 7_500L))
    }

    @Test
    fun slotParityUnknownWhenOffline() {
        assertNull(slotParityOf(0L, 15_000L))
        assertNull(slotParityOf(1_000L, 0L))
    }

    @Test
    fun oppositeSlotParityPairsWithTarget() {
        // 目标在每第 1/3 个时隙（0）→ 我发第 2/4 个（1）；目标在第 2/4 个（1）→ 我发第 1/3 个（0）
        assertEquals(1, oppositeSlotParity(minute, 15_000L))
        assertEquals(0, oppositeSlotParity(minute + 15_000L, 15_000L))
        assertEquals(1, oppositeSlotParity(minute + 30_000L, 15_000L))
        assertEquals(0, oppositeSlotParity(minute + 45_000L, 15_000L))
    }

    @Test
    fun oppositeSlotParityUnknownWhenOffline() {
        assertNull(oppositeSlotParity(0L, 15_000L))
    }
}
