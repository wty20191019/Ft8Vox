package com.example.ft8vox.ui

import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.qso.QsoProgress
import com.example.ft8vox.qso.QsoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 前台服务通知文案（纯函数 `serviceStatusText`）的 JVM 单测，阶段 9 后台保活。 */
class SessionServiceTextTest {

    @Test
    fun notRunningFallsBackToStopped() {
        assertEquals("已停止", serviceStatusText(ReceiverStatus(running = false)))
    }

    @Test
    fun receivingShowsProtocolBandAndDecodeCount() {
        val text = serviceStatusText(
            ReceiverStatus(
                running = true,
                protocol = Protocol.FT8,
                band = "20m",
                decodedTotal = 12,
            ),
        )
        assertEquals("接收中 · FT8 · 20m · 解码 12", text)
    }

    @Test
    fun transmittingTakesPrecedenceOverReceiving() {
        val text = serviceStatusText(
            ReceiverStatus(
                running = true,
                txing = true,
                protocol = Protocol.FT4,
                band = "40m",
                decodedTotal = 3,
            ),
        )
        assertEquals("发射中 · FT4 · 40m · 解码 3", text)
    }

    @Test
    fun activeQsoAppendsTheirCall() {
        val text = serviceStatusText(
            ReceiverStatus(
                running = true,
                protocol = Protocol.FT8,
                band = "20m",
                decodedTotal = 1,
                qso = QsoProgress(state = QsoState.WAIT_REPLY, theirCall = "JA1XYZ"),
            ),
        )
        assertTrue(text, text.endsWith("· JA1XYZ"))
    }

    @Test
    fun finishedQsoDoesNotAppendCall() {
        val text = serviceStatusText(
            ReceiverStatus(
                running = true,
                protocol = Protocol.FT8,
                band = "20m",
                decodedTotal = 1,
                qso = QsoProgress(state = QsoState.DONE, theirCall = "JA1XYZ"),
            ),
        )
        assertEquals("接收中 · FT8 · 20m · 解码 1", text)
    }
}
