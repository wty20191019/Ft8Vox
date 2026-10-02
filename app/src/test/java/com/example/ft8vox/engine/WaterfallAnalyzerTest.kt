package com.example.ft8vox.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Kotlin 侧瀑布分析器（[WaterfallAnalyzer]）的 JVM 单测。
 *
 * 用合成单音验证频率轴与峰值定位，不依赖 native / 音频设备。
 */
class WaterfallAnalyzerTest {

    private val rate = 12000

    private fun tone(freqHz: Double, seconds: Double, amp: Float = 1f): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { i -> (amp * sin(2.0 * PI * freqHz * i / rate)).toFloat() }
    }

    @Test
    fun axisCoversRequestedRange() {
        val a = WaterfallAnalyzer(rate, 200f, 3000f)
        assertTrue("fMin=${a.info.fMinHz}", a.info.fMinHz in 195f..206f)
        assertEquals(12000f / 2048f, a.info.binHz, 1e-4f)
        // 约 2800 Hz / 5.86 Hz ≈ 478 个 bin
        assertTrue("bins=${a.info.bins}", a.info.bins in 470..490)
    }

    @Test
    fun toneShowsPeakNearItsFrequency() {
        val a = WaterfallAnalyzer(rate, 200f, 3000f)
        a.feed(tone(1000.0, 2.0))
        val data = a.poll(256)
        val bins = a.info.bins
        assertTrue("应产出瀑布行", data.isNotEmpty())
        val rows = data.size / bins
        assertTrue("rows=$rows", rows > 10)

        val col = LongArray(bins)
        for (r in 0 until rows) {
            for (b in 0 until bins) col[b] += (data[r * bins + b].toInt() and 0xFF).toLong()
        }
        val peak = col.indices.maxByOrNull { col[it] }!!
        val peakHz = a.info.fMinHz + peak * a.info.binHz
        assertTrue("peakHz=$peakHz", abs(peakHz - 1000f) < 20f)
    }

    @Test
    fun rowRateIsEightyMilliseconds() {
        val a = WaterfallAnalyzer(rate, 200f, 3000f)
        a.feed(tone(700.0, 1.0))
        val rows = a.poll(256).size / a.info.bins
        // 1 s 内：首行在 ~2048 样本（171 ms）后，其后每 960 样本（80 ms）一行 → 约 11 行
        assertTrue("rows=$rows", rows in 10..14)
    }

    @Test
    fun resetClearsPendingRows() {
        val a = WaterfallAnalyzer(rate, 200f, 3000f)
        a.feed(tone(700.0, 1.0))
        a.reset()
        assertEquals(0, a.poll(256).size)
    }
}
