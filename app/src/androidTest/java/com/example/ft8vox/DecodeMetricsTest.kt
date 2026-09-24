package com.example.ft8vox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ft8vox.engine.Ft8Config
import com.example.ft8vox.engine.Ft8Engine
import com.example.ft8vox.engine.Protocol
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 阶段 5 的确定性测试：waterfall 频率轴、解码指标（SNR/DT/DF）。
 *
 * 全部用自产 FT8 波形离线验证，不依赖音频设备。
 */
@RunWith(AndroidJUnit4::class)
class DecodeMetricsTest {

    private val frequencyHz = 1000f
    private val message = "CQ F4FSY JN25"

    private fun encode(scale: Float = 1f, noise: Float = 0f): FloatArray {
        val pcm = Ft8Engine.encode(message, frequencyHz, Protocol.FT8, 12000)
        if (scale == 1f && noise == 0f) return pcm
        val rng = Random(1234)
        return FloatArray(pcm.size) { i ->
            pcm[i] * scale + ((rng.nextFloat() * 2f - 1f) * noise)
        }
    }

    /** 返回解码结果中匹配目标报文的那一条。 */
    private fun decodeFirst(pcm: FloatArray) = com.example.ft8vox.engine.DecodeResult::class.let {
        Ft8Engine.initialize(Ft8Config())
        try {
            Ft8Engine.processAudio(pcm)
            Ft8Engine.decodeDetailed().firstOrNull { r -> r.text.contains(message) }
        } finally {
            Ft8Engine.release()
        }
    }

    /** waterfall 的频率轴应覆盖 200..3000 Hz，bin 宽 6.25 Hz，且信号能量集中在 1000 Hz 附近。 */
    @Test
    fun waterfallAxisAndPeak() {
        val pcm = encode()
        Ft8Engine.initialize(Ft8Config())
        try {
            Ft8Engine.processAudio(pcm)

            val info = Ft8Engine.waterfallInfo()!!
            assertTrue("fMin 应约为 200 Hz，实际=${info.fMinHz}", abs(info.fMinHz - 200f) < 8f)
            assertTrue("bin 宽应约为 6.25 Hz，实际=${info.binHz}", abs(info.binHz - 6.25f) < 0.2f)

            val data = Ft8Engine.pollWaterfall(256)
            val bins = info.bins
            val rows = data.size / bins
            assertTrue("waterfall 行数过少: $rows", rows > 100)

            // 逐列累加能量
            val colEnergy = LongArray(bins)
            for (r in 0 until rows) {
                val base = r * bins
                for (b in 0 until bins) {
                    colEnergy[b] += (data[base + b].toInt() and 0xFF).toLong()
                }
            }

            val expectedBin = ((frequencyHz - info.fMinHz) / info.binHz).roundToInt()
            val inBand = (expectedBin..expectedBin + 8).sumOf { colEnergy[it].toDouble() }
            val total = colEnergy.sumOf { it.toDouble() }
            val inAvg = inBand / 9.0
            val outAvg = (total - inBand) / (bins - 9).toDouble()
            assertTrue(
                "信号带内均值($inAvg) 应显著高于带外($outAvg)，peakExpectedBin=$expectedBin",
                inAvg > outAvg * 5.0,
            )
        } finally {
            Ft8Engine.release()
        }
    }

    /** 解码指标：DF 应接近 1000 Hz，DT 应接近 0（减去 0.5 s 名义起点）。 */
    @Test
    fun decodeMetricsAreSane() {
        val result = decodeFirst(encode())!!
        assertTrue("DF 应接近 1000 Hz，实际=${result.df}", abs(result.df - 1000) <= 15)
        assertTrue("DT 应接近 0 s，实际=${result.dt}", abs(result.dt) <= 0.15f)
        assertTrue("score 应为正，实际=${result.score}", result.score > 0)
        assertTrue("SNR 应在合理范围，实际=${result.snr}", result.snr in -30..60)
    }

    /** 叠加同样噪声时，信号增强 12 dB 应让估计 SNR 明显上升。 */
    @Test
    fun snrTracksSignalStrength() {
        val noise = 0.05f
        val loud = decodeFirst(encode(scale = 1.0f, noise = noise))!!
        val quiet = decodeFirst(encode(scale = 0.25f, noise = noise))!!

        val delta = loud.snr - quiet.snr
        assertTrue(
            "SNR 应随信号增强而上升（loud=${loud.snr}, quiet=${quiet.snr}）",
            delta > 4,
        )
    }
}
