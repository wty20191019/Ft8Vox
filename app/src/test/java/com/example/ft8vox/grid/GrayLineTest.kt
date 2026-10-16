package com.example.ft8vox.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 昼夜灰线几何的 JVM 单测（docs/Ft8Vox.md）。
 *
 * 时间基准用 `QsoTime` 的口径：**UTC 毫秒**（`System.currentTimeMillis()`）。测试里用
 * `java.time.Instant.parse(...).toEpochMilli()` 换算，避免手写魔法数字。
 */
class GrayLineTest {

    private fun utc(iso: String): Long = java.time.Instant.parse(iso).toEpochMilli()

    // ---- 太阳赤纬 ----

    @Test
    fun declinationFollowsSeasons() {
        // 北半球：1 月最南、7 月最北；春分/秋分前后在 0 附近
        val jan = GrayLine.sunDeclination(utc("2026-01-15T12:00:00Z"))
        val apr = GrayLine.sunDeclination(utc("2026-04-15T12:00:00Z"))
        val jul = GrayLine.sunDeclination(utc("2026-07-15T12:00:00Z"))
        val oct = GrayLine.sunDeclination(utc("2026-10-15T12:00:00Z"))
        assertTrue("1 月赤纬应为负（${jan}）", jan < 0.0)
        assertTrue("4 月赤纬应为正（${apr}）", apr in 5.0..20.0)
        assertTrue("7 月赤纬应接近 +23.4（${jul}）", jul in 15.0..23.5)
        assertTrue("10 月赤纬应为负（${oct}）", oct in -20.0..-5.0)
    }

    @Test
    fun declinationNeverExceedsAxialTilt() {
        // 四年内每 5 天采一次：|δ| 不得超过黄赤交角（≈23.5°）
        var ms = utc("2026-01-01T00:00:00Z")
        val step = 5L * 86_400_000L
        var checked = 0
        while (ms < utc("2030-01-01T00:00:00Z")) {
            val dec = GrayLine.sunDeclination(ms)
            assertTrue("赤纬越界：$dec", abs(dec) <= 23.5)
            ms += step
            checked++
        }
        assertTrue(checked > 280)
    }

    // ---- 直射点经度 ----

    @Test
    fun subsolarLongitudeTracksUtcTime() {
        // 12:00 UTC 太阳在格林尼治附近；00:00 UTC 在 180° 附近（均差 ±3° 内）
        val noon = GrayLine.subsolarLongitude(utc("2026-06-21T12:00:00Z"))
        val midnight = GrayLine.subsolarLongitude(utc("2026-06-21T00:00:00Z"))
        assertTrue("12:00 直射点应在 0° 附近（$noon）", abs(noon) < 3.0)
        assertTrue("00:00 直射点应在 ±180° 附近（$midnight）", abs(abs(midnight) - 180.0) < 3.0)
    }

    @Test
    fun subsolarLongitudeIsNormalized() {
        var ms = utc("2026-01-01T00:00:00Z")
        repeat(96) {
            val lon = GrayLine.subsolarLongitude(ms)
            assertTrue("经度未归一：$lon", lon >= -180.0 && lon <= 180.0)
            ms += 3_600_000L * 5
        }
    }

    // ---- 分界线折线 ----

    @Test
    fun terminatorSweepsAllLongitudes() {
        val line = GrayLine.terminator(utc("2026-06-21T12:00:00Z"))
        // −180…+180、步长 2° → 181 个点
        assertEquals(181, line.size)
        assertEquals(-180.0, line.first().first, 1e-9)
        assertEquals(180.0, line.last().first, 1e-9)
        var prev = line.first().first
        for ((lon, lat) in line) {
            assertTrue("经度必须递增：$prev → $lon", lon > prev - 1e-9)
            assertTrue("纬度越界：$lat", lat >= -90.0 && lat <= 90.0)
            prev = lon
        }
    }

    @Test
    fun terminatorReachesArcticCircleAtSummerSolstice() {
        // 夏至：分界线最北到 90° − 23.44° ≈ 66.6°，最南到 −66.6°
        val ms = utc("2026-06-21T12:00:00Z")
        val line = GrayLine.terminator(ms)
        val dec = GrayLine.sunDeclination(ms)
        val maxLat = line.maxOf { it.second }
        val minLat = line.minOf { it.second }
        assertEquals(90.0 - dec, maxLat, 1.5)
        assertEquals(-(90.0 - dec), minLat, 1.5)
    }

