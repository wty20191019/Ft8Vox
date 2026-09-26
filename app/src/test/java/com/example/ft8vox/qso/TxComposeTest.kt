package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 发射抽屉纯逻辑（报文构造/宏/队列/调度）的 JVM 单测。 */
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
    fun composesReplyExchangeAndClosings() {
        assertEquals(
            "JA1ABC K1ABC FN42",
            TxCompose.compose(TxMessageKind.REPLY, "ja1abc", "K1ABC", "FN42"),
        )
        assertEquals(
            "JA1ABC K1ABC -12",
            TxCompose.compose(TxMessageKind.EXCHANGE, "JA1ABC", "K1ABC", "FN42", -12),
        )
        assertEquals(
            "JA1ABC K1ABC RR73",
            TxCompose.compose(TxMessageKind.RR73, "JA1ABC", "K1ABC", "FN42"),
        )
        assertEquals(
            "JA1ABC K1ABC 73",
            TxCompose.compose(TxMessageKind.SEVENTY_THREE, "JA1ABC", "K1ABC", "FN42"),
        )
    }

    @Test
    fun directedKindsNeedTarget() {
        assertNull(TxCompose.compose(TxMessageKind.REPLY, null, "K1ABC", "FN42"))
        assertNull(TxCompose.compose(TxMessageKind.EXCHANGE, "", "K1ABC", "FN42"))
        assertNull(TxCompose.compose(TxMessageKind.RR73, null, "K1ABC", "FN42"))
    }

    // ---- 类型识别 ----

    @Test
    fun classifiesMessageKinds() {
        assertEquals(TxMessageKind.CQ, TxCompose.kindOf("CQ K1ABC FN42", "K1ABC"))
        assertEquals(TxMessageKind.REPLY, TxCompose.kindOf("JA1ABC K1ABC FN42", "K1ABC"))
        assertEquals(TxMessageKind.EXCHANGE, TxCompose.kindOf("JA1ABC K1ABC -12", "K1ABC"))
        assertEquals(TxMessageKind.EXCHANGE, TxCompose.kindOf("K1ABC JA1ABC R-12", "K1ABC"))
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

    // ---- 宏展开 ----

    @Test
    fun expandsMacroPlaceholders() {
        val out = TxCompose.expandMacro(
            "{call} {mycall} {report} {mygrid}",
            target = "ja1abc",
            myCall = "k1abc",
            myGrid = "fn42",
            report = -7,
        )
        assertEquals("JA1ABC K1ABC -07 FN42", out)
    }

    @Test
    fun macroKeepsLiteralTextAndCollapsesSpaces() {
        val out = TxCompose.expandMacro("CQ   TEST   {mycall}", null, "k1abc", "", 0)
        assertEquals("CQ TEST K1ABC", out)
    }

    @Test
    fun defaultMacrosAreEightAndExpandable() {
        assertEquals(8, DEFAULT_MACROS.size)
        for (m in DEFAULT_MACROS) {
            val out = TxCompose.expandMacro(m, "JA1ABC", "K1ABC", "FN42", 0)
            assertTrue("宏展开不应为空: $m", out.isNotEmpty())
        }
    }

    // ---- 发送队列 ----

    @Test
    fun queueEnqueueSkipsBlankAndCapsAtMax() {
        var q = emptyList<String>()
        q = TxQueue.enqueue(q, "  ")
        assertTrue(q.isEmpty())

        q = TxQueue.enqueue(q, "A")
        q = TxQueue.enqueue(q, "B")
        assertEquals(listOf("A", "B"), q)

        var big = emptyList<String>()
        repeat(TxQueue.MAX + 5) { big = TxQueue.enqueue(big, "M$it") }
        assertEquals(TxQueue.MAX, big.size)
    }

    @Test
    fun queueRemoveAndMove() {
        val q = listOf("A", "B", "C")
        assertEquals(listOf("A", "C"), TxQueue.removeAt(q, 1))
        assertEquals(q, TxQueue.removeAt(q, 9))

        assertEquals(listOf("B", "A", "C"), TxQueue.move(q, 0, 1))
        assertEquals(listOf("B", "C", "A"), TxQueue.move(q, 0, 2))
        assertEquals(listOf("C", "A", "B"), TxQueue.move(q, 2, 0))
        assertEquals(q, TxQueue.move(q, 0, 0))
        assertEquals(q, TxQueue.move(q, -1, 1))
    }

    @Test
    fun queueLabelFormat() {
        assertEquals("1:JA1ABC/回复", TxQueue.label(0, "JA1ABC K1ABC FN42", "K1ABC"))
        assertEquals("2:K1ABC/CQ", TxQueue.label(1, "CQ K1ABC FN42", "K1ABC"))
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
        // FT4：4.48 s 报文 + 200 ms 前导
        assertEquals(4_680L, TxScheduler.minSendNowMs(4_480, 200))
        // 报文时长未知 → 兜底 2.5 s；前导为负按 0 处理
        assertEquals(TxScheduler.MIN_SEND_NOW_MS, TxScheduler.minSendNowMs(0))
        assertEquals(TxScheduler.MIN_SEND_NOW_MS, TxScheduler.minSendNowMs(0, -100))

        // FT8 本时隙开头约 1 s（剩 14 s）→ 立即发；剩 10 s 已放不下整条报文 → 排下一周期
        val ft8 = TxScheduler.minSendNowMs(12_640, 50)
        assertTrue(TxScheduler.canSendNow(0, 0, 14_000, ft8))
        assertFalse(TxScheduler.canSendNow(0, 0, 10_000, ft8))
    }
}
