package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 发射抽屉纯逻辑（报文构造 / CQ 前缀 / 发射调度）的 JVM 单测。 */
class TxComposeTest {

    private fun decode(text: String, snr: Int, df: Int = 1200, slot: Long = 0) =
        DecodeResult(text = text, snr = snr, dt = 0f, df = df, score = 10, slotUtcMs = slot)

    // ---- 报文构造 ----

    @Test
    fun composesCqWithCallAndGrid() {
        assertEquals(
            "CQ K1ABC FN42",
            TxCompose.compose(TxMessageKind.CQ, null, "k1abc", "fn42"),
        )
    }

    @Test
    fun composesCqWithoutGrid() {
        assertEquals("CQ K1ABC", TxCompose.compose(TxMessageKind.CQ, null, "K1ABC", ""))
    }

    @Test
    fun composesSixStepsInOrder() {
        assertEquals(
            "JA1ABC K1ABC FN42",
            TxCompose.compose(TxMessageKind.GRID, "ja1abc", "K1ABC", "FN42"),
        )
        assertEquals(
            "JA1ABC K1ABC -12",
            TxCompose.compose(TxMessageKind.REPORT, "JA1ABC", "K1ABC", "FN42", -12),
        )
        assertEquals(
            "JA1ABC K1ABC R-12",
            TxCompose.compose(TxMessageKind.ROGER, "JA1ABC", "K1ABC", "FN42", -12),
        )
        assertEquals(
            "JA1ABC K1ABC RR73",
            TxCompose.compose(TxMessageKind.RR73, "JA1ABC", "K1ABC", "FN42"),
        )
        assertEquals(
            "JA1ABC K1ABC 73",
            TxCompose.compose(TxMessageKind.SEVENTY_THREE, "JA1ABC", "K1ABC", "FN42"),
        )
        // 六步 order 与 QsoEngine 的 Step.order 对齐
        assertEquals(1, TxMessageKind.GRID.order)
        assertEquals(6, TxMessageKind.CQ.order)
    }

    @Test
    fun directedKindsNeedTarget() {
        assertNull(TxCompose.compose(TxMessageKind.GRID, null, "K1ABC", "FN42"))
        assertNull(TxCompose.compose(TxMessageKind.REPORT, "", "K1ABC", "FN42"))
        assertNull(TxCompose.compose(TxMessageKind.ROGER, null, "K1ABC", "FN42"))
        assertNull(TxCompose.compose(TxMessageKind.RR73, null, "K1ABC", "FN42"))
    }

    // ---- 六格报文槽（发射区，docs/Ft8Vox.md「格内文字就是真发的报文」） ----

    @Test
    fun slotsUseMyOwnReportForBothReportAndRoger() {
        // 序号 2「报告」与序号 3「R报告」必须是**同一个值**（我方实测强度，照 FT8CN `toCallsign.snr`）。
        // 真机 bug：R 报告误用「对方给我的报告」→ 槽内文字与真正发出的报文不一致，
        // 发射区的红/绿点（按**文本相等**匹配）就不亮（现象：在发 R-07，3 R报告那格不点灯）。
        val slots = TxCompose.slots("ja1abc", "K1ABC", "FN42", reportSent = -7)
        assertEquals(TxMessageKind.REPORT, slots[1].first)
        assertEquals("JA1ABC K1ABC -07", slots[1].second)
        assertEquals(TxMessageKind.ROGER, slots[2].first)
        assertEquals("JA1ABC K1ABC R-07", slots[2].second)
    }

