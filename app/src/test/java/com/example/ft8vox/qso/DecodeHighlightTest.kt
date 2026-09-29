package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun classifyMyOwnCqIsTopPriority() {
        // 本地回采（声学耦合 / 监听口）：解码列表里出现**我自己发的 CQ**（发方 = 我方呼号）
        val parsed = MessageParser.parse("CQ DX BG7ZW OM89")
        val style = DecodeHighlight.classify(parsed, myCall = "BG7ZW")
        assertEquals(HighlightRole.TX, style.role)
        assertFalse("不是「正在发射」的报文文本匹配路径", style.transmitting)
        assertFalse(style.toMe)
    }

    @Test
    fun classifyMyOwnDirectedMessageIsTopPriority() {
        // 我方发出的定向报文：发方 = 我、收方是对端 ⇒ 既不是「与我有关」也未被文本匹配命中
        val parsed = MessageParser.parse("BG7ZJW BG7ZW R-11")
        val style = DecodeHighlight.classify(parsed, myCall = "BG7ZW")
        assertEquals(HighlightRole.TX, style.role)
        assertFalse(style.transmitting)
        assertFalse(style.toMe)
    }

    @Test
    fun classifyMyOwnPortableCallIsTopPriority() {
        // 复合呼号按宽松口径（`/P`）同样算「我发出去的」
        val parsed = MessageParser.parse("BG7ZJW BG7ZW/P -08")
        val style = DecodeHighlight.classify(parsed, myCall = "BG7ZW")
        assertEquals(HighlightRole.TX, style.role)
    }

    @Test
    fun classifyMyOwnMessageBeatsWorkedAndDuplicate() {
        val w = WorkedIndex(calls = listOf("BG7ZW"))
        val parsed = MessageParser.parse("BG7ZJW BG7ZW R-11")
        val style = DecodeHighlight.classify(parsed, worked = w, duplicate = true, myCall = "BG7ZW")
        assertEquals(HighlightRole.TX, style.role)
    }

    @Test
    fun classifyOtherStationIsNotMarkedAsMyOwnMessage() {
        val parsed = MessageParser.parse("BG7ZJW F4FSY -08")
        assertNotEquals(HighlightRole.TX, DecodeHighlight.classify(parsed, myCall = "BG7ZW").role)
    }

    @Test
    fun classifySimilarCallOrEmptyMyCallIsNotMarkedAsMyOwnMessage() {
        // 只差一个字母的呼号不是自己；未填呼号时也不判
        val similar = MessageParser.parse("CQ BG7ZWX OM89")
        assertNotEquals(HighlightRole.TX, DecodeHighlight.classify(similar, myCall = "BG7ZW").role)
        val mine = MessageParser.parse("CQ BG7ZW OM89")
        assertNotEquals(HighlightRole.TX, DecodeHighlight.classify(mine, myCall = "").role)
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
    fun newGridWinsOverNewEntityWhenBothHit() {
        // 颜色全部恒启用（§29），所以优先级链是固定的：新网格 > 新实体（含新前缀）
        val parsed = MessageParser.parse("W1AW JA1ABC PM95")
        val style = DecodeHighlight.classify(parsed)
        assertEquals(HighlightRole.NEW_GRID, style.role)
        assertTrue(style.newGrid)
        assertTrue(style.newPrefix)
    }

    @Test
    fun workedEntitySuppressesNewItuAndCqZone() {
        // 已通联美国（W1AW）：报文 from = K1ABC（同实体）既不新实体，也不新 ITU / 新 CQ 区域；
        // 只按粗粒度的「新前缀」落到新实体档
        val worked = WorkedIndex(calls = listOf("W1AW"))
        val parsed = MessageParser.parse("JA1ABC K1ABC -08")
        assertEquals("K1ABC", parsed.from)
        val style = DecodeHighlight.classify(parsed, worked)
        assertFalse(style.newEntity)
        assertFalse(style.newItu)
        assertFalse(style.newCqZone)
        assertTrue(style.newPrefix)
        assertEquals(HighlightRole.NEW_ENTITY, style.role)
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
    fun cqAndWorkedWinOverNewCategories() {
        val cq = MessageParser.parse("CQ JA1ABC PM95")
        assertEquals(HighlightRole.CQ, DecodeHighlight.classify(cq).role)
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
