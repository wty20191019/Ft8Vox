package com.example.ft8vox.data.settings

import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.engine.DecodeParams
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.qso.AutoProgramSettings
import com.example.ft8vox.qso.DEFAULT_CQ_PREFIXES
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

/** 外观主题（docs/UI.md §2.6）。 */
enum class ThemeMode(val label: String) {
    DARK("暗"),
    LIGHT("亮"),
}

/** 字体档位，作为 sp 的缩放系数（docs/UI.md §2.6）。 */
enum class FontSize(val label: String, val scale: Float) {
    SMALL("小", 0.9f),
    MEDIUM("中", 1f),
    LARGE("大", 1.15f),
}

/**
 * 瀑布高度档位（**按屏高百分比**）。
 *
 * 实际高度 = 屏高 × [fraction]，最小 150dp（很矮的屏幕上 15% 会被 150dp 下限抬起）。
 * 默认 [PCT24] = 24%，即原来「紧凑」档的外观（操作页 `WaterfallView` 的高度）。
 *
 * 枚举名刻意用 `PCT15/PCT24/PCT45`（**不用 SHORT/TALL 之类的语义名**）：档位按百分比就是
 * 本源，且旧版本在 DataStore 里留下的 `waterfall_height` 值（`COMPACT`/`NORMAL`/`TALL`/`SHORT`…）
 * 一律认不出 → 回落默认档，不会被旧名字意外「复活」成别的高度。
 */
enum class WaterfallHeight(val label: String, val fraction: Float) {
    PCT15("15%", 0.15f),
    PCT24("24%", 0.24f),
    PCT45("45%", 0.45f),
}

/**
 * 解码预设档位。
 *
 * **只有一个真预设 [FAST]（默认）**：参数照搬 FT8CN 的「快速解码」；原「标准 / 深」
 * 两档已删除 —— 想要更全的解码时逐项手改下面 6 个高级参数（改后会自动显示 [CUSTOM]）。
 */