    @Test
    fun slotsMatchEngineSpokenText() {
        // 契约：每一格都要与 QsoEngine 对应序号的 `txText` **逐字相等**，否则 `slotLed`
        // 的红/绿点匹配不上。六个序号逐一对照（下标 0..5 即序号 1..6）。
        val slots = TxCompose.slots("JA1ABC", "K1ABC", "FN42", reportSent = -7)
        val engine = QsoEngine()
        engine.configure("K1ABC", "FN42")

        // 序号 1：应答对方 CQ → 发网格
        val p1 = engine.startResponderQso("JA1ABC", "PM95", utcMs = 1_000, snr = -7)
        assertEquals(1, p1.order)
        assertEquals(slots[0].second, p1.txText)

        // 序号 2：对方回我网格 → 发实测报告（FT8 定向报文是「<收方> <发方> <载荷>」）
        val p2 = engine.onDecoded(listOf(decode("K1ABC JA1ABC PM95", -10, slot = 15_000)), 16_000)
        assertEquals(2, p2.order)
        assertEquals(slots[1].second, p2.txText)

        // 序号 3：对方给我报告 → 发 R 报告（本次真机截图那一格）
        val p3 = engine.respondToReport("JA1ABC", theirReport = -12, snr = -7, utcMs = 31_000)
        assertEquals(3, p3.order)
        assertEquals(slots[2].second, p3.txText)

        // 序号 4：对方 R 报告 → 发 RR73
        val p4 = engine.respondToRoger("JA1ABC", theirReport = -12, snr = -7, utcMs = 46_000)
        assertEquals(4, p4.order)
        assertEquals(slots[3].second, p4.txText)

        // 序号 5：对方 RR73 → 回 73
        val p5 = engine.respondToRr73("JA1ABC", snr = -7, utcMs = 61_000)
        assertEquals(5, p5.order)
        assertEquals(slots[4].second, p5.txText)

        // 序号 6：主叫 CQ（不受目标影响）
        val p6 = engine.startCq(utcMs = 76_000)
        assertEquals(6, p6.order)
        assertEquals(slots[5].second, p6.txText)
    }

    @Test
    fun targetPrefersEngineWhileBusy() {
        // 引擎在通联 / 有待发报文（engineBusy=true）→ 以引擎对手为准，忽略用户先前点选的旧呼号
        assertEquals("JA1ABC", TxCompose.targetFor(engineCall = "JA1ABC", engineBusy = true, picked = "N0CALL"))
        // 引擎忙但没有对手 → 退回点选目标
        assertEquals("N0CALL", TxCompose.targetFor(engineCall = null, engineBusy = true, picked = "N0CALL"))
    }

    @Test
    fun targetFallsBackToPickedWhenEngineIdle() {
        // 引擎空闲（DONE 残留的对手不算数）→ 用点选目标，方便预先备好报文
        assertEquals("N0CALL", TxCompose.targetFor(engineCall = "JA1ABC", engineBusy = false, picked = "N0CALL"))
        // 没有点选目标 → 回落到引擎残留对手；再没有则 null
        assertEquals("JA1ABC", TxCompose.targetFor(engineCall = "JA1ABC", engineBusy = false, picked = null))
        assertNull(TxCompose.targetFor(engineCall = null, engineBusy = false, picked = "  "))
    }

    // ---- 类型识别 ----

    @Test
    fun classifiesMessageKinds() {
        assertEquals(TxMessageKind.CQ, TxCompose.kindOf("CQ K1ABC FN42", "K1ABC"))
        assertEquals(TxMessageKind.GRID, TxCompose.kindOf("JA1ABC K1ABC FN42", "K1ABC"))
        assertEquals(TxMessageKind.REPORT, TxCompose.kindOf("JA1ABC K1ABC -12", "K1ABC"))
        assertEquals(TxMessageKind.ROGER, TxCompose.kindOf("K1ABC JA1ABC R-12", "K1ABC"))
        assertEquals(TxMessageKind.RR73, TxCompose.kindOf("JA1ABC K1ABC RR73", "K1ABC"))
        assertEquals(TxMessageKind.SEVENTY_THREE, TxCompose.kindOf("JA1ABC K1ABC 73", "K1ABC"))
        assertEquals(TxMessageKind.CUSTOM, TxCompose.kindOf("HELLO WORLD DE K1ABC", "K1ABC"))
        assertNull(TxCompose.kindOf("", "K1ABC"))
        assertNull(TxCompose.kindOf(null, "K1ABC"))
    }

    // ---- 信号报告 ----

    @Test
    fun reportComesFromLatestDecodeOfTarget() {
        val msgs = listOf(
            decode("K1ABC JA1ABC -08", snr = -8, df = 1300),
            decode("CQ JA1ABC FN42", snr = -15),
        )
        // messages 为新→旧，取第一个匹配
        assertEquals(-8, TxCompose.reportFor(msgs, "ja1abc"))
        assertEquals(0, TxCompose.reportFor(msgs, "N0CALL"))
        assertEquals(0, TxCompose.reportFor(msgs, null))
    }

