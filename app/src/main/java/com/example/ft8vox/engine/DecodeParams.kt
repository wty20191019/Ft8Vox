package com.example.ft8vox.engine

/**
 * 解码器调优参数（对应 native 的 `ftx_decode_params_t`）。
 *
 * 与 [Ft8Config] 不同：这些参数**不改变 STFT/monitor 结构**，可在接收运行中热更新
 * （见 [AudioEngine.setDecodeParams] / [Ft8Engine.setDecodeParams]）；而 [Ft8Config] 里的
 * 频率范围与 OSR 需要重建引擎。
 *
 * 默认值与 ft8_lib 官方示例 `demo/decode_ft8.c` 一致。
 */
data class DecodeParams(
    /** Costas 同步最低得分，越高候选越少、越快，漏解风险越大。 */
    val minScore: Int = 10,
    /** 单时隙候选上限。 */
    val maxCandidates: Int = 140,
    /** LDPC 最大迭代次数，越高越慢但弱信号解码率更高。 */
    val ldpcIterations: Int = 25,
    /** 单时隙最多解出的报文条数。 */
    val maxDecoded: Int = 50,
) {
    /** 各字段的安全范围（与 native `sanitize_decode_params` 对应）。 */
    companion object {
        val MIN_SCORE_RANGE = 4..40
        val MAX_CANDIDATES_RANGE = 20..500
        val LDPC_RANGE = 5..60
        val MAX_DECODED_RANGE = 5..100

        /** 钳制到安全范围，避免越界值传到 native。 */
        fun of(
            minScore: Int,
            maxCandidates: Int,
            ldpcIterations: Int,
            maxDecoded: Int,
        ): DecodeParams = DecodeParams(
            minScore = minScore.coerceIn(MIN_SCORE_RANGE),
            maxCandidates = maxCandidates.coerceIn(MAX_CANDIDATES_RANGE),
            ldpcIterations = ldpcIterations.coerceIn(LDPC_RANGE),
            maxDecoded = maxDecoded.coerceIn(MAX_DECODED_RANGE),
        )
    }

    /** 钳制到安全范围。 */
    fun clamped(): DecodeParams = of(minScore, maxCandidates, ldpcIterations, maxDecoded)
}
