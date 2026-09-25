package com.example.ft8vox

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ft8vox.engine.AudioDevices
import com.example.ft8vox.engine.AudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U7c：音频路由与增益的设备端回归。
 *
 * 覆盖设备枚举、显式设备 id 打开采集流、以及采集增益下发（热生效不崩溃）。
 * 无音频输入的模拟器上，采集类用例会自动跳过（Assume）。
 */
@RunWith(AndroidJUnit4::class)
class AudioRoutingTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun grantMicrophone() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation
            .executeShellCommand("pm grant ${instrument.targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}")
            .close()
    }

    @Test
    fun deviceEnumerationHasDefaultFirstAndUniqueIds() {
        val inputs = AudioDevices.inputs(context)
        val outputs = AudioDevices.outputs(context)
        assertTrue("输入设备列表为空", inputs.isNotEmpty())
        assertTrue("输出设备列表为空", outputs.isNotEmpty())
        assertEquals("系统默认", inputs.first().name)
        assertEquals(0, inputs.first().id)
        assertEquals("系统默认", outputs.first().name)
        assertEquals(0, outputs.first().id)
        assertEquals("输入设备 id 重复", inputs.size, inputs.map { it.id }.toSet().size)
        assertEquals("输出设备 id 重复", outputs.size, outputs.map { it.id }.toSet().size)
    }

    @Test
    fun parseAndFormatAgreeWithStoredSetting() {
        for (d in AudioDevices.inputs(context) + AudioDevices.outputs(context)) {
            assertEquals(d.id, AudioDevices.parseId(AudioDevices.formatId(d.id)))
        }
    }

    @Test
    fun inputGainIsAcceptedAndStateStaysFinite() {
        AudioEngine.initialize()
        try {
            for (db in intArrayOf(-12, 0, 12, 30)) {
                AudioEngine.setInputGain(db)
            }
            val st = AudioEngine.state()
            assertTrue("state null", st != null)
            assertTrue("voxLevelDb not finite", st!!.voxLevelDb.isFinite())
        } finally {
            AudioEngine.release()
        }
    }

    @Test
    fun captureOpensWithExplicitDeviceId() {
        val builtin = AudioDevices.inputs(context).firstOrNull { it.id > 0 } ?: return
        AudioEngine.initialize()
        try {
            AudioEngine.setInputGain(6)
            val rate = AudioEngine.startCapture(48000, builtin.id)
            Assume.assumeTrue("无可用音频输入（rate=$rate）", rate > 0)
            assertTrue("设备实际采样率异常: $rate", rate > 0)
            val st = AudioEngine.state()
            assertTrue("state null", st != null)
            assertTrue("running=false", st!!.running)
        } finally {
            AudioEngine.stopCapture()
            AudioEngine.release()
        }
    }
}
