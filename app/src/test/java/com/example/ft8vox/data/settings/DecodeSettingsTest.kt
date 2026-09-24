package com.example.ft8vox.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解码预设与参数钳制的 JVM 单测。 */
class DecodeSettingsTest {

    @Test
    fun presetFastIsCheaperThanStandard() {
        val standard = DecodeSettings()
        val fast = standard.applyPreset(DecodePreset.FAST)
        assertTrue(fast.timeOsr < standard.timeOsr || fast.freqOsr < standard.freqOsr)
        assertTrue(fast.ldpcIterations < standard.ldpcIterations)
        assertTrue(fast.maxCandidates < standard.maxCandidates)
        assertTrue(fast.minScore >= standard.minScore)
    }

    @Test
    fun presetDeepIsHeavierThanStandard() {
        val standard = DecodeSettings()
        val deep = standard.applyPreset(DecodePreset.DEEP)
        assertTrue(deep.timeOsr >= standard.timeOsr)
        assertTrue(deep.freqOsr >= standard.freqOsr)
        assertTrue(deep.ldpcIterations > standard.ldpcIterations)
        assertTrue(deep.maxCandidates > standard.maxCandidates)
        assertTrue(deep.maxDecoded > standard.maxDecoded)
        assertTrue(deep.minScore <= standard.minScore)
    }

    @Test
    fun presetsDoNotTouchFrequencyRange() {
        val base = DecodeSettings(fMinHz = 300, fMaxHz = 2700)
        for (p in listOf(DecodePreset.FAST, DecodePreset.STANDARD, DecodePreset.DEEP)) {
            val applied = base.applyPreset(p)
            assertEquals(300, applied.fMinHz)
            assertEquals(2700, applied.fMaxHz)
        }
    }

    @Test
    fun customPresetLeavesValuesUnchanged() {
        val custom = DecodeSettings(minScore = 17, ldpcIterations = 33, maxCandidates = 200)
        assertEquals(custom, custom.applyPreset(DecodePreset.CUSTOM))
    }

    @Test
    fun clampingFixesOutOfRangeValues() {
        val wild = DecodeSettings(
            timeOsr = 0,
            freqOsr = 99,
            minScore = -5,
            ldpcIterations = 1000,
            maxCandidates = 1,
            maxDecoded = 9999,
            fMinHz = 10,
            fMaxHz = 99999,
        ).clamped()

        assertEquals(DecodeSettings.TIME_OSR_RANGE.first, wild.timeOsr)
        assertEquals(DecodeSettings.FREQ_OSR_RANGE.last, wild.freqOsr)
        assertEquals(DecodeSettings.MIN_SCORE_RANGE.first, wild.minScore)
        assertEquals(DecodeSettings.LDPC_RANGE.last, wild.ldpcIterations)
        assertEquals(DecodeSettings.MAX_CANDIDATES_RANGE.first, wild.maxCandidates)
        assertEquals(DecodeSettings.MAX_DECODED_RANGE.last, wild.maxDecoded)
        assertEquals(DecodeSettings.F_MIN_RANGE.first, wild.fMinHz)
        assertEquals(DecodeSettings.F_MAX_RANGE.last, wild.fMaxHz)
    }

    @Test
    fun clampingKeepsAtLeastOneHundredHzSpan() {
        val inverted = DecodeSettings(fMinHz = 2000, fMaxHz = 1500).clamped()
        assertTrue(inverted.fMaxHz >= inverted.fMinHz + 100)
    }

    @Test
    fun appSettingsMapToNativeDecodeParams() {
        val s = AppSettings(
            decode = DecodeSettings(
                minScore = 12,
                ldpcIterations = 40,
                maxCandidates = 200,
                maxDecoded = 70,
            ),
        )
        val p = s.decodeParams
        assertEquals(12, p.minScore)
        assertEquals(40, p.ldpcIterations)
        assertEquals(200, p.maxCandidates)
        assertEquals(70, p.maxDecoded)
    }

    @Test
    fun appSettingsClampWhenMappingToNative() {
        // 即使绕过了 clamped()（例如旧数据），映射到 native 时也必须落回安全范围
        val s = AppSettings(decode = DecodeSettings(minScore = 0, maxDecoded = 5000))
        assertEquals(com.example.ft8vox.engine.DecodeParams.MIN_SCORE_RANGE.first, s.decodeParams.minScore)
        assertEquals(com.example.ft8vox.engine.DecodeParams.MAX_DECODED_RANGE.last, s.decodeParams.maxDecoded)
    }
}
