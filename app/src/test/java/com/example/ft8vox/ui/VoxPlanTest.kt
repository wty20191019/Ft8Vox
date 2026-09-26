package com.example.ft8vox.ui

import com.example.ft8vox.data.settings.VoxTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        // 我方周期且刚过起点：就地发射，无前导 → 数据起点即现在
        assertEquals(100L, p.targetSlotIndex)
        assertEquals(now, p.targetStartMs)
        assertEquals(now, p.startAtMs)
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
    fun `有前导但来得及时就地发射并保留前导`() {
        val now = 100 * ft8 + 200
        val p = planTx(now, ft8, txParity = 0, preambleMs = 1_000)

        // 已过 200ms + 前导 1000ms 仍在起始窗口内：就地发射，立刻开始写播放
        assertEquals(100L, p.targetSlotIndex)
        assertEquals(now, p.startAtMs)
        assertEquals(now + 1_000L, p.targetStartMs)
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
    fun `有前导但已过起始窗口时瞄准后续时隙`() {
        val now = 100 * ft8 + 3_000
        val p = planTx(now, ft8, txParity = 0, preambleMs = 1_000)

        // 已过 3000ms + 前导 1000ms 超出窗口：等下一个我方周期
        assertEquals(102L, p.targetSlotIndex)
        assertEquals(1_530_000L, p.targetStartMs)
        assertEquals(1_529_000L, p.startAtMs)
    }

    @Test
    fun `报文能在本时隙播完时就地发射`() {
        // 起点后 2000ms + 前导 50ms + FT8 报文 12640ms = 14690 ≤ 15000：本时隙就能播完
        val now = 100 * ft8 + 2_000
        val p = planTx(now, ft8, txParity = 0, preambleMs = 50, messageMs = 12_640)

        assertEquals(100L, p.targetSlotIndex)
        assertEquals(now, p.startAtMs)
        assertEquals(now + 50L, p.targetStartMs)
    }

    @Test
    fun `报文播不完时等下一个我方时隙`() {
        // 起点后 2500ms：2500 + 50 + 12640 = 15190 > 15000 → 排下一个我方周期
        val now = 100 * ft8 + 2_500
        val p = planTx(now, ft8, txParity = 0, preambleMs = 50, messageMs = 12_640)

        assertEquals(102L, p.targetSlotIndex)
        assertEquals(1_530_000L, p.targetStartMs)
    }

    @Test
    fun `未提供报文时长时仍按旧的起始窗口判定`() {
        // 同为起点后 2000ms，但报文时长未知（0）→ 超出 1200ms 起始窗口，等下一个我方周期
        val now = 100 * ft8 + 2_000
        val p = planTx(now, ft8, txParity = 0, preambleMs = 50)

        assertEquals(102L, p.targetSlotIndex)
    }

    @Test
    fun `时隙偏移不影响报文塞得下的判断`() {
        // 偏移 +1500ms：名义起点后 3000ms 其实刚过偏移后的起点 1500ms，报文仍塞得下
        val now = 100 * ft8 + 3_000
        val p = planTx(
            now, ft8, txParity = 0, preambleMs = 50,
            messageMs = 12_640, slotOffsetMs = 1_500,
        )

        assertEquals(100L, p.targetSlotIndex)
        assertEquals(now, p.startAtMs)
    }

    @Test
    fun `FT4 报文更短就地窗口更宽`() {
        // FT4 时隙 7500ms：起点后 2500ms + 50 + 4480 = 7030 ≤ 7500 → 就地发射
        val now = 10 * ft4 + 2_500
        val p = planTx(now, ft4, txParity = 0, preambleMs = 50, messageMs = 4_480)

        assertEquals(10L, p.targetSlotIndex)
        assertEquals(now, p.startAtMs)
    }

    @Test
    fun `FT4 时隙同样成立`() {
        val now = 10 * ft4 + 50
        val p = planTx(now, ft4, txParity = 0, preambleMs = 0)
        assertEquals(10L, p.targetSlotIndex)
        assertEquals(now, p.targetStartMs)
    }

    @Test
    fun `缩水前导不小于零`() {
        assertEquals(1_500L, effectivePreambleMs(2_000, 500))
        assertEquals(0L, effectivePreambleMs(2_000, 2_500))
        assertEquals(0L, effectivePreambleMs(0, 100))
    }

    @Test
    fun `电平文案区分未运行_无信号与有信号`() {
        assertEquals("VOX --", voxLabel(-100f, signal = false, running = false))
        assertEquals("VOX --", voxLabel(-100f, signal = false, running = true))
        assertEquals("VOX -42 dB", voxLabel(-42.3f, signal = false, running = true))
        assertEquals("VOX -42 dB ●", voxLabel(-42.3f, signal = true, running = true))
    }

    @Test
    fun `VOX 指示随触发方式翻译_音频检测命中是有信号_静音检测命中是静音`() {
        // 音频检测：命中（电平 ≥ 阈值）＝有信号
        assertTrue(voxHasSignal(VoxTrigger.AUDIO, open = true))
        assertFalse(voxHasSignal(VoxTrigger.AUDIO, open = false))
        // 静音检测：命中（电平 < 阈值）＝静音，反而是「无信号」
        assertFalse(voxHasSignal(VoxTrigger.SILENCE, open = true))
        assertTrue(voxHasSignal(VoxTrigger.SILENCE, open = false))

        assertEquals("有信号", voxStateLabel(VoxTrigger.AUDIO, open = true, running = true))
        assertEquals("空闲", voxStateLabel(VoxTrigger.AUDIO, open = false, running = true))
        assertEquals("静音", voxStateLabel(VoxTrigger.SILENCE, open = true, running = true))
        assertEquals("有信号", voxStateLabel(VoxTrigger.SILENCE, open = false, running = true))
        // 未运行时不看触发方式
        assertEquals("未运行", voxStateLabel(VoxTrigger.SILENCE, open = true, running = false))
    }
}
