package com.example.ft8vox.qso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 呼号前缀归属地近似定位的 JVM 单测。 */
class CallLocationTest {

    @Test
    fun locatesCommonPrefixes() {
        assertEquals("日本", CallLocation.locate("JA1ABC")?.name)
        assertEquals("美国", CallLocation.locate("W1AW")?.name)
        assertEquals("英格兰", CallLocation.locate("G0ABC")?.name)
        assertEquals("法国", CallLocation.locate("F4FSY")?.name)
        assertEquals("澳大利亚", CallLocation.locate("VK2ABC")?.name)
        assertEquals("德国", CallLocation.locate("DL1ABC")?.name)
    }

    @Test
    fun longestPrefixWins() {
        // EA8（加那利）优先于 EA（西班牙）
        assertEquals("加那利", CallLocation.locate("EA8ABC")?.name)
        assertEquals("西班牙", CallLocation.locate("EA4ABC")?.name)
        // KH6（夏威夷）优先于 K（美国）
        assertEquals("夏威夷", CallLocation.locate("KH6ABC")?.name)
        assertEquals("美国", CallLocation.locate("K1ABC")?.name)
    }

    @Test
    fun stripsPortableSuffix() {
        assertEquals("法国", CallLocation.locate("F4FSY/P")?.name)
        assertEquals("日本", CallLocation.locate("ja1abc")?.name)
    }

    @Test
    fun unknownOrNonCallsignReturnsNull() {
        assertNull(CallLocation.locate("ZZ9ZZZ"))
        assertNull(CallLocation.locate("HELLO"))
        assertNull(CallLocation.locate(""))
        assertNull(CallLocation.locate(null))
    }
}
