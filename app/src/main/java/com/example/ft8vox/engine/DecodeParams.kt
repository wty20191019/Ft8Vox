package com.example.ft8vox.engine

/**
 * 解码器调优参数（对应 native 的 `ftx_decode_params_t`）。
 *
 * 与 [Ft8Config] 不同：这些参数**不改变 STFT/monitor 结构**，可在接收运行中热更新
 * （见 [AudioEngine.setDecodeParams] / [Ft8Engine.setDecodeParams]）；而 [Ft8Config] 里的
 * 频率范围与 OSR 需要重建引擎。
 *
 * 默认值＝设置的「快」预设，照搬 FT8CN「快速解码」（10 / 120 / 20 / 100、快档）；
 * 该默认只在设置下发前的极短窗口内生效，正常运行时用的是设置页的值。
 */
data class DecodeParams(
    /** Costas 同步最低得分，越高候选越少、越快，漏解风险越大。 */
    val minScore: Int = 10,
    /** 单时隙候选上限。 */
    val maxCandidates: Int = 120,
    /** 快档 LDPC 最大迭代次数，越高越慢但弱信号解码率更高。 */
    val ldpcIterations: Int = 20,
    /** 单时隙最多解出的报文条数。 */
    val maxDecoded: Int = 100,
    /**
     * 深度解码开关（照 FT8CN `setDecodeMode(isDeep)`）。
     *
     * - `false`（默认）＝快：只跑一趟（快档迭代），不做减谱；
     * - `true`＝深：快跑后再用高迭代深跑一趟，随后把已解报文从瀑布幅度上抹零、
     *   在残留谱上反复重解，直到不再出新解或超时（native 里 7 s）。
     */
    val deep: Boolean = false,
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
            deep: Boolean = false,
        ): DecodeParams = DecodeParams(
            minScore = minScore.coerceIn(MIN_SCORE_RANGE),
            maxCandidates = maxCandidates.coerceIn(MAX_CANDIDATES_RANGE),
            ldpcIterations = ldpcIterations.coerceIn(LDPC_RANGE),
            maxDecoded = maxDecoded.coerceIn(MAX_DECODED_RANGE),
            deep = deep,
        )
    }

    /** 钳制到安全范围。 */
    fun clamped(): DecodeParams =
        of(minScore, maxCandidates, ldpcIterations, maxDecoded, deep)
}
