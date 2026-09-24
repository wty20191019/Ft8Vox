package com.example.ft8vox.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 等距圆柱投影与视口的 JVM 单测。 */
class MapProjectionTest {

    @Test
    fun fitCentersWorldAndSetsUnitScale() {
        val p = MapProjection.fit(360.0, 180.0)
        assertEquals(1.0, p.scale, 1e-9)
        assertEquals(0.0, p.centerLon, 1e-9)
        assertEquals(0.0, p.centerLat, 1e-9)
    }

    @Test
    fun cornersMapToViewportCorners() {
        val p = MapProjection.fit(360.0, 180.0)
        val tl = p.toScreen(90.0, -180.0)
        assertEquals(0.0, tl.x, 1e-6)
        assertEquals(0.0, tl.y, 1e-6)
        val br = p.toScreen(-90.0, 180.0)
        assertEquals(360.0, br.x, 1e-6)
        assertEquals(180.0, br.y, 1e-6)
    }

    @Test
    fun geoRoundTrips() {
        val p = MapProjection.fit(360.0, 180.0)
        val (lat, lon) = p.toGeo(123.0, 45.0)
        val back = p.toScreen(lat, lon)
        assertEquals(123.0, back.x, 1e-6)
        assertEquals(45.0, back.y, 1e-6)
    }

    @Test
    fun zoomKeepsAnchorFixed() {
        val p = MapProjection.fit(360.0, 180.0)
        val z = p.zoomBy(2.0, 0.0, 0.0)
        assertEquals(2.0, z.scale, 1e-9)
        // 锚点 (0,0) 下的地理坐标缩放后仍在 (0,0)
        val fixed = z.toScreen(90.0, -180.0)
        assertEquals(0.0, fixed.x, 1e-6)
        assertEquals(0.0, fixed.y, 1e-6)
    }

    @Test
    fun cannotZoomOutBelowFit() {
        val p = MapProjection.fit(360.0, 180.0)
        val z = p.zoomBy(0.1, 180.0, 90.0)
        assertEquals(p.fitScale, z.scale, 1e-9)
    }

    @Test
    fun panMovesCenterAfterZoomIn() {
        val p = MapProjection.fit(360.0, 180.0).zoomBy(4.0, 180.0, 90.0)
        val before = p.centerLon
        val moved = p.panBy(40.0, 0.0)
        assertTrue("拖动后中心应改变", moved.centerLon < before)
        assertEquals(before - 40.0 / p.scale, moved.centerLon, 1e-6)
    }

    @Test
    fun panAtFitIsClampedToCenter() {
        val p = MapProjection.fit(360.0, 180.0)
        val moved = p.panBy(50.0, 50.0)
        assertEquals(0.0, moved.centerLon, 1e-9)
        assertEquals(0.0, moved.centerLat, 1e-9)
    }

    @Test
    fun fillUsesLargerDimensionAndClampsCenter() {
        val p = MapProjection.fill(360.0, 900.0, centerLat = 30.0, centerLon = 10.0)
        assertEquals(5.0, p.scale, 1e-9)
        // 高度方向铺满 → 纬度中心被钳到赤道
        assertEquals(0.0, p.centerLat, 1e-9)
        assertEquals(10.0, p.centerLon, 1e-9)
    }
}
