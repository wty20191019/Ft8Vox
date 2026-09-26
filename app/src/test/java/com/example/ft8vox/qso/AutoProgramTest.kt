package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「自动程序」第 2 层调度与选台的 JVM 单测。 */
class AutoProgramTest {

    private fun decoded(text: String, snr: Int = -10, df: Int = 1000, slotUtcMs: Long = 0L) =
        DecodeResult(
            text = text,
            snr = snr,
            dt = 0f,
            df = df,
            score = 20,
            slotUtcMs = slotUtcMs,
        )

    private val program = AutoProgramSettings(mode = AutoMode.CALLER)

    private fun scheduler(
        settings: AutoProgramSettings = program,
        myCall: String = "F4FSY",
        myGrid: String = "JN25",
        nowMs: Long = 1_000L,
    ): AutoScheduler = AutoScheduler().apply {
        configure(settings, myCall, myGrid)
        enable(nowMs)
    }

    // ---- 选台：collect ----

    @Test
    fun collectFindsCqAndDirectedKinds() {
        val list = AutoProgramSelector.collect(
            listOf(
                decoded("CQ W1AW FN42", snr = -2),
                decoded("F4FSY JA1ABC PM95", snr = -7),
                decoded("F4FSY DL1ABC -12", snr = -9),
                decoded("F4FSY OK1ABC R-05", snr = -11),
            ),
            program,
            myCall = "F4FSY",
        )
        assertEquals(4, list.size)
        assertEquals(AutoTargetKind.CQ, list[0].kind)
        assertEquals(AutoTargetKind.CALL, list[1].kind)
        assertEquals(AutoTargetKind.REPORT, list[2].kind)
        assertEquals(-12, list[2].report)
        assertEquals(AutoTargetKind.ROGER, list[3].kind)
    }

    @Test
    fun collectIgnores73AndNonAddressed() {
        val list = AutoProgramSelector.collect(
            listOf(
                decoded("F4FSY JA1ABC 73"),
                decoded("F4FSY JA1ABC RR73"),
                decoded("JA1ABC W1AW -12"),
                decoded("CQ F4FSY JN25"),
            ),
            program,
            myCall = "F4FSY",
        )
        assertTrue("73/RR73、非定向、自己发的都不算候选", list.isEmpty())
    }

    @Test
    fun collectRespectsFiltersAndDedupes() {
        val replyOnly = AutoProgramSelector.collect(
            listOf(decoded("CQ JA2XYZ PM96", snr = -2)),
            program,
            filter = DecodeFilterState(tags = setOf(DecodeFilterTag.REPLY)),
            myCall = "F4FSY",
        )
        assertTrue(replyOnly.isEmpty())

        val ignored = AutoProgramSelector.collect(
            listOf(
                decoded("CQ JA1ABC PM95", snr = -2),
                decoded("CQ JA2XYZ PM96", snr = -9),
                decoded("CQ JA2XYZ PM96", snr = -3),
            ),
            program,
            filter = DecodeFilterState(tags = setOf(DecodeFilterTag.ALL), ignoredCalls = setOf("JA1ABC")),
            myCall = "F4FSY",
        )
        assertEquals(1, ignored.size)
        assertEquals("JA2XYZ", ignored[0].call)
        assertEquals("同呼号去重保留第一条", -9, ignored[0].snr)
    }

    @Test
    fun collectExcludesWorkedUnlessAllowed() {
        val worked = WorkedIndex(calls = listOf("JA1ABC"))
        val excluded = AutoProgramSelector.collect(
            listOf(decoded("CQ JA1ABC PM95", snr = -2)),
            program,
            worked = worked,
            myCall = "F4FSY",
        )
        assertTrue(excluded.isEmpty())

        val allowed = AutoProgramSelector.collect(
            listOf(decoded("CQ JA1ABC PM95", snr = -2)),
            program.copy(allowRepeat = true),
            worked = worked,
            myCall = "F4FSY",
        )
        assertEquals(1, allowed.size)
        assertTrue(allowed[0].worked)
    }

    // ---- 选台：排序 ----

    @Test
    fun rankBySnrIsDefault() {
        val list = AutoProgramSelector.collect(
            listOf(
                decoded("CQ JA1ABC PM96", snr = -2, df = 1),
                decoded("CQ DL1ABC JN48", snr = -15, df = 2),
            ),
            program,
        )
        val ranked = AutoProgramSelector.rank(list, program, "PM95")
        assertEquals("JA1ABC", ranked[0].call)
    }

