package com.example.ft8vox.engine

/**
 * 协议类型。ordinal 会传给 native，务必保持顺序（0=FT8, 1=FT4）。
 *
 * @property messageMs 单条报文的波形时长（ms）：FT8 = 79 符号 × 160 ms = 12.64 s，
 *   FT4 = 105 符号 × 42.67 ms ≈ 4.48 s。用于判断「这条报文能否在本时隙内播完」。
 */
enum class Protocol(val messageMs: Int) {
    FT8(12_640),
    FT4(4_480),
}

/** 解码器配置，对应 native 的 monitor_config_t。 */
data class Ft8Config(
    val protocol: Protocol = Protocol.FT8,
    /** 分析采样率，FT8/FT4 标准为 12000 Hz。 */
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
 * FT8 / FT4 引擎的 Kotlin 入口。
 *
 * 负责加载 native 库 libft8.so，并向上层暴露解码、编码等接口。
 * 接口契约见 docs/JNI-CONTRACT.md。
 *
 * 注意：类名与包名决定 native 函数符号（Java_<包>_<类>_<方法>），
 * 重命名此类或包时必须同步修改 app/src/main/cpp/jni_bridge.c。
 */
object Ft8Engine {

    init {
        System.loadLibrary("ft8")
    }

    /** native 引擎句柄（指向 native ft8_engine_t），0 表示未初始化。 */
    private var handle: Long = 0L

    /** 最近一次 initialize 使用的配置，供 encode 默认复用。 */
    private var config: Ft8Config = Ft8Config()

    /** 最近一次下发的解码参数（便于调试/回读）。 */
    var decodeParams: DecodeParams = DecodeParams()
        private set

    /**
     * 初始化解码引擎。重复调用会先释放旧引擎。
     * @throws IllegalStateException 当 native 初始化失败时抛出。
     */
    fun initialize(
        config: Ft8Config = Ft8Config(),
        decodeParams: DecodeParams = DecodeParams(),
    ) {
        release()
        this.config = config
        handle = nativeInit(
            config.protocol.ordinal,
            config.sampleRate,
            config.fMin,
            config.fMax,
            config.timeOsr,
            config.freqOsr,
        )
        check(handle != 0L) { "Failed to initialize Ft8Engine (native init returned 0)" }
        setDecodeParams(decodeParams)
    }

    /**
     * 更新热生效的解码参数；需先 [initialize]，未初始化时静默忽略。
     */
    fun setDecodeParams(params: DecodeParams) {
        this.decodeParams = params.clamped()
        if (handle != 0L) {
            val p = decodeParams
            nativeSetDecodeParams(
                handle,
                p.minScore,
                p.maxCandidates,
                p.ldpcIterations,
                p.maxDecoded,
            )
        }
    }

    /** 清空当前时隙的 waterfall，准备下一个周期。 */
    fun reset() {
        if (handle != 0L) nativeReset(handle)
    }

    /**
     * 喂入一块 12 kHz 单声道 PCM（float，[-1,1]）。
     * 内部会按 monitor 的块大小累积到 waterfall。
     */
    fun processAudio(samples: FloatArray, length: Int = samples.size) {
        if (handle != 0L) nativeProcess(handle, samples, length)
    }

    /** 对当前累积的 waterfall 解码，返回报文明文列表。 */
    fun decode(): List<String> = decodeDetailed().map { it.text }

    /** 对当前累积的 waterfall 解码，返回带指标（SNR/DT/DF/score）的结果。 */
    fun decodeDetailed(): List<DecodeResult> =
        if (handle != 0L) nativeDecode(handle).toList() else emptyList()

    /** waterfall 频率轴信息；未初始化时为 null。 */
    fun waterfallInfo(): WaterfallInfo? {
        if (handle == 0L) return null
        val v = nativeWaterfallInfo(handle)
        return WaterfallInfo(bins = v[0], binHz = v[1] / 1000f, fMinHz = v[2] / 1000f)
    }

    /** 取走新产生的 waterfall 行（每行 `waterfallInfo()!!.bins` 字节）。 */
    fun pollWaterfall(maxRows: Int = 64): ByteArray =
        if (handle != 0L) nativePollWaterfall(handle, maxRows) else ByteArray(0)

    /**
     * 将文本编码为一个完整时隙的发射 PCM（12 kHz，float，[-1,1]，含静音填充）。
     *
     * @param text 报文明文，如 "CQ F4FSY JN25"、"GJ0KYZ RK9AX MO05"。
     * @param frequencyHz 音频基频（符号 0 对应的频率）。
     * @param protocol 协议，默认沿用 initialize 的配置。
     * @param sampleRate 采样率，默认沿用 initialize 的配置。
     * @throws IllegalArgumentException 当报文无法解析/编码时抛出。
     */
    fun encode(
        text: String,
        frequencyHz: Float,
        protocol: Protocol = config.protocol,
        sampleRate: Int = config.sampleRate,
    ): FloatArray =
        nativeEncode(protocol.ordinal, text, frequencyHz, sampleRate)
            ?: throw IllegalArgumentException("无法编码报文: \"$text\"")

    /** 释放 native 资源。可重复调用。 */
    fun release() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    // ---- native 接口 ----
    private external fun nativeInit(
        protocol: Int,
        sampleRate: Int,
        fMin: Float,
        fMax: Float,
        timeOsr: Int,
        freqOsr: Int,
    ): Long

    private external fun nativeReset(handle: Long)
    private external fun nativeProcess(handle: Long, samples: FloatArray, length: Int)
    private external fun nativeDecode(handle: Long): Array<DecodeResult>
    private external fun nativeSetDecodeParams(
        handle: Long,
        minScore: Int,
        maxCandidates: Int,
        ldpcIterations: Int,
        maxDecoded: Int,
    )
    private external fun nativeWaterfallInfo(handle: Long): IntArray
    private external fun nativePollWaterfall(handle: Long, maxRows: Int): ByteArray
    private external fun nativeEncode(
        protocol: Int,
        text: String,
        frequencyHz: Float,
        sampleRate: Int,
    ): FloatArray?

    private external fun nativeRelease(handle: Long)

    /** 占位接口：返回 native 侧的握手字符串。 */
    external fun test(): String
}
