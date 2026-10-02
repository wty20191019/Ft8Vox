package com.example.ft8vox.engine

/**
 * 单条解码结果（对应 native 的 `ftx_decode_result_t`）。
 *
 * 注意：字段顺序与类型必须与 native `jni_common.h` 中的构造签名
 * `(Ljava/lang/String;IFIIJ)V` 保持一致。
 */
data class DecodeResult(
    /** 报文明文。 */
    val text: String,
    /** 近似 SNR（dB，2500 Hz 参考带宽）。 */
    val snr: Int,
    /** 相对时隙起点的时间偏移（秒），名义 0。 */
    val dt: Float,
    /** 音频频率偏移（Hz）。 */
    val df: Int,
    /** Costas 同步得分。 */
    val score: Int,
    /** 所属时隙的 UTC 起点（毫秒）；离线解码时为 0。 */
    val slotUtcMs: Long,
) {
    /**
     * 深度（弱信号二次）解码标记，见《按 FT8CN QSO 逻辑改造方案》§1.6。
     *
     * FT8CN 的 `isDeep` / `isWeakSignal` 来自同一路「弱信号二次解码」：深度解码只用于显示，
     * **不驱动自动程序、也不计无回应**。当前 native 只有单遍解码 ⇒ 恒为 `false`（**行为等价现状**），
     * 这里保留为钩子，将来 native 引入二次解码后直接置真即可。
     *
     * 放在**类体**（而不是主构造器）是为了保持 native 的 JNI 构造签名
     * `(Ljava/lang/String;IFIIJ)V` 不变。
     */
    val deep: Boolean = false
}

/** waterfall 静态信息（频率轴）。 */
data class WaterfallInfo(
    /** 频率 bin 数。 */
    val bins: Int,
    /** 每个 bin 的频率宽度（Hz）：Kotlin STFT 为 `sampleRate / fftSize`（12 kHz / 2048 ≈ 5.86 Hz）。 */
    val binHz: Float,
    /** 起始频率（Hz）。 */
    val fMinHz: Float,
)
