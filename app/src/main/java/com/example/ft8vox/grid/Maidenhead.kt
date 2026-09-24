package com.example.ft8vox.grid

/**
 * Maidenhead 网格（locator）工具：解析 / 编码 / 归属。
 *
 * 纯 Kotlin，无 Android 依赖，便于 JVM 单测。
 *
 * 层级（每层为一对字符）：
 * 1. 字段 field：A–R（18 等分），经度 20°、纬度 10°；
 * 2. 方格 square：0–9，经度 2°、纬度 1°；
 * 3. 子方格 subsquare：A–X（24 等分），经度 5′、纬度 2.5′；
 * 4. 扩展 extended：0–9，经度 0.5′、纬度 0.25′。
 */
object Maidenhead {

    /** 网格覆盖的经纬度范围（度）。 */
    data class Bounds(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
    ) {
        val centerLat: Double get() = (minLat + maxLat) / 2.0
        val centerLon: Double get() = (minLon + maxLon) / 2.0
    }

    /** 某层的等分数（radix）：字段 18、方格 10、子方格 24、扩展 10。 */
    private fun radixOf(level: Int): Int = when (level) {
        0 -> 18
        1 -> 10
        2 -> 24
        else -> 10
    }

    /** 归一化：去空白、转大写。 */
    fun normalize(grid: String?): String =
        (grid ?: "").trim().uppercase().replace(" ", "")

    /** 网格是否合法（长度 2/4/6/8 且各层字符在取值范围内）。 */
    fun isValid(grid: String?): Boolean = parse(grid) != null

    /** 取字段（前两位，如 "JN"）；非法返回 null。 */
    fun field(grid: String?): String? {
        val g = normalize(grid)
        return if (g.length >= 2 && g[0] in 'A'..'R' && g[1] in 'A'..'R') g.substring(0, 2) else null
    }

    /** 解析网格为经纬度范围；非法返回 null。 */
    fun parse(grid: String?): Bounds? {
        val g = normalize(grid)
        if (g.length < 2 || g.length > 8 || g.length % 2 != 0) return null

        var lon = -180.0
        var lat = -90.0
        var lonSpan = 360.0
        var latSpan = 180.0

        var level = 0
        var i = 0
        while (i < g.length) {
            val radix = radixOf(level)
            val a = g[i]
            val b = g[i + 1]
            val ia: Int
            val ib: Int
            when (level) {
                0 -> {
                    if (a !in 'A'..'R' || b !in 'A'..'R') return null
                    ia = a - 'A'
                    ib = b - 'A'
                }
                1, 3 -> {
                    if (!a.isDigit() || !b.isDigit()) return null
                    ia = a - '0'
                    ib = b - '0'
                }
                else -> {
                    if (a !in 'A'..'X' || b !in 'A'..'X') return null
                    ia = a - 'A'
                    ib = b - 'A'
                }
            }
            if (ia >= radix || ib >= radix) return null
            lonSpan /= radix
            latSpan /= radix
            lon += ia * lonSpan
            lat += ib * latSpan
            level++
            i += 2
        }
        return Bounds(minLat = lat, maxLat = lat + latSpan, minLon = lon, maxLon = lon + lonSpan)
    }

    /** 网格中心经纬度；非法返回 null。 */
    fun center(grid: String?): Pair<Double, Double>? =
        parse(grid)?.let { it.centerLat to it.centerLon }

    /**
     * 由经纬度生成网格。
     *
     * @param precision 输出字符数（2/4/6/8），奇数会向下取偶后钳到 [2,8]。
     */
    fun encode(lat: Double, lon: Double, precision: Int = 4): String {
        val p = (if (precision % 2 != 0) precision - 1 else precision).coerceIn(2, 8)
        val sb = StringBuilder(p)
        var lonRem = (lon + 180.0).coerceIn(0.0, 360.0)
        var latRem = (lat + 90.0).coerceIn(0.0, 180.0)
        var lonSpan = 360.0
        var latSpan = 180.0

        for (level in 0 until p / 2) {
            val radix = radixOf(level)
            lonSpan /= radix
            latSpan /= radix
            val ia = (lonRem / lonSpan).toInt().coerceIn(0, radix - 1)
            val ib = (latRem / latSpan).toInt().coerceIn(0, radix - 1)
            if (level % 2 == 0) {
                sb.append('A' + ia).append('A' + ib)
            } else {
                sb.append('0' + ia).append('0' + ib)
            }
            lonRem -= ia * lonSpan
            latRem -= ib * latSpan
        }
        return sb.toString()
    }
}
