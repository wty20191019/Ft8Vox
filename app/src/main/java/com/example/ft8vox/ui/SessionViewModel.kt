package com.example.ft8vox.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ft8vox.SessionService
import com.example.ft8vox.SessionServiceBridge
import com.example.ft8vox.container
import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.data.log.QsoComment
import com.example.ft8vox.data.log.QsoEntity
import com.example.ft8vox.data.log.QsoRepository
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.data.settings.SettingsRepository
import com.example.ft8vox.data.settings.TX_PARITY_EVEN
import com.example.ft8vox.data.settings.TX_PARITY_ODD
import com.example.ft8vox.engine.AlertTone
import com.example.ft8vox.engine.AudioDevices
import com.example.ft8vox.engine.AudioEngine
import com.example.ft8vox.engine.DecodeParams
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.engine.Diag
import com.example.ft8vox.engine.Ft8Config
import com.example.ft8vox.engine.Ft8Engine
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.engine.VoxConfig
import com.example.ft8vox.engine.WaterfallInfo
import com.example.ft8vox.qso.AutoAction
import com.example.ft8vox.qso.AutoProgramSettings
import com.example.ft8vox.qso.AutoProgramSelector
import com.example.ft8vox.qso.AutoScheduler
import com.example.ft8vox.qso.AutoTarget
import com.example.ft8vox.qso.AutoTargetKind
import com.example.ft8vox.qso.CQ_PREFIX_SLOTS
import com.example.ft8vox.qso.DEFAULT_CQ_PREFIXES
import com.example.ft8vox.qso.DecodeFilterState
import com.example.ft8vox.qso.FollowRoster
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.qso.QsoEngine
import com.example.ft8vox.qso.QsoLogEntry
import com.example.ft8vox.qso.QsoProgress
import com.example.ft8vox.qso.QsoState
import com.example.ft8vox.qso.TxCompose
import com.example.ft8vox.qso.TxScheduler
import com.example.ft8vox.qso.WorkedIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 发射/试音「写入 0 帧」时的统一提示。
 *
 * 最典型原因：输出流被系统挂起（真机华为 EMUI 10 的 AAudio **MMAP** 输出空闲数秒后被服务端
 * `Suspending stream`，之后所有写都 0 帧超时 —— 已改为**非 MMAP 输出路径**从根上规避，见
 * `audio_engine.c` 的 `out_open_locked`）。native 仍会把这类流标记为坏流、在下次播放前关流重开，
 * 所以这里提示「已标记重开，请重试」，而不是含糊的「已发射（0 帧）」。
 */
private const val OUT_STALLED_FAIL = "输出声卡无响应（已标记重开，请重试）"

/** QSO/自动程序决策的诊断日志标签（logcat：`adb logcat -s Ft8VoxQso`）。 */
private const val TAG_QSO = "Ft8VoxQso"

/** 接收会话的非瀑布状态（供状态栏/控制区使用）。 */
data class ReceiverStatus(
    val running: Boolean = false,
    /** 是否正处于某个时隙的采集窗口内（false 表示正在等待时隙对齐）。 */
    val inSlot: Boolean = false,
    val status: String = "就绪",
    val protocol: Protocol = Protocol.FT8,
    val inputRate: Int = 0,
    val outputRate: Int = 0,
    val slotMs: Int = 15000,
    val msToNextSlot: Long = 0,
    val slotProgress: Float = 0f,
    /** 当前时隙奇偶：0=偶数周期，1=奇数周期。 */
    val slotParity: Int = 0,
    val slotsDecoded: Long = 0,
    val droppedSamples: Long = 0,
    /** 本会话累计解码条数（docs/Ft8Vox.md 底部状态条「总数」）。 */
    val decodedTotal: Long = 0,
    /** 最近一次解码耗时（ms，0=还没解码过）；顶栏「解码 nms」，用于调解码深度参数。 */
    val lastDecodeMs: Long = 0,
    val selectedFreqHz: Int = 1000,
    // ---- 台站（来自设置） ----
    val myCall: String = "",
    val myGrid: String = "",
    /** 当前波段（无 CAT，由用户指定，用于记录与 ADIF 导出）。 */
    val band: String = BandPlan.DEFAULT_BAND,
    /** 当前刻度频率（Hz，已解析；0 表示未知）。 */
    val dialHz: Long = 0L,
    // ---- 发射/QSO ----
    /**
     * 发送总开关：false=只接收（默认），true=允许发射。
     *
     * 只回答「能不能发」，自己不发任何报文；发什么由手动发送（抽屉「发送」按钮 / 解码卡片手势）
     * 或自动程序决定。仅会话内有效，不持久化。
     */
    val txEnabled: Boolean = false,
    /** 我方发射所在的周期：0=偶数，1=奇数（自动按手机 UTC 时间锁定）。 */
    val txParity: Int = 0,
    /** 已武装本次发射（有待发报文，等时隙到点）。 */
    val txArmed: Boolean = false,
    /** 距离下一个我方发射时隙的毫秒数。 */
    val txCountdownMs: Long = 0,
    /** 正在播放发射音频。 */
    val txing: Boolean = false,
    val qso: QsoProgress = QsoProgress(),
    /** 最近一次发射的报文与所属时隙起点。 */
    val lastTxText: String? = null,
    val lastTxSlotMs: Long = 0,
    // ---- JTDX 风格操作（阶段 7c） ----
    /**
     * 同频发射：true＝选台时发射频率（红线）跟到目标频率；false＝异频发射（发射固定在设定频率）。
     */
    val sameFreqTx: Boolean = true,
    /**
     * 自动程序策略（来自设置；照 FT8CN 四项，见方案 §3.1）。
     *
     * **没有档位、也没有独立开关**：自动程序常开，唯一的闸门是「发送总开关」[txEnabled]
     * （默认关、不持久化，开即自动发射、无确认框）。
     */
    val autoProgram: AutoProgramSettings = AutoProgramSettings(),
    /** 自动程序状态文案（发送总开关关闭时为 null）。 */
    val autoPhaseLabel: String? = null,
    /** 第 2 层定向呼叫队列长度（等待逐个完成的回应者）。 */
    val autoQueueSize: Int = 0,
    /** 待发的一次性报文（长按解码行选择；发完即清空）。 */
    val manualTxText: String? = null,
    /**
     * 一次性发射（自定义报文框 / 长按解码行「逐条发送」）的**拒绝原因**（红字，几秒后自动消失）。
     *
     * 以前这些拒绝只写进 [status] 字符串，而竖屏外壳里没有显示它的位置 —— 用户点「发送」
     * 看上去什么都没发生（真机反馈）。现在统一显示在发射区底部，见 `docs/Ft8Vox.md`。
     */
    val txNotice: String? = null,
    // ---- PTT / 输入电平（U7b） ----
    /** 平滑后的输入电平（dBFS，下限约 -100）；纯显示用。 */
    val voxLevelDb: Float = -100f,
) {
    /**
     * 下一次计划发射的报文（取值与发射调度 `txTick` 一致：一次性手动 > QSO 引擎计划）。
     */
    val pendingTxText: String?
        get() = manualTxText?.takeIf { it.isNotBlank() } ?: qso.txText?.takeIf { it.isNotBlank() }

    /**
     * 界面显示用的发射报文。
     *
     * **发射中固定为本条实际在播的 [lastTxText]**：发射途中改目标 / QSO 状态推进都不会让
     * 顶栏与抽屉的文案跟着变（否则显示的会与真正在天上的报文不符）；其余时刻显示
     * [pendingTxText]（下一次会发射什么）。
     */
    val displayTxText: String?
        get() = if (txing) lastTxText?.takeIf { it.isNotBlank() } ?: pendingTxText else pendingTxText
}

