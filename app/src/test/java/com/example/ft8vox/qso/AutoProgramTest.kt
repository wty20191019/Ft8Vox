package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「自动程序」选台与决策的 JVM 单测。 */
class AutoProgramTest {

    private fun decoded(text: String, snr: Int = -10, df: Int = 1000, slotUtcMs: Long = 0L) = DecodeResult(
        text = text,
        snr = snr,
        dt = 0f,
        df = df,
        score = 20,
        slotUtcMs = slotUtcMs,
    )

    private val program = AutoProgramSettings(level = AutoLevel.UNTIL_END)

    // ---- 等级 ----

    @Test
    fun manualReturnsNone() {
        val d = AutoProgramSelector.decide(
            listOf(decoded("CQ JA1ABC PM95", snr = -5)),
            AutoProgramSettings(level = AutoLevel.MANUAL),
        )
        assertTrue(d is AutoDecision.None)
    }

    @Test
    fun firstDecodePicksFirstCandidate() {
        val d = AutoProgramSelector.decide(
            listOf(
                decoded("CQ JA1ABC PM95", snr = -12),
                decoded("CQ W1AW FN42", snr = -3),
            ),
            AutoProgramSettings(level = AutoLevel.FIRST_DECODE),
        )
        assertEquals("JA1ABC", (d as AutoDecision.AnswerCq).target.call)
    }

    @Test
    fun decodeWindowPicksStrongest() {
        val d = AutoProgramSelector.decide(
            listOf(
                decoded("CQ JA1ABC PM95", snr = -12),
                decoded("CQ W1AW FN42", snr = -3),
                decoded("CQ DL1ABC JN48", snr = -8),
            ),
            AutoProgramSettings(level = AutoLevel.DECODE_WINDOW),
        )
        assertEquals("W1AW", (d as AutoDecision.AnswerCq).target.call)
    }

    @Test
    fun autoCqWhenNoCandidates() {
        val d = AutoProgramSelector.decide(
            listOf(decoded("JA1ABC W1AW -08")),
            AutoProgramSettings(level = AutoLevel.AUTO_CQ),
            myCall = "F4FSY",
        )
        assertTrue(d is AutoDecision.CallCq)
    }

    @Test
    fun untilEndReturnsNoneWhenNoCandidates() {
        val d = AutoProgramSelector.decide(
            listOf(decoded("JA1ABC W1AW -08")),
            AutoProgramSettings(level = AutoLevel.UNTIL_END),
            myCall = "F4FSY",
        )
        assertTrue(d is AutoDecision.None)
    }

    // ---- 已通联台 ----

    @Test
    fun workedExcludedByDefault() {
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val d = AutoProgramSelector.decide(
            listOf(decoded("CQ JA1ABC PM95", snr = -2)),
            program,
            worked = worked,
        )
        assertTrue(d is AutoDecision.None)
    }

    @Test
    fun answerWorkedAllowsWorkedCq() {
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val d = AutoProgramSelector.decide(
            listOf(decoded("CQ JA1ABC PM95", snr = -2)),
            program.copy(answerWorked = true),
            worked = worked,
        )
        assertEquals("JA1ABC", (d as AutoDecision.AnswerCq).target.call)
    }

    @Test
    fun workedOnlyAutoSearchSendsCqWhenDisallowed() {
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val d = AutoProgramSelector.decide(
            listOf(decoded("CQ JA1ABC PM95", snr = -2)),
            AutoProgramSettings(level = AutoLevel.AUTO_CQ),
            worked = worked,
        )
        assertTrue(d is AutoDecision.CallCq)
    }

    @Test
    fun preferNewCallRanksNewFirst() {
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val d = AutoProgramSelector.decide(
            listOf(
                decoded("CQ JA1ABC PM95", snr = -2),
                decoded("CQ W1AW FN42", snr = -9),
            ),
            program.copy(answerWorked = true, preferNewCall = true),
            worked = worked,
        )
        assertEquals("W1AW", (d as AutoDecision.AnswerCq).target.call)
    }

