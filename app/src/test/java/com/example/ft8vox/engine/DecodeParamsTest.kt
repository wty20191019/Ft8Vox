package com.example.ft8vox.engine

import org.junit.Assert.assertEquals
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
        assertEquals(2, d.passes) // SIC 默认 2 趟
    }

    @Test
    fun ofClampsEveryField() {
        val p = DecodeParams.of(
            minScore = -100,
            maxCandidates = 100000,
            ldpcIterations = 0,
            maxDecoded = 1,
            passes = 0,
        )
        assertEquals(DecodeParams.MIN_SCORE_RANGE.first, p.minScore)
        assertEquals(DecodeParams.MAX_CANDIDATES_RANGE.last, p.maxCandidates)
        assertEquals(DecodeParams.LDPC_RANGE.first, p.ldpcIterations)
        assertEquals(DecodeParams.MAX_DECODED_RANGE.first, p.maxDecoded)
        assertEquals(DecodeParams.PASSES_RANGE.first, p.passes)
    }

    @Test
    fun clampedIsIdempotentForValidValues() {
        val valid = DecodeParams(
            minScore = 8, maxCandidates = 250, ldpcIterations = 50, maxDecoded = 80, passes = 3,
        )
        assertEquals(valid, valid.clamped())
    }

    // ---- 解码并行度（与 native `ft8_decode_slot` 的分支规则同一口径） ----

    @Test
    fun parallelismSplitsBandsAndThreadsByCores() {
        // 8 核 + 默认 100–3000 Hz：段数 min(核数,4)=4，线程 min(核数,8)=8 → 每段 2
        val eight = decodeParallelism(fMinHz = 100, fMaxHz = 3000, cores = 8)
        assertEquals(4, eight.bands)
        assertEquals(2, eight.threadsPerBand)
        assertEquals(8, eight.totalThreads)
        assertEquals("4段×2", eight.shortLabel)
        assertEquals("4 段 × 2 线程 = 8", eight.label)
        // 2 核：段 2、线程 2 → 每段 1
        val two = decodeParallelism(100, 3000, cores = 2)
        assertEquals(2, two.bands)
        assertEquals(1, two.threadsPerBand)
        // 单核：不分段，线程预算整份用在候选并行上
        val one = decodeParallelism(100, 3000, cores = 1)
        assertEquals(1, one.bands)
        assertEquals(1, one.threadsPerBand)
        assertEquals("1线程", one.shortLabel)
        assertEquals("1 段 × 1 线程", one.label)
    }

    @Test
    fun parallelismFallsBackToSingleBandOnNarrowSpan() {
        // 单段最小 200 Hz：400 Hz 跨度 → 2 段；300 Hz → 1 段（不分段）
        assertEquals(2, decodeParallelism(100, 500, cores = 8).bands)
        val narrow = decodeParallelism(100, 400, cores = 8)
        assertEquals(1, narrow.bands)
        assertEquals(8, narrow.totalThreads)
        assertEquals("8线程", narrow.shortLabel)
        assertEquals("1 段 × 8 线程", narrow.label)
        // 非法（上界不大于下界）也退回单段
        assertEquals(1, decodeParallelism(500, 100, cores = 8).bands)
        assertEquals(1, decodeParallelism(500, 501, cores = 8).bands)
    }

    @Test
    fun candidateCapIsSplitAcrossBands() {
        // 「候选上限」是**整个频谱**的总名额：8 核 4 段时每段拿到 1/4
        val p = decodeParallelism(100, 3000, cores = 8)
        assertEquals(30, p.candidatesPerBand(120))
        assertEquals(125, p.candidatesPerBand(500))
        // 不分段时整份都用上
        assertEquals(120, decodeParallelism(100, 400, cores = 8).candidatesPerBand(120))
        // 名额不足段数时每段至少 1，不出现 0
        assertEquals(1, p.candidatesPerBand(3))
    }
}
