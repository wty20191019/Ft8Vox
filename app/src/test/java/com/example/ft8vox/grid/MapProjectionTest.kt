package com.example.ft8vox.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Web Mercator 投影与视口的 JVM 单测。 */
class MapProjectionTest {

    @Test
    fun fitCentersWorldAndSetsUnitScale() {
        val p = MapProjection.fit(1000.0, 800.0)
        // 世界为 1×1 方形 → 取较小边
        assertEquals(800.0, p.scale, 1e-9)
        assertEquals(0.0, p.centerLon, 1e-9)
        assertEquals(0.0, p.centerLat, 1e-9)
    }

    @Test
    fun cornersMapToViewportCorners() {
        // 视口比世界宽 → 世界左右各留白 100px（用世界坐标，避免中心在正中时的取整歧义）
        val p = MapProjection.fit(1000.0, 800.0)
        val tl = p.toScreenUV(0.0, 0.0)
        assertEquals(100.0, tl.x, 1e-6)
        assertEquals(0.0, tl.y, 1e-6)
        val br = p.toScreenUV(1.0, 1.0)
        assertEquals(900.0, br.x, 1e-6)
        assertEquals(800.0, br.y, 1e-6)
    }

    @Test
    fun geoRoundTrips() {
        val p = MapProjection.fit(800.0, 800.0)
        val (lat, lon) = p.toGeo(123.0, 45.0)
        val back = p.toScreen(lat, lon)
        assertEquals(123.0, back.x, 1e-6)
        assertEquals(45.0, back.y, 1e-6)
    }

    @Test
    fun zoomKeepsAnchorFixed() {
        val p = MapProjection.fit(800.0, 800.0)
        val z = p.zoomBy(2.0, 0.0, 0.0)
        assertEquals(1600.0, z.scale, 1e-9)
        // 锚点 (0,0) 下的世界西北角缩放后仍在 (0,0)
        val fixed = z.toScreen(MapProjection.MAX_LAT, -180.0)
        assertEquals(0.0, fixed.x, 1e-6)
        assertEquals(0.0, fixed.y, 1e-6)
    }

    @Test
    fun cannotZoomOutBelowFit() {
        val p = MapProjection.fit(800.0, 800.0)
        val z = p.zoomBy(0.1, 400.0, 400.0)
        assertEquals(p.fitScale, z.scale, 1e-9)
    }

    @Test
    fun panMovesCenterAfterZoomIn() {
        val p = MapProjection.fit(800.0, 800.0).zoomBy(4.0, 400.0, 400.0)
        val before = p.centerLon
        val moved = p.panBy(40.0, 0.0)
        assertTrue("拖动后中心应改变", moved.centerLon < before)
        assertEquals(MapProjection.lonOfU(0.5 - 40.0 / p.scale), moved.centerLon, 1e-9)
    }

    @Test
    fun panByWholeWorldKeepsCenter() {
        val p = MapProjection.fit(800.0, 800.0)
        // 世界在 u/v 两个方向的周期都是 1 → 平移整整一个世界应回到原处
        val moved = p.panBy(800.0, 800.0)
        assertEquals(0.0, moved.centerLon, 1e-9)
        assertEquals(0.0, moved.centerLat, 1e-9)
    }

    @Test
    fun panWrapsAcrossSeamInsteadOfClamping() {
        val p = MapProjection.fit(800.0, 800.0)
        // 向左拖 1/4 世界：中心经度 90°W，越界也不被钳回
        val moved = p.panBy(200.0, 0.0)
        assertEquals(-90.0, moved.centerLon, 1e-6)
        // 纵向同理：中心 v 从 0.5 走到 0.25（向北），不被钳制
        val movedV = p.panBy(0.0, 200.0)
        assertEquals(0.25, movedV.centerV, 1e-9)
    }

    @Test
    fun fillUsesLargerDimensionAndHonorsCenter() {
        val p = MapProjection.fill(400.0, 900.0, centerLat = 30.0, centerLon = 10.0)
        assertEquals(900.0, p.scale, 1e-9)
        // 世界循环后不再钳制中心：请求在哪就在哪
        assertEquals(30.0, p.centerLat, 1e-9)
        assertEquals(10.0, p.centerLon, 1e-9)
    }

    @Test
    fun horizontalSeamTargetsRenderOnScreen() {
        // 视口中心在 179°E，目标在 179°W —— 只差 2°，必须落在屏幕内而不是绕到世界另一头
        val p = MapProjection.at(800.0, 800.0, 3200.0, lat = 0.0, lon = 179.0)
        val q = p.toScreen(0.0, -179.0)
        assertEquals(400.0 + (2.0 / 360.0) * 3200.0, q.x, 1e-6)
        assertTrue("跨缝目标应仍在视口内（x=${q.x}）", q.x in 0.0..800.0)
    }