enum class DecodePreset(val label: String) {
    FAST("快"),
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
 * **构造函数的默认值就是「快」预设**（`DecodePreset.FAST`，由单测锁定，两处不许漂移），
 * 照搬 FT8CN「快速解码」的实测参数：时间/频率 OSR 2 / 最低得分 10 / LDPC 迭代 20 /
 * 候选上限 120 / 单时隙上限 100 / 频率范围 100–3000 Hz（FT8CN 的 `libft8cn.so` 里
 * `init_decoder` 写死的正是 `f_min=100`、`f_max=3000`、`time_osr=2`、`freq_osr=2`，
 * 「快」的 LDPC 迭代 20、候选 120，见 `fast_kLDPC_iterations` / `kMax_candidates`）。
 * 想更全就逐项调大（会显示「自定义」）。
 *
 * - [timeOsr]/[freqOsr]/[fMinHz]/[fMaxHz] 属于 `monitor_config_t`，改动需**重建引擎**；
 * - [minScore]/[ldpcIterations]/[maxCandidates]/[maxDecoded]/[passes] 每次解码读取，**热生效**。
 */
data class DecodeSettings(
    val timeOsr: Int = 2,
    val freqOsr: Int = 2,
    val minScore: Int = 10,
    val ldpcIterations: Int = 20,
    val maxCandidates: Int = 120,
    val maxDecoded: Int = 100,
    /** 多趟减谱重解（SIC）趟数；1 = 单趟（关闭），2 = 默认。见 docs/UI.md §5.4.2。 */
    val passes: Int = 2,
    val fMinHz: Int = 100,
    val fMaxHz: Int = 3000,
) {
    /** 把预设应用到当前高级参数（频率范围不随预设变化）。[DecodePreset.CUSTOM] 不改动。 */
    fun applyPreset(preset: DecodePreset): DecodeSettings = when (preset) {
        // 「快」＝构造函数默认值（改这里必须同步改上面的默认值，单测会卡住漂移）
        DecodePreset.FAST -> DecodeSettings(
            timeOsr = 2, freqOsr = 2, minScore = 10,
            ldpcIterations = 20, maxCandidates = 120, maxDecoded = 100, passes = 2,
            fMinHz = fMinHz, fMaxHz = fMaxHz,
        )
        DecodePreset.CUSTOM -> this
    }

    /**
     * 除**频率范围**（[fMinHz]/[fMaxHz] 不随预设变化）外，其余各项是否与 [other] 相同。
     *
     * 用于反推「当前处于哪个预设」（[AppSettings.decodePreset]）——只有这几项由预设决定，
     * 因此只改频率范围不会被判成「自定义」。
     */
    fun samePresetValues(other: DecodeSettings): Boolean =
        timeOsr == other.timeOsr && freqOsr == other.freqOsr && minScore == other.minScore &&
            ldpcIterations == other.ldpcIterations && maxCandidates == other.maxCandidates &&
            maxDecoded == other.maxDecoded && passes == other.passes

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
        passes = passes.coerceIn(PASSES_RANGE),
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
        /** SIC 趟数范围；上与 native `K_MAX_DECODE_PASSES` 一致。 */
        val PASSES_RANGE = 1..4
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
    /** 音频发射频率（Hz）＝瀑布上的红线位置（「异频发射」时的设定频率）。 */
    val selectedFreqHz: Int = 1500,
    /**
     * 同频发射：选台时把发射频率（红线）跟到目标频率。
     *
     * - `true`（默认）＝**同频发射**：「点谁打谁」，应答/呼叫我方时红线移到对方频率。
     * - `false`＝**异频发射**（split）：发射固定在 [selectedFreqHz]，选台不改红线（只由用户拖动红线设置）。
     */
    val sameFreqTx: Boolean = true,
    /** 自动程序（工作模式 + 选台规则 + 重发机制 + 保护限制；对应文档 §五菜单）。 */
    val auto: AutoProgramSettings = AutoProgramSettings(),

    // ---- 解码列表过滤（显示层，docs/UI.md §3.3） ----
    /** 已选中的筛选项；空集表示「一个都没开」。 */
    val filterTags: Set<DecodeFilterTag> = setOf(DecodeFilterTag.ALL),
    /** 呼号/前缀过滤串（逗号分隔）。 */
    val callFilter: String = "",
    /** 被忽略的呼号（右滑忽略 / 长按菜单忽略）。 */
    val ignoredCalls: Set<String> = emptySet(),
    /**
     * 关注的呼号（长按菜单「关注 / 取消关注」手动加入，或「自动收录 CQ 台」自动加入）。
     *
     * - ⭐「关注呼号列表」显示这些台；
     * - 照 FT8CN：`autoAddCqToFollow`（自动收录）关掉时，自动程序**仍会**呼叫名单里 CQ 台的 CQ（名单是例外）。
     */
    val followCalls: Set<String> = emptySet(),

    /**
     * [followCalls] 中**由「自动收录 CQ 台」自动加入**的那些呼号，**最近加入在前**；恒为 [followCalls] 子集。
     *
     * 只用于超出 `FollowRoster.AUTO_MAX` 时按「最早收录」淘汰。手动关注的呼号不在此列，永不被淘汰。
     */
    val autoFollowOrder: List<String> = emptyList(),

    // ---- 发射抽屉（docs/UI.md §2.3） ----
    /**
     * CQ 前缀（4×2 = 8 个可编辑格子）。
     *
     * 前缀插在 `CQ` 与我方呼号之间（如 `CQ DX K1ABC FN42`）；**空串＝普通 CQ**。
     * 选中哪一个由 [cqPrefixIndex] 决定，**所有 CQ 都用它**（抽屉「消息类型 6 CQ」与自动程序 / 手动呼叫）。
     */
    val cqPrefixes: List<String> = DEFAULT_CQ_PREFIXES,
    /** 当前选中的 CQ 前缀在 [cqPrefixes] 中的下标（越界按 0 处理）。 */
    val cqPrefixIndex: Int = 0,

    // ---- 地图页（docs/UI.md §2.4） ----
    /** CQ 旗帜是否显示呼号。 */
    val mapCqFlagShowCall: Boolean = true,
    /** CQ 旗帜是否显示信号强度。 */
    val mapCqFlagShowSnr: Boolean = false,
    /** 信号连线是否显示内容文字（关闭则只显示移动方块）。 */
    val mapShowLinkText: Boolean = true,

    // ---- 电台 / PTT（docs/UI.md §2.6） ----
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

    // ---- 音频（docs/UI.md §2.6） ----
    /** 输入设备（空 = 系统默认；枚举依赖 U7）。 */
    val inputDevice: String = "",
    /** 输入增益（dB，−12…+30；生效依赖 U7）。 */
    val inputGainDb: Int = 0,

    // ---- FT8（docs/UI.md §2.6） ----
    /**
     * 时隙偏移（ms，−2500…+2500）：**整个时隙一起偏移**（解码窗口起点 + 发射起点）。
     *
     * 校准用法：操作页解码卡片上的「时间差」就是本机时隙起点与对端的偏差，把它原样填进来
     * （如 +1.5s → +1500），DT 回到约 0 即校准完成；正值 = 推后，负值 = 提前。
     */
    val slotOffsetMs: Int = 0,

    // ---- 高亮与提醒（docs/UI-MOBILE.md §29/§31：颜色与末端标记恒启用，不再提供开关） ----
    /** 含我呼号时哔声提醒（依赖音频，U7）。 */
    val beepOnMyCall: Boolean = false,

    // ---- 外观（docs/UI.md §2.6） ----
    val themeMode: ThemeMode = ThemeMode.DARK,
    val fontSize: FontSize = FontSize.MEDIUM,

    // ---- 界面/音频 ----
    val waterfallHeight: WaterfallHeight = WaterfallHeight.PCT24,
    val sampleRate: SampleRatePref = SampleRatePref.AUTO,

    // ---- 解码参数 ----
    val decode: DecodeSettings = DecodeSettings(),
) {
    /**
     * 当前解码预设：**由 [decode] 反推**（不再单独持久化，避免与新默认值漂移）。
     *
     * 只有 [DecodePreset.FAST] 一个真预设（＝[DecodeSettings] 的默认值，即「快」）；
     * 6 项高级参数被手改过就是 [DecodePreset.CUSTOM]；只改频率范围不算手改（频率范围不随预设变化）。
     */
    val decodePreset: DecodePreset
        get() = if (decode.samePresetValues(DecodeSettings())) DecodePreset.FAST else DecodePreset.CUSTOM

    /** [protocolName] 对应的枚举；非法回落 FT8。 */
    val protocol: Protocol
        get() = Protocol.entries.firstOrNull { it.name == protocolName } ?: Protocol.FT8

    /**
     * 当前 CQ 前缀文本（已去空白 / 转大写；空串＝普通 CQ）。
     *
     * [cqPrefixIndex] 越界时回落 `cqPrefixes[0]`，列表为空时回落空串。
     */
    val cqPrefix: String
        get() = cqPrefixes.getOrElse(cqPrefixIndex) { cqPrefixes.firstOrNull().orEmpty() }
            .trim()
            .uppercase()

    /**
     * 发射前导总时长（ms）＝ PTT 前导静音 [pttDelayMs] + 发射前导音 [txLeadToneMs]。
     *
     * 供「立即发」判定使用：报文波形 + 前导都能在本时隙剩余时间内播完才允许立即发射。
     */
    val txPreambleMs: Int
        get() = pttDelayMs.coerceAtLeast(0) + if (txLeadTone) txLeadToneMs.coerceAtLeast(0) else 0

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
            passes = decode.passes,
        )
}
