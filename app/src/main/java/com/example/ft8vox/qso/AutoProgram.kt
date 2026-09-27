package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.grid.Maidenhead

/**
 * 发射监管（FT8CN `launchSupervision`）可选档位：**分钟**。
 *
 * `0`＝不监管；其余为 FT8CN `LaunchSupervisionSpinnerAdapter` 的 `5/15/…/95`。
 */
val SUPERVISION_MINUTES: List<Int> = listOf(0) + (5..95 step 10).toList()

/** 无回应次数上限（FT8CN `noReplyLimit`）：`0`＝忽略；其余 `1..30`。 */
val NO_REPLY_LIMIT_RANGE: IntRange = 0..30

/**
 * 自动程序策略（持久化于设置；**照 FT8CN 四项重做**，见 `docs/QSO.md` §5.1）。
 *
 * 已**没有档位**：自动程序的开关就是「发送总开关」`txEnabled`（见方案 §2.4）。
 */
data class AutoProgramSettings(
    /** 发射监管：`0`＝不监管，`5/15/…/95` 分钟（默认 10，FT8CN `launchSupervision`）。 */
    val supervisionMinutes: Int = 10,
    /** 无回应次数上限：`0`＝忽略（默认，FT8CN `noReplyLimit`）；超出后第 2 层换台 / 回 CQ。 */
    val noReplyLimit: Int = 0,
    /**
     * 自动收录 CQ 台（默认开；对应 FT8CN `autoFollowCQ` `GeneralVariables.java:205`）。
     *
     * **本机有意偏离 FT8CN**：FT8CN 只把 CQ 报文推送到「呼叫」列表、**不写关注名单**（其帮助文件
     * `auto_follow_help.txt` 明确说明）；本机改为**真的把解到的 CQ 台写入关注名单**
     * （`AppSettings.followCalls` + `autoFollowOrder`，见 [FollowRoster]），这样 ⭐「关注呼号列表」
     * 会随解码自动增长。
     *
     * 它同时是 [AutoProgramSelector.collect] 的 CQ 候选闸门：开启 ⇒ 任何未通联的 CQ 台都可呼叫；
     * 关闭 ⇒ **只**呼叫「关注名单」（`AppSettings.followCalls`）里的 CQ 台（名单不受本开关限制）。
     */
    val autoAddCqToFollow: Boolean = true,
    /**
     * 自动呼叫 CQ 台：是否真的去呼叫候选里的 CQ 台（默认开，FT8CN `autoCallFollow`）。
     *
     * 它是**总闸**（FT8CN `:714`，关掉一条 CQ 都不叫）；开启时，[autoAddCqToFollow] 也开 ⇒ 任何未通联
     * CQ 台都可呼叫，[autoAddCqToFollow] 关 ⇒ **只**呼叫「关注名单」（`AppSettings.followCalls`）里的 CQ 台。
     */
    val autoCallFollow: Boolean = true,
)

/** 自动程序目标的报文类型。 */
enum class AutoTargetKind {
    /** 对方的 CQ（S&P 应答的目标）。 */
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
    /** CQ 修饰符（`DX` / `NA` / `TEST` …；仅 [AutoTargetKind.CQ] 有意义）。 */
    val cqModifier: String? = null,
)

/** 第 2 层在决策点算出的下一步动作。 */
sealed interface AutoAction {
    /** 发 CQ（主叫）。 */
    data object SendCq : AutoAction

    /** 应答对方的 CQ（S&P）。 */
    data class AnswerCq(val target: AutoTarget) : AutoAction

    /**
     * 处理发给我方的定向报文（第 1 层）：
     * 呼叫我 → 直接发报告；给我报告 → 回 R 报告；Roger 我的报告 → 回 RR73 收尾。
     */
    data class HandleDirected(val target: AutoTarget) : AutoAction

    /** 保持接收、不发。 */
    data object Listen : AutoAction

    /** 发射监管触发：由 VM 关闭「发送总开关」。 */
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
 * 规则（照 FT8CN，见方案 §3.3）：
 * - **定向报文一律入选**（CALL/REPORT/ROGER），不受显示筛选、不受已通联、不受两个开关影响；
 *   只有显式「忽略该呼号」才会被挡住。
 * - **CQ 台**：仅当 [AutoProgramSettings.autoAddCqToFollow] 为真、**或**该台在 `filter.followedCalls`
 *   （关注名单）里，才入选（照 FT8CN：`autoFollowCQ || callsignInFollow`）；**已通联的一律跳过**
 *   （硬编码，对应 FT8CN `checkQSLCallsign` 过滤）；**不再套用 `DecodeFilter.matches`**（显示筛选只影响显示）。
 * - 深度/弱信号解码（[DecodeResult.deep]）不驱动自动程序。
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
            // 深度（弱信号二次）解码不驱动自动程序（FT8CN isDeep；当前 native 单遍解码 ⇒ 恒 false）
            if (m.deep) continue
            val p = MessageParser.parse(m.text)
            val from = p.from?.trim()?.uppercase() ?: continue
            if (from.equals(myCall, ignoreCase = true)) continue
            // 显式「忽略该呼号」任何时候都生效（用户主动屏蔽，不是显示筛选）
            if (from in filter.ignoredCalls) continue