/**
 * 会话状态管理：把实时引擎的轮询结果整理为不可变 UI 状态，并驱动 QSO 自动序列。
 *
 * - [messages]：解码列表（新→旧）
 * - [waterfall]：瀑布帧（滚动缓冲）
 * - [status]：状态栏、控制区与当前 QSO 进度
 * - [recentQso]：最近完成的通联（来自 Room）
 *
 * 设置以 DataStore 为唯一事实来源：本类只读 [SettingsRepository]，用户改动经
 * [persist] 写回，再由设置流回灌到 [status]，避免两处状态各写各的。
 *
 * 发射调度：每个时隙检查一次，仅在我方周期（[ReceiverStatus.txParity]）且处于时隙起始窗口内
 * 调用 native 阻塞写播放；其余时间保持静音。
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo: SettingsRepository = app.container.settings
    private val qsoRepo: QsoRepository = app.container.qso

    private val _messages = MutableStateFlow<List<DecodeResult>>(emptyList())
    val messages: StateFlow<List<DecodeResult>> = _messages.asStateFlow()

    /**
     * 我方发射记录（接收列表里的「TX 行」，docs/Ft8Vox.md）。
     *
     * 与 [_messages] 共用同一个条数上限 [ACTIVITY_LIMIT]（在界面层合并时统一截断）。
     */
    private val _txRecords = MutableStateFlow<List<TxRecord>>(emptyList())
    val txRecords: StateFlow<List<TxRecord>> = _txRecords.asStateFlow()

    /** [TxRecord.id] 的发号器（本次会话内单调递增）。 */
    private var txRecordSeq = 0L

    private val _waterfall = MutableStateFlow<WaterfallFrame?>(null)
    val waterfall: StateFlow<WaterfallFrame?> = _waterfall.asStateFlow()

    private val _status = MutableStateFlow(ReceiverStatus())
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    /** 最近 3 条通联（操作页展示）。 */
    val recentQso: Flow<List<QsoEntity>> = qsoRepo.observeRecent(3)

    /** 已通联索引（呼号/网格/前缀），供操作页过滤与高亮、自动程序共用。 */
    private val _worked = MutableStateFlow(WorkedIndex.EMPTY)
    val workedIndex: StateFlow<WorkedIndex> = _worked.asStateFlow()

    /**
     * **当前波段**已通联呼号（键＝波段名大写），照 FT8CN `checkQSLCallsign`
     * （`DatabaseOpr.GetAllQSLCallsign` 的 `where band=?`）。
     *
     * 只用于**自动程序**的「已通联」判据（CQ 台是否纳入候选）；界面高亮与
     * 「已通联」筛选仍用跨波段的 [_worked]。
     */
    private var workedCallsByBand: Map<String, Set<String>> = emptyMap()

    private val qsoEngine = QsoEngine()

    private var pollJob: Job? = null
    private var txJob: Job? = null
    private var wfInfo: WaterfallInfo? = null
    private var wfPixels = IntArray(0)

    private var lastSlotsDecoded = 0L
    /** 上次把「实时读数」写进 [ReceiverStatus] 的 UTC 毫秒数（见 [pollOnce] 的节流说明）。 */
    private var lastLivePublishMs = 0L
    private var pendingDecodes = mutableListOf<DecodeResult>()
    private var lastTxSlotIndex = -1L
    private var txJustFinished = false
    /** 当前正在播放的报文是否为「一次性发射」（发完不再推进 QSO 状态机）。 */
    private var manualInFlight = false

    /**
     * 发射「作废代次」：每次中止发射（[stopTransmit]）时 +1。
     *
     * 用于丢弃**已排程但还没开始写声卡**的那次发射：`txJob.cancel()` 打断不了 native
     * 阻塞写入，若某个协程已经跑过检查、正要做 `playTx`，中止后它不能再出声。
     * 由 UI 线程写、IO 线程读，因此标 `@Volatile`。
     */
    @Volatile
    private var txAbortGen = 0

    /**
     * 自动周期模式下锁定的发射周期（0/1）；未锁定为 null。
     *
     * 锁定后**一直沿用**，QSO 结束也不清除（否则会按时间重锁导致偶/奇来回跳）。
     * 仅「关闭发送总开关」「停止发送」「停止接收」会清除。
     */
    private var autoParity: Int? = null

    /**
     * 「设为目标」后固定的发射周期（目标接收时隙的相反周期）；null 表示未固定。
     *
     * 目标固定期间 [effectiveTxParity] 优先返回该值，保证与目标交替收发（时隙自动对应）；
     * QSO 结束或「取消目标」时只解除固定，发射周期本身保持不变。
     */
    private var pinnedTxParity: Int? = null

    /** 第 2 层自动程序调度器（定向队列 / S&P 选台 / 发射监管）。 */
    private val scheduler = AutoScheduler()

    /**
     * QSO 已完成、但最后一条（RR73/73）还没发出去。
     *
     * 发完这条后在 [txTick] 里通知第 2 层，避免下一个动作覆盖掉状态机导致收尾报文丢失；
     * 期间 `pollOnce` 也不让第 2 层启动新 QSO（见那里的「收尾报文待发」分支）。
     */
    private var pendingAutoFinish = false

    /**
     * 最近一次**已处理**的解码所属时隙序号（native `lastDecodedSlot`；与 `planTx` 的
     * `targetSlotIndex` 同一坐标系，-1=还没解码过）。见 [txTick] 的「先处理完上一时隙解码」。
     */
    private var lastDecodedSlotIndex = -1L

    /**
     * 最近一次处理解码批次的时间（UTC ms）。
     *
     * [txTick] 只在解码还在正常推进（最近两个时隙内）时才拦发射，避免采集/解码异常时
     * 反而把自己锁死。
     */
    private var lastDecodeAtMs = 0L

    /** 诊断：「每个目标时隙只评估一次」去重（真机排查 QSO 不发射/发射被闸门拦住）。 */
    private var lastTxDebugSlot = Long.MIN_VALUE

    /**
     * 本段会话内已落库的呼号（会话内去重，见方案 §4.2）。
     *
     * 取代旧的 [QSO_LOG_DEDUP_MS] 时间窗：同一呼号一段会话只写一条日志。
     * 会话结束（[stop]）时清空。
     */
    private val sessionSavedCalls = HashSet<String>()

    /** 最近一次读到的设置（供 start() 组装 native 配置）。 */
    private var latestSettings = AppSettings()

    /** 最近一次下发给 native 的解码参数，避免设置流每次发射都重复下发。 */
    private var lastDecodeParams = DecodeParams()

    /** 最近一次下发给 native 的 VOX/PTT 配置；引擎重建后置空以强制重下发。 */
    private var lastVoxConfig: VoxConfig? = null

    /** 最近一次下发给 native 的采集增益（dB），U7c。 */
    private var lastInputGainDb: Int? = null

    /** 最近一次下发给 native 的输出音量（dB），U7c 增补。 */
    private var lastOutputGainDb: Int? = null

    /** 最近一次生效的输出设备 id（U7c）；变化时需重开播放流。 */
    private var lastOutputDeviceId: Int? = null

    /** 最近一次生效的输入设备 id（U7c）；变化需重开采集流，运行中提示下次生效。 */
    private var lastInputDeviceId: Int? = null

    /** 最近一次下发给 native 的时隙偏移（ms，U7「发射偏移」）。 */
    private var lastSlotOffsetMs: Int? = null

    /** 发射频率（红线）拖动期间的落盘防抖任务（见 [setTxFreq]）。 */
    private var txFreqPersistJob: Job? = null

    /**
     * 引擎代次：每次重建 native 引擎（[start]）都 +1。
     *
     * 排程好的发射协程可能在引擎重建**之后**才真正开跑（`txJob.cancel()` 打断不了已经
     * 开始的 JNI 阻塞写入），此时 `AudioEngine` 已指向新引擎 —— 用代次把这种「跨引擎的
     * 迟到发射」直接丢弃，避免把旧协议的波形发到新引擎上。
     */
    private var engineGen = 0

    /** 前台服务是否已启动（阶段 9：后台保活）。 */
    private var serviceOn = false

    /**
     * 临时抑制前台服务的启停。
     *
     * [restartForProtocol] 内部会成对调用 `stop()` + `start()`，若照常同步服务会「停服务→
     * 起服务」造成通知闪烁，故这一小段窗口内跳过服务同步，结束后统一收口一次。
     */
    private var serviceSuppressed = false

    /** 「含我呼号哔声」提示音（U7d）。 */
    private val alertTone = AlertTone()

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                latestSettings = s
                applySettings(s)
            }
        }
        viewModelScope.launch {
            qsoRepo.observeAll().collect { list ->
                _worked.value = WorkedIndex(
                    calls = list.map { it.theirCall },
                    grids = list.mapNotNull { it.theirGrid },
                )
                // 自动程序用「本波段」已通联呼号（照 FT8CN checkQSLCallsign 的波段口径）
                workedCallsByBand = list
                    .groupBy { it.band.trim().uppercase() }
                    .mapValues { (_, rows) ->
                        rows.mapNotNull { it.theirCall.trim().uppercase().takeIf { c -> c.isNotEmpty() } }
                            .toSet()
                    }
            }
        }
        // 通知栏「停止接收」（阶段 9）：服务只转发请求，真正停会话/放引擎仍由本类负责
        viewModelScope.launch {
            SessionServiceBridge.stopRequests.collect { stop() }
        }
    }

    // ---- 设置 → 运行状态 ----

    private fun applySettings(s: AppSettings) {
        _status.update { cur ->
            cur.copy(
                myCall = s.myCall,
                myGrid = s.myGrid,
                band = s.band,
                dialHz = s.resolvedDialHz,
                selectedFreqHz = s.selectedFreqHz,
                // 固定自动周期：未锁定时按手机 UTC 时间取「下一个来得及的时隙」
                txParity = autoParity ?: nextSlotParity(
                    AudioEngine.utcNowMs(),
                    (if (cur.running) cur.slotMs else s.protocol.slotMs).toLong(),
                    txPreambleMs().toLong() + AUTO_PARITY_LEAD_MARGIN_MS,
                ),
                sameFreqTx = s.sameFreqTx,
                autoProgram = s.auto,
                // 协议绑定在引擎上（时隙长度与调制方式），运行中不能直接改：
                // 这里先保留当前值，随后 [restartForProtocol] 会重建引擎切到 s.protocol
                protocol = if (cur.running) cur.protocol else s.protocol,
                slotMs = if (cur.running) cur.slotMs else s.protocol.slotMs,
            )
        }
        // 运行中改协议 → 重建引擎（接收短暂中断）
        if (_status.value.running && s.protocol != _status.value.protocol) {
            restartForProtocol(s.protocol)
        }
        qsoEngine.configure(s.myCall, s.myGrid, s.auto.noReplyLimit)
        scheduler.configure(s.auto, s.myCall)
        applyDecodeParams(s)
        applyVox(s)
        applyAudio(s)
        applySlotOffset(s)
    }

    /** 运行中把「热生效」的解码参数下发给 native（频率范围/OSR 需重启，见 [start]）。 */
    private fun applyDecodeParams(s: AppSettings) {
        val p = s.decodeParams
        if (p == lastDecodeParams) return
        lastDecodeParams = p
        if (_status.value.running) AudioEngine.setDecodeParams(p)
    }

    /**
     * 把 PTT 配置下发给 native（热生效）。
     * 引擎未初始化时 [AudioEngine.setVox] 为无操作；[start] 会用 force 重下发一次。
     */
    private fun applyVox(s: AppSettings, force: Boolean = false) {
        val cfg = voxConfigOf(s)
        if (!force && cfg == lastVoxConfig) return
        lastVoxConfig = cfg
        AudioEngine.setVox(cfg)
    }

    /** 由设置组装 native 的 PTT 配置。 */
    private fun voxConfigOf(s: AppSettings): VoxConfig = VoxConfig(
        pttDelayMs = s.pttDelayMs,
        leadToneMs = if (s.txLeadTone) s.txLeadToneMs else 0,
    )

    /**
     * 下发音频路由/增益（U7c）。
     *
     * - 采集增益、输出音量：热生效；
     * - 输出设备：变化时关闭播放流，下次 [armPlayback] 用新设备重开；
     * - 输入设备：需重开采集流，运行中时不打断，提示下次开始接收生效。
     */
    private fun applyAudio(s: AppSettings, force: Boolean = false) {
        if (force || s.inputGainDb != lastInputGainDb) {
            lastInputGainDb = s.inputGainDb
            AudioEngine.setInputGain(s.inputGainDb)
        }

        if (force || s.outputGainDb != lastOutputGainDb) {
            lastOutputGainDb = s.outputGainDb
            AudioEngine.setOutputGain(s.outputGainDb)
        }

        val outId = AudioDevices.parseId(s.outputDevice)
        if (force || outId != lastOutputDeviceId) {
            val changed = lastOutputDeviceId != null && outId != lastOutputDeviceId
            lastOutputDeviceId = outId
            if (changed) {
                if (_status.value.txing) {
                    _status.update { it.copy(status = "输出声卡将在下次发射生效") }
                } else {
                    AudioEngine.stopPlayback()
                    if (_status.value.txArmed) armPlayback()
                }
            }
        }

        val inId = AudioDevices.parseId(s.inputDevice)
        if (force || inId != lastInputDeviceId) {
            val changed = lastInputDeviceId != null && inId != lastInputDeviceId
            lastInputDeviceId = inId
            if (changed && _status.value.running) {
                _status.update { it.copy(status = "输入设备将在下次开始接收生效") }
            }
        }
    }

    /**
     * 下发时隙偏移（热生效，U7「发射偏移」）。
     *
     * 影响 native 的采集窗口起点（= 解码 DT 的基准），发射起点由 [planTx] 用同一值平移。
     * 改在时隙中途时，当前时隙的窗口会错位到下一个网格点生效，属预期。
     */
    private fun applySlotOffset(s: AppSettings, force: Boolean = false) {
        if (!force && s.slotOffsetMs == lastSlotOffsetMs) return
        lastSlotOffsetMs = s.slotOffsetMs
        AudioEngine.setSlotOffsetMs(s.slotOffsetMs)
    }

    private fun persist(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    // ---- 配置 ----

    fun selectProtocol(protocol: Protocol) {
        if (_status.value.protocol == protocol) return
        // 协议不能热切换：只持久化，[applySettings] 检测到差异后会自动重建引擎重启接收
        persist { it.copy(protocolName = protocol.name) }
    }

    /**
     * 「移动红线」：把发射频率直接设到 [hz]（瀑布单击 / 拖动，以及任何显式设定）。
     *
     * 拖动会高频调用本方法，落盘做了 250 ms 防抖（[txFreqPersistJob]），UI 状态仍逐帧更新。
     */
    fun setTxFreq(hz: Int) {
        val v = clampFreq(hz)
        if (_status.value.selectedFreqHz == v) return
        _status.update { it.copy(selectedFreqHz = v) }
        txFreqPersistJob?.cancel()
        txFreqPersistJob = viewModelScope.launch {
            delay(250)
            settingsRepo.update { it.copy(selectedFreqHz = v) }
        }
    }

    /**
     * 选台：**同频发射**时把红线移到目标频率 [hz]；**异频发射**时保持设定频率不动。
     *
     * 用于点解码行 / 应答 CQ / 自动程序选到目标（实际发射频率一律取红线位置 [ReceiverStatus.selectedFreqHz]）。
     */
    fun selectTargetFreq(hz: Int) {
        if (!_status.value.sameFreqTx) return
        setTxFreq(hz)
    }

    /** 同频发射（true，选台时红线跟随目标）/ 异频发射（false，红线固定在设定频率）。 */
    fun setSameFreqTx(value: Boolean) {
        _status.update { it.copy(sameFreqTx = value) }
        persist { it.copy(sameFreqTx = value) }
    }

    /**
     * 改一项自动程序策略开关（发射监管 / 无回应次数 / 两个开关）。
     *
     * 自动程序**没有独立的总开关**：唯一闸门是「发送总开关」[setTxEnabled]。
     */
    fun setAutoOption(transform: (AutoProgramSettings) -> AutoProgramSettings) {
        _status.update { it.copy(autoProgram = transform(it.autoProgram)) }
        persist { it.copy(auto = transform(it.auto)) }
    }

    /** 忽略一个呼号（不再显示其解码，也不参与自动程序选台）。 */
    fun ignoreCall(call: String) {
        val c = call.trim().uppercase().takeIf { it.isNotEmpty() } ?: return
        persist { it.copy(ignoredCalls = it.ignoredCalls + c) }
    }

    /** 从当前解码列表删除一条（仅本会话显示层；不写入忽略名单、不影响同呼号的后续解码）。 */
    fun removeMessage(msg: DecodeResult) {
        _messages.update { list -> list.filterNot { it === msg } }
    }

    /** 取消忽略（供设置页管理）。 */
    fun unignoreCall(call: String) {
        val c = call.trim().uppercase()
        persist { it.copy(ignoredCalls = it.ignoredCalls - c) }
    }

    fun clearIgnored() {
        persist { it.copy(ignoredCalls = emptySet()) }
    }

    /** 手动跟踪一个呼号（长按菜单「跟踪」）；`autoAddCqToFollow` 关时自动程序仍会呼叫它的 CQ。 */
    fun followCall(call: String) {
        val c = call.trim().uppercase().takeIf { it.isNotEmpty() } ?: return
        persist { it.copy(followCalls = it.followCalls + c) }
    }

    /** 取消跟踪（⭐ 跟踪列表右滑 / 长按菜单；自动收录的顺序也一并摘掉）。 */
    fun unfollowCall(call: String) {
        val c = call.trim().uppercase()
        persist { it.copy(followCalls = it.followCalls - c, autoFollowOrder = it.autoFollowOrder - c) }
    }

    /** 切换跟踪状态（长按菜单「跟踪 / 取消跟踪」）。 */
    fun toggleFollow(call: String) {
        val c = call.trim().uppercase().takeIf { it.isNotEmpty() } ?: return
        persist {
            if (c in it.followCalls) {
                it.copy(followCalls = it.followCalls - c, autoFollowOrder = it.autoFollowOrder - c)
            } else {
                it.copy(followCalls = it.followCalls + c)
            }
        }
    }

    /** 清空跟踪名单（跟踪列表里的「全部清除」；手动跟踪与自动收录一并清掉）。 */
    fun clearFollowCalls() {
        persist { it.copy(followCalls = emptySet(), autoFollowOrder = emptyList()) }
    }

    /**
     * 「自动跟踪 CQ（本波段）」：把本批解到的**当前波段还没通联过**的 **CQ** 呼号并入跟踪名单
     * （超出 `FollowRoster.AUTO_MAX` 时淘汰最早收录的；手动跟踪的永不被淘汰）。
     *
     * 波段口径照 FT8CN `checkQSLCallsign`（`where band=?`）：跨波段通联过的台在本波段仍算没通联过。
     * 与 FT8CN 的**有意偏离**：FT8CN 只推送到「呼叫」列表、不写跟踪名单，见 [FollowRoster] 文档。
     */
    private fun autoCollectCqToFollow(batch: List<DecodeResult>) {
        if (batch.isEmpty()) return
        val s = latestSettings
        if (!s.auto.autoAddCqToFollow) return
        val incoming = FollowRoster.pickCqCalls(
            messages = batch,
            myCall = _status.value.myCall,
            ignoredCalls = s.ignoredCalls,
            followedCalls = s.followCalls,
            workedCalls = workedCallsOnBand(_status.value.band),
        )
        if (incoming.isEmpty()) return
        persist { cur ->
            val (calls, order) = FollowRoster.merge(cur.followCalls, cur.autoFollowOrder, incoming)
            if (calls == cur.followCalls && order == cur.autoFollowOrder) cur
            else cur.copy(followCalls = calls, autoFollowOrder = order)
        }
    }

    /**
     * 保存 CQ 前缀格子**与选中项**（发射区「CQ 前缀」弹窗一次提交，docs/Ft8Vox.md）。
     *
     * 一次写完：分两次 `persist` 会各自读一次设置快照，选中项可能落到旧格子表上。
     */
    fun setCqPrefix(slots: List<String>, index: Int) {
        val (prefixes, selected) = cleanCqPrefixes(slots, index)
        persist { it.copy(cqPrefixes = prefixes, cqPrefixIndex = selected) }
    }

    /** 选择「波段 + 刻度频率」（顶栏弹窗 / 设置页）。[hz]<=0 表示用该波段默认频率。 */
    fun setBandFreq(name: String, hz: Long) {
        val n = name.trim().takeIf { it.isNotEmpty() } ?: return
        val resolved = BandPlan.resolveDialHz(n, hz)
        // 换波段＝换一张「本波段会话」：清空会话内落库去重（方案 §4.2 口径为「本波段」）
        if (n != _status.value.band) sessionSavedCalls.clear()
        _status.update { it.copy(band = n, dialHz = resolved) }
        persist { it.copy(band = n, dialHz = resolved) }
    }

    /** 仅切波段（沿用该波段默认频率）。 */
    fun setBand(name: String) = setBandFreq(name, 0L)

    /**
     * 发送总开关（默认关 = 只接收）：唯一回答「**能不能发**」。
     *
     * 它自己也**不发任何报文** —— 发什么由「发送」按钮 / 解码卡片手势（手动）或自动程序（自动）决定。
     * **自动程序没有独立开关**：打开总开关即启动自动发射（无任何确认框），
     * 关闭总开关即让自动程序停发（见 docs/Ft8Vox.md）。
     *
     * - 开启：允许发射、清空自动程序队列并复位发射监管计时；总开关此前未锁定时按手机 UTC
     *   时间锁定「下一个来得及准备的时隙」。
     * - 关闭：立即停发、解除时隙锁定与目标固定。
     *
     * 因不再提供周期设置，用户通过在不同时机重开总开关来换发射时隙。
     */
    fun setTxEnabled(enabled: Boolean) {
        if (!enabled) {
            autoParity = null
            pinnedTxParity = null
            stopTransmit()
            _status.update {
                it.copy(
                    txEnabled = false,
                    autoPhaseLabel = null,
                    autoQueueSize = 0,
                    status = "发送已关闭（只接收）｜自动程序已停",
                )
            }
            return
        }
        scheduler.enable(AudioEngine.utcNowMs())
        relockAutoParityIfNeeded()
        val p = _status.value.txParity
        _status.update {
            it.copy(
                txEnabled = true,
                txParity = p,
                autoPhaseLabel = autoPhaseText(),
                autoQueueSize = scheduler.queuedCount(),
                status = "发送已开启（允许发射，${parityLabel(p)}）",
            )
        }
    }

    /** 自动程序状态文案（发送总开关已开时）。 */
    private fun autoPhaseText(): String {
        val m = _status.value.autoProgram.supervisionMinutes
        return if (m <= 0) "自动运行（发射监管关闭）" else "自动运行（发射监管 $m 分钟）"
    }

    /** 把发射时隙固定到 [targetSlotUtcMs] 的相反周期；返回固定后的周期，无法判定返回 null。 */
    private fun pinToTargetSlot(targetSlotUtcMs: Long): Int? {
        val st = _status.value
        val mine = oppositeSlotParity(targetSlotUtcMs, st.slotMs.toLong()) ?: return null
        pinnedTxParity = mine
        autoParity = mine
        return mine
    }

    /**
     * 某个呼号最近一条解码所在时隙的 UTC 起点（毫秒）。
     *
     * 应答时要按「对方时隙」取相反周期，但不能用 [nextSlotParity] 按当前时间推算：
     * 解码在时隙结束后才到达，此时当前时隙已是对方时隙，再 +1 会落到**与对方相同**的周期
     * （双方同时发射、永远收不到对方）。因此这里回查该台最近一次解码的时隙。
     *
     * @return 时隙起点；列表里找不到该台或离线解码（无时隙）时返回 0
     */
    private fun lastHeardSlotUtcMsOf(call: String): Long {
        val c = call.trim()
        if (c.isEmpty()) return 0L
        return _messages.value
            .firstOrNull { m -> MessageParser.parse(m.text).from?.equals(c, ignoreCase = true) == true }
            ?.slotUtcMs ?: 0L
    }

    /** 清除「设为目标」时的时隙固定（取消目标 / 关闭发射时）。 */
    fun clearTargetSlot() {
        pinnedTxParity = null
    }

    /** 按需重新锁定自动周期（进入新 QSO / 一次性发射前调用）。 */
    private fun relockAutoParityIfNeeded() {
        val st = _status.value
        val p = effectiveTxParity(
            pinned = pinnedTxParity,
            locked = autoParity,
            nowMs = AudioEngine.utcNowMs(),
            slotMs = st.slotMs.toLong(),
            leadMs = txPreambleMs().toLong() + AUTO_PARITY_LEAD_MARGIN_MS,
        )
        autoParity = p
        if (st.txParity != p) _status.update { it.copy(txParity = p) }
    }

    /**
     * 当前墙钟所在时隙的奇偶（名义网格，含 `slotOffsetMs`），取不到时回退到 UI 的 `txParity`。
     *
     * 用途：发射途中人工换目标 / 换报文时，「我方发射时隙」= **此刻墙钟所在时隙**，
     * 不能再用目标解码的时隙去推（见 [answerInternal] / [startAutoTarget]）。
     */
    private fun currentSlotParity(): Int {
        val st = _status.value
        val p = slotParityOf(AudioEngine.utcNowMs() - latestSettings.slotOffsetMs, st.slotMs.toLong())
        return p ?: st.txParity
    }

    /**
     * 人工「换报文」（解码列表左滑之外的入口：报文槽点击 / 自定义「发送」/ 呼叫 CQ）前决定发射周期。
     *
     * **正在发射时必须保持当前周期**：本时隙就是我方发射时隙，若此时按「当前时间」重锁
     * （`nextSlotParity` 取紧邻的下一个时隙），周期会翻到那个**接收时隙** —— 于是「时间不够」
     * 分支的新报文就会被排进本应接收的时隙（与左滑「呼叫」同一类现象）。
     * 保持当前周期后，[tryRetargetNow] / [planTx] 会落到下一个**我方发射时隙**（`slotIdx+2`）。
     */
    private fun relockParityBeforeManualTx() {
        val inFlight = _status.value.txing || txJob?.isActive == true
        if (!inFlight) relockAutoParityIfNeeded()
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _txRecords.value = emptyList()
    }

    // ---- 我方发射记录（接收列表的 TX 行，docs/Ft8Vox.md） ----

    /**
     * 记一行「我方发射」（TX 行），初始状态 [TxOutcome.PLAYING]。
     *
     * 在真正开始播的那一刻调用（[transmit] / [sendNow]），所以 TX 行**一开播就出现**。
     */
    private fun beginTxRecord(text: String, slotUtcMs: Long): Long {
        val id = ++txRecordSeq
        val rec = TxRecord(
            id = id,
            text = text,
            slotUtcMs = slotUtcMs,
            order = TxCompose.kindOf(text, _status.value.myCall)?.order ?: 0,
        )
        _txRecords.update { (listOf(rec) + it).take(ACTIVITY_LIMIT) }
        return id
    }

    /** 该次发射**已完整播出**。 */
    private fun finishTxRecord(id: Long) {
        _txRecords.update { list ->
            list.map { if (it.id == id) it.copy(outcome = TxOutcome.DONE) else it }
        }
    }

    /**
     * 把**正在播**的那条标为 [TxOutcome.ABORTED]（行尾显示「未发完」）。
     *
     * 不按 id 定位：`abortTransmit()` 可能从主线程调用，而 [beginTxRecord] 在 IO 线程，
     * 共享一个「当前 id」字段反而不安全；同一时刻只可能有一条在播，直接扫状态即可。
     */
    private fun abortPlayingTxRecord() {
        _txRecords.update { list ->
            list.map {
                if (it.outcome == TxOutcome.PLAYING) it.copy(outcome = TxOutcome.ABORTED) else it
            }
        }
    }

    // ---- 前台服务（阶段 9：后台保活） ----

    /**
     * 让前台服务的启停与会话运行状态保持一致。
     *
     * 服务负责两件事：以 `microphone` 类型进入前台（Android 14+ 后台访问麦克风的前提），
     * 以及在通知栏常驻会话状态。会话开始/停止时调用；[serviceSuppressed] 期间跳过。
     */
    private fun syncService() {
        if (serviceSuppressed) return
        if (_status.value.running) {
            if (!serviceOn) {
                try {
                    SessionService.start(getApplication<Application>())
                    serviceOn = true
                } catch (e: Exception) {
                    // Android 12+ 从后台启动前台服务会被系统拒绝；此时保持应用内接收，不让异常上抛
                    _status.update { it.copy(status = "前台服务启动失败：${e.message}") }
                }
            }
        } else if (serviceOn) {
            SessionService.stop(getApplication<Application>())
            serviceOn = false
        }
    }

    /** 把当前会话状态推给前台服务通知（`StateFlow` 只在文案变化时才真正刷新通知）。 */
    private fun publishServiceStatus() {
        SessionServiceBridge.statusText.value = serviceStatusText(_status.value)
    }

    // ---- 采集 ----

    fun start() {
        if (_status.value.running) return
        val protocol = _status.value.protocol
        val decode = latestSettings.decode

        try {
            AudioEngine.initialize(
                Ft8Config(
                    protocol = protocol,
                    fMin = decode.fMinHz.toFloat(),
                    fMax = decode.fMaxHz.toFloat(),
                    timeOsr = decode.timeOsr,
                    freqOsr = decode.freqOsr,
                ),
                decodeParams = latestSettings.decodeParams,
                context = getApplication<Application>(),
            )
        } catch (e: Exception) {
            _status.update { it.copy(status = "初始化失败: ${e.message}") }
            return
        }
        engineGen++   // 新引擎：作废所有还在路上的发射协程（见 transmit 的 genAtPlan）

        wfInfo = AudioEngine.waterfallInfo()
        wfPixels = IntArray(0)
        _waterfall.value = null

        // 引擎刚重建：强制把 VOX/PTT 配置下发给新实例
        applyVox(latestSettings, force = true)
        applyAudio(latestSettings, force = true)
        applySlotOffset(latestSettings, force = true)

        val rate = AudioEngine.startCapture(preferredRate(), inputDeviceId())
        if (rate <= 0) {
            AudioEngine.release()
            wfInfo = null
            _status.update { it.copy(status = "采集启动失败（错误码 $rate）") }
            return
        }

        _status.update {
            it.copy(
                running = true,
                inputRate = rate,
                status = "接收中（设备采样率 $rate Hz）",
                decodedTotal = 0,
            )
        }
        startPolling()
        syncService()
        publishServiceStatus()
    }

    fun stop() {
        stopTransmit()
        scheduler.disable()
        sessionSavedCalls.clear()
        stopPollingAndJoin()
        pinnedTxParity = null
        AudioEngine.stopCapture()
        AudioEngine.release()
        wfInfo = null
        _status.update {
            it.copy(
                running = false,
                inSlot = false,
                status = "已停止",
                msToNextSlot = 0,
                slotProgress = 0f,
                txEnabled = false,
                txArmed = false,
                txing = false,
                manualTxText = null,
                autoPhaseLabel = null,
                autoQueueSize = 0,
                qso = qsoEngine.stop(),
            )
        }
        // 只用停服务收口：不要在这里推送「已停止」文案，否则通知会在服务销毁前被重贴成僵尸通知
        syncService()
    }

    /**
     * 运行中切换协议：**停一下再重建引擎**。
     *
     * 引擎的时隙长度与调制方式在创建时就固定了，没法热改，所以只能重启一次采集。
     * [stop] 会顺手关掉「发送总开关」并结束当前 QSO，这里按切换前的状态把总开关恢复回来
     * （换协议本身不应该收回「能不能发」的授权）。当前仅支持 FT8，本路径保留以备用。
     */
    private fun restartForProtocol(protocol: Protocol) {
        val cur = _status.value
        val txWasOn = cur.txEnabled
        val wasTxing = cur.txing
        // 抑制服务启停：这段 stop()/start() 是原子的，服务无需跟着停一下
        serviceSuppressed = true
        try {
            stop()
            _status.update { it.copy(protocol = protocol, slotMs = protocol.slotMs) }
            start()
        } finally {
            serviceSuppressed = false
        }
        syncService()
        publishServiceStatus()
        if (!_status.value.running) return
        if (txWasOn) setTxEnabled(true)
        _status.update {
            it.copy(
                status = "已切换到 ${protocol.name}（重建引擎" +
                    (if (wasTxing) "，已中止本次发射" else "") + "）",
            )
        }
    }

    // ---- 发射 / QSO ----

    /** 是否已配置呼号，可开始 QSO。 */
    val canOperate: Boolean get() = _status.value.myCall.isNotEmpty()

    /**
     * 开始呼叫 CQ。若采集未启动会先自动启动。
     *
     * 唯一闸门是「发送总开关」（`txEnabled`）：打开即允许发射，UI 侧不再弹确认框。
     * 人工操作**不暂停**自动程序（FT8CN 模型），只是把发射指令改掉并复位发射监管计时。
     */
    fun startCq() {
        scheduler.resetSupervision(AudioEngine.utcNowMs())
        startCqInternal()
    }

    private fun startCqInternal() {
        if (!canOperate) {
            _status.update { it.copy(status = "请先填写呼号") }
            return
        }
        if (!_status.value.txEnabled) {
            _status.update { it.copy(status = "请先打开「发射」开关") }
            return
        }
        if (!_status.value.running) start()
        if (!_status.value.running) return

        relockParityBeforeManualTx()
        if (!armPlayback()) return
        lastTxSlotIndex = -1L
        val p = qsoEngine.startCq(AudioEngine.utcNowMs(), latestSettings.cqPrefix)
        // 开始 QSO 即以 QSO 为准：清掉可能还排着的「一次性待发」报文（否则它会抢占下一个时隙，
        // 把新 QSO 的第一条报文压住、状态机也不推进）
        _status.update {
            it.copy(qso = p, txArmed = true, manualTxText = null, status = "QSO：${p.description}")
        }
        tryRetargetNow(p.txText, manual = false)
    }

    /**
     * 应答指定 CQ / 左滑「呼叫」指定台。人工操作不暂停自动程序，只复位发射监管计时。
     *
     * @param slotUtcMs 被操作那条解码**自己的时隙起点**（毫秒）；0 表示未知。
     *   一定优先用它：`_messages` 里可能混入我方发射的**自听回声**（`对方 我 报文` 被解成
     *   `from=对方`），其所在时隙正是**我方周期**，「回查该台最近解码」会把相反周期算反 ——
     *   真机现象就是发射中左滑「呼叫」停不下来（ANR）、还把新报文发进本应接收的时隙。
     */
    fun answer(call: String, grid: String?, theirDf: Int? = null, slotUtcMs: Long = 0L) {
        scheduler.resetSupervision(AudioEngine.utcNowMs())
        answerInternal(call, grid, theirDf, slotUtcMs)
    }

    private fun answerInternal(call: String, grid: String?, theirDf: Int? = null, slotUtcMs: Long = 0L) {
        if (!canOperate) {
            _status.update { it.copy(status = "请先填写呼号") }
            return
        }
        if (!_status.value.txEnabled) {
            _status.update { it.copy(status = "请先打开「发射」开关") }
            return
        }
        // 同频发射：把红线跟到对方频率（异频发射则保持设定频率）
        if (theirDf != null && theirDf > 0) selectTargetFreq(theirDf)
        if (!_status.value.running) start()
        if (!_status.value.running) return

        // 时隙自动对应：
        // - **正在发射**（本时隙就是我方发射时隙）→ **保持当前发射周期**（按墙钟取当前时隙奇偶），
        //   不按被滑那条解码重锁。被滑的解码可能恰好落在**与我方相同的周期**上（自听回声、
        //   采集窗口错抖等），「取相反周期」会正好翻到紧邻的下一个时隙 —— 也就是本该接收的时隙，
        //   且 in-place 判据必然失败；真机现象就是「停不下来、还发进接收时隙」。
        //   保持当前周期后：够时间 → tryRetargetNow 就地改发；不够 → 排到下一个同周期（我方）时隙。
        // - 没在发射 → 优先用被操作那条解码**自己的时隙**取**相反周期**（拿不到时隙再回退到最近解码）。
        val inFlight = _status.value.txing || txJob?.isActive == true
        if (inFlight) {
            val keep = currentSlotParity()
            pinnedTxParity = keep
            autoParity = keep
        } else {
            val heard = slotUtcMs.takeIf { it > 0L } ?: lastHeardSlotUtcMsOf(call)
            if (pinToTargetSlot(heard) == null) pinnedTxParity = null
        }
        relockAutoParityIfNeeded()
        if (!armPlayback()) return
        lastTxSlotIndex = -1L
        val p = qsoEngine.startResponderQso(call, grid, AudioEngine.utcNowMs())
        if (!p.active) {
            _status.update { it.copy(status = "无法应答（呼号无效或与自身相同）") }
            return
        }
        _status.update {
            it.copy(qso = p, txArmed = true, manualTxText = null, status = "QSO：${p.description}")
        }
        tryRetargetNow(p.txText, manual = false)
    }

    /**
     * 一次性发射一条报文（自定义报文框的「发送」/ 长按解码行的「逐条发送」）。
     *
     * **前置编码校验**（§27）：`nativeEncode` 编不出来的文本（非法报文）当场红字拒绝，不排程 ——
     * 否则要白等一个发射时隙才在编码里报「发射异常」。校验放后台线程（一次完整编码约十几毫秒，
     * 不该占 UI 线程）。
     *
     * 通过后走 [sendOnceInternal]：报文排到**下一个我方时隙**发出（不打断正在跑的 QSO，
     * 见那里的说明）。人工操作不暂停自动程序，只复位发射监管计时。
     */
    fun sendOnce(text: String) {
        scheduler.resetSupervision(AudioEngine.utcNowMs())
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        txBlockReason(_status.value.myCall, _status.value.txEnabled)?.let {
            showTxNotice(it)
            return
        }
        val st = _status.value
        viewModelScope.launch {
            // 编码校验放后台线程（一次完整编码约十几毫秒），**排程仍回到主线程**——
            // 所有发射入口（发送 / 呼号 / 六槽）保持单入口、顺序执行。
            val encodable = withContext(Dispatchers.Default) { canEncodeMessage(trimmed, st) }
            if (!encodable) {
                showTxNotice("FT8 编不出这条报文：$trimmed")
                return@launch
            }
            _status.update { it.copy(txNotice = null) }
            sendOnceInternal(trimmed)
        }
    }

    /**
     * 试编码一次，只判断「这条报文能不能发」（前置校验用，结果丢弃）。
     *
     * `nativeEncode` 在 native 侧**无状态**（不碰解码 handle），可在任意线程安全调用 ——
     * 排程后的正式发射本来也在 IO 线程里调它。
     */
    private fun canEncodeMessage(text: String, st: ReceiverStatus): Boolean = try {
        Ft8Engine.encode(text, st.selectedFreqHz.toFloat(), st.protocol, 12000).isNotEmpty()
    } catch (e: Exception) {
        Log.w(TAG_QSO, "报文无法编码：$text", e)
        false
    }

    /**
     * 在发射区底部显示一条红字提示（一次性发射被拒 / 编不出来的原因），[TX_NOTICE_MS] 后自动消失。
     *
     * 用文本比对作废旧定时器：期间又冒出新提示时，旧定时器不会把新提示清掉。
     */
    private fun showTxNotice(message: String) {
        _status.update { it.copy(txNotice = message) }
        viewModelScope.launch {
            delay(TX_NOTICE_MS)
            _status.update { if (it.txNotice == message) it.copy(txNotice = null) else it }
        }
    }

    /**
     * 排程一条一次性报文（已通过前置校验）。
     *
     * **QSO 进行中也允许**（§27 真机反馈：「插一条、不打断」）：本条报文在 [txTick] 里优先级高于
     * `qso.txText`（`manualTxText ?: qso.txText`），而且发完不推进 QSO 状态机
     * （[txTick] 的 `manualInFlight` 分支），所以原 QSO 只是**暂停一个周期**、接着往下跑。
     * 对方可能因此多等一个周期（真机可接受，也是手动优先的代价）。
     */
    private fun sendOnceInternal(text: String) {
        txBlockReason(_status.value.myCall, _status.value.txEnabled)?.let {
            showTxNotice(it)
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!_status.value.running) start()
        if (!_status.value.running) {
            showTxNotice("音频未启动，无法发射")
            return
        }

        relockParityBeforeManualTx()
        if (!armPlayback()) {
            showTxNotice("音频输出未就绪，无法发射")
            return
        }
        lastTxSlotIndex = -1L
        _status.update {
            it.copy(
                manualTxText = trimmed,
                txArmed = true,
                txNotice = null,
                status = "待发（一次性）：$trimmed",
            )
        }
        tryRetargetNow(trimmed, manual = true)
    }

    /**
     * 立即发射一条报文（**不按时隙对齐**，用于发射抽屉的「立即发」）。
     *
     * 正式按时隙发射请用 [sendOnce]（排到下一个我方周期）。
     */
    fun sendNow(text: String) {
        if (!canOperate) {
            _status.update { it.copy(status = "请先填写呼号") }
            return
        }
        if (!_status.value.txEnabled) {
            _status.update { it.copy(status = "请先打开「发射」开关") }
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!_status.value.running) start()
        if (!_status.value.running) return
        if (!armPlayback()) return

        abortTransmit()   // 作废可能正在响的那一段（非阻塞）
        val (pttMs, leadMs) = txPreambleParts()
        val abortAtPlan = txAbortGen
        txJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val st = _status.value
                val pcm = Ft8Engine.encode(trimmed, st.selectedFreqHz.toFloat(), st.protocol, 12000)
                if (abortAtPlan != txAbortGen) return@launch   // 期间被「停止发射」：丢弃
                // TX 行（§37）：「立即发」不对齐时隙，用当前 UTC 当作时间轴上的位置。
                val recId = beginTxRecord(trimmed, AudioEngine.utcNowMs())
                _status.update { it.copy(txing = true, lastTxText = trimmed, lastTxSlotMs = 0) }
                val written = AudioEngine.playTx(pcm, pttMs, leadMs)
                if (abortAtPlan == txAbortGen && written > 0) {
                    finishTxRecord(recId)
                } else {
                    abortPlayingTxRecord()
                }
                _status.update {
                    it.copy(
                        status = if (written > 0) "已发射「$trimmed」（$written 帧）"
                        else "发射失败：$OUT_STALLED_FAIL",
                    )
                }
            } catch (e: Exception) {
                _status.update { it.copy(status = "发射失败: ${e.message}") }
            } finally {
                _status.update { it.copy(txing = false) }
            }
        }
    }

    /**
     * 紧急停止发射：解除武装并中止可能正在进行的播放。
     *
     * **只停本次发射，不动自动程序**：发送总开关仍开着时，下一时隙自动程序会继续
     * （要全停请关「发送总开关」）。
     *
     * 中止走 [AudioEngine.abortTx]（非阻塞、不加锁）：**不要**在这里调
     * `AudioEngine.stopPlayback()` —— 关流要等 `tx_mutex`，而阻塞式 `AAudioStream_write`
     * 会整段独占它（FT8 约 16 s），UI 线程一调用就卡死（ANR）。
     */
    fun stopTransmit() {
        abortTransmit()
        pendingAutoFinish = false
        autoParity = null
        val p = qsoEngine.stop()
        _status.update {
            it.copy(
                txArmed = false,
                txing = false,
                manualTxText = null,
                qso = p,
                txCountdownMs = 0,
                autoPhaseLabel = if (it.txEnabled) autoPhaseText() else null,
                autoQueueSize = if (it.txEnabled) scheduler.queuedCount() else 0,
                status = if (it.txEnabled) {
                    "已停止发射｜自动程序仍开启（要全停请关「发送总开关」）"
                } else {
                    "已停止发射"
                },
            )
        }
    }

    /**
     * 作废当前发射（取消协程 + 非阻塞中止 native 写入 + 记一次作废代次）。
     *
     * 可在主线程调用；返回后当前报文最多再响一块（约 85 ms）就会停。
     */
    private fun abortTransmit() {
        txJob?.cancel()
        txJob = null
        txAbortGen++
        abortPlayingTxRecord()   // 被作废的那次发射 → TX 行标「未发完」（§37）
        AudioEngine.abortTx()
    }

    /**
     * 立即播放一段测试波形（**不按时隙对齐**，仅用于验证音频通路与音量）。
     *
     * 正式发射请用 [startCq] / [answer]。
     */
    fun transmitTest() {
        val st = _status.value
        val text = st.qso.txText
            ?: if (canOperate) listOf("CQ", st.myCall, st.myGrid).filter { it.isNotEmpty() }.joinToString(" ")
            else "CQ TEST"
        if (!armPlayback()) return
        abortTransmit()   // 作废可能正在响的那一段（非阻塞）
        val (pttMs, leadMs) = txPreambleParts()
        val genAtPlan = engineGen
        val abortAtPlan = txAbortGen
        txJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (genAtPlan != engineGen) return@launch   // 期间重建过引擎：丢弃
                val pcm = Ft8Engine.encode(text, st.selectedFreqHz.toFloat(), st.protocol, 12000)
                if (abortAtPlan != txAbortGen) return@launch   // 期间被「停止发射」：丢弃
                _status.update { it.copy(txing = true) }
                val written = AudioEngine.playTx(pcm, pttMs, leadMs)
                _status.update {
                    it.copy(
                        status = if (written > 0) "已发射测试「$text」（$written 帧）"
                        else "发射失败：$OUT_STALLED_FAIL",
                    )
                }
            } catch (e: Exception) {
                _status.update { it.copy(status = "发射失败: ${e.message}") }
            } finally {
                _status.update { it.copy(txing = false) }
            }
        }
    }

    /** 打开播放流（复用同一流，避免发射瞬间才建流导致错过时隙）。 */
    private fun armPlayback(): Boolean {
        // 正在发射时流必然已打开（playTx 正在写）：直接复用，**不要**调 AudioEngine.startPlayback ——
        // 它的 writeLock 被整段播放（约 13 s）占着，主线程等锁会卡死（ANR，真机现象：发射中左滑
        // 「呼叫」→ answerInternal → armPlayback）。设备 / 采样率变更已在 applyAudio 里延后到
        // 「下次发射」，下一段（txing=false）会走下面的重建。
        if (_status.value.txing) return true
        val rate = AudioEngine.startPlayback(preferredRate(), outputDeviceId())
        if (rate <= 0) {
            _status.update { it.copy(status = "播放启动失败（错误码 $rate）") }
            return false
        }
        _status.update { it.copy(outputRate = rate) }
        return true
    }

    /** 音频设备采样率偏好：0（自动）回落到 48000。 */
    private fun preferredRate(): Int =
        latestSettings.sampleRate.hz.takeIf { it > 0 } ?: 48000

    /** 设置里保存的输入设备 id（0=系统默认）。 */
    private fun inputDeviceId(): Int = AudioDevices.parseId(latestSettings.inputDevice)

    /** 设置里保存的输出设备 id（0=系统默认）。 */
    private fun outputDeviceId(): Int = AudioDevices.parseId(latestSettings.outputDevice)

    // ---- 轮询主循环 ----

    /**
     * 停止轮询并**等它真正退出** —— 销毁引擎（`AudioEngine.release()` → nativeDestroy
     * 会 `free` 引擎）之前必须调用。
     *
     * 轮询跑在 `Dispatchers.Default`：`cancel()` 只是置标志位，如果它正卡在
     * `pollDecoded()/state()` 这类 JNI 调用里，取消并不会打断它，而 native 侧的
     * 播放保护（`tx_enter/tx_wait_idle`）**管不到轮询**。`join()` 通常立刻返回
     * （最多个位数毫秒），代价可以接受。
     */
    private fun stopPollingAndJoin() {
        val job = pollJob ?: return
        pollJob = null
        job.cancel()
        runBlocking { job.join() }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    _status.update { it.copy(status = "轮询异常: ${e.message}") }
                }
                delay(80)
            }
        }
    }

    private fun pollOnce() {
        // 1) 解码结果（新→旧）
        val decoded = AudioEngine.pollDecoded()
        if (decoded.isNotEmpty()) {
            _messages.update { current -> (decoded + current).take(ACTIVITY_LIMIT) }
            _status.update { it.copy(decodedTotal = it.decodedTotal + decoded.size) }
            pendingDecodes.addAll(decoded)
            // 「自动跟踪 CQ（本波段）」：把本批新解到的本波段未通联 CQ 呼号并入跟踪名单
            autoCollectCqToFollow(decoded)
            // U7d：有报文叫我呼号时短促提示
            val my = _status.value.myCall
            if (shouldAlertMyCall(latestSettings.beepOnMyCall, my, decoded.map { it.text })) {
                alertTone.beep()
            }
        }

        // 2) 状态
        //
        // 性能（2026-09-26）：`msToNextSlot` / `slotProgress` / `voxLevelDb` 这些「实时读数」
        // native 每个轮询周期（80ms）都在变。若原样发布，`_status` 会以 12.5 Hz 发射，进而让
        // MainShell / 顶栏 / 底栏 / 操作页 / 发射抽屉 —— 即**每一页**（含没有瀑布的日志页、设置页）
        // 整屏 12.5 Hz 全量重组 + 重绘（模拟器实测各页均 105 帧 / 8 s，操作页主线程约 11% CPU，
        // 是全机最大开销）。它们在 UI 上都只是粗略的「仪表读数」：时隙进度条只有 3dp 高、
        // 电平只显示整数 dB、剩余时间只用于 2500ms 的「能否立即发送」阈值判断 —— 5 Hz 刷新足够。
        // 因此统一节流 + 量化后再发布；窗口未到时沿用旧值，`copy` 结果相等 → StateFlow 不发射。
        val s = AudioEngine.state()
        if (s != null) {
            val nowLiveMs = s.utcNowMs
            val live = nowLiveMs - lastLivePublishMs >= LIVE_PUBLISH_INTERVAL_MS
            if (live) lastLivePublishMs = nowLiveMs
            _status.update {
                it.copy(
                    inSlot = s.inSlot,
                    inputRate = s.inputRate,
                    slotMs = s.slotMs.toInt(),
                    msToNextSlot =
                        if (live) s.msToNextSlot / LIVE_STEP_MS * LIVE_STEP_MS else it.msToNextSlot,
                    slotProgress =
                        if (live) (s.slotProgress * LIVE_PROGRESS_STEPS).toInt() / LIVE_PROGRESS_STEPS else it.slotProgress,
                    slotParity = slotParityOf(s.utcNowMs, s.slotMs) ?: 0,
                    slotsDecoded = s.slotsDecoded,
                    lastDecodeMs = s.lastDecodeMs,
                    droppedSamples = s.droppedSamples,
                    voxLevelDb = if (live) s.voxLevelDb else it.voxLevelDb,
                )
            }

            // 3) 每完成一个接收时隙，交给 QSO 状态机或自动程序
            if (s.slotsDecoded > lastSlotsDecoded) {
                lastSlotsDecoded = s.slotsDecoded
                // 记下「已处理到哪个时隙的解码」：txTick 靠它避免用上一时隙的旧报文抢发本时隙
                lastDecodedSlotIndex = s.lastDecodedSlot
                lastDecodeAtMs = s.utcNowMs
                val st = _status.value
                val batch = pendingDecodes.toList()
                pendingDecodes = mutableListOf()
                val slotMs = s.slotMs.toLong()
                // 自己发出的信号：**逐条**过滤掉「落在我方刚发射那个时隙」的解码。
                // 不能用本批时隙最大值整批丢弃 —— 本批若同时含自听信号与对方的回复，
                // 会把对方的回复一起丢掉，导致状态机收不到回应而反复重发 / 两端跑死。
                val txSlot = lastTxSlotIndex
                val incoming = if (txSlot < 0 || slotMs <= 0) {
                    batch
                } else {
                    batch.filter { m ->
                        val idx = if (m.slotUtcMs > 0) m.slotUtcMs / slotMs else Long.MIN_VALUE
                        idx != txSlot
                    }
                }
                // 静默时隙（空批）仍要交给调度器推进「连续无回应」计数；
                // 只有「本批解码全部属于我方发射时隙」才整体跳过。
                val onlyOwnTx = batch.isNotEmpty() && incoming.isEmpty()
                // 发射监管（方案 §3.5）：每个接收批次都检查一次（含正在进行的 QSO 与仅自听批次）；
                // 触发即关闭「发送总开关」，避免对静默伙伴无限重试而跑死。
                val supervisionReason =
                    if (st.txEnabled) scheduler.checkSupervision(s.utcNowMs) else null
                if (supervisionReason != null) {
                    stopAutoBySupervision(supervisionReason)
                } else if (!onlyOwnTx) {
                    if (st.qso.active) {
                        if (st.qso.awaitingResponders) {
                            // 第 2 层「已发 CQ、等回应者」阶段：解码交给调度器收集/排序回应者
                            runAutoProgram(incoming, s.utcNowMs)
                        } else {
                            val p = qsoEngine.onDecoded(incoming, s.utcNowMs)
                            Log.i(
                                TAG_QSO,
                                "engine n=${incoming.size} their=${st.qso.theirCall} " +
                                    "state=${st.qso.state} -> ${p.state} noReply=${p.noReplyCount} " +
                                    "msgs=${incoming.map { it.text }}",
                            )
                            applyQsoProgress(p, s.utcNowMs, incoming)
                            // FT8CN `checkCQMeOrFollowCQMessage` 循环 2：当前目标本批沉默时，
                            // 也要应答「其他呼叫我方」的定向台（避免忙起来就漏应答）。
                            val takeover = if (p.active && !p.advanced && _status.value.txEnabled) {
                                scheduler.directedTakeover(
                                    incoming,
                                    p.theirCall,
                                    currentFilter(),
                                    workedForAutoProgram(),
                                )
                            } else {
                                null
                            }
                            if (takeover != null) {
                                Log.i(TAG_QSO, "听到其他定向呼叫 → 换台 ${takeover.call}（${takeover.kind}）")
                                startAutoTarget(takeover)
                            } else {
                                // 本批没有其他定向呼叫；「无回应 / 对方转呼别人」的兜底判据
                                // 已在 QsoEngine 内（照 FT8CN 的 5 路完成判据）判定并收尾，
                                // 收尾动作由 applyQsoProgress 按 p.gaveUp 派发。
                            }
                        }
                    } else if (st.txEnabled && st.qso.txText == null) {
                        runAutoProgram(incoming, s.utcNowMs)
                    } else if (st.txEnabled) {
                        // 「QSO 已结束、收尾报文（RR73/73）还没真正发出去」：本时隙不启动新 QSO，
                        // 否则 startCq/应答会 reset 引擎并把 qso.txText 覆盖成 CQ，这条收尾报文
                        // 就永远发不出去了（真机现象：对方给我 RR73，我没回 73）。
                        Log.i(TAG_QSO, "收尾报文待发 ${st.qso.txText}：本时隙不启动新 QSO")
                    }
                }
            }
        }

        // 4) 瀑布行流
        pollWaterfall()

        // 5) 发射调度
        txTick()

        // 6) 前台服务通知（文案不变时 StateFlow 不会重复刷新）
        publishServiceStatus()
    }

    /**
     * 把状态机进度同步到 UI 状态、写入日志，并收口第 2 层调度。
     *
     * QSO 结束时（成功/作废）：
     * - 还有最后一条 RR73/73 要发 → 置 [pendingAutoFinish]，等它**真正发出去**后在 [txTick]
     *   里收口；期间第 2 层既不会 `onQsoFinished`，`pollOnce` 也不会启动新 QSO —— 否则
     *   `startCq` 会 reset 引擎把这条收尾报文覆盖掉（真机现象：收到 RR73 不回 73）。
     * - 没有收尾报文 → 立刻通知第 2 层（[AutoScheduler.onQsoFinished]）决定下一步。
     * - 发送总开关关闭时不通知第 2 层（自动程序已停）。
     *
     * 落库：**会话内同一呼号只写一条**（收到 73/RR73 立即落库、不弹确认、不关 TX），
     * 完成即写「已通联」索引（方案 §4.2 / §4.4）。
     */
    private fun applyQsoProgress(
        p: QsoProgress,
        utcNowMs: Long = AudioEngine.utcNowMs(),
        incoming: List<DecodeResult> = emptyList(),
    ) {
        val finished = p.state == QsoState.DONE || p.state == QsoState.FAILED
        // 完成时**保留发射周期**：还要用同一周期把最后一条 RR73/73 发出去；
        // 失败时只解除「目标时隙固定」（保留当前周期，避免下一段跳时隙）。
        if (p.state == QsoState.FAILED) pinnedTxParity = null
        _status.update { it.copy(qso = p, status = "QSO：${p.description}") }
        qsoEngine.consumeCompleted()?.let { entry: QsoLogEntry ->
            // 会话内去重（取代旧的 90s 时间窗）：同一呼号一段会话只落一条。
            // 只影响**写日志**，不影响「有人呼叫我方一定应答」。
            val key = entry.theirCall.trim().uppercase()
            if (!sessionSavedCalls.add(key)) {
                _status.update { it.copy(status = "已忽略重复通联记录：${entry.theirCall}") }
                return@let
            }
            val st = _status.value
            val entity = QsoEntity(
                theirCall = entry.theirCall,
                theirGrid = entry.theirGrid,
                myCall = st.myCall,
                myGrid = st.myGrid.ifEmpty { null },
                utcMs = entry.utcMs,
                startUtcMs = entry.startUtcMs,
                band = st.band,
                freqHz = st.dialHz,
                mode = st.protocol.name,
                reportSent = entry.reportSent,
                reportReceived = entry.reportReceived,
                // 默认 COMMENT：台站备注（快照，可留空）+ 自动距离备注
                //（仿 FT8CN：`Distance: 1738 km, QSO by Ft8Vox`）
                comment = QsoComment.auto(
                    stationNote = latestSettings.note,
                    myGrid = st.myGrid.ifEmpty { null },
                    theirGrid = entry.theirGrid,
                ),
            )
            viewModelScope.launch(Dispatchers.IO) {
                qsoRepo.add(entity)
            }
            // 完成即写「已通联」索引（立即生效；DB 回流会幂等重算）
            _worked.update { it.plus(entry.theirCall, entry.theirGrid) }
            // 自动程序用的「本波段」索引也立刻更新（否则要等 Room 回流才生效）
            val bandKey = st.band.trim().uppercase()
            workedCallsByBand = workedCallsByBand +
                (bandKey to (workedCallsByBand[bandKey].orEmpty() + key))
            // 「跟踪的呼号通联完成后取消跟踪」：本段通联已落库，无需再盯这个台
            // （名单与自动收录顺序一并移除；幂等）
            val wasFollowed = key in latestSettings.followCalls
            if (wasFollowed) {
                persist {
                    it.copy(followCalls = it.followCalls - key, autoFollowOrder = it.autoFollowOrder - key)
                }
            }
            _status.update {
                it.copy(
                    status = if (wasFollowed) {
                        "已记录通联：${entry.theirCall}（已取消跟踪）"
                    } else {
                        "已记录通联：${entry.theirCall}"
                    },
                )
            }
        }
        if (!finished) return
        if (!_status.value.txEnabled) return
        when {
            // 照 FT8CN 的兜底判据收尾（发过 RR73 后对方消失 / 转呼别人）→ **优先换台**，
            // 没有可换的台才回 CQ（FT8CN `getNewTargetCallsign`）。
            p.gaveUp -> runAutoAction(
                scheduler.onTargetGaveUp(incoming, currentFilter(), workedForAutoProgram(), utcNowMs),
            )
            // 没有收尾报文：立刻让第 2 层决定下一步
            p.txText == null -> runAutoAction(scheduler.onQsoFinished(utcNowMs))
            // 还有 RR73/73 要发：发完再收口（见 txTick）
            else -> pendingAutoFinish = true
        }
    }

    /**
     * 第 2 层：在「无进行中 QSO」或「已发 CQ 等回应者」时，把本时隙解码交给调度器，
     * 并按返回的动作执行（发 CQ / 应答 CQ / 处理定向报文 / 发射监管停止）。
     */
    private fun runAutoProgram(batch: List<DecodeResult>, utcNowMs: Long) {
        val st = _status.value
        if (!st.txEnabled) return
        val action = scheduler.onDecoded(
            messages = batch,
            filter = currentFilter(),
            worked = workedForAutoProgram(),
            utcNowMs = utcNowMs,
        )
        Log.i(
            TAG_QSO,
            "auto n=${batch.size} q=${scheduler.queuedCount()} -> ${action::class.simpleName} " +
                "msgs=${batch.map { it.text }}",
        )
        publishAutoState()
        runAutoAction(action)
    }

    /** 执行第 2 层给出的动作。 */
    private fun runAutoAction(action: AutoAction) {
        when (action) {
            AutoAction.SendCq -> startCqInternal()
            is AutoAction.AnswerCq -> startAutoTarget(action.target)
            is AutoAction.HandleDirected -> startAutoTarget(action.target)
            AutoAction.Listen -> {
                // 暂无 CQ 台可应答：保持接收、不发
                if (_status.value.txArmed || _status.value.qso.active) {
                    val p = qsoEngine.stop()
                    _status.update {
                        it.copy(qso = p, txArmed = false, status = "自动程序：监听中（等待其他人的 CQ）")
                    }
                }
            }
            is AutoAction.Stop -> stopAutoBySupervision(action.reason)
            AutoAction.None -> Unit
        }
    }

    /**
     * 发射监管触发（方案 §3.5）：**关闭发送总开关**（`txEnabled=false`）并停止本次发射。
     *
     * 重新打开总开关即恢复自动运行（监管计时重新开始）；总开关不持久化，重启 App 不会自动发射。
     */
    private fun stopAutoBySupervision(reason: String) {
        scheduler.markProtectedStop()
        setTxEnabled(false)
        _status.update { it.copy(status = "自动程序已停止（$reason）：发送总开关已关闭") }
    }

    /** 把第 2 层队列/监管状态同步到 UI 状态。 */
    private fun publishAutoState() {
        val st = _status.value
        val label = if (st.txEnabled) autoPhaseText() else null
        val q = if (st.txEnabled) scheduler.queuedCount() else 0
        if (st.autoPhaseLabel != label || st.autoQueueSize != q) {
            _status.update { it.copy(autoPhaseLabel = label, autoQueueSize = q) }
        }
    }

    /**
     * 自动应答选中的目标，并把整段 QSO 交给状态机跑完。
     *
     * 支持五类目标：[AutoTargetKind.CQ]（应答 CQ）、[AutoTargetKind.CALL]（对方呼叫我）、
     * [AutoTargetKind.REPORT]（对方给我报告）、[AutoTargetKind.ROGER]（对方 Roger 我的报告）、
     * [AutoTargetKind.RR73]（对方直接给我 RR73 → 我回 73 收尾）。
     */
    private fun startAutoTarget(t: AutoTarget) {
        val st = _status.value
        // 时隙自动对应：正在发射时**保持当前周期**（同 answerInternal，免得翻到接收时隙）；
        // 否则固定到对方时隙的相反周期。
        val inFlight = _status.value.txing || txJob?.isActive == true
        if (inFlight) {
            val keep = currentSlotParity()
            pinnedTxParity = keep
            autoParity = keep
        } else if (pinToTargetSlot(t.slotUtcMs) == null) {
            pinnedTxParity = null
        }
        relockAutoParityIfNeeded()
        if (!armPlayback()) return

        lastTxSlotIndex = -1L
        // 同频发射：把红线跟到目标频率（异频发射则保持设定频率）
        if (st.sameFreqTx) setTxFreq(t.df)

        val nowMs = AudioEngine.utcNowMs()
        val p = when (t.kind) {
            AutoTargetKind.CQ -> qsoEngine.startResponderQso(t.call, t.grid, nowMs, t.snr)
            AutoTargetKind.CALL -> qsoEngine.startCallerQso(t.call, t.grid, t.snr, nowMs)
            AutoTargetKind.REPORT -> qsoEngine.respondToReport(t.call, t.report ?: return, t.snr, nowMs)
            AutoTargetKind.ROGER -> qsoEngine.respondToRoger(t.call, t.report ?: return, t.snr, t.slotUtcMs)
            AutoTargetKind.RR73 -> qsoEngine.respondToRr73(t.call, t.snr, t.slotUtcMs)
        }
        if (!p.active && p.state != QsoState.DONE) return
        Log.i(TAG_QSO, "auto start kind=${t.kind} call=${t.call} -> ${p.state} tx=${p.txText}")
        _status.update {
            it.copy(qso = p, txArmed = true, status = "自动程序：应答 ${t.call}")
        }
        tryRetargetNow(p.txText, manual = false)
        // ROGER / RR73 场景一上来即完成：走统一收尾（写日志 + 通知第 2 层）
        if (p.state == QsoState.DONE) applyQsoProgress(p)
    }

    /**
     * 人工菜单「回复」：对一条解码按**报文类型**给出正确回复（与自动程序同一条路径）。
     *
     * CQ → 应答；定向网格 → 发报告；定向报告 → 回 R 报告；定向 R → 回 RR73 收尾。
     * 人工操作**不暂停**自动程序，只复位发射监管计时（FT8CN 口径）。
     *
     * @return false 表示该行不能作为 QSO 起点（自听 / `73` 类 / 非定向非 CQ / 未开「发送总开关」）
     */
    fun replyTo(row: DecodeResult): Boolean {
        val t = AutoProgramSelector.toTarget(row, _status.value.myCall) ?: run {
            _status.update { it.copy(status = "该报文不能作为通联起点（自听 / 73 收尾 / 非定向非 CQ）") }
            return false
        }
        if (!_status.value.txEnabled) {
            _status.update { it.copy(status = "请先打开「发射」开关") }
            return false
        }
        scheduler.resetSupervision(AudioEngine.utcNowMs())
        startAutoTarget(t)
        return true
    }

    /**
     * 显示 / 自动程序共用的呼号名单（docs/Ft8Vox.md）。
     *
     * 操作页的筛选与搜索已整体删除，因此这里**不再带 tags / query**（恒为 `ALL` + 空搜索），
     * 只保留忽略名单（隐藏）与跟踪名单（自动程序例外）。`AppSettings.filterTags` / `callFilter`
     * 两个字段仅为兼容旧设置保留，界面已无入口。
     */
    private fun currentFilter(): DecodeFilterState {
        val s = latestSettings
        return DecodeFilterState(
            ignoredCalls = s.ignoredCalls,
            followedCalls = s.followCalls,
        )
    }

    /** 指定波段的已通联呼号（照 FT8CN `checkQSLCallsign`）；自动程序的 CQ 闸门用。 */
    private fun workedCallsOnBand(band: String): Set<String> =
        workedCallsByBand[band.trim().uppercase()].orEmpty()

    /**
     * 自动程序用的已通联索引：**只含当前波段**。
     *
     * 照 FT8CN `checkQSLCallsign`（按 band 过滤）——跨波段通联过的 CQ 台在本波段仍会作为候选
     * （波段新槽位）；界面高亮 / 「已通联」筛选另用跨波段的 [_worked]。
     */
    private fun workedForAutoProgram(): WorkedIndex =
        WorkedIndex(calls = workedCallsOnBand(_status.value.band))

    /**
     * 当前待发报文是否要「等刚结束那个时隙的解码」才能定下来。
     *
     * 只要 QSO 状态机还在进行中（`active`），本时隙发什么就要等这一批解码：
     * - **等对方回复**：解码可能推进/收尾（如 `WAIT_RR73` 收到 RR73 → 要发 73）；
     * - **已发 CQ、等回应者**：解码里可能有人应答或定向呼叫我，此时应**应答**而不是再发一遍 CQ
     *   （真机现象：对端在 `1` 时隙呼叫我，我在 `0` 时隙仍发 CQ，隔一个周期才应答）。
     *
     * 反之，收尾报文（DONE 下的 RR73/73）与手动一次性发送的文本不随后续解码变化，不拦。
     * 见 [txTick]：拦的只是「提前抢发」，解码到手后照发。
     */
    private fun txTextAwaitsDecode(): Boolean = _status.value.qso.active

    /** 把频率钳制到当前解码频段内。 */
    private fun clampFreq(hz: Int): Int {
        val d = latestSettings.decode
        val lo = d.fMinHz.coerceAtLeast(100)
        val hi = d.fMaxHz.coerceAtLeast(lo + 100)
        return hz.coerceIn(lo, hi)
    }

    /** 发射调度：只在我方周期、时隙起始窗口内、且该时隙尚未发射过时触发。 */
    private fun txTick() {
        // 上一轮发射结束后，在轮询线程统一推进状态机（保证 QsoEngine 单线程访问）
        if (txJustFinished) {
            txJustFinished = false
            if (manualInFlight) {
                // 一次性发射：**不推进 QSO 状态机**（真机语义：自定义报文只是「插一条」）
                manualInFlight = false
                val p = qsoEngine.progress()
                val stillRunning = qsoStillNeedsTx(p)
                _status.update {
                    val done = "一次性发射完成：${it.lastTxText ?: ""}"
                    it.copy(
                        manualTxText = null,
                        // 「插一条」不打断 QSO（§27）：QSO 还没走完（含收尾报文待发）→ 保留武装，
                        // 下一周期接着发原序列；空闲时的一次性发射则发完即解除武装。
                        txArmed = stillRunning && it.txEnabled,
                        qso = p,
                        status = if (p.active) "$done｜QSO 继续" else done,
                    )
                }
            } else {
                // 实际发出去的文本：本时隙发射可能在前导提前量到点时就用旧报文排定了，
                // 而上一时隙的解码（可能把 qso 推进到收尾的 73/RR73）随后才处理 ——
                // 把真实文本交给引擎，它就不会把还没发出去的收尾报文误清掉。
                val sentText = _status.value.lastTxText
                qsoEngine.onTransmitted(sentText, AudioEngine.utcNowMs())
                val p = qsoEngine.progress()
                val finished = p.state == QsoState.DONE || p.state == QsoState.FAILED
                // 收尾报文（RR73/73）**还没真正发出去** → 保留武装与周期，等下一个我方时隙。
                val finalPending = finished && p.txText != null
                if (finished && !finalPending) {
                    // 最后一条已发完 / 本就没有收尾报文：只解除「目标时隙固定」，
                    // **保留当前发射周期**——下一段 QSO 沿用同一周期。
                    // 若在这里把周期清掉，下一段会按当前时间重锁，导致偶/奇来回跳时隙。
                    pinnedTxParity = null
                }
                _status.update {
                    it.copy(qso = p, txArmed = if (qsoStillNeedsTx(p)) it.txArmed else false)
                }
                if (finalPending) {
                    Log.i(TAG_QSO, "收尾报文未发完：${p.txText}，保持发射、暂不启动新 QSO")
                } else if (finished && pendingAutoFinish) {
                    // 收尾报文已发出：现在才让第 2 层决定下一步，避免覆盖掉这条 RR73/73
                    pendingAutoFinish = false
                    runAutoAction(scheduler.onQsoFinished(AudioEngine.utcNowMs()))
                }
            }
        }

        var st = _status.value
        if (!st.txEnabled || !st.txArmed || !st.running) return
        if (autoParity == null) {
            relockAutoParityIfNeeded()
            st = _status.value
        }
        if (txJob?.isActive == true) return
        val text = st.manualTxText ?: st.qso.txText ?: return
        val slotMs = st.slotMs.toLong()
        if (slotMs <= 0) return

        val now = AudioEngine.utcNowMs()

        // 前导（PTT 静音 + 发射前导音）：需要提前 preamble 启动播放，数据才落在时隙起点
        val preambleMs = txPreambleMs().toLong()
        val plan = planTx(
            now, slotMs, st.txParity, preambleMs,
            slotOffsetMs = latestSettings.slotOffsetMs.toLong(),
            messageMs = st.protocol.messageMs.toLong(),
        )

        // 距数据起点的倒计时（用于 UI）
        _status.update { it.copy(txCountdownMs = maxOf(0L, plan.targetStartMs - now)) }

        if (plan.targetSlotIndex != lastTxDebugSlot) {
            lastTxDebugSlot = plan.targetSlotIndex
            val off = latestSettings.slotOffsetMs.toLong()
            val line = "txTick 评估：slot=${plan.targetSlotIndex} lastTxSlot=$lastTxSlotIndex " +
                "startAt=${plan.startAtMs} now=$now late=${now - plan.startAtMs} parity=${st.txParity} " +
                "offset=$off wallSlot=${Math.floorDiv(now - off, slotMs)} " +
                "lastDecodedSlot=$lastDecodedSlotIndex lastDecodeAt=$lastDecodeAtMs " +
                "waitDecode=${txTextAwaitsDecode() && txShouldWaitForDecode(plan.targetSlotIndex, plan.targetSlotIndex * slotMs + off, lastDecodedSlotIndex, now, lastDecodeAtMs, slotMs)} " +
                "text=$text"
            Log.i(TAG_QSO, line)
            Diag.line(line)
        }

        if (plan.targetSlotIndex == lastTxSlotIndex) return

        // **QSO 进行中**时，不能用上一时隙的旧报文抢发本时隙：本时隙该发什么，取决于上一时隙
        // 的解码 —— ① 等对方回复时可能推进/收尾（`WAIT_RR73` 收到 RR73 → 要发 73）；
        // ② 已发 CQ 等回应者时可能有人呼叫我（应改判为应答，而不是再发一遍 CQ）。
        // 真机时序是：解码要等时隙末尾才出结果，比前导提前量（`plan.startAtMs`）晚；抢发会让
        // 旧报文占据该时隙（阻塞写要播十几秒，期间无法改发），新报文被挤到下下个周期——
        // 现象就是「对方给我 RR73 我没回 73」与「别人呼叫我，我却还在发 CQ」。
        //
        // 等到该解码处理完再发：此时 `planTx` 走「就地发射」路径，前导仍然完整，只是数据起点
        // 后移几十毫秒（对端 DT 偏移很小）；若解码改判了报文，`startCqInternal`/`answerInternal`/
        // `startAutoTarget` 会把 `lastTxSlotIndex` 复位，同一时隙仍能发出去。
        // 收尾报文 / 手动一次性发送的文本不随后续解码变化，不拦；解码停摆（超过两个时隙没推进）
        // 时也不拦，免得把自己锁死。
        if (txTextAwaitsDecode() &&
            txShouldWaitForDecode(
                targetSlotIndex = plan.targetSlotIndex,
                targetSlotStartMs = plan.targetSlotIndex * slotMs + latestSettings.slotOffsetMs.toLong(),
                lastDecodedSlotIndex = lastDecodedSlotIndex,
                nowMs = now,
                lastDecodeAtMs = lastDecodeAtMs,
                slotMs = slotMs,
            )
        ) {
            return
        }

        if (now < plan.startAtMs) return

        // 已晚于计划起点：用缩水的前导补偿，补偿不够则放弃本时隙
        val lateness = now - plan.startAtMs
        if (lateness > preambleMs + TX_START_WINDOW_MS) return
        val effectivePreamble = effectivePreambleMs(preambleMs, lateness)

        lastTxSlotIndex = plan.targetSlotIndex
        manualInFlight = st.manualTxText != null
        val genAtPlan = engineGen
        val abortAtPlan = txAbortGen
        Log.i(TAG_QSO, "txTick 发射：text=$text slot=${plan.targetSlotIndex} " +
            "startAt=${plan.targetStartMs} late=$lateness preamble=$effectivePreamble manual=$manualInFlight")
        Diag.line("txTick 发射：text=$text slot=${plan.targetSlotIndex} " +
            "startAt=${plan.targetStartMs} late=$lateness preamble=$effectivePreamble manual=$manualInFlight")
        txJob = viewModelScope.launch(Dispatchers.IO) {
            transmit(text, plan.targetStartMs, effectivePreamble, genAtPlan, abortAtPlan)
        }
    }

    /** 设置里的完整前导组成：PTT 前导静音与发射前导音（ms）。 */
    private fun txPreambleParts(): Pair<Int, Int> {
        val s = latestSettings
        val ptt = s.pttDelayMs.coerceAtLeast(0)
        val lead = if (s.txLeadTone) s.txLeadToneMs.coerceAtLeast(0) else 0
        return ptt to lead
    }

    /** 本次发射的完整前导时长（ms）。 */
    private fun txPreambleMs(): Int = latestSettings.txPreambleMs

    /**
     * 阻塞式播放一段发射波形（调用线程为 IO，不阻塞 UI 与轮询）。
     *
     * [genAtPlan] 是排程时的引擎代次：若期间引擎被重建（例如切了协议），**直接丢弃**本次
     * 发射 —— `txJob.cancel()` 打断不了已经开始的 JNI 阻塞写入，只能用代次判断，否则会
     * 把上一个协议的波形写到新引擎上。
     *
     * [abortAtPlan] 是排程时的「发射作废代次」（见 [txAbortGen]）：期间用户按了「停止发射」
     * 就丢弃 —— 否则已排程的这次仍会响一整段。
     */
    private fun transmit(
        text: String,
        slotStartMs: Long,
        preambleMs: Long,
        genAtPlan: Int,
        abortAtPlan: Int,
    ) {
        val st = _status.value
        val (pttMs, leadMs) = txPreambleParts()
        // 缩水后的前导：优先保证前导音（键控 VOX），剩余给前导静音
        val eff = preambleMs.coerceAtLeast(0L)
        val effLead = minOf(leadMs.toLong(), eff).toInt()
        val effPtt = minOf(pttMs.toLong(), eff - effLead).toInt()

        if (genAtPlan != engineGen || abortAtPlan != txAbortGen) return
        Log.i(TAG_QSO, "transmit：text=$text slotStart=$slotStartMs ptt=$effPtt lead=$effLead " +
            "freq=${st.selectedFreqHz} gen=$genAtPlan/$engineGen abort=$abortAtPlan/$txAbortGen")
        Diag.line("transmit：text=$text slotStart=$slotStartMs ptt=$effPtt lead=$effLead " +
            "freq=${st.selectedFreqHz} gen=$genAtPlan/$engineGen abort=$abortAtPlan/$txAbortGen")
        val pcm = try {
            Ft8Engine.encode(text, st.selectedFreqHz.toFloat(), st.protocol, 12000)
        } catch (e: Exception) {
            Log.w(TAG_QSO, "transmit：编码异常", e)
            _status.update { it.copy(status = "发射异常: ${e.message}") }
            return
        }
        // 解码/编码期间可能被「停止发射」或换了引擎：丢弃
        if (genAtPlan != engineGen || abortAtPlan != txAbortGen) {
            Log.i(TAG_QSO, "transmit：编码后作废（gen/abort 变了）")
            return
        }

        try {
            // TX 行「一开播就出现」：先记账，再开始阻塞式播放（docs/Ft8Vox.md）。
            val recId = beginTxRecord(text, slotStartMs)
            _status.update { it.copy(txing = true, lastTxText = text, lastTxSlotMs = slotStartMs) }
            val written = AudioEngine.playTx(pcm, effPtt, effLead)
            Log.i(TAG_QSO, "transmit：playTx 返回 written=$written")
            Diag.line("transmit：playTx 返回 written=$written")
            // 仍是当前有效的那一次、且确实写进了声卡 → 算「已播完」；否则（被作废 / 写入失败）标「未发完」。
            if (abortAtPlan == txAbortGen && written > 0) {
                finishTxRecord(recId)
            } else {
                if (written <= 0) {
                    _status.update { it.copy(status = "发射失败：$OUT_STALLED_FAIL（写入 $written 帧）") }
                }
                abortPlayingTxRecord()
            }
        } catch (e: Exception) {
            _status.update { it.copy(status = "发射异常: ${e.message}") }
            abortPlayingTxRecord()
        } finally {
            // 只有「仍是当前有效的那一次」才收尾 / 记账：被「停止发射」或「发射途中换目标就地重发」
            // 作废的这次不再推进状态机 —— 否则会把没播完的旧报文当成已发出去，抢先推进 QSO。
            if (abortAtPlan == txAbortGen) {
                _status.update { it.copy(txing = false) }
                txJustFinished = true
            }
        }
    }

    /**
     * **发射途中换目标**：本时隙剩余时间还够完整播完新报文时，立刻作废正在响的那段音频、
     * 当场重新编码并发射（不白等一个完整周期）；不够就什么都不做，照旧排到下一个我方周期。
     *
     * 触发点：所有「换目标 / 换报文」的人工入口（[startCqInternal] / [answerInternal] /
     * [startAutoTarget] / [sendOnceInternal]）在写入新状态后调用一次 —— 由它统一判断该不该就地重发。
     *
     * 安全性：判断走纯函数 [TxScheduler.canRetargetInSlot]（含波形自带 0.5 s 保护间隔与重启余量）；
     * 作废走 [abortTransmit]（非阻塞，native 最多再响一块）；被作废的那次不会再记账
     * （见 [transmit] 的 `finally`）；新报文与在播报文相同时直接跳过，避免无意义的打断。
     *
     * @param text 刚设定的新报文（由调用方给出，避免被别处的待发报文抢占）
     * @param manual 该报文是否属于「一次性发送」（决定发完是否推进 QSO 状态机）
     */
    private fun tryRetargetNow(text: String?, manual: Boolean) {
        val st = _status.value
        if (text.isNullOrBlank()) return
        if (!st.running || !st.txEnabled || !st.txArmed) return
        // 没有在飞的发射（含「已排定但还在编码」的那一小段）就没什么可换的，交给 txTick
        if (txJob?.isActive != true && !st.txing) return
        // 新目标与正在播的是同一条报文：不必打断
        if (text.trim() == st.lastTxText?.trim()) return
        val slotMs = st.slotMs.toLong()
        val now = AudioEngine.utcNowMs()
        val preambleMs = txPreambleMs().toLong()
        if (!TxScheduler.canRetargetInSlot(
                nowMs = now,
                slotMs = slotMs,
                txParity = st.txParity,
                preambleMs = preambleMs,
                messageMs = st.protocol.messageMs,
                slotOffsetMs = latestSettings.slotOffsetMs.toLong(),
            )
        ) {
            Log.i(TAG_QSO, "换目标：本时隙剩余时间不够播完，排到下一个我方周期 -> $text")
            return
        }
        Log.i(TAG_QSO, "换目标：就地重发 -> $text")
        abortTransmit()   // 作废在飞的那段（非阻塞；它不再记账）
        lastTxSlotIndex = Math.floorDiv(now - latestSettings.slotOffsetMs, slotMs)
        manualInFlight = manual
        val genAtPlan = engineGen
        val abortAtPlan = txAbortGen
        txJob = viewModelScope.launch(Dispatchers.IO) {
            transmit(text, now + preambleMs, preambleMs, genAtPlan, abortAtPlan)
        }
    }

    /**
     * 播放测试单音（设置页「测试音」）。采集未启动时会先自动启动以获得输出流。
     * 独立于 QSO 发射，不武装 TX。
     */
    fun playTestTone(freqHz: Int = 1000, durationMs: Int = 2000) {
        if (!_status.value.running) start()
        if (!_status.value.running) {
            _status.update { it.copy(status = "测试音失败：音频未启动") }
            return
        }
        if (!armPlayback()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val written = AudioEngine.playTone(freqHz, durationMs)
                _status.update {
                    it.copy(
                        status = if (written > 0) "已播放测试音（$written 帧）"
                        else "测试音失败：$OUT_STALLED_FAIL（写入 $written 帧）",
                    )
                }
            } catch (e: Exception) {
                _status.update { it.copy(status = "测试音异常: ${e.message}") }
            }
        }
    }

    // ---- 瀑布 ----

    private fun pollWaterfall() {
        val info = wfInfo ?: return
        val bins = info.bins
        if (bins <= 0) return

        val data = AudioEngine.pollWaterfall(256)
        val rows = data.size / bins
        if (rows <= 0) return

        if (wfPixels.size != WF_ROWS * bins) {
            wfPixels = IntArray(WF_ROWS * bins)
        }
        val pixels = wfPixels
        val shift = minOf(rows, WF_ROWS)
        if (WF_ROWS - shift > 0) {
            System.arraycopy(pixels, shift * bins, pixels, 0, (WF_ROWS - shift) * bins)
        }
        val startRow = WF_ROWS - shift

        // 固定阈值（设置页 / 频谱页可调）：[floorDb, floorDb + rangeDb] 线性映射到色带，
        // 不再按滚动峰值自适应，便于跨设备复现与手动微调。
        val floorDb = latestSettings.waterfallFloorDb
        val rangeDb = latestSettings.waterfallRangeDb
        val lut = WaterfallColors.rampLut
        for (i in 0 until shift) {
            val srcBase = (rows - shift + i) * bins
            val dstBase = (startRow + i) * bins
            for (b in 0 until bins) {
                val u = data[srcBase + b].toInt() and 0xFF
                pixels[dstBase + b] = lut[waterfallIdx(u, floorDb, rangeDb)]
            }
        }

        _waterfall.value = WaterfallFrame(
            bins = bins,
            rows = WF_ROWS,
            binHz = info.binHz,
            fMinHz = info.fMinHz,
            pixels = pixels,
        )
    }

    override fun onCleared() {
        stopPollingAndJoin()
        txJob?.cancel()
        AudioEngine.stopCapture()
        AudioEngine.release()
        alertTone.release()
        SessionService.stop(getApplication<Application>())
        serviceOn = false
        super.onCleared()
    }
}

