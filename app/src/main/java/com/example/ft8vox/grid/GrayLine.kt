package com.example.ft8vox.grid

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * 昼夜分界（灰线）几何 —— 照 FT8CN `GridOsmMapView.computeDayNightTerminator`
 * （docs/Ft8Vox.md）。
 *
 * 纯 Kotlin（无 Android / Compose 依赖），可 JVM 单测。太阳位置用与 FT8CN 相同的低精度级数
 * （儒略日 → 平黄经 / 平近点角 → 黄道经度 → 赤纬），误差在角分量级 —— 画一条地图灰线足够。
 *
 * 分界线（太阳高度角 = 0）的纬度公式：
 *
 * ```
 * lat(lon) = atan(cos(lon − 直射点经度) / tan(δ))
 * ```
 *
 * δ 为太阳赤纬：分界线在直射点经度处到达 ±(90° − |δ|)，在对面经度处翻到下半个球。
 */
object GrayLine {

    /** 采样经度步长（度）：每 2° 取一点，共 181 个点连成一条折线。 */
    const val STEP_DEG = 2.0

    /**
     * 灰线重算间隔（ms）。
     *
     * 地球自转 15°/h ⇒ 直射点经度每分钟只走 0.25°，5 分钟刷新一次肉眼完全看不出跳变。
     * （FT8CN 是进地图时算一次；本机地图页可能长时间停留，所以按时间刷新。）
     */
    const val REFRESH_MS = 5 * 60 * 1000L

    private const val K = PI / 180.0

    /** 太阳赤纬（度，−23.44…+23.44）：北半球夏至最大、冬至最小。 */
    fun sunDeclination(utcMs: Long): Double {
        val t = centuriesSinceJ2000(utcMs)
        val lambda = sunEclipticLongitude(t)
        val eps = obliquity(t)
        return asin(sin(K * eps) * sin(K * lambda)) / K
    }

    /** 太阳直射点经度（度，−180…180）：该经度上太阳在天顶（正午前后）。 */
    fun subsolarLongitude(utcMs: Long): Double {
        val jd = julianDate(utcMs)
        val tau = (jd - floor(jd)) * 360.0
        return normalize180(-tau)
    }

    /**
     * 昼夜分界线：返回 `(lon, lat)` 列表，经度从 −180 到 +180 逐点推进（含两端）。
     *
     * 调用方（[com.example.ft8vox.ui.GridMap]）按顺序连折线，并用「离上一个点最近的世界副本」
     * 处理跨 180° 的接缝，因此这里只给一串孤立的地理点即可。
     */
    fun terminator(utcMs: Long, stepDeg: Double = STEP_DEG): List<Pair<Double, Double>> {
        val dec = sunDeclination(utcMs)
        val sub = subsolarLongitude(utcMs)
        val step = if (stepDeg > 0.0) stepDeg else STEP_DEG
        val n = (360.0 / step).toInt()
        val out = ArrayList<Pair<Double, Double>>(n + 1)
        for (i in 0..n) {
            val lon = normalize180(-180.0 + i * step)
            out.add(lon to latAt(lon, dec, sub))
        }
        return out
    }

    /** 分界线在经度 [lon] 处的纬度（度）；[dec] 为赤纬、[subsolarLon] 为直射点经度。 */
    fun latAt(lon: Double, dec: Double, subsolarLon: Double): Double {
        val c = cos(K * (lon - subsolarLon))
        val denom = tan(K * dec)
        // 春/秋分前后 δ≈0：分界线退化成两条经线（tan δ → 0），此时 lat → ±90
        if (denom == 0.0) {
            return when {
                c > 0.0 -> 90.0
                c < 0.0 -> -90.0
                else -> 0.0
            }
        }
        return atan(c / denom) / K
    }

    /** 经度归一到 −180…180。 */
    fun normalize180(lon: Double): Double {
        val m = lon % 360.0
        return when {
            m > 180.0 -> m - 360.0
            m < -180.0 -> m + 360.0
            else -> m
        }
    }

    /** 儒略日（Unix 纪元 → 天文纪元，`.5` 是「中午 vs 午夜」）。 */
    private fun julianDate(utcMs: Long): Double = utcMs / 86_400_000.0 + 2_440_587.5

    /** 自 J2000 起算的儒略世纪数。 */
    private fun centuriesSinceJ2000(utcMs: Long): Double = (julianDate(utcMs) - 2_451_545.0) / 36_525.0

    /** 太阳黄道经度（度）。级数照 FT8CN（Meeus 低精度）。 */
    private fun sunEclipticLongitude(t: Double): Double {
        val l = normalize360(280.46645 + 36_000.76983 * t + 0.0003032 * t * t)
        val m = normalize360(357.52910 + 35_999.05030 * t - 0.0001559 * t * t - 0.00000048 * t * t * t)
        val c = (1.914600 - 0.004817 * t - 0.000014 * t * t) * sin(K * m) +
            (0.019993 - 0.000101 * t) * sin(K * 2 * m) +
            0.000290 * sin(K * 3 * m)
        return l + c
    }

    /** 黄赤交角（度，含章动与光行差修正）。级数照 FT8CN。 */
    private fun obliquity(t: Double): Double {
        val ls = normalize360(280.46645 + 36_000.76983 * t + 0.0003032 * t * t)
        val lm = 218.3165 + 481_267.8813 * t
        val eps0 = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0 -
            (46.8150 * t + 0.00059 * t * t - 0.001813 * t * t * t) / 3600.0
        val omega = 125.04452 - 1934.136261 * t + 0.0020708 * t * t + t * t * t / 450_000.0
        val dEps = (9.20 * cos(K * omega) + 0.57 * cos(K * 2 * ls) +
            0.10 * cos(K * 2 * lm) - 0.09 * cos(K * 2 * omega)) / 3600.0
        return eps0 + dEps + 0.00256 * cos(K * (125.04 - 1934.136 * t))
    }

    /** 归一到 [0, 360)。 */
    private fun normalize360(deg: Double): Double {
        val m = deg % 360.0
        return if (m < 0.0) m + 360.0 else m
    }
}
