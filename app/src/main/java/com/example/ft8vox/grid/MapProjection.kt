package com.example.ft8vox.grid

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** 屏幕像素坐标（纯 Kotlin，避免依赖 Compose，便于 JVM 单测）。 */
data class PlotPoint(val x: Double, val y: Double)

/**
 * Web Mercator（EPSG:3857）投影 + 视口（缩放 / 平移），纯函数，便于单测。
 *
 * 世界坐标用归一化 Mercator 立方体：`u∈[0,1]`（经度，西→东）、`v∈[0,1]`（纬度，北→南），
 * 因此世界是 **1×1 的正方形**，`scale` = 「世界 1 单位 = 多少屏幕像素」。
 *
 * 之所以不用等距圆柱：离线卫星底图（`assets/map/world_z5.jpg`）是标准 Web Mercator
 * XYZ 图像，只有同投影才能让底图与网格/呼号标记严格对齐（否则纬度越高偏差越大）。
 *
 * 注意：纬度钳制在 ±[MAX_LAT]（墨卡托在极点发散）；只保证中心在合理范围内（[clamped]），
 * 不做边界硬裁剪，允许少量越界留白。
 */
data class MapProjection(
    val viewWidth: Double,
    val viewHeight: Double,
    val scale: Double,
    val centerLon: Double,
    val centerLat: Double,
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

        /** 适应窗口：整个世界缩放到视口内并居中。 */
        fun fit(viewWidth: Double, viewHeight: Double): MapProjection {
            val w = viewWidth.coerceAtLeast(1.0)
            val h = viewHeight.coerceAtLeast(1.0)
            return MapProjection(w, h, minOf(w, h), 0.0, 0.0)
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
            return MapProjection(w, h, maxOf(w, h), centerLon, centerLat).clamped()
        }
    }

    /** 视口中心的世界坐标。 */
    val centerU: Double get() = mercX(centerLon)
    val centerV: Double get() = mercY(centerLat)

    /** 适应窗口对应的缩放（也是允许的最小缩放，不能再缩小到世界之外）。 */
    val fitScale: Double get() = minOf(viewWidth, viewHeight)
    val minScale: Double get() = fitScale

    /** 最大缩放：允许在底图 1:1 基础上再放大 2×（再多就是无意义的插值放大）。 */
    val maxScale: Double get() = maxOf(WORLD_IMAGE_PX * 2.0, fitScale * 6.0)

    /** 经纬度 → 屏幕像素。 */
    fun toScreen(lat: Double, lon: Double): PlotPoint = toScreenUV(mercX(lon), mercY(lat))

    /** 世界坐标 u,v（∈[0,1]）→ 屏幕像素。 */
    fun toScreenUV(u: Double, v: Double): PlotPoint = PlotPoint(
        viewWidth / 2.0 + (u - centerU) * scale,
        viewHeight / 2.0 + (v - centerV) * scale,
    )

    /** 屏幕像素 → 经纬度（lat 在前）。 */
    fun toGeo(x: Double, y: Double): Pair<Double, Double> {
        val (u, v) = toUV(x, y)
        return latOfV(v) to lonOfU(u)
    }

    /** 屏幕像素 → 世界坐标 u,v（∈[0,1]；不钳制，可能越界）。 */
    fun toUV(x: Double, y: Double): Pair<Double, Double> =
        (centerU + (x - viewWidth / 2.0) / scale) to (centerV + (y - viewHeight / 2.0) / scale)

    /** 平移：屏幕像素位移（含手势的 dx/dy）。 */
    fun panBy(dxPx: Double, dyPx: Double): MapProjection {
        val u = centerU - dxPx / scale
        val v = centerV - dyPx / scale
        return clamped(copy(centerLon = lonOfU(u), centerLat = latOfV(v)))
    }

    /** 以屏幕点 ([anchorX], [anchorY]) 为锚缩放，锚点下的地理位置保持不动。 */
    fun zoomBy(factor: Double, anchorX: Double, anchorY: Double): MapProjection {
        val ns = (scale * factor).coerceIn(minScale, maxScale)
        if (ns == scale) return this
        val (au, av) = toUV(anchorX, anchorY)
        val u = au - (anchorX - viewWidth / 2.0) / ns
        val v = av - (anchorY - viewHeight / 2.0) / ns
        return clamped(copy(scale = ns, centerLon = lonOfU(u), centerLat = latOfV(v)))
    }

    /** 把中心钳制到世界范围内（视口比世界还大时居中）。 */
    fun clamped(): MapProjection = clamped(this)

    /** 把中心钳制到世界范围内（视口比世界还大时居中）。 */
    private fun clamped(p: MapProjection): MapProjection {
        val halfU = (viewWidth / 2.0) / p.scale
        val halfV = (viewHeight / 2.0) / p.scale
        val u = if (halfU >= 0.5) 0.5 else p.centerU.coerceIn(halfU, 1.0 - halfU)
        val v = if (halfV >= 0.5) 0.5 else p.centerV.coerceIn(halfV, 1.0 - halfV)
        return p.copy(centerLon = lonOfU(u), centerLat = latOfV(v))
    }
}
