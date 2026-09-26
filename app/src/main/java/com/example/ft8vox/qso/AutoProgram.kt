package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.grid.Geo

/** 主叫状态「连续发 CQ 无人回应」多少次后，混合模式转去应答别人的 CQ（文档 §2.3）。 */
const val MIXED_CQ_NO_REPLY_LIMIT = 3

/**
 * 自动程序工作模式（对应《QSO 自动系统设计文档》§五「工作模式」）。
 *
 * 取代旧的「等级 0/1/2/3/4+」：旧方案里等级 1/2/3（首先解码 / 解码窗口中 / 解码后）
 * 只在「解码时机」上有差别，而 Ft8Vox 的解码结果按接收时隙整批返回，三者行为一致；
 * 这里合并为三种**真正有区别**的模式。
 */
enum class AutoMode(val label: String, val shortLabel: String, val subtitle: String) {
    MANUAL("0 手动模式", "手动", "不自动发射，目标由你手动选择。"),
    CALLER(
        "1 主叫模式（有人值守）",
        "主叫",
        "持续发 CQ，收集回应者并排队，逐个完成整段 QSO。",
    ),
    MIXED(
        "2 混合模式（无人值守）",
        "混合",
        "优先主叫；连续 $MIXED_CQ_NO_REPLY_LIMIT 次无人回应后转为应答别人的 CQ，完成即切回主叫。",
    ),
    ;

    /** 是否开启自动发射。 */
    val enabled: Boolean get() = this != MANUAL
}

/** 应答选台规则的排序依据（文档 §2.4）。 */
enum class AutoSort(val label: String) {
    /** 按解码到的时间先后（保持本时隙解码顺序）。 */
    DECODE_ORDER("解码先后"),

    /** 按信号强度降序。 */
    SNR("SNR 优先"),

    /** 按距离降序（需填写我的网格）。 */
    DISTANCE("距离优先"),
}

/**
 * 解码时机（文档 §五「解码时机」）。
 *
 * **当前架构下两种时机等价**：native monitor 在一个接收时隙结束时整批给出解码结果，
 * 没有「时隙中途先给一部分」的通道，因此保留该项仅用于对齐菜单与记录用户选择。
 */
enum class DecodeTiming(val label: String, val subtitle: String) {
    IN_GAP("解码至发射间隔期间", "在接收→发射的间隔内用解码结果决策（当前架构即此行为）"),
    UNTIL_END("直到解码结束", "等完整解码结束再决策（当前架构与上一项等价）"),
}

/** 自动程序策略（持久化于设置；对应文档 §五菜单）。 */
data class AutoProgramSettings(
    /** 工作模式：0 手动 / 1 主叫 / 2 混合。「模式即开关」，0＝关闭。 */
    val mode: AutoMode = AutoMode.MANUAL,
    /** 解码时机（当前架构两种等价，见 [DecodeTiming]）。 */
    val decodeTiming: DecodeTiming = DecodeTiming.IN_GAP,
    /** 允许重复通联：不勾选时把已通呼号从候选里剔除。 */
    val allowRepeat: Boolean = false,
    /** 排序依据。 */
    val sortBy: AutoSort = AutoSort.SNR,
    /** 报告信息优先：发给我方的定向报文优先于 CQ / 排队。 */
    val reportPriority: Boolean = true,
    /** 已选台回应无反应 [retryLimit] 次后放弃（第 1 层重发机制）。 */
    val giveUpAfterRetry: Boolean = true,
    val retryLimit: Int = 3,
    /** 保护限制（第 2 层监控）：连续无有效 QSO [noQsoMinutes] 分钟后自动停止。 */
    val stopAfterNoQso: Boolean = false,
    val noQsoMinutes: Int = 10,
    /** 保护限制（第 2 层监控）：单次发射总时长超过 [txTotalMinutes] 分钟后自动停止。 */
    val stopAfterTxTotal: Boolean = false,
    val txTotalMinutes: Int = 30,
) {
    /** 已通联台是否可作为目标（不勾「允许重复通联」即过滤掉）。 */
    val allowsWorked: Boolean get() = allowRepeat
}

/** 自动程序目标的报文类型。 */
enum class AutoTargetKind {
    /** 对方的 CQ（第 2 层应答状态的目标）。 */
    CQ,

    /** 发给我、带网格的定向报文（对方在呼叫我 / 应答我刚才的 CQ）。 */
    CALL,

    /** 发给我且带信号报告的定向报文（对方直接给了我报告）。 */
    REPORT,

    /** 发给我且带 R 的定向报文（对方 Roger 了我的报告）。 */
    ROGER,
}

