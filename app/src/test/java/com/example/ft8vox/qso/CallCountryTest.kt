package com.example.ft8vox.qso

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「呼号 → 国家」配对与解码行第二行对齐的 JVM 单测（U8）。 */
class CallCountryTest {

    private fun tags(text: String) = CallCountry.tags(MessageParser.parse(text))
    private fun grid(text: String) = CallCountry.grid(MessageParser.parse(text))

    @Test
    fun tagsEachCallWithItsCountryAndColumn() {
        // FT8 定向报文＝「收方 发方」；col 是该呼号在报文原文里的起始列
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
    fun skipsCallsMissingFromDxccTable() {
        assertEquals(listOf(CallCountry.Tag("OH8JK", "芬兰", 7)), tags("QQ9QQQ OH8JK -07"))
        assertEquals("", grid("QQ9QQQ QQ8QQQ -07"))
    }

    @Test
    fun skipsCallNotFoundInMessageText() {
        // 呼号不在报文原文里（例如只有哈希占位）→ 不占位，也不影响另一个呼号
        val parsed = ParsedMessage(raw = "CQ JA1ABC PM95", isCq = true, from = "JA1ABC", to = "K1ABC")
        assertEquals(listOf(CallCountry.Tag("JA1ABC", "日本", 3)), CallCountry.tags(parsed))
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
