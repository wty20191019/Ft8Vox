package com.example.ft8vox.grid

/** 屏幕像素坐标（纯 Kotlin，避免依赖 Compose，便于 JVM 单测）。 */
data class PlotPoint(val x: Double, val y: Double)

/**
 * 等距圆柱投影 + 视口（缩放 / 平移），纯函数，便于单测。
 *
 * 世界坐标定义：经度 `lon∈[-180,180]` → `wx = lon+180 ∈[0,360]`；
 * 纬度 `lat∈[-90,90]` → `wy = 90-lat ∈[0,180]`。
 * 屏幕坐标：以视口中心为原点，`scale` 为「世界 1 度 = 多少像素」。
 *
 * 注意：只保证中心在合理范围内（[clamped]），不做边界硬裁剪，允许少量越界留白。
 */
data class MapProjection(
    val viewWidth: Double,
    val viewHeight: Double,
    val scale: Double,
    val centerLon: Double,
    val centerLat: Double,
) {
    companion object {
        const val WORLD_W = 360.0
        const val WORLD_H = 180.0

        /** 适应窗口：整个世界缩放到视口内并居中。 */
        fun fit(viewWidth: Double, viewHeight: Double): MapProjection {
            val w = viewWidth.coerceAtLeast(1.0)
            val h = viewHeight.coerceAtLeast(1.0)
            val s = minOf(w / WORLD_W, h / WORLD_H)
            return MapProjection(w, h, s, 0.0, 0.0)
        }

        /**
         * 填满窗口：整个世界按较大的一维缩放（会裁掉一部分），并以 [centerLat]/[centerLon] 为中心。
         *
         * 竖屏手机上比 [fit] 更实用：网格单元更大、留白更少。用户可双指缩小回到全球视图。
         */
        fun fill(
            viewWidth: Double,
            viewHeight: Double,
            centerLat: Double = 0.0,
            centerLon: Double = 0.0,
        ): MapProjection {
            val w = viewWidth.coerceAtLeast(1.0)
            val h = viewHeight.coerceAtLeast(1.0)
            val s = maxOf(w / WORLD_W, h / WORLD_H)
            return MapProjection(w, h, s, centerLon, centerLat).clamped()
        }
    }

    /** 适应窗口对应的缩放（也是允许的最小缩放，不能再缩小到世界之外）。 */
    val fitScale: Double get() = minOf(viewWidth / WORLD_W, viewHeight / WORLD_H)
    val minScale: Double get() = fitScale
    val maxScale: Double get() = fitScale * 48.0

    /** 经纬度 → 屏幕像素。 */
    fun toScreen(lat: Double, lon: Double): PlotPoint = PlotPoint(
        viewWidth / 2.0 + ((lon + 180.0) - (centerLon + 180.0)) * scale,
        viewHeight / 2.0 + ((90.0 - lat) - (90.0 - centerLat)) * scale,
    )

    /** 屏幕像素 → 经纬度（lat 在前）。 */
    fun toGeo(x: Double, y: Double): Pair<Double, Double> {
        val wx = (x - viewWidth / 2.0) / scale + (centerLon + 180.0)
        val wy = (y - viewHeight / 2.0) / scale + (90.0 - centerLat)
        return (90.0 - wy) to (wx - 180.0)
    }

    /** 平移：屏幕像素位移（含手势的 dx/dy）。 */
    fun panBy(dxPx: Double, dyPx: Double): MapProjection =
        clamped(copy(centerLon = centerLon - dxPx / scale, centerLat = centerLat + dyPx / scale))

    /** 以屏幕点 ([anchorX], [anchorY]) 为锚缩放，锚点下的地理位置保持不动。 */
    fun zoomBy(factor: Double, anchorX: Double, anchorY: Double): MapProjection {
        val ns = (scale * factor).coerceIn(minScale, maxScale)
        if (ns == scale) return this
        val (lat, lon) = toGeo(anchorX, anchorY)
        val cxWorld = (lon + 180.0) - (anchorX - viewWidth / 2.0) / ns
        val cyWorld = (90.0 - lat) - (anchorY - viewHeight / 2.0) / ns
        return clamped(copy(scale = ns, centerLon = cxWorld - 180.0, centerLat = 90.0 - cyWorld))
    }

    /** 把中心钳制到世界范围内（视口比世界还大时居中）。 */
    fun clamped(): MapProjection = clamped(this)

    /** 把中心钳制到世界范围内（视口比世界还大时居中）。 */
    private fun clamped(p: MapProjection): MapProjection {
        val halfLon = (viewWidth / 2.0) / p.scale
        val halfLat = (viewHeight / 2.0) / p.scale
        val cx = if (halfLon >= WORLD_W / 2.0) 0.0 else p.centerLon.coerceIn(-180.0 + halfLon, 180.0 - halfLon)
        val cy = if (halfLat >= WORLD_H / 2.0) 0.0 else p.centerLat.coerceIn(-90.0 + halfLat, 90.0 - halfLat)
        return p.copy(centerLon = cx, centerLat = cy)
    }
}