    @Test
    fun terminatorPeaksOnTheAntisolarSideOfTheSubsolarMeridian() {
        // 直射点经线是**地方时正午**：那一侧的分界线落在**背对太阳**的半球。
        // 夏至（δ≈+23.44）时正午分界线在南纬 90°−|δ|≈66.6°，对面经线（地方时子夜）才到北纬 66.6°。
        val ms = utc("2026-06-21T12:00:00Z")
        val dec = GrayLine.sunDeclination(ms)
        val sub = GrayLine.subsolarLongitude(ms)
        // δ 为正时北半球是「夏」，子夜经线到北纬极值；δ 为负则反过来
        val s = if (dec >= 0.0) 1.0 else -1.0
        val noon = GrayLine.latAt(sub, dec, sub)
        val midnight = GrayLine.latAt(GrayLine.normalize180(sub + 180.0), dec, sub)
        assertEquals("正午经线应在背对太阳的半球", -s * (90.0 - abs(dec)), noon, 1.5)
        assertEquals("子夜经线应在向阳半球的对面", s * (90.0 - abs(dec)), midnight, 1.5)
        val a = GrayLine.latAt(GrayLine.normalize180(sub + 60.0), dec, sub)
        val b = GrayLine.latAt(GrayLine.normalize180(sub - 60.0), dec, sub)
        assertEquals("关于直射点经线应对称", a, b, 1e-9)
    }

    @Test
    fun terminatorIsPerpendicularToSunDirection() {
        // 物理判据（唯一能测出南北镜像的一条）：分界线上任一点，指向太阳的单位矢量与该点
        // 地平面法线的点积为 0 —— 也就是「太阳正好在地平线上」。
        val cases = listOf(
            "2026-01-15T12:00:00Z",
            "2026-03-20T06:00:00Z",
            "2026-06-21T12:00:00Z",
            "2026-10-14T12:00:00Z",
        )
        for (iso in cases) {
            val ms = utc(iso)
            val dec = GrayLine.sunDeclination(ms)
            val sub = GrayLine.subsolarLongitude(ms)
            for ((lon, lat) in GrayLine.terminator(ms)) {
                val d = Math.toRadians(dec)
                val phi = Math.toRadians(lat)
                val dl = Math.toRadians(GrayLine.normalize180(lon - sub))
                val dot = cos(d) * cos(phi) * cos(dl) + sin(d) * sin(phi)
                assertTrue("$iso 的 ($lon, $lat) 不在晨昏线上（点积 $dot）", abs(dot) < 1e-6)
            }
        }
    }

    @Test
    fun equinoxTerminatorDegeneratesToTwoMeridians() {
        // 春分：δ≈0 → 分界线退化成两条经线（lat → ±90）
        val ms = utc("2026-03-20T12:00:00Z")
        val dec = GrayLine.sunDeclination(ms)
        assertTrue("春分赤纬应接近 0（$dec）", abs(dec) < 1.0)
        val sub = GrayLine.subsolarLongitude(ms)
        val line = GrayLine.terminator(ms)
        // 直射点经线附近取到极端纬度
        val nearSub = line.minByOrNull { abs(GrayLine.normalize180(it.first - sub)) }!!.second
        assertTrue("直射点经线处应接近极点（$nearSub）", abs(nearSub) > 85.0)
    }

    @Test
    fun stepControlsSampleCount() {
        assertEquals(73, GrayLine.terminator(utc("2026-06-21T12:00:00Z"), stepDeg = 5.0).size)
        // 非法步长回退到默认
        assertEquals(181, GrayLine.terminator(utc("2026-06-21T12:00:00Z"), stepDeg = 0.0).size)
    }

    // ---- 屏幕线段（视口内每一份世界副本都要描到） ----

    @Test
    fun screenSegmentsCoverEveryVisibleWorldCopy() {
        // 视口中心放在 120°E（u≈0.833，本机用户所在经度一带）→ 横向同时看到两份世界副本。
        // 旧实现把整条折线只锚在「离视口中心最近的一份副本」上（u∈[1,2] → x∈[83,283]），
        // 视口左边一半就没有线 —— 地图上看到的就是「晨昏线显示不全」。
        val line = GrayLine.terminator(utc("2026-10-14T12:00:00Z"))
        val p = MapProjection(
            viewWidth = 100.0,
            viewHeight = 200.0,
            scale = 200.0,
            centerU = (120.0 + 180.0) / 360.0,
            centerV = 0.5,
        )
        val segs = GrayLine.screenSegments(p, line)
        assertTrue("应有线段", segs.size > 60)
        // 横向每一列都要被某条线段盖住（无缺口）
        for (x in 0..100) {
            val hit = segs.any { (a, b) -> minOf(a.x, b.x) <= x.toDouble() && x.toDouble() <= maxOf(a.x, b.x) }
            assertTrue("x=$x 处没有晨昏线", hit)
        }
        // 线段不得整段落在视口外（两端同侧越界的应当被裁掉）
        val margin = 24.0
        for ((a, b) in segs) {
            assertTrue("线段整段在视口外（x）", !(a.x < -margin && b.x < -margin) && !(a.x > 100 + margin && b.x > 100 + margin))
            assertTrue("线段整段在视口外（y）", !(a.y < -margin && b.y < -margin) && !(a.y > 200 + margin && b.y > 200 + margin))
        }
    }
}