            val workedCall = worked.hasWorkedCall(from)
            val directed = p.addressedTo(myCall)

            val kind: AutoTargetKind
            val report: Int?
            if (p.isCq) {
                // CQ 台：默认「自动收录 CQ 台」打开才纳入候选；照 FT8CN，**关注名单里的台是例外**
                //（开关关掉也照样纳入、会被自动呼叫）。已通联的 CQ 台一律跳过（硬编码）。
                val followed = from in filter.followedCalls
                if (!program.autoAddCqToFollow && !followed) continue
                if (workedCall) continue
                kind = AutoTargetKind.CQ
                report = null
            } else if (directed) {
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
                    cqModifier = p.cqModifier,
                ),
            )
        }
        return out
    }

    /**
     * 把**单条**解码解析成一个手动可用的目标（解码菜单「呼叫 / 回复」用）。
     *
     * 与 [collect] 共用同一套报文分类，但**不受**两个开关、已通联、显示筛选影响
     * （人工只改目标，不暂停自动程序）。无法作为 QSO 起点的报文返回 null：
     * 自听、`RR73`/`73` 收尾报文、既非 CQ 又非发给我方的报文。
     */
    fun toTarget(msg: DecodeResult, myCall: String): AutoTarget? {
        if (msg.deep) return null
        val p = MessageParser.parse(msg.text)
        val from = p.from?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (from.equals(myCall, ignoreCase = true)) return null

        val kind: AutoTargetKind
        val report: Int?
        if (p.isCq) {
            kind = AutoTargetKind.CQ
            report = null
        } else if (p.addressedTo(myCall)) {
            // RR73/73/RRR 表示通联已结束，不作为新 QSO 的起点
            if (DecodeFilter.is73(p)) return null
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
                else -> return null
            }
        } else {
            return null
        }
        return AutoTarget(
            call = from,
            grid = p.grid,
            snr = msg.snr,
            df = msg.df,
            kind = kind,
            report = report,
            slotUtcMs = msg.slotUtcMs,
            cqModifier = p.cqModifier,
        )
    }

    /**
     * 排序（无 `sortBy`）：降序键 = `(CQ 修饰符优先级, 解码顺序)`。
     *
     * `Kotlin` 的 `sortedByDescending` 是**稳定**排序，同优先级保持解码先后。
     * 优先级见 [modifierPriority]：`DX` 最高，其次「与我所在区域匹配」，再次其它修饰符，最后无修饰符。
     */
    fun rank(candidates: List<AutoTarget>, myGrid: String = ""): List<AutoTarget> {
        val myArea = areaOfGrid(myGrid)
        return candidates.sortedByDescending { modifierPriority(it.cqModifier, myArea) }
    }

    /**
     * CQ 修饰符优先级：`DX`=3 ＞ 我所在区域=2 ＞ 其它修饰符=1 ＞ 无=0。
     *
     * 修饰符可能含多个词（如 `DX NA`），取其中最高的一项。
     */
    fun modifierPriority(modifier: String?, myArea: String?): Int {
        val tokens = modifier?.trim()?.uppercase()?.split(Regex("\\s+"))
            ?.filter { it.isNotEmpty() } ?: return 0
        if (tokens.isEmpty()) return 0
        if (tokens.contains("DX")) return 3
        if (myArea != null && tokens.contains(myArea)) return 2
        return 1
    }

    /**
     * 由网格粗判「我所在的大洲/区域」（`NA`/`EU`/`AS`/`OC`/`SA`/`AF`）。
     *
     * 只用于 CQ 修饰符选台的**粗略**优先级判定（不是精确的 CQ 区域）；网格非法返回 null。
     */
    fun areaOfGrid(grid: String?): String? {
        val (lat, lon) = Maidenhead.center(grid) ?: return null
        return when {
            lon in -170.0..-30.0 && lat in 5.0..85.0 -> "NA"
            lon in 100.0..180.0 && lat in -50.0..-8.0 -> "OC"
            lon in -85.0..-30.0 && lat in -58.0..14.0 -> "SA"
            lon in -35.0..45.0 && lat in 30.0..75.0 -> "EU"
            lon in 45.0..180.0 && lat in -12.0..82.0 -> "AS"
            lon in -20.0..55.0 && lat in -38.0..38.0 -> "AF"
            else -> null
        }
    }
}

