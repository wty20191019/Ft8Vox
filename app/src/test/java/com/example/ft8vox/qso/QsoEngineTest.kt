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

    private fun engine(): QsoEngine = QsoEngine(maxRetries = 6).apply {
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

    @Test
    fun cqPhaseWaitsForSchedulerInsteadOfPickingFirst() {
        val q = engine()
        val cq = q.startCq()
        assertTrue("发 CQ 后应处于等回应者阶段", cq.awaitingResponders)
        // CQ 阶段由第 2 层收集/排序回应者：状态机不自行认人，也不计重试
        val s = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ IO90")))
        assertEquals(QsoState.WAIT_REPLY, s.state)
        assertEquals(0, s.retries)
        assertEquals("CQ F4FSY JN25", s.txText)
    }

    @Test
    fun ignoresMessagesNotAddressedToMe() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90")
        val s = q.onDecoded(listOf(decoded("K1ABC W9XYZ IO90")))
        assertEquals(QsoState.WAIT_REPLY, s.state)
        assertEquals(1, s.retries)
    }

    @Test
    fun ignoresOwnTransmission() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90")
        val s = q.onDecoded(listOf(decoded("K1ABC F4FSY JN25")))
        assertEquals(QsoState.WAIT_REPLY, s.state)
        assertEquals(1, s.retries)
    }

    @Test
    fun givesUpAfterMaxRetries() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90")
        var last = q.progress()
        repeat(7) { last = q.onDecoded(emptyList()) }
        assertEquals(QsoState.FAILED, last.state)
        assertNull(last.txText)
        assertFalse(last.active)
    }

    @Test
    fun neverGivesUpWhenRetryMechanismDisabled() {
        val q = engine()
        q.configure("F4FSY", "JN25", maxRetries = 3, giveUp = false)
        q.startResponderQso("GJ0KYZ", "IO90")
        var last = q.progress()
        repeat(20) { last = q.onDecoded(emptyList()) }
        assertEquals(QsoState.WAIT_REPLY, last.state)
        assertTrue(last.active)
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
        val a = QsoEngine(maxRetries = 6).apply { configure("A1AAA", "JN25") }
        val b = QsoEngine(maxRetries = 6).apply { configure("B2BBB", "IO90") }
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
}
