package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult

/** 本次 QSO 中我的角色（**只供 UI**；照 FT8CN，报文转移完全由序号驱动）。 */
enum class QsoRole { NONE, CALLER, RESPONDER }

/**
 * QSO 状态机的对外状态，直接采用论文（QEX 2020《The FT4 and FT8 Communication
 * Protocols》§7）的六个状态，并保留本机的 IDLE / FAILED 扩展：
 *
 * | 论文状态 | 本机 | 含义 |
 * | --- | --- | --- |
 * | Calling | [CALLING] | 发 CQ，等第一个回应 |
 * | Replying | [REPLYING] | 已发网格（Tx1），等对方报告 |
 * | Report | [REPORT] | 已发报告（Tx2），等对方 R 报告 |
 * | Roger Report | [ROGER_REPORT] | 已发 R 报告（Tx3），等 RR73/RRR |
 * | Rogers | [ROGERS] | 已发 RR73（Tx4），等对方 73 |
 * | Signoff | [SIGNOFF] | 已发 73（Tx5），QSO 完成 |
 */
enum class QsoState {
    IDLE,

    /** 论文 Calling：发 CQ（Tx6），等回应者。 */
    CALLING,

    /** 论文 Replying：已应答 CQ / 发出网格（Tx1），等对方报告。 */
    REPLYING,

    /** 论文 Report：已发信号报告（Tx2），等对方 R 报告（Tx3）。 */
    REPORT,

    /** 论文 Roger Report：已发 R 报告（Tx3），等对方的 RR73 / RRR。 */
    ROGER_REPORT,

    /** 论文 Rogers：已发 RR73（Tx4），等对方的 73。 */
    ROGERS,

    /** 论文 Signoff：已发 73（Tx5），QSO 完成。 */
    SIGNOFF,

    /** 本机扩展：按兜底判据放弃（对方无响应 / 转呼别人）。 */
    FAILED,
}

/**
 * **会话级阶段**——单一状态机对外暴露的显式状态（`docs/QsoStateMachine.md`）。
 *
 * 它取代了旧的第 1 层 `QsoState` + 第 2 层 `AutoScheduler` 的队列 / 监管 + 第 3 层
 * `SessionViewModel.pendingAutoFinish` 三处分散状态：会话此刻在做什么，只看这一个值。
 *
 * | 阶段 | 含义 | 对应旧实现 |
 * | --- | --- | --- |
 * | [IDLE] | 无进行中的 QSO、也没有收尾报文待发 | `!active && txText == null` |
 * | [CQ] | 我方主叫中（序号 6），等回应者由选台逻辑收集 | `awaitingResponders` |
 * | [QSO] | 一段定向 QSO 进行中（序号 1~5） | `active && !awaitingResponders` |
 * | [FINISHING] | QSO 已判定结束，但收尾报文（RR73/73）**还没真正发出** | `pendingAutoFinish` |
 */
enum class QsoPhase { IDLE, CQ, QSO, FINISHING }

/**
 * **我方下一条要发的报文序号**（FT8CN `functionOrder`）的显式枚举。
 *
 * 取代原先的 `order: Int` 魔法值：报文渲染（[QsoEngine] 的 `render`）、对外状态同步
 * （`syncState`）、角色推断（`roleOf`）现在**全部按它分支**，新增一步不会再「漏改一处 `when`」。
 * 序号数值仍由 [QsoProgress.order] 对外暴露（兼容 UI 与单测）。
 */
enum class QsoStep(val order: Int) {
    /** 无（空闲 / 已结束）。 */
    NONE(0),

    /** 1 网格：应答对方的 CQ。 */
    GRID(1),

    /** 2 报告：`<对方> <我> <±dd>`。 */
    REPORT(2),

    /** 3 R 报告：`<对方> <我> R<±dd>`。 */
    ROGER(3),

    /** 4 RR73：收尾（落库）。 */
    RR73(4),

    /** 5 73：收尾（落库）。 */
    FINAL_73(5),

    /** 6 CQ：主叫。 */
    CQ(6),
    ;

    companion object {
        /** 按 FT8CN 的序号数值取枚举；未知值回退 [NONE]。 */
        fun of(order: Int): QsoStep = entries.firstOrNull { it.order == order } ?: NONE

        /** 「我的序号 ＝ 对方序号 + 1」（唯一一条转移规则）。 */
        fun replyTo(theirOrder: Int): QsoStep = of(theirOrder + 1)
    }
}

