package com.example.ft8vox.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** 操作页解码列表空态文案的 JVM 单测。 */
class DecodeEmptyHintTest {

    @Test
    fun stoppedReceiverAsksUserToStart() {
        val hint = decodeEmptyHint(ReceiverStatus(running = false), decodedTotal = 0)
        assertTrue(hint.contains("开始接收"))
    }

    @Test
    fun runningWithoutDecodesWaitsForSignal() {
        val hint = decodeEmptyHint(ReceiverStatus(running = true), decodedTotal = 0)
        assertTrue(hint.contains("暂无解码"))
    }

    @Test
    fun filteredOutIsDistinguishedFromNoDecode() {
        val hint = decodeEmptyHint(ReceiverStatus(running = true), decodedTotal = 12)
        assertTrue(hint.contains("过滤"))
    }
}