/**
 * 第 2 层：自动程序调度（纯 Kotlin，可 JVM 单测）。
 *
 * 职责（照 FT8CN，见方案 §3）：
 * - 收集本时隙候选（定向一律应答；CQ 台按两个开关与已通联过滤）并排序；
 * - 无进行中 QSO 时决定「应答某个 CQ」或「发 CQ」；
 * - 一段 QSO 结束后回到 CQ / 继续处理队列里的定向呼叫；
 * - 目标无回应超限后换台（有未通联 CQ 就应答，否则回 CQ）；
 * - 发射监管（超时返回 [AutoAction.Stop]，由 VM 关闭发送总开关）。
 *
 * 它**不**直接发报文：每个决策点返回一个 [AutoAction]，由 `SessionViewModel` 转成
 * 第 1 层 `QsoEngine` 的调用与实际发射。人工操作**不暂停**本调度（FT8CN 无 paused 概念）。
 */
class AutoScheduler {

    private var settings = AutoProgramSettings()
    private var myCall = ""
    private var myGrid = ""

    /** 定向呼叫队列（回应者 / 呼叫我方的台），逐个完成。 */
    private val queue = ArrayDeque<AutoTarget>()

    /** 发射监管计时基准（每次 [enable] / 人工操作 [resetSupervision] 重置）。 */
    private var supervisionSinceMs = 0L

    /** 发射监管已触发（停止后再不产出动作，直到 [enable] / [resetSupervision]）。 */
    private var protectedStop = false

    fun configure(s: AutoProgramSettings, myCall: String, myGrid: String) {
        settings = s
        this.myCall = myCall.trim().uppercase()
        this.myGrid = myGrid.trim().uppercase()
    }

    /** 「发送总开关」打开时调用：清空队列、复位监管计时。 */
    fun enable(utcNowMs: Long) {
        queue.clear()
        protectedStop = false
        supervisionSinceMs = utcNowMs
    }

    /** 「发送总开关」关闭 / 停止会话时调用。 */
    fun disable() {
        queue.clear()
        protectedStop = false
        supervisionSinceMs = 0L
    }

    fun queuedCount(): Int = queue.size

    fun isProtectedStop(): Boolean = protectedStop

    /** 人工操作（手动呼叫 / 应答 / 发 CQ）后复位发射监管计时（FT8CN `resetLaunchSupervision`）。 */
    fun resetSupervision(utcNowMs: Long) {
        supervisionSinceMs = utcNowMs
        protectedStop = false
    }

    /** 发射监管检查：触发返回可读原因，否则 null（方案 §3.5）。 */
    fun checkSupervision(utcNowMs: Long): String? {
        if (protectedStop) return null
        if (supervisionSinceMs == 0L) supervisionSinceMs = utcNowMs
        val m = settings.supervisionMinutes
        if (m <= 0 || utcNowMs <= 0 || supervisionSinceMs <= 0) return null
        if (utcNowMs - supervisionSinceMs >= m * 60_000L) {
            return "发射监管超时（连续发射 $m 分钟）"
        }
        return null
    }

    /** 标记发射监管已触发（避免每时隙重复上报）。 */
    fun markProtectedStop() {
        protectedStop = true
    }

    /**
     * 接收时隙结束、且当前无进行中 QSO（或处于「已发 CQ、等回应者」）时的决策。
     *
     * 顺序（方案 §3.2）：监管超时 → Stop；定向候选非空 → 一律应答（最高优先）；
     * 否则按两个开关决定「应答未通联的 CQ」或「发 CQ」。
     */
    fun onDecoded(
        messages: List<DecodeResult>,
        filter: DecodeFilterState = DecodeFilterState(),
        worked: WorkedIndex = WorkedIndex.EMPTY,
        utcNowMs: Long = 0L,
    ): AutoAction {
        checkSupervision(utcNowMs)?.let { return AutoAction.Stop(it) }
        if (protectedStop) return AutoAction.None

        val candidates = AutoProgramSelector.collect(messages, settings, filter, worked, myCall)
        val directed = candidates.filter { it.kind != AutoTargetKind.CQ }
        // 「有人呼叫我方一定应答」：定向报文优先，且不受任何开关 / 筛选 / 已通联影响
        if (directed.isNotEmpty()) {
            enqueue(AutoProgramSelector.rank(directed, myGrid))
            return AutoAction.HandleDirected(queue.removeFirst())
        }
        return nextAction(candidates.filter { it.kind == AutoTargetKind.CQ })
    }

