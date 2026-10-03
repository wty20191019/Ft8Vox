package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult

/**
 * 发射监管（FT8CN `launchSupervision`）可选档位：**分钟**。
 *
 * `0`＝不监管；其余为 FT8CN `LaunchSupervisionSpinnerAdapter` 的 `5/15/…/95`。
 */
val SUPERVISION_MINUTES: List<Int> = listOf(0) + (5..95 step 10).toList()

/**
 * 无回应次数上限（FT8CN `noReplyLimit`）：`0`＝按**内置硬上限**（20 个批次）收尾；
 * 其余 `1..30`＝**连续 `noReplyCount` 达到该值**时收尾。
 *
 * **与 FT8CN 的有意偏离**：FT8CN `:836` 用 `noReplyCount > 2 × limit`，本机改为字面值，
 * 让设置档位与界面「达到该次数」的含义一致。
 */
val NO_REPLY_LIMIT_RANGE: IntRange = 0..30

/**
 * 自动程序策略（持久化于设置；**照 FT8CN 四项重做**，见 `docs/Ft8Vox.md`）。
 *
 * 已**没有档位**：自动程序的开关就是「发送总开关」`txEnabled`（见方案 §2.4）。
 */
data class AutoProgramSettings(
    /** 发射监管：`0`＝不监管，`5/15/…/95` 分钟（默认 10，FT8CN `launchSupervision`）。 */
    val supervisionMinutes: Int = 10,
    /**
     * 无回应次数上限（默认 `3`；对应 FT8CN `noReplyLimit`，但**本机有意偏离其 `× 2` 口径**——
     * 设置值就是「连续多少个解码批次无回应」）。
     *
     * 判据在**除 CQ 主叫（序号 6）外的各阶段**都生效（FT8CN 只在**我发过 RR73（序号 4）**后生效，
     * 会让停在序号 1~3 的呼叫者永远重发、无法换台）：
     * `> 0` 时 `noReplyCount >= limit` 收尾；`== 0` 时仍以 **20 个批次**为硬上限。
     * 命中后由 `AutoScheduler.onTargetGaveUp` 换台 / 回 CQ。
     */
    val noReplyLimit: Int = 3,
    /**
     * 自动跟踪 CQ（默认开；对应 FT8CN `autoFollowCQ` `GeneralVariables.java:205`）。
     *
     * **与 FT8CN 的有意偏离**：FT8CN 只把关「哪些 CQ 台可以进候选被自动呼叫」，不写名单
     * （其帮助文件 `auto_follow_help.txt` 明确写「该呼号不会被长久保存到关注的呼号数据库中」）；
     * 本机开启时还会把本波段未通联的 CQ 台**自动写进 ⭐「跟踪 CQ 列表」**（见 `FollowRoster`），
     * 并在该台通联完成后自动移除。
     *
     * 它同时是 [AutoProgramSelector.collect] 的 CQ 候选闸门：开启 ⇒ 任何本波段未通联的 CQ 台都可呼叫；
     * 关闭 ⇒ **只**呼叫「跟踪名单」（`AppSettings.followCalls`）里的 CQ 台（名单不受本开关限制）。
     */
    val autoAddCqToFollow: Boolean = true,
    /**
     * 自动呼叫 CQ 台：是否真的去呼叫候选里的 CQ 台（默认开，FT8CN `autoCallFollow`）。
     *
     * 它是**总闸**（FT8CN `:714`，关掉一条 CQ 都不叫）；开启时，[autoAddCqToFollow] 也开 ⇒ 任何本波段
     * 未通联的 CQ 台都可呼叫，[autoAddCqToFollow] 关 ⇒ **只**呼叫「跟踪名单」（`AppSettings.followCalls`）
     * 里的 CQ 台。
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

    /**
     * 发给我、且本身就是 `RR73` / `RRR` 的定向报文（序号 4）。
     *
     * 照 FT8CN `checkCQMeOrFollowCQMessage`（只排除 `73`）：这种报文也要接 —— 我回 `73` 收尾
     * （我方序号 5）。这正是「App 重启 / 丢了状态后把 QSO 的尾巴接回来」的路径。
     */
    RR73,
}

