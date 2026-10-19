package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话级显式状态 [QsoPhase] 的转移单测（`docs/QsoStateMachine.md`）。
 *
 * [QsoEngineTest] 覆盖的是「一段 QSO 怎么走完」；本文件专门钉住**会话阶段**这一个对外契约，
 * 即 `SessionViewModel.pollOnce` 用来分支的那个值：空闲 / 主叫 / 跑 QSO / 收尾待发。
 */
class QsoPhaseTest {

    private fun decoded(text: String, snr: Int = -10) = DecodeResult(
        text = text,
        snr = snr,
        dt = 0f,
        df = 1000,
        score = 20,
        slotUtcMs = 0L,
    )

    private fun engine(noReplyLimit: Int = 0): QsoEngine = QsoEngine().apply {
        configure("F4FSY", "JN25", noReplyLimit)
    }

    // ---- IDLE ----

    @Test
    fun idleByDefault() {
        val q = engine()
        assertEquals(QsoPhase.IDLE, q.phase)
        assertFalse(q.progress().finishing)
    }

    // ---- CQ：主叫中 ----

    @Test
    fun callingCqIsCqPhase() {
        val q = engine()
        q.startCq(utcMs = 1_000L, cqPrefix = "")
        assertEquals(QsoPhase.CQ, q.phase)
        assertTrue(q.progress().awaitingResponders)
    }

    // ---- QSO：一段定向 QSO 进行中 ----

    @Test
    fun answeringIsQsoPhase() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90", utcMs = 1_000L, snr = -11)
        assertEquals(QsoPhase.QSO, q.phase)
    }

    @Test
    fun advancingKeepsQsoPhase() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90", utcMs = 1_000L, snr = -11) // 我方序号 1
        val p = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ -07")), 2_000L)  // 对方报告(2) → 我方 R 报告(3)
        assertEquals(3, p.order)
        assertEquals(QsoPhase.QSO, q.phase)
    }

    @Test
    fun rr73SentStillQsoUntilFinalHeard() {
        // 序号 4（已发 RR73、等对方 73）不是收尾阶段：还没到「结束但收尾待发」
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90", utcMs = 1_000L, snr = -11)
        val p = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ R-07")), 2_000L) // 对方 R → 我方序号 4
        assertEquals(4, p.order)
        assertEquals(QsoState.ROGERS, p.state)
        assertEquals(QsoPhase.QSO, q.phase)
        assertFalse(q.progress().finishing)
    }

    // ---- FINISHING：QSO 已判定结束、收尾报文还没真正发出 ----

    @Test
    fun finalPendingIsFinishing() {
        // 对方给我 RR73 → 我回 73（我方序号 5）：状态 DONE，但 73 还没真正发出去
        val q = engine()
        q.respondToRr73("GJ0KYZ", snr = -11, utcMs = 1_000L)
        assertEquals(QsoPhase.FINISHING, q.phase)
        assertTrue(q.progress().finishing)
        assertNotNull(q.progress().txText)
    }

    @Test
    fun transmittingFinalLeavesFinishing() {
        val q = engine()
        q.respondToRr73("GJ0KYZ", snr = -11, utcMs = 1_000L)
        val pending = q.progress().txText
        q.onTransmitted(pending, utcMs = 15_000L)
        assertEquals(QsoPhase.IDLE, q.phase)
        assertFalse(q.progress().finishing)
    }

    // ---- 收尾（无待发报文）→ 直接回 IDLE ----

    @Test
    fun receivingFinal73EndsQso() {
        val q = engine()
        q.startCallerQso("GJ0KYZ", "IO90", snr = -11, utcMs = 1_000L) // 我方序号 2
        val p = q.onDecoded(listOf(decoded("F4FSY GJ0KYZ 73")), 2_000L) // 对方 73 → 完成
        assertEquals(QsoState.SIGNOFF, p.state)
        assertEquals(QsoPhase.IDLE, q.phase)
    }

    @Test
    fun noReplyGiveUpEndsQso() {
        val q = engine(noReplyLimit = 2)
        q.startResponderQso("GJ0KYZ", "IO90", utcMs = 1_000L, snr = -11)
        q.onDecoded(emptyList(), 2_000L, countNoReply = true)
        q.onDecoded(emptyList(), 3_000L, countNoReply = true)
        // 判据在自增之前比较：前两批把计数推到 2，第三批的开头才判定超限 → 兜底收尾
        q.onDecoded(emptyList(), 4_000L, countNoReply = true)
        val p = q.progress()
        assertEquals(QsoState.SIGNOFF, p.state)
        assertTrue(p.gaveUp)
        assertEquals(QsoPhase.IDLE, q.phase)
    }

    @Test
    fun stopReturnsToIdle() {
        val q = engine()
        q.startResponderQso("GJ0KYZ", "IO90", utcMs = 1_000L, snr = -11)
        q.stop()
        assertEquals(QsoPhase.IDLE, q.phase)
    }
}
