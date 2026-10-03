package com.example.ft8vox.engine

/**
 * 解码器调优参数（对应 native 的 `ftx_decode_params_t`）。
 *
 * 与 [Ft8Config] 不同：这些参数**不改变 STFT/monitor 结构**，可在接收运行中热更新
 * （见 [AudioEngine.setDecodeParams] / [Ft8Engine.setDecodeParams]）；而 [Ft8Config] 里的
 * 频率范围与 OSR 需要重建引擎。
 *
 * 默认值＝设置的「快」预设，照搬 FT8CN「快速解码」（10 / 120 / 20 / 100）；
 * 该默认只在设置下发前的极短窗口内生效，正常运行时用的是设置页的值。
 */
data class DecodeParams(
    /** Costas 同步最低得分，越高候选越少、越快，漏解风险越大。 */
    val minScore: Int = 10,
    /** 单时隙候选上限。 */
    val maxCandidates: Int = 120,
    /** LDPC 最大迭代次数，越高越慢但弱信号解码率更高。 */
    val ldpcIterations: Int = 20,
    /** 单时隙最多解出的报文条数。 */
    val maxDecoded: Int = 100,
    /**
     * 多趟减谱重解（SIC）趟数：1 = 单趟（不做减谱重解），2 = 默认。
     *
     * 见 docs/Ft8Vox.md：解完一趟后把已解报文在瀑布幅度上抹掉、再搜一趟，
     * 把被强信号压住的弱信号挖出来；趟数每加一趟解码耗时约合再乘一次。
     */
    val passes: Int = 2,
) {
    /** 各字段的安全范围（与 native `sanitize_decode_params` 对应）。 */
    companion object {
        val MIN_SCORE_RANGE = 4..40
        val MAX_CANDIDATES_RANGE = 20..500
        val LDPC_RANGE = 5..60
        val MAX_DECODED_RANGE = 5..100
        /** SIC 趟数上与 native 的 `K_MAX_DECODE_PASSES` 一致。 */
        val PASSES_RANGE = 1..4

        /** 钳制到安全范围，避免越界值传到 native。 */
        fun of(
            minScore: Int,
            maxCandidates: Int,
            ldpcIterations: Int,
            maxDecoded: Int,
            passes: Int = 2,
        ): DecodeParams = DecodeParams(
            minScore = minScore.coerceIn(MIN_SCORE_RANGE),
            maxCandidates = maxCandidates.coerceIn(MAX_CANDIDATES_RANGE),
            ldpcIterations = ldpcIterations.coerceIn(LDPC_RANGE),
            maxDecoded = maxDecoded.coerceIn(MAX_DECODED_RANGE),
            passes = passes.coerceIn(PASSES_RANGE),
        )
    }

    /** 钳制到安全范围。 */
    fun clamped(): DecodeParams =
        of(minScore, maxCandidates, ldpcIterations, maxDecoded, passes)
}

/**
 * 分段并行解码的硬件自适应参数。
 *
 * 段数 = min(核数, 4)：每段独立做一次完整 STFT，段数越多 FFT 重复开销越大，
 * 实测 4 段在 4 核上召回不降、耗时约降 1.3×；核更多时靠「每段多线程」补足并行度。
 * 线程总数 = min(核数, 8)：由 native 按 `线程总数 / 段数` 均分到各段（每段至少 1）。
 */
internal fun decodeBandsFor(cores: Int): Int = cores.coerceIn(1, 4)

internal fun decodeThreadsFor(cores: Int): Int = cores.coerceIn(1, 8)
