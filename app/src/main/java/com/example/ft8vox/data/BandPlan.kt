package com.example.ft8vox.data

import java.util.Locale

/**
 * 业余波段表。
 *
 * 无 CAT 时 App 无法得知电台实际频率，因此以「用户选择的波段 + 刻度频率」为准记录与导出 ADIF。
 * 每个波段给出多个 FT8/FT4 常用刻度频率（[Band.freqs]），[Band.dialHz] 取首项作为该波段的默认频率。
 * 也允许用户自定义波段名与频率（见 [resolveDialHz] / [parseFreqMhz]）。
 */
object BandPlan {

    /** 波段内的一个常用刻度频率。 */
    data class DialFreq(
        /** 刻度频率（Hz）。 */
        val hz: Long,
        /** 模式 / 用途标注，如 "FT8"、"FT4"。 */
        val label: String,
    ) {
        /** MHz 文本（保留 4 位小数，足以区分 FT4 的 7.0475）。 */
        val mhz: String get() = String.format(Locale.US, "%.4f", hz / 1_000_000.0)

        /** 列表显示用，如 "14.0740 MHz · FT8"。 */
        val display: String get() = "$mhz MHz · $label"
    }

    data class Band(
        /** 波段名，如 "20m"。 */
        val name: String,
        /** 该波段的默认刻度频率（Hz，= [freqs] 首项）。 */
        val dialHz: Long,
        /** 波段频率下限（Hz），用于按频率反查波段。 */
        val lowHz: Long,
        /** 波段频率上限（Hz）。 */
        val highHz: Long,
        /** 该波段的常用刻度频率；首项应与 [dialHz] 一致。 */
        val freqs: List<DialFreq> = listOf(DialFreq(dialHz, "FT8")),
    ) {
        /** ADIF 的 FREQ 字段（MHz，保留 5 位小数）。 */
        val freqMhz: String get() = String.format(Locale.US, "%.5f", dialHz / 1_000_000.0)

        /** 某频率是否落在此波段内。 */
        fun containsHz(hz: Long): Boolean = hz in lowHz..highHz
    }

    /** 默认波段。 */
    const val DEFAULT_BAND = "20m"

    /** 频率合法性上限（Hz）：1 GHz，足以覆盖全部业余波段。 */
    const val MAX_FREQ_HZ = 1_000_000_000L

    val bands: List<Band> = listOf(
        Band(
            "160m", 1_840_000L, 1_800_000L, 2_000_000L,
            listOf(DialFreq(1_840_000L, "FT8"), DialFreq(1_836_000L, "FT4")),
        ),
        Band(
            "80m", 3_573_000L, 3_500_000L, 4_000_000L,
            listOf(DialFreq(3_573_000L, "FT8"), DialFreq(3_575_000L, "FT4")),
        ),
        Band(
            "60m", 5_357_000L, 5_060_000L, 5_450_000L,
            listOf(DialFreq(5_357_000L, "FT8"), DialFreq(5_366_500L, "FT4")),
        ),
        Band(
            "40m", 7_074_000L, 7_000_000L, 7_300_000L,
            listOf(DialFreq(7_074_000L, "FT8"), DialFreq(7_047_500L, "FT4"), DialFreq(7_056_000L, "FT4")),
        ),
        Band(
            "30m", 10_136_000L, 10_100_000L, 10_150_000L,
            listOf(DialFreq(10_136_000L, "FT8"), DialFreq(10_140_000L, "FT4")),
        ),
        Band(
            "20m", 14_074_000L, 14_000_000L, 14_350_000L,
            listOf(
                DialFreq(14_074_000L, "FT8"),
                DialFreq(14_080_000L, "FT4"),
                DialFreq(14_090_000L, "FT8 DX"),
                DialFreq(14_095_000L, "FT8 Hound"),
            ),
        ),
        Band(
            "17m", 18_100_000L, 18_068_000L, 18_168_000L,
            listOf(DialFreq(18_100_000L, "FT8"), DialFreq(18_104_000L, "FT4")),
        ),
        Band(
            "15m", 21_074_000L, 21_000_000L, 21_450_000L,
            listOf(DialFreq(21_074_000L, "FT8"), DialFreq(21_140_000L, "FT4")),
        ),
        Band(
            "12m", 24_915_000L, 24_890_000L, 24_990_000L,
            listOf(DialFreq(24_915_000L, "FT8"), DialFreq(24_919_000L, "FT4")),
        ),
        Band(
            "10m", 28_074_000L, 28_000_000L, 29_700_000L,
            listOf(DialFreq(28_074_000L, "FT8"), DialFreq(28_180_000L, "FT4")),
        ),
        Band(
            "6m", 50_313_000L, 50_000_000L, 54_000_000L,
            listOf(DialFreq(50_313_000L, "FT8"), DialFreq(50_318_000L, "FT4")),
        ),
        Band(
            "2m", 144_174_000L, 144_000_000L, 148_000_000L,
            listOf(DialFreq(144_174_000L, "FT8"), DialFreq(144_170_000L, "FT4")),
        ),
    )

    private val byName: Map<String, Band> = bands.associateBy { it.name }

    /** 按波段名查找；未知返回 null。 */
    fun byName(name: String?): Band? = byName[(name ?: "").trim()]

    /** 按波段名取默认刻度频率；未知返回 0。 */
    fun dialHz(name: String?): Long = byName(name)?.dialHz ?: 0L

    /** 特定波段是否收录了某刻度频率。 */
    fun hasFreq(name: String?, hz: Long): Boolean = byName(name)?.containsHz(hz) == true

    /**
     * 解析「波段名 + 期望频率」为最终刻度频率。
     *
     * - [preferred] 合法且属于该波段（或该波段未知，视为自定义波段）时采用；
     * - 否则回落到波段默认频率；波段也未知则保留 [preferred]（可能为 0）。
     */
    fun resolveDialHz(name: String?, preferred: Long): Long {
        val b = byName(name)
        if (preferred > 0 && (b == null || b.containsHz(preferred))) return preferred
        return b?.dialHz ?: preferred.coerceAtLeast(0L)
    }

    /** 按频率（Hz）反查波段；不在任何业余波段内返回 null。 */
    fun fromFreqHz(hz: Long): Band? =
        bands.firstOrNull { hz in it.lowHz..it.highHz }

    /**
     * 解析频率文本（MHz，允许逗号小数点）为 Hz；非法或非正数返回 null。
     */
    fun parseFreqMhz(text: String?): Long? {
        val mhz = text?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        if (mhz <= 0) return null
        val hz = (mhz * 1_000_000).toLong()
        return hz.takeIf { it in 1..MAX_FREQ_HZ }
    }

    /** 按 ADIF 的 FREQ（MHz 文本）反查波段；无法解析返回 null。 */
    fun fromFreqMhz(text: String?): Band? {
        val hz = parseFreqMhz(text) ?: return null
        return fromFreqHz(hz)
    }

    /** 波段名是否已知（自定义波段返回 false）。 */
    fun contains(name: String?): Boolean = byName(name) != null
}
