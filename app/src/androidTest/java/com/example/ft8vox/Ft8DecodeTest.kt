package com.example.ft8vox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ft8vox.engine.Ft8Config
import com.example.ft8vox.engine.Ft8Engine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 阶段 2 的 WAV 离线解码回归测试。
 *
 * 样本取自 ft8_lib/test/wav/（12 kHz、单声道、16-bit、15 秒），
 * 期望报文见同目录下的 .txt。
 */
@RunWith(AndroidJUnit4::class)
class Ft8DecodeTest {

    /** 读取 androidTest/assets 下的 PCM WAV，返回 [-1,1] 的 float 采样。 */
    private fun loadWavAsset(name: String): FloatArray {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open(name).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        require(bb.getInt(0) == 0x46464952) { "Not a RIFF file: $name" } // "RIFF"

        var pos = 12
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = bb.getInt(pos)
            val size = bb.getInt(pos + 4)
            if (id == 0x61746164) { // "data"
                dataOffset = pos + 8
                dataSize = size
                break
            }
            pos += 8 + size + (size and 1)
        }
        require(dataOffset >= 0) { "No data chunk in $name" }

        val n = dataSize / 2 // 16-bit mono
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = bb.getShort(dataOffset + i * 2) / 32768f
        }
        return out
    }

    private fun decodeAsset(name: String): List<String> {
        val pcm = loadWavAsset(name)
        Ft8Engine.initialize(Ft8Config())
        return try {
            Ft8Engine.processAudio(pcm)
            Ft8Engine.decode()
        } finally {
            Ft8Engine.release()
        }
    }

    @Test
    fun decodesSingleSlotMessages() {
        val messages = decodeAsset("191111_110145.wav")
        assertTrue("expected 'GJ0KYZ RK9AX MO05', got=$messages", messages.any { it.contains("GJ0KYZ RK9AX MO05") })
        assertTrue("expected 'RY8CAA', got=$messages", messages.any { it.contains("RY8CAA") })
    }

    @Test
    fun decodesBusySlotMessages() {
        val messages = decodeAsset("191111_110615.wav")
        val expected = listOf("VK4BLE OH8JK R-17", "CQ F4FSY JN25", "JR5MJS OH8NW 73")
        for (message in expected) {
            assertTrue("expected '$message', got=$messages", messages.any { it.contains(message) })
        }
    }
}
