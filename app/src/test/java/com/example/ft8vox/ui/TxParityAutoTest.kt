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

    // ---- 发射调度 planTx：紧邻时隙应答 ----

    @Test
    fun planTxOwnSlotWithinWindowTransmitsNow() {
        // 我方周期，时隙起点后 300ms，前导 50ms：就地发射，立刻开始写播放
        val now = minute + 300L
        val plan = planTx(now, slot, txParity = 0, preambleMs = 50L)
        assertEquals(minute / slot, plan.targetSlotIndex)
        assertEquals(now, plan.startAtMs)
        assertEquals(now + 50L, plan.targetStartMs)
    }

    @Test
    fun planTxOwnSlotPastWindowWaitsNextCycle() {
        // 我方周期但已过 3s（+前导超出起始窗口）：等下一个我方周期（+2）
        val now = minute + 3_000L
        val plan = planTx(now, slot, txParity = 0, preambleMs = 50L)
        assertEquals(minute / slot + 2, plan.targetSlotIndex)
        assertEquals(minute + 2 * slot, plan.targetStartMs)
        assertEquals(minute + 2 * slot - 50L, plan.startAtMs)
    }

    @Test
    fun planTxOpponentSlotTargetsNextSlot() {
        // 对方周期（奇）：下一个时隙必然是我方周期（偶）
        val now = minute + slot + 3_000L
        val plan = planTx(now, slot, txParity = 0, preambleMs = 50L)
        assertEquals(minute / slot + 2, plan.targetSlotIndex)
        assertEquals(minute + 2 * slot, plan.targetStartMs)
    }

    // ---- 时隙偏移（U7「发射偏移」）：整个时隙一起平移 ----

    @Test
    fun slotOffsetShiftsTargetStartAndKeepsSlotIndex() {
        // 名义时隙 10 刚开头 200ms，我方周期为奇 → 无偏移时瞄准时隙 11 的边界
        val now = 10 * slot + 200L
        val base = planTx(now, slot, txParity = 1, preambleMs = 50L)
        assertEquals(11, base.targetSlotIndex)
        assertEquals(11 * slot, base.targetStartMs)

        // +1.5s 偏移：仍是同一个时隙 11，但起点与播放起点整体推后 1500ms
        val shifted = planTx(now, slot, txParity = 1, preambleMs = 50L, slotOffsetMs = 1_500L)
        assertEquals(11, shifted.targetSlotIndex)
        assertEquals(11 * slot + 1_500L, shifted.targetStartMs)
        assertEquals(base.startAtMs + 1_500L, shifted.startAtMs)
    }

    @Test
    fun slotOffsetMovesInPlaceWindow() {
        // 偏移 +1.5s 后时隙 10 的起点刚到 100ms：仍走「就地发射」
        val now = 10 * slot + 1_500L + 100L
        val plan = planTx(now, slot, txParity = 0, preambleMs = 50L, slotOffsetMs = 1_500L)
        assertEquals(10, plan.targetSlotIndex)
        assertEquals(now, plan.startAtMs)
        assertEquals(now + 50L, plan.targetStartMs)

        // 同一时刻若偏移为负（起点早已过去 3s+）：等下一个我方周期（时隙 12）
        val late = planTx(now, slot, txParity = 0, preambleMs = 50L, slotOffsetMs = -1_500L)
        assertEquals(12, late.targetSlotIndex)
        assertEquals(12 * slot - 1_500L, late.targetStartMs)
    }

    // ---- 回归：解码在时隙结束后才到手，应答必须用「对方时隙的相反周期」 ----

    @Test
    fun answeringUsesOppositeOfHeardSlotNotTimeBasedParity() {
        val heard = minute + 30_000L            // 对方在第 3 个时隙（偶）发 CQ
        val deliveredAt = heard + slot + 300L   // 解码在下一时隙开头 300ms 才轮到轮询
        // 时间法会算出「再下一个时隙」的奇偶，恰好与对方相同 → 双方同周期发射、永远收不到
        assertEquals(0, nextSlotParity(deliveredAt, slot, 0L))
        // 正确做法：取对方时隙的相反周期
        assertEquals(1, oppositeSlotParity(heard, slot))
    }

    @Test
    fun answeringLandsInTheVeryNextSlot() {
        val heard = minute + 30_000L
        val deliveredAt = heard + slot + 300L
        val myParity = oppositeSlotParity(heard, slot)!!
        val plan = planTx(deliveredAt, slot, myParity, preambleMs = 50L)
        // 落在紧邻的下一个时隙（不是白等一个周期）
        assertEquals(heard / slot + 1, plan.targetSlotIndex)
        assertEquals(heard + slot, plan.targetStartMs / slot * slot)
        assertEquals(1, slotParityOf(plan.targetStartMs, slot))
    }

    @Test
    fun answeringWithFullMessageLengthLandsInTheSameSlot() {
        // 对方在时隙 N（偶）发 CQ，解码在 N+1（我方奇）开头 800ms 才轮到轮询
        val heard = minute + 30_000L
        val deliveredAt = heard + slot + 800L
        val myParity = oppositeSlotParity(heard, slot)!!
        val plan = planTx(deliveredAt, slot, myParity, preambleMs = 50L, messageMs = 12_640L)
        // 800 + 50 + 12640 = 13490 ≤ 15000：本时隙就能把整条报文播完，不必再等一个周期
        assertEquals(heard / slot + 1, plan.targetSlotIndex)
        assertEquals(deliveredAt, plan.startAtMs)
        assertEquals(deliveredAt + 50L, plan.targetStartMs)
    }

    @Test
    fun qsoProgressLandsInTheFollowingOwnSlot() {
        // 我方为 CQ 方（偶），在对方周期收到报告：下一个我方时隙就要发出去
        val receivedAt = minute + slot + 300L    // 对方时隙开头 300ms 到手
        val plan = planTx(receivedAt, slot, txParity = 0, preambleMs = 50L)
        // 当前是对方周期 → 瞄准下一个我方周期（偶）
        assertEquals(0, slotParityOf(plan.targetStartMs, slot))
        assertEquals(minute + 2 * slot, plan.targetStartMs)
    }

    // ---- 回归：QSO 完成后不得「跳时隙」 ----

    @Test
    fun keepsLockedParityAfterQsoCompletes() {
        // 时隙 10（偶）刚开头：按时间重锁会算出「下一个」是奇
        val now = 10 * slot + 1_000L
        assertEquals(1, nextSlotParity(now, slot, 0L))
        assertEquals(1, effectiveTxParity(pinned = null, locked = null, nowMs = now, slotMs = slot, leadMs = 0L))
        // 已经锁定为偶（上一段 QSO 用的周期）→ 保持不变，下一段继续用偶
        assertEquals(0, effectiveTxParity(pinned = null, locked = 0, nowMs = now, slotMs = slot, leadMs = 0L))
        // 「设为目标」的固定周期优先（目标在偶 → 我发奇）
        assertEquals(1, effectiveTxParity(pinned = 1, locked = 0, nowMs = now, slotMs = slot, leadMs = 0L))
        assertEquals(0, effectiveTxParity(pinned = 0, locked = 1, nowMs = now, slotMs = slot, leadMs = 0L))
    }

    @Test
    fun effectiveTxParityFallsBackWhenSlotLengthUnknown() {
        // 离线/未运行时 slotMs=0 → nextSlotParity 回退偶周期
        assertEquals(0, effectiveTxParity(pinned = null, locked = null, nowMs = 1_000L, slotMs = 0L, leadMs = 0L))
        // 已有锁定周期时不受影响
        assertEquals(1, effectiveTxParity(pinned = null, locked = 1, nowMs = 1_000L, slotMs = 0L, leadMs = 0L))
    }
}
