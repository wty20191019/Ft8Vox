package com.example.ft8vox.engine

/** 实时音频引擎的状态快照（对应 native 的 audio_engine_t 统计字段）。 */
data class AudioState(
    /** 采集流是否在运行（native running）。 */
    val running: Boolean,
    /** 当前是否处于时隙累积窗口内（native capturing）。 */
    val inSlot: Boolean,
    val inputRate: Int,
    val outputRate: Int,
    val fedSamples: Long,
    val slotSamples: Long,
    val slotMs: Long,
    val utcNowMs: Long,
    val droppedSamples: Long,
    val slotsDecoded: Long,
) {
    /** 当前时隙已采集比例 0..1。 */
    val slotProgress: Float
        get() = if (slotSamples > 0) (fedSamples.toFloat() / slotSamples).coerceIn(0f, 1f) else 0f

    /** 距离下一个时隙起点的毫秒数。 */
    val msToNextSlot: Long
        get() = if (slotMs > 0) slotMs - (utcNowMs % slotMs) else 0L
}

/**
 * AAudio 实时音频引擎的 Kotlin 入口。
 *
 * 负责：采集（优先 48 kHz，native 重采样到 12 kHz）、按时隙累积与解码、
 * 以及按时隙播放发射 PCM。解码结果通过 [pollDecoded] 拉取（不引入 native 回调）。
 *
 * 接口契约见 docs/JNI-CONTRACT.md。
 */
object AudioEngine {

    init {
        System.loadLibrary("ft8")
    }

    private var handle: Long = 0L

    /** 创建引擎（按配置初始化 monitor 会话）。重复调用会先释放旧引擎。 */
    fun initialize(
        config: Ft8Config = Ft8Config(),
        decodeParams: DecodeParams = DecodeParams(),
    ) {
        release()
        handle = nativeCreate(
            config.protocol.ordinal,
            config.fMin,
            config.fMax,
            config.timeOsr,
            config.freqOsr,
        )
        check(handle != 0L) { "Failed to initialize AudioEngine (native create returned 0)" }
        setDecodeParams(decodeParams)
    }

    /**
     * 更新热生效的解码参数（候选数/最低得分/LDPC 迭代/单时隙上限）。
     * 需先 [initialize]；未初始化时静默忽略。
     */
    fun setDecodeParams(params: DecodeParams) {
        val p = params.clamped()
        if (handle != 0L) {
            nativeSetDecodeParams(
                handle,
                p.minScore,
                p.maxCandidates,
                p.ldpcIterations,
                p.maxDecoded,
            )
        }
    }

    /** 释放引擎（会先停止采集与播放）。 */
    fun release() {
        if (handle != 0L) {
            stopCapture()
            stopPlayback()
            nativeDestroy(handle)
            handle = 0L
        }
    }

    /**
     * 开始采集。
     * @return 设备实际采样率；负数表示失败（-1 未初始化，-2 无法建流，-3 打开失败…）。
     */
    fun startCapture(preferredRate: Int = 48000): Int =
        if (handle != 0L) nativeStartCapture(handle, preferredRate) else -1

    /** 停止采集。 */
    fun stopCapture() {
        if (handle != 0L) nativeStopCapture(handle)
    }

    /** 取走并清空自上次调用以来解出的报文（含指标）。 */
    fun pollDecoded(): List<DecodeResult> =
        if (handle != 0L) nativePollDecoded(handle).toList() else emptyList()

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
     * 打开播放流（不播放音频）。
     * @return 设备实际采样率；负数表示失败。
     */
    fun startPlayback(preferredRate: Int = 48000): Int =
        if (handle != 0L) nativeStartPlayback(handle, preferredRate) else -1

    /** 关闭播放流。 */
    fun stopPlayback() {
        if (handle != 0L) nativeStopPlayback(handle)
    }

    /** 播放一段 12 kHz PCM（内部按输出采样率重采样），返回写入帧数。 */
    fun play(pcm: FloatArray): Int =
        if (handle != 0L) nativePlay(handle, pcm) else -1

    /** 读取当前状态快照。 */
    fun state(): AudioState? {
        if (handle == 0L) return null
        val v = nativeGetState(handle)
        return AudioState(
            running = v[0] != 0L,
            inSlot = v[1] != 0L,
            inputRate = v[2].toInt(),
            outputRate = v[3].toInt(),
            fedSamples = v[4],
            slotSamples = v[5],
            slotMs = v[6],
            utcNowMs = v[7],
            droppedSamples = v[8],
            slotsDecoded = v[9],
        )
    }

    /** 当前 UTC 毫秒时间（用于时隙对齐/倒计时）。 */
    fun utcNowMs(): Long = nativeUtcNowMs()

    /** 测试用：把 input 从 inRate 重采样到 outRate。 */
    internal fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray =
        nativeResample(input, inRate, outRate) ?: FloatArray(0)

    // ---- native 接口 ----
    private external fun nativeCreate(
        protocol: Int,
        fMin: Float,
        fMax: Float,
        timeOsr: Int,
        freqOsr: Int,
    ): Long

    private external fun nativeSetDecodeParams(
        handle: Long,
        minScore: Int,
        maxCandidates: Int,
        ldpcIterations: Int,
        maxDecoded: Int,
    )

    private external fun nativeDestroy(handle: Long)
    private external fun nativeStartCapture(handle: Long, preferredRate: Int): Int
    private external fun nativeStopCapture(handle: Long)
    private external fun nativePollDecoded(handle: Long): Array<DecodeResult>
    private external fun nativeWaterfallInfo(handle: Long): IntArray
    private external fun nativePollWaterfall(handle: Long, maxRows: Int): ByteArray
    private external fun nativeStartPlayback(handle: Long, preferredRate: Int): Int
    private external fun nativeStopPlayback(handle: Long)
    private external fun nativePlay(handle: Long, pcm: FloatArray): Int
    private external fun nativeGetState(handle: Long): LongArray
    private external fun nativeUtcNowMs(): Long
    private external fun nativeResample(input: FloatArray, inRate: Int, outRate: Int): FloatArray?
}
