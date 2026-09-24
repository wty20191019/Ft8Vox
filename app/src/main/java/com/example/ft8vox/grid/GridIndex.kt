package com.example.ft8vox.grid

/** 网格地图的着色粒度。 */
enum class GridGranularity(val label: String) {
    /** 大网格（field，20°×10°）。 */
    FIELD("大网格"),

    /** 小网格（square，2°×1°）。 */
    SQUARE("小网格"),
}

/** 一个待着色的网格单元。 */
data class GridCell(
    /** 单元键：field 为 2 字符，square 为 4 字符。 */
    val grid: String,
    val bounds: Maidenhead.Bounds,
    /** 是否已确认（QSL/LoTW）。 */
    val confirmed: Boolean,
)

/**
 * 由通联网格集合构造地图着色单元（纯 Kotlin，可 JVM 单测）。
 *
 * 已确认优先级高于已通联：同一单元既已通联又有确认时，取已确认。
 */
object GridIndex {

    fun cells(
        worked: Collection<String>,
        confirmed: Collection<String>,
        granularity: GridGranularity,
    ): List<GridCell> {
        fun key(g: String): String? = when (granularity) {
            GridGranularity.FIELD -> Maidenhead.field(g)
            GridGranularity.SQUARE -> Maidenhead.normalize(g).takeIf { it.length >= 4 }?.substring(0, 4)
        }

        val confirmedKeys = confirmed.mapNotNull { key(it) }.toSet()
        val byKey = LinkedHashMap<String, GridCell>()
        for (g in worked) {
            val k = key(g) ?: continue
            val bounds = Maidenhead.parse(k) ?: continue
            val conf = k in confirmedKeys
            val prev = byKey[k]
            if (prev == null || (conf && !prev.confirmed)) {
                byKey[k] = GridCell(k, bounds, conf)
            }
        }
        return byKey.values.toList()
    }
}
