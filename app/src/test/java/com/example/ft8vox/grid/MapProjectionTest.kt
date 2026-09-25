package com.example.ft8vox.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val p = MapProjection.fit(800.0, 800.0)
        val tl = p.toScreen(MapProjection.MAX_LAT, -180.0)
        assertEquals(0.0, tl.x, 1e-6)
        assertEquals(0.0, tl.y, 1e-6)
        val br = p.toScreen(-MapProjection.MAX_LAT, 180.0)
        assertEquals(800.0, br.x, 1e-6)
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
    fun panAtFitIsClampedToCenter() {
        val p = MapProjection.fit(800.0, 800.0)
        val moved = p.panBy(50.0, 50.0)
        assertEquals(0.0, moved.centerLon, 1e-9)
        assertEquals(0.0, moved.centerLat, 1e-9)
    }

    @Test
    fun fillUsesLargerDimensionAndClampsCenter() {
        val p = MapProjection.fill(400.0, 900.0, centerLat = 30.0, centerLon = 10.0)
        assertEquals(900.0, p.scale, 1e-9)
        // 高度方向铺满 → 纬度中心被钳到赤道
        assertEquals(0.0, p.centerLat, 1e-9)
        assertEquals(10.0, p.centerLon, 1e-9)
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
