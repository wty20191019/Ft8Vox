package com.example.ft8vox.qso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 呼号宽松匹配（照 FT8CN，见 `docs/QSO.md` §7）的 JVM 单测。 */
class CallMatchTest {

    @Test
    fun shortCallTakesLongestSlashSegment() {
        assertEquals("BG7ZJW", CallMatch.shortCall("BG7ZJW/P"))
        assertEquals("BG7ZJW", CallMatch.shortCall("BG7ZJW"))
        assertEquals("F4FSY", CallMatch.shortCall("/F4FSY"))
        assertEquals("", CallMatch.shortCall(null))
    }

    @Test
    fun isCallingMeMatchesPortableAndMobileSuffixes() {
        // 我的呼号带后缀 → 报文里的裸呼号也算呼叫我
        assertTrue(CallMatch.isCallingMe("BG7ZJW", "BG7ZJW/P"))
        // 报文里带后缀 → 我的裸呼号也算
        assertTrue(CallMatch.isCallingMe("BG7ZJW/P", "BG7ZJW"))
        assertTrue(CallMatch.isCallingMe("bg7zjw", "BG7ZJW"))
        assertFalse(CallMatch.isCallingMe("K1ABC", "BG7ZJW"))
        assertFalse(CallMatch.isCallingMe(null, "BG7ZJW"))
    }

    @Test
    fun isFromMatchesTargetWithOrWithoutSlash() {
        assertTrue(CallMatch.isFrom("F4FSY", "F4FSY"))
        assertTrue(CallMatch.isFrom("F4FSY/P", "F4FSY"))
        assertTrue(CallMatch.isFrom("F4FSY", "F4FSY/P")) // 目标带 / → contains
        assertFalse(CallMatch.isFrom("K1ABC", "F4FSY"))
        assertFalse(CallMatch.isFrom(null, "F4FSY"))
    }
}