/** 允许在时隙起点后多久内就地发射（含前导；超出则等下一个我方周期）。 */
internal const val TX_START_WINDOW_MS = 1200L

/**
 * 「实时状态」量化步进（性能，见 [SessionViewModel] 的轮询）：`msToNextSlot` 量化到 500ms。
 *
 * 目的：让 [ReceiverStatus] 只在有肉眼可见变化时才发射，避免 12.5 Hz 整页重组。
 */
internal const val LIVE_STEP_MS = 500L

/** 「实时状态」量化步进：`slotProgress` 量化到 1/50（时隙进度条只有 3dp 高，无需更细）。 */
internal const val LIVE_PROGRESS_STEPS = 50f

/**
 * 「实时读数」统一节流窗口（ms）：见 [SessionViewModel] 的轮询。
 *
 * `msToNextSlot` / `slotProgress` / `voxLevelDb` 只在跨越本窗口边界时发布一次，
 * 使 [ReceiverStatus] 的发射率从 12.5 Hz 降到 5 Hz —— 这几项在 UI 上都只是粗略的仪表读数。
 */
internal const val LIVE_PUBLISH_INTERVAL_MS = 200L

/** 一次发射的调度结果：目标时隙、播放起点与数据起点。 */
internal data class TxPlan(
    /** 目标时隙序号（`UTCms / slotMs`），用于去重。 */
    val targetSlotIndex: Long,
    /** 数据（FT8 波形）预定起点 = 播放起点 + 前导；计划起点时一般等于目标时隙起点。 */
    val targetStartMs: Long,
    /** 播放起点，= 数据起点 − 前导时长。 */
    val startAtMs: Long,
)

