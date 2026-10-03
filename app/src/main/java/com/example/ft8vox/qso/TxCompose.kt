package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult

/**
 * 发射抽屉里的报文类型（docs/Ft8Vox.md）。
 *
 * 即 FT8CN 的**六步指令序列**（见 `docs/Ft8Vox.md`）：1=网格 / 2=报告 / 3=R报告 /
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

    /**
     * 发射区**六个报文槽**的文本（3 列 × 2 行、列优先 `1 3 5` / `2 4 6`；下标 `0..5` 即序号 `1..6`）。
     *
     * **必须与 [QsoEngine] 的 `txText` 逐字一致**：发射区的红/绿点靠**文本相等**匹配
     * （`com.example.ft8vox.ui.slotLed`），差一个字符就不点灯 —— 真机现象是「在发 `R-07`，
     * 但 `3 R报告` 那格不亮红框红点」。
     *
     * 序号 3 的 `R<报告>` 用**我方实测的 [reportSent]**（与序号 2 **同一个值**，照 FT8CN
     * `toCallsign.snr`），**不是**对方给我的报告。
     */
    fun slots(
        target: String?,
        myCall: String,
        myGrid: String,
        reportSent: Int,
        cqPrefix: String = "",
    ): List<Pair<TxMessageKind, String?>> = listOf(
        TxMessageKind.GRID to compose(TxMessageKind.GRID, target, myCall, myGrid),
        TxMessageKind.REPORT to compose(TxMessageKind.REPORT, target, myCall, myGrid, reportSent),
        TxMessageKind.ROGER to compose(TxMessageKind.ROGER, target, myCall, myGrid, reportSent),
        TxMessageKind.RR73 to compose(TxMessageKind.RR73, target, myCall, myGrid),
        TxMessageKind.SEVENTY_THREE to compose(TxMessageKind.SEVENTY_THREE, target, myCall, myGrid),
        TxMessageKind.CQ to compose(TxMessageKind.CQ, null, myCall, myGrid, cqPrefix = cqPrefix),
    )

    /**
     * 发射区该以**谁**为目标呼号（六槽组包用）。
     *
     * **引擎有进行中的 QSO / 有待发报文时，以引擎真正在通联的对手为准** —— 否则用户先前
     * 点选过的目标（UI 里只赋值、从不清空、还是 `rememberSaveable`）会一直优先，六槽按旧呼号
     * 组包，与「正在发送」对不上（真机现象：自动程序已与 B 通联，槽内却还写着 A）。
     * 引擎空闲时才用点选目标，方便为还没开始的 QSO 预先备好报文。
     *
     * @param engineCall 引擎当前对手（`QsoProgress.theirCall`）
     * @param engineBusy 引擎是否在进行中或有待发报文（`active || txText != null`）
     * @param picked 用户点选的目标（解码行左滑 / 跟踪列表 / 详情「呼叫」）
     */
    fun targetFor(engineCall: String?, engineBusy: Boolean, picked: String?): String? {
        val engine = engineCall?.takeIf { engineBusy && it.isNotBlank() }
        return engine ?: picked?.trim()?.takeIf { it.isNotBlank() } ?: engineCall?.takeIf { it.isNotBlank() }
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
 * CQ 前缀格子数（`AppSettings.cqPrefixes` 恒为这个长度的可编辑格子，见 [DEFAULT_CQ_PREFIXES]）。
 */
const val CQ_PREFIX_SLOTS = 8

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
 * 发射调度（docs/Ft8Vox.md）：
 * 「本周期剩余时间够播完这一条报文就立即发，否则排下一周期」。
 *
 * 判据是**报文波形 + 前导必须能在本时隙内播完**：FT8 报文 12.64 s / 时隙 15 s，
 * 因此本时隙开头约 2 s 都还来得及就地发射 ——
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

    /**
     * 报文波形自带的前导静音（ms）。
     *
     * 见 `jni_bridge.c` 的 `lead = 0.5 s`：按 WSJT-X 约定，波形在时隙起点后 0.5 s 才开始
     * 发声，末尾再留白填满整个时隙。算「还来不来得及播完」时**必须**把它算进去。
     */
    const val WAVE_LEAD_MS = 500L

    /**
     * 「发射途中换目标、就地重发」的额外余量（ms）。
     *
     * 重启要作废正在写的那一段（native 侧最多再响一块，约 85 ms）、重新编码、重开写入，
     * 所以判据要比「刚好播得完」再宽一点，否则容易拖到下一时隙边界。
     */
    const val RETARGET_MARGIN_MS = 300L

    /**
     * **发射途中换目标**：本时隙剩余时间是否还够完整播完新报文（就地重发判据）。
     *
     * 判据 ＝ 前导（PTT 静音 + 前导音）+ 波形自带保护间隔 [WAVE_LEAD_MS] + 报文时长
     * + 重启余量 [marginMs] ≤ 本时隙剩余时间；且当前必须是**我方发射时隙**
     * （换目标时可能刚按对方时隙重锁了周期，那就只能在下一个我方时隙发）。
     *
     * FT8：报文 12.64 s / 时隙 15 s → 只在时隙开头约 1.5 s 内换目标才来得及。
     * 够就当场重发，不够则照旧等下一个我方周期。
     *
     * @param nowMs 当前 UTC 毫秒
     * @param slotMs 时隙长度（FT8 为 15000）
     * @param txParity 我方发射周期（0=偶，1=奇）
     * @param preambleMs 本次发射的完整前导（PTT 延迟 + 前导音，见 `AppSettings.txPreambleMs`）
     * @param messageMs 新报文的波形时长（ms，见 `Protocol.messageMs`）；≤0 视为未知 → 不可就地重发
     * @param slotOffsetMs 整个时隙的偏移（ms，与 `planTx` 同一口径）
     */
    fun canRetargetInSlot(
        nowMs: Long,
        slotMs: Long,
        txParity: Int,
        preambleMs: Long,
        messageMs: Int,
        slotOffsetMs: Long = 0L,
        marginMs: Long = RETARGET_MARGIN_MS,
    ): Boolean {
        if (slotMs <= 0L || messageMs <= 0) return false
        val shiftedNow = nowMs - slotOffsetMs
        val slotIdx = Math.floorDiv(shiftedNow, slotMs)
        if ((slotIdx % 2L).toInt() != txParity) return false
        val posInSlot = shiftedNow - slotIdx * slotMs
        val needed = preambleMs.coerceAtLeast(0L) + WAVE_LEAD_MS +
            messageMs.toLong() + marginMs.coerceAtLeast(0L)
        return posInSlot + needed <= slotMs
    }
}
