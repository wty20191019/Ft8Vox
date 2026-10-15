package com.example.ft8vox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ft8vox.engine.AudioEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * U7b：VOX 发射路径的设备端回归。
 *
 * 验证 `playTx`（前导 + 波形）与 `playTone` 在真机/模拟器上都能正常打开输出流并写完。
 */
@RunWith(AndroidJUnit4::class)
class VoxTxNativeTest {

    /** 生成一段短单音（12 kHz），用于代替整时隙 PCM 以缩短测试时间。 */
    private fun tone(ms: Int, hz: Int = 1000): FloatArray {
        val n = 12_000 * ms / 1000
        return FloatArray(n) { 0.2f * sin(2.0 * PI * hz * it / 12_000.0).toFloat() }
    }

    @Test
    fun playTxWithPreambleCompletes() {
        AudioEngine.initialize()
        try {
            val rate = AudioEngine.startPlayback(48000)
            assertTrue("startPlayback failed: $rate", rate > 0)

            // 1 s 数据 + 100 ms 静音前导
            val written = AudioEngine.playTx(tone(1000), preambleMs = 100)
            assertTrue("playTx wrote $written frames", written > 0)

            // 写入帧数应至少覆盖数据本身（约 1.15 s × 48 kHz ≈ 55200）
            assertTrue("expected >= 48000 frames, got $written", written >= 48_000)

            // 状态可读，VOX 电平为有限值
            val st = AudioEngine.state()
            assertTrue("state null", st != null)
            assertTrue("voxLevelDb not finite: ${st!!.voxLevelDb}", st.voxLevelDb.isFinite())

            AudioEngine.stopPlayback()
        } finally {
            AudioEngine.release()
        }
    }

    @Test
    fun playTestToneCompletes() {
        AudioEngine.initialize()
        try {
            val rate = AudioEngine.startPlayback(48000)
            assertTrue("startPlayback failed: $rate", rate > 0)
            val written = AudioEngine.playTone(freqHz = 1000, durationMs = 300)
            assertTrue("playTone wrote $written frames", written > 0)
            AudioEngine.stopPlayback()
        } finally {
            AudioEngine.release()
        }
    }
}
