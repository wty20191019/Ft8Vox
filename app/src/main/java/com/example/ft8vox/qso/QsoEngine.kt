package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult

/** 本次 QSO 中我的角色。 */
enum class QsoRole { NONE, CALLER, RESPONDER }

/** QSO 状态机的状态。 */
enum class QsoState {
    IDLE,
    /** 已发出本轮报文，等待对方有效回复。 */
    WAIT_REPLY,
    /** 我已发出信号报告，等待对方 R 报告。 */
    WAIT_REPORT,
    /** 我已发出 R 报告（或作为应答方），等待对方 RR73/73。 */
    WAIT_RR73,
    DONE,
    FAILED,
}

/** 一次完成的通联记录（阶段 7 将持久化到 ADIF）。 */
data class QsoLogEntry(
    val theirCall: String,
    val theirGrid: String?,
    val reportSent: Int?,
    val reportReceived: Int?,
    /** 通联**完成**时间（UTC 毫秒），即 ADIF 的 `QSO_DATE_OFF` / `TIME_OFF`。 */
    val utcMs: Long,
    /**
     * 通联**起始**时间（UTC 毫秒），即 ADIF 的 `QSO_DATE` / `TIME_ON`。
     *
     * 照 FT8CN 的 `startTime`：本段 QSO 第一次发射/应答的时刻；无法取得时回退为完成时间。
     */
    val startUtcMs: Long = 0,
)

/** QSO 状态机对外暴露的只读进度。 */
data class QsoProgress(
    val role: QsoRole = QsoRole.NONE,
    val state: QsoState = QsoState.IDLE,
    val theirCall: String? = null,
    val theirGrid: String? = null,
    val reportSent: Int? = null,
    val reportReceived: Int? = null,
    /** 下一个发射时隙要发送的报文；null 表示不发。 */
    val txText: String? = null,
    /** 六步指令序列的当前序号（1=网格 / 2=报告 / 3=R报告 / 4=RR73 / 5=73 / 6=CQ；0=无）。 */
    val order: Int = 0,
    /**
     * 无回应计数（FT8CN 口径：按**解码批次**累计，收到有效回复即清零）。
     *
     * 它对应 FT8CN 的 `noReplyLimit` 安全阀，供第 2 层判断「超出后换台 / 回 CQ」；
     * 引擎自身**不再**因重试耗尽而放弃（`retryLimit` 体系已退役，见方案 §1.5）。
     */
    val noReplyCount: Int = 0,
    /**
     * 是否处于「已发 CQ、等回应者」阶段（只由 [QsoEngine.startCq] 置位）。
     *
     * 该阶段的解码由第 2 层自动程序调度（收集、排序回应者）处理，而不是由状态机
     * 直接认第一个回应者；`SessionViewModel` 据此分流。
     */
    val awaitingResponders: Boolean = false,
    /**
     * 最近一次 [QsoEngine.onDecoded] 是否发生了推进（收到**当前对手**的有效回复）。
     *
     * 第 2 层据此识别「当前目标本批沉默」→ 应答其他呼叫我方的定向台
     * （FT8CN `checkCQMeOrFollowCQMessage` 循环 2；见 `docs/QSO.md` §3.1）。
     */
    val advanced: Boolean = false,
    /**
     * 当前 [txText] 是否**已经真正发出去**过。
     *
     * 状态机一收到对方报文就把 [txText] 排定为下一跳，但**要等到我方时隙才会真的播出去**；
     * 若 UI 直接说「已发出」，就会出现「其实还没发」的误导（真机反馈）。
     * 这里由 [QsoEngine.onTransmitted] 记录真正播出去的文本，[QsoProgress.txText] 与之一致时为 `true`。
     */
    val txSent: Boolean = false,
) {
    /** 是否处于进行中的 QSO。 */
    val active: Boolean
        get() = state != QsoState.IDLE && state != QsoState.DONE && state != QsoState.FAILED

    /** 本阶段还没轮到我方时隙发射时的提示后缀。 */
    private val pendingHint: String get() = "待发（等我的发射时隙）"

    val description: String
        get() = when (state) {
            QsoState.IDLE -> "空闲"
            QsoState.DONE -> "已完成"
            QsoState.FAILED -> "已放弃（对方无响应）"
            QsoState.WAIT_REPLY ->
                when {
                    awaitingResponders && txSent -> "CQ 已发出，等待回应"
                    awaitingResponders -> "CQ $pendingHint"
                    txSent -> "已呼叫 $theirCall，等待回复"
                    else -> "呼叫 $theirCall $pendingHint"
                }
            QsoState.WAIT_REPORT -> {
                val r = reportSent?.let { MessageParser.formatReport(it) } ?: ""
                if (txSent) "已发报告 $r，等待 $theirCall 的 R 报告"
                else "报告 $r $pendingHint"
            }
            QsoState.WAIT_RR73 ->
                if (txSent) "已发 R 报告，等待 $theirCall 的 RR73"
                else "R 报告 $pendingHint"
        }
}