    @Test
    fun rankByDecodeOrderKeepsArrivalOrder() {
        val settings = program.copy(sortBy = AutoSort.DECODE_ORDER)
        val list = AutoProgramSelector.collect(
            listOf(
                decoded("CQ JA1ABC PM96", snr = -2, df = 1),
                decoded("CQ DL1ABC JN48", snr = -15, df = 2),
            ),
            settings,
        )
        val ranked = AutoProgramSelector.rank(list, settings, "PM95")
        assertEquals("JA1ABC", ranked[0].call)
    }

    @Test
    fun rankByDistancePicksFartherStation() {
        val settings = program.copy(sortBy = AutoSort.DISTANCE)
        val list = AutoProgramSelector.collect(
            listOf(
                decoded("CQ JA1ABC PM96", snr = -2, df = 1), // 近
                decoded("CQ DL1ABC JN48", snr = -15, df = 2), // 远
            ),
            settings,
        )
        val ranked = AutoProgramSelector.rank(list, settings, "PM95")
        assertEquals("DL1ABC", ranked[0].call)
    }

    // ---- 第 2 层：主叫模式 ----

    @Test
    fun manualModeNeverActs() {
        val s = scheduler(AutoProgramSettings(mode = AutoMode.MANUAL))
        assertEquals(AutoAction.None, s.onDecoded(emptyList(), utcNowMs = 2_000L))
        assertEquals(AutoAction.None, s.onQsoFinished(success = true, utcNowMs = 2_000L))
    }

    @Test
    fun callerModeSendsCqWhenNobodyReplies() {
        val s = scheduler()
        assertEquals(AutoAction.SendCq, s.onDecoded(emptyList(), utcNowMs = 2_000L))
        assertEquals(AutoAction.SendCq, s.onDecoded(emptyList(), utcNowMs = 3_000L))
    }

    @Test
    fun callerModeHandlesRespondersInOrder() {
        val settings = program.copy(reportPriority = false, sortBy = AutoSort.SNR)
        val s = scheduler(settings)
        // 本批收到两个回应者（强台优先）→ 先处理强台，另一个排队
        val a = s.onDecoded(
            listOf(
                decoded("F4FSY JA1ABC PM95", snr = -7),
                decoded("F4FSY W1AW FN42", snr = -2),
            ),
            utcNowMs = 2_000L,
        ) as AutoAction.HandleDirected
        assertEquals("W1AW", a.target.call)
        assertEquals(1, s.queuedCount())

        // 第一段结束（失败）→ 取队列里的下一位
        val b = s.onQsoFinished(success = false, utcNowMs = 3_000L) as AutoAction.HandleDirected
        assertEquals("JA1ABC", b.target.call)
        assertEquals(0, s.queuedCount())

        // 队列清空 → 回到发 CQ
        assertEquals(AutoAction.SendCq, s.onQsoFinished(success = false, utcNowMs = 4_000L))
    }

    @Test
    fun reportPriorityHandlesDirectedBeforeCq() {
        val settings = program.copy(reportPriority = true)
        val s = scheduler(settings)
        val a = s.onDecoded(
            listOf(
                decoded("CQ W1AW FN42", snr = -2),
                decoded("F4FSY JA1ABC -12", snr = -15),
            ),
            utcNowMs = 2_000L,
        ) as AutoAction.HandleDirected
        assertEquals("JA1ABC", a.target.call)
    }

    // ---- 第 2 层：混合模式 ----

    @Test
    fun mixedModeSwitchesToAnsweringAfterThreeSilentCqs() {
        val s = scheduler(program.copy(mode = AutoMode.MIXED))
        assertEquals(AutoAction.SendCq, s.onDecoded(emptyList(), utcNowMs = 2_000L)) // CQ 1
        assertEquals(AutoAction.SendCq, s.onDecoded(emptyList(), utcNowMs = 3_000L)) // CQ 2
        assertEquals(AutoAction.SendCq, s.onDecoded(emptyList(), utcNowMs = 4_000L)) // CQ 3
        // 第 4 次决策时确认第 3 次 CQ 也无人回应 → 转应答状态；
        // 本批没有 CQ 台 → 保持监听（不再发 CQ）
        assertEquals(AutoAction.Listen, s.onDecoded(emptyList(), utcNowMs = 5_000L))
        assertEquals(AutoScheduler.Phase.ANSWERING, s.currentPhase())
    }