/** 自动程序挑出的目标台站。 */
data class AutoTarget(
    val call: String,
    val grid: String?,
    val snr: Int,
    val df: Int,
    val kind: AutoTargetKind,
    /** 仅 [AutoTargetKind.REPORT]/[AutoTargetKind.ROGER] 有意义：对方发给我的信号报告。 */
    val report: Int? = null,
    val worked: Boolean = false,
    /** 该报文所属时隙的 UTC 起点（ms）；用于把发射时隙对到对方的相反周期。 */
    val slotUtcMs: Long = 0L,
)

/** 第 2 层在「无进行中 QSO」时算出的下一步动作。 */
sealed interface AutoAction {
    /** 发 CQ（主叫状态）。 */
    data object SendCq : AutoAction

    /** 应答对方的 CQ（第 1 层应答 QSO）。 */
    data class AnswerCq(val target: AutoTarget) : AutoAction

    /**
     * 处理发给我方的定向报文（第 1 层）：
     * 呼叫我 → 直接发报告；给我报告 → 回 R 报告；Roger 我的报告 → 回 RR73 收尾。
     */
    data class HandleDirected(val target: AutoTarget) : AutoAction

    /** 保持接收、不发（混合模式应答状态且暂时没有 CQ 台）。 */
    data object Listen : AutoAction

    /** 保护限制触发：停止第 2 层调度并切回手动模式。 */
    data class Stop(val reason: String) : AutoAction

    /** 不动作。 */
    data object None : AutoAction
}

/**
 * 第 2 层选台：从本时隙解码里收集候选并按规则排序（纯 Kotlin，可 JVM 单测）。
 *
 * 候选覆盖两类「能推进到完成 QSO」的报文：
 * - 对方的 **CQ**（[AutoTargetKind.CQ]，我来应答）；
 * - **发给我** 的定向报文：呼叫我 / 给我报告 / Roger 我的报告。
 * `RR73`/`73` 表示通联已结束，不作为新 QSO 的起点。
 *
 * 与显示列表共用 [DecodeFilter]，避免「列表里看不到、自动却应答」的不一致。
 */
object AutoProgramSelector {

    /** 收集本时隙可用候选（保持解码顺序，同呼号去重）。 */
    fun collect(
        messages: List<DecodeResult>,
        program: AutoProgramSettings,
        filter: DecodeFilterState = DecodeFilterState(),
        worked: WorkedIndex = WorkedIndex.EMPTY,
        myCall: String = "",
    ): List<AutoTarget> {
        val out = ArrayList<AutoTarget>()
        val seen = HashSet<String>()
        for (m in messages) {
            val p = MessageParser.parse(m.text)
            val from = p.from?.trim()?.uppercase() ?: continue
            if (from.equals(myCall, ignoreCase = true)) continue
            if (!DecodeFilter.matches(p, filter, worked, myCall)) continue

            val workedCall = worked.hasWorkedCall(from)
            if (workedCall && !program.allowsWorked) continue

            val kind: AutoTargetKind
            val report: Int?
            if (p.isCq) {
                kind = AutoTargetKind.CQ
                report = null
            } else if (p.addressedTo(myCall)) {
                // 定向报文；RR73/73/RRR 表示通联已结束，不据此开启新 QSO
                if (DecodeFilter.is73(p)) continue
                when {
                    p.isRoger && p.report != null -> {
                        kind = AutoTargetKind.ROGER
                        report = p.report
                    }
                    p.report != null -> {
                        kind = AutoTargetKind.REPORT
                        report = p.report
                    }
                    p.grid != null -> {
                        kind = AutoTargetKind.CALL
                        report = null
                    }
                    else -> continue
                }
            } else {
                continue
            }
            if (!seen.add(from)) continue
            out.add(
                AutoTarget(
                    call = from,
                    grid = p.grid,
                    snr = m.snr,
                    df = m.df,
                    kind = kind,
                    report = report,
                    worked = workedCall,
                    slotUtcMs = m.slotUtcMs,
                ),
            )
        }
        return out
    }

    /** 按 [AutoProgramSettings.sortBy] 排序（Kotlin sort 稳定，同值保持解码顺序）。 */
    fun rank(
        candidates: List<AutoTarget>,
        program: AutoProgramSettings,
        myGrid: String = "",
    ): List<AutoTarget> = when (program.sortBy) {
        AutoSort.DECODE_ORDER -> candidates
        AutoSort.SNR -> candidates.sortedWith(compareByDescending { it.snr })
        AutoSort.DISTANCE -> {
            val distance: (AutoTarget) -> Double = { t ->
                Geo.betweenGrids(myGrid, t.grid)?.first ?: 0.0
            }
            candidates.sortedWith(compareByDescending { distance(it) })
        }
    }
}

