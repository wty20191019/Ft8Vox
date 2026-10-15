package com.example.ft8vox.ui

import com.example.ft8vox.data.settings.SLOT_OFFSET_LIMIT_MS
import com.example.ft8vox.engine.DecodeParallel
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

    @Test
    fun decodeParallelLabelShowsBandsAndThreads() {
        // 顶栏读数行：分段时是「段数×每段线程」，不分段时只剩线程总数；没传就不占位
        assertNull(decodeParallelLabel(null))
        assertEquals("4段×2", decodeParallelLabel(DecodeParallel(bands = 4, threadsPerBand = 2, totalThreads = 8)))
        assertEquals("8线程", decodeParallelLabel(DecodeParallel(bands = 1, threadsPerBand = 8, totalThreads = 8)))
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

    // ---- 一键校准：平均 DT → 时隙偏移 ----

    @Test
    fun avgDtNeedsEnoughSamples() {
        // 少于 3 条不给建议（不占位）
        assertNull(avgDtMs(emptyList()))
        assertNull(avgDtMs(listOf(1.2f)))
        assertNull(avgDtMs(listOf(1.2f, 1.3f)))
        assertEquals(1000, avgDtMs(listOf(1.0f, 1.0f, 1.0f)))
    }

    @Test
    fun avgDtRoundsToMilliseconds() {
        // 平均 1.233 s → 1233 ms
        assertEquals(1233, avgDtMs(listOf(1.2f, 1.3f, 1.2f)))
        // 负的 DT（本机时钟快了）同样成立
        assertEquals(-1500, avgDtMs(listOf(-1.5f, -1.5f, -1.5f)))
    }

    @Test
    fun slotOffsetFromDtClampsToLimit() {
        // 平均 DT 原样填进去（符号不变），超出 ±2500 夹住
        assertEquals(1500, slotOffsetFromDtMs(1500))
        assertEquals(-1500, slotOffsetFromDtMs(-1500))
        assertEquals(SLOT_OFFSET_LIMIT_MS, slotOffsetFromDtMs(9000))
        assertEquals(-SLOT_OFFSET_LIMIT_MS, slotOffsetFromDtMs(-9000))
    }

    @Test
    fun calibrationLabelHiddenWithoutSamples() {
        assertNull(dtCalibrationLabel(null, 0))
        assertEquals("校准 +1.5s·12", dtCalibrationLabel(1500, 12))
        assertEquals("校准 -0.4s·3", dtCalibrationLabel(-400, 3))
    }

    @Test
    fun slotOffsetInputParsesPlainAndSignedMs() {
        assertEquals(1500, parseSlotOffsetInput("1500"))
        assertEquals(1500, parseSlotOffsetInput("+1500"))
        assertEquals(-1500, parseSlotOffsetInput("-1500"))
        assertEquals(0, parseSlotOffsetInput("0"))
    }

    @Test
    fun slotOffsetInputToleratesSpacesAndFullWidthSigns() {
        assertEquals(1500, parseSlotOffsetInput(" +1500 "))
        assertEquals(1500, parseSlotOffsetInput("\uFF0B1500")) // 全角 ＋
        assertEquals(-1500, parseSlotOffsetInput("\uFF0D1500")) // 全角 －
        assertEquals(-1500, parseSlotOffsetInput("\u22121500")) // 数学减号 −
        assertEquals(1500, parseSlotOffsetInput("1\u00A0500")) // 不换行空格
    }

    @Test
    fun slotOffsetInputRejectsNonMsText() {
        // 单位固定毫秒：小数 / 带单位后缀 / 字母 / 空 / 只有符号 都算非法（提交时不写盘、退回原值）
        assertNull(parseSlotOffsetInput(""))
        assertNull(parseSlotOffsetInput("   "))
        assertNull(parseSlotOffsetInput("1.5"))
        assertNull(parseSlotOffsetInput("1.5s"))
        assertNull(parseSlotOffsetInput("abc"))
        assertNull(parseSlotOffsetInput("+"))
        assertNull(parseSlotOffsetInput("-"))
        assertNull(parseSlotOffsetInput("1 2a"))
        assertNull(parseSlotOffsetInput("+-1500"))
    }

    @Test
    fun slotOffsetInputClampsToLimit() {
        // 与设置读写层的 coerceIn 同口径：填过大就顶到边界，不算非法
        assertEquals(SLOT_OFFSET_LIMIT_MS, parseSlotOffsetInput("99999"))
        assertEquals(-SLOT_OFFSET_LIMIT_MS, parseSlotOffsetInput("-99999"))
        assertEquals(SLOT_OFFSET_LIMIT_MS, parseSlotOffsetInput("2500"))
    }

    @Test
    fun slotOffsetInputTextRoundTrips() {
        // 零也带正号：与原来步进器的显示（`+0 ms`）一致
        assertEquals("+1500", slotOffsetInputText(1500))
        assertEquals("-1500", slotOffsetInputText(-1500))
        assertEquals("+0", slotOffsetInputText(0))
        // 回填文本能被自己解析回原值（编辑态的空占位符用的就是它）
        for (v in listOf(-2500, -100, 0, 100, 2500)) {
            assertEquals(v, parseSlotOffsetInput(slotOffsetInputText(v)))
        }
    }
}
