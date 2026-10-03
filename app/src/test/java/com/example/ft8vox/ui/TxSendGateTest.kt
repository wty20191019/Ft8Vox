package com.example.ft8vox.ui

import com.example.ft8vox.qso.QsoProgress
import com.example.ft8vox.qso.QsoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一次性发射（自定义报文框的「发送」/ 长按解码行「逐条发送」）的闸门逻辑单测
 * （`docs/Ft8Vox.md`）。
 *
 * 两个纯函数各管一半：
 * - [txBlockReason]：**现在能不能发**，不能发时给出红字原因（以前只写进没人显示的 `status` 字段，
 *   真机表现为「点发送没反应」）。
 * - [qsoStillNeedsTx]：「自定义报文插一条」发完之后要不要继续保持发射武装——
 *   QSO 还没走完（含收尾 RR73/73 待发）就必须保留，否则原 QSO 会被悄悄掐断。
 */
class TxSendGateTest {

    // ---- txBlockReason ----

    @Test
    fun noCallIsRejected() {
        assertEquals("请先到设置填写呼号与网格", txBlockReason(myCall = "", txEnabled = true))
    }

    @Test
    fun noCallWinsOverReceiveOnly() {
        // 两样都不满足时报「没填呼号」（先解决前置条件）
        assertEquals("请先到设置填写呼号与网格", txBlockReason(myCall = "", txEnabled = false))
    }

    @Test
    fun receiveOnlyIsRejected() {
        assertEquals(
            "只接收：先打开信息头右上角的「发射」开关",
            txBlockReason(myCall = "BG7ZJW", txEnabled = false),
        )
    }

    @Test
    fun callAndSwitchOnAllowsSending() {
        assertNull(txBlockReason(myCall = "BG7ZJW", txEnabled = true))
    }

    // ---- qsoStillNeedsTx ----

    @Test
    fun idleQsoNeedsNothing() {
        assertFalse(qsoStillNeedsTx(QsoProgress()))
    }

    @Test
    fun activeQsoNeedsItsNextMessage() {
        assertTrue(
            qsoStillNeedsTx(
                QsoProgress(
                    state = QsoState.WAIT_REPLY,
                    theirCall = "JA1ABC",
                    txText = "JA1ABC BG7ZJW -11",
                ),
            ),
        )
    }

    @Test
    fun doneWithFinalMessageNotYetSentStillNeedsTx() {
        // 已 DONE 但 RR73/73 还没播出去：必须保持武装，否则收尾报文永远发不出去
        assertTrue(
            qsoStillNeedsTx(
                QsoProgress(state = QsoState.DONE, theirCall = "JA1ABC", txText = "JA1ABC BG7ZJW RR73"),
            ),
        )
    }

    @Test
    fun doneWithoutFinalMessageNeedsNothing() {
        assertFalse(qsoStillNeedsTx(QsoProgress(state = QsoState.DONE, theirCall = "JA1ABC")))
    }

    @Test
    fun failedWithoutMessageNeedsNothing() {
        assertFalse(qsoStillNeedsTx(QsoProgress(state = QsoState.FAILED, theirCall = "JA1ABC")))
    }
}