    @Test
    fun preferNewCallOffPicksStrongestEvenWorked() {
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val d = AutoProgramSelector.decide(
            listOf(
                decoded("CQ JA1ABC PM95", snr = -2),
                decoded("CQ W1AW FN42", snr = -9),
            ),
            program.copy(answerWorked = true, preferNewCall = false),
            worked = worked,
        )
        assertEquals("JA1ABC", (d as AutoDecision.AnswerCq).target.call)
    }

    // ---- 最远距离取代最佳信噪比 ----

    @Test
    fun farthestOverSnrPicksFartherStation() {
        val d = AutoProgramSelector.decide(
            listOf(
                decoded("CQ JA1ABC PM96", snr = -2), // 近
                decoded("CQ DL1ABC JN48", snr = -15), // 远
            ),
            program.copy(farthestOverSnr = true),
            myGrid = "PM95",
        )
        assertEquals("DL1ABC", (d as AutoDecision.AnswerCq).target.call)
    }

    @Test
    fun strongestByDefaultPicksHigherSnr() {
        val d = AutoProgramSelector.decide(
            listOf(
                decoded("CQ JA1ABC PM96", snr = -2),
                decoded("CQ DL1ABC JN48", snr = -15),
            ),
            program,
            myGrid = "PM95",
        )
        assertEquals("JA1ABC", (d as AutoDecision.AnswerCq).target.call)
    }

    // ---- 发给我方的定向报文（被呼自动应答） ----

    @Test
    fun answersDirectedReport() {
        val d = AutoProgramSelector.decide(
            listOf(decoded("F4FSY JA1ABC -12", snr = -6, df = 1521, slotUtcMs = 30_000L)),
            program.copy(reportPriority = false),
            myCall = "F4FSY",
        )
        val t = (d as AutoDecision.AnswerDirected).target
        assertEquals("JA1ABC", t.call)
        assertEquals(-12, t.report)
        assertEquals(1521, t.df)
        assertEquals(30_000L, t.slotUtcMs)
    }

    @Test
    fun answersDirectedCallWithGrid() {
        // 对方呼叫我方（应答我之前的 CQ）：<myCall> <theirCall> <grid>
        val d = AutoProgramSelector.decide(
            listOf(decoded("F4FSY JA1ABC PM95", snr = -7, slotUtcMs = 45_000L)),
            program,
            myCall = "F4FSY",
        )
        val t = (d as AutoDecision.AnswerDirected).target
        assertEquals("JA1ABC", t.call)
        assertEquals(AutoTargetKind.CALL, t.kind)
        assertEquals("PM95", t.grid)
    }

    @Test
    fun answersDirectedRoger() {
        // 对方 Roger 我的报告：<myCall> <theirCall> R-12
        val d = AutoProgramSelector.decide(
            listOf(decoded("F4FSY JA1ABC R-12", snr = -9)),
            program,
            myCall = "F4FSY",
        )
        val t = (d as AutoDecision.AnswerDirected).target
        assertEquals(AutoTargetKind.ROGER, t.kind)
        assertEquals(-12, t.report)
    }

    @Test
    fun ignoresDirected73AsNewQso() {
        // 73/RR73 表示通联已结束，不作为新 QSO 的起点
        val d = AutoProgramSelector.decide(
            listOf(
                decoded("F4FSY JA1ABC 73"),
                decoded("F4FSY JA1ABC RR73"),
            ),
            program,
            myCall = "F4FSY",
        )
        assertTrue(d is AutoDecision.None)
    }

    @Test
    fun ignoresMessageAddressedToOthers() {
        val d = AutoProgramSelector.decide(
            listOf(decoded("JA1ABC W1AW -12")),
            program,
            myCall = "F4FSY",
        )
        assertTrue(d is AutoDecision.None)
    }

    @Test
    fun reportPriorityRanksReportBeforeCq() {
        val d = AutoProgramSelector.decide(
            listOf(
                decoded("CQ W1AW FN42", snr = -2),
                decoded("F4FSY JA1ABC -12", snr = -15),
            ),
            program.copy(reportPriority = true),
            myCall = "F4FSY",
        )
        assertTrue(d is AutoDecision.AnswerDirected)
        assertEquals("JA1ABC", (d as AutoDecision.AnswerDirected).target.call)
    }

