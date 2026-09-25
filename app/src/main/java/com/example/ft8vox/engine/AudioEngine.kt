package com.example.ft8vox.engine

import com.example.ft8vox.data.settings.SLOT_OFFSET_LIMIT_MS
import com.example.ft8vox.data.settings.clampOutputGainDb

/** VOX 触发方式（对应设置页「VOX 触发」；无 CAT 时用于输入电平判定）。 */
enum class VoxMode {
    /** 音频检测：输入电平 ≥ 阈值视为触发/有信号。 */
    AUDIO,

    /** 静音检测：输入电平 < 阈值视为静音/空闲。 */
    SILENCE,
}

/**
 * VOX / PTT 配置（U7b）。
 *
 * 无 CAT 时 App 无法控制电台 PTT，只能通过发射音频序列间接键控：
 * [pttDelayMs] 前导静音 + [leadToneMs] 前导音先让电台 VOX 动作，
 * 随后 FT8 数据落在时隙起点。[watchdogMs] 为发射写入的卡死保护。
 */
data class VoxConfig(
    val mode: VoxMode = VoxMode.AUDIO,
    val thresholdDb: Int = -40,
    val delayMs: Int = 300,
    val pttDelayMs: Int = 0,
    val leadToneMs: Int = 0,
    val watchdogMs: Int = 10_000,
)

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
    /** VOX 判定为已触发（基于输入电平近似，仅作提示）。 */
    val voxOpen: Boolean,
    /** 平滑后的输入电平（dBFS，下限约 -100）。 */
    val voxLevelDb: Float,
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
     * @param deviceId 指定输入设备（AudioManager 的设备 id）；<=0 表示系统默认。
     * @return 设备实际采样率；负数表示失败（-1 未初始化，-2 无法建流，-3 打开失败…）。
     */
    fun startCapture(preferredRate: Int = 48000, deviceId: Int = 0): Int =
        if (handle != 0L) nativeStartCapture(handle, preferredRate, deviceId) else -1

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
     * @param deviceId 指定输出设备（AudioManager 的设备 id）；<=0 表示系统默认。
     * @return 设备实际采样率；负数表示失败。
     */
    fun startPlayback(preferredRate: Int = 48000, deviceId: Int = 0): Int =
        if (handle != 0L) nativeStartPlayback(handle, preferredRate, deviceId) else -1

    /** 关闭播放流。 */
    fun stopPlayback() {
        if (handle != 0L) nativeStopPlayback(handle)
    }

    /** 播放一段 12 kHz PCM（内部按输出采样率重采样），返回写入帧数。 */
    fun play(pcm: FloatArray): Int =
        if (handle != 0L) nativePlay(handle, pcm) else -1

    /**
     * 播放发射 PCM，带 PTT 前导静音与发射前导音；返回写入帧数。
     *
     * 前导会把数据整体推后 `pttSilenceMs + leadToneMs`，因此调用方必须
     * 相应提前播放起点，FT8 数据才能落在时隙起点。
     */
    fun playTx(pcm: FloatArray, pttSilenceMs: Int = 0, leadToneMs: Int = 0): Int =
        if (handle != 0L) nativePlayTx(handle, pcm, pttSilenceMs, leadToneMs) else -1

    /** 播放一段测试单音（VOX 键控/音量联调），返回写入帧数。 */
    fun playTone(freqHz: Int = 1000, durationMs: Int = 2000): Int =
        if (handle != 0L) nativePlayTone(handle, freqHz, durationMs) else -1

    /**
     * 下发 VOX / PTT 配置（热生效）。需先 [initialize]；未初始化时静默忽略。
     * 参数会按设置页范围做一次钳制。
     */
    fun setVox(config: VoxConfig) {
        if (handle == 0L) return
        val mode = if (config.mode == VoxMode.SILENCE) 1 else 0
        nativeSetVox(
            handle,
            mode,
            config.thresholdDb.coerceIn(-60, -20),
            config.delayMs.coerceIn(0, 2000),
            config.pttDelayMs.coerceIn(0, 500),
            config.leadToneMs.coerceIn(0, 2000),
            config.watchdogMs.coerceIn(1000, 60_000),
        )
    }

    /**
     * 下发采集增益（热生效，U7c）。需先 [initialize]；未初始化时静默忽略。
     * 增益在 native 对原始样本生效（含 VOX 电平），范围按设置页钳制到 -12..30 dB。
     */
    fun setInputGain(gainDb: Int) {
        if (handle == 0L) return
        nativeSetInputGain(handle, gainDb.coerceIn(-12, 30))
    }

    /**
     * 下发输出音量（热生效）。需先 [initialize]；未初始化时静默忽略。
     *
     * 对**所有播放的发射音频**生效（FT8/FT4 报文、前导音、设置页「测试音」），
     * 在 native 写入声卡前按 `10^(dB/20)` 缩放。发射波形本身已是数字满幅，
     * 所以只能衰减（0 dB = 原样输出），范围见 [clampOutputGainDb]。
     */
    fun setOutputGain(gainDb: Int) {
        if (handle == 0L) return
        nativeSetOutputGain(handle, clampOutputGainDb(gainDb))
    }

    /**
     * 下发时隙偏移（热生效，U7「发射偏移」）。
     *
     * **整个时隙一起偏移**：native 的采集窗口起点（解码 DT 的基准）与 Kotlin 的发射起点
     * 都按同一数值平移（正=推后、负=提前）。需先 [initialize]；未初始化时静默忽略。
     * 范围与设置页一致（[SLOT_OFFSET_LIMIT_MS]）：FT8 的 DT 搜索窗约 ±2.5 s。
     */
    fun setSlotOffsetMs(offsetMs: Int) {
        if (handle == 0L) return
        nativeSetSlotOffsetMs(handle, offsetMs.coerceIn(-SLOT_OFFSET_LIMIT_MS, SLOT_OFFSET_LIMIT_MS))
    }

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
            voxOpen = v[10] != 0L,
            voxLevelDb = v[11] / 10f,
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
    private external fun nativeStartCapture(handle: Long, preferredRate: Int, deviceId: Int): Int
    private external fun nativeStopCapture(handle: Long)
    private external fun nativePollDecoded(handle: Long): Array<DecodeResult>
    private external fun nativeWaterfallInfo(handle: Long): IntArray
    private external fun nativePollWaterfall(handle: Long, maxRows: Int): ByteArray
    private external fun nativeStartPlayback(handle: Long, preferredRate: Int, deviceId: Int): Int
    private external fun nativeStopPlayback(handle: Long)
    private external fun nativePlay(handle: Long, pcm: FloatArray): Int
    private external fun nativePlayTx(
        handle: Long,
        pcm: FloatArray,
        pttSilenceMs: Int,
        leadToneMs: Int,
    ): Int
    private external fun nativePlayTone(handle: Long, freqHz: Int, durationMs: Int): Int
    private external fun nativeSetVox(
        handle: Long,
        trigger: Int,
        thresholdDb: Int,
        delayMs: Int,
        pttDelayMs: Int,
        leadToneMs: Int,
        watchdogMs: Int,
    )
    private external fun nativeSetInputGain(handle: Long, gainDb: Int)

    /** 输出音量（发射音频的数字衰减，热生效，见 [setOutputGain]）。 */
    private external fun nativeSetOutputGain(handle: Long, gainDb: Int)

    /** 时隙偏移（U7「发射偏移」，热生效，见 [setSlotOffsetMs]）。 */
    private external fun nativeSetSlotOffsetMs(handle: Long, offsetMs: Int)

    private external fun nativeGetState(handle: Long): LongArray
    private external fun nativeUtcNowMs(): Long
    private external fun nativeResample(input: FloatArray, inRate: Int, outRate: Int): FloatArray?
}
