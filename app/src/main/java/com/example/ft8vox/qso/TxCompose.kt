package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult

/** 发射抽屉里的报文类型（new_ui.md §3.4）。 */
enum class TxMessageKind(val label: String) {
    CQ("CQ"),
    REPLY("回复"),
    EXCHANGE("交换"),
    RR73("RR73"),
    SEVENTY_THREE("73"),
    CUSTOM("自定义"),
}

/**
 * 报文构造、类型识别、宏展开与队列的**纯逻辑**（无 Android 依赖，可 JVM 单测）。
 *
 * 占位符：`{call}` 目标呼号、`{mycall}` 我的呼号、`{mygrid}` 我的网格、`{report}` 信号报告。
 */
object TxCompose {

    /** 自定义文本上限（FT8 报文 75 bit，UI 按字符数提示）。 */
    const val MAX_TEXT_CHARS = 75

    /** 按类型构造报文；需要目标而目标为空时返回 null。 */
    fun compose(
        kind: TxMessageKind,
        target: String?,
        myCall: String,
        myGrid: String,
        report: Int = 0,
    ): String? {
        val me = myCall.trim().uppercase()
        val grid = myGrid.trim().uppercase()
        val them = target?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        return when (kind) {
            TxMessageKind.CQ -> join("CQ", me, grid)
            TxMessageKind.REPLY -> them?.let { join(it, me, grid) }
            TxMessageKind.EXCHANGE ->
                them?.let { join(it, me, MessageParser.formatReport(report)) }
            TxMessageKind.RR73 -> them?.let { join(it, me, "RR73") }
            TxMessageKind.SEVENTY_THREE -> them?.let { join(it, me, "73") }
            TxMessageKind.CUSTOM -> null
        }
    }

    /** 识别一段报文的类型（用于收起态的「当前消息类型」）。 */
    fun kindOf(text: String?, myCall: String = ""): TxMessageKind? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val p = MessageParser.parse(t)
        return when {
            p.isCq -> TxMessageKind.CQ
            p.isRr73 -> TxMessageKind.RR73
            p.is73 -> TxMessageKind.SEVENTY_THREE
            p.report != null || p.isRoger -> TxMessageKind.EXCHANGE
            p.grid != null -> TxMessageKind.REPLY
            p.isFreeText -> TxMessageKind.CUSTOM
            p.from != null && p.addressedTo(myCall) -> TxMessageKind.REPLY
            else -> TxMessageKind.CUSTOM
        }
    }

    /** 从最近解码中取某台站的信号报告（clamp −24…+30）；无记录返回 0。 */
    fun reportFor(messages: List<DecodeResult>, call: String?): Int {
        val target = call?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return 0
        val m = messages.firstOrNull {
            MessageParser.parse(it.text).from?.equals(target, ignoreCase = true) == true
        } ?: return 0
        return m.snr.coerceIn(-24, 30)
    }

    /** 展开宏模板。 */
    fun expandMacro(
        template: String,
        target: String?,
        myCall: String,
        myGrid: String,
        report: Int = 0,
    ): String {
        val them = target?.trim()?.uppercase().orEmpty()
        return template
            .replace("{call}", them)
            .replace("{mycall}", myCall.trim().uppercase())
            .replace("{mygrid}", myGrid.trim().uppercase())
            .replace("{report}", MessageParser.formatReport(report))
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private fun join(vararg parts: String): String =
        parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}

/** 宏模板默认值（4×2 = 8 个，可在抽屉里编辑）。 */
val DEFAULT_MACROS: List<String> = listOf(
    "CQ {mycall} {mygrid}",
    "CQ DX {mycall} {mygrid}",
    "{call} {mycall} {mygrid}",
    "{call} {mycall} {report}",
    "{call} {mycall} R{report}",
    "{call} {mycall} RR73",
    "{call} {mycall} 73",
    "CQ TEST {mycall} {mygrid}",
)

/**
 * 发送队列的有序操作（纯 Kotlin）。
 *
 * 队列只保存报文原文，展示标签由 [TxCompose] 派生。
 */
object TxQueue {

    /** 队列上限，避免无节制堆积。 */
    const val MAX = 20

    fun enqueue(list: List<String>, text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return list
        if (list.size >= MAX) return list
        return list + t
    }

    fun removeAt(list: List<String>, index: Int): List<String> =
        if (index in list.indices) list.filterIndexed { i, _ -> i != index } else list

    /** 把 [from] 位置的报文移动到 [to]（越界返回原列表）。 */
    fun move(list: List<String>, from: Int, to: Int): List<String> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList()
        val item = m.removeAt(from)
        m.add(to, item)
        return m
    }

    /** 队列胶囊标签，形如 `1:JA1ABC/回复`。 */
    fun label(index: Int, text: String, myCall: String = ""): String {
        val p = MessageParser.parse(text)
        val target = p.to ?: p.from
        val kind = TxCompose.kindOf(text, myCall)?.label ?: "自定义"
        return "${index + 1}:${target ?: "-"}/$kind"
    }
}

/**
 * 发射调度（new_ui.md §3.4 第 6 条）：
 * 「本周期剩余时间够播完这一条报文就立即发，否则排下一周期」。
 *
 * 判据是**报文波形 + 前导必须能在本时隙内播完**：FT8 报文 12.64 s / 时隙 15 s、
 * FT4 报文 4.48 s / 时隙 7.5 s，因此本时隙开头约 2 s 都还来得及就地发射 ——
 * 不必白等一个周期（解码结果本来就是在时隙结束后几百毫秒才到手）。
 */
object TxScheduler {

    /** 报文时长未知时的兜底最小剩余时间（ms）。 */
    const val MIN_SEND_NOW_MS = 2500L

    /**
     * 「立即发」所需的最小剩余时间（ms）＝ 报文时长 + 前导，且不小于 [MIN_SEND_NOW_MS]。
     *
     * @param messageMs 报文波形时长（ms，见 `Protocol.messageMs`）
     * @param preambleMs 发射前导总时长（ms，见 `AppSettings.txPreambleMs`）
     */
    fun minSendNowMs(messageMs: Int, preambleMs: Int = 0): Long =
        maxOf(MIN_SEND_NOW_MS, messageMs.toLong() + preambleMs.coerceAtLeast(0).toLong())

    /**
     * 当前是否可立即发射。
     *
     * @param currentParity 当前时隙奇偶（0=偶，1=奇）
     * @param txParity 我方发射周期
     * @param msToNextSlot 距离下一个时隙起点的毫秒数（≈本周期剩余时间）
     * @param minNeededMs 所需最小剩余时间；正常调用应传 [minSendNowMs]（报文时长 + 前导），
     *   默认值 [MIN_SEND_NOW_MS] 仅作报文时长未知时的兜底
     */
    fun canSendNow(
        currentParity: Int,
        txParity: Int,
        msToNextSlot: Long,
        minNeededMs: Long = MIN_SEND_NOW_MS,
    ): Boolean = currentParity == txParity && msToNextSlot >= minNeededMs
}
