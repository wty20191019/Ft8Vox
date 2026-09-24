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
    val maxRetries: Int = 6,
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
                if (role == QsoRole.CALLER) "呼叫 CQ（第 ${retries + 1} 次）"
                else "等待 $theirCall 回复"
            QsoState.WAIT_REPORT -> "已发报告 ${reportSent?.let { MessageParser.formatReport(it) } ?: ""}，等待 $theirCall 的 R 报告"
            QsoState.WAIT_RR73 -> "等待 $theirCall 的 RR73"
        }
}

/**
 * FT8/FT4 的 QSO 自动序列状态机（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 支持两种角色：
 * - **CALLER**：`CQ <me> <grid>` → 收到应答后发信号报告 → 收到 `R<报告>` 后发 `RR73` → 完成。
 * - **RESPONDER**：应答他人 CQ → 收到报告后发 `R<报告>` → 收到 `RR73`/`73` 后发 `73` → 完成。
 *
 * 驱动方式：每个接收时隙结束后把解码结果交给 [onDecoded]；
 * 若该时隙没有带来状态推进，会自动累计重试次数，超过 [maxRetries] 则放弃。
 */
class QsoEngine(private var maxRetries: Int = 6) {

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

    /** 是否已配置好呼号，可以开始 QSO。 */
    val canOperate: Boolean get() = myCall.isNotEmpty()

    /** 更新台站信息与最大重试次数（来自设置）。 */
    fun configure(myCall: String, myGrid: String, maxRetries: Int = this.maxRetries) {
        this.myCall = myCall.trim().uppercase()
        this.myGrid = myGrid.trim().uppercase()
        this.maxRetries = maxRetries.coerceIn(1, 50)
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
        txText = listOf("CQ", myCall, myGrid).filter { it.isNotEmpty() }.joinToString(" ")
        return progress()
    }

    /** 应答指定 CQ。 */
    fun answer(call: String, grid: String?): QsoProgress {
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
        txText = listOf(their, myCall, myGrid).filter { it.isNotEmpty() }.joinToString(" ")
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
            if (retries > maxRetries) {
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