/**
 * FT8/FT4 的 QSO 自动序列状态机（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * ### 设计：报文驱动的收敛阶梯
 *
 * 早期版本是「角色优先的固定脚本」（先假定自己是主叫或应答方），只在很窄的
 * 状态转移上推进。两台机器同时自动运行时，只要一端停在 `WAIT_RR73`（已发 R）、
 * 另一端停在 `WAIT_REPORT`（还在发纯报告），前者收到的报文不匹配任何转移，就会
 * 一直重发 R 直到重试耗尽；而重试耗尽后第 2 层又会重新挑同一个台、从错误阶段重启，
 * 于是两个台在同一段上反复跑死（真机现象：`-08` / `R-10` 每 30 s 交替、永不结束）。
 *
 * 现在改成：**任何一个非终态下收到对方任一形态的报文，都用同一条阶梯算出正确的下一步**，
 * 角色（主叫 / 应答）由报文内容动态推断，而不是开局写死。阶梯（按优先级）：
 *
 * 1. 收到 `73`：对方收尾 → 直接完成，不再发。
 * 2. 收到 `RR73`：回 `73` 并完成。
 * 3. 收到 `R<报告>`（Roger）：对方已确认收到我的报告 → 回 `RR73` 并完成。
 * 4. 收到**纯报告** `<我> <对方> <报告>`：
 *    - 我已在 `ROGER`（回过 R）：说明对方没收到我的 R，**直接回 `RR73` 收尾**
 *      （两颗报告其实已互换），从而打破死循环；
 *    - 否则：对方是主叫（senior），我转为应答方，回**我自己实测的** `R<报告>`。
 * 5. 收到**网格** `<我> <对方> <网格>`（对方在应答我 / 双方同时应答）：我为主叫
 *    （senior），直接发**我自己实测的**信号报告；已发过报告之后的重复网格忽略。
 *
 * 这样任意两端从任意阶段进入，都会在若干步内收敛到 `DONE`，且空中不出现重复报文。
 *
 * ### 无回应与放弃
 *
 * 引擎自身**不再**因重试耗尽而放弃（`retryLimit` 体系退役，见方案 §1.5）：未推进时按
 * **解码批次**累计 [QsoProgress.noReplyCount]（FT8CN 口径），收到有效回复即清零。
 * 是否「目标作废、换台 / 回 CQ」由第 2 层按设置项 `noReplyLimit` 决定。
 *
 * 驱动方式：每个接收时隙结束后把解码结果交给 [onDecoded]。
 */
class QsoEngine {

    /**
     * 我在本段 QSO 里**最后已发出**的那一步（报文的语义阶段）。
     *
     * 取值即 FT8CN 的 `functionOrder`（见 `docs/QSO.md` §2.1 六步指令序列）：
     * 1=网格 / 2=报告 / 3=R报告 / 4=RR73 / 5=73 / 6=CQ；[NONE] 为无步骤（order 0）。
     */
    private enum class Step(val order: Int) {
        NONE(0), GRID(1), REPORT(2), ROGER(3), RR73(4), SEVENTY3(5), CQ(6)
    }

    private var myCall: String = ""
    private var myGrid: String = ""
    /** CQ 前缀（`CQ <前缀> <我> <网格>`；空串＝普通 CQ），由 [startCq] 设置。 */
    private var cqModifier: String = ""