/** 一次完成的通联记录（由 `SessionViewModel` 持久化到 ADIF / Room）。 */
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
    /**
     * 我下一条要发的报文序号（FT8CN `functionOrder`）：
     * 1 网格 / 2 报告 / 3 R 报告 / 4 RR73 / 5 73 / 6 CQ；0＝无。
     */
    val order: Int = 0,
    /**
     * 无回应计数（FT8CN 口径：按**解码批次**累计，收到有效回复即清零）。
     *
     * 照 FT8CN `FT8TransmitSignal.java:812`，**空批（一条解码都没有）不计数**。
     */
    val noReplyCount: Int = 0,
    /**
     * 是否处于「已发 CQ、等回应者」阶段（只由 [QsoEngine.startCq] 置位）。
     *
     * 照 FT8CN `functionOrder == 6` 的状态：该阶段的解码由选台逻辑
     * （收集、排序回应者）处理，状态机不自行认人，也**永不放弃主叫、不自增无回应计数**。
     */
    val awaitingResponders: Boolean = false,
    /**
     * 最近一次 [QsoEngine.onDecoded] 是否发生了推进（收到**当前对手**的有效回复）。
     *
     * 据此识别「当前目标本批沉默」→ 应答其他呼叫我方的定向台
     * （FT8CN `checkCQMeOrFollowCQMessage` 循环 2；见 `docs/Ft8Vox.md`）。
     */
    val advanced: Boolean = false,
    /**
     * 本段不是正常跑完，而是按 FT8CN 的兜底判据收尾的（「我发过 RR73 但对方消失」等）。
     *
     * 据此走「换台 / 回 CQ」（[QsoEngine.onTargetGaveUp]）而不是「正常结束」。
     */
    val gaveUp: Boolean = false,
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
        get() = state != QsoState.IDLE && state != QsoState.SIGNOFF && state != QsoState.FAILED

    /**
     * QSO 已判定结束（成功 / 作废），但收尾报文（RR73/73）**还没真正发出**。
     *
     * 即会话阶段的 [QsoPhase.FINISHING]：此时必须保持发射武装，且**不得启动新 QSO**——
     * 否则 `startCq` 会 `reset` 引擎，把这条收尾报文覆盖掉（真机现象：对方给我 RR73，我没回 73）。
     */
    val finishing: Boolean
        get() = (state == QsoState.SIGNOFF || state == QsoState.FAILED) && txText != null

    /** 本阶段还没轮到我方时隙发射时的提示后缀。 */
    private val pendingHint: String get() = "待发（等我的发射时隙）"

    val description: String
        get() = when (state) {
            QsoState.IDLE -> "空闲"
            QsoState.SIGNOFF -> "已完成"
            QsoState.FAILED -> "已放弃（对方无响应）"
            QsoState.CALLING ->
                when {
                    awaitingResponders && txSent -> "CQ 已发出，等待回应"
                    awaitingResponders -> "CQ $pendingHint"
                    txSent -> "已呼叫 $theirCall，等待回复"
                    else -> "呼叫 $theirCall $pendingHint"
                }
            QsoState.REPLYING ->
                if (txSent) "已应答 $theirCall，等待回复"
                else "应答 $theirCall $pendingHint"
            QsoState.REPORT -> {
                val r = reportSent?.let { MessageParser.formatReport(it) } ?: ""
                if (txSent) "已发报告 $r，等待 $theirCall 的 R 报告"
                else "报告 $r $pendingHint"
            }
            QsoState.ROGER_REPORT ->
                if (txSent) "已发 R 报告，等待 $theirCall 的 RR73"
                else "R 报告 $pendingHint"
            QsoState.ROGERS ->
                if (txSent) "已发 RR73（已落库），等待 $theirCall 的 73"
                else "RR73 $pendingHint"
        }
}