/**
 * 第 2 层：自动程序调度（纯 Kotlin，可 JVM 单测）。
 *
 * 职责（文档 §2）：
 * - 维护目标队列（回应者 / CQ 台），选台排序、过滤重复通联；
 * - 主叫模式与混合模式的调度与切换（主叫 ⇄ 应答）；
 * - 保护限制监控（连续无有效 QSO / 发射总时长）。
 *
 * 它**不**直接发报文：每个决策点返回一个 [AutoAction]，由 `SessionViewModel` 转成
 * 第 1 层 `QsoEngine` 的调用与实际发射。第 3 层（用户手动设目标）用 [pause]/[resume]
 * 临时接管，且**不改动**本类的队列与阶段状态。
 *
 * 调用时序：每个接收时隙结束时（且当前无进行中 QSO）调 [onDecoded]；一段 QSO 结束
 * （成功/失败）时调 [onQsoFinished]；每完成一次发射调 [addTxMs]。
 */
class AutoScheduler {

    /** 混合模式的两态（文档 §2.3）。 */
    enum class Phase { CALLING, ANSWERING }

    private var settings = AutoProgramSettings()
    private var myCall = ""
    private var myGrid = ""

    private var phase = Phase.CALLING
    private val queue = ArrayDeque<AutoTarget>()

    /** 主叫状态连续「发了 CQ 但无人回应」的次数。 */
    private var noReplyStreak = 0

    /** 上一次已发出的 CQ 还在等回应（决定下一次决策是否计「无人回应」）。 */
    private var cqPending = false

    /** 第 3 层是否临时接管。 */
    var paused = false
        private set

    /** 计时基准：自动程序启用时刻与最近一次有效 QSO 时刻。 */
    private var enabledSinceMs = 0L
    private var lastValidQsoMs = 0L

    /** 本段自动程序累计的发射时长（ms）。 */
    private var txAccumMs = 0L

    /** 保护限制已触发（停止后再不产出动作，直到 [enable] / [disable]）。 */
    private var protectedStop = false

    fun configure(s: AutoProgramSettings, myCall: String, myGrid: String) {
        settings = s
        this.myCall = myCall.trim().uppercase()
        this.myGrid = myGrid.trim().uppercase()
        // 关掉自动程序时同步清空调度状态（「模式即开关」）
        if (!s.mode.enabled) clear()
    }

    /** 自动程序启用（模式从 0 切到 1/2）时调用：清空队列并重新计时。 */
    fun enable(utcNowMs: Long) {
        clear()
        enabledSinceMs = utcNowMs
        lastValidQsoMs = utcNowMs
    }

    /** 关闭自动程序。 */
    fun disable() = clear()

    private fun clear() {
        phase = Phase.CALLING
        queue.clear()
        noReplyStreak = 0
        cqPending = false
        paused = false
        enabledSinceMs = 0L
        lastValidQsoMs = 0L
        txAccumMs = 0L
        protectedStop = false
    }

    /** 第 3 层接管（用户手动设目标）。 */
    fun pause() {
        paused = true
    }

    /** 第 3 层交还控制权（手动 QSO 结束或被取消）；队列与阶段保持不变。 */
    fun resume() {
        paused = false
    }

    fun currentPhase(): Phase = phase

    fun queuedCount(): Int = queue.size

    /** 计时基准（供 UI 显示）：自动程序已启用 + 累计发射时长。 */
    fun txAccumMs(): Long = txAccumMs

    /** 记录一次发射的实际时长（保护限制「发射总时长」用）。 */
    fun addTxMs(ms: Long) {
        if (ms > 0) txAccumMs += ms
    }

    /**
     * 保护限制检查：触发返回可读原因，否则 null（文档 §2.5）。
     */
    fun checkProtection(utcNowMs: Long): String? {
        if (!settings.mode.enabled || protectedStop) return null
        ensureArmed(utcNowMs)
        if (settings.stopAfterNoQso) {
            val base = if (lastValidQsoMs > 0) lastValidQsoMs else enabledSinceMs
            if (base > 0 && utcNowMs - base >= settings.noQsoMinutes * 60_000L) {
                return "连续 ${settings.noQsoMinutes} 分钟无有效 QSO"
            }
        }
        if (settings.stopAfterTxTotal &&
            txAccumMs >= settings.txTotalMinutes * 60_000L
        ) {
            return "发射总时长超过 ${settings.txTotalMinutes} 分钟"
        }
        return null
    }

