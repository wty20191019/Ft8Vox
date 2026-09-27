package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QSO 状态机的 JVM 单测。 */
class QsoEngineTest {

    private fun decoded(text: String, snr: Int = -10, slotUtcMs: Long = 0L) = DecodeResult(
        text = text,
        snr = snr,
        dt = 0f,
        df = 1000,
        score = 20,
        slotUtcMs = slotUtcMs,
    )

    private fun engine(): QsoEngine = QsoEngine().apply {
        configure("F4FSY", "JN25")
    }

    @Test
    fun callerCompletesFullQso() {
        val q = engine()
        // 主叫 QSO（对方是我方 CQ 的回应者）：我发信号报告
        val cq = q.startCallerQso("GJ0KYZ", "IO90", snr = -11)
        assertEquals(QsoState.WAIT_REPORT, cq.state)
        assertEquals(QsoRole.CALLER, cq.role)
        assertEquals("GJ0KYZ F4FSY -11", cq.txText)
        assertEquals("GJ0KYZ", cq.theirCall)

        // 对方发 R 报告
        val s2 = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ R-05", snr = -9)))
        assertEquals(QsoState.DONE, s2.state)
        assertEquals("GJ0KYZ F4FSY RR73", s2.txText)

        val log = q.consumeCompleted()
        assertNotNull(log)
        assertEquals("GJ0KYZ", log!!.theirCall)
        assertEquals("IO90", log.theirGrid)
        assertEquals(-11, log.reportSent)
        assertEquals(-5, log.reportReceived)
        assertNull("完成后记录只能取一次", q.consumeCompleted())

        // 最后一条 RR73 发完即清空，不再重发
        q.onTransmitted()
        assertNull(q.progress().txText)
    }

    @Test
    fun responderCompletesFullQso() {
        val q = engine()
        val start = q.startResponderQso("GJ0KYZ", "IO90")
        assertEquals("GJ0KYZ F4FSY JN25", start.txText)
        assertEquals(QsoRole.RESPONDER, start.role)

        // 对方给我信号报告
        val s1 = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ -12", snr = -7)))
        assertEquals(QsoState.WAIT_RR73, s1.state)
        assertEquals("GJ0KYZ F4FSY R-07", s1.txText)

        // 对方发 RR73 → 我回 73 并完成
        val s2 = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ RR73")))
        assertEquals(QsoState.DONE, s2.state)
        assertEquals("GJ0KYZ F4FSY 73", s2.txText)

        val log = q.consumeCompleted()
        assertNotNull(log)
        assertEquals(-7, log!!.reportSent)
        assertEquals(-12, log.reportReceived)
    }

    /**
     * 真机回归（2026-09-27）：**收到对方 `RR73` 后必须还能回 `73`**。
     *
     * 真机时序：本时隙的发射在前导提前量到点时就用「已知的旧报文」排定了，而上一时隙的
     * 解码（把状态推进到收尾 `73`）随后才处理。于是「刚发完的」是旧报文，`onTransmitted`
     * 若照旧在 DONE 下清空 `txText`，这条 `73` 就永远发不出去 —— 真机现象：对方给我
     * RR73，我不回 73，下一个时隙反而起 CQ。
     */
    @Test
    fun keepsFinalTextWhenStaleMessageWasTransmitted() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90")
        q.onTransmitted("GJ0KYZ F4FSY JN25") // 网格已发出
        val s1 = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ -12", snr = -7)))
        assertEquals("GJ0KYZ F4FSY R-07", s1.txText)
        q.onTransmitted("GJ0KYZ F4FSY R-07") // R 报告已发出

        // 对方 RR73 到手（本时隙实际在播的仍是上一时隙抢发的 R-07）
        val s2b = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ RR73")))
        assertEquals(QsoState.DONE, s2b.state)
        assertEquals("GJ0KYZ F4FSY 73", s2b.txText)
        assertNotNull(q.consumeCompleted())

        q.onTransmitted("GJ0KYZ F4FSY R-07") // 旧报文发完：不得清掉收尾报文
        assertEquals("收尾报文不能被旧报文的「发完」清掉", "GJ0KYZ F4FSY 73", q.progress().txText)

