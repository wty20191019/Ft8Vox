package com.example.ft8vox.qso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DXCC 实体 / CQ·ITU 区域表的 JVM 单测（U7）。 */
class DxccTest {

    @Test
    fun resolvesCommonEntitiesWithZones() {
        val ja = Dxcc.resolve("JA1ABC")
        assertEquals("日本", ja?.name)
        assertEquals(25, ja?.cqZone)
        assertEquals(45, ja?.ituZone)

        val w = Dxcc.resolve("W1AW")
        assertEquals("美国", w?.name)
        assertEquals(4, w?.cqZone)
        assertEquals(7, w?.ituZone)

        val g = Dxcc.resolve("G0ABC")
        assertEquals("英格兰", g?.name)
        assertEquals(14, g?.cqZone)
        assertEquals(27, g?.ituZone)

        val dl = Dxcc.resolve("DL1ABC")
        assertEquals("德国", dl?.name)
        assertEquals(28, dl?.ituZone)
    }

    @Test
    fun longestPrefixWins() {
        assertEquals("加那利", Dxcc.resolve("EA8ABC")?.name)
        assertEquals("西班牙", Dxcc.resolve("EA4ABC")?.name)
        assertEquals("休达", Dxcc.resolve("EA9ABC")?.name)
        assertEquals("夏威夷", Dxcc.resolve("KH6ABC")?.name)
        assertEquals("美国", Dxcc.resolve("K1ABC")?.name)
        assertEquals("阿拉斯加", Dxcc.resolve("KL7ABC")?.name)
        assertEquals("关岛", Dxcc.resolve("KH2ABC")?.name)
    }

    @Test
    fun groupsCallAreasIntoOneEntity() {
        // 同一实体的不同呼号区应归并到同一实体
        assertEquals("日本", Dxcc.resolve("JH1XYZ")?.name)
        assertEquals("日本", Dxcc.resolve("JS3ABC")?.name)
        assertEquals("美国", Dxcc.resolve("AA1ZZ")?.name)
        assertEquals("美国", Dxcc.resolve("K5ABC")?.name)
        assertEquals("中国", Dxcc.resolve("BG5ABC")?.name)
        assertEquals("德国", Dxcc.resolve("DH1ABC")?.name)
        assertEquals("意大利", Dxcc.resolve("IK1ABC")?.name)
    }

    @Test
    fun stripsPortableAndCaseInsensitive() {
        assertEquals("法国", Dxcc.resolve("F4FSY/P")?.name)
        assertEquals("日本", Dxcc.resolve("ja1abc")?.name)
        assertEquals("日本", Dxcc.resolve(" JA1ABC ")?.name)
    }

    @Test
    fun returnsNullForUnrecognized() {
        // QQ 未被分配给任何实体（ITU 把 Q 段留给 Q 简码），故仍应返回 null
        assertNull(Dxcc.resolve("QQ9QQQ"))
        assertNull(Dxcc.resolve("HELLO"))
        assertNull(Dxcc.resolve(""))
        assertNull(Dxcc.resolve(null))
        assertNull(Dxcc.resolve("123"))
    }

    @Test
    fun resolvesCorpusDerivedCallAreas() {
        // 语料回测（ft8_w/test 的 1298 条期望报文）暴露出来的缺失呼号区
        assertEquals("沙特", Dxcc.resolve("7Z1AL")?.name)
        assertEquals("德国", Dxcc.resolve("DM2DLG")?.name)
        assertEquals("比利时", Dxcc.resolve("OR7EG")?.name)
        assertEquals("瑞典", Dxcc.resolve("SA0BYP")?.name)
        assertEquals("瑞典", Dxcc.resolve("SB7W")?.name)
        assertEquals("苏格兰", Dxcc.resolve("2M0OGG")?.name)
        assertEquals("圣多美和普林西比", Dxcc.resolve("S9CT")?.name)
        assertEquals("巴西", Dxcc.resolve("ZY50Y")?.name)
        assertEquals("菲律宾", Dxcc.resolve("4F3OM")?.name)
        assertEquals("波兰", Dxcc.resolve("HF19NY")?.name)
        assertEquals("纳米比亚", Dxcc.resolve("V51MA")?.name)
        assertEquals("联合国", Dxcc.resolve("4U1A")?.name)
    }

    @Test
    fun prefixTargetsAllExistInEntityTable() {
        assertEquals(emptyList<String>(), Dxcc.unknownPrefixTargets())
    }

    @Test
    fun entityTableHasUniqueNamesAndValidZones() {
        val names = Dxcc.entities.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(Dxcc.entities.size >= 150)
        assertNotNull(Dxcc.resolve("ZL1ABC"))
        for (e in Dxcc.entities) {
            assertTrue("CQ zone of ${e.name}", e.cqZone in 1..40)
            assertTrue("ITU zone of ${e.name}", e.ituZone in 1..90)
            assertTrue("lat of ${e.name}", e.lat in -90.0..90.0)
            assertTrue("lon of ${e.name}", e.lon in -180.0..180.0)
        }
    }
}