    /** 一段 QSO 结束（成功）后的决策：先处理队列，否则回到发 CQ（FT8CN `resetToCQ`）。 */
    fun onQsoFinished(utcNowMs: Long = 0L): AutoAction {
        if (protectedStop) return AutoAction.None
        checkSupervision(utcNowMs)?.let { return AutoAction.Stop(it) }
        if (queue.isNotEmpty()) return AutoAction.HandleDirected(queue.removeFirst())
        return AutoAction.SendCq
    }

    /**
     * 目标无回应超限、被判定作废后的决策（FT8CN `getNewTargetCallsign`）：
     * **优先换台**（有未通联 CQ 候选就应答最优的），**没有才回 CQ**。
     */
    fun onTargetGaveUp(
        messages: List<DecodeResult>,
        filter: DecodeFilterState = DecodeFilterState(),
        worked: WorkedIndex = WorkedIndex.EMPTY,
        utcNowMs: Long = 0L,
    ): AutoAction {
        checkSupervision(utcNowMs)?.let { return AutoAction.Stop(it) }
        if (protectedStop) return AutoAction.None
        queue.clear()
        val candidates = AutoProgramSelector.collect(messages, settings, filter, worked, myCall)
        val directed = candidates.filter { it.kind != AutoTargetKind.CQ }
        if (directed.isNotEmpty()) {
            return AutoAction.HandleDirected(AutoProgramSelector.rank(directed, myGrid).first())
        }
        return nextAction(candidates.filter { it.kind == AutoTargetKind.CQ })
    }

    /**
     * FT8CN `checkCQMeOrFollowCQMessage` **循环 2** 的落地：进行中的 QSO **目标本批沉默**时，
     * 也必须应答「其他呼叫我方」的定向台（否则忙起来就会漏应答）。
     *
     * 与 [onDecoded] 的差别：**不看**两个开关、不看是否已有目标，只排除「当前目标自身」
     * 与本批已结束的 `RR73`/`73`/`RRR`；命中则返回应切换到的目标，没有则 null
     * （调用方继续走无回应 / 放弃判定）。保持当前目标不换台由调用方在目标有回应时不调用本方法保证。
     */
    fun directedTakeover(
        messages: List<DecodeResult>,
        currentTarget: String?,
        filter: DecodeFilterState = DecodeFilterState(),
        worked: WorkedIndex = WorkedIndex.EMPTY,
    ): AutoTarget? {
        if (protectedStop) return null
        val directed = AutoProgramSelector.collect(messages, settings, filter, worked, myCall)
            .filter { it.kind != AutoTargetKind.CQ }
            .filter { !CallMatch.isFrom(it.call, currentTarget) }
        if (directed.isEmpty()) return null
        return AutoProgramSelector.rank(directed, myGrid).first()
    }

    /**
     * 「优先应答未通联的 CQ 台」（S&P）；否则发 CQ。
     *
     * 对应 FT8CN `checkCQMeOrFollowCQMessage` 循环 3（条件
     * `autoCallFollow && (autoFollowCQ || 该台在关注名单里)`）。本机两个开关**串联**生效：
     * - [AutoProgramSettings.autoAddCqToFollow]：CQ 台是否**纳入候选**（照 FT8CN「自动关注 CQ」＝把 CQ
     *   推送到可呼叫集合；已在 [AutoProgramSelector.collect] 里把关，关注名单是它的例外）。
     * - [AutoProgramSettings.autoCallFollow]：是否**真的去呼叫**（FT8CN `:714`，关掉则一条 CQ 都不叫）。
     *
     * 因此「自动收录 CQ 台」关掉后，本机**只**自动呼叫关注名单里的 CQ 台（没有名单时就不叫 CQ）。
     */
    private fun nextAction(cqs: List<AutoTarget>): AutoAction =
        if (settings.autoCallFollow && cqs.isNotEmpty()) {
            AutoAction.AnswerCq(AutoProgramSelector.rank(cqs, myGrid).first())
        } else {
            AutoAction.SendCq
        }

    private fun enqueue(list: List<AutoTarget>) {
        list.forEach { t ->
            if (queue.none { it.call.equals(t.call, ignoreCase = true) }) queue.addLast(t)
        }
    }
}
