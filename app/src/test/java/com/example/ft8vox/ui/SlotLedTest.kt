package com.example.ft8vox.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 六格报文槽指示灯（[slotLed]）的 JVM 单测。
 *
 * 实机口径：**发送中的那格红点、待发的那格绿点**，其余熄灭。
 */
class SlotLedTest {

    private val report = "GJ0KYZ BG7ZJW -11"
    private val rreport = "GJ0KYZ BG7ZJW R-11"
    private val rr73 = "GJ0KYZ BG7ZJW RR73"

    @Test
    fun pendingSlotIsQueuedGreen() {
        // 待发 = 下一次要发的那条（还没轮到我方时隙）
        assertEquals(
            SlotLed.QUEUED,
            slotLed(report, onAirText = null, pendingText = report, txing = false),
        )
    }

    @Test
    fun transmittingSlotIsOnAirRed() {
        assertEquals(
            SlotLed.ON_AIR,
            slotLed(report, onAirText = report, pendingText = report, txing = true),
        )
    }

    @Test
    fun onAirBeatsQueuedWhileTransmitting() {
        // 收尾报文已排定（下一格待发），但我这条还在播 → 在播的红、下一格绿
        assertEquals(
            SlotLed.ON_AIR,
            slotLed(rreport, onAirText = rreport, pendingText = rr73, txing = true),
        )
        assertEquals(
            SlotLed.QUEUED,
            slotLed(rr73, onAirText = rreport, pendingText = rr73, txing = true),
        )
    }

    @Test
    fun otherSlotsStayOff() {
        val other = "BG7ZJW BG7ZJW IO90"
        assertEquals(SlotLed.OFF, slotLed(other, onAirText = report, pendingText = report, txing = false))
        assertEquals(SlotLed.OFF, slotLed(other, onAirText = report, pendingText = report, txing = true))
    }

    @Test
    fun emptySlotNeverLights() {
        assertEquals(SlotLed.OFF, slotLed(null, onAirText = null, pendingText = null, txing = false))
        // 该步还没有报文（如未设目标时的报告）→ 即使正在发射也不点灯
        assertEquals(SlotLed.OFF, slotLed(null, onAirText = report, pendingText = report, txing = true))
    }

    @Test
    fun onAirIsIgnoredWhenNotTransmitting() {
        // 非发射中：lastTxText 只是「上次发过什么」，不该继续点红/绿灯
        assertEquals(
            SlotLed.OFF,
            slotLed(report, onAirText = report, pendingText = null, txing = false),
        )
    }
}
