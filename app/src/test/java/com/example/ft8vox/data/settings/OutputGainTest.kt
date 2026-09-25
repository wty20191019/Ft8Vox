package com.example.ft8vox.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 输出音量（发射数字衰减）范围与钳制的 JVM 单测。
 *
 * 发射波形（synth_gfsk）本身已是数字满幅，所以这个设置**只能衰减**：
 * 0 dB = 原样输出，正值一律钳回 0。
 */
class OutputGainTest {

    @Test
    fun defaultIsFullScale() {
        assertEquals(0, AppSettings().outputGainDb)
        assertEquals(0, OUTPUT_GAIN_MAX_DB)
    }

    @Test
    fun clampKeepsInRange() {
        assertEquals(-12, clampOutputGainDb(-12))
        assertEquals(OUTPUT_GAIN_MIN_DB, clampOutputGainDb(OUTPUT_GAIN_MIN_DB))
        assertEquals(-30, clampOutputGainDb(-40))
    }

    @Test
    fun positiveGainIsClampedToFullScale() {
        // 不允许放大：波形已满幅，放大只会削顶失真
        assertEquals(0, clampOutputGainDb(0))
        assertEquals(0, clampOutputGainDb(1))
        assertEquals(0, clampOutputGainDb(30))
    }
}
