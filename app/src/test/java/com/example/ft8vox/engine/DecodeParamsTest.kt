package com.example.ft8vox.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** native 解码参数（DecodeParams）钳制的 JVM 单测。 */
class DecodeParamsTest {

    @Test
    fun defaultsMatchFt8cnFastDecode() {
        // 与设置页「快」预设（DecodeSettings 构造默认值）保持同一口径
        val d = DecodeParams()
        assertEquals(10, d.minScore)
        assertEquals(120, d.maxCandidates)
        assertEquals(20, d.ldpcIterations)
        assertEquals(100, d.maxDecoded)
        assertFalse(d.deep) // 默认快档：单趟不减谱
    }

    @Test
    fun ofClampsNumericFields() {
        val p = DecodeParams.of(
            minScore = -100,
            maxCandidates = 100000,
            ldpcIterations = 0,
            maxDecoded = 1,
            deep = true,
        )
        assertEquals(DecodeParams.MIN_SCORE_RANGE.first, p.minScore)
        assertEquals(DecodeParams.MAX_CANDIDATES_RANGE.last, p.maxCandidates)
        assertEquals(DecodeParams.LDPC_RANGE.first, p.ldpcIterations)
        assertEquals(DecodeParams.MAX_DECODED_RANGE.first, p.maxDecoded)
        assertTrue(p.deep)
    }

    @Test
    fun clampedIsIdempotentForValidValues() {
        val valid = DecodeParams(
            minScore = 8, maxCandidates = 250, ldpcIterations = 50, maxDecoded = 80, deep = true,
        )
        assertEquals(valid, valid.clamped())
    }

    @Test
    fun ofDefaultsToFastWhenDeepOmitted() {
        val p = DecodeParams.of(minScore = 10, maxCandidates = 120, ldpcIterations = 20, maxDecoded = 100)
        assertFalse(p.deep)
    }
}