    @Test
    fun withoutReportPriorityCqMayWinOnSnr() {
        // reportPriority 关闭时，定向报文与 CQ 一起按信噪比排序
        val d = AutoProgramSelector.decide(
            listOf(
                decoded("CQ W1AW FN42", snr = -2),
                decoded("F4FSY JA1ABC -12", snr = -15),
            ),
            program.copy(reportPriority = false),
            myCall = "F4FSY",
        )
        assertEquals("W1AW", (d as AutoDecision.AnswerCq).target.call)
    }

    // ---- 失败重试 ----

    @Test
    fun retryCallIsPreferredWhenPresent() {
        val d = AutoProgramSelector.decide(
            listOf(
                decoded("CQ W1AW FN42", snr = -2),
                decoded("CQ JA1ABC PM95", snr = -15),
            ),
            program,
            retryCall = "JA1ABC",
        )
        assertEquals("JA1ABC", (d as AutoDecision.AnswerCq).target.call)
    }

    @Test
    fun retryCallIgnoredWhenAbsent() {
        val d = AutoProgramSelector.decide(
            listOf(decoded("CQ W1AW FN42", snr = -2)),
            program,
            retryCall = "JA1ABC",
        )
        assertEquals("W1AW", (d as AutoDecision.AnswerCq).target.call)
    }

    // ---- 默认值 ----

    @Test
    fun defaultIsContinuousNotSingleQso() {
        assertTrue(!AutoProgramSettings().singleQso)
    }

    // ---- 过滤 / 去重 ----

    @Test
    fun respectsFilterTags() {
        val replyOnly = DecodeFilterState(tags = setOf(DecodeFilterTag.REPLY))
        val d = AutoProgramSelector.decide(
            listOf(decoded("CQ JA2XYZ PM96", snr = -2)),
            program,
            filter = replyOnly,
        )
        assertTrue(d is AutoDecision.None)
    }

    @Test
    fun ignoresIgnoredCalls() {
        val filter = DecodeFilterState(
            tags = setOf(DecodeFilterTag.ALL),
            ignoredCalls = setOf("JA1ABC"),
        )
        val d = AutoProgramSelector.decide(
            listOf(
                decoded("CQ JA1ABC PM95", snr = -2),
                decoded("CQ JA2XYZ PM96", snr = -9),
            ),
            program,
            filter = filter,
        )
        assertEquals("JA2XYZ", (d as AutoDecision.AnswerCq).target.call)
    }

    @Test
    fun ignoresOwnCall() {
        val d = AutoProgramSelector.decide(
            listOf(decoded("CQ F4FSY JN25", snr = 0)),
            program,
            myCall = "F4FSY",
        )
        assertTrue(d is AutoDecision.None)
    }

    @Test
    fun dedupesSameCallKeepingFirst() {
        val d = AutoProgramSelector.decide(
            listOf(
                decoded("CQ JA1ABC PM95", snr = -9),
                decoded("CQ JA1ABC PM95", snr = -3),
            ),
            program.copy(farthestOverSnr = false),
        )
        val t = (d as AutoDecision.AnswerCq).target
        assertEquals("JA1ABC", t.call)
        assertEquals(-9, t.snr)
    }

    @Test
    fun carriesGridAndDf() {
        val d = AutoProgramSelector.decide(
            listOf(decoded("CQ JA1ABC PM95", snr = -4, df = 1521)),
            AutoProgramSettings(level = AutoLevel.FIRST_DECODE),
        )
        val t = (d as AutoDecision.AnswerCq).target
        assertEquals("PM95", t.grid)
        assertEquals(1521, t.df)
    }

    @Test
    fun allowsWorkedIsOrOfBothFlags() {
        assertTrue(AutoProgramSettings(answerWorked = true).allowsWorked)
        assertTrue(AutoProgramSettings(callWorked = true).allowsWorked)
        assertTrue(!AutoProgramSettings().allowsWorked)
    }
}
