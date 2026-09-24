package com.example.ft8vox.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** native 解码参数（DecodeParams）钳制的 JVM 单测。 */
class DecodeParamsTest {

    @Test
    fun defaultsMatchFt8LibSample() {
        val d = DecodeParams()
        assertEquals(10, d.minScore)
        assertEquals(140, d.maxCandidates)
        assertEquals(25, d.ldpcIterations)
        assertEquals(50, d.maxDecoded)
    }

    @Test
    fun ofClampsEveryField() {
        val p = DecodeParams.of(
            minScore = -100,
            maxCandidates = 100000,
            ldpcIterations = 0,
            maxDecoded = 1,
        )
        assertEquals(DecodeParams.MIN_SCORE_RANGE.first, p.minScore)
        assertEquals(DecodeParams.MAX_CANDIDATES_RANGE.last, p.maxCandidates)
        assertEquals(DecodeParams.LDPC_RANGE.first, p.ldpcIterations)
        assertEquals(DecodeParams.MAX_DECODED_RANGE.first, p.maxDecoded)
    }

    @Test
    fun clampedIsIdempotentForValidValues() {
        val valid = DecodeParams(minScore = 8, maxCandidates = 250, ldpcIterations = 50, maxDecoded = 80)
        assertEquals(valid, valid.clamped())
    }
}
