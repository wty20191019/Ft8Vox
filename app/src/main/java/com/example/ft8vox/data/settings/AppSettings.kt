package com.example.ft8vox.data.settings

import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.engine.DecodeParams
import com.example.ft8vox.engine.Protocol

/** Call 1st 自动应答策略。 */
enum class CallFirstMode(val label: String) {
    OFF("关"),
    STRONGEST("最强 CQ"),
    FIRST("首个 CQ"),
}

/** 设备采样率偏好（0 表示自动选择）。 */
enum class SampleRatePref(val label: String, val hz: Int) {
    AUTO("自动", 0),
    HZ_48000("48000", 48000),
    HZ_44100("44100", 44100),
}

/** 瀑布高度档位。 */
enum class WaterfallHeight(val label: String, val heightDp: Int) {
    COMPACT("紧凑", 120),
    NORMAL("标准", 180),
    TALL("高", 260),
}

/** 解码预设档位。[CUSTOM] 表示用户手动改过高级参数，不再是任何预设。 */
enum class DecodePreset(val label: String) {
    FAST("快"),
    STANDARD("标准"),
    DEEP("深"),
    CUSTOM("自定义"),
}

/**
 * 解码参数（对应 native 的可调项）。
 *
 * - [timeOsr]/[freqOsr]/[fMinHz]/[fMaxHz] 属于 `monitor_config_t`，改动需**重建引擎**；
 * - [minScore]/[ldpcIterations]/[maxCandidates]/[maxDecoded] 每次解码读取，**热生效**。
 */
data class DecodeSettings(
    val timeOsr: Int = 2,
    val freqOsr: Int = 2,
    val minScore: Int = 10,
    val ldpcIterations: Int = 25,
    val maxCandidates: Int = 140,
    val maxDecoded: Int = 50,
    val fMinHz: Int = 200,
    val fMaxHz: Int = 3000,
) {
    /** 把预设应用到当前高级参数（频率范围不随预设变化）。[DecodePreset.CUSTOM] 不改动。 */
    fun applyPreset(preset: DecodePreset): DecodeSettings = when (preset) {
        DecodePreset.FAST -> copy(
            timeOsr = 1, freqOsr = 1, minScore = 12,
            ldpcIterations = 10, maxCandidates = 80, maxDecoded = 30,
        )
        DecodePreset.STANDARD -> copy(
            timeOsr = 2, freqOsr = 2, minScore = 10,
            ldpcIterations = 25, maxCandidates = 140, maxDecoded = 50,
        )
        DecodePreset.DEEP -> copy(
            timeOsr = 4, freqOsr = 4, minScore = 8,
            ldpcIterations = 50, maxCandidates = 250, maxDecoded = 80,
        )
        DecodePreset.CUSTOM -> this
    }

    /**
     * 把各字段钳制到允许范围。
     *
     * 用于两处：从 DataStore 读出的旧数据、以及用户在设置页手动输入的值，
     * 避免越界值传到 native。
     */
    fun clamped(): DecodeSettings = copy(
        timeOsr = timeOsr.coerceIn(TIME_OSR_RANGE),
        freqOsr = freqOsr.coerceIn(FREQ_OSR_RANGE),
        minScore = minScore.coerceIn(MIN_SCORE_RANGE),
        ldpcIterations = ldpcIterations.coerceIn(LDPC_RANGE),
        maxCandidates = maxCandidates.coerceIn(MAX_CANDIDATES_RANGE),
        maxDecoded = maxDecoded.coerceIn(MAX_DECODED_RANGE),
        fMinHz = fMinHz.coerceIn(F_MIN_RANGE),
        fMaxHz = fMaxHz.coerceIn(F_MAX_RANGE),
    ).let { if (it.fMaxHz < it.fMinHz + 100) it.copy(fMaxHz = it.fMinHz + 100) else it }

    companion object {
        /** 各字段的允许范围，供设置页做钳制与提示。 */
        val TIME_OSR_RANGE = 1..4
        val FREQ_OSR_RANGE = 1..4
        val MIN_SCORE_RANGE = 4..40
        val LDPC_RANGE = 5..60
        val MAX_CANDIDATES_RANGE = 20..500
        val MAX_DECODED_RANGE = 5..100
        val F_MIN_RANGE = 100..2000
        val F_MAX_RANGE = 1000..5000
    }
}

/**
 * 应用设置（DataStore 持久化）。
 *
 * 一次读写整个对象；新增字段时给出默认值即可，旧数据缺失的键会回落到默认值。
 */
data class AppSettings(
    // ---- 台站 ----
    val myCall: String = "",
    val myGrid: String = "",
    val note: String = "",
    /** 当前波段（无 CAT，由用户指定）。 */
    val band: String = BandPlan.DEFAULT_BAND,

    // ---- 操作 ----
    /** 协议名（存名字而非 ordinal，避免枚举顺序变化导致旧数据错位）。 */
    val protocolName: String = Protocol.FT8.name,
    /** 音频发射频率（Hz）。 */
    val selectedFreqHz: Int = 1500,
    /** 我方发射周期：0=偶数，1=奇数。 */
    val txParity: Int = 0,
    /** 锁定发射频率（应答时不跟随对方频率）。 */
    val holdTxFreq: Boolean = false,
    /** Call 1st 自动应答策略。 */
    val callFirst: CallFirstMode = CallFirstMode.OFF,
    /** 自动序列最大重试次数。 */
    val maxRetries: Int = 6,

    // ---- 解码列表过滤（显示层） ----
    val cqOnly: Boolean = false,
    val excludeWorked: Boolean = false,
    /** 呼号/前缀过滤串（逗号分隔）。 */
    val callFilter: String = "",

    // ---- 界面/音频 ----
    val waterfallHeight: WaterfallHeight = WaterfallHeight.NORMAL,
    val sampleRate: SampleRatePref = SampleRatePref.AUTO,

    // ---- 解码参数 ----
    val decodePreset: DecodePreset = DecodePreset.STANDARD,
    val decode: DecodeSettings = DecodeSettings(),
) {
    /** [protocolName] 对应的枚举；非法回落 FT8。 */
    val protocol: Protocol
        get() = Protocol.entries.firstOrNull { it.name == protocolName } ?: Protocol.FT8

    /** 可热更新的解码参数（映射到 native `DecodeParams`）。 */
    val decodeParams: DecodeParams
        get() = DecodeParams.of(
            minScore = decode.minScore,
            maxCandidates = decode.maxCandidates,
            ldpcIterations = decode.ldpcIterations,
            maxDecoded = decode.maxDecoded,
        )
}
