package com.example.ft8vox.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** U7c：音频设备 id 的字符串编解码与显示文本（纯逻辑部分）。 */
class AudioDevicesTest {

    @Test
    fun parseIdDefaultsToZero() {
        assertEquals(0, AudioDevices.parseId(""))
        assertEquals(0, AudioDevices.parseId("   "))
        assertEquals(0, AudioDevices.parseId("abc"))
        assertEquals(0, AudioDevices.parseId("-5"))
        assertEquals(0, AudioDevices.parseId("0"))
        assertEquals(42, AudioDevices.parseId("42"))
        assertEquals(42, AudioDevices.parseId(" 42 "))
    }

    @Test
    fun formatIdRoundTrips() {
        assertEquals("", AudioDevices.formatId(0))
        assertEquals("", AudioDevices.formatId(-1))
        assertEquals("7", AudioDevices.formatId(7))
        assertEquals(7, AudioDevices.parseId(AudioDevices.formatId(7)))
    }

    @Test
    fun labelFallsBackToDefault() {
        val list = listOf(
            AudioDevices.DEFAULT,
            AudioDevice(7, "USB Audio", "USB 设备"),
        )
        assertEquals("系统默认", AudioDevices.label(list, ""))
        assertEquals("系统默认", AudioDevices.label(list, "999"))
        assertEquals("USB Audio · USB 设备", AudioDevices.label(list, "7"))
    }

    @Test
    fun labelOfDefaultIsNameOnly() {
        assertEquals("系统默认", AudioDevices.label(listOf(AudioDevices.DEFAULT), "0"))
    }
}