    private var role = QsoRole.NONE
    private var state = QsoState.IDLE
    private var step = Step.NONE
    private var theirCall: String? = null
    private var theirGrid: String? = null
    private var reportSent: Int? = null
    private var reportReceived: Int? = null
    /**
     * Tx2 实际发出的报告快照：Tx3 的 `R<报告>` **复用**它（见 `docs/QSO.md` §2.4）。
     */
    private var lastSentReport: Int? = null
    private var txText: String? = null
    /** 本段 QSO 的起始时间（UTC 毫秒，FT8CN `startTime` 口径；0＝未知，落库时回退为完成时间）。 */
    private var startedUtcMs = 0L
    /** 无回应计数（FT8CN 口径，按解码批次累计，收到回复清零）。 */
    private var noReplyCount = 0
    /** 最近一次 [onDecoded] 是否推进（供第 2 层识别「目标本批沉默」，见 [QsoProgress.advanced]）。 */
    private var lastAdvanced = false
    /** **真正发出去**的那条报文文本（[onTransmitted] 记录，用于 [QsoProgress.txSent] 判定）。 */
    private var lastSentText: String? = null
    private var logEntry: QsoLogEntry? = null

    /** 是否处于「已发 CQ、等回应者」阶段（见 [QsoProgress.awaitingResponders]）。 */
    private var awaitingResponders = false

    /** 是否已配置好呼号，可以开始 QSO。 */
    val canOperate: Boolean get() = myCall.isNotEmpty()

    /** 更新台站信息（来自设置）。 */
    fun configure(
        myCall: String,
        myGrid: String,
    ) {
        this.myCall = myCall.trim().uppercase()
        this.myGrid = myGrid.trim().uppercase()
    }

    fun progress(): QsoProgress = QsoProgress(
        role = role,
        state = state,
        theirCall = theirCall,
        theirGrid = theirGrid,
        reportSent = reportSent,
        reportReceived = reportReceived,
        txText = txText,
        order = step.order,
        noReplyCount = noReplyCount,
        awaitingResponders = awaitingResponders,
        advanced = lastAdvanced,
        txSent = txText != null && txText == lastSentText,
    )

    /** 取走刚完成的通联记录（一次性，取走后清空）。 */
    fun consumeCompleted(): QsoLogEntry? {
        val e = logEntry
        logEntry = null
        return e
    }

    /**
     * 通知状态机：某一轮报文已实际发射完毕。
     *
     * [sentText] 为**实际发出去**的那条报文文本。真机上存在这样的时序：本时隙的发射在前导
     * 提前量到点时就被排定，而上一时隙的解码要等到时隙末尾才处理完；于是 ``qso.txText``
     * 已经推进成收尾的 `73`/`RR73`，**真正在播的却还是旧报文**（如 `R-07`）。此时若照旧
     * 在 DONE 下清空 `txText`，收尾报文就再也没有时隙可发 —— 真机现象是「对方给我 RR73
     * 后我没有回 73，反而开始发 CQ」。
     *
     * 所以只有「刚发出去的这一条就是当前应发报文」才允许清空；发的是旧报文时**保留**，
     * 由第 2 层在下一次我方时隙把它发出去（见 `SessionViewModel.txTick`）。
     */
    fun onTransmitted(sentText: String? = null) {
        // 记录「真正发出去的是哪条」，供 QsoProgress.txSent / description 区分「待发」与「已发出」
        if (sentText != null) lastSentText = sentText
        if (state == QsoState.DONE || state == QsoState.FAILED) {
            if (sentText == null || txText == null || sentText == txText) txText = null
        }
        // Tx2 实际发出后快照，供 Tx3 的 R 报告复用（「每次重测最新」⇒ 重发 Tx2 会刷新）
        if (step == Step.REPORT && reportSent != null) lastSentReport = reportSent
    }

    /**
     * 开始呼叫 CQ。
     *
     * [utcMs] 为本段起始时间（UTC 毫秒，用于日志 `startTime`）；[cqPrefix] 为 CQ 前缀
     * （插在 `CQ` 与我方呼号之间，空串＝普通 CQ），**整段 CQ 阶段沿用**（每周期重发同一条）。
     */
    fun startCq(utcMs: Long = 0L, cqPrefix: String = ""): QsoProgress {
        require(canOperate) { "未配置呼号" }
        reset()
        startedUtcMs = if (utcMs > 0) utcMs else 0L
        role = QsoRole.CALLER
        awaitingResponders = true
        step = Step.CQ
        cqModifier = cqPrefix.trim().uppercase()
        render()
        syncState()
        return progress()
    }

