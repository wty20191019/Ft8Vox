package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.grid.Geo

/**
 * 自动程序等级（对应 FT8CN「自动程序」菜单的 0/1/2/3/4+）。
 *
 * Ft8Vox 的解码结果按**接收时隙整批**返回，因此 [FIRST_DECODE]/[DECODE_WINDOW]/[UNTIL_END]
 * 的差异主要体现为「选台准则」：
 * - [FIRST_DECODE] 取本时隙最先解码到的台（不做排序）；
 * - [DECODE_WINDOW]/[UNTIL_END] 在整批解码中择优（最强信噪比 / 最远距离）；
 * - [AUTO_CQ] 在择优基础上，无可答目标时自动发 CQ（自动搜索）。
 */
enum class AutoLevel(val label: String, val shortLabel: String) {
    MANUAL("0 手动选择", "手动"),
    FIRST_DECODE("1 呼叫首先解码", "首先解码"),
    DECODE_WINDOW("2 解码至发射间隔期间", "窗口中"),
    UNTIL_END("3 直到解码结束", "解码后"),
    AUTO_CQ("4+ 自动搜索及自动回应别人的CQ信息", "自动搜索"),
    ;

    /** 是否开启自动应答。 */
    val enabled: Boolean get() = this != MANUAL

    /** 是否在无可答目标时自动发 CQ（自动搜索）。 */
    val autoSearch: Boolean get() = this == AUTO_CQ
}

/** 自动程序策略（持久化于设置）。 */
data class AutoProgramSettings(
    val level: AutoLevel = AutoLevel.MANUAL,
    /** 回答曾经通联的电台：允许自动应答已通联台发来的 CQ。 */
    val answerWorked: Boolean = false,
    /** 呼叫曾经通联过的电台：允许自动程序把已通联台纳入目标。 */
    val callWorked: Boolean = false,
    /** 优先选择新呼号发来的呼叫。 */
    val preferNewCall: Boolean = true,
    /** 报告信息优先：发给我方的定向报文优先于 CQ 排队。 */
    val reportPriority: Boolean = false,
    /** 最远距离取代最佳信噪比：选台准则由 SNR 改为距离。 */
    val farthestOverSnr: Boolean = false,
    /** 单次通联：完成一次 QSO 后自动停止自动程序（默认关闭＝连续通联）。 */
    val singleQso: Boolean = false,
) {
    /**
     * 已通联台是否可作为目标。
     *
     * Ft8Vox 没有 FT8CN 的「关注呼号」跟踪列表，因此「回答」与「呼叫」两项合并为
     * 「允许自动程序与已通联台建立联系」；任一开启即允许。
     */
    val allowsWorked: Boolean get() = answerWorked || callWorked
}

/** 自动程序目标的报文类型。 */
enum class AutoTargetKind {
    /** 对方的 CQ。 */
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

/** 自动程序在「无进行中 QSO」时算出的下一步动作。 */
sealed interface AutoDecision {
    /** 应答对方的 CQ。 */
    data class AnswerCq(val target: AutoTarget) : AutoDecision

    /** 回应对我方的定向报文（呼叫我 / 给我报告 / Roger 我的报告），直接继续完成该 QSO。 */
    data class AnswerDirected(val target: AutoTarget) : AutoDecision

    /** 无可答目标：自动发 CQ 搜索（等级 4+）。 */
    data object CallCq : AutoDecision

    /** 不动作。 */
    data object None : AutoDecision
}

/**
 * 自动程序的目标挑选与决策（纯 Kotlin，可 JVM 单测）。
 *
 * 候选来自本时隙解码，覆盖两类「能推进到完成 QSO」的报文：
 * - 对方的 **CQ**（我来应答）；
 * - **发给我** 的定向报文：对方呼叫我 / 给我报告 / Roger 我的报告。
 * `RR73`/`73` 表示通联已结束，不作为新 QSO 的起点。
 *
 * 已通联台默认排除，需开启「回答 / 呼叫曾经通联的电台」。
 * 与显示列表共用 [DecodeFilter]，避免「列表里看不到、自动却应答」的不一致。
 */
object AutoProgramSelector {

    /**
     * 由本时隙解码与策略计算下一步动作。
     *
     * @param retryCall 上一次 QSO 失败后希望优先重试的呼号（仍出现在本批解码中才会被采纳）
     */
    fun decide(
        messages: List<DecodeResult>,
        program: AutoProgramSettings,
        filter: DecodeFilterState = DecodeFilterState(),
        worked: WorkedIndex = WorkedIndex.EMPTY,
        myCall: String = "",
        myGrid: String = "",
        retryCall: String? = null,
    ): AutoDecision {
        if (!program.level.enabled) return AutoDecision.None
        val candidates = collect(messages, program, filter, worked, myCall)
        if (candidates.isEmpty()) {
            return if (program.level.autoSearch) AutoDecision.CallCq else AutoDecision.None
        }
        // 失败重试：上一次的对手若仍在呼叫我方/发 CQ，优先回到它
        if (retryCall != null) {
            candidates.firstOrNull { it.call.equals(retryCall, ignoreCase = true) }?.let {
                return decisionOf(it)
            }
        }
        val picked = if (program.level == AutoLevel.FIRST_DECODE) {
            candidates.first()
        } else {
            candidates.sortedWith(rank(program, myGrid)).first()
        }
        return decisionOf(picked)
    }

    private fun decisionOf(t: AutoTarget): AutoDecision =
        if (t.kind == AutoTargetKind.CQ) AutoDecision.AnswerCq(t) else AutoDecision.AnswerDirected(t)

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

    /** 选台准则：报告优先 > 新呼号优先 > 最远距离 / 最强信噪比。 */
    private fun rank(program: AutoProgramSettings, myGrid: String): Comparator<AutoTarget> {
        val distanceKm: (AutoTarget) -> Double = { t -> Geo.betweenGrids(myGrid, t.grid)?.first ?: 0.0 }
        return Comparator { a, b ->
            // 1) 报告信息优先：发给我方的定向报文排在 CQ 之前
            if (program.reportPriority) {
                val ra = if (a.kind == AutoTargetKind.CQ) 1 else 0
                val rb = if (b.kind == AutoTargetKind.CQ) 1 else 0
                if (ra != rb) return@Comparator ra - rb
            }
            // 2) 优先新呼号
            if (program.preferNewCall && a.worked != b.worked) {
                return@Comparator if (a.worked) 1 else -1
            }
            // 3) 最远距离取代最佳信噪比
            if (program.farthestOverSnr) {
                distanceKm(b).compareTo(distanceKm(a))
            } else {
                b.snr.compareTo(a.snr)
            }
        }
    }
}
