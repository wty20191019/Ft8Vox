package com.example.ft8vox

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ft8vox.engine.AudioEngine
import com.example.ft8vox.engine.Ft8Config
import com.example.ft8vox.engine.Ft8Engine
import com.example.ft8vox.engine.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * 阶段 4 的实时音频引擎测试：重采样质量（确定性）、采集生命周期冒烟，
 * 以及真机上的实时采集→解码整时隙验证。
 *
 * 采集类测试在无音频设备的模拟器上会自动跳过（Assume）。
 */
@RunWith(AndroidJUnit4::class)
class AudioEngineTest {

    /** 直接通过 shell 授予麦克风权限，避免 OEM 弹窗阻塞测试。 */
    @Before
    fun grantMicrophone() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation
            .executeShellCommand("pm grant ${instrument.targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}")
            .close()
    }

    /** 48 kHz 的 1 kHz 正弦重采样到 12 kHz 后，长度应约为 1/4、频率仍为 1 kHz。 */
    @Test
    fun resamplerPreservesTone() {
        val inRate = 48000
        val outRate = 12000
        val freq = 1000.0
        val input = FloatArray(inRate) { i -> sin(2.0 * PI * freq * i / inRate).toFloat() }

        val out = AudioEngine.resample(input, inRate, outRate)

        assertTrue("输出长度异常: ${out.size}", out.size in (outRate - 20)..(outRate + 20))

        // 用正向过零率估算频率（每秒正向过零次数 ≈ 信号频率）
        var zeroCrossings = 0
        for (i in 1 until out.size) {
            if (out[i - 1] <= 0f && out[i] > 0f) zeroCrossings++
        }
        val estimated = zeroCrossings.toDouble()
        assertTrue("频率估算异常: $estimated", estimated in (freq * 0.9)..(freq * 1.1))
    }

    /** 采集生命周期：初始化 → 启动 → 取状态 → 停止，不应崩溃。 */
    @Test
    fun captureLifecycleSmoke() {
        AudioEngine.initialize(Ft8Config())
        try {
            val rate = AudioEngine.startCapture(48000)
            Assume.assumeTrue("音频采集不可用（无音频设备/模拟器），跳过", rate > 0)
            Thread.sleep(600)

            val state = AudioEngine.state()
            assertTrue("状态为空", state != null)
            assertEquals("FT8 时隙应为 15000 ms", 15000L, state!!.slotMs)
            assertTrue("采集流未处于运行状态", state.running)

            AudioEngine.stopCapture()
        } finally {
            AudioEngine.release()
        }
    }

    /** FT4 时隙应为 7500 ms。 */
    @Test
    fun ft4SlotLength() {
        AudioEngine.initialize(Ft8Config(protocol = Protocol.FT4))
        try {
            val state = AudioEngine.state()
            assertEquals("FT4 时隙应为 7500 ms", 7500L, state!!.slotMs)
        } finally {
            AudioEngine.release()
        }
    }

    /**
     * 端到端验证实时重采样链路：12 kHz 的 FT8 信号升到 48 kHz（模拟设备采样率）
     * 再降回 12 kHz 后，仍应能被解码器解出。
     */
    @Test
    fun resampledFt8StillDecodes() {
        val message = "CQ F4FSY JN25"
        val pcm12k = Ft8Engine.encode(message, 1000f, Protocol.FT8, 12000)
        val pcm48k = AudioEngine.resample(pcm12k, 12000, 48000)
        val pcm12kBack = AudioEngine.resample(pcm48k, 48000, 12000)

        Ft8Engine.initialize(Ft8Config())
        try {
            Ft8Engine.processAudio(pcm12kBack)
            val messages = Ft8Engine.decode()
            assertTrue("重采样后未能解出 '$message'，实际=$messages", messages.any { it.contains(message) })
        } finally {
            Ft8Engine.release()
        }
    }

    /**
     * 真机实时链路：打开麦克风采集，等待至少完成一个完整时隙的解码周期。
     * 时隙对齐 + 采集满一个时隙最坏约 30 s，故给 45 s 上限。
     */
    @Test
    fun realtimeCaptureCompletesOneSlot() {
        AudioEngine.initialize(Ft8Config())
        try {
            val rate = AudioEngine.startCapture(48000)
            Assume.assumeTrue("音频采集不可用（无音频设备/模拟器），跳过", rate > 0)

            val deadline = System.currentTimeMillis() + 45_000
            var slots = 0L
            while (System.currentTimeMillis() < deadline) {
                val state = AudioEngine.state()
                if (state != null && state.slotsDecoded >= 1) {
                    slots = state.slotsDecoded
                    break
                }
                Thread.sleep(500)
            }
            assertTrue("45 秒内未完成一个时隙的实时解码周期", slots >= 1)
        } finally {
            AudioEngine.stopCapture()
            AudioEngine.release()
        }
    }
}