        q.onTransmitted("GJ0KYZ F4FSY 73") // 73 真正发出后才清空，不再重发
        assertNull(q.progress().txText)
    }

    @Test
    fun cqPhaseWaitsForSchedulerInsteadOfPickingFirst() {
        val q = engine()
        val cq = q.startCq()
        assertTrue("发 CQ 后应处于等回应者阶段", cq.awaitingResponders)
        // CQ 阶段由第 2 层收集/排序回应者：状态机不自行认人，也不计重试
        val s = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ IO90")))
        assertEquals(QsoState.WAIT_REPLY, s.state)
        assertEquals(0, s.noReplyCount)
        assertEquals("CQ F4FSY JN25", s.txText)
    }

    @Test
    fun ignoresMessagesNotAddressedToMe() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90")
        q.onTransmitted() // 已发出网格，等回复
        val s = q.onDecoded(listOf(decoded("K1ABC W9XYZ IO90")))
        assertEquals(QsoState.WAIT_REPLY, s.state)
        assertEquals(1, s.noReplyCount)
    }

    @Test
    fun ignoresOwnTransmission() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90")
        q.onTransmitted() // 已发出网格，等回复
        val s = q.onDecoded(listOf(decoded("K1ABC F4FSY JN25")))
        assertEquals(QsoState.WAIT_REPLY, s.state)
        assertEquals(1, s.noReplyCount)
    }

    @Test
    fun silentSlotsCountNoReplyPerBatch() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90")
        // FT8CN 口径：空批也按批次累计无回应（不要求先发射）
        repeat(5) { q.onDecoded(emptyList()) }
        assertEquals(5, q.progress().noReplyCount)
    }

    @Test
    fun neverFailsWithoutReply() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90")
        var last = q.progress()
        repeat(20) {
            q.onTransmitted() // 每次都实际重发一次
            last = q.onDecoded(emptyList())
        }
        // 引擎不再因重试耗尽而放弃（放弃由第 2 层按 noReplyLimit 决定）
        assertEquals(QsoState.WAIT_REPLY, last.state)
        assertTrue(last.active)
        assertEquals(20, last.noReplyCount)
        assertNotNull(last.txText)
    }

    @Test
    fun handlesDirectReportInsteadOfRoger() {
        val q = engine()
        // 我方发过 CQ、对方用网格回应 → 已发出报告，等对方的 R 报告
        val s0 = q.startCallerQso("GJ0KYZ", "IO90", snr = -11)
        assertEquals(QsoState.WAIT_REPORT, s0.state)
        // 双方同时进入「发报告」阶段：对方发来报告（未加 R）
        val s = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ -03")))
        assertEquals(QsoState.WAIT_RR73, s.state)
        // 回**我自己的**报告（R-11），不回显对方的 -03（那是他测到的我）
        assertEquals("GJ0KYZ F4FSY R-11", s.txText)
    }

    @Test
    fun finishesWhenPartnerRogersWhileWaitingRr73() {
        val q = engine()
        // 双方同时进入「发报告」→ 各自回 R 报告 → 同时停在「等 RR73」：
        // 真机 bug 就是这里两端互相重复 R 报告（R-02 / R-10 每 30 s 交替）。
        val s0 = q.respondToReport("GJ0KYZ", theirReport = -12, snr = -7)
        assertEquals(QsoState.WAIT_RR73, s0.state)
        assertEquals("GJ0KYZ F4FSY R-07", s0.txText)

        // 对方也发来 R 报告（对撞）：应直接回 RR73 收尾并记日志，而不是再回一次 R
        val s = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ R-09", snr = -5)))
        assertEquals(QsoState.DONE, s.state)
        assertEquals("GJ0KYZ F4FSY RR73", s.txText)
        val log = q.consumeCompleted()
        assertNotNull(log)
        assertEquals(-7, log!!.reportSent)
        assertEquals(-12, log.reportReceived)
    }

    @Test
    fun responderFinishesWhenPartnerRogersBeforeReport() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90")
        // 已发网格、还没发报告，却收到对方的 R 报告：按标准 FT8 直接回 RR73 完成
        val s = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ R-04", snr = -7)))
        assertEquals(QsoState.DONE, s.state)
        assertEquals("GJ0KYZ F4FSY RR73", s.txText)
        val log = q.consumeCompleted()
        assertNotNull(log)
        assertEquals(-7, log!!.reportSent)
        assertEquals(-4, log.reportReceived)
    }

    @Test
    fun responderTurnsCallerWhenPartnerAlsoAnswers() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90")
        // 双方同时在应答对方：对方发来「我的呼号 + 他的网格」→ 转主叫，直接发报告
        val s = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ IO90", snr = -9)))
        assertEquals(QsoRole.CALLER, s.role)
        assertEquals(QsoState.WAIT_REPORT, s.state)
        assertEquals("GJ0KYZ F4FSY -09", s.txText)
    }

    @Test
    fun bothAnsweringConvergeWithoutRepeatingReports() {
        // 两端都自动运行、同时应答对方（真机 bug 的最小复现）：交替时隙交换报文，
        // 应在几步内双方都完成并各记一条日志，且空中不出现「重复的 R 报告」。
        val a = QsoEngine().apply { configure("A1AAA", "JN25") }
        val b = QsoEngine().apply { configure("B2BBB", "IO90") }
        a.startResponderQso("B2BBB", "IO90")
        b.startResponderQso("A1AAA", "JN25")
        var aText = a.progress().txText
        var bText = b.progress().txText
        val air = mutableListOf<String>()
        repeat(12) { i ->
            if (i % 2 == 0) {
                aText?.let { t ->
                    air += t
                    b.onDecoded(listOf(decoded(t, snr = -10)))
                    a.onTransmitted()
                }
                bText = b.progress().txText
            } else {
                bText?.let { t ->
                    air += t
                    a.onDecoded(listOf(decoded(t, snr = -10)))
                    b.onTransmitted()
                }
                aText = a.progress().txText
            }
        }
        assertEquals(QsoState.DONE, a.progress().state)
        assertEquals(QsoState.DONE, b.progress().state)
        assertNotNull("A 应记入一条通联", a.consumeCompleted())
        assertNotNull("B 应记入一条通联", b.consumeCompleted())
        // 同一条报文不得重复出现（死循环特征：R 报告反复重发）
        assertEquals("空中不应出现重复报文：$air", air.size, air.distinct().size)
    }

    @Test
    fun stopResetsState() {
        val q = engine()
        q.startCq()
        val s = q.stop()
        assertEquals(QsoState.IDLE, s.state)
        assertNull(s.txText)
        assertFalse(s.active)
    }

    @Test
    fun requiresConfiguredCallsign() {
        val q = QsoEngine()
        assertFalse(q.canOperate)
        val failed = runCatching { q.startCq() }.isFailure
        assertTrue("未配置呼号时不应能开始 CQ", failed)
    }

    @Test
    fun answerRejectsOwnCall() {
        val q = engine()
        val s = q.startResponderQso("F4FSY", "JN25")
        assertEquals(QsoState.IDLE, s.state)
    }

    @Test
    fun startCallerQsoRespondsToDirectedCallAndCompletes() {
        // 对方主动呼叫我方：<myCall> <theirCall> <grid> → 我直接发报告
        val q = engine()
        val start = q.startCallerQso("GJ0KYZ", "IO90", snr = -11)
        assertEquals(QsoRole.CALLER, start.role)
        assertEquals(QsoState.WAIT_REPORT, start.state)
        assertEquals("GJ0KYZ F4FSY -11", start.txText)

        // 对方 Roger → 回 RR73 并完成
        val s = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ R-05", snr = -9)))
        assertEquals(QsoState.DONE, s.state)
        assertEquals("GJ0KYZ F4FSY RR73", s.txText)

        val log = q.consumeCompleted()
        assertNotNull(log)
        assertEquals("GJ0KYZ", log!!.theirCall)
        assertEquals("IO90", log.theirGrid)
        assertEquals(-11, log.reportSent)
        assertEquals(-5, log.reportReceived)
    }

    @Test
    fun respondToRogerCompletesImmediately() {
        val q = engine()
        val s = q.respondToRoger("GJ0KYZ", theirReport = -12, snr = -7, utcMs = 30_000L)
        assertEquals(QsoRole.RESPONDER, s.role)
        assertEquals(QsoState.DONE, s.state)
        assertEquals("GJ0KYZ F4FSY RR73", s.txText)
        assertFalse(s.active)

        val log = q.consumeCompleted()
        assertNotNull(log)
        assertEquals(-7, log!!.reportSent)
        assertEquals(-12, log.reportReceived)
        assertEquals(30_000L, log.utcMs)
    }

    @Test
    fun respondToReportWaitsForRr73() {
        val q = engine()
        val start = q.respondToReport("GJ0KYZ", theirReport = -12, snr = -7)
        assertEquals(QsoState.WAIT_RR73, start.state)
        assertEquals("GJ0KYZ F4FSY R-07", start.txText)

        val s = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ RR73")))
        assertEquals(QsoState.DONE, s.state)
    }

    // ---- 死循环复现与收敛（真机 bug：-08 / R-10 每 30 s 交替） ----

    @Test
    fun alreadySentRRecoversWhenPartnerKeepsSendingReport() {
        // 我已回过 R（WAIT_RR73），对方却还在发纯报告（说明对方没收到我的 R）。
        // 旧逻辑：纯报告不匹配任何转移 → 一直重发 R 直到重试耗尽 → 第 2 层重启 → 死循环。
        // 新逻辑：直接回 RR73 收尾（两颗报告其实已互换），打破死循环。
        val q = engine()
        val s0 = q.respondToReport("GJ0KYZ", theirReport = -8, snr = -7)
        assertEquals(QsoState.WAIT_RR73, s0.state)
        assertEquals("GJ0KYZ F4FSY R-07", s0.txText)

        val s1 = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ -08", snr = -10, slotUtcMs = 30_000L)))
        assertEquals(QsoState.DONE, s1.state)
        assertEquals("GJ0KYZ F4FSY RR73", s1.txText)

        val log = q.consumeCompleted()
        assertNotNull(log)
        assertEquals(-7, log!!.reportSent)
        assertEquals(-8, log.reportReceived)
        assertEquals(30_000L, log.utcMs)
    }

    @Test
    fun recordsStartTimeFromQsoBegin() {
        val q = engine()
        // 起始时间来自 start* 传入的 UTC；完成时间取报文时隙
        q.startResponderQso("GJ0KYZ", "IO90", utcMs = 1_000L)
        q.onDecoded(listOf(decoded("F4FSY GJ0KYZ -08", snr = -10, slotUtcMs = 31_000L)))
        q.onDecoded(listOf(decoded("F4FSY GJ0KYZ R-05", snr = -9, slotUtcMs = 46_000L)))
        val log = q.consumeCompleted()
        assertNotNull(log)
        assertEquals(1_000L, log!!.startUtcMs)
        assertEquals(46_000L, log.utcMs)
    }

    @Test
    fun fallsBackToEndTimeWhenStartUnknown() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90") // 不给起始时间
        q.onDecoded(listOf(decoded("F4FSY GJ0KYZ -08", snr = -10, slotUtcMs = 31_000L)))
        q.onDecoded(listOf(decoded("F4FSY GJ0KYZ R-05", snr = -9, slotUtcMs = 46_000L)))
        val log = q.consumeCompleted()
        assertNotNull(log)
        assertEquals(log!!.utcMs, log.startUtcMs)
    }

    @Test
    fun asymmetricCollisionConvergesWithoutDeadlock() {
        // 真机截图场景的最小复现：A 已回过 R（等 RR73），B 仍在发纯报告（等 R），
        // 且 A 的 R 一直没被 B 收到。双方必须在有限步内各自完成并只记一条日志。
        val a = QsoEngine().apply { configure("A1AAA", "JN25") }
        val b = QsoEngine().apply { configure("B2BBB", "IO90") }
        a.respondToReport("B2BBB", theirReport = -8, snr = -10) // A → B2BBB A1AAA R-10
        b.startCallerQso("A1AAA", "IO90", snr = -8)             // B → A1AAA B2BBB -08

        val air = mutableListOf<String>()
        // 只让 B → A 的报文成功送达（模拟 A 的 R 一直丢），A 也把自己的收尾发出去
        var rounds = 0
        while (rounds < 8 && (a.progress().active || b.progress().active)) {
            rounds++
            b.progress().txText?.let { t ->
                air += t
                a.onDecoded(listOf(decoded(t, snr = -10)))
                b.onTransmitted()
            }
            a.progress().txText?.let { t ->
                air += t
                b.onDecoded(listOf(decoded(t, snr = -10)))
                a.onTransmitted()
            }
        }
        assertEquals(QsoState.DONE, a.progress().state)
        assertEquals(QsoState.DONE, b.progress().state)
        assertNotNull("A 应记一条通联", a.consumeCompleted())
        assertNotNull("B 应记一条通联", b.consumeCompleted())
        assertEquals("空中不应出现重复报文：$air", air.size, air.distinct().size)
    }

    @Test
    fun staleGridAfterReportIsIgnoredButReportRefreshes() {
        // 我已发出报告（等 R）后，对方又（重复/滞后地）发来网格：不应退回「发报告」阶段；
        // 但报告值按「每次重测最新」刷新为本次解码的 SNR（默认 -10）。
        val q = engine()
        q.startCallerQso("GJ0KYZ", "IO90", snr = -11)
        val s = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ IO90")))
        assertEquals(QsoState.WAIT_REPORT, s.state)
        assertEquals("GJ0KYZ F4FSY -10", s.txText)
    }

    // ---- 六步指令序列（FT8CN functionOrder） ----

    @Test
    fun exposesSixStepOrder() {
        val q = engine()
        assertEquals(0, q.progress().order)                             // 空闲
        assertEquals(6, q.startCq().order)                              // 6=CQ
        q.stop()
        assertEquals(1, q.startResponderQso("GJ0KYZ", "IO90").order)    // 1=网格
        q.stop()
        assertEquals(2, q.startCallerQso("GJ0KYZ", "IO90", -11).order)  // 2=报告
        q.stop()
        assertEquals(3, q.respondToReport("GJ0KYZ", -12, -7).order)     // 3=R报告
        q.stop()
        assertEquals(4, q.respondToRoger("GJ0KYZ", -12, -7).order)      // 4=RR73（随即完成）
    }

    @Test
    fun noReplyCountAccumulatesPerDecodeBatchAndResetsOnReply() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90")
        // 连续三个批次都无法推进 → 批次计数 +3（FT8CN 口径，不要求先发射）
        repeat(3) { q.onDecoded(emptyList()) }
        assertEquals(3, q.progress().noReplyCount)
        // 收到有效回复 → 清零
        q.onTransmitted()
        val s = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ -12", snr = -7)))
        assertEquals(0, s.noReplyCount)
        assertEquals(QsoState.WAIT_RR73, s.state)
    }

    @Test
    fun reportRefreshesToLatestSnrButRReusesTransmittedTx2Value() {
        val q = engine()
        val s0 = q.startCallerQso("GJ0KYZ", "IO90", snr = -11)
        assertEquals("GJ0KYZ F4FSY -11", s0.txText)
        // 未推进时用最新 SNR 刷新（-18）
        val s1 = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ IO90", snr = -18)))
        assertEquals("GJ0KYZ F4FSY -18", s1.txText)
        // 实际发出 Tx2（快照 -18）
        q.onTransmitted()
        // 对方 R 报告（本批 SNR -9）：回 RR73 完成，落库 reportSent 用 Tx2 的 -18（不是 -9）
        val s2 = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ R-05", snr = -9)))
        assertEquals(QsoState.DONE, s2.state)
        val log = q.consumeCompleted()
        assertNotNull(log)
        assertEquals(-18, log!!.reportSent)
        assertEquals(-5, log.reportReceived)
    }

    @Test
    fun advancedFlagTrueOnlyWhenTargetReplies() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90")
        // 空批 / 别人呼叫我方 → 未推进
        assertFalse(q.onDecoded(emptyList()).advanced)
        assertFalse(q.onDecoded(listOf(decoded("F4FSY DL1ABC -12"))).advanced)
        // 目标有效回复 → 推进，且无回应计数清零
        val s = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ -12", snr = -7)))
        assertTrue(s.advanced)
        assertEquals(0, s.noReplyCount)
    }

    @Test
    fun advancedFlagTrueOnCompletion() {
        val q = engine()
        q.startCallerQso("GJ0KYZ", "IO90", snr = -11)
        assertTrue(q.onDecoded(listOf(decoded("F4FSY GJ0KYZ R-05"))).advanced)
    }
}
