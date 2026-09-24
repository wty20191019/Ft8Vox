package com.example.ft8vox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ft8vox.engine.Ft8Config
import com.example.ft8vox.engine.Ft8Engine
import com.example.ft8vox.engine.Protocol
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阶段 3 的自回环测试：用编码器生成发射 PCM，再交给解码器解出，验证文本往返一致。
 *
 * 覆盖 FT8 / FT4 两种协议，以及 CQ、通联、73、自由文本等常见报文。
 */
@RunWith(AndroidJUnit4::class)
class Ft8EncodeTest {

    private val frequencyHz = 1000f

    /** 编码 → 解码 一个时隙，返回解码结果。 */
    private fun selfLoop(protocol: Protocol, text: String): List<String> {
        val config = Ft8Config(protocol = protocol)
        val pcm = Ft8Engine.encode(text, frequencyHz, protocol, config.sampleRate)

        Ft8Engine.initialize(config)
        return try {
            Ft8Engine.processAudio(pcm)
            Ft8Engine.decode()
        } finally {
            Ft8Engine.release()
        }
    }

    private fun assertRoundTrip(protocol: Protocol, text: String) {
        val messages = selfLoop(protocol, text)
        assertTrue(
            "[$protocol] 期望回环解出 '$text'，实际=$messages",
            messages.any { it.contains(text) },
        )
    }

    @Test
    fun ft8RoundTrip() {
        val messages = listOf(
            "CQ F4FSY JN25",
            "GJ0KYZ RK9AX MO05",
            "VK4BLE OH8JK R-17",
            "JR5MJS OH8NW 73",
            "HI HI HI HI", // 自由文本（4 token 会走 free text 编码路径）
        )
        for (message in messages) {
            assertRoundTrip(Protocol.FT8, message)
        }
    }

    @Test
    fun ft4RoundTrip() {
        val messages = listOf(
            "CQ F4FSY JN25",
            "GJ0KYZ RK9AX MO05",
            "VK4BLE OH8JK R-17",
            "HI HI HI HI",
        )
        for (message in messages) {
            assertRoundTrip(Protocol.FT4, message)
        }
    }

    @Test
    fun rejectsUnencodableText() {
        var thrown = false
        try {
            Ft8Engine.encode("this message is far too long to be encoded", frequencyHz)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("超长/非法报文应抛出 IllegalArgumentException", thrown)
    }
}