    @Test
    fun verticalSeamTargetsRenderOnScreen() {
        // 中心 v=0（北分界线，视口正好一个世界高）：南半球目标应绕到上边界附近
        val p = MapProjection(800.0, 800.0, 800.0, 0.5, 0.0)
        val q = p.toScreen(-80.0, 0.0)
        val v = MapProjection.mercY(-80.0)
        assertEquals(400.0 + (v - 1.0) * 800.0, q.y, 1e-6)
        assertTrue("纵向跨缝目标应仍在视口内（y=${q.y}）", q.y in 0.0..800.0)
    }

    @Test
    fun nearestCopyPicksSingleRepresentative() {
        val p = MapProjection.at(800.0, 800.0, 3200.0, lat = 0.0, lon = 179.0)
        // 179°E 与 179°W 只差 2°：折算副本后应落在视口中心附近（而不是差近一个世界）
        val a = p.nearestCopy(MapProjection.mercX(179.0), MapProjection.mercY(0.0))
        val b = p.nearestCopy(MapProjection.mercX(-179.0), MapProjection.mercY(0.0))
        assertEquals(400.0, p.toScreenUV(a).x, 1e-6)
        assertEquals(2.0 / 360.0, b.first - a.first, 1e-9)
        assertEquals(400.0, p.toScreenUV(a).y, 1e-6)
    }

    @Test
    fun linkEndsTakeShortArcAcrossSeam() {
        val p = MapProjection.at(800.0, 800.0, 3200.0, lat = 0.0, lon = 179.0)
        val (a, b) = p.linkEnds(fromLat = 0.0, fromLon = 179.0, toLat = 0.0, toLon = -179.0)
        // 短弧：两点相距约 2°（≈17.8 px），而不是绕 358° 横穿整张地图
        assertEquals(2.0 / 360.0 * 3200.0, abs(b.x - a.x), 1e-6)
        assertTrue("连线不应横穿整张地图（dx=${abs(b.x - a.x)}）", abs(b.x - a.x) < 800.0)
    }

    @Test
    fun cellRectStaysAsOnePieceAcrossSeam() {
        val p = MapProjection.at(800.0, 800.0, 3200.0, lat = 0.0, lon = 179.0)
        // 179.5°E–178.5°W 的 1° 网格：中心应落在视口内，宽度约 1° 而不是被撕成两半
        val r = p.cellRect(minLat = -0.5, maxLat = 0.5, minLon = -179.5, maxLon = -178.5)
        assertEquals(1.0 / 360.0 * 3200.0, r.w, 1e-6)
        assertTrue("网格方块中心应在视口内（cx=${r.cx}）", r.cx in 0.0..800.0)
    }

    @Test
    fun toGeoWrapsBackIntoRange() {
        val p = MapProjection.at(800.0, 800.0, 3200.0, lat = 0.0, lon = 0.0)
        // 屏幕外很远的点也要折回 −180…180 / ±MAX_LAT
        val (lat, lon) = p.toGeo(-5000.0, 5000.0)
        assertTrue("经度应折回（lon=$lon）", lon in -180.0..180.0)
        assertTrue("纬度应折回（lat=$lat）", lat in -MapProjection.MAX_LAT..MapProjection.MAX_LAT)
    }

    @Test
    fun mercatorClampsPolarLatitude() {
        assertEquals(MapProjection.mercY(MapProjection.MAX_LAT), MapProjection.mercY(90.0), 1e-12)
        assertEquals(MapProjection.mercY(-MapProjection.MAX_LAT), MapProjection.mercY(-90.0), 1e-12)
    }

    @Test
    fun mercatorRoundTrips() {
        for (lat in listOf(-80.0, -45.0, -10.0, 0.0, 10.0, 45.0, 80.0)) {
            assertEquals(lat, MapProjection.latOfV(MapProjection.mercY(lat)), 1e-9)
        }
    }

    @Test
    fun mercatorDiffersFromEquirectangular() {
        // 等距圆柱下 45°N 的 v = 0.25；墨卡托应更大（高纬放大）
        assertTrue(MapProjection.mercY(45.0) > 0.25)
    }

    @Test
    fun maxScaleCoversBaseImage() {
        val p = MapProjection.fit(800.0, 800.0)
        assertTrue("最大缩放应允许底图 1:1 以上", p.maxScale >= MapProjection.WORLD_IMAGE_PX)
    }
}