    @Test
    fun reportIsClamped() {
        assertEquals(30, TxCompose.reportFor(listOf(decode("CQ JA1ABC FN42", 99)), "JA1ABC"))
        assertEquals(-24, TxCompose.reportFor(listOf(decode("CQ JA1ABC FN42", -99)), "JA1ABC"))
    }

    // ---- CQ 前缀 ----

    @Test
    fun composesCqWithPrefix() {
        assertEquals(
            "CQ DX K1ABC FN42",
            TxCompose.compose(TxMessageKind.CQ, null, "k1abc", "fn42", cqPrefix = "dx"),
        )
    }

    @Test
    fun blankPrefixIsPlainCq() {
        assertEquals("CQ K1ABC FN42", TxCompose.compose(TxMessageKind.CQ, null, "k1abc", "fn42", cqPrefix = "  "))
    }

    @Test
    fun prefixOnlyAffectsCq() {
        assertEquals(
            "JA1ABC K1ABC -07",
            TxCompose.compose(TxMessageKind.REPORT, "ja1abc", "K1ABC", "FN42", -7, cqPrefix = "DX"),
        )
    }

    @Test
    fun defaultCqPrefixesHaveEightSlotsAndFirstIsPlain() {
        assertEquals(8, DEFAULT_CQ_PREFIXES.size)
        assertEquals("", DEFAULT_CQ_PREFIXES.first())
        assertTrue(DEFAULT_CQ_PREFIXES.drop(1).all { it.isNotBlank() })
    }

    // ---- 发射调度 ----

    @Test
    fun sendNowOnlyOnOwnSlotWithEnoughTime() {
        // 我方偶数周期，剩 10s → 立即
        assertTrue(TxScheduler.canSendNow(0, 0, 10_000))
        // 剩正好 2.5s → 立即
        assertTrue(TxScheduler.canSendNow(0, 0, TxScheduler.MIN_SEND_NOW_MS))
        // 剩 2.499s → 排下一周期
        assertFalse(TxScheduler.canSendNow(0, 0, TxScheduler.MIN_SEND_NOW_MS - 1))
        // 非我方周期 → 排下一周期
        assertFalse(TxScheduler.canSendNow(1, 0, 10_000))
    }

    @Test
    fun minSendNowNeedsWholeMessagePlusPreamble() {
        // FT8：12.64 s 报文 + 50 ms 前导
        assertEquals(12_690L, TxScheduler.minSendNowMs(12_640, 50))
        // FT4：5.04 s 报文 + 200 ms 前导
        assertEquals(5_240L, TxScheduler.minSendNowMs(5_040, 200))
        // 报文时长未知 → 兜底 2.5 s；前导为负按 0 处理
        assertEquals(TxScheduler.MIN_SEND_NOW_MS, TxScheduler.minSendNowMs(0))
        assertEquals(TxScheduler.MIN_SEND_NOW_MS, TxScheduler.minSendNowMs(0, -100))

        // FT8 本时隙开头约 1 s（剩 14 s）→ 立即发；剩 10 s 已放不下整条报文 → 排下一周期
        val ft8 = TxScheduler.minSendNowMs(12_640, 50)
        assertTrue(TxScheduler.canSendNow(0, 0, 14_000, ft8))
        assertFalse(TxScheduler.canSendNow(0, 0, 10_000, ft8))
    }

    // ---- 发射途中换目标：就地重发 ----

    /** 我方偶数周期、FT8、前导 300 ms：所需 = 0.3 + 0.5（波形自带保护间隔）+ 12.64 + 0.3（重启余量）= 13.74 s。 */
    private fun ft8RetargetAt(posInSlotMs: Long, txParity: Int = 0): Boolean =
        TxScheduler.canRetargetInSlot(
            nowMs = posInSlotMs,          // 时隙 0 是偶数周期，直接拿 pos 当绝对时间
            slotMs = 15_000,
            txParity = txParity,
            preambleMs = 300,
            messageMs = 12_640,
        )

