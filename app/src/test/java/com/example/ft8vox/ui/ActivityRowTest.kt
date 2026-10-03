package com.example.ft8vox.ui

import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.qso.DecodeStyle
import com.example.ft8vox.qso.HighlightRole
import com.example.ft8vox.qso.ParsedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接收列表两类行（接收解码 / 我方发射）混排的 JVM 单测（docs/Ft8Vox.md）。
 *
 * 口径：**按同一时间轴（slotUtcMs 新→旧）混排**，两类**共用一个条数上限**（一起淘汰最旧的）。
 */
class ActivityRowTest {

    private fun rx(text: String, slot: Long): DecodeRow = DecodeRow(
        msg = DecodeResult(text = text, snr = -10, dt = 0f, df = 1000, score = 10, slotUtcMs = slot),
        parsed = ParsedMessage(raw = text),
        style = DecodeStyle(role = HighlightRole.NORMAL),
    )

    private fun tx(id: Long, text: String, slot: Long, outcome: TxOutcome = TxOutcome.DONE) =
        TxRecord(id = id, text = text, slotUtcMs = slot, outcome = outcome)

    @Test
    fun mixesRxAndTxOnOneTimeline() {
        // 接收（新→旧）与我方发射（新→旧）按 slotUtcMs 交错
        val out = mergeActivity(
            rx = listOf(rx("A", 30_000), rx("B", 10_000)),
            tx = listOf(tx(2, "T2", 45_000), tx(1, "T1", 15_000)),
        )
        assertEquals(listOf(45_000L, 30_000L, 15_000L, 10_000L), out.map { it.slotUtcMs })
        assertTrue(out[0] is ActivityRow.Tx)
        assertTrue(out[1] is ActivityRow.Rx)
        assertTrue(out[2] is ActivityRow.Tx)
        assertTrue(out[3] is ActivityRow.Rx)
    }

    @Test
    fun sharesOneLimitAcrossBothKinds() {
        // 上限是两类共用的：截断时只留最新的 limit 行，更旧的（不分来源）被淘汰
        val rx = (0 until 5).map { rx("R$it", (it + 1) * 1000L) }          // 1000,2000,3000,4000,5000
        val tx = (0 until 5).map { tx(it.toLong(), "T$it", (it + 1) * 1000L + 500) } // 1500..5500
        val out = mergeActivity(rx, tx, limit = 3)
        assertEquals(3, out.size)
        assertEquals(listOf(5500L, 5000L, 4500L), out.map { it.slotUtcMs })
        assertTrue(out[0] is ActivityRow.Tx)
        assertTrue(out[1] is ActivityRow.Rx)
        assertTrue(out[2] is ActivityRow.Tx)
    }

    @Test
    fun stableWithinSameSlotRxBeforeTx() {
        // 同一时隙：保持传入顺序（接收解码在前、TX 在后），不因排序抖动
        val out = mergeActivity(rx = listOf(rx("A", 5000)), tx = listOf(tx(1, "T", 5000)))
        assertTrue(out[0] is ActivityRow.Rx)
        assertTrue(out[1] is ActivityRow.Tx)
    }

    @Test
    fun txNewerThanAllRxGoesFirst() {
        val out = mergeActivity(rx = listOf(rx("A", 1000)), tx = listOf(tx(1, "T", 9000)))
        assertTrue(out[0] is ActivityRow.Tx)
        assertTrue(out[1] is ActivityRow.Rx)
    }

    @Test
    fun abortedOutcomeIsCarried() {
        // 「未发完」的标注靠 outcome 带到界面
        val out = mergeActivity(emptyList(), listOf(tx(1, "T", 1000, TxOutcome.ABORTED)))
        assertEquals(TxOutcome.ABORTED, (out[0] as ActivityRow.Tx).rec.outcome)
    }

    @Test
    fun keysAreStableAndDistinctAcrossKinds() {
        val a = ActivityRow.Rx(rx("A", 5000))
        val b = ActivityRow.Tx(tx(1, "A", 5000))
        assertTrue(a.key != b.key)
        assertEquals("tx:1", b.key)
    }
}
