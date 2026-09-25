package com.example.ft8vox.grid

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 大圆距离与方位角（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * 用于解码详情半屏的「距离 / 方位」以及地图页的连线信息。
 */
object Geo {

    /** 地球平均半径（km）。 */
    private const val R_KM = 6371.0088

    private fun rad(deg: Double): Double = deg * PI / 180.0
    private fun deg(rad: Double): Double = rad * 180.0 / PI

    /** 两点大圆距离（km）。 */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2).pow(2)
        return 2 * R_KM * kotlin.math.asin(min(1.0, sqrt(a)))
    }

    /** 起点指向终点的初始方位角（0–360°，正北为 0，顺时针）。 */
    fun azimuthDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1)
        val p2 = rad(lat2)
        val dl = rad(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        val az = deg(atan2(y, x))
        return (az + 360.0) % 360.0
    }

    /** 两个网格中心之间的距离（km）与方位角；任一网格非法返回 null。 */
    fun betweenGrids(grid1: String?, grid2: String?): Pair<Double, Double>? {
        val a = Maidenhead.center(grid1) ?: return null
        val b = Maidenhead.center(grid2) ?: return null
        return distanceKm(a.first, a.second, b.first, b.second) to
            azimuthDeg(a.first, a.second, b.first, b.second)
    }

    /** 方位角对应的八向罗盘（N / NE / E / SE / S / SW / W / NW）。 */
    fun compass(azDeg: Double): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((azDeg % 360 + 360) % 360) / 45.0).roundToInt() % 8
        return dirs[idx]
    }
}