    @Test
    fun retargetAllowedOnlyAtSlotHead() {
        // 时隙开头 1 s 处：0.3 + 0.5 + 12.64 + 0.3 = 13.74 s，剩 14 s → 够
        assertTrue(ft8RetargetAt(0))
        assertTrue(ft8RetargetAt(1_000))
        // 13.74 + pos > 15 000（pos ≈ 1.26 s 起）→ 不够，照旧等下一个我方周期
        assertFalse(ft8RetargetAt(1_300))
        assertFalse(ft8RetargetAt(5_000))
        assertFalse(ft8RetargetAt(14_000))
    }

    @Test
    fun retargetCountsWaveLeadAndMargin() {
        // 不含重启余量时的边界：0.3 + 0.5 + 12.64 = 13.44 s → pos = 1.56 s 正好卡住
        assertFalse(ft8RetargetAt(1_560))
        assertTrue(
            TxScheduler.canRetargetInSlot(
                nowMs = 1_560, slotMs = 15_000, txParity = 0,
                preambleMs = 300, messageMs = 12_640, marginMs = 0,
            ),
        )
        assertFalse(
            TxScheduler.canRetargetInSlot(
                nowMs = 1_561, slotMs = 15_000, txParity = 0,
                preambleMs = 300, messageMs = 12_640, marginMs = 0,
            ),
        )
        // 余量调大 → 窗口收窄（pos = 1 s 也不够了）
        assertFalse(
            TxScheduler.canRetargetInSlot(
                nowMs = 1_000, slotMs = 15_000, txParity = 0,
                preambleMs = 300, messageMs = 12_640, marginMs = 1_000,
            ),
        )
    }

    @Test
    fun retargetOnlyOnOwnSlot() {
        // 当前处于奇数时隙、我方是偶数周期 → 不能在此时隙就地重发（下一个我方时隙才发）
        assertFalse(ft8RetargetAt(15_000, txParity = 0))
        // 我方是奇数周期 → 奇数时隙开头可以
        assertTrue(ft8RetargetAt(15_000, txParity = 1))
    }

    @Test
    fun retargetRespectsSlotOffsetAndUnknownMessage() {
        // 时隙网格整体推后 2 s：名义 0 s 处还在上一个（奇数）时隙里 → 不是我方周期
        assertFalse(
            TxScheduler.canRetargetInSlot(
                nowMs = 0, slotMs = 15_000, txParity = 0,
                preambleMs = 300, messageMs = 12_640, slotOffsetMs = 2_000,
            ),
        )
        // 推后 2 s 后的时隙起点（名义 2 s）→ 偶数周期开头，够播完
        assertTrue(
            TxScheduler.canRetargetInSlot(
                nowMs = 2_000, slotMs = 15_000, txParity = 0,
                preambleMs = 300, messageMs = 12_640, slotOffsetMs = 2_000,
            ),
        )
        // 偏移 −1 s：名义 2 s 处已是偏移后时隙的第 3 s → 放不下整条报文
        assertFalse(
            TxScheduler.canRetargetInSlot(
                nowMs = 2_000, slotMs = 15_000, txParity = 0,
                preambleMs = 300, messageMs = 12_640, slotOffsetMs = -1_000,
            ),
        )
        // 报文时长未知（0）→ 不就地重发
        assertFalse(
            TxScheduler.canRetargetInSlot(
                nowMs = 0, slotMs = 15_000, txParity = 0, preambleMs = 0, messageMs = 0,
            ),
        )
    }

    @Test
    fun retargetFt4MessageFitsLater() {
        // FT4：时隙 7.5 s、报文 5.04 s、前导 300 ms → 0.3 + 0.5 + 5.04 + 0.3 = 6.14 s（pos ≤ 1.36 s）
        assertTrue(
            TxScheduler.canRetargetInSlot(
                nowMs = 1_000, slotMs = 7_500, txParity = 0,
                preambleMs = 300, messageMs = 5_040,
            ),
        )
        assertFalse(
            TxScheduler.canRetargetInSlot(
                nowMs = 2_000, slotMs = 7_500, txParity = 0,
                preambleMs = 300, messageMs = 5_040,
            ),
        )
    }
}
