package com.example.ft8vox.ui

import com.example.ft8vox.engine.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 瀑布「发射频带」像素区间（[txBandPx]）的 JVM 单测。
 *
 * 频带＝`[下边频, 下边频 + 占用带宽]`：红线画在下边频，半透明红条铺满整段占用带宽。
 */
class WaterfallBandTest {

    // 频率轴：200 Hz 起、跨 2800 Hz、宽 2800 px ⇒ 1 px = 1 Hz（便于直接比对数字）
    private val width = 2800f

    private fun band(freqHz: Int, occupiedHz: Int) =
        txBandPx(freqHz, occupiedHz, fMinHz = 200f, spanHz = 2800f, width = width)

    @Test
    fun bandStartsAtLowerEdgeAndCoversOccupiedBandwidth() {
        val (a, b) = band(1000, 50)
        assertEquals(800f, a, 0.001f) // 1000 − 200
        assertEquals(850f, b, 0.001f) // + 50 Hz（FT8 占用带宽）
        assertEquals(50f, b - a, 0.001f)
    }

    @Test
    fun ft4BandIsWiderThanFt8() {
        val ft8 = band(1000, Protocol.FT8.occupiedHz)
        val ft4 = band(1000, Protocol.FT4.occupiedHz)
        assertEquals(50f, ft8.second - ft8.first, 0.001f)
        assertEquals(83f, ft4.second - ft4.first, 0.001f)
        assertTrue("FT4 占用带宽应比 FT8 宽", ft4.second > ft8.second)
    }

    @Test
    fun lowerEdgeBelowAxisIsClampedAndBandKeepsItsWidth() {
        // 下边频在视口左侧之外：红线贴到 0，条仍按真实带宽铺到 60 Hz 处
        val (a, b) = band(140, 50)
        assertEquals(0f, a, 0.001f)
        assertEquals(0f, b, 0.001f) // 上边频 190 Hz 也在 200 Hz 之下 ⇒ 整条在视口外

        val (c, d) = band(190, 50)
        assertEquals(0f, c, 0.001f)
        assertEquals(40f, d, 0.001f) // 240 Hz − 200 Hz
    }

    @Test
    fun bandAtRightEdgeIsZeroWidth() {
        // 下边频已到/越过右端：没有可画的范围（调用方此时只画线）
        val (a, b) = band(3000, 50)
        assertEquals(width, a, 0.001f)
        assertEquals(width, b, 0.001f)

        val (c, d) = band(2980, 50)
        assertEquals(2780f, c, 0.001f)
        assertEquals(width, d, 0.001f) // 上边频越过右端 ⇒ 夹到右端
    }

    @Test
    fun zeroSpanIsSafe() {
        assertEquals(0f to 0f, txBandPx(1000, 50, fMinHz = 200f, spanHz = 0f, width = width))
    }

    @Test
    fun gridStepKeepsLineCountBetweenFourAndEight() {
        // 常见音频轴跨度：从 200 Hz 到 3000 Hz 之间
        for (span in listOf(500f, 1000f, 2400f, 2800f, 2900f, 3000f)) {
            val step = gridStepHz(span)
            val lines = span / step
            assertTrue("跨度 $span Hz 的网格线数 $lines 应在 4~8 之间", lines >= 4f && lines <= 8f)
        }
    }

    @Test
    fun gridStepPrefersRoundNumbers() {
        assertEquals(500f, gridStepHz(2800f), 0.001f) // 2800/500 = 5.6 条
        assertEquals(1000f, gridStepHz(6000f), 0.001f) // 6000/500 = 12 太密，退到 1000
        assertEquals(100f, gridStepHz(600f), 0.001f) // 窄轴用细网格
    }
}
