package com.example.ft8vox.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U7b：VOX/PTT 发射调度与状态文案的纯逻辑回归。
 */
class VoxPlanTest {

    private val ft8 = 15_000L
    private val ft4 = 7_500L

    @Test
    fun `无前导且周期匹配时就地发射`() {
        val now = 100 * ft8 + 200 // 偶数时隙（周期 0），距起点 200 ms
        val p = planTx(now, ft8, txParity = 0, preambleMs = 0)

        assertEquals(100L, p.targetSlotIndex)
        assertEquals(1_500_000L, p.targetStartMs)
        assertEquals(1_500_000L, p.startAtMs) // 无前导：播放起点即数据起点
    }

    @Test
    fun `无前导且周期不匹配时等下一个我方时隙`() {
        val now = 101 * ft8 + 300 // 奇数时隙（周期 1），目标周期 0
        val p = planTx(now, ft8, txParity = 0, preambleMs = 0)

        assertEquals(102L, p.targetSlotIndex)
        assertEquals(1_530_000L, p.targetStartMs)
        assertEquals(1_530_000L, p.startAtMs)
    }

    @Test
    fun `有前导且周期匹配时瞄准后续时隙并提前播放`() {
        val now = 100 * ft8 + 200
        val p = planTx(now, ft8, txParity = 0, preambleMs = 1_000)

        // 当前时隙起点已过，只能瞄准再下一个匹配时隙
        assertEquals(102L, p.targetSlotIndex)
        assertEquals(1_530_000L, p.targetStartMs)
        assertEquals(1_529_000L, p.startAtMs)
        // 播放起点 + 前导 = 数据起点
        assertEquals(p.targetStartMs, p.startAtMs + 1_000)
    }

    @Test
    fun `有前导且周期不匹配时在上一时隙末启动`() {
        val now = 101 * ft8 + 300
        val p = planTx(now, ft8, txParity = 0, preambleMs = 1_000)

        assertEquals(102L, p.targetSlotIndex)
        assertEquals(1_530_000L, p.targetStartMs)
        // 起点落在当前（非匹配）时隙末尾
        assertEquals(1_529_000L, p.startAtMs)
        assertEquals(101L, p.startAtMs / ft8)
    }

    @Test
    fun `FT4 时隙同样成立`() {
        val now = 10 * ft4 + 50
        val p = planTx(now, ft4, txParity = 0, preambleMs = 0)
        assertEquals(10L, p.targetSlotIndex)
        assertEquals(75_000L, p.targetStartMs)
    }

    @Test
    fun `缩水前导不小于零`() {
        assertEquals(1_500L, effectivePreambleMs(2_000, 500))
        assertEquals(0L, effectivePreambleMs(2_000, 2_500))
        assertEquals(0L, effectivePreambleMs(0, 100))
    }

    @Test
    fun `电平文案区分未运行_静音与触发`() {
        assertEquals("VOX --", voxLabel(-100f, open = false, running = false))
        assertEquals("VOX --", voxLabel(-100f, open = false, running = true))
        assertEquals("VOX -42 dB", voxLabel(-42.3f, open = false, running = true))
        assertEquals("VOX -42 dB ●", voxLabel(-42.3f, open = true, running = true))
    }
}
