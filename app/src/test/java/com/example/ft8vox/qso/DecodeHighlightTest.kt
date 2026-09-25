package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 呼号前缀近似、已通联索引、解码高亮与去重键的 JVM 单测。 */
class DecodeHighlightTest {

    private fun decoded(text: String, snr: Int = -10, df: Int = 1000, slotUtcMs: Long = 0L) =
        DecodeResult(text = text, snr = snr, dt = 0f, df = df, score = 20, slotUtcMs = slotUtcMs)

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
    fun classifyCqIsCqRoleWithNewGrid() {
        val parsed = MessageParser.parse("CQ JA1ABC PM95")
        val style = DecodeHighlight.classify(parsed)
        assertEquals(HighlightRole.CQ, style.role)
        assertTrue(style.isCq)
        assertTrue(style.newGrid)
        assertTrue(style.newPrefix)
        assertTrue(style.newCall)
    }

    @Test
    fun classifyToMeBeatsNewGrid() {
        val parsed = MessageParser.parse("F4FSY JA1ABC PM95")
        val style = DecodeHighlight.classify(parsed, myCall = "F4FSY")
        assertEquals(HighlightRole.TO_ME, style.role)
        assertTrue(style.toMe)
    }

    @Test
    fun classifyCurrentQsoIsToMeWithCurrentFlag() {
        val parsed = MessageParser.parse("F4FSY JA1ABC -08")
        val style = DecodeHighlight.classify(parsed, currentQsoCall = "JA1ABC", myCall = "F4FSY")
        assertEquals(HighlightRole.TO_ME, style.role)
        assertTrue(style.current)
        assertTrue(style.toMe)
    }

    @Test
    fun classifyWorkedCallIsWorked() {
        val w = WorkedIndex(calls = listOf("JA1ABC"), grids = listOf("PM95"))
        val parsed = MessageParser.parse("F4FSY JA1ABC -08")
        val style = DecodeHighlight.classify(parsed, w)
        assertEquals(HighlightRole.WORKED, style.role)
        assertTrue(style.worked)
        assertFalse(style.newGrid)
        assertFalse(style.newPrefix)
    }

    @Test
    fun classifyDuplicateBeatsNewCall() {
        val parsed = MessageParser.parse("CQ W1AW FN42")
        val style = DecodeHighlight.classify(parsed, duplicate = true)
        // CQ 优先级高于重复
        assertEquals(HighlightRole.CQ, style.role)
        val nonCq = MessageParser.parse("W1AW JA1ABC -08")
        assertEquals(HighlightRole.DUPLICATE, DecodeHighlight.classify(nonCq, duplicate = true).role)
    }

    @Test
    fun classifyTransmittingIsTopPriority() {
        val parsed = MessageParser.parse("F4FSY JA1ABC -08")
        val style = DecodeHighlight.classify(
            parsed,
            currentQsoCall = "JA1ABC",
            myCall = "F4FSY",
            currentTxText = "F4FSY JA1ABC -08",
        )
        assertEquals(HighlightRole.TX, style.role)
        assertTrue(style.transmitting)
    }

    @Test
    fun classifyNewEntityWhenGridAlreadyWorked() {
        val w = WorkedIndex(grids = listOf("PM95"))
        val parsed = MessageParser.parse("W1AW JA1ABC PM95")
        val style = DecodeHighlight.classify(parsed, w)
        assertEquals(HighlightRole.NEW_ENTITY, style.role)
        assertFalse(style.newGrid)
        assertTrue(style.newPrefix)
    }

    @Test
    fun classifyNewGridForNonCqDirectMessage() {
        val parsed = MessageParser.parse("W1AW JA1ABC PM95")
        val style = DecodeHighlight.classify(parsed)
        assertEquals(HighlightRole.NEW_GRID, style.role)
        assertTrue(style.newGrid)
    }

    @Test
    fun classifyFreeTextWithoutCallIsNormal() {
        val parsed = MessageParser.parse("TNX 73 GL")
        val style = DecodeHighlight.classify(parsed)
        assertEquals(HighlightRole.NORMAL, style.role)
        assertFalse(style.newGrid)
        assertFalse(style.newPrefix)
    }

    @Test
    fun duplicateRowKeysMarksRepeatsExceptOldest() {
        val msgs = listOf(
            decoded("CQ W1AW FN42", slotUtcMs = 3000),
            decoded("CQ JA1ABC PM95", slotUtcMs = 2000),
            decoded("CQ W1AW FN42", slotUtcMs = 1000),
        )
        val dup = DecodeHighlight.duplicateRowKeys(msgs)
        assertEquals(setOf("CQ W1AW FN42@3000"), dup)
    }

    @Test
    fun rowKeyIsStableByTextAndSlot() {
        assertEquals("CQ W1AW FN42@1000", DecodeHighlight.rowKey("CQ W1AW FN42", 1000))
        assertEquals("CQ W1AW FN42@1000", DecodeHighlight.rowKey(" CQ W1AW FN42 ", 1000))
    }
}
