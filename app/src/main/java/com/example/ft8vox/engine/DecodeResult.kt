package com.example.ft8vox.engine

/**
 * 单条解码结果（对应 native 的 `ftx_decode_result_t`）。
 *
 * 注意：字段顺序与类型必须与 native `jni_common.h` 中的构造签名
 * `(Ljava/lang/String;IFIIJZ)V` 保持一致。
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
    /**
     * 深度（弱信号二次）解码标记，照 FT8CN 的 `isDeep` / `isWeakSignal`：来自深档（高迭代
     * 深跑或减谱重解循环）的报文只用于显示，**不驱动自动程序、也不计无回应**
     * （见 [com.example.ft8vox.qso.AutoProgram] 与 [com.example.ft8vox.qso.QsoEngine]）。
     * 快档（默认）单跑一趟，恒为 `false`。
     */
    val deep: Boolean = false,
)

/** waterfall 静态信息（频率轴）。 */
data class WaterfallInfo(
    /** 频率 bin 数。 */
    val bins: Int,
    /** 每个 bin 的频率宽度（Hz）。FT8/FT4 为 6.25 Hz。 */
    val binHz: Float,
    /** 起始频率（Hz）。 */
    val fMinHz: Float,
)