/**
 * 计算本次发射的目标时隙与播放起点（纯函数，便于单测）。
 *
 * - 当前处于**对方周期**：瞄准下一个我方周期（必到），用完整前导提前启动。
 * - 当前处于**我方周期**且「时隙内已过时间 + 前导」仍在 [startWindowMs] 内，**或**整条报文仍能
 *   在本时隙内播完（[messageMs] 非 0 时，报文时长 + 前导 + 已过时间 ≤ 时隙长）：**就地发射**，
 *   立刻开始写播放、前导完整保留（数据起点相应后移几百毫秒）。解码结果在时隙结束后几百毫秒
 *   才到手，这条路径让应答落在**紧邻的时隙**，而不是白等一个完整周期。
 * - 否则（我方周期但已等太久）：瞄准下一个我方周期（+2）。
 *
 * 真正决定数据起点的是「播放起点 + 前导」；[TxPlan.targetStartMs] 即该值。
 * [slotOffsetMs] 非 0 时整个时隙网格（含 native 解码窗口）一起平移，见该参数说明。
 */
internal fun planTx(
    nowMs: Long,
    slotMs: Long,
    txParity: Int,
    preambleMs: Long,
    startWindowMs: Long = TX_START_WINDOW_MS,
    /**
     * 整个时隙的偏移（ms，正=推后）：所有时隙边界 = 名义 UTC 边界 + 该值。
     *
     * 与 native 采集窗口用同一个值（[AudioEngine.setSlotOffsetMs]），因此「解码窗口」与
     * 「发射起点」同步平移：把解码卡片显示的「时间差」原样填进来即可同时校准两端。
     */
    slotOffsetMs: Long = 0L,
    /**
     * 单条报文的波形时长（ms，0=未知）：非 0 时放宽「就地发射」窗口 —— 只要**整条报文能在
     * 本时隙内播完**（已过时间 + 前导 + 报文 ≤ 时隙长）就直接本时隙发射，不必白等一个周期。
     *
     * FT8 报文 12.64 s 远短于时隙 15 s，而解码结果在时隙结束后几百
     * 毫秒才到手，所以应答通常正好落在紧邻的时隙。传 0 时退回只按 [startWindowMs] 判定。
     */
    messageMs: Long = 0L,
): TxPlan {
    require(slotMs > 0) { "slotMs must be positive" }
    val pre = preambleMs.coerceAtLeast(0L)
    val off = slotOffsetMs
    // 在「偏移后的时间轴」上判断：等价于把整个时隙网格平移 off（时隙序号仍是名义 UTC 序号）
    val shiftedNow = nowMs - off
    val slotIdx = shiftedNow / slotMs
    val parity = (slotIdx % 2L).toInt()
    if (parity != txParity) {
        // 对方周期：下一个时隙必是我方周期
        val targetIdx = slotIdx + 1
        val targetStart = targetIdx * slotMs + off
        return TxPlan(targetIdx, targetStart, targetStart - pre)
    }
    val posInSlot = shiftedNow - slotIdx * slotMs
    val msg = messageMs.coerceAtLeast(0L)
    // 就地发射：① 刚过（偏移后的）起点（旧窗口）；② 整条报文能在本时隙内播完
    val inPlace = posInSlot + pre <= startWindowMs ||
        (msg > 0L && posInSlot + pre + msg <= slotMs)
    if (inPlace) {
        // 前导照旧，数据起点 = 现在 + 前导（数据尾仍落在本时隙内）
        return TxPlan(slotIdx, nowMs + pre, nowMs)
    }
    // 已过太久：下一个我方周期
    val targetIdx = slotIdx + 2
    val targetStart = targetIdx * slotMs + off
    return TxPlan(targetIdx, targetStart, targetStart - pre)
}

