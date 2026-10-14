package com.example.ft8vox.qso

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「呼号 → 国家」配对的 JVM 单测（U8）。 */
class CallCountryTest {

    private fun seg(text: String) = CallCountry.segment(MessageParser.parse(text))

    @Test
    fun tagsEachCallWithItsOwnCountry() {
        // 两个呼号：发方在前、收方在后，各标自己的国家
        assertEquals(
            listOf(
                CallCountry.Tag("VK4BLE", "澳大利亚"),
                CallCountry.Tag("OH8JK", "芬兰"),
            ),
            CallCountry.tags(MessageParser.parse("VK4BLE OH8JK R-17")),
        )
        assertEquals("VK4BLE·澳大利亚 OH8JK·芬兰", seg("VK4BLE OH8JK R-17"))
    }

    @Test
    fun cqMessageTagsTheCallingStationOnly() {
        assertEquals(listOf(CallCountry.Tag("JA1ABC", "日本")), CallCountry.tags(MessageParser.parse("CQ JA1ABC PM95")))
        assertEquals("JA1ABC·日本", seg("CQ JA1ABC PM95"))
    }

    @Test
    fun skipsCallsMissingFromDxccTable() {
        // QQ9QQQ 不在实体表内 → 不占位；同一条里的 OH8JK 照常标
        assertEquals(listOf(CallCountry.Tag("OH8JK", "芬兰")), CallCountry.tags(MessageParser.parse("QQ9QQQ OH8JK -07")))
        assertEquals("", seg("QQ9QQQ QQ8QQQ -07"))
    }

    @Test
    fun corpusDerivedCallAreasShowTheirCountries() {
        // 语料里最常见的几个呼号区（补表后）
        assertEquals("7Z1AL·沙特 OK2BV·捷克", seg("7Z1AL OK2BV JN89"))
        assertEquals("4U1A·联合国", seg("CQ 4U1A JN88"))
        assertEquals("SA0BYP·瑞典 DH3JF·德国", seg("SA0BYP DH3JF -12"))
        assertEquals("DM2DLG·德国 2M0OGG·苏格兰", seg("DM2DLG 2M0OGG RR73"))
    }
}
