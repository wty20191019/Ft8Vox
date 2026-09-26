package com.example.ft8vox.grid

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sinh
import kotlin.math.tan

/** 屏幕像素坐标（纯 Kotlin，避免依赖 Compose，便于 JVM 单测）。 */
data class PlotPoint(val x: Double, val y: Double)

/** 屏幕上的矩形（中心 + 尺寸，像素）；用于网格方块这类「中心固定、尺寸另算」的图形。 */
data class PlotRect(val cx: Double, val cy: Double, val w: Double, val h: Double)

/** 视口覆盖的世界坐标范围（**未**按世界周期折叠，可能越出 [0,1]）。 */
data class WorldRange(val minU: Double, val maxU: Double, val minV: Double, val maxV: Double)

/**
 * Web Mercator（EPSG:3857）投影 + 视口（缩放 / 平移），纯函数，便于单测。
 *
 * 世界坐标用归一化 Mercator 立方体：`u`（经度，西→东）、`v`（纬度，北→南），
 * 因此世界是 **1×1 的正方形**，`scale` = 「世界 1 单位 = 多少屏幕像素」。
 *
 * **世界在横向与纵向都循环**（把 [0,1)² 当环面）：视口状态只记 [centerU]/[centerV]，
 * 绘制方对每个目标调用 [nearestCopy] 取「离视口中心最近的一份副本」（整数个世界宽/高的平移），
 * 于是平移跨过 ±180°（或南北分界线）不会出现硬边，且同一目标永远只画一份。
 * 纵向循环是纯视觉装置：越过 ±[MAX_LAT] 后接的是另一侧的极区，会有一次纬度跳变
 * （墨卡托在极点发散，无法避免）。
 *
 * 之所以不用等距圆柱：离线卫星底图（`assets/map/world_z5.jpg`）是标准 Web Mercator
 * XYZ 图像，只有同投影才能让底图与网格/呼号标记严格对齐（否则纬度越高偏差越大）。
 */