    /** 标记保护限制已触发（避免每时隙重复上报）。 */
    fun markProtectedStop() {
        protectedStop = true
    }

    private fun ensureArmed(utcNowMs: Long) {
        if (enabledSinceMs == 0L) enabledSinceMs = utcNowMs
        if (lastValidQsoMs == 0L) lastValidQsoMs = utcNowMs
    }

    /**
     * 接收时隙结束、且当前无进行中 QSO 时的决策。
     */
    fun onDecoded(
        messages: List<DecodeResult>,
        filter: DecodeFilterState = DecodeFilterState(),
        worked: WorkedIndex = WorkedIndex.EMPTY,
        utcNowMs: Long = 0L,
    ): AutoAction {
        if (!settings.mode.enabled) return AutoAction.None
        checkProtection(utcNowMs)?.let { return AutoAction.Stop(it) }
        if (paused || protectedStop) return AutoAction.None

        val candidates = AutoProgramSelector.collect(messages, settings, filter, worked, myCall)
        val directed = candidates.filter { it.kind != AutoTargetKind.CQ }
        val cqs = candidates.filter { it.kind == AutoTargetKind.CQ }

        // 报告信息优先：发给我方的定向报文（多半是进行中 QSO 的续报）先处理
        if (settings.reportPriority && directed.isNotEmpty()) {
            noReplyStreak = 0
            cqPending = false
            return AutoAction.HandleDirected(best(directed))
        }
        return when (settings.mode) {
            AutoMode.MANUAL -> AutoAction.None
            AutoMode.CALLER -> callingStep(directed, cqs)
            AutoMode.MIXED ->
                if (phase == Phase.CALLING) callingStep(directed, cqs)
                else answeringStep(cqs)
        }
    }

    /**
     * 一段 QSO 结束后的决策（成功或失败）。
     *
     * 失败目标已从队列移除（开始时就出队），这里只决定「继续队列 / 回到 CQ / 切回主叫」。
     */
    fun onQsoFinished(success: Boolean, utcNowMs: Long): AutoAction {
        if (!settings.mode.enabled || protectedStop) return AutoAction.None
        if (success) {
            lastValidQsoMs = utcNowMs
            noReplyStreak = 0
        }
        checkProtection(utcNowMs)?.let { return AutoAction.Stop(it) }
        if (paused) return AutoAction.None
        // 应答状态完成一次即切回主叫（文档 §2.3）
        if (settings.mode == AutoMode.MIXED && phase == Phase.ANSWERING) {
            phase = Phase.CALLING
            noReplyStreak = 0
            queue.clear()
            cqPending = true
            return AutoAction.SendCq
        }
        if (queue.isNotEmpty()) return AutoAction.HandleDirected(queue.removeFirst())
        cqPending = true
        return AutoAction.SendCq
    }

    // ---- 主叫 / 应答两个状态 ----

    /** 主叫状态：优先处理回应者队列，无人回应则发 CQ；混合模式连续无回应则转应答。 */
    private fun callingStep(directed: List<AutoTarget>, cqs: List<AutoTarget>): AutoAction {
        if (directed.isNotEmpty()) {
            // 收集本批回应者（先按选台规则排序，再去重）后取队首，其余排队
            enqueue(AutoProgramSelector.rank(directed, settings, myGrid))
            noReplyStreak = 0
            cqPending = false
            return AutoAction.HandleDirected(queue.removeFirst())
        }
        if (queue.isNotEmpty()) {
            cqPending = false
            return AutoAction.HandleDirected(queue.removeFirst())
        }
        // 无人回应
        if (cqPending) {
            cqPending = false
            noReplyStreak++
            if (settings.mode == AutoMode.MIXED && noReplyStreak >= MIXED_CQ_NO_REPLY_LIMIT) {
                phase = Phase.ANSWERING
                return answeringStep(cqs)
            }
        }
        cqPending = true
        return AutoAction.SendCq
    }

    /** 应答状态：扫描 CQ 台并逐个应答；没有就保持监听（不切回主叫）。 */
    private fun answeringStep(cqs: List<AutoTarget>): AutoAction {
        if (cqs.isEmpty()) return AutoAction.Listen
        return AutoAction.AnswerCq(best(cqs))
    }

    private fun enqueue(list: List<AutoTarget>) {
        list.forEach { t ->
            if (queue.none { it.call.equals(t.call, ignoreCase = true) }) queue.addLast(t)
        }
    }

    private fun best(list: List<AutoTarget>): AutoTarget =
        AutoProgramSelector.rank(list, settings, myGrid).first()
}
