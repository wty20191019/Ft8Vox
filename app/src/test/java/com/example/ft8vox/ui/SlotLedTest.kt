package com.example.ft8vox.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 六格报文槽指示灯（[slotLed]）的 JVM 单测。
 *
 * 实机口径：**发送中的那格红点、待发的那格绿点**，其余熄灭。
 *
 * 判据是**报文序号**（1 网格 / 2 报告 / 3 R报告 / 4 RR73 / 5 73 / 6 CQ），
 * 不再比较报文文本 —— 文本相等属于 UI 与运算层的耦合，一旦两边拼法有出入，
 * 灯就整格不亮（真机现象：在发 `R-07`，`3 R报告` 那格却不亮）。
 */
class SlotLedTest {

    @Test
    fun queuedSlotIsGreen() {
        // 待发 = 下一次要发的那条（还没轮到我方时隙）
        assertEquals(SlotLed.QUEUED, slotLed(kindOrder = 3, queuedOrder = 3, onAirOrder = null))
    }

    @Test
    fun transmittingSlotIsRed() {
        assertEquals(SlotLed.ON_AIR, slotLed(kindOrder = 3, queuedOrder = 3, onAirOrder = 3))
    }

    @Test
    fun onAirBeatsQueuedWhileTransmitting() {
        // 收尾报文（序号 4）已排定，但我这条（序号 3）还在播 → 在播的红、下一格绿
        assertEquals(SlotLed.ON_AIR, slotLed(kindOrder = 3, queuedOrder = 4, onAirOrder = 3))
        assertEquals(SlotLed.QUEUED, slotLed(kindOrder = 4, queuedOrder = 4, onAirOrder = 3))
    }

    @Test
    fun otherSlotsStayOff() {
        // 在播 3、待发 3 → 只有 3 号槽亮，其余熄灭
        assertEquals(SlotLed.OFF, slotLed(kindOrder = 1, queuedOrder = 3, onAirOrder = 3))
        assertEquals(SlotLed.OFF, slotLed(kindOrder = 5, queuedOrder = 3, onAirOrder = 3))
    }

    @Test
    fun noQueuedOrOnAirNeverLights() {
        // 没有待发、也没在发（空闲）→ 六格全灭
        assertEquals(SlotLed.OFF, slotLed(kindOrder = 3, queuedOrder = null, onAirOrder = null))
        // 待发是在播的是**自定义报文**（不属于六步序列，调用方传 null）→ 全灭
        assertEquals(SlotLed.OFF, slotLed(kindOrder = 1, queuedOrder = null, onAirOrder = null))
        assertEquals(SlotLed.OFF, slotLed(kindOrder = 6, queuedOrder = null, onAirOrder = null))
    }

    @Test
    fun cqOrderLightsOnlyCqSlot() {
        // 主叫 CQ = 序号 6 → 只有 6 号槽绿灯，1..5 不亮
        assertEquals(SlotLed.QUEUED, slotLed(kindOrder = 6, queuedOrder = 6, onAirOrder = null))
        assertEquals(SlotLed.OFF, slotLed(kindOrder = 5, queuedOrder = 6, onAirOrder = null))
        assertEquals(SlotLed.OFF, slotLed(kindOrder = 1, queuedOrder = 6, onAirOrder = null))
    }

    @Test
    fun onAirIsIgnoredWhenNotTransmitting() {
        // 非发射中：onAirOrder 传 null（lastTxText 只是「上次发过什么」，不该继续点红灯）
        assertEquals(SlotLed.OFF, slotLed(kindOrder = 2, queuedOrder = null, onAirOrder = null))
    }
}
