package com.example.ft8vox.engine

import com.ft8.nativecore.Ft8DecodeConfig
import com.ft8.nativecore.Ft8Native

/**
 * 协议类型。当前仅支持 FT8；FT4 已于本次改造中从引擎/协议层移除。
 *
 * @property messageMs 单条报文的波形时长（ms）：FT8 = 79 符号 × 160 ms = 12.64 s。
 *   用于判断「这条报文能否在本时隙内播完」（`planTx` 就地发射、`TxScheduler` 立即发/换目标）。
 * @property occupiedHz 单条报文实际占用的音频带宽（Hz）：FT8 = 8 音 × 6.25 Hz = 50 Hz。
 *   报文的下边频就是报文的音频频率（瀑布上的红线）。
 * @property slotMs 协议时隙长度（ms）：FT8 = 15 s。
 */
enum class Protocol(val messageMs: Int, val occupiedHz: Int, val slotMs: Int) {
    FT8(12_640, 50, 15_000),
}

/** 解码器配置。 */
data class Ft8Config(
    val protocol: Protocol = Protocol.FT8,
    /** 分析采样率，FT8 标准为 12000 Hz。 */
    val sampleRate: Int = 12000,
    /** 分析频率下限（Hz）。 */
    val fMin: Float = 200f,
    /** 分析频率上限（Hz）。 */
    val fMax: Float = 3000f,
    /** 时间过采样率。 */
    val timeOsr: Int = 2,
    /** 频率过采样率。 */
    val freqOsr: Int = 2,
)

/**
 * FT8 离线编解码的 Kotlin 入口（基于 ft8_w 的 [Ft8Native]）。
 *
 * 实时接收/发射已改由 [AudioEngine]（Kotlin 实时引擎）负责；本对象保留给：
 * - 一次性报文编码校验（`SessionViewModel.canEncodeMessage`）；
 * - 单元/仪器测试的整段解码。
 *
 * 说明：ft8_w 的上报 `dt` 是「相对音频起点」的秒数（信号通常落在 0.5 s），而本
 * App 的 [DecodeResult.dt] 沿用 WSJT-X 口径（名义 0），故此处统一减去 0.5 s。
 */
object Ft8Engine {

    /** 最近一次 initialize 使用的配置，供 encode 默认复用。 */
    private var config: Ft8Config = Ft8Config()

    /** 最近一次下发的解码参数。 */
    var decodeParams: DecodeParams = DecodeParams()
        private set

    /** 离线累积的音频缓冲（最多一个时隙）。 */
    private var buffer: FloatArray = FloatArray(0)
    private var bufferLen: Int = 0

    /** 初始化（离线）：重置累积缓冲并保存配置/参数。可重复调用。 */
    fun initialize(
        config: Ft8Config = Ft8Config(),
        decodeParams: DecodeParams = DecodeParams(),
    ) {
        this.config = config
        this.decodeParams = decodeParams.clamped()
        reset()
    }

    /** 更新热生效的解码参数。 */
    fun setDecodeParams(params: DecodeParams) {
        decodeParams = params.clamped()
    }

    /** 清空当前累积的音频，准备下一段解码。 */
    fun reset() {
        val slot = config.sampleRate * 15
        if (buffer.size != slot) buffer = FloatArray(slot)
        bufferLen = 0
    }

    /** 追加一块 12 kHz 单声道 PCM（float，[-1,1]），最多保留一个时隙。 */
    fun processAudio(samples: FloatArray, length: Int = samples.size) {
        val n = length.coerceAtMost(samples.size).coerceAtLeast(0)
        if (n == 0) return
        if (buffer.isEmpty()) reset()
        val take = minOf(n, buffer.size - bufferLen)
        if (take > 0) {
            System.arraycopy(samples, 0, buffer, bufferLen, take)
            bufferLen += take
        }
    }

    /** 对当前累积的音频解码，返回报文明文列表。 */
    fun decode(): List<String> = decodeDetailed().map { it.text }

    /** 对当前累积的音频解码，返回带指标（SNR/DT/DF/score）的结果。 */
    fun decodeDetailed(): List<DecodeResult> {
        if (bufferLen <= 0) return emptyList()
        val samples = if (bufferLen == buffer.size) buffer else buffer.copyOf(bufferLen)
        return runCatching {
            Ft8Native.decode(samples, decodeConfig()).map(::toDecodeResult).take(decodeParams.maxDecoded)
        }.getOrDefault(emptyList())
    }

    /**
     * 将文本编码为发射 PCM（12 kHz，float）。波形自带 0.5 s 起播保护间隔（WSJT-X 约定），
     * 不填充到整时隙 —— 播放端 [AudioEngine.playTx] 会在其前面再加 PTT 前导。
     *
     * @throws IllegalArgumentException 当报文无法解析/编码时抛出。
     */
    fun encode(
        text: String,
        frequencyHz: Float,
        @Suppress("UNUSED_PARAMETER") protocol: Protocol = config.protocol,
        sampleRate: Int = config.sampleRate,
    ): FloatArray {
        val cfg = com.ft8.nativecore.Ft8EncodeConfig(
            sampleRate = sampleRate,
            baseFreqHz = frequencyHz,
            amplitude = 1.0f,
            symbolBt = 2.0f,
            leadInSec = 0.5f,
            tailSec = 0f,
        ).toFloatArray()
        val len = Ft8Native.encodeLength(text, cfg)
        require(len > 0) { "无法编码报文: \"$text\"" }
        val out = FloatArray(len)
        val written = Ft8Native.encode(text, cfg, out)
        require(written > 0) { "无法编码报文: \"$text\"" }
        return if (written == out.size) out else out.copyOf(written)
    }

    /** 离线引擎不提供瀑布（仅实时 [AudioEngine] 提供）；恒为 null。 */
    fun waterfallInfo(): WaterfallInfo? = null

    /** 离线引擎不提供瀑布行；恒为空。 */
    fun pollWaterfall(@Suppress("UNUSED_PARAMETER") maxRows: Int = 64): ByteArray = ByteArray(0)

    /** 释放资源（无原生句柄，仅清空缓冲）。 */
    fun release() {
        bufferLen = 0
    }

    /** 占位接口：返回 native 侧的握手字符串。 */
    fun test(): String = "Ft8Native ${Ft8Native.version()}"

    // ---- 内部 ----

    private fun decodeConfig(): Ft8DecodeConfig {
        val p = decodeParams
        return Ft8DecodeConfig(
            fMinHz = config.fMin,
            fMaxHz = config.fMax,
            sampleRate = config.sampleRate,
            timeOsr = config.timeOsr,
            freqOsr = config.freqOsr,
            maxCandidates = p.maxCandidates,
            minSyncScore = p.minScore,
            ldpcIterations = p.ldpcIterations,
            decodeDepth = p.passes,
            enableSubtract = p.passes > 1,
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            osdDepth = 2,
        )
    }

    /** ft8_w 结果 → App 的 [DecodeResult]（dt 归一到 WSJT-X 口径；离线无时隙）。 */
    private fun toDecodeResult(m: com.ft8.nativecore.Ft8Message): DecodeResult = DecodeResult(
        text = m.text,
        snr = Math.round(m.snr),
        dt = m.dt - 0.5f,
        df = Math.round(m.freq),
        score = m.score,
        slotUtcMs = 0L,
    )
}
