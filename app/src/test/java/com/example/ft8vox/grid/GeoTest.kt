package com.example.ft8vox.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 大圆距离 / 方位角的 JVM 单测。 */
class GeoTest {

    @Test
    fun distanceAlongEquator() {
        // 赤道上 1° 经度 ≈ 111.2 km
        val d = Geo.distanceKm(0.0, 0.0, 0.0, 1.0)
        assertTrue(d in 111.0..111.5)
    }

    @Test
    fun distanceIsSymmetricAndZeroAtSamePoint() {
        assertEquals(0.0, Geo.distanceKm(35.0, 139.0, 35.0, 139.0), 0.001)
        val a = Geo.distanceKm(35.0, 139.0, 48.0, 2.0)
        val b = Geo.distanceKm(48.0, 2.0, 35.0, 139.0)
        assertEquals(a, b, 0.001)
    }

    @Test
    fun azimuthCardinals() {
        assertEquals(0.0, Geo.azimuthDeg(0.0, 0.0, 1.0, 0.0), 0.5)
        assertEquals(90.0, Geo.azimuthDeg(0.0, 0.0, 0.0, 1.0), 0.5)
        assertEquals(180.0, Geo.azimuthDeg(0.0, 0.0, -1.0, 0.0), 0.5)
        assertEquals(270.0, Geo.azimuthDeg(0.0, 0.0, 0.0, -1.0), 0.5)
    }

    @Test
    fun compassNames() {
        assertEquals("N", Geo.compass(0.0))
        assertEquals("E", Geo.compass(90.0))
        assertEquals("SW", Geo.compass(225.0))
        assertEquals("N", Geo.compass(359.0))
    }

    @Test
    fun betweenGridsResolvesDistanceAndAzimuth() {
        val r = Geo.betweenGrids("JN25", "PM95")
        assertTrue(r != null)
        assertTrue(r!!.first > 1000.0) // 欧洲 ↔ 日本 约 9000+ km
        assertTrue(r.second in 0.0..360.0)
    }

    @Test
    fun betweenGridsNullOnInvalid() {
        assertNull(Geo.betweenGrids(null, "PM95"))
        assertNull(Geo.betweenGrids("PM95", "ZZ99"))
    }
}