/** 迟到后缩水的前导：至少为 0，用于把数据重新对齐到时隙起点。 */
internal fun effectivePreambleMs(preambleMs: Long, latenessMs: Long): Long =
    (preambleMs - latenessMs).coerceAtLeast(0L)

/**
 * 一次性发射（自定义报文框的「发送」/ 长按解码行的「逐条发送」）**不能发的原因**（纯逻辑，便于单测）。
 *
 * 返回 null 表示可以发。原来这些拒绝只写进 `ReceiverStatus.status`，而竖屏外壳里没有显示它的
 * 位置，用户点「发送」就像什么都没发生（真机反馈）—— 现在统一走红字提示 `ReceiverStatus.txNotice`。
 */
internal fun txBlockReason(myCall: String, txEnabled: Boolean): String? = when {
    myCall.isEmpty() -> "请先到设置填写呼号与网格"
    !txEnabled -> "只接收：先打开信息头右上角的「发射」开关"
    else -> null
}

/**
 * QSO 后面**还有报文要发**：状态机还在进行（`active`），或者已经 DONE/FAILED、但收尾的
 * RR73/73 还没播出去（`txText != null`）。
 *
 * 发射武装（`ReceiverStatus.txArmed`）在一条报文发完后要不要保留、以及「自定义报文插一条」发完
 * 要不要接着发原序列，都看它 —— 两处判据本来各写一遍，统一到这里。纯逻辑便于单测。
 */
