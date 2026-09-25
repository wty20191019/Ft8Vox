package com.example.ft8vox.ui

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 底部状态条纯逻辑的 JVM 单测。 */
class AppChromeTest {

    private fun msg(slotMs: Long, dt: Float = 0f) = DecodeResult(
        text = "CQ JA1ABC PM95",
        snr = -10,
        dt = dt,
        df = 1200,
        score = 20,
        slotUtcMs = slotMs,
    )

    @Test
    fun decodesPerMinuteCountsOnlyRecentSlots() {
        val now = 1_000_000L
        val list = listOf(
            msg(now - 10_000),
            msg(now - 59_000),
            msg(now - 61_000),
            msg(0), // 离线解码不参与速率
        )
        assertEquals(2, decodesPerMinute(list, now))
    }

    @Test
    fun timeSyncWarnsWhenDtTooLarge() {
        assertEquals("时间不同步", timeSyncWarning(2.0f))
        assertEquals("时间不同步", timeSyncWarning(-1.5f))
    }

    @Test
    fun timeSyncQuietWithinThreshold() {
        assertNull(timeSyncWarning(0.2f))
        assertNull(timeSyncWarning(null))
    }
}