/** 自动程序挑出的目标台站。 */
data class AutoTarget(
    val call: String,
    val grid: String?,
    val snr: Int,
    val df: Int,
    val kind: AutoTargetKind,
    /**
     * 对方发给我的信号报告：仅 [AutoTargetKind.REPORT] / [AutoTargetKind.ROGER] 有意义
     * （[AutoTargetKind.RR73] 无报告，回 73 时不需要）。
     */
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
     * 呼叫我 → 直接发报告；给我报告 → 回 R 报告；Roger 我的报告 → 回 RR73 收尾；
     * 直接给我 RR73 → 回 73 收尾。
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
 * 第 2 层选台：从本时隙解码里收集候选（纯 Kotlin，可 JVM 单测）。
 *
 * 候选覆盖两类「能推进到完成 QSO」的报文：
 * - 对方的 **CQ**（[AutoTargetKind.CQ]，我来应答）；
 * - **发给我** 的定向报文：呼叫我 / 给我报告 / Roger 我的报告 / 直接给我 `RR73`。
 * 只有 `73` 表示通联已结束，不作为 QSO 起点（照 FT8CN `checkCQMeOrFollowCQMessage`）。
 *
 * 规则（照 FT8CN，见方案 §3.3）：
 * - **定向报文一律入选**（CALL/REPORT/ROGER/RR73），不受显示筛选、不受已通联、不受两个开关影响；
 *   只有显式「忽略该呼号」才会被挡住。
 * - **CQ 台**：仅当 [AutoProgramSettings.autoAddCqToFollow] 为真、**或**该台在 `filter.followedCalls`
 *   （跟踪名单）里，才入选（照 FT8CN：`autoFollowCQ || callsignInFollow`）；**本波段已通联的一律跳过**
 *   （硬编码，对应 FT8CN `checkQSLCallsign` 的 `where band=?`；调用方传入的 [worked] 即「只含当前波段」，
 *   跨波段通联过的台在本波段仍可入选）；**不再套用 `DecodeFilter.matches`**（显示筛选只影响显示）。
 * - **不做 DX / 区域分级**（照 FT8CN：选台只看可用性，不按国家/洲加权），候选顺序即解码顺序。
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
                // CQ 台：默认「自动跟踪 CQ」打开才纳入候选；照 FT8CN，**跟踪名单里的台是例外**
                //（开关关掉也照样纳入、会被自动呼叫）。本波段已通联的 CQ 台一律跳过（照 FT8CN
                // `checkQSLCallsign` 的 `where band=?`；[worked] 由调用方按当前波段构造）。
                val followed = from in filter.followedCalls
                if (!program.autoAddCqToFollow && !followed) continue
                if (workedCall) continue
                kind = AutoTargetKind.CQ
                report = null
            } else if (directed) {
                // 定向报文；只有 73（序号 5）表示对方和我已互相收尾，不据此开新 QSO。
                // RR73 / RRR（序号 4）要接：我回 73 收尾（照 FT8CN，见 AutoTargetKind.RR73）。
                if (p.is73) continue
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
                    p.isRr73 || p.isRoger -> {
                        kind = AutoTargetKind.RR73
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
     * 自听、`73` 收尾报文、既非 CQ 又非发给我方的报文。
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
            // 只有 73 表示通联已结束；RR73 / RRR 可接（回 73 收尾，照 FT8CN）
            if (p.is73) return null
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
                p.isRr73 || p.isRoger -> {
                    kind = AutoTargetKind.RR73
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
     * 排序：**照 FT8CN 不做 DX / 区域分级**，候选保持解码顺序（同批内解码器给的先后）。
     *
     * 调用方取 `.first()`，即「本批里最先解到的那台」。FT8CN 是在候选表里从后往前扫，
     * 本机解码批次本来就是**新→旧**逐时隙单独处理，跨时隙的先后由批次本身决定，故这里不再重排。
     */
    fun rank(candidates: List<AutoTarget>): List<AutoTarget> = candidates
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

    /** 定向呼叫队列（回应者 / 呼叫我方的台），逐个完成。 */
    private val queue = ArrayDeque<AutoTarget>()

    /** 发射监管计时基准（每次 [enable] / 人工操作 [resetSupervision] 重置）。 */
    private var supervisionSinceMs = 0L

    /** 发射监管已触发（停止后再不产出动作，直到 [enable] / [resetSupervision]）。 */
    private var protectedStop = false

    fun configure(s: AutoProgramSettings, myCall: String) {
        settings = s
        this.myCall = myCall.trim().uppercase()
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
            enqueue(AutoProgramSelector.rank(directed))
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
            return AutoAction.HandleDirected(AutoProgramSelector.rank(directed).first())
        }
        return nextAction(candidates.filter { it.kind == AutoTargetKind.CQ })
    }

    /**
     * FT8CN `checkCQMeOrFollowCQMessage` **循环 2** 的落地：进行中的 QSO **目标本批沉默**时，
     * 也必须应答「其他呼叫我方」的定向台（否则忙起来就会漏应答）。
     *
     * 与 [onDecoded] 的差别：**不看**两个开关、不看是否已有目标，只排除「当前目标自身」；
     * 本批里 `73`（已互相收尾）也不据以开新 QSO，`RR73`/`RRR` 可以（回 73 收尾）。
     * 命中则返回应切换到的目标，没有则 null
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
        return AutoProgramSelector.rank(directed).first()
    }

    /**
     * 「优先应答未通联的 CQ 台」（S&P）；否则发 CQ。
     *
     * 对应 FT8CN `checkCQMeOrFollowCQMessage` 循环 3（条件
     * `autoCallFollow && (autoFollowCQ || 该台在跟踪名单里)`）。本机两个开关**串联**生效：
     * - [AutoProgramSettings.autoAddCqToFollow]：CQ 台是否**纳入候选**（并自动写进跟踪名单；照 FT8CN「自动
     *   跟踪 CQ」＝把 CQ 推送到可呼叫集合；已在 [AutoProgramSelector.collect] 里把关，跟踪名单是它的例外）。
     * - [AutoProgramSettings.autoCallFollow]：是否**真的去呼叫**（FT8CN `:714`，关掉则一条 CQ 都不叫）。
     *
     * 因此「自动跟踪 CQ」关掉后，本机**只**自动呼叫跟踪名单里的 CQ 台（没有名单时就不叫 CQ）。
     */
    private fun nextAction(cqs: List<AutoTarget>): AutoAction =
        if (settings.autoCallFollow && cqs.isNotEmpty()) {
            AutoAction.AnswerCq(AutoProgramSelector.rank(cqs).first())
        } else {
            AutoAction.SendCq
        }

    private fun enqueue(list: List<AutoTarget>) {
        list.forEach { t ->
            if (queue.none { it.call.equals(t.call, ignoreCase = true) }) queue.addLast(t)
        }
    }
}