internal fun qsoStillNeedsTx(p: QsoProgress): Boolean =
    p.active || ((p.state == QsoState.DONE || p.state == QsoState.FAILED) && p.txText != null)

/** 发射区一次性提示（红字）的显示时长：够看清一句短句，又不至于长时间盖住 QSO 进度那一行。 */
internal const val TX_NOTICE_MS = 6000L

/**
 * 「发射排定」是否要停下来等上一时隙的解码（`txTick` 的闸门；纯函数便于单测）。
 *
 * QSO 进行中时，本时隙该发什么取决于**上一时隙**（[targetSlotIndex] − 1）的解码：它可能把
 * 状态推进/收尾（`WAIT_RR73` 收到 RR73 → 要发 73），也可能带来「有人呼叫我」而该改判为应答
 * （而不是再发一遍 CQ）。native 的 FT8 解码要到时隙末尾（14.88 s）才出结果，比前导提前量
 * （`planTx.startAtMs`）晚，所以必须等：抢发出去的会是上一时隙的旧报文，而且该时隙已被占用
 * （阻塞写要播十几秒，期间无法改发），新报文只能再等一个周期 —— 真机现象就是「对方给我 RR73
 * 我没回 73」与「别人呼叫我，我却还在发 CQ」。
 *
 * [lastDecodeAtMs] 是健康判据：解码停摆（超过两个时隙没推进）时**不再拦**，避免采集/解码异常
 * 时把自己锁死（宁可发旧报文，也不能一条都不发）。
 *
 * [targetSlotStartMs] 是第二道兜底：若解码网格因故（采集预缓冲、系统调度抖动）比墙钟滞后，
 * `lastDecodedSlotIndex < targetSlotIndex-1` 可能**恒真**，而解码又在推进（[lastDecodeAtMs] 健康
 * 判据不触发）→ 闸门永不放开，QSO 一条都发不出去。因此只允许在「目标时隙起点 +
 * [DECODE_WAIT_GRACE_MS]」之前等：一旦进目标时隙超过该宽限，就按现状发（就地发射仍能整条落在
 * 本时隙内），保证永远不锁死。
 *
 * 网格本身的错位已在 [AudioEngine] 侧修根（对齐标签按目标边界取值，见 `armAlignment`）；
 * 这里的宽限只是防御性兜底，正常情况下解码结果在时隙结束后几十~百余 ms 到手，闸门会立即放开。
 */
