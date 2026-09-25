package com.example.ft8vox.data.settings

import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.engine.DecodeParams
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.qso.AutoProgramSettings
import com.example.ft8vox.qso.DEFAULT_MACROS
import com.example.ft8vox.qso.DecodeFilterTag

/** 发射时隙奇偶：0=偶数周期，1=奇数周期（具体时隙由「自动」按手机 UTC 时间锁定）。 */
const val TX_PARITY_EVEN = 0
const val TX_PARITY_ODD = 1

/** 设备采样率偏好（0 表示自动选择）。 */
enum class SampleRatePref(val label: String, val hz: Int) {
    AUTO("自动", 0),
    HZ_48000("48000", 48000),
    HZ_44100("44100", 44100),
    HZ_96000("96000", 96000),
}

/** VOX 触发方式（new_ui §6.1；生效依赖 U7 native 能力）。 */
enum class VoxTrigger(val label: String) {
    AUDIO("音频检测"),
    SILENCE("静音检测"),
}

/** 已通联呼号的呈现方式（new_ui §6.4）。 */
enum class WorkedStyle(val label: String) {
    STRIKE("删除线"),
    UNDERLINE("下划线"),
    HIDE("隐藏"),
}

/** 外观主题（new_ui §6.5）。 */
enum class ThemeMode(val label: String) {
    DARK("暗"),
    LIGHT("亮"),
}

/** 字体档位，作为 sp 的缩放系数（new_ui §6.5）。 */
enum class FontSize(val label: String, val scale: Float) {
    SMALL("小", 0.9f),
    MEDIUM("中", 1f),
    LARGE("大", 1.15f),
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
 * 时隙偏移的允许范围（ms）：见 [AppSettings.slotOffsetMs]。
 *
 * FT8 的解码器只在窗口起点前后约 ±2.5 s 内搜索信号（`find_candidates` 的
 * `time_offset ∈ [-10, +19]` 个符号块），超出这个范围既测不出 DT 也解不出报文。
 */
const val SLOT_OFFSET_LIMIT_MS = 2500

/**
 * 输出音量（发射音频的数字衰减）允许范围（dB），见 [AppSettings.outputGainDb]。
 *
 * 发射波形是 GFSK 合成、本身已是**数字满幅**（峰值 1.0 ≈ 0 dBFS），所以这里只能往小调：
 * 0 dB = 原样输出，下限 −30 dB。要更大声只能调电台/声卡的音量。
 */
const val OUTPUT_GAIN_MIN_DB = -30
const val OUTPUT_GAIN_MAX_DB = 0

/** 把输出音量钳制到 [OUTPUT_GAIN_MIN_DB]…[OUTPUT_GAIN_MAX_DB]。 */
fun clampOutputGainDb(db: Int): Int = db.coerceIn(OUTPUT_GAIN_MIN_DB, OUTPUT_GAIN_MAX_DB)

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
    /** 当前刻度频率（Hz）；0 表示按 [band] 的默认频率推导。 */
    val dialHz: Long = 0L,

    // ---- 操作 ----
    /** 协议名（存名字而非 ordinal，避免枚举顺序变化导致旧数据错位）。 */
    val protocolName: String = Protocol.FT8.name,
    /** 音频发射频率（Hz）。 */
    val selectedFreqHz: Int = 1500,
    /** 锁定发射频率（应答时不跟随对方频率）。 */
    val holdTxFreq: Boolean = false,
    /** 自动程序（等级 + 策略；对应 FT8CN「自动程序」菜单）。 */
    val auto: AutoProgramSettings = AutoProgramSettings(),
    /** 自动序列最大重试次数。 */
    val maxRetries: Int = 6,

    // ---- 解码列表过滤（显示层，new_ui §3.2） ----
    /** 已选中的筛选项；空集表示「一个都没开」。 */
    val filterTags: Set<DecodeFilterTag> = setOf(DecodeFilterTag.ALL),
    /** 呼号/前缀过滤串（逗号分隔）。 */
    val callFilter: String = "",
    /** 被忽略的呼号（右滑忽略 / 长按菜单忽略）。 */
    val ignoredCalls: Set<String> = emptySet(),

    // ---- 发射抽屉（new_ui §3.4） ----
    /** 发送队列（报文原文，有序）。 */
    val txQueue: List<String> = emptyList(),
    /** 宏模板（4×2）。 */
    val macros: List<String> = DEFAULT_MACROS,

