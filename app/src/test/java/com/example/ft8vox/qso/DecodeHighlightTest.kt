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
    fun workedIndexResolvesEntitiesAndZones() {
        val w = WorkedIndex(calls = listOf("JA1ABC", "W1AW"))
        // 同实体不同呼号区
        assertTrue(w.hasWorkedEntity("JH1XYZ"))
        assertTrue(w.hasWorkedEntity("K5ABC"))
        // 区域跟随实体
        assertTrue(w.hasWorkedCqZone("N0CALL"))
        assertTrue(w.hasWorkedItuZone("AA1ZZ"))
        // 未通联实体
        assertFalse(w.hasWorkedEntity("DL1ABC"))
        assertFalse(w.hasWorkedItuZone("G0ABC"))
        assertFalse(w.hasWorkedCqZone("VK2ABC"))
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
    fun highlightPrefsDisableNewGridFallsThroughToNewEntity() {
        val parsed = MessageParser.parse("W1AW JA1ABC PM95")
        val style = DecodeHighlight.classify(parsed, prefs = HighlightPrefs(newGrid = false))
        assertEquals(HighlightRole.NEW_ENTITY, style.role)
        assertFalse(style.newGrid)
        assertTrue(style.newPrefix)
    }

    @Test
    fun highlightPrefsDisableNewGridAndEntityFallsThroughToNewCall() {
        val parsed = MessageParser.parse("W1AW JA1ABC PM95")
        val style = DecodeHighlight.classify(
            parsed,
            prefs = HighlightPrefs(
                newGrid = false,
                newEntity = false,
                newItu = false,
                newCqZone = false,
                newPrefix = false,
            ),
        )
        assertEquals(HighlightRole.NEW_CALL, style.role)
        assertTrue(style.newCall)
        assertFalse(style.newGrid)
        assertFalse(style.newPrefix)
    }

    @Test
    fun highlightPrefsAllDisabledFallsToNormal() {
        val parsed = MessageParser.parse("W1AW JA1ABC PM95")
        val style = DecodeHighlight.classify(
            parsed,
            prefs = HighlightPrefs(
                newCall = false,
                newGrid = false,
                newEntity = false,
                newItu = false,
                newCqZone = false,
                newPrefix = false,
            ),
        )
        assertEquals(HighlightRole.NORMAL, style.role)
        assertFalse(style.newCall)
    }

    @Test
    fun highlightNewItuAloneStillMarksEntity() {
        val parsed = MessageParser.parse("W1AW JA1ABC PM95")
        val style = DecodeHighlight.classify(
            parsed,
            prefs = HighlightPrefs(
                newGrid = false,
                newEntity = false,
                newPrefix = false,
                newItu = true,
                newCqZone = false,
            ),
        )
        assertEquals(HighlightRole.NEW_ENTITY, style.role)
        assertTrue(style.newItu)
        assertFalse(style.newEntity)
        assertFalse(style.newCqZone)
    }

    @Test
    fun workedEntitySuppressesNewItuAndCqZone() {
        // 已通联美国（W1AW）：报文 from = K1ABC（同实体）既不新实体，也不新 ITU / 新 CQ 区域
        // （关闭「新前缀」以排除粗粒度前缀路径的干扰）
        val worked = WorkedIndex(calls = listOf("W1AW"))
        val parsed = MessageParser.parse("JA1ABC K1ABC -08")
        assertEquals("K1ABC", parsed.from)
        val style = DecodeHighlight.classify(parsed, worked, prefs = HighlightPrefs(newPrefix = false))
        assertFalse(style.newEntity)
        assertFalse(style.newItu)
        assertFalse(style.newCqZone)
        assertFalse(style.newPrefix)
        assertEquals(HighlightRole.NEW_CALL, style.role)
    }

    @Test
    fun hasNewEntityMarkCoversAllEntitySources() {
        assertTrue(DecodeStyle(HighlightRole.NEW_ENTITY, newEntity = true).hasNewEntityMark)
        assertTrue(DecodeStyle(HighlightRole.NEW_ENTITY, newItu = true).hasNewEntityMark)
        assertTrue(DecodeStyle(HighlightRole.NEW_ENTITY, newCqZone = true).hasNewEntityMark)
        assertTrue(DecodeStyle(HighlightRole.NEW_ENTITY, newPrefix = true).hasNewEntityMark)
        assertFalse(DecodeStyle(HighlightRole.NORMAL).hasNewEntityMark)
    }

    @Test
    fun highlightPrefsDoNotAffectCqOrWorkedPriorities() {
        val cq = MessageParser.parse("CQ JA1ABC PM95")
        assertEquals(
            HighlightRole.CQ,
            DecodeHighlight.classify(cq, prefs = HighlightPrefs(newCall = false, newGrid = false)).role,
        )
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val direct = MessageParser.parse("F4FSY JA1ABC -08")
        assertEquals(HighlightRole.WORKED, DecodeHighlight.classify(direct, worked).role)
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