    @Test
    fun mixedModeAnswersCqThenReturnsToCalling() {
        val s = scheduler(program.copy(mode = AutoMode.MIXED))
        repeat(4) { s.onDecoded(emptyList(), utcNowMs = 2_000L + it) }
        assertEquals(AutoScheduler.Phase.ANSWERING, s.currentPhase())

        // 应答状态：扫到 CQ 台就应答
        val a = s.onDecoded(
            listOf(decoded("CQ JA1ABC PM95", snr = -9, slotUtcMs = 6_000L)),
            utcNowMs = 6_000L,
        ) as AutoAction.AnswerCq
        assertEquals("JA1ABC", a.target.call)

        // 应答 QSO 完成 → 立即切回主叫并发 CQ
        assertEquals(AutoAction.SendCq, s.onQsoFinished(success = true, utcNowMs = 7_000L))
        assertEquals(AutoScheduler.Phase.CALLING, s.currentPhase())
    }

    // ---- 第 3 层：手动接管 ----

    @Test
    fun pauseKeepsQueueAndResumeRestores() {
        val settings = program.copy(reportPriority = false)
        val s = scheduler(settings)
        s.onDecoded(
            listOf(
                decoded("F4FSY JA1ABC PM95", snr = -7),
                decoded("F4FSY W1AW FN42", snr = -2),
            ),
            utcNowMs = 2_000L,
        )
        assertEquals(1, s.queuedCount())

        // 第 3 层接管：暂停期间不产出任何动作，也不改队列
        s.pause()
        assertTrue(s.paused)
        assertEquals(AutoAction.None, s.onDecoded(emptyList(), utcNowMs = 3_000L))
        assertEquals(AutoAction.None, s.onQsoFinished(success = true, utcNowMs = 3_000L))
        assertEquals("暂停期间保留队列", 1, s.queuedCount())

        // 交还控制权：队列里还有目标，继续处理
        s.resume()
        assertFalse(s.paused)
        val next = s.onDecoded(emptyList(), utcNowMs = 4_000L) as AutoAction.HandleDirected
        assertEquals("JA1ABC", next.target.call)
    }

    // ---- 保护限制 ----

    @Test
    fun protectionStopsAfterNoQsoTimeout() {
        val settings = program.copy(stopAfterNoQso = true, noQsoMinutes = 10)
        val s = scheduler(settings, nowMs = 1_000L)
        assertTrue(s.onDecoded(emptyList(), utcNowMs = 599_000L) is AutoAction.SendCq)
        val stop = s.onDecoded(emptyList(), utcNowMs = 601_000L) as AutoAction.Stop
        assertTrue(stop.reason.contains("无有效 QSO"))
    }

    @Test
    fun protectionStopsAfterTxTotalTimeout() {
        val settings = program.copy(stopAfterTxTotal = true, txTotalMinutes = 1)
        val s = scheduler(settings, nowMs = 1_000L)
        s.addTxMs(30_000)
        assertEquals(AutoAction.SendCq, s.onDecoded(emptyList(), utcNowMs = 2_000L))
        s.addTxMs(30_000)
        val stop = s.onDecoded(emptyList(), utcNowMs = 3_000L) as AutoAction.Stop
        assertTrue(stop.reason.contains("发射总时长"))
    }

    @Test
    fun successfulQsoResetsNoQsoTimer() {
        val settings = program.copy(stopAfterNoQso = true, noQsoMinutes = 10)
        val s = scheduler(settings, nowMs = 1_000L)
        // 9 分钟后完成一次有效 QSO → 计时重置
        s.onQsoFinished(success = true, utcNowMs = 541_000L)
        assertEquals(AutoAction.SendCq, s.onDecoded(emptyList(), utcNowMs = 601_000L))
    }

    // ---- 默认值 ----

    @Test
    fun defaultsMatchDocument() {
        val d = AutoProgramSettings()
        assertEquals(AutoMode.MANUAL, d.mode)
        assertFalse(d.allowRepeat)
        assertEquals(AutoSort.SNR, d.sortBy)
        assertTrue(d.giveUpAfterRetry)
        assertEquals(3, d.retryLimit)
        assertFalse("保护限制默认不勾选（文档 □）", d.stopAfterNoQso)
        assertEquals(10, d.noQsoMinutes)
        assertFalse(d.stopAfterTxTotal)
        assertEquals(30, d.txTotalMinutes)
    }
}