internal fun txShouldWaitForDecode(
    targetSlotIndex: Long,
    targetSlotStartMs: Long,
    lastDecodedSlotIndex: Long,
    nowMs: Long,
    lastDecodeAtMs: Long,
    slotMs: Long,
): Boolean =
    lastDecodedSlotIndex < targetSlotIndex - 1 &&
        nowMs - lastDecodeAtMs <= slotMs * 2 &&
        nowMs - targetSlotStartMs <= DECODE_WAIT_GRACE_MS

/**
 * 进入目标时隙后仍允许等上一时隙解码的宽限（ms）：超过就发。
 *
 * 取值需小于「就地发射」余量（报文 12640 ms，时隙 15000 ms，前导若干百 ms），否则宽限结束时
 * 已无法整条落回本时隙，`planTx` 会跳到下下个周期，反而继续等。700 ms 在默认前导下安全。
 */
internal const val DECODE_WAIT_GRACE_MS = 700L

/** 自动周期模式的前导余量：给播放流准备留出的额外时间（ms）。 */
internal const val AUTO_PARITY_LEAD_MARGIN_MS = 500L

/**
 * 时隙奇偶标记（0/1）：按 UTC 时隙起点取整后的槽位序号取奇偶。
 *
 * 每 60 s 恰好 4 个 15 s（FT8）时隙，因此：
 * **第 1、3 个时隙为 0，第 2、4 个时隙为 1**。
 *
 * @param slotUtcMs 时隙的 UTC 起点（毫秒）；离线解码为 0
 * @param slotMs 时隙长度（毫秒）
 * @return 0/1；[slotUtcMs] 或 [slotMs] 非法时返回 null
 */
