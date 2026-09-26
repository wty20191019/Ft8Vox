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
 * 支持两种角色（对应《QSO 自动系统设计文档》§1.3 / §1.4）：
 * - **CALLER**（主叫 QSO，[startCallerQso]）：发 `<对方> <我> <报告>` → 收到 `R<报告>` 后发 `RR73` → 完成。
 * - **RESPONDER**（应答 QSO，[startResponderQso]）：发 `<对方> <我> <网格>` → 收到报告后发 `R<报告>` → 收到 `RR73`/`73` 后发 `73` → 完成。
 * - **CQ**（[startCq]）：发 `CQ <我> <网格>`，之后由第 2 层自动程序收集回应者（见 [QsoProgress.awaitingResponders]）。
 *
 * 驱动方式：每个接收时隙结束后把解码结果交给 [onDecoded]；
 * 若该时隙没有带来状态推进，会自动累计重试次数，超过 [maxRetries] 则放弃
 * （[giveUp] 为 false 时永不放弃，用于「已选台回应无反应 N 次后放弃」关闭的情形）。
 */
class QsoEngine(private var maxRetries: Int = 3) {

    private var myCall: String = ""
    private var myGrid: String = ""

    private var role = QsoRole.NONE
    private var state = QsoState.IDLE
    private var theirCall: String? = null
    private var theirGrid: String? = null
    private var reportSent: Int? = null
    private var reportReceived: Int? = null
    private var txText: String? = null
    private var retries = 0
    private var logEntry: QsoLogEntry? = null

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
     * 若 QSO 已结束（DONE/FAILED），清空 txText，避免无限重发。
     */
    fun onTransmitted() {
        if (state == QsoState.DONE || state == QsoState.FAILED) {
            txText = null
        }
    }

    /** 开始呼叫 CQ。 */
    fun startCq(): QsoProgress {
        require(canOperate) { "未配置呼号" }
        role = QsoRole.CALLER
        state = QsoState.WAIT_REPLY
        theirCall = null
        theirGrid = null
        reportSent = null
        reportReceived = null
        retries = 0
        logEntry = null
        awaitingResponders = true
        txText = listOf("CQ", myCall, myGrid).filter { it.isNotEmpty() }.joinToString(" ")
        return progress()
    }

    /**
     * 第 1 层「应答 QSO」（文档 §1.4）：我应答对方的 CQ。
     *
     * 先发 `<对方> <我> <网格>`（让 CQ 台拿到我的网格），随后等报告 → 发 `R<报告>` →
     * 等 RR73 → 发 73 收尾。
     */
    fun startResponderQso(call: String, grid: String?): QsoProgress {
        require(canOperate) { "未配置呼号" }
        val their = call.trim().uppercase()
        if (their.isEmpty() || their == myCall) return progress()
        role = QsoRole.RESPONDER
        state = QsoState.WAIT_REPLY
        theirCall = their
        theirGrid = grid?.trim()?.uppercase()?.ifEmpty { null }
        reportSent = null
        reportReceived = null
        retries = 0
        logEntry = null
        awaitingResponders = false
        txText = listOf(their, myCall, myGrid).filter { it.isNotEmpty() }.joinToString(" ")
        return progress()
    }

    /**
     * 第 1 层「主叫 QSO」（文档 §1.3）：我方发过 CQ，[call] 是回应者。
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
        role = QsoRole.CALLER
        state = QsoState.WAIT_REPORT
        theirCall = their
        theirGrid = grid?.trim()?.uppercase()?.ifEmpty { null }
        reportSent = reportFromSnr(snr)
        reportReceived = null
        retries = 0
        logEntry = null
        awaitingResponders = false
        txText = "$their $myCall ${MessageParser.formatReport(reportSent!!)}"
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
        role = QsoRole.RESPONDER
        state = QsoState.WAIT_RR73
        theirCall = their
        theirGrid = null
        reportReceived = theirReport
        reportSent = reportFromSnr(snr)
        retries = 0
        logEntry = null
        awaitingResponders = false
        txText = "$their $myCall R${MessageParser.formatReport(reportSent!!)}"
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
        role = QsoRole.RESPONDER
        state = QsoState.DONE
        theirCall = their
        theirGrid = null
        reportReceived = theirReport
        reportSent = reportFromSnr(snr)
        retries = 0
        awaitingResponders = false
        txText = "$their $myCall RR73"
        logEntry = QsoLogEntry(
            theirCall = their,
            theirGrid = null,
            reportSent = reportSent,
            reportReceived = reportReceived,
            utcMs = if (utcMs > 0) utcMs else 0L,
        )
        return progress()
    }

    /** 中止当前 QSO。 */
    fun stop(): QsoProgress {
        role = QsoRole.NONE
        state = QsoState.IDLE
        theirCall = null
        theirGrid = null
        reportSent = null
        reportReceived = null
        txText = null
        retries = 0
        logEntry = null
        awaitingResponders = false
        return progress()
    }