/**
 * **唯一的会话级 QSO 状态机**（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 它把原先散在三处的状态合而为一（`docs/QsoStateMachine.md`）：
 *
 * | 原位置 | 原职责 | 现在 |
 * | --- | --- | --- |
 * | `QsoEngine`（第 1 层） | 一段 QSO 的六步序号推进、落库、无回应判据 | 本类的「一段 QSO」[QsoPhase.QSO] 分支 |
 * | `AutoScheduler`（第 2 层） | 选台 / 定向队列 / 发射监管 / 换台 | 本类的会话方法 |
 * | `SessionViewModel.pendingAutoFinish`（第 3 层） | 「QSO 结束但收尾报文未发」的标志 | 显式阶段 [QsoPhase.FINISHING] |
 *
 * ### 驱动方式
 *
 * - 每个接收时隙结束：交给 [onDecoded]（进行中的一段 QSO）或 [onIdleBatch]（空闲 / 等回应者）；
 * - 我方一条报文真正播出：[onTransmitted]；
 * - 会话阶段由 [phase] 显式暴露，`SessionViewModel` 只按它分支，不再自行维护标志。
 *
 * ### 设计：照 FT8CN 的**报文序号**模型
 *
 * 本引擎是一场 QSO 的状态：**我下一条要发的报文序号** [QsoProgress.order]（FT8CN `functionOrder`）。
 * 转移规则只有一条（照 `FT8TransmitSignal.parseMessageToFunction` `:862`）：
 *
 * > 收到对方的报文 → 解析出它的序号（[FunctionOrder]）→ **我的序号 ＝ 它的序号 + 1**。
 *
 * 序号含义与渲染（FT8CN `getFunctionCommand` `:251`）：
 *
 * | 我方序号 | 我方报文 | 来源 |
 * | :--: | --- | --- |
 * | 1 | `<对方> <我> [网格]` | 应答对方的 CQ |
 * | 2 | `<对方> <我> <±dd>` | 收到对方的网格（序号 1）→ 发我实测的报告 |
 * | 3 | `<对方> <我> R<±dd>` | 收到对方的报告（序号 2）→ 确认 |
 * | 4 | `<对方> <我> RR73` | 收到对方的 R 报告（序号 3）→ 收尾（**落库**） |
 * | 5 | `<对方> <我> 73` | 收到对方的 RR73 / RRR（序号 4）→ 收尾 |
 * | 6 | `CQ [修饰符] <我> [网格]` | 主叫 |
 *
 * 收到对方的 **73**（序号 5）→ 通联完成（落库、不再发射）。
 *
 * ### 与 FT8CN 一致的几处关键口径
 *
 * - **报告值**：我发给对方的报告取「**首次测到**的强度」（FT8CN `toCallsign.snr`）——
 *   进入序号 2 / 3 时算一次，之后**重发不刷新**；序号 3 的 `R<报告>` 与序号 2 **同一个值**。
 * - **落库**：进入序号 4 / 5（发出 RR73 / 73）即落库，幂等（FT8CN `record.saved`）。
 * - **无回应**：按**解码批次**累计（空批不计），收到回复清零；是否换台由
 *   `noReplyLimit` 决定，但兜底判据在**本引擎**里（见下）。
 * - **完成判据**（照 FT8CN `:832-843`，5 路 OR）：
 *   1. 对方的报文序号 = 5（对方发 73）→ 完成；
 *   2. 我的序号 = 5 且对方沉默（本机结构上不可达：发出 73 后状态已是 [QsoState.SIGNOFF]）；
 *   3. **除 CQ 主叫（序号 6）外各阶段**且 `noReplyLimit > 0` 且 `noReplyCount >= noReplyLimit` → 作废换台；
 *   4. 我的序号 = 4 且**对方开始呼叫别人**（[targetCallingOthers]）→ 作废换台；
 *   5. **除 CQ 主叫外各阶段**且 `noReplyLimit == 0` 且 `noReplyCount >= 20` → 作废换台。
 *
 *   第 3~5 路命中时 [QsoProgress.gaveUp] 为真，由会话层换台 / 回 CQ。
 *   **与 FT8CN 的有意偏离**：FT8CN 第 3~5 路只在序号 4 生效、且第 3 路用 `× 2`；本机把
 *   第 3 / 5 路放宽到除 CQ 外各阶段、阈值取字面值，避免停在序号 1~3 的呼叫者永远重发。
 * - **序号 4 不是终态**：发出 RR73 后本段仍在跑（照 FT8CN 每周期重发 RR73），
 *   直到对方的 73 或上面第 3~5 路兜底。
 */
class QsoEngine {

    /** `noReplyLimit == 0`（未设或设 0）时，除 CQ 外各阶段的「硬上限」批次（照 FT8CN `:840` 的字面量 20）。 */
    private companion object {
        const val NO_REPLY_HARD_LIMIT = 20
    }

    // ---- 台站与设置 ----

    private var myCall: String = ""
    private var myGrid: String = ""
    /** CQ 前缀（`CQ <前缀> <我> <网格>`；空串＝普通 CQ），由 [startCq] 设置。 */
    private var cqModifier: String = ""
    /** 无回应次数上限（FT8CN `noReplyLimit`；`0`＝忽略）。完成判据要用，由 [configure] 下发。 */
    private var noReplyLimit = 0
    /** 自动程序策略（选台 / 监管阈值），由 [configure] 下发。 */
    private var settings = AutoProgramSettings()

    // ---- 一段 QSO 的状态（原第 1 层） ----

    private var role = QsoRole.NONE
    private var state = QsoState.IDLE
    /** 我方下一条要发的报文序号（显式状态，见 [QsoStep]）。 */
    private var step: QsoStep = QsoStep.NONE
    private var theirCall: String? = null
    private var theirGrid: String? = null
    /** 我发给对方的报告（FT8CN `toCallsign.snr`）：进入序号 2 / 3 时算一次，之后不刷新。 */
    private var reportSent: Int? = null
    /** 对方发给我的报告（FT8CN `receiveTargetReport`）。 */
    private var reportReceived: Int? = null
    private var txText: String? = null
    /** 本段 QSO 的起始时间（UTC 毫秒，FT8CN `startTime` 口径；0＝未知，落库时回退为完成时间）。 */
    private var startedUtcMs = 0L
    /** 无回应计数（FT8CN 口径，按解码批次累计，收到回复清零）。 */
    private var noReplyCount = 0
    /** 最近一次 [onDecoded] 是否推进（供会话层识别「目标本批沉默」，见 [QsoProgress.advanced]）。 */
    private var lastAdvanced = false
    /** **真正发出去**的那条报文文本（[onTransmitted] 记录，用于 [QsoProgress.txSent] 判定）。 */
    private var lastSentText: String? = null
    private var logEntry: QsoLogEntry? = null
    /** 本段是否**已经落库**（照 FT8CN `record.saved`；[finish] 幂等，一段只出一条）。 */
    private var logged = false
    /** 是否处于「已发 CQ、等回应者」阶段（见 [QsoProgress.awaitingResponders]）。 */
    private var awaitingResponders = false
    /** 本段是否按兜底判据收尾（见 [QsoProgress.gaveUp]）。 */
    private var gaveUp = false
    /** 最近一次带时间戳的调用（毫秒）；[finish] 拿不到显式时间时回退它。 */
    private var lastUtcMs = 0L

