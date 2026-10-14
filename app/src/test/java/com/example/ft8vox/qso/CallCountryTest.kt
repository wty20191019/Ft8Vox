package com.example.ft8vox.qso

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「呼号 → 国家」配对与解码行第二行对齐的 JVM 单测（U8）。 */
class CallCountryTest {

    private fun tags(text: String) = CallCountry.tags(text)
    private fun grid(text: String) = CallCountry.grid(text)

    @Test
    fun tagsEachCallWithItsCountryAndColumn() {
        assertEquals(
            listOf(
                CallCountry.Tag("VK4BLE", "澳大利亚", 0),
                CallCountry.Tag("OH8JK", "芬兰", 7),
            ),
            tags("VK4BLE OH8JK R-17"),
        )
    }

    @Test
    fun gridAlignsCountriesUnderTheirCalls() {
        // 日本＝4 格；DL1ABC 在第 7 列 → 补 3 格，正落在它上面
        assertEquals("日本" + " ".repeat(3) + "德国", grid("JA1ABC DL1ABC -10"))
        // CQ 报文：JA1ABC 在第 3 列
        assertEquals(" ".repeat(3) + "日本", grid("CQ JA1ABC PM95"))
        // 语料原样（JO1COV / PA0CAH 各 6 格）
        assertEquals("日本" + " ".repeat(3) + "荷兰", grid("JO1COV PA0CAH JO21"))
    }

    @Test
    fun keepsAtLeastOneSpaceWhenCountryIsLonger() {
        // 国家名比它上面的呼号长（澳大利亚＝8 格 > VK4BLE 6 格 / 圣多美和普林西比＝16 格）
        // → 至少空 1 格，两个国家不挤在一起
        assertEquals("澳大利亚 芬兰", grid("VK4BLE OH8JK R-17"))
        assertEquals("圣多美和普林西比 日本", grid("S9CT JA1ABC 73"))
    }

    @Test
    fun tagsSpecialEventCallWithHashedPeer() {
        // 截图里的那条：发方是未解析的哈希 → 解析器当自由文本（to/from 都空），
        // 但呼号本身仍能认出实体（LZ365BM 是保加利亚的俱乐部台）
        assertEquals(listOf(CallCountry.Tag("LZ365BM", "保加利亚", 0)), tags("LZ365BM <...> 73"))
        assertEquals("保加利亚", grid("LZ365BM <...> 73"))
        // 长前缀特殊台（标准呼号正则不认，但前缀表认）
        assertEquals(listOf(CallCountry.Tag("OR18OSB", "比利时", 3)), tags("CQ OR18OSB JO11"))
    }

    @Test
    fun neverTreatsGridOrReportAsStation() {
        // 网格 JO21 / JN89 会被前缀表当成日本（JO/JN ⊆ JA–JS）、RR73 / R-17 会被当成俄罗斯（R）
        // → 必须排除，只留真正的呼号词
        assertEquals(listOf("JO1COV", "PA0CAH"), tags("JO1COV PA0CAH JO21").map { it.call })
        assertEquals(listOf("7Z1AL", "OK2BV"), tags("7Z1AL OK2BV JN89").map { it.call })
        assertEquals(listOf("W1ABC", "K5XYZ"), tags("W1ABC K5XYZ RR73").map { it.call })
        assertEquals(listOf("MM0IMC", "4U1A"), tags("MM0IMC 4U1A -06").map { it.call })
        assertEquals(emptyList<CallCountry.Tag>(), tags("73 73 RRR -07"))
    }

    @Test
    fun skipsCallsMissingFromDxccTableAndKeepsFirstOccurrence() {
        assertEquals(listOf(CallCountry.Tag("OH8JK", "芬兰", 7)), tags("QQ9QQQ OH8JK -07"))
        assertEquals("", grid("QQ9QQQ QQ8QQQ -07"))
        // 同一呼号重复出现只标一次（标在第一次出现的位置）
        assertEquals(listOf(CallCountry.Tag("JA1ABC", "日本", 0)), tags("JA1ABC JA1ABC 73"))
    }

    @Test
    fun corpusDerivedCallAreasShowTheirCountries() {
        val t = tags("7Z1AL OK2BV JN89")
        assertEquals(CallCountry.Tag("7Z1AL", "沙特", 0), t[0])
        assertEquals(CallCountry.Tag("OK2BV", "捷克", 6), t[1])
        assertEquals(listOf(CallCountry.Tag("4U1A", "联合国", 3)), tags("CQ 4U1A JN88"))
        assertEquals(CallCountry.Tag("SA0BYP", "瑞典", 0), tags("SA0BYP DH3JF -12")[0])
        assertEquals(CallCountry.Tag("2M0OGG", "苏格兰", 7), tags("DM2DLG 2M0OGG RR73")[1])
    }
}
