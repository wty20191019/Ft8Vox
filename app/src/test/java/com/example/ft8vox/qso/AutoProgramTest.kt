package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「自动程序」第 2 层调度与选台的 JVM 单测（照 FT8CN 模型，见 FT8CN_QSO_PLAN.md §3）。 */
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

    private val program = AutoProgramSettings()

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
        assertEquals("FN42", list[0].grid)
        assertEquals(AutoTargetKind.CALL, list[1].kind)
        assertEquals(AutoTargetKind.REPORT, list[2].kind)
        assertEquals(-12, list[2].report)
        assertEquals(AutoTargetKind.ROGER, list[3].kind)
        assertEquals(-5, list[3].report)
    }

    @Test
    fun collectSkipsRanOutPlus73AndSelfAndIgnored() {
        val list = AutoProgramSelector.collect(
            listOf(
                decoded("CQ F4FSY JN25"), // 自己发的 CQ
                decoded("F4FSY W1AW RR73"), // 73 类不开启新 QSO
                decoded("F4FSY K1XYZ 73"),
                decoded("CQ JA1ABC PM95"), // 被忽略
            ),
            program,
            filter = DecodeFilterState(ignoredCalls = setOf("JA1ABC")),
            myCall = "F4FSY",
        )
        assertTrue(list.isEmpty())
    }

    @Test
    fun collectCqGovernedByFollowSwitch() {
        val msgs = listOf(decoded("CQ W1AW FN42"))
        // 关闭「自动关注 CQ」→ 不收集 CQ
        assertEquals(
            0,
            AutoProgramSelector.collect(
                msgs,
                program.copy(autoFollowCq = false),
                myCall = "F4FSY",
            ).size,
        )
        // 打开 → 收集
        assertEquals(
            1,
            AutoProgramSelector.collect(msgs, program, myCall = "F4FSY").size,
        )
    }

    @Test
    fun collectSkipsWorkedCqButAlwaysHandlesDirected() {
        val worked = WorkedIndex(calls = listOf("W1AW", "DL1ABC"))
        val list = AutoProgramSelector.collect(
            listOf(
                decoded("CQ W1AW FN42"), // 已通联 CQ：跳过
                decoded("F4FSY DL1ABC -12"), // 已通联，但定向 → 一定应答
            ),
            program,
            // 显示筛选为「什么都不选」（会隐藏一切），定向仍不受影响
            filter = DecodeFilterState(tags = emptySet()),
            worked = worked,
            myCall = "F4FSY",
        )
        assertEquals(1, list.size)
        assertEquals(AutoTargetKind.REPORT, list[0].kind)
        assertEquals("DL1ABC", list[0].call)
    }

    @Test
    fun collectKeepsDecodeOrderAndDedupes() {
        val list = AutoProgramSelector.collect(
            listOf(
                decoded("CQ W1AW FN42", snr = -1),
                decoded("CQ JA1ABC PM95", snr = -5),
                decoded("CQ W1AW FN42", snr = -2), // 同呼号重复：去重
            ),
            program,
            myCall = "F4FSY",
        )
        assertEquals(2, list.size)
        assertEquals("W1AW", list[0].call)
        assertEquals("JA1ABC", list[1].call)
    }

    // ---- 单条目标解析（解码菜单「呼叫 / 回复」） ----

    @Test
    fun toTargetClassifiesSingleMessage() {
        assertEquals(AutoTargetKind.CQ, AutoProgramSelector.toTarget(decoded("CQ W1AW FN42"), "F4FSY")?.kind)
        assertEquals("FN42", AutoProgramSelector.toTarget(decoded("CQ W1AW FN42"), "F4FSY")?.grid)
        assertEquals(AutoTargetKind.CALL, AutoProgramSelector.toTarget(decoded("F4FSY W1AW FN42"), "F4FSY")?.kind)
        val report = AutoProgramSelector.toTarget(decoded("F4FSY W1AW -12"), "F4FSY")
        assertEquals(AutoTargetKind.REPORT, report?.kind)
        assertEquals(-12, report?.report)
        assertEquals(AutoTargetKind.ROGER, AutoProgramSelector.toTarget(decoded("F4FSY W1AW R-12"), "F4FSY")?.kind)
    }

    @Test
    fun toTargetRejectsUnusableMessages() {
        assertNull(AutoProgramSelector.toTarget(decoded("CQ F4FSY JN25"), "F4FSY")) // 自听
        assertNull(AutoProgramSelector.toTarget(decoded("F4FSY W1AW RR73"), "F4FSY")) // 收尾
        assertNull(AutoProgramSelector.toTarget(decoded("F4FSY W1AW 73"), "F4FSY"))
        assertNull(AutoProgramSelector.toTarget(decoded("K1ABC W9XYZ IO90"), "F4FSY")) // 别人的 QSO
    }

    // ---- CQ 修饰符优先级（方案 §3.3） ----

    @Test
    fun modifierPriorityDxMyAreaThenOther() {
        assertEquals(3, AutoProgramSelector.modifierPriority("DX", "EU"))
        assertEquals(3, AutoProgramSelector.modifierPriority("DX NA", "EU"))
        assertEquals(2, AutoProgramSelector.modifierPriority("EU", "EU"))
        assertEquals(1, AutoProgramSelector.modifierPriority("TEST", "EU"))
        assertEquals(0, AutoProgramSelector.modifierPriority(null, "EU"))
        assertEquals(0, AutoProgramSelector.modifierPriority("  ", "EU"))
    }

    @Test
    fun areaOfGridRoughContinents() {
        assertEquals("EU", AutoProgramSelector.areaOfGrid("JN25"))
        assertEquals("NA", AutoProgramSelector.areaOfGrid("FN42"))
        assertEquals("AS", AutoProgramSelector.areaOfGrid("PM95"))
        assertNull(AutoProgramSelector.areaOfGrid("n/a"))
    }

    @Test
    fun rankPrefersDxThenMyAreaStableOtherwise() {
        val cqs = AutoProgramSelector.collect(
            listOf(
                decoded("CQ W1AW FN42"),        // 无修饰符
                decoded("CQ DX JA1ABC PM95"),   // DX
                decoded("CQ NA K1XYZ FN31"),    // 与我同区（EU？否，我的网格 JN25 → EU）
                decoded("CQ VK2ABC QF56"),      // 无修饰符
            ),
            program,
            myCall = "F4FSY",
        )
        val ranked = AutoProgramSelector.rank(cqs, myGrid = "JN25")
        assertEquals("JA1ABC", ranked[0].call) // DX 最高
        // 其余按解码顺序稳定：无修饰符的 W1AW、VK2ABC（NA 修饰符不是我所在区，优先级 1）
        assertEquals("K1XYZ", ranked[1].call) // 其它修饰符 > 无修饰符
        assertEquals("W1AW", ranked[2].call)
        assertEquals("VK2ABC", ranked[3].call)
    }

    // ---- 调度：onDecoded ----

    @Test
    fun noCandidatesFallsBackToSendCq() {
        val s = scheduler()
        assertEquals(AutoAction.SendCq, s.onDecoded(emptyList(), utcNowMs = 1_000L))
    }

    @Test
    fun answersBestCqWhenBothSwitchesOn() {
        val s = scheduler()
        val action = s.onDecoded(
            listOf(decoded("CQ W1AW FN42")),
            utcNowMs = 1_000L,
        )
        assertTrue(action is AutoAction.AnswerCq)
        assertEquals("W1AW", (action as AutoAction.AnswerCq).target.call)
    }

    @Test
    fun autoCallFollowOffMeansSendCq() {
        val s = scheduler(program.copy(autoCallFollow = false))
        assertEquals(
            AutoAction.SendCq,
            s.onDecoded(listOf(decoded("CQ W1AW FN42")), utcNowMs = 1_000L),
        )
    }

    @Test
    fun autoFollowCqOffMeansSendCq() {
        // 「自动关注 CQ」关 ⇒ CQ 台不进候选 ⇒ 不自动呼叫 CQ
        //（本机没有 FT8CN 的「关注呼号名单」这条可绕开开关的例外）
        val s = scheduler(program.copy(autoFollowCq = false))
        assertEquals(
            AutoAction.SendCq,
            s.onDecoded(listOf(decoded("CQ W1AW FN42")), utcNowMs = 1_000L),
        )
    }

    @Test
    fun directedMessageWinsAndIsQueued() {
        val s = scheduler()
        val action = s.onDecoded(
            listOf(
                decoded("CQ W1AW FN42"),
                decoded("F4FSY DL1ABC -12"),
            ),
            utcNowMs = 1_000L,
        )
        assertTrue(action is AutoAction.HandleDirected)
        assertEquals("DL1ABC", (action as AutoAction.HandleDirected).target.call)
        assertEquals(AutoTargetKind.REPORT, action.target.kind)
        // 队列为空（只入了一个且已弹出）：进一步解码转到下一动作
        assertEquals(0, s.queuedCount())
    }

    @Test
    fun queuedDirectedTargetsDrainAfterQso() {
        val s = scheduler()
        // 同一批两个定向：第一个立即处理，第二个进队列
        s.onDecoded(
            listOf(
                decoded("F4FSY DL1ABC -12"),
                decoded("F4FSY OK1ABC R-05"),
            ),
            utcNowMs = 1_000L,
        )
        assertEquals(1, s.queuedCount())
        val next = s.onQsoFinished(utcNowMs = 2_000L)
        assertTrue(next is AutoAction.HandleDirected)
        assertEquals("OK1ABC", (next as AutoAction.HandleDirected).target.call)
        // 队列清空后回 CQ
        assertEquals(AutoAction.SendCq, s.onQsoFinished(utcNowMs = 2_000L))
    }

    // ---- 换台（无回应超限后） ----

    @Test
    fun targetGaveUpPrefersAnotherCq() {
        val s = scheduler()
        val action = s.onTargetGaveUp(
            listOf(decoded("CQ W1AW FN42"), decoded("CQ DX JA1ABC PM95")),
            utcNowMs = 1_000L,
        )
        assertTrue(action is AutoAction.AnswerCq)
        // DX 优先
        assertEquals("JA1ABC", (action as AutoAction.AnswerCq).target.call)
    }

    @Test
    fun targetGaveUpWithoutCqReturnsToCq() {
        val s = scheduler()
        assertEquals(
            AutoAction.SendCq,
            s.onTargetGaveUp(emptyList(), utcNowMs = 1_000L),
        )
    }

    // ---- 忙时：目标本批沉默 → 应答其他定向台（FT8CN `checkCQMeOrFollowCQMessage` 循环 2） ----

    @Test
    fun directedTakeoverPicksOtherCallerWhenTargetSilent() {
        val s = scheduler()
        val t = s.directedTakeover(
            listOf(
                decoded("F4FSY JA1ABC PM95"), // 当前目标 → 排除
                decoded("CQ W1AW FN42"),      // CQ 不参与换台
                decoded("F4FSY DL1ABC PM95"), // 其他定向呼叫 → 命中
            ),
            currentTarget = "JA1ABC",
        )
        assertEquals("DL1ABC", t?.call)
        assertEquals(AutoTargetKind.CALL, t?.kind)
    }

    @Test
    fun directedTakeoverNullWhenOnlyCurrentTargetOrCq() {
        val s = scheduler()
        assertNull(s.directedTakeover(listOf(decoded("F4FSY JA1ABC PM95")), currentTarget = "JA1ABC"))
        assertNull(s.directedTakeover(listOf(decoded("CQ W1AW FN42")), currentTarget = "JA1ABC"))
    }

    @Test
    fun directedTakeoverIgnoresEndOfQsoReports() {
        val s = scheduler()
        // RR73 / 73 表示通联已结束，不作为新 QSO 起点
        assertNull(s.directedTakeover(listOf(decoded("F4FSY DL1ABC RR73")), currentTarget = "JA1ABC"))
        assertNull(s.directedTakeover(listOf(decoded("F4FSY DL1ABC 73")), currentTarget = "JA1ABC"))
    }

    @Test
    fun directedTakeoverTreatsCompoundedCallAsSameTarget() {
        val s = scheduler()
        // 宽松匹配：JA1ABC/P 与当前目标 JA1ABC 是同一台，不算「其他人」
        assertNull(s.directedTakeover(listOf(decoded("F4FSY JA1ABC/P PM95")), currentTarget = "JA1ABC"))
    }

    // ---- 发射监管 ----

    @Test
    fun supervisionTriggersStopAndMarksProtected() {
        val s = scheduler(program.copy(supervisionMinutes = 5), nowMs = 1_000L)
        val limitMs = 1_000L + 5 * 60_000L
        // 未到点：无动作
        assertNull(s.checkSupervision(limitMs - 1))
        val action = s.onDecoded(emptyList(), utcNowMs = limitMs)
        assertTrue(action is AutoAction.Stop)
        // 触发后进入保护停止：不再产出动作
        s.markProtectedStop()
        assertEquals(AutoAction.None, s.onDecoded(listOf(decoded("CQ W1AW FN42")), utcNowMs = limitMs))
    }

    @Test
    fun supervisionZeroDisables() {
        val s = scheduler(program.copy(supervisionMinutes = 0), nowMs = 1_000L)
        assertNull(s.checkSupervision(1_000L + 24 * 60 * 60_000L))
    }

    @Test
    fun resetSupervisionClearsProtectedStop() {
        val s = scheduler(program.copy(supervisionMinutes = 5), nowMs = 1_000L)
        val limitMs = 1_000L + 5 * 60_000L
        assertTrue(s.onDecoded(emptyList(), utcNowMs = limitMs) is AutoAction.Stop)
        s.markProtectedStop()
        assertTrue(s.isProtectedStop())
        s.resetSupervision(limitMs)
        assertFalse(s.isProtectedStop())
        // 复位后又有一整段监管时间
        assertNull(s.checkSupervision(limitMs + 1_000L))
    }

    // ---- 默认值（照 FT8CN） ----

    @Test
    fun defaultsMatchFt8cn() {
        val d = AutoProgramSettings()
        assertEquals(10, d.supervisionMinutes)
        assertEquals(0, d.noReplyLimit)
        assertTrue(d.autoFollowCq)
        assertTrue(d.autoCallFollow)
    }
}
