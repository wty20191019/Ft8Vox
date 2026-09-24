package com.example.ft8vox.data

import java.util.Locale

/**
 * 业余波段表。
 *
 * 无 CAT 时 App 无法得知电台实际频率，因此以「用户选择的波段」为准记录与导出 ADIF。
 * [dialHz] 取 FT8/FT4 的常用刻度频率，用于 ADIF 的 FREQ 字段。
 */
object BandPlan {

    data class Band(
        /** 波段名，如 "20m"。 */
        val name: String,
        /** 该波段 FT8 常用刻度频率（Hz）。 */
        val dialHz: Long,
        /** 波段频率下限（Hz），用于按频率反查波段。 */
        val lowHz: Long,
        /** 波段频率上限（Hz）。 */
        val highHz: Long,
    ) {
        /** ADIF 的 FREQ 字段（MHz，保留 5 位小数）。 */
        val freqMhz: String get() = String.format(Locale.US, "%.5f", dialHz / 1_000_000.0)
    }

    /** 默认波段。 */
    const val DEFAULT_BAND = "20m"

    val bands: List<Band> = listOf(
        Band("160m", 1_840_000L, 1_800_000L, 2_000_000L),
        Band("80m", 3_573_000L, 3_500_000L, 4_000_000L),
        Band("60m", 5_357_000L, 5_060_000L, 5_450_000L),
        Band("40m", 7_074_000L, 7_000_000L, 7_300_000L),
        Band("30m", 10_136_000L, 10_100_000L, 10_150_000L),
        Band("20m", 14_074_000L, 14_000_000L, 14_350_000L),
        Band("17m", 18_100_000L, 18_068_000L, 18_168_000L),
        Band("15m", 21_074_000L, 21_000_000L, 21_450_000L),
        Band("12m", 24_915_000L, 24_890_000L, 24_990_000L),
        Band("10m", 28_074_000L, 28_000_000L, 29_700_000L),
        Band("6m", 50_313_000L, 50_000_000L, 54_000_000L),
        Band("2m", 144_174_000L, 144_000_000L, 148_000_000L),
    )

    private val byName: Map<String, Band> = bands.associateBy { it.name }

    /** 按波段名查找；未知返回 null。 */
    fun byName(name: String?): Band? = byName[(name ?: "").trim()]

    /** 按波段名取刻度频率；未知返回 0。 */
    fun dialHz(name: String?): Long = byName(name)?.dialHz ?: 0L

    /** 按频率（Hz）反查波段；不在任何业余波段内返回 null。 */
    fun fromFreqHz(hz: Long): Band? =
        bands.firstOrNull { hz in it.lowHz..it.highHz }

    /** 按 ADIF 的 FREQ（MHz 文本）反查波段；无法解析返回 null。 */
    fun fromFreqMhz(text: String?): Band? {
        val mhz = text?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        return fromFreqHz((mhz * 1_000_000).toLong())
    }

    /** 波段名是否已知。 */
    fun contains(name: String?): Boolean = byName(name) != null
}
