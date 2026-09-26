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
    val utcMs: Long,
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
    val retries: Int = 0,
    val maxRetries: Int = 3,
    /**
     * 是否处于「已发 CQ、等回应者」阶段（只由 [QsoEngine.startCq] 置位）。
     *
     * 该阶段的解码由第 2 层自动程序调度（收集、排序回应者）处理，而不是由状态机
     * 直接认第一个回应者；`SessionViewModel` 据此分流。
     */
    val awaitingResponders: Boolean = false,
) {
    /** 是否处于进行中的 QSO。 */
    val active: Boolean
        get() = state != QsoState.IDLE && state != QsoState.DONE && state != QsoState.FAILED

    val description: String
        get() = when (state) {
            QsoState.IDLE -> "空闲"
            QsoState.DONE -> "已完成"
            QsoState.FAILED -> "已放弃（对方无响应）"
            QsoState.WAIT_REPLY ->
                if (awaitingResponders) "CQ 已发出，等待回应"
                else if (role == QsoRole.CALLER) "呼叫 CQ（第 ${retries + 1} 次）"
                else "等待 $theirCall 回复"
            QsoState.WAIT_REPORT -> "已发报告 ${reportSent?.let { MessageParser.formatReport(it) } ?: ""}，等待 $theirCall 的 R 报告"
            QsoState.WAIT_RR73 -> "等待 $theirCall 的 RR73"
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
 * ### 重试
 *
 * 重试次数按**实际重发次数**累计（`onTransmitted` 后收到的下一个解码批次若没带来
 * 推进才 +1），而不是每过一个空时隙就 +1；否则两端周期相反时会在真正放弃前提前
 * 触发「放弃 → 第 2 层重启」的抖动。
 *
 * 驱动方式：每个接收时隙结束后把解码结果交给 [onDecoded]。
 */
class QsoEngine(private var maxRetries: Int = 3) {

    /** 我在本段 QSO 里**最后已发出**的那一步（报文的语义阶段）。 */
    private enum class Step { NONE, CQ, GRID, REPORT, ROGER, RR73, SEVENTY3 }

    private var myCall: String = ""
    private var myGrid: String = ""

    private var role = QsoRole.NONE
    private var state = QsoState.IDLE
    private var step = Step.NONE
    private var theirCall: String? = null
    private var theirGrid: String? = null
    private var reportSent: Int? = null
    private var reportReceived: Int? = null
    private var txText: String? = null
    private var retries = 0
    private var logEntry: QsoLogEntry? = null

    /** 通过重试耗尽而放弃（用于 [QsoState.FAILED]）。 */
    private var failed = false

    /** 上一次发射后是否还没收到任何有价值的回复（决定是否累计一次重试）。 */
    private var awaitingReplySinceTx = false

    /** 超过 [maxRetries] 是否放弃（false＝一直重发，仅用于关闭「重发机制」时）。 */
    private var giveUp = true

    /** 是否处于「已发 CQ、等回应者」阶段（见 [QsoProgress.awaitingResponders]）。 */
    private var awaitingResponders = false

    /** 是否已配置好呼号，可以开始 QSO。 */
    val canOperate: Boolean get() = myCall.isNotEmpty()

    /** 更新台站信息与重发机制（来自设置）。 */
    fun configure(
        myCall: String,
        myGrid: String,
        maxRetries: Int = this.maxRetries,
        giveUp: Boolean = this.giveUp,
    ) {
        this.myCall = myCall.trim().uppercase()
        this.myGrid = myGrid.trim().uppercase()
        this.maxRetries = maxRetries.coerceIn(1, 50)
        this.giveUp = giveUp
    }

    fun progress(): QsoProgress = QsoProgress(
        role = role,
        state = state,
        theirCall = theirCall,
        theirGrid = theirGrid,
        reportSent = reportSent,
        reportReceived = reportReceived,
        txText = txText,
        retries = retries,
        maxRetries = maxRetries,
        awaitingResponders = awaitingResponders,
    )

    /** 取走刚完成的通联记录（一次性，取走后清空）。 */
    fun consumeCompleted(): QsoLogEntry? {
        val e = logEntry
        logEntry = null
        return e
    }

    /**
     * 通知状态机：本轮 [QsoProgress.txText] 已实际发射完毕。
     *
     * 若 QSO 已结束（DONE/FAILED），清空 txText，避免无限重发；否则标记「本轮已发，
     * 等回复」，供 [onDecoded] 判断是否累计一次重试。
     */
    fun onTransmitted() {
        if (state == QsoState.DONE || state == QsoState.FAILED) {
            txText = null
        }
        awaitingReplySinceTx = true
    }

    /** 开始呼叫 CQ。 */
    fun startCq(): QsoProgress {
        require(canOperate) { "未配置呼号" }
        reset()
        role = QsoRole.CALLER
        awaitingResponders = true
        step = Step.CQ
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
    fun startResponderQso(call: String, grid: String?): QsoProgress {
        require(canOperate) { "未配置呼号" }
        val their = call.trim().uppercase()
        if (their.isEmpty() || their == myCall) return progress()
        reset()
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
    fun startCallerQso(call: String, grid: String?, snr: Int): QsoProgress {
        require(canOperate) { "未配置呼号" }
        val their = call.trim().uppercase()
        if (their.isEmpty() || their == myCall) return progress()
        reset()
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
    fun respondToReport(call: String, theirReport: Int, snr: Int): QsoProgress {
        require(canOperate) { "未配置呼号" }
        val their = call.trim().uppercase()
        if (their.isEmpty() || their == myCall) return progress()
        reset()
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
     * 每次调用只消费一条有效报文；未产生推进时累计重试（仅在上一轮**确实发射过**、
     * 即等过一次回复后才 +1）。
     */
    fun onDecoded(messages: List<DecodeResult>, utcMs: Long = 0L): QsoProgress {
        if (!canOperate) return progress()
        if (!progress().active) return progress()
        // 「已发 CQ、等回应者」阶段由第 2 层自动程序收集/排序回应者，状态机不自行认人
        if (awaitingResponders) return progress()

        var advanced = false
        for (m in messages) {
            val p = MessageParser.parse(m.text)
            val from = p.from ?: continue
            if (from.equals(myCall, ignoreCase = true)) continue // 忽略自己
            if (!p.addressedTo(myCall)) continue // 只处理发给我的
            if (theirCall != null && !from.equals(theirCall, ignoreCase = true)) continue // 只认当前对手
            if (applyMessage(p, m, utcMs)) {
                advanced = true
                break
            }
        }

        if (advanced) {
            retries = 0
            awaitingReplySinceTx = false
            // 非终态转移只改了 step，需要把对外 state 同步过来（终态分支里 finish 已同步，幂等）
            syncState()
        } else if (awaitingReplySinceTx) {
            // 上一轮发了报文、本批解码没能推进 → 计一次无效重发
            awaitingReplySinceTx = false
            retries++
            // giveUp=false（关闭「重发机制」）时一直重发，不主动放弃
            if (giveUp && retries > maxRetries) {
                failed = true
                step = Step.NONE
                txText = null
                theirCall = null
                theirGrid = null
                syncState()
            }
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
                reportSent = reportSent ?: reportFromSnr(m.snr)
                step = Step.SEVENTY3
                txText = null
                finish(utcMs, m.slotUtcMs)
                true
            }

            // 2) 对方 RR73：回 73 并完成
            p.isRr73 -> {
                reportSent = reportSent ?: reportFromSnr(m.snr)
                step = Step.SEVENTY3
                render()
                finish(utcMs, m.slotUtcMs)
                true
            }

            // 3) 对方 R 报告：已确认收到我的报告 → 回 RR73 并完成
            p.isRoger -> {
                reportReceived = reportReceived ?: p.report
                reportSent = reportSent ?: reportFromSnr(m.snr)
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
                    reportSent = reportSent ?: reportFromSnr(m.snr)
                    step = Step.RR73
                } else {
                    // 4b) 对方是主叫（senior），我转为应答方 → 回我自己实测的 R 报告
                    role = QsoRole.RESPONDER
                    reportReceived = p.report
                    reportSent = reportSent ?: reportFromSnr(m.snr)
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
            Step.CQ -> listOf("CQ", myCall, myGrid).filter { it.isNotEmpty() }.joinToString(" ")
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

    /** 依 [step] / [failed] 同步对外状态。 */
    private fun syncState() {
        state = when {
            failed -> QsoState.FAILED
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
        logEntry = QsoLogEntry(
            theirCall = them,
            theirGrid = theirGrid,
            reportSent = reportSent,
            reportReceived = reportReceived,
            utcMs = if (utcMs > 0) utcMs else slotUtcMs,
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
        txText = null
        retries = 0
        logEntry = null
        failed = false
        awaitingReplySinceTx = false
        awaitingResponders = false
    }

    /** 用解码 SNR 作为要发送的信号报告（clamp 到 -24..+30 dB）。 */
    private fun reportFromSnr(snr: Int): Int = snr.coerceIn(-24, 30)
}