    /**
     * 第 1 层「应答 QSO」：我应答对方的 CQ。
     *
     * 先发 `<对方> <我> <网格>`（让 CQ 台拿到我的网格），随后等报告 → 发 `R<报告>` →
     * 等 RR73 → 发 73 收尾。
     */
    fun startResponderQso(call: String, grid: String?, utcMs: Long = 0L): QsoProgress {
        require(canOperate) { "未配置呼号" }
        val their = call.trim().uppercase()
        if (their.isEmpty() || their == myCall) return progress()
        reset()
        startedUtcMs = if (utcMs > 0) utcMs else 0L
        role = QsoRole.RESPONDER
        theirCall = their
        theirGrid = grid?.trim()?.uppercase()?.ifEmpty { null }
        step = Step.GRID
        render()
        syncState()
        return progress()
    }

    /**
     * 第 1 层「主叫 QSO」：我方发过 CQ，[call] 是回应者。
     *
     * 直接从「发信号报告」开始（对方已经用网格/报告回应了我方的 CQ）：
     * 发 `<对方> <我> <报告>` → 等 `R<报告>` → 发 RR73 → 完成。
     *
     * 也用于「对方主动呼叫我方（`<我> <对方> <网格>`）」：即使我方没有进行中的 QSO，
     * 也直接补发报告把这段 QSO 跑完。
     *
     * @param snr 本次解码的信噪比（作为我发给对方的报告）
     */
    fun startCallerQso(call: String, grid: String?, snr: Int, utcMs: Long = 0L): QsoProgress {
        require(canOperate) { "未配置呼号" }
        val their = call.trim().uppercase()
        if (their.isEmpty() || their == myCall) return progress()
        reset()
        startedUtcMs = if (utcMs > 0) utcMs else 0L
        role = QsoRole.CALLER
        theirCall = their
        theirGrid = grid?.trim()?.uppercase()?.ifEmpty { null }
        reportSent = reportFromSnr(snr)
        step = Step.REPORT
        render()
        syncState()
        return progress()
    }

    /**
     * 对方直接发来信号报告（未经我应答）：以应答方身份从「已收报告」阶段进入 QSO。
     *
     * 用于自动程序的「被呼自动应答」：对方发 `<myCall> <theirCall> <report>` 时，
     * 我直接回 `R<报告>` 并等待对方 RR73。
     *
     * @param theirReport 对方给我的信号报告
     * @param snr 本次解码的信噪比（作为我发给对方的报告）
     */
    fun respondToReport(call: String, theirReport: Int, snr: Int, utcMs: Long = 0L): QsoProgress {
        require(canOperate) { "未配置呼号" }
        val their = call.trim().uppercase()
        if (their.isEmpty() || their == myCall) return progress()
        reset()
        startedUtcMs = if (utcMs > 0) utcMs else 0L
        role = QsoRole.RESPONDER
        theirCall = their
        reportReceived = theirReport
        reportSent = reportFromSnr(snr)
        step = Step.ROGER
        render()
        syncState()
        return progress()
    }

    /**
     * 对方已 Roger 我的报告（`<myCall> <theirCall> R<report>`）：回 `RR73` 并直接完成本次通联。
     *
     * 用于自动程序的「被呼自动应答」：我方空闲时收到带 R 的定向报文，说明对方已确认，
     * 只需收尾即可完成 QSO。
     *
     * @param utcMs 本次通联的 UTC 时间（毫秒）；<=0 时记录为 0
     */
    fun respondToRoger(call: String, theirReport: Int, snr: Int, utcMs: Long = 0L): QsoProgress {
        require(canOperate) { "未配置呼号" }
        val their = call.trim().uppercase()
        if (their.isEmpty() || their == myCall) return progress()
        reset()
        role = QsoRole.RESPONDER
        theirCall = their
        reportReceived = theirReport
        reportSent = reportFromSnr(snr)
        step = Step.RR73
        render()
        startedUtcMs = if (utcMs > 0) utcMs else 0L
        finish(utcMs = if (utcMs > 0) utcMs else 0L, slotUtcMs = 0L)
        return progress()
    }