internal fun slotParityOf(slotUtcMs: Long, slotMs: Long): Int? =
    if (slotUtcMs <= 0L || slotMs <= 0L) null else ((slotUtcMs / slotMs) % 2L).toInt()

/**
 * 「设为目标」时我方的发射时隙奇偶：目标时隙的**相反周期**（对方第 1/3 个我发第 2/4 个）。
 *
 * @return 0/1；目标时隙不可知（离线解码）时返回 null
 */
internal fun oppositeSlotParity(targetSlotUtcMs: Long, slotMs: Long): Int? =
    slotParityOf(targetSlotUtcMs, slotMs)?.let { 1 - it }

/**
 * 自动周期：按手机 UTC 时间取「下一个距起点 >= [leadMs] 的时隙」的奇偶（0=偶，1=奇）。
 *
 * 若当前时隙剩余时间不足以准备前导，则跳到再下一个时隙（即「再下一个时隙发射」）。
 */
internal fun nextSlotParity(nowMs: Long, slotMs: Long, leadMs: Long): Int {
    if (slotMs <= 0) return TX_PARITY_EVEN
    val now = nowMs.coerceAtLeast(0L)
    var idx = now / slotMs + 1
    if (idx * slotMs - now < leadMs) idx += 1
    return (idx % 2L).toInt()
}

/**
 * 「需要时」决定当前应当使用的发射周期。
 *
 * - [pinned] 非空（已「设为目标」/ 对齐目标时隙）→ 用这个固定周期；
 * - 否则若已有锁定周期 [locked] → **保持不变**；
 * - 两者都没有（刚开启总开关 / 刚停止接收）→ 按 UTC 时间取下一个来得及准备的时隙。
 *
 * 之所以要「保持不变」：每段 QSO 结束都按当前时间重锁，会让发射时隙在偶/奇之间来回跳
 * （QSO 之间「跳时隙」），与 WSJT-X / FT8CN「一直用同一周期收发」不一致。
 * 需要换周期只有两种情况：目标在相反周期（上面的固定周期），或用户重开总开关。
 */
internal fun effectiveTxParity(
    pinned: Int?,
    locked: Int?,
    nowMs: Long,
    slotMs: Long,
    leadMs: Long,
): Int = pinned ?: locked ?: nextSlotParity(nowMs, slotMs, leadMs)

/** 周期文案（用于状态提示）。 */
internal fun parityLabel(parity: Int): String =
    if (parity == TX_PARITY_ODD) "奇数周期" else "偶数周期"

/**
 * 规整「CQ 前缀」格子与选中项（纯逻辑，便于单测；发射区「CQ 前缀」弹窗「确定」时提交）。
 *
 * - 固定 [CQ_PREFIX_SLOTS] 格、去首尾空格、转大写；**空格子保留**（空串＝普通 CQ），不能去空；
 * - 全是空格子时回落 [DEFAULT_CQ_PREFIXES]（给出「恢复默认」的出口，也免得 CQ 报文没有前缀格可选）；
 * - 选中下标按最终格子数夹住。
 *
 * @return `(格子表, 选中下标)`
 */
internal fun cleanCqPrefixes(slots: List<String>, index: Int): Pair<List<String>, Int> {
    val cleaned = List(CQ_PREFIX_SLOTS) { slots.getOrElse(it) { "" }.trim().uppercase() }
    val kept = if (cleaned.any { it.isNotEmpty() }) cleaned else DEFAULT_CQ_PREFIXES
    return kept to index.coerceIn(0, (kept.size - 1).coerceAtLeast(0))
}

/**
 * 前台服务通知的副标题（纯函数，便于单测，阶段 9）。
 *
 * 形如 `接收中 · FT8 · 20m · 解码 12`；发射中时以「发射中」开头并附当前对手呼号。
 * 未运行（服务本应已停止，仅作兜底）时只返回「已停止」。
 */
internal fun serviceStatusText(st: ReceiverStatus): String {
    val head = when {
        st.txing -> "发射中"
        st.running -> "接收中"
        else -> "已停止"
    }
    if (!st.running) return head
    return buildString {
        append(head)
        append(" · ").append(st.protocol.name)
        if (st.band.isNotEmpty()) append(" · ").append(st.band)
        append(" · 解码 ").append(st.decodedTotal)
        if (st.qso.active) st.qso.theirCall?.let { append(" · ").append(it) }
    }
}

/**
 * 「含我呼号哔声」判定（纯函数，便于单测，U7d）。
 *
 * 仅在开关打开、我方呼号非空，且本批解码中存在发给我（`to == myCall`）的报文时为真。
 * 自己发出的报文不会被解码回来，因此不会误报。
 */
internal fun shouldAlertMyCall(beepOn: Boolean, myCall: String, texts: List<String>): Boolean {
    if (!beepOn) return false
    val my = myCall.trim()
    if (my.isEmpty()) return false
    return texts.any { MessageParser.parse(it).addressedTo(my) }
}
