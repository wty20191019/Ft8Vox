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
        val cq = q.startCq()
        assertEquals("CQ F4FSY JN25", cq.txText)
        assertEquals(QsoState.WAIT_REPLY, cq.state)
        assertEquals(QsoRole.CALLER, cq.role)

        // 对方应答（带网格）
        val s1 = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ IO90", snr = -11)))
        assertEquals(QsoState.WAIT_REPORT, s1.state)
        assertEquals("GJ0KYZ F4FSY -11", s1.txText)
        assertEquals("GJ0KYZ", s1.theirCall)

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
        val start = q.answer("GJ0KYZ", "IO90")
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
    fun ignoresMessagesNotAddressedToMe() {
        val q = engine()
        q.startCq()
        val s = q.onDecoded(listOf(decoded("K1ABC W9XYZ IO90")))
        assertEquals(QsoState.WAIT_REPLY, s.state)
        assertEquals(1, s.retries)
    }

    @Test
    fun ignoresOwnTransmission() {
        val q = engine()
        q.startCq()
        val s = q.onDecoded(listOf(decoded("K1ABC F4FSY JN25")))
        assertEquals(QsoState.WAIT_REPLY, s.state)
        assertEquals(1, s.retries)
    }

    @Test
    fun givesUpAfterMaxRetries() {
        val q = engine()
        q.startCq()
        var last = q.progress()
        repeat(7) { last = q.onDecoded(emptyList()) }
        assertEquals(QsoState.FAILED, last.state)
        assertNull(last.txText)
        assertFalse(last.active)
    }

    @Test
    fun handlesDirectReportInsteadOfRoger() {
        val q = engine()
        q.startCq()
        q.onDecoded(listOf(decoded("F4FSY GJ0KYZ IO90")))
        // 对方直接发报告（未加 R）
        val s = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ -03")))
        assertEquals(QsoState.WAIT_RR73, s.state)
        assertEquals("GJ0KYZ F4FSY R-03", s.txText)
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
        val s = q.answer("F4FSY", "JN25")
        assertEquals(QsoState.IDLE, s.state)
    }
}