    /** 中止当前 QSO。 */
    fun stop(): QsoProgress {
        reset()
        return progress()
    }

    /**
     * 处理一个接收时隙的解码结果，推进状态机。
     *
     * 每次调用只消费一条有效报文；未产生推进时按 FT8CN 口径累计 [noReplyCount]
     * （由第 2 层据此判断换台）；深度/弱信号批次不计。
     */
    fun onDecoded(messages: List<DecodeResult>, utcMs: Long = 0L): QsoProgress {
        lastAdvanced = false
        if (!canOperate) return progress()
        if (!progress().active) return progress()
        // 「已发 CQ、等回应者」阶段由第 2 层自动程序收集/排序回应者，状态机不自行认人
        if (awaitingResponders) return progress()

        val them = theirCall
        var advanced = false
        var latestTargetSnr: Int? = null
        for (m in messages) {
            // 深度（弱信号二次）解码只用于显示，不推进 QSO（FT8CN isDeep；当前恒 false）
            if (m.deep) continue
            val p = MessageParser.parse(m.text)
            val from = p.from ?: continue
            if (from.equals(myCall, ignoreCase = true)) continue // 忽略自己
            // 记录当前对手的最新 SNR（供「每次重测最新」刷新 Tx2 的报告）
            if (them != null && CallMatch.isFrom(from, them)) latestTargetSnr = m.snr
            if (!p.addressedTo(myCall)) continue // 只处理发给我的
            if (them != null && !CallMatch.isFrom(from, them)) continue // 只认当前对手
            if (applyMessage(p, m, utcMs)) {
                advanced = true
                break
            }
        }

        lastAdvanced = advanced
        if (advanced) {
            noReplyCount = 0
            // 非终态转移只改了 step，需要把对外 state 同步过来（终态分支里 finish 已同步，幂等）
            syncState()
        } else {
            // 「每次重测最新」：未推进时用当前对手的最新 SNR 刷新 Tx2 的报告（重发时生效），
            // 已发出的 R 报告由 [lastSentReport] 冻结，不受此刷新影响。
            if (step == Step.REPORT && latestTargetSnr != null) {
                reportSent = reportFromSnr(latestTargetSnr)
                render()
            }
            // 无回应按解码批次累计（FT8CN 口径；含空批）；纯深度/弱信号批次不计（方案 §1.6）
            if (messages.isEmpty() || messages.any { !it.deep }) noReplyCount++
            syncState()
        }
        return progress()
    }

    /**
     * 用一条报文推进状态；返回是否发生推进。
     *
     * 见类注释的「收敛阶梯」：这里对**任意非终态**都接受任意形态的报文，并按阶梯
     * 决定下一步，从而保证两端无论从哪个阶段进入都能收敛。
     */
    private fun applyMessage(p: ParsedMessage, m: DecodeResult, utcMs: Long): Boolean {
        val them = theirCall ?: p.from ?: return false
        if (state == QsoState.DONE || state == QsoState.FAILED) return false

        return when {
            // 1) 对方收尾（73）：我无需再发，直接完成
            p.is73 -> {
                reportSent = lastSentReport ?: reportSent ?: reportFromSnr(m.snr)
                step = Step.SEVENTY3
                txText = null
                finish(utcMs, m.slotUtcMs)
                true
            }

            // 2) 对方 RR73：回 73 并完成
            p.isRr73 -> {
                reportSent = lastSentReport ?: reportSent ?: reportFromSnr(m.snr)
                step = Step.SEVENTY3
                render()
                finish(utcMs, m.slotUtcMs)
                true
            }

            // 3) 对方 R 报告：已确认收到我的报告 → 回 RR73 并完成
            p.isRoger -> {
                reportReceived = reportReceived ?: p.report
                reportSent = lastSentReport ?: reportSent ?: reportFromSnr(m.snr)
                step = Step.RR73
                render()
                finish(utcMs, m.slotUtcMs)
                true
            }

            // 4) 对方发来（纯）信号报告
            p.report != null -> {
                theirGrid = theirGrid ?: p.grid
                if (step == Step.ROGER) {
                    // 4a) 我已经回过 R（对方没收到）→ 直接 RR73 收尾，打破死循环
                    reportReceived = reportReceived ?: p.report
                    reportSent = lastSentReport ?: reportSent ?: reportFromSnr(m.snr)
                    step = Step.RR73
                } else {
                    // 4b) 对方是主叫（senior），我转为应答方 → 回我自己实测的 R 报告
                    role = QsoRole.RESPONDER
                    reportReceived = p.report
                    reportSent = lastSentReport ?: reportSent ?: reportFromSnr(m.snr)
                    step = Step.ROGER
                }
                render()
                if (step == Step.RR73) finish(utcMs, m.slotUtcMs)
                true
            }

            // 5) 对方用网格应答（我为主叫 / 双方同时应答）
            p.grid != null -> {
                if (step == Step.NONE || step == Step.CQ || step == Step.GRID) {
                    theirGrid = p.grid
                    role = QsoRole.CALLER
                    reportSent = reportFromSnr(m.snr)
                    step = Step.REPORT
                    render()
                    true
                } else {
                    // 已发过报告及以后：重复/滞后的网格忽略（保持当前 txText）
                    false
                }
            }

            else -> false
        }
    }

