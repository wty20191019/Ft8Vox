package com.example.ft8vox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ft8vox.engine.DecodeParams
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.engine.Ft8Config
import com.example.ft8vox.engine.Ft8Engine
import com.example.ft8vox.engine.Protocol
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阶段 7b 的 instrumented 测试：解码参数经 JNI 下发后仍能正确解码。
 *
 * 用自产 FT8 波形离线验证，不依赖音频设备。
 */
@RunWith(AndroidJUnit4::class)
class DecodeParamsInstrumentedTest {

    private val frequencyHz = 1500f
    private val message = "CQ F4FSY JN25"

    private fun decodeWith(params: DecodeParams, hotApply: Boolean = false): List<DecodeResult> {
        val pcm = Ft8Engine.encode(message, frequencyHz, Protocol.FT8, 12000)
        Ft8Engine.initialize(Ft8Config(), if (hotApply) DecodeParams() else params)
        return try {
            if (hotApply) Ft8Engine.setDecodeParams(params)
            Ft8Engine.processAudio(pcm)
            Ft8Engine.decodeDetailed()
        } finally {
            Ft8Engine.release()
        }
    }

    private fun assertDecoded(params: DecodeParams, hotApply: Boolean = false) {
        val results = decodeWith(params, hotApply)
        assertTrue(
            "参数 $params (hot=$hotApply) 下应解出 $message，实际=${results.map { it.text }}",
            results.any { it.text.contains("F4FSY") },
        )
    }

    @Test
    fun decodesWithFastPreset() {
        assertDecoded(DecodeParams.of(minScore = 12, maxCandidates = 80, ldpcIterations = 10, maxDecoded = 30))
    }

    @Test
    fun decodesWithDeepPreset() {
        assertDecoded(DecodeParams.of(minScore = 8, maxCandidates = 250, ldpcIterations = 50, maxDecoded = 80))
    }

    /** 运行中热更新参数（不重建引擎）也要生效且不破坏解码。 */
    @Test
    fun decodesAfterHotParameterUpdate() {
        assertDecoded(
            DecodeParams.of(minScore = 12, maxCandidates = 80, ldpcIterations = 10, maxDecoded = 30),
            hotApply = true,
        )
    }

    /** 单时隙上限应限制返回条数。 */
    @Test
    fun maxDecodedCapsResults() {
        val results = decodeWith(
            DecodeParams.of(minScore = 4, maxCandidates = 500, ldpcIterations = 25, maxDecoded = 5),
        )
        assertTrue("返回条数不应超过 maxDecoded=5，实际=${results.size}", results.size <= 5)
    }
}