    // ---- 地图页（new_ui §4.4/§4.5） ----
    /** CQ 旗帜是否显示呼号。 */
    val mapCqFlagShowCall: Boolean = true,
    /** CQ 旗帜是否显示信号强度。 */
    val mapCqFlagShowSnr: Boolean = false,
    /** 信号连线是否显示内容文字（关闭则只显示移动方块）。 */
    val mapShowLinkText: Boolean = true,

    // ---- 电台 / VOX（new_ui §6.1；生效依赖 U7） ----
    /** VOX 触发方式。 */
    val voxTrigger: VoxTrigger = VoxTrigger.AUDIO,
    /** VOX 延迟（ms，50–1000）。 */
    val voxDelayMs: Int = 300,
    /** VOX 阈值（dB，−60…−20）。 */
    val voxThresholdDb: Int = -40,
    /** 发射前导音开关。 */
    val txLeadTone: Boolean = false,
    /** 前导音时长（ms，0–2000）。 */
    val txLeadToneMs: Int = 200,
    /** 输出声卡（空 = 系统默认；设备枚举依赖 U7）。 */
    val outputDevice: String = "",
    /**
     * 输出音量（dB，[OUTPUT_GAIN_MIN_DB]…[OUTPUT_GAIN_MAX_DB]）：发射音频的数字衰减，
     * **0 = 数字满幅**（波形本身已是满幅，只能往小调）。对报文与「测试音」都生效，热生效。
     */
    val outputGainDb: Int = 0,
    /** PTT 延迟（ms，0–500）。 */
    val pttDelayMs: Int = 50,
    /** 看门狗超时（ms，1000–60000）。 */
    val watchdogMs: Int = 10000,

    // ---- 音频（new_ui §6.2） ----
    /** 输入设备（空 = 系统默认；枚举依赖 U7）。 */
    val inputDevice: String = "",
    /** 输入增益（dB，−12…+30；生效依赖 U7）。 */
    val inputGainDb: Int = 0,

    // ---- FT8（new_ui §6.3） ----
    /**
     * 时隙偏移（ms，−2500…+2500）：**整个时隙一起偏移**（解码窗口起点 + 发射起点）。
     *
     * 校准用法：操作页解码卡片上的「时间差」就是本机时隙起点与对端的偏差，把它原样填进来
     * （如 +1.5s → +1500），DT 回到约 0 即校准完成；正值 = 推后，负值 = 提前。
     */
    val slotOffsetMs: Int = 0,

    // ---- 高亮与提醒（new_ui §6.4） ----
    /** 新 CQ 区域（按呼号前缀映射实体表）。 */
    val highlightNewCqZone: Boolean = true,
    /** 新 ITU 区域（按呼号前缀映射实体表）。 */
    val highlightNewItu: Boolean = true,
    /** 新 DXCC 实体（按呼号前缀映射实体表）。 */
    val highlightNewEntity: Boolean = true,
    /** 新网格。 */
    val highlightNewGrid: Boolean = true,
    /** 新前缀（粗略口径，取首个数字前的连续字母）。 */
    val highlightNewPrefix: Boolean = true,
    /** 新呼号。 */
    val highlightNewCall: Boolean = true,
    /** 已通联呼号的呈现方式。 */
    val workedStyle: WorkedStyle = WorkedStyle.STRIKE,
    /** 含我呼号时哔声提醒（依赖音频，U7）。 */
    val beepOnMyCall: Boolean = false,
    /** 末端红标记：报文含我呼号。 */
    val endMarkMyCall: Boolean = true,
    /** 末端蓝标记：当前 QSO 对手。 */
    val endMarkActive: Boolean = true,

    // ---- 外观（new_ui §6.5） ----
    val themeMode: ThemeMode = ThemeMode.DARK,
    val fontSize: FontSize = FontSize.MEDIUM,

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

    /** 当前实际使用的刻度频率（Hz）：自定义波段或波段内频率优先，否则取波段默认。 */
    val resolvedDialHz: Long
        get() = BandPlan.resolveDialHz(band, dialHz)

    /** 可热更新的解码参数（映射到 native `DecodeParams`）。 */
    val decodeParams: DecodeParams
        get() = DecodeParams.of(
            minScore = decode.minScore,
            maxCandidates = decode.maxCandidates,
            ldpcIterations = decode.ldpcIterations,
            maxDecoded = decode.maxDecoded,
        )
}