    /** 依据 [step] 渲染当前应发报文到 [txText]。 */
    private fun render() {
        val them = theirCall
        txText = when (step) {
            Step.NONE -> null
            Step.CQ -> listOf("CQ", cqModifier, myCall, myGrid)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            Step.GRID ->
                if (them == null) null
                else listOf(them, myCall, myGrid).filter { it.isNotEmpty() }.joinToString(" ")
            Step.REPORT ->
                if (them == null || reportSent == null) null
                else "$them $myCall ${MessageParser.formatReport(reportSent!!)}"
            Step.ROGER ->
                if (them == null || reportSent == null) null
                else "$them $myCall R${MessageParser.formatReport(reportSent!!)}"
            Step.RR73 -> if (them == null) null else "$them $myCall RR73"
            Step.SEVENTY3 -> if (them == null) null else "$them $myCall 73"
        }
    }

    /**
     * 依 [step] 同步对外状态。
     *
     * 注意：[QsoState.FAILED] 已不再由「重试耗尽」产生（`retryLimit` 体系退役）；保留该枚举值
     * 仅为兼容 UI，异常/中止一律走 [stop]（回到 IDLE）。
     */
    private fun syncState() {
        state = when {
            step == Step.RR73 || step == Step.SEVENTY3 -> QsoState.DONE
            step == Step.REPORT -> QsoState.WAIT_REPORT
            step == Step.ROGER -> QsoState.WAIT_RR73
            step == Step.CQ || step == Step.GRID -> QsoState.WAIT_REPLY
            role != QsoRole.NONE -> QsoState.WAIT_REPLY
            else -> QsoState.IDLE
        }
    }

    /** 写入通联记录（幂等；同一段只记一条）。 */
    private fun finish(utcMs: Long, slotUtcMs: Long) {
        if (logEntry != null) return
        val them = theirCall ?: return
        val endMs = if (utcMs > 0) utcMs else slotUtcMs
        logEntry = QsoLogEntry(
            theirCall = them,
            theirGrid = theirGrid,
            reportSent = reportSent,
            reportReceived = reportReceived,
            utcMs = endMs,
            // 起始时间未知时回退为完成时间（ADIF 的 TIME_ON 与 TIME_OFF 相同）
            startUtcMs = if (startedUtcMs > 0) startedUtcMs else endMs,
        )
        syncState()
    }

    private fun reset() {
        role = QsoRole.NONE
        state = QsoState.IDLE
        step = Step.NONE
        theirCall = null
        theirGrid = null
        reportSent = null
        reportReceived = null
        lastSentReport = null
        txText = null
        startedUtcMs = 0L
        noReplyCount = 0
        lastAdvanced = false
        lastSentText = null
        logEntry = null
        awaitingResponders = false
    }

    /** 用解码 SNR 作为要发送的信号报告（clamp 到 -24..+30 dB）。 */
    private fun reportFromSnr(snr: Int): Int = snr.coerceIn(-24, 30)
}
