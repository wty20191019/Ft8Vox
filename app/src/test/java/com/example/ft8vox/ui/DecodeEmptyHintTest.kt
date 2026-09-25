package com.example.ft8vox.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** 操作页解码列表空态文案的 JVM 单测。 */
class DecodeEmptyHintTest {

    @Test
    fun stoppedReceiverAsksUserToStart() {
        val hint = decodeEmptyHint(ReceiverStatus(running = false), decodedTotal = 0)
        assertTrue(hint.contains("未开始接收"))
    }

    @Test
    fun runningWithoutDecodesWaitsForSignal() {
        val hint = decodeEmptyHint(ReceiverStatus(running = true), decodedTotal = 0)
        assertTrue(hint.contains("等待解码"))
    }

    @Test
    fun filteredOutIsDistinguishedFromNoDecode() {
        val hint = decodeEmptyHint(ReceiverStatus(running = true), decodedTotal = 12)
        assertTrue(hint.contains("筛选"))
    }

    @Test
    fun emptyChipSelectionAsksToPickOne() {
        val hint = decodeEmptyHint(ReceiverStatus(running = true), decodedTotal = 12, filterEmptySelection = true)
        assertTrue(hint.contains("至少"))
    }
}
