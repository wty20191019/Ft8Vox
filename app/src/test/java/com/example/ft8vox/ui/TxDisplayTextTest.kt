package com.example.ft8vox.ui

import com.example.ft8vox.qso.QsoProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 顶栏 / 抽屉「发射报文」文案（[ReceiverStatus.pendingTxText] / [ReceiverStatus.displayTxText]）的单测。
 *
 * 关键语义：**发射中固定显示本条实际在播的报文**，发射途中改目标或 QSO 推进（`qso.txText` 变化）
 * 都不允许让屏幕上的文案变，否则显示的会与真正在天上的报文不符。
 */
class TxDisplayTextTest {

    private fun status(
        txing: Boolean = false,
        lastTxText: String? = null,
        manualTxText: String? = null,
        qsoTxText: String? = null,
    ) = ReceiverStatus(
        txing = txing,
        lastTxText = lastTxText,
        manualTxText = manualTxText,
        qso = QsoProgress(txText = qsoTxText),
    )

    @Test
    fun pendingPrefersManualOneShotOverEnginePlan() {
        val st = status(manualTxText = "CQ BG7ZJW OM89", qsoTxText = "GJ0KYZ BG7ZJW -11")
        assertEquals("CQ BG7ZJW OM89", st.pendingTxText)
    }

    @Test
    fun pendingFallsBackToEnginePlan() {
        val st = status(qsoTxText = "GJ0KYZ BG7ZJW -11")
        assertEquals("GJ0KYZ BG7ZJW -11", st.pendingTxText)
    }

    @Test
    fun pendingIsNullWhenBlank() {
        assertNull(status(manualTxText = "  ", qsoTxText = "").pendingTxText)
        assertNull(status().pendingTxText)
    }

    @Test
    fun displayFreezesToOnAirMessageWhileTransmitting() {
        // 发射中 qso.txText 已被推进成下一帧（RR73），但屏幕上必须还是当前在播的 R 报告
        val st = status(
            txing = true,
            lastTxText = "GJ0KYZ BG7ZJW R-11",
            qsoTxText = "GJ0KYZ BG7ZJW RR73",
        )
        assertEquals("GJ0KYZ BG7ZJW R-11", st.displayTxText)
    }

    @Test
    fun displayFallsBackToPendingWhenTransmittingWithoutLast() {
        val st = status(txing = true, manualTxText = "CQ BG7ZJW OM89")
        assertEquals("CQ BG7ZJW OM89", st.displayTxText)
    }

    @Test
    fun displayShowsNextPlanWhenIdleEvenIfLastExists() {
        val st = status(lastTxText = "GJ0KYZ BG7ZJW R-11", qsoTxText = "GJ0KYZ BG7ZJW RR73")
        assertEquals("GJ0KYZ BG7ZJW RR73", st.displayTxText)
    }

    @Test
    fun displayIsNullWhenNothingPlanned() {
        assertNull(status(lastTxText = "GJ0KYZ BG7ZJW R-11").displayTxText)
    }
}
