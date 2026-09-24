package com.example.ft8vox.qso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 呼号前缀近似、已通联索引与解码高亮的 JVM 单测。 */
class DecodeHighlightTest {

    @Test
    fun prefixTakesLeadingLettersBeforeDigit() {
        assertEquals("JA", CallPrefix.of("JA1ABC"))
        assertEquals("W", CallPrefix.of("W1AW"))
        assertEquals("F", CallPrefix.of("f4fsy"))
    }

    @Test
    fun prefixStripsPortableSuffix() {
        assertEquals("F", CallPrefix.of("F4FSY/P"))
        assertEquals("DL", CallPrefix.of("DL1ABC/MM"))
    }

    @Test
    fun prefixNullForNonCallsign() {
        assertEquals(null, CallPrefix.of(null))
        assertEquals(null, CallPrefix.of(""))
        assertEquals(null, CallPrefix.of("123"))
    }

    @Test
    fun workedIndexNormalizesGridsToSquare() {
        val w = WorkedIndex(grids = listOf("pm95ab", "JN25"))
        assertTrue(w.hasWorkedGrid("PM95"))
        assertTrue(w.hasWorkedGrid("PM95ab"))
        assertTrue(w.hasWorkedGrid("jn25"))
        assertFalse(w.hasWorkedGrid("AA00"))
        assertFalse(w.hasWorkedGrid(null))
    }

    @Test
    fun workedIndexBuildsPrefixesFromCalls() {
        val w = WorkedIndex(calls = listOf("ja1abc", "W1AW"))
        assertTrue(w.hasWorkedPrefix("JA2XYZ"))
        assertTrue(w.hasWorkedPrefix("w6abc"))
        assertFalse(w.hasWorkedPrefix("DL1ABC"))
    }

    @Test
    fun classifyCqWithNewGrid() {
        val parsed = MessageParser.parse("CQ JA1ABC PM95")
        val style = DecodeHighlight.classify(parsed)
        assertEquals(HighlightRole.NEW_GRID, style.role)
        assertTrue(style.isCq)
        assertTrue(style.newGrid)
        assertTrue(style.newPrefix)
    }

    @Test
    fun classifyToMeBeatsNewGrid() {
        val parsed = MessageParser.parse("F4FSY JA1ABC PM95")
        val style = DecodeHighlight.classify(parsed, myCall = "F4FSY")
        assertEquals(HighlightRole.TO_ME, style.role)
        assertTrue(style.toMe)
    }

    @Test
    fun classifyCurrentQsoBeatsToMe() {
        val parsed = MessageParser.parse("F4FSY JA1ABC -08")
        val style = DecodeHighlight.classify(parsed, currentQsoCall = "JA1ABC", myCall = "F4FSY")
        assertEquals(HighlightRole.CURRENT_QSO, style.role)
    }

    @Test
    fun classifyWorkedCallAndGridIsNormal() {
        val w = WorkedIndex(calls = listOf("JA1ABC"), grids = listOf("PM95"))
        val parsed = MessageParser.parse("CQ JA1ABC PM95")
        val style = DecodeHighlight.classify(parsed, w)
        assertEquals(HighlightRole.NORMAL, style.role)
        assertTrue(style.worked)
        assertFalse(style.newGrid)
        assertFalse(style.newPrefix)
    }

    @Test
    fun classifyNewPrefixWhenGridAlreadyWorked() {
        val w = WorkedIndex(grids = listOf("PM95"))
        val parsed = MessageParser.parse("CQ JA1ABC PM95")
        val style = DecodeHighlight.classify(parsed, w)
        assertEquals(HighlightRole.NEW_PREFIX, style.role)
        assertFalse(style.newGrid)
        assertTrue(style.newPrefix)
    }

    @Test
    fun classifyFreeTextWithoutCallIsNormal() {
        val parsed = MessageParser.parse("TNX 73 GL")
        val style = DecodeHighlight.classify(parsed)
        assertEquals(HighlightRole.NORMAL, style.role)
        assertFalse(style.newGrid)
        assertFalse(style.newPrefix)
    }
}
