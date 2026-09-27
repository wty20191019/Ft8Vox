package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult

/**
 * 发射抽屉里的报文类型（docs/UI.md §2.3）。
 *
 * 即 FT8CN 的**六步指令序列**（见 `docs/QSO.md` §2.1）：1=网格 / 2=报告 / 3=R报告 /
 * 4=RR73 / 5=73 / 6=CQ。[order] 与 `QsoEngine` 的 `Step.order` 一致（[CUSTOM] 为 0）。
 */
enum class TxMessageKind(val label: String, val order: Int) {
    GRID("网格", 1),
    REPORT("报告", 2),
    ROGER("R报告", 3),
    RR73("RR73", 4),
    SEVENTY_THREE("73", 5),
    CQ("CQ", 6),
    CUSTOM("自定义", 0),
}

/**
 * 报文构造、类型识别与发射调度的**纯逻辑**（无 Android 依赖，可 JVM 单测）。
 */
object TxCompose {

    /** 自定义文本上限（FT8 报文 75 bit，UI 按字符数提示）。 */
    const val MAX_TEXT_CHARS = 75

    /**
     * 按类型构造报文；需要目标而目标为空时返回 null。
     *
     * [cqPrefix] 只对 [TxMessageKind.CQ] 生效：CQ 前缀（如 `DX`、`TEST`），**空串＝普通 CQ**。
     */
    fun compose(
        kind: TxMessageKind,
        target: String?,
        myCall: String,
        myGrid: String,
        report: Int = 0,
        cqPrefix: String = "",
    ): String? {
        val me = myCall.trim().uppercase()
        val grid = myGrid.trim().uppercase()
        val them = target?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        return when (kind) {
            TxMessageKind.CQ -> join("CQ", cqPrefix.trim().uppercase(), me, grid)
            TxMessageKind.GRID -> them?.let { join(it, me, grid) }
            TxMessageKind.REPORT -> them?.let { join(it, me, MessageParser.formatReport(report)) }
            TxMessageKind.ROGER -> them?.let { join(it, me, "R${MessageParser.formatReport(report)}") }
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
            p.isRoger -> TxMessageKind.ROGER
            p.report != null -> TxMessageKind.REPORT
            p.grid != null -> TxMessageKind.GRID
            p.isFreeText -> TxMessageKind.CUSTOM
            p.from != null && p.addressedTo(myCall) -> TxMessageKind.GRID
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

    private fun join(vararg parts: String): String =
        parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * CQ 前缀默认值（4×2 = 8 个可编辑格子，抽屉里可改）。
 *
 * 前缀插在 `CQ` 与我方呼号之间（如 `CQ DX K1ABC FN42`）；**空串＝普通 CQ**（`CQ K1ABC FN42`）。
 */
val DEFAULT_CQ_PREFIXES: List<String> = listOf(
    "",
    "DX",
    "ASIA",
    "EU",
    "NA",
    "JA",
    "TEST",
    "POTA",
)

/**
 * 发射调度（docs/UI.md §2.3 第 6 条）：
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
