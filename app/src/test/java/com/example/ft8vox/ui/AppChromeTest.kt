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

    @Test
    fun decodeTimeHiddenUntilFirstDecode() {
        // 未接收 / 还没解码过：整段不显示，不占位
        assertNull(decodeTimeLabel(0L, running = false))
        assertNull(decodeTimeLabel(0L, running = true))
        assertNull(decodeTimeLabel(118L, running = false))
    }

    @Test
    fun decodeTimeShowsMillisecondsWhileRunning() {
        assertEquals("解码 118ms", decodeTimeLabel(118L, running = true))
        assertEquals("解码 1500ms", decodeTimeLabel(1500L, running = true))
    }

    // ---- 信息头 DX 栏（实机反馈：不能与「CQ 已发出」自相矛盾）----

    @Test
    fun dxLabelShowsTargetWhenSet() {
        assertEquals("DX→ JA1ABC PM95 -08", dxTargetLabel("JA1ABC", "PM95", -8, awaitingResponders = false))
        // 网格未知时用占位符
        assertEquals("DX→ JA1ABC -- +05", dxTargetLabel("JA1ABC", null, 5, awaitingResponders = false))
    }

    @Test
    fun dxLabelSaysCallingCqInsteadOfNoTarget() {
        // 正在叫 CQ 且还没人回应：不能再只说「未设目标」，否则和发射区「CQ 已发出，等待回应」打架
        val text = dxTargetLabel(null, null, -8, awaitingResponders = true)
        assertEquals("CQ 呼叫中，暂无目标", text)
    }

    @Test
    fun dxLabelFallsBackToNoTargetWhenIdle() {
        assertEquals("DX→ 未设目标", dxTargetLabel(null, null, -8, awaitingResponders = false))
    }
}