    /**
     * 处理一个接收时隙的解码结果，推进状态机。
     *
     * 每次调用只消费一条有效报文；未产生推进时累计重试。
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
        } else {
            retries++
            // giveUp=false（关闭「重发机制」）时一直重发，不主动放弃
            if (giveUp && retries > maxRetries) {
                state = QsoState.FAILED
                txText = null
            }
        }
        return progress()
    }

    /** 尝试用一条报文推进状态；返回是否发生推进。 */
    private fun applyMessage(p: ParsedMessage, m: DecodeResult, utcMs: Long): Boolean {
        val them = theirCall ?: p.from ?: return false
        return when (state) {
            QsoState.WAIT_REPLY -> when (role) {
                QsoRole.CALLER -> {
                    // 收到应答：<me> <them> [grid|report]
                    if (p.grid == null && p.report == null && !p.isRoger) return false
                    theirCall = p.from
                    if (p.grid != null) theirGrid = p.grid
                    reportSent = reportFromSnr(m.snr)
                    txText = "$them $myCall ${MessageParser.formatReport(reportSent!!)}"
                    state = QsoState.WAIT_REPORT
                    true
                }
                QsoRole.RESPONDER -> {
                    // 等待对方给我的信号报告：<me> <them> <report>
                    val rep = p.report ?: return false
                    reportReceived = rep
                    reportSent = reportFromSnr(m.snr)
                    txText = "$them $myCall R${MessageParser.formatReport(reportSent!!)}"
                    state = QsoState.WAIT_RR73
                    true
                }
                else -> false
            }

            QsoState.WAIT_REPORT -> {
                when {
                    p.isRr73 || p.is73 -> {
                        reportReceived = reportReceived ?: p.report
                        // 对方直接跳到 RR73/73：回一条 73 收尾（不要重发上一条报告）
                        txText = "$them $myCall 73"
                        complete(m, utcMs)
                        true
                    }
                    p.isRoger -> {
                        reportReceived = p.report
                        txText = "$them $myCall RR73"
                        complete(m, utcMs)
                        true
                    }
                    p.report != null -> {
                        // 对方直接发了报告（未加 R）：我回 R<报告>，等待 RR73
                        reportReceived = p.report
                        txText = "$them $myCall R${MessageParser.formatReport(p.report)}"
                        state = QsoState.WAIT_RR73
                        true
                    }
                    else -> false
                }
            }

            QsoState.WAIT_RR73 -> {
                if (p.isRr73 || p.is73) {
                    reportReceived = reportReceived ?: p.report
                    txText = "$them $myCall 73"
                    complete(m, utcMs)
                    true
                } else if (p.report != null && reportReceived == null) {
                    reportReceived = p.report
                    false
                } else {
                    false
                }
            }

            else -> false
        }
    }

    private fun complete(m: DecodeResult, utcMs: Long) {
        state = QsoState.DONE
        logEntry = QsoLogEntry(
            theirCall = theirCall ?: return,
            theirGrid = theirGrid,
            reportSent = reportSent,
            reportReceived = reportReceived,
            utcMs = if (utcMs > 0) utcMs else m.slotUtcMs,
        )
    }

    /** 用解码 SNR 作为要发送的信号报告（clamp 到 -24..+30 dB）。 */
    private fun reportFromSnr(snr: Int): Int = snr.coerceIn(-24, 30)
}