    // ---- 会话调度状态（原第 2 层 AutoScheduler） ----

    /** 定向呼叫队列（回应者 / 呼叫我方的台），逐个完成。 */
    private val queue = ArrayDeque<AutoTarget>()

    /** 发射监管计时基准（每次 [enable] / 人工操作 [resetSupervision] 重置）。 */
    private var supervisionSinceMs = 0L

    /** 发射监管已触发（停止后再不产出动作，直到 [enable] / [resetSupervision]）。 */
    private var protectedStop = false

    /** 是否已配置好呼号，可以开始 QSO。 */
    val canOperate: Boolean get() = myCall.isNotEmpty()

    /**
     * 会话当前阶段（**单一状态机的显式状态**，见 [QsoPhase]）。
     *
     * `SessionViewModel` 只按它分支，不再自行组合 `active` / `awaitingResponders` / `txText` 三个标志。
     */
    val phase: QsoPhase
        get() = when {
            awaitingResponders -> QsoPhase.CQ
            state == QsoState.SIGNOFF || state == QsoState.FAILED ->
                if (txText != null) QsoPhase.FINISHING else QsoPhase.IDLE
            state == QsoState.IDLE -> QsoPhase.IDLE
            else -> QsoPhase.QSO
        }

    /**
     * 更新台站信息与安全阀设置（来自设置页）。
     *
     * [noReplyLimit] 是 FT8CN 的「无回应限制」（**除 CQ 主叫外各阶段**生效；`0`＝用内置硬上限 20）。
     * [program] 是自动程序策略（选台开关 / 发射监管分钟数），供会话层选台与监管使用。
     */
    fun configure(
        myCall: String,
        myGrid: String,
        noReplyLimit: Int = 0,
        program: AutoProgramSettings = AutoProgramSettings(),
    ) {
        this.myCall = myCall.trim().uppercase()
        this.myGrid = myGrid.trim().uppercase()
        this.noReplyLimit = noReplyLimit.coerceAtLeast(0)
        this.settings = program
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
        gaveUp = gaveUp,
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
     * 提前量到点时就被排定，而上一时隙的解码要等到时隙末尾才处理完；于是 `qso.txText`
     * 已经推进成收尾的 `73`/`RR73`，**真正在播的却还是旧报文**（如 `R-07`）。此时若照旧
     * 在 DONE 下清空 `txText`，收尾报文就再也没有时隙可发 —— 真机现象是「对方给我 RR73
     * 后我没有回 73，反而开始发 CQ」。
     *
     * 所以只有「刚发出去的这一条就是当前应发报文」才允许清空；发的是旧报文时**保留**，
     * 由执行层在下一次我方时隙把它发出去（见 `SessionViewModel.txTick`）。
     *
     * 照 FT8CN `setCurrentFunctionOrder` `:550`：我方序号进到 4 / 5（RR73 / 73）时**落库**。
     */
    fun onTransmitted(sentText: String? = null, utcMs: Long = 0L) {
        markTime(utcMs)
        // 记录「真正发出去的是哪条」，供 QsoProgress.txSent / description 区分「待发」与「已发出」
        if (sentText != null) lastSentText = sentText
        if (state == QsoState.SIGNOFF || state == QsoState.FAILED) {
            if (sentText == null || txText == null || sentText == txText) txText = null
        }
        // 收尾报文真正发出即落库（发的是旧报文时不算：那条还没轮到我方的收尾序号）
        if (step == QsoStep.RR73 || step == QsoStep.FINAL_73) {
            finish(utcMs, 0L)
        }
    }

    /**
     * 开始呼叫 CQ（我方序号 6）。
     *
     * [utcMs] 为本段起始时间（UTC 毫秒，用于日志 `startTime`）；[cqPrefix] 为 CQ 前缀
     * （插在 `CQ` 与我方呼号之间，空串＝普通 CQ），**整段 CQ 阶段沿用**（每周期重发同一条）。
     */
    fun startCq(utcMs: Long = 0L, cqPrefix: String = ""): QsoProgress {
        markTime(utcMs)
        require(canOperate) { "未配置呼号" }
        reset()
        startedUtcMs = if (utcMs > 0) utcMs else 0L
        role = QsoRole.CALLER
        awaitingResponders = true
        cqModifier = cqPrefix.trim().uppercase()
        setOrder(QsoStep.CQ)
        return progress()
    }

    /**
     * 应答对方的 CQ（我方序号 1：`<对方> <我> [网格]`）。
     *
     * 照 FT8CN，此刻不做别的：等对方回应（它多半直接回序号 2 的报告，也可能回序号 1 的网格）。
     *
     * @param snr 选台那一刻测到的对方强度（FT8CN `toCallsign.snr`）；给 null 时等对方首次回复再取。
     */
    fun startResponderQso(
        call: String,
        grid: String?,
        utcMs: Long = 0L,
        snr: Int? = null,
    ): QsoProgress = begin(
        call = call,
        grid = grid,
        newOrder = 1,
        newRole = QsoRole.RESPONDER,
        sentReport = snr?.let { reportFromSnr(it) },
        utcMs = utcMs,
    )

    /**
     * 我方发过 CQ、或对方主动呼叫我方（`<我> <对方> <网格>`）：直接发信号报告（我方序号 2）。
     *
     * @param snr 本次解码的信噪比（作为我发给对方的报告）
     */
    fun startCallerQso(call: String, grid: String?, snr: Int, utcMs: Long = 0L): QsoProgress = begin(
        call = call,
        grid = grid,
        newOrder = 2,
        newRole = QsoRole.CALLER,
        sentReport = reportFromSnr(snr),
        utcMs = utcMs,
    )

    /**
     * 对方直接发来信号报告（序号 2）→ 我确认（我方序号 3 的 `R<报告>`）。
     *
     * 用于自动程序的「被呼自动应答」：对方发 `<myCall> <theirCall> <report>` 时，
     * 我直接回 `R<报告>` 并等待对方 RR73。
     *
     * @param theirReport 对方给我的信号报告
     * @param snr 本次解码的信噪比（作为我发给对方的报告）
     */
    fun respondToReport(
        call: String,
        theirReport: Int,
        snr: Int,
        utcMs: Long = 0L,
    ): QsoProgress = begin(
        call = call,
        grid = null,
        newOrder = 3,
        newRole = QsoRole.RESPONDER,
        sentReport = reportFromSnr(snr),
        receivedReport = theirReport,
        utcMs = utcMs,
    )

    /**
     * 对方已确认收到我的报告（序号 3 的 `R<报告>`）→ 我收尾（我方序号 4 的 `RR73`）。
     *
     * 发出 RR73 即**落库**（FT8CN `setCurrentFunctionOrder(4)`）；本段仍会停在 [QsoState.ROGERS]
     * 等对方的 73（照 FT8CN，等不到会重发 RR73 / 按兜底收尾）。
     *
     * @param utcMs 落库时间（UTC 毫秒）；<=0 时回退到最近一次带时间戳的调用
     */
    fun respondToRoger(
        call: String,
        theirReport: Int,
        snr: Int,
        utcMs: Long = 0L,
    ): QsoProgress = begin(
        call = call,
        grid = null,
        newOrder = 4,
        newRole = QsoRole.RESPONDER,
        sentReport = reportFromSnr(snr),
        receivedReport = theirReport,
        utcMs = utcMs,
    )

    /**
     * 对方已发 RR73 / RRR（序号 4）→ 我回 73 收尾（我方序号 5）。
     *
     * 照 FT8CN `checkCQMeOrFollowCQMessage` 循环 2（只排除 `73`）：**即使本机没有进行中的 QSO**，
     * 收到指名给我的 RR73 也要回 73 并落库 —— 这正是「App 重启 / 丢状态后把尾巴接回来」的路径。
     *
     * @param utcMs 落库时间（UTC 毫秒）；<=0 时回退到最近一次带时间戳的调用
     */
    fun respondToRr73(call: String, snr: Int, utcMs: Long = 0L): QsoProgress = begin(
        call = call,
        grid = null,
        newOrder = 5,
        newRole = QsoRole.RESPONDER,
        sentReport = reportFromSnr(snr),
        utcMs = utcMs,
    )

    /** 中止当前 QSO。 */
    fun stop(): QsoProgress {
        reset()
        return progress()
    }

    /**
     * 处理一个接收时隙的解码结果，按 FT8CN 的序号模型推进。
     *
     * 流程照 `FT8TransmitSignal.parseMessageToFunction` `:808`：
     * 找「对方 → 我」的报文序号（找到即清零无回应计数）→ 完成判据 → 推进一格 → 无回应计数。
     *
     * @param countNoReply 本批若没有目标的回复是否累加「无回应」。上层只在**对方可能回我的那个
     *   时隙**（我方不发射的相反周期）传 `true`；我方发射时隙的批传 `false`，避免每周期数两次。
     *   **空批也算**（对方静默时批往往整批为空，若不计数监管永远不会触发）。
     */
    fun onDecoded(
        messages: List<DecodeResult>,
        utcMs: Long = 0L,
        countNoReply: Boolean = true,
    ): QsoProgress {
        markTime(utcMs)
        lastAdvanced = false
        if (!canOperate) return progress()
        if (!progress().active) return progress()
        // 序号 6（已发 CQ、等回应者）阶段由选台逻辑收集/排序回应者；
        // 照 FT8CN，此阶段永不放弃主叫、也不自增无回应计数。
        if (awaitingResponders) return progress()

        val them = theirCall
        // 照 FT8CN `checkFunctionOrdFromMessages` `:604`：从**最后一条**往前找「to 是我 && from 是当前目标」
        var newOrder = FunctionOrder.NONE
        var hit: DecodeResult? = null
        var hitParsed: ParsedMessage? = null
        for (m in messages.asReversed()) {
            if (m.deep) continue // 深度（弱信号二次）解码只用于显示，不推进 QSO（FT8CN isDeep）
            val p = MessageParser.parse(m.text)
            val from = p.from ?: continue
            if (from.equals(myCall, ignoreCase = true)) continue // 忽略自己
            if (!p.addressedTo(myCall)) continue // 只处理发给我的
            if (them != null && !CallMatch.isFrom(from, them)) continue // 只认当前对手
            val o = FunctionOrder.of(p)
            if (o == FunctionOrder.NONE) continue
            newOrder = o
            hit = m
            hitParsed = p
            break
        }

        if (newOrder != FunctionOrder.NONE) {
            noReplyCount = 0
            // 照 FT8CN `:618-626`：对方给的报告（序号 2 / 3）记下来，落库取这个值
            if (newOrder == 2 || newOrder == 3) hitParsed?.report?.let { reportReceived = it }
            theirGrid = theirGrid ?: hitParsed?.grid
        }

        // ---- 照 FT8CN `:832-843`：完成判据（任一命中即收尾，不再推进）----
        // 无回应计数：**除 CQ 主叫（序号 6）外各阶段**都生效（FT8CN 只在序号 4 生效，
        // 会让停在序号 1~3 的呼叫者永远重发、无法换台）。阈值取字面值（不照 FT8CN ×2）。
        val noReplyExceeded =
            (noReplyLimit > 0 && noReplyCount >= noReplyLimit) ||
                (noReplyLimit == 0 && noReplyCount >= NO_REPLY_HARD_LIMIT)
        // 「对方开始呼别人」是独立信号，仍只在序号 4（发出 RR73 后）判定：
        // 序号 1~3 时对方重发 CQ 只是没抄到我，属正常重试，靠上面的无回应计数兜底。
        val giveUpByNoReply =
            settings.autoSeq &&
                ((step != QsoStep.NONE && step != QsoStep.CQ && noReplyExceeded) ||
                    (step == QsoStep.RR73 && targetCallingOthers(messages, them)))
        if (newOrder == 5 || giveUpByNoReply) {
            finish(utcMs, hit?.slotUtcMs ?: 0L)
            gaveUp = giveUpByNoReply
            step = QsoStep.NONE
            txText = null
            state = QsoState.SIGNOFF
            lastAdvanced = newOrder != FunctionOrder.NONE
            return progress()
        }

        if (newOrder != FunctionOrder.NONE) {
            // 照 FT8CN `:857-860`：对方第一次回复（序号 1 / 2）时复位目标报告、重建报文表。
            // 本机没有报文表；报告值本身**不刷新**（照 FT8CN 取的是首次测到的强度）。
            if (newOrder == 1 || newOrder == 2) reportReceived = reportReceived ?: hitParsed?.report
            // 报告值：只在**第一次推进**时按触发报文的 SNR 取一次（照 FT8CN `toCallsign.snr`：
            // 定下这台时测到的强度，QSO 内不再刷新）。正常流程里 `start*` 已带 SNR，
            // 这里只兜住「开始时不带 SNR」的路径（如人工从半路接手）。
            if (reportSent == null) reportSent = reportFromSnr(hit?.snr ?: 0)
            lastAdvanced = true
            // Auto Seq 关闭时仍推进状态（UI 的六个报文槽才能显示下一条），
            // 但 SessionViewModel 的 txTick 不会自动播 qso.txText，等人工点槽发送。
            setOrder(QsoStep.replyTo(newOrder), utcMs, hit?.slotUtcMs ?: 0L)
            return progress()
        }

        // 到此：本批**没有**「对我的回复」→ 记一次「无回应」。
        //
        // 与 FT8CN 的**有意偏离**：FT8CN `:812` 空批直接返回（不计数）、`:886` 只对非弱信号批次 +1。
        // 真机后果：对方静默时这一批往往**整个是空的**（或只解到我方自己的自听回声、被上层过滤），
        // `noReplyCount` 永远停在 0 → 「无回应次数」形同虚设，对静默伙伴无限重发（实测 20+ 批次不换台）。
        // 改为：只要本批**没有**目标的回复就 +1（含空批）。是否计数由上层 [countNoReply] 控制 ——
        // 对方只可能在我们**不发射**的周期回我，所以我方发射时隙的批不计，免得每周期数两次。
        if (settings.autoSeq && countNoReply) noReplyCount++
        return progress()
    }

    // ---- 会话调度（原第 2 层 AutoScheduler） ----

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
     * 接收时隙结束、且当前处于**空闲**或**等回应者**阶段时的决策。
     *
     * 顺序（方案 §3.2）：监管超时 → Stop；定向候选非空 → 一律应答（最高优先）；
     * 否则按两个开关决定「应答未通联的 CQ」或「发 CQ」。
     */
    fun onIdleBatch(
        messages: List<DecodeResult>,
        filter: DecodeFilterState = DecodeFilterState(),
        worked: WorkedIndex = WorkedIndex.EMPTY,
        utcNowMs: Long = 0L,
    ): AutoAction {
        checkSupervision(utcNowMs)?.let { return AutoAction.Stop(it) }
        if (protectedStop) return AutoAction.None
        // 论文 Auto Seq 关闭：不自动选台 / 不应答 / 不发 CQ，只保留手动操作。
        if (!settings.autoSeq) return AutoAction.None

        val candidates = AutoProgramSelector.collect(messages, settings, filter, worked, myCall)
        val directed = candidates.filter { it.kind != AutoTargetKind.CQ }
        // 论文 Call 1st：CQ 阶段收到第一个定向回应时自动应答；关闭后交给人工选择。
        if (settings.callFirst && directed.isNotEmpty()) {
            enqueue(AutoProgramSelector.rank(directed))
            return AutoAction.HandleDirected(queue.removeFirst())
        }
        return nextAction(candidates.filter { it.kind == AutoTargetKind.CQ })
    }

    /** 一段 QSO 结束（成功）后的决策：先处理队列，否则回到发 CQ（FT8CN `resetToCQ`）。 */
    fun onQsoFinished(utcNowMs: Long = 0L): AutoAction {
        if (protectedStop) return AutoAction.None
        if (!settings.autoSeq) return AutoAction.None
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
        if (!settings.autoSeq) return AutoAction.None
        queue.clear()
        val candidates = AutoProgramSelector.collect(messages, settings, filter, worked, myCall)
        val directed = candidates.filter { it.kind != AutoTargetKind.CQ }
        if (settings.callFirst && directed.isNotEmpty()) {
            return AutoAction.HandleDirected(AutoProgramSelector.rank(directed).first())
        }
        return nextAction(candidates.filter { it.kind == AutoTargetKind.CQ })
    }

    /**
     * FT8CN `checkCQMeOrFollowCQMessage` **循环 2** 的落地：进行中的 QSO **目标本批沉默**时，
     * 也必须应答「其他呼叫我方」的定向台（否则忙起来就会漏应答）。
     *
     * 与 [onIdleBatch] 的差别：**不看**两个开关、不看是否已有目标，只排除「当前目标自身」；
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
        // Auto Seq / Call 1st 关闭时不做自动换台（论文语义：交给人工）。
        if (!settings.autoSeq || !settings.callFirst) return null
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
            AutoAction.AnswerCq(AutoProgramSelector.rank(cqs).first(), settings.skipTx1)
        } else {
            AutoAction.SendCq
        }

    private fun enqueue(list: List<AutoTarget>) {
        list.forEach { t ->
            if (queue.none { it.call.equals(t.call, ignoreCase = true) }) queue.addLast(t)
        }
    }

    // ---- 内部 ----

    /**
     * 照 FT8CN `checkTargetCallMe` `:578`：本批里「当前目标在呼叫别人」吗？
     *
     * 计数从 1 起（`:579`）：只要本批**有一条**目标发给我的报文就返回 false；
     * 否则目标每出现一条就 +1，`>1`（即目标至少有两条发言且都不是给我的）判为「在呼别人」。
     */
    private fun targetCallingOthers(messages: List<DecodeResult>, them: String?): Boolean {
        if (them == null) return false
        var fromCount = 1
        for (m in messages) {
            if (m.deep) continue
            val p = MessageParser.parse(m.text)
            val from = p.from ?: continue
            if (!CallMatch.isFrom(from, them)) continue
            if (p.addressedTo(myCall)) return false
            fromCount++
        }
        return fromCount > 1
    }

    /**
     * 开始一段 QSO 并直接进入某个序号（照 FT8CN `setTransmit` `:199`：目标 + 序号 + 网格）。
     *
     * 呼号非法（空 / 等于自己）时什么都不做，返回当前进度。
     */
    private fun begin(
        call: String,
        grid: String?,
        newOrder: Int,
        newRole: QsoRole,
        sentReport: Int?,
        receivedReport: Int? = null,
        utcMs: Long = 0L,
    ): QsoProgress {
        require(canOperate) { "未配置呼号" }
        val their = call.trim().uppercase()
        if (their.isEmpty() || their == myCall) return progress()
        reset()
        startedUtcMs = if (utcMs > 0) utcMs else 0L
        role = newRole
        theirCall = their
        theirGrid = grid?.trim()?.uppercase()?.ifEmpty { null }
        reportSent = sentReport
        reportReceived = receivedReport
        setOrder(QsoStep.of(newOrder), utcMs)
        return progress()
    }

    /**
     * 设定**我方要发的报文序号**（照 FT8CN `setCurrentFunctionOrder` `:542`）：
     * 渲染报文、同步对外状态；序号 4 / 5（发出 RR73 / 73）即落库。
     */
    private fun setOrder(newStep: QsoStep, utcMs: Long = 0L, slotUtcMs: Long = 0L) {
        step = newStep
        role = roleOf(newStep) ?: role
        render()
        syncState()
        if (step == QsoStep.RR73 || step == QsoStep.FINAL_73) finish(utcMs, slotUtcMs)
    }

    /** 序号隐含的角色（只供 UI；4 / 5 沿用进入时的角色）。 */
    private fun roleOf(s: QsoStep): QsoRole? = when (s) {
        QsoStep.GRID, QsoStep.ROGER -> QsoRole.RESPONDER
        QsoStep.REPORT, QsoStep.CQ -> QsoRole.CALLER
        else -> null
    }

    /** 依据 [step] 渲染当前应发报文到 [txText]（照 FT8CN `getFunctionCommand`）。 */
    private fun render() {
        val them = theirCall
        txText = when (step) {
            QsoStep.NONE -> null
            QsoStep.GRID -> if (them == null) null
            else listOf(them, myCall, myGrid).filter { it.isNotEmpty() }.joinToString(" ")
            QsoStep.REPORT -> if (them == null || reportSent == null) null
            else "$them $myCall ${MessageParser.formatReport(reportSent!!)}"
            QsoStep.ROGER -> if (them == null || reportSent == null) null
            else "$them $myCall R${MessageParser.formatReport(reportSent!!)}"
            QsoStep.RR73 -> if (them == null) null else "$them $myCall RR73"
            QsoStep.FINAL_73 -> if (them == null) null else "$them $myCall 73"
            QsoStep.CQ -> listOf("CQ", cqModifier, myCall, myGrid).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    /**
     * 依 [step] 同步对外状态。
     *
     * 注意：[QsoState.FAILED] 已无来源（旧的 `retryLimit` 重试体系已退役）；保留该枚举值仅
     * 为兼容 UI，异常 / 中止一律走 [stop]（回到 IDLE）。
     */
    private fun syncState() {
        state = when (step) {
            QsoStep.RR73 -> QsoState.ROGERS
            QsoStep.FINAL_73 -> QsoState.SIGNOFF
            QsoStep.REPORT -> QsoState.REPORT
            QsoStep.ROGER -> QsoState.ROGER_REPORT
            QsoStep.GRID -> QsoState.REPLYING
            QsoStep.CQ -> QsoState.CALLING
            QsoStep.NONE -> if (role != QsoRole.NONE) QsoState.CALLING else QsoState.IDLE
        }
    }

    /** 写入通联记录（**幂等**：同一段只出一条，照 FT8CN `record.saved`）。 */
    private fun finish(utcMs: Long, slotUtcMs: Long) {
        if (logged) return
        val them = theirCall ?: return
        logged = true
        val endMs = when {
            utcMs > 0 -> utcMs
            // 完成时间优先取**触发收尾那条报文**的时隙起点（照 FT8CN 的「本次解码时刻」）
            slotUtcMs > 0 -> slotUtcMs
            else -> lastUtcMs
        }
        logEntry = QsoLogEntry(
            theirCall = them,
            theirGrid = theirGrid,
            reportSent = reportSent,
            reportReceived = reportReceived,
            utcMs = endMs,
            // 起始时间未知时回退为完成时间（ADIF 的 TIME_ON 与 TIME_OFF 相同）
            startUtcMs = if (startedUtcMs > 0) startedUtcMs else endMs,
        )
    }

    /** 记下最近一次带时间戳的调用（供 [finish] 缺时间时回退）。 */
    private fun markTime(utcMs: Long) {
        if (utcMs > 0) lastUtcMs = utcMs
    }

    private fun reset() {
        role = QsoRole.NONE
        state = QsoState.IDLE
        step = QsoStep.NONE
        theirCall = null
        theirGrid = null
        reportSent = null
        reportReceived = null
        txText = null
        startedUtcMs = 0L
        noReplyCount = 0
        lastAdvanced = false
        lastSentText = null
        logEntry = null
        logged = false
        awaitingResponders = false
        gaveUp = false
    }

    /** 用解码 SNR 作为要发送的信号报告（clamp 到 -24..+30 dB）。 */
    private fun reportFromSnr(snr: Int): Int = snr.coerceIn(-24, 30)
}
