package com.example.ft8vox.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解码预设与参数钳制的 JVM 单测。 */
class DecodeSettingsTest {

    @Test
    fun fastPresetIsExactlyTheDefaultSettings() {
        // 「快」＝构造函数默认值：两处不许漂移；值照搬 FT8CN「快速解码」（迭代 20 / 候选 120 / 上限 100）
        val applied = DecodeSettings(ldpcIterations = 40, maxCandidates = 200, maxDecoded = 90, passes = 3)
            .applyPreset(DecodePreset.FAST)
        assertEquals(DecodeSettings(), applied)
        assertEquals(20, applied.ldpcIterations)
        assertEquals(120, applied.maxCandidates)
        assertEquals(100, applied.maxDecoded)
        assertEquals(2, applied.passes) // SIC 趟数也归「快」预设管，默认 2
    }

    @Test
    fun defaultSettingsIsTheFastPreset() {
        val s = AppSettings()
        assertEquals(DecodePreset.FAST, s.decodePreset)
        assertEquals(DecodeSettings(), s.decode)
    }

    @Test
    fun decodePresetIsDerivedFromValues() {
        // 手改任一项 → 自定义；只改频率范围（不随预设变化）→ 仍是「快」
        assertEquals(
            DecodePreset.CUSTOM,
            AppSettings(decode = DecodeSettings(minScore = 9)).decodePreset,
        )
        assertEquals(
            DecodePreset.CUSTOM,
            AppSettings(decode = DecodeSettings(maxDecoded = 90)).decodePreset,
        )
        // SIC 趟数也是预设管辖项：手改成 3 趟就算「自定义」
        assertEquals(
            DecodePreset.CUSTOM,
            AppSettings(decode = DecodeSettings(passes = 3)).decodePreset,
        )
        assertEquals(
            DecodePreset.FAST,
            AppSettings(decode = DecodeSettings(fMinHz = 300, fMaxHz = 2700)).decodePreset,
        )
    }

    @Test
    fun waterfallThresholdDefaultsAreMinus90ToMinus40() {
        val s = AppSettings()
        assertEquals(-90, s.waterfallFloorDb)
        assertEquals(50, s.waterfallRangeDb)
        assertEquals(-90, WATERFALL_FLOOR_DB_DEFAULT)
        assertEquals(50, WATERFALL_RANGE_DB_DEFAULT)
        // 默认窗口：-90 ~ -40 dBFS
        assertEquals(-40, s.waterfallFloorDb + s.waterfallRangeDb)
    }

    @Test
    fun waterfallThresholdClampsToAllowedRange() {
        assertEquals(WATERFALL_FLOOR_DB_RANGE.first, clampWaterfallFloorDb(-999))
        assertEquals(WATERFALL_FLOOR_DB_RANGE.last, clampWaterfallFloorDb(999))
        assertEquals(WATERFALL_RANGE_DB_RANGE.first, clampWaterfallRangeDb(-999))
        assertEquals(WATERFALL_RANGE_DB_RANGE.last, clampWaterfallRangeDb(999))
    }

    @Test
    fun presetsDoNotTouchFrequencyRange() {
        val base = DecodeSettings(fMinHz = 300, fMaxHz = 2700)
        for (p in DecodePreset.entries) {
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
            passes = 99,
            fMinHz = 10,
            fMaxHz = 99999,
        ).clamped()

        assertEquals(DecodeSettings.TIME_OSR_RANGE.first, wild.timeOsr)
        assertEquals(DecodeSettings.FREQ_OSR_RANGE.last, wild.freqOsr)
        assertEquals(DecodeSettings.MIN_SCORE_RANGE.first, wild.minScore)
        assertEquals(DecodeSettings.LDPC_RANGE.last, wild.ldpcIterations)
        assertEquals(DecodeSettings.MAX_CANDIDATES_RANGE.first, wild.maxCandidates)
        assertEquals(DecodeSettings.MAX_DECODED_RANGE.last, wild.maxDecoded)
        assertEquals(DecodeSettings.PASSES_RANGE.last, wild.passes)
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
                passes = 3,
            ),
        )
        val p = s.decodeParams
        assertEquals(12, p.minScore)
        assertEquals(40, p.ldpcIterations)
        assertEquals(200, p.maxCandidates)
        assertEquals(70, p.maxDecoded)
        assertEquals(3, p.passes)
    }

    @Test
    fun appSettingsClampWhenMappingToNative() {
        // 即使绕过了 clamped()（例如旧数据），映射到 native 时也必须落回安全范围
        val s = AppSettings(decode = DecodeSettings(minScore = 0, maxDecoded = 5000))
        assertEquals(com.example.ft8vox.engine.DecodeParams.MIN_SCORE_RANGE.first, s.decodeParams.minScore)
        assertEquals(com.example.ft8vox.engine.DecodeParams.MAX_DECODED_RANGE.last, s.decodeParams.maxDecoded)
    }
}