data class MapProjection(
    val viewWidth: Double,
    val viewHeight: Double,
    val scale: Double,
    /** 视口中心的世界 u（经度方向）；可为任意实数，与目标相差的整数值即「世界副本」偏移。 */
    val centerU: Double,
    /** 视口中心的世界 v（纬度方向，北 0、南 1）；可为任意实数。 */
    val centerV: Double,
) {
    companion object {
        /** Web Mercator 可表示的最大纬度（±85.05112878°）。 */
        const val MAX_LAT = 85.05112877980659

        /** 离线底图边长（`assets/map/world_z5.jpg` = z5 = 8192 px），用于限制最大缩放。 */
        const val WORLD_IMAGE_PX = 8192.0

        /** 经度 → 世界 u（∈[0,1]）。 */
        fun mercX(lon: Double): Double = (lon + 180.0) / 360.0

        /** 纬度 → 世界 v（∈[0,1]，北 0、南 1）；输入自动钳制到 ±[MAX_LAT]。 */
        fun mercY(lat: Double): Double {
            val phi = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
            return 0.5 - ln(tan(PI / 4.0 + phi / 2.0)) / (2.0 * PI)
        }

        /** 世界 u → 经度。 */
        fun lonOfU(u: Double): Double = u * 360.0 - 180.0

        /** 世界 v → 纬度。 */
        fun latOfV(v: Double): Double = atan(sinh(PI * (1.0 - 2.0 * v))) * 180.0 / PI

        /** 取模 1 到 [0,1)（世界在 u/v 两个方向的周期都是 1）。 */
        fun wrap01(x: Double): Double = x - floor(x)

        /** 适应窗口：整个世界缩放到视口内并居中。 */
        fun fit(viewWidth: Double, viewHeight: Double): MapProjection {
            val w = viewWidth.coerceAtLeast(1.0)
            val h = viewHeight.coerceAtLeast(1.0)
            return MapProjection(w, h, minOf(w, h), 0.5, 0.5)
        }

        /**
         * 填满窗口：整个世界按较大的一维缩放（会裁掉一部分），并以 [centerLat]/[centerLon] 为中心。
         *
         * 竖屏手机上比 [fit] 更实用：可视范围更大、留白更少。用户可双指缩小回到全球视图。
         */
        fun fill(
            viewWidth: Double,
            viewHeight: Double,
            centerLat: Double = 0.0,
            centerLon: Double = 0.0,
        ): MapProjection {
            val w = viewWidth.coerceAtLeast(1.0)
            val h = viewHeight.coerceAtLeast(1.0)
            return at(w, h, maxOf(w, h), centerLat, centerLon)
        }

        /** 以某个经纬度为中心构造视口（世界坐标取 mod 1）。 */
        fun at(
            viewWidth: Double,
            viewHeight: Double,
            scale: Double,
            lat: Double,
            lon: Double,
        ): MapProjection = MapProjection(
            viewWidth.coerceAtLeast(1.0),
            viewHeight.coerceAtLeast(1.0),
            scale,
            wrap01(mercX(lon)),
            wrap01(mercY(lat)),
        )
    }

    /** 视口中心经度（由 [centerU] 反推，恒在 −180…180）。 */
    val centerLon: Double get() = lonOfU(wrap01(centerU))

    /** 视口中心纬度（由 [centerV] 反推；跨越南北分界线时会有一次跳变）。 */
    val centerLat: Double get() = latOfV(wrap01(centerV))

    /** 适应窗口对应的缩放（也是允许的最小缩放，不能再缩小到世界之外）。 */
    val fitScale: Double get() = minOf(viewWidth, viewHeight)
    val minScale: Double get() = fitScale

    /** 最大缩放：允许在底图 1:1 基础上再放大 2×（再多就是无意义的插值放大）。 */
    val maxScale: Double get() = maxOf(WORLD_IMAGE_PX * 2.0, fitScale * 6.0)

    /** 视口覆盖的世界坐标范围（未折叠；基图据此枚举「世界副本」）。 */
    fun visibleWorldRange(): WorldRange {
        val hw = (viewWidth / 2.0) / scale
        val hh = (viewHeight / 2.0) / scale
        return WorldRange(centerU - hw, centerU + hw, centerV - hh, centerV + hh)
    }

    /**
     * 把世界坐标 (u,v) 平移整数个世界宽/高，使其离 ([anchorU],[anchorV]) 最近。
     *
     * 这是「同一目标只画一份」与「连线走短弧」的唯一判据：整数差代表同一地理点的
     * 另一份世界副本，选最近的那份既避免重复，也保证跨界线时位置连续。
     */
    fun copyNearestTo(anchorU: Double, anchorV: Double, u: Double, v: Double): Pair<Double, Double> =
        (u + round(anchorU - u)) to (v + round(anchorV - v))

    /** 把世界坐标 (u,v) 平移到离视口中心最近的那份副本。 */
    fun nearestCopy(u: Double, v: Double): Pair<Double, Double> =
        copyNearestTo(centerU, centerV, u, v)

    /** 经纬度 → 屏幕像素（取离视口中心最近的那份世界副本）。 */
    fun toScreen(lat: Double, lon: Double): PlotPoint = toScreenUV(nearestCopy(mercX(lon), mercY(lat)))

    /** 世界坐标 u,v → 屏幕像素（u/v 可为任意实数：整数差即「世界副本」偏移）。 */
    fun toScreenUV(u: Double, v: Double): PlotPoint = toScreenUV(u to v)

    /** 世界坐标 (u,v) → 屏幕像素。 */
    fun toScreenUV(uv: Pair<Double, Double>): PlotPoint = PlotPoint(
        viewWidth / 2.0 + (uv.first - centerU) * scale,
        viewHeight / 2.0 + (uv.second - centerV) * scale,
    )

    /**
     * 一条连线两个端点的屏幕像素：**起点**取离视口中心最近的一份副本，
     * **终点**取离起点最近的那份 → 跨 180° 时连线永远走短弧，不会横穿整张地图。
     */
    fun linkEnds(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Pair<PlotPoint, PlotPoint> {
        val (au, av) = nearestCopy(mercX(fromLon), mercY(fromLat))
        val (bu, bv) = copyNearestTo(au, av, mercX(toLon), mercY(toLat))
        return toScreenUV(au to av) to toScreenUV(bu to bv)
    }

    /**
     * 网格方块（Maidenhead）在屏幕上的中心与尺寸（像素）；只取离视口中心最近的一份副本。
     *
     * 尺寸按**中心所在副本**里的经纬跨度折算，两角因此不会各挑一份副本（跨界线时不会撕裂）。
     */
    fun cellRect(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): PlotRect {
        val cu = (mercX(minLon) + mercX(maxLon)) / 2.0
        val cv = (mercY(minLat) + mercY(maxLat)) / 2.0
        val c = toScreenUV(nearestCopy(cu, cv))
        return PlotRect(
            cx = c.x,
            cy = c.y,
            w = (mercX(maxLon) - mercX(minLon)) * scale,
            h = (mercY(minLat) - mercY(maxLat)) * scale,
        )
    }

    /** 屏幕像素 → 经纬度（lat 在前）。 */
    fun toGeo(x: Double, y: Double): Pair<Double, Double> {
        val (u, v) = toUV(x, y)
        return latOfV(wrap01(v)) to lonOfU(wrap01(u))
    }

    /** 屏幕像素 → 世界坐标 u,v（不钳制，可能越界；整数差即世界副本偏移）。 */
    fun toUV(x: Double, y: Double): Pair<Double, Double> =
        (centerU + (x - viewWidth / 2.0) / scale) to (centerV + (y - viewHeight / 2.0) / scale)

    /** 平移：屏幕像素位移（含手势的 dx/dy）；中心按世界周期收回 [0,1)。 */
    fun panBy(dxPx: Double, dyPx: Double): MapProjection = copy(
        centerU = wrap01(centerU - dxPx / scale),
        centerV = wrap01(centerV - dyPx / scale),
    )

    /** 以屏幕点 ([anchorX], [anchorY]) 为锚缩放，锚点下的地理位置保持不动。 */
    fun zoomBy(factor: Double, anchorX: Double, anchorY: Double): MapProjection {
        val ns = (scale * factor).coerceIn(minScale, maxScale)
        if (ns == scale) return this
        val (au, av) = toUV(anchorX, anchorY)
        val u = au - (anchorX - viewWidth / 2.0) / ns
        val v = av - (anchorY - viewHeight / 2.0) / ns
        return copy(scale = ns, centerU = wrap01(u), centerV = wrap01(v))
    }
}
