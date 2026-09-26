package com.example.ft8vox.ui

import android.app.Application
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
import com.example.ft8vox.engine.Ft8Config
import com.example.ft8vox.engine.Ft8Engine
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.engine.VoxConfig
import com.example.ft8vox.engine.VoxMode
import com.example.ft8vox.engine.WaterfallInfo
import com.example.ft8vox.qso.AutoAction
import com.example.ft8vox.qso.AutoMode
import com.example.ft8vox.qso.AutoProgramSettings
import com.example.ft8vox.qso.AutoScheduler
import com.example.ft8vox.qso.AutoTarget
import com.example.ft8vox.qso.AutoTargetKind
import com.example.ft8vox.qso.DEFAULT_MACROS
import com.example.ft8vox.qso.DecodeFilterState
import com.example.ft8vox.qso.DecodeFilterTag
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.qso.QsoEngine
import com.example.ft8vox.qso.QsoLogEntry
import com.example.ft8vox.qso.QsoProgress
import com.example.ft8vox.qso.QsoState
import com.example.ft8vox.qso.TxQueue
import com.example.ft8vox.qso.WorkedIndex
import com.example.ft8vox.data.settings.VoxTrigger
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
    /** 本会话累计解码条数（new_ui 底部状态条「总数」）。 */
    val decodedTotal: Long = 0,
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
     * 自动程序策略（来自设置；对应《QSO 自动系统设计文档》§五菜单）。
     *
     * **模式即开关**：`mode == MANUAL`（「0 手动模式」）＝自动程序关闭，1/2 ＝开启；
     * 不存在独立的「启用/关闭」状态（见 [SessionViewModel.setAutoMode]）。
     */
    val autoProgram: AutoProgramSettings = AutoProgramSettings(),
    /** 第 2 层当前阶段文案（主叫／应答／监听），关闭自动程序时为 null。 */
    val autoPhaseLabel: String? = null,
    /** 第 2 层目标队列长度（等待逐个完成的回应者）。 */
    val autoQueueSize: Int = 0,
    /** 待发的一次性报文（长按解码行选择；发完即清空）。 */
    val manualTxText: String? = null,
    // ---- VOX / PTT（U7b，基于输入电平近似判定，仅作提示） ----
    /** VOX 判定为已触发。 */
    val voxOpen: Boolean = false,
    /** 平滑后的输入电平（dBFS，下限约 -100）。 */
    val voxLevelDb: Float = -100f,
)

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

    private val _waterfall = MutableStateFlow<WaterfallFrame?>(null)
    val waterfall: StateFlow<WaterfallFrame?> = _waterfall.asStateFlow()

    private val _status = MutableStateFlow(ReceiverStatus())
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    /** 最近 3 条通联（操作页展示）。 */
    val recentQso: Flow<List<QsoEntity>> = qsoRepo.observeRecent(3)

    /** 已通联索引（呼号/网格/前缀），供操作页过滤与高亮、自动程序共用。 */
    private val _worked = MutableStateFlow(WorkedIndex.EMPTY)
    val workedIndex: StateFlow<WorkedIndex> = _worked.asStateFlow()

    private val qsoEngine = QsoEngine(maxRetries = 6)

    private var pollJob: Job? = null
    private var txJob: Job? = null
    private var wfInfo: WaterfallInfo? = null
    private var wfPixels = IntArray(0)
    private var wfPeak = 0

    /** 瀑布噪声底估计（mag 单位，0.5 dB/单位），-1 表示尚未初始化。 */
    private var wfFloor = -1

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

    /** 第 2 层自动程序调度器（目标队列 / 主叫 / 混合 / 保护限制）。 */
    private val scheduler = AutoScheduler()

    /**
     * 第 3 层：用户是否正在手动接管（设了目标 / 手动发了 CQ），此时第 2 层保持暂停。
     *
     * 手动 QSO 结束或被「停止发射」取消后置回 false 并 `scheduler.resume()`。
     */
    private var manualQso = false

    /**
     * QSO 已完成、但最后一条（RR73/73）还没发出去。
     *
     * 发完这条后在 [txTick] 里通知第 2 层，避免下一个动作覆盖掉状态机导致收尾报文丢失。
     */
    private var pendingAutoFinish = false

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
                    (if (cur.running) cur.slotMs else slotMsOf(s.protocol)).toLong(),
                    txPreambleMs().toLong() + AUTO_PARITY_LEAD_MARGIN_MS,
                ),
                sameFreqTx = s.sameFreqTx,
                autoProgram = s.auto,
                // 协议绑定在 native 引擎上（时隙长度 15s/7.5s 与调制方式），运行中不能直接改：
                // 这里先保留当前值，随后 [restartForProtocol] 会重建引擎切到 s.protocol
                protocol = if (cur.running) cur.protocol else s.protocol,
                slotMs = if (cur.running) cur.slotMs else slotMsOf(s.protocol),
            )
        }
        // 运行中改协议 → 重建引擎（接收短暂中断）
        if (_status.value.running && s.protocol != _status.value.protocol) {
            restartForProtocol(s.protocol)
        }
        qsoEngine.configure(
            s.myCall,
            s.myGrid,
            maxRetries = s.auto.retryLimit,
            giveUp = s.auto.giveUpAfterRetry,
        )
        scheduler.configure(s.auto, s.myCall, s.myGrid)
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
     * 把 VOX/PTT 配置下发给 native（热生效）。
     * 引擎未初始化时 [AudioEngine.setVox] 为无操作；[start] 会用 force 重下发一次。
     */
    private fun applyVox(s: AppSettings, force: Boolean = false) {
        val cfg = voxConfigOf(s)
        if (!force && cfg == lastVoxConfig) return
        lastVoxConfig = cfg
        AudioEngine.setVox(cfg)
    }

    /** 由设置组装 native 的 VOX/PTT 配置。 */
    private fun voxConfigOf(s: AppSettings): VoxConfig = VoxConfig(
        mode = if (s.voxTrigger == VoxTrigger.SILENCE) VoxMode.SILENCE else VoxMode.AUDIO,
        thresholdDb = s.voxThresholdDb,
        delayMs = s.voxDelayMs,
        pttDelayMs = s.pttDelayMs,
        leadToneMs = if (s.txLeadTone) s.txLeadToneMs else 0,
        watchdogMs = s.watchdogMs,
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
     * 设置自动程序工作模式 —— **模式即开关**：`0 手动模式`＝关闭，1 主叫 / 2 混合＝开启。
     *
     * 由「0 手动模式」切到 1/2 时，UI 需先弹防误发确认（`AutoEnableConfirmDialog`）再调本方法；
     * 确认后若「发送总开关」为关，先 [setTxEnabled] 打开（总开关此前未锁定时顺带按手机 UTC
     * 时间锁定发射时隙）—— 总开关仍是「能不能发」的唯一权限。
     *
     * 切回「0 手动模式」只停止自动化，**不动「发送总开关」**（手动发送仍需要这个权限）。
     */
    fun setAutoMode(mode: AutoMode) {
        if (mode == _status.value.autoProgram.mode) return
        if (mode == AutoMode.MANUAL) {
            manualQso = false
            scheduler.disable()
            _status.update {
                it.copy(
                    autoProgram = it.autoProgram.copy(mode = mode),
                    autoPhaseLabel = null,
                    autoQueueSize = 0,
                    status = "自动程序已关闭（0 手动模式，发送总开关不变）",
                )
            }
            persist { it.copy(auto = it.auto.copy(mode = mode)) }
            return
        }
        if (!canOperate) {
            _status.update { it.copy(status = "请先填写呼号，再启用自动程序") }
            return
        }
        if (!_status.value.txEnabled) setTxEnabled(true)
        val parity = parityLabel(_status.value.txParity)
        manualQso = false
        scheduler.enable(AudioEngine.utcNowMs())
        _status.update {
            it.copy(
                autoProgram = it.autoProgram.copy(mode = mode),
                autoPhaseLabel = phaseLabel(scheduler.currentPhase()),
                autoQueueSize = 0,
                status = "自动程序已启用（${mode.label}，$parity）",
            )
        }
        persist { it.copy(auto = it.auto.copy(mode = mode)) }
    }

    /** 第 2 层阶段的显示文案。 */
    private fun phaseLabel(phase: AutoScheduler.Phase): String = when (phase) {
        AutoScheduler.Phase.CALLING -> "主叫（发 CQ / 排队回应者）"
        AutoScheduler.Phase.ANSWERING -> "应答（监听并应答别人的 CQ）"
    }

    /** 改一项自动程序策略开关。 */
    fun setAutoOption(transform: (AutoProgramSettings) -> AutoProgramSettings) {
        _status.update { it.copy(autoProgram = transform(it.autoProgram)) }
        persist { it.copy(auto = transform(it.auto)) }
    }

    fun setFilterTags(tags: Set<DecodeFilterTag>) {
        persist { it.copy(filterTags = tags) }
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

    fun setCallFilter(value: String) {
        persist { it.copy(callFilter = value) }
    }

    /** 保存宏模板（最多 8 个；全空则回落默认）。 */
    fun setMacros(list: List<String>) {
        val cleaned = list.map { it.trim() }.filter { it.isNotEmpty() }.take(8)
        persist { it.copy(macros = cleaned.ifEmpty { DEFAULT_MACROS }) }
    }

    fun enqueueTx(text: String) {
        persist { it.copy(txQueue = TxQueue.enqueue(it.txQueue, text)) }
    }

    fun removeQueuedTx(index: Int) {
        persist { it.copy(txQueue = TxQueue.removeAt(it.txQueue, index)) }
    }

    fun moveQueuedTx(from: Int, to: Int) {
        persist { it.copy(txQueue = TxQueue.move(it.txQueue, from, to)) }
    }

    fun clearTxQueue() {
        persist { it.copy(txQueue = emptyList()) }
    }

    /** 选择「波段 + 刻度频率」（顶栏弹窗 / 设置页）。[hz]<=0 表示用该波段默认频率。 */
    fun setBandFreq(name: String, hz: Long) {
        val n = name.trim().takeIf { it.isNotEmpty() } ?: return
        val resolved = BandPlan.resolveDialHz(n, hz)
        _status.update { it.copy(band = n, dialHz = resolved) }
        persist { it.copy(band = n, dialHz = resolved) }
    }

    /** 仅切波段（沿用该波段默认频率）。 */
    fun setBand(name: String) = setBandFreq(name, 0L)

    /**
     * 发送总开关（默认关 = 只接收）：唯一回答「**能不能发**」。
     *
     * 它自己**不发任何报文** —— 发什么由「发送」按钮 / 解码卡片手势（手动）或自动程序（自动）决定，
     * 见 `TxDrawer` 收起态条与 [setAutoMode]。
     *
     * - 开启：允许发射；总开关此前未锁定时按手机 UTC 时间锁定「下一个来得及准备的时隙」。
     * - 关闭：立即停发、解除时隙锁定与目标固定；**不动自动程序模式** —— 开回总开关即按原模式继续
     *   （不能发射时自动程序只是待命，没必要把模式清掉）。
     *
     * 因不再提供周期设置，用户通过在不同时机重开总开关来换发射时隙。
     */
    fun setTxEnabled(enabled: Boolean) {
        if (!enabled) {
            autoParity = null
            pinnedTxParity = null
            scheduler.pause()
            stopTransmit(resumeAuto = false)
            _status.update {
                it.copy(
                    txEnabled = false,
                    status = if (it.autoProgram.mode.enabled) {
                        "发送已关闭（只接收）｜自动程序待命（开总开关即继续）"
                    } else {
                        "发送已关闭（只接收）"
                    },
                )
            }
            return
        }
        scheduler.resume()
        relockAutoParityIfNeeded()
        val p = _status.value.txParity
        _status.update {
            it.copy(
                txEnabled = true,
                txParity = p,
                autoPhaseLabel = if (it.autoProgram.mode.enabled) phaseLabel(scheduler.currentPhase()) else null,
                autoQueueSize = if (it.autoProgram.mode.enabled) scheduler.queuedCount() else 0,
                status = "发送已开启（允许发射，${parityLabel(p)}）",
            )
        }
    }

    /**
     * 「设为目标」时把发射时隙固定到目标接收时隙的**相反周期**（时隙自动对应）。
     *
     * QSO 进行中忽略（不打断当前序列）；无时隙信息时清除固定。
     *
     * @param targetSlotUtcMs 目标解码所在时隙的 UTC 起点（毫秒）
     */
    fun alignTxToTarget(targetSlotUtcMs: Long) {
        val st = _status.value
        if (st.qso.active) return
        val mine = pinToTargetSlot(targetSlotUtcMs)
        if (mine == null) {
            pinnedTxParity = null
            return
        }
        _status.update { it.copy(txParity = mine, status = "已设为目标：时隙自动对应（${parityLabel(mine)}周期）") }
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

    fun clearMessages() {
        _messages.value = emptyList()
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
            )
        } catch (e: Exception) {
            _status.update { it.copy(status = "初始化失败: ${e.message}") }
            return
        }
        engineGen++   // 新引擎：作废所有还在路上的发射协程（见 transmit 的 genAtPlan）

        wfInfo = AudioEngine.waterfallInfo()
        wfPixels = IntArray(0)
        wfPeak = 0
        wfFloor = -1
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
        stopTransmit(resumeAuto = false)
        scheduler.disable()
        manualQso = false
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
                qso = qsoEngine.stop(),
            )
        }
        // 只用停服务收口：不要在这里推送「已停止」文案，否则通知会在服务销毁前被重贴成僵尸通知
        syncService()
    }

    /**
     * 运行中切换协议（FT8 ⇄ FT4）：**停一下再重建引擎**。
     *
     * native monitor 的时隙长度（15 s / 7.5 s）与调制方式在创建时就固定了，没法热改，
     * 所以只能重启一次采集。[stop] 会顺手关掉「发送总开关」并结束当前 QSO，这里按切换前
     * 的状态把总开关恢复回来（换协议本身不应该收回「能不能发」的授权）。
     */
    private fun restartForProtocol(protocol: Protocol) {
        val cur = _status.value
        val txWasOn = cur.txEnabled
        val wasTxing = cur.txing
        // 抑制服务启停：这段 stop()/start() 是原子的，服务无需跟着停一下
        serviceSuppressed = true
        try {
            stop()
            _status.update { it.copy(protocol = protocol, slotMs = slotMsOf(protocol)) }
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
     * 用户手动发起＝第 3 层：会暂停第 2 层调度（手动 QSO 结束后自动恢复）。
     */
    fun startCq() {
        manualIntervention()
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

        relockAutoParityIfNeeded()
        if (!armPlayback()) return
        lastTxSlotIndex = -1L
        val p = qsoEngine.startCq()
        _status.update { it.copy(qso = p, txArmed = true, status = "QSO：${p.description}") }
    }

    /** 应答指定 CQ。用户手动发起＝第 3 层。 */
    fun answer(call: String, grid: String?, theirDf: Int? = null) {
        manualIntervention()
        answerInternal(call, grid, theirDf)
    }

    private fun answerInternal(call: String, grid: String?, theirDf: Int? = null) {
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

        // 时隙自动对应：按该台最近一条解码的**相反周期**应答
        if (pinToTargetSlot(lastHeardSlotUtcMsOf(call)) == null) pinnedTxParity = null
        relockAutoParityIfNeeded()
        if (!armPlayback()) return
        lastTxSlotIndex = -1L
        val p = qsoEngine.startResponderQso(call, grid)
        if (!p.active) {
            _status.update { it.copy(status = "无法应答（呼号无效或与自身相同）") }
            return
        }
        _status.update { it.copy(qso = p, txArmed = true, status = "QSO：${p.description}") }
    }

    /**
     * 一次性发射一条报文（长按解码行的「逐条发送」）。
     *
     * 不进入 QSO 自动序列，发完即解除武装；QSO 进行中不允许（避免打断自动序列）。
     * 用户手动发起＝第 3 层：会暂停第 2 层调度。
     */
    fun sendOnce(text: String) {
        manualIntervention()
        sendOnceInternal(text)
    }

    private fun sendOnceInternal(text: String) {
        if (!canOperate) {
            _status.update { it.copy(status = "请先填写呼号") }
            return
        }
        if (!_status.value.txEnabled) {
            _status.update { it.copy(status = "请先打开「发射」开关") }
            return
        }
        if (_status.value.qso.active) {
            _status.update { it.copy(status = "QSO 进行中，暂不逐条发送") }
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!_status.value.running) start()
        if (!_status.value.running) return

        relockAutoParityIfNeeded()
        if (!armPlayback()) return
        lastTxSlotIndex = -1L
        _status.update {
            it.copy(manualTxText = trimmed, txArmed = true, status = "待发（一次性）：$trimmed")
        }
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
                _status.update { it.copy(txing = true, lastTxText = trimmed, lastTxSlotMs = 0) }
                val written = AudioEngine.playTx(pcm, pttMs, leadMs)
                _status.update { it.copy(status = "已发射「$trimmed」（$written 帧）") }
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
     * **只停本次发射，不动自动程序模式**：模式 ≥1 时自动程序下一时隙会继续（要全停请关
     * 「发送总开关」，或把模式切回「0 手动模式」）。
     *
     * 第 3 层语义（文档 §3.3）：若这是用户手动接管的 QSO，取消后立即把控制权交还第 2 层
     * （[resumeAuto]，队列与阶段原样保留）。[resumeAuto]=false 用于「关总开关」等场景：
     * 此时自动程序应当待命而不是马上接续。
     *
     * 中止走 [AudioEngine.abortTx]（非阻塞、不加锁）：**不要**在这里调
     * `AudioEngine.stopPlayback()` —— 关流要等 `tx_mutex`，而阻塞式 `AAudioStream_write`
     * 会整段独占它（FT8 约 16 s），UI 线程一调用就卡死（ANR）。
     */
    fun stopTransmit(resumeAuto: Boolean = true) {
        abortTransmit()
        pendingAutoFinish = false
        autoParity = null
        if (resumeAuto) resumeAutoAfterManual()
        val p = qsoEngine.stop()
        _status.update {
            it.copy(
                txArmed = false,
                txing = false,
                manualTxText = null,
                qso = p,
                txCountdownMs = 0,
                autoPhaseLabel = if (it.autoProgram.mode.enabled) phaseLabel(scheduler.currentPhase()) else null,
                autoQueueSize = if (it.autoProgram.mode.enabled) scheduler.queuedCount() else 0,
                status = if (it.autoProgram.mode.enabled) {
                    "已停止发射｜自动程序仍开启（要全停请关「发送总开关」）"
                } else {
                    "已停止发射"
                },
            )
        }
    }

    /**
     * 第 3 层：用户手动接管（设目标 / 手动发 CQ / 逐条发送）时暂停第 2 层调度。
     *
     * 第 2 层的队列与阶段状态**原样保留**（文档 §3.3「不修改第 2 层的调度状态」），
     * 手动 QSO 结束或被取消后再恢复。
     */
    private fun manualIntervention() {
        if (!_status.value.autoProgram.mode.enabled) return
        manualQso = true
        scheduler.pause()
        _status.update { it.copy(autoPhaseLabel = "已暂停（手动接管）") }
    }

    /** 第 3 层交还控制权：手动 QSO 结束后恢复第 2 层调度（状态保留）。 */
    private fun resumeAutoAfterManual() {
        if (!manualQso) return
        manualQso = false
        scheduler.resume()
        _status.update {
            it.copy(
                autoPhaseLabel = if (it.autoProgram.mode.enabled) phaseLabel(scheduler.currentPhase()) else null,
                autoQueueSize = if (it.autoProgram.mode.enabled) scheduler.queuedCount() else 0,
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
                _status.update { it.copy(status = "已发射测试「$text」（$written 帧）") }
            } catch (e: Exception) {
                _status.update { it.copy(status = "发射失败: ${e.message}") }
            } finally {
                _status.update { it.copy(txing = false) }
            }
        }
    }

    /** 打开播放流（复用同一流，避免发射瞬间才建流导致错过时隙）。 */
    private fun armPlayback(): Boolean {
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
            _messages.update { current -> (decoded + current).take(200) }
            _status.update { it.copy(decodedTotal = it.decodedTotal + decoded.size) }
            pendingDecodes.addAll(decoded)
            // U7d：有报文叫我呼号时短促提示
            val my = _status.value.myCall
            if (shouldAlertMyCall(latestSettings.beepOnMyCall, my, decoded.map { it.text })) {
                alertTone.beep()
            }
        }

        // 2) 状态
        //
        // 性能（2026-09-26）：`msToNextSlot` / `slotProgress` / `voxLevelDb` / `voxOpen` 这些「实时读数」
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
                    droppedSamples = s.droppedSamples,
                    voxOpen = if (live) s.voxOpen else it.voxOpen,
                    voxLevelDb = if (live) s.voxLevelDb else it.voxLevelDb,
                )
            }

            // 3) 每完成一个接收时隙，交给 QSO 状态机或自动程序
            if (s.slotsDecoded > lastSlotsDecoded) {
                lastSlotsDecoded = s.slotsDecoded
                val st = _status.value
                val batch = pendingDecodes.toList()
                pendingDecodes = mutableListOf()
                val slotMs = s.slotMs.toLong()
                // 本批解码所属时隙：优先用解码自带的时隙起点（native 精确给出该时隙 UTC 起点）
                val batchSlot = batch.maxOfOrNull { it.slotUtcMs } ?: 0L
                val batchSlotIdx = if (slotMs > 0 && batchSlot > 0) batchSlot / slotMs else Long.MIN_VALUE
                // 只丢弃「本批正好落在我方刚发射的那个时隙」的解码（自己发出的信号）。
                // 不能用 txParity 推断：锁定周期可能恰好与对方相同，会把对方的报文整批丢掉。
                val isOwnTxSlot = batchSlotIdx != Long.MIN_VALUE && batchSlotIdx == lastTxSlotIndex
                if (!isOwnTxSlot) {
                    if (st.qso.active) {
                        if (st.qso.awaitingResponders) {
                            // 第 2 层「已发 CQ、等回应者」阶段：解码交给调度器收集/排序回应者
                            runAutoProgram(batch, s.utcNowMs)
                        } else {
                            applyQsoProgress(qsoEngine.onDecoded(batch, s.utcNowMs), s.utcNowMs)
                        }
                    } else if (st.autoProgram.mode.enabled) {
                        runAutoProgram(batch, s.utcNowMs)
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
     * QSO 结束时（成功/失败）：
     * - 失败且已无待发报文 → 立刻通知第 2 层（[AutoScheduler.onQsoFinished]）；
     * - 成功时还有最后一条 RR73/73 要发 → 置 [pendingAutoFinish]，等它发完在 [txTick] 里收口；
     *   否则第 2 层下一个动作（如 `startCq`）会覆盖状态机，最后一条就丢了。
     * - 若这是第 3 层的手动接管（[manualQso]），不通知第 2 层，只交还控制权。
     */
    private fun applyQsoProgress(p: QsoProgress, utcNowMs: Long = AudioEngine.utcNowMs()) {
        val finished = p.state == QsoState.DONE || p.state == QsoState.FAILED
        // 完成时**保留发射周期**：还要用同一周期把最后一条 RR73/73 发出去；
        // 失败时只解除「目标时隙固定」（保留当前周期，避免下一段跳时隙）。
        if (p.state == QsoState.FAILED) pinnedTxParity = null
        _status.update { it.copy(qso = p, status = "QSO：${p.description}") }
        qsoEngine.consumeCompleted()?.let { entry: QsoLogEntry ->
            val st = _status.value
            val entity = QsoEntity(
                theirCall = entry.theirCall,
                theirGrid = entry.theirGrid,
                myCall = st.myCall,
                myGrid = st.myGrid.ifEmpty { null },
                utcMs = entry.utcMs,
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
            _status.update { it.copy(status = "已记录通联：${entry.theirCall}") }
        }
        if (!finished) return
        val wasManual = manualQso
        resumeAutoAfterManual()
        if (wasManual || !_status.value.autoProgram.mode.enabled) return
        if (p.txText == null) {
            // 没有收尾报文（例如重试超限失败）：立刻让第 2 层决定下一步
            runAutoAction(scheduler.onQsoFinished(p.state == QsoState.DONE, utcNowMs))
        } else {
            // 还有 RR73/73 要发：发完再收口（见 txTick）
            pendingAutoFinish = true
        }
    }

    /**
     * 第 2 层：在「无进行中 QSO」或「已发 CQ 等回应者」时，把本时隙解码交给调度器，
     * 并按返回的动作执行（发 CQ / 排队主叫 / 应答 CQ / 处理定向报文 / 保持监听）。
     */
    private fun runAutoProgram(batch: List<DecodeResult>, utcNowMs: Long) {
        val st = _status.value
        if (!st.txEnabled) return
        if (!st.autoProgram.mode.enabled) return
        val action = scheduler.onDecoded(
            messages = batch,
            filter = currentFilter(),
            worked = _worked.value,
            utcNowMs = utcNowMs,
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
                // 混合模式应答状态且暂无 CQ 台：保持接收、不发
                if (_status.value.txArmed || _status.value.qso.active) {
                    val p = qsoEngine.stop()
                    _status.update {
                        it.copy(qso = p, txArmed = false, status = "自动程序：监听中（等待其他人的 CQ）")
                    }
                }
            }
            is AutoAction.Stop -> stopAutoByProtection(action.reason)
            AutoAction.None -> Unit
        }
    }

    /** 保护限制触发：停止第 2 层调度并切回「0 手动模式」（文档 §2.5）。 */
    private fun stopAutoByProtection(reason: String) {
        scheduler.markProtectedStop()
        manualQso = false
        stopTransmit(resumeAuto = false)
        _status.update {
            it.copy(
                autoProgram = it.autoProgram.copy(mode = AutoMode.MANUAL),
                autoPhaseLabel = null,
                autoQueueSize = 0,
                status = "自动程序已停止并切回「0 手动模式」：$reason（发送总开关不变）",
            )
        }
        persist { it.copy(auto = it.auto.copy(mode = AutoMode.MANUAL)) }
    }

    /** 把第 2 层阶段/队列同步到 UI 状态。 */
    private fun publishAutoState() {
        val st = _status.value
        if (!st.autoProgram.mode.enabled) return
        val label = if (manualQso) "已暂停（手动接管）" else phaseLabel(scheduler.currentPhase())
        val q = scheduler.queuedCount()
        if (st.autoPhaseLabel != label || st.autoQueueSize != q) {
            _status.update { it.copy(autoPhaseLabel = label, autoQueueSize = q) }
        }
    }

    /**
     * 自动应答选中的目标，并把整段 QSO 交给状态机跑完。
     *
     * 支持四类目标：[AutoTargetKind.CQ]（应答 CQ）、[AutoTargetKind.CALL]（对方呼叫我）、
     * [AutoTargetKind.REPORT]（对方给我报告）、[AutoTargetKind.ROGER]（对方 Roger 我的报告）。
     */
    private fun startAutoTarget(t: AutoTarget) {
        val st = _status.value
        // 时隙自动对应：固定到对方时隙的相反周期
        if (pinToTargetSlot(t.slotUtcMs) == null) pinnedTxParity = null
        relockAutoParityIfNeeded()
        if (!armPlayback()) return

        lastTxSlotIndex = -1L
        // 同频发射：把红线跟到目标频率（异频发射则保持设定频率）
        if (st.sameFreqTx) setTxFreq(t.df)

        val p = when (t.kind) {
            AutoTargetKind.CQ -> qsoEngine.startResponderQso(t.call, t.grid)
            AutoTargetKind.CALL -> qsoEngine.startCallerQso(t.call, t.grid, t.snr)
            AutoTargetKind.REPORT -> qsoEngine.respondToReport(t.call, t.report ?: return, t.snr)
            AutoTargetKind.ROGER -> qsoEngine.respondToRoger(t.call, t.report ?: return, t.snr, t.slotUtcMs)
        }
        if (!p.active && p.state != QsoState.DONE) return
        _status.update { it.copy(qso = p, txArmed = true, status = "自动程序：应答 ${t.call}") }
        // ROGER 场景一上来即完成：走统一收尾（写日志 + 通知第 2 层）
        if (p.state == QsoState.DONE) applyQsoProgress(p)
    }

    /** 由设置构造显示过滤条件（操作页与自动程序共用）。 */
    private fun currentFilter(): DecodeFilterState {
        val s = latestSettings
        return DecodeFilterState(
            tags = s.filterTags,
            query = s.callFilter,
            ignoredCalls = s.ignoredCalls,
        )
    }

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
                // 一次性发射：不推进 QSO 状态机，发完即解除武装
                manualInFlight = false
                resumeAutoAfterManual()
                _status.update {
                    it.copy(
                        manualTxText = null,
                        txArmed = false,
                        status = "一次性发射完成：${it.lastTxText ?: ""}",
                    )
                }
            } else {
                qsoEngine.onTransmitted()
                val p = qsoEngine.progress()
                val finished = p.state == QsoState.DONE || p.state == QsoState.FAILED
                if (finished) {
                    // 最后一条（RR73/73）已发完：只解除「目标时隙固定」，
                    // **保留当前发射周期**——下一段 QSO 沿用同一周期。
                    // 若在这里把周期清掉，下一段会按当前时间重锁，导致偶/奇来回跳时隙。
                    pinnedTxParity = null
                }
                _status.update { it.copy(qso = p, txArmed = if (finished) false else it.txArmed) }
                // 收尾报文已发出：现在才让第 2 层决定下一步，避免覆盖掉这条 RR73/73
                if (finished && pendingAutoFinish) {
                    pendingAutoFinish = false
                    runAutoAction(
                        scheduler.onQsoFinished(p.state == QsoState.DONE, AudioEngine.utcNowMs()),
                    )
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

        if (plan.targetSlotIndex == lastTxSlotIndex) return
        if (now < plan.startAtMs) return

        // 已晚于计划起点：用缩水的前导补偿，补偿不够则放弃本时隙
        val lateness = now - plan.startAtMs
        if (lateness > preambleMs + TX_START_WINDOW_MS) return
        val effectivePreamble = effectivePreambleMs(preambleMs, lateness)

        lastTxSlotIndex = plan.targetSlotIndex
        manualInFlight = st.manualTxText != null
        val genAtPlan = engineGen
        val abortAtPlan = txAbortGen
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
        val pcm = try {
            Ft8Engine.encode(text, st.selectedFreqHz.toFloat(), st.protocol, 12000)
        } catch (e: Exception) {
            _status.update { it.copy(status = "发射异常: ${e.message}") }
            return
        }
        // 解码/编码期间可能被「停止发射」或换了引擎：丢弃
        if (genAtPlan != engineGen || abortAtPlan != txAbortGen) return

        try {
            _status.update { it.copy(txing = true, lastTxText = text, lastTxSlotMs = slotStartMs) }
            val t0 = AudioEngine.utcNowMs()
            val written = AudioEngine.playTx(pcm, effPtt, effLead)
            // 保护限制「单次发射总时长」（文档 §2.5）：自动程序开启时累计实际发射时长
            if (_status.value.autoProgram.mode.enabled) {
                scheduler.addTxMs(AudioEngine.utcNowMs() - t0)
            }
            if (written <= 0) {
                _status.update { it.copy(status = "发射失败（写入 $written 帧）") }
            }
        } catch (e: Exception) {
            _status.update { it.copy(status = "发射异常: ${e.message}") }
        } finally {
            _status.update { it.copy(txing = false) }
            txJustFinished = true
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
                    it.copy(status = if (written > 0) "已播放测试音（$written 帧）" else "测试音失败（$written）")
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

        // 自适应强度：底部锚定「噪声底」（帧内 10 分位，抗强信号），顶部由滚动峰值
        // 决定，跨度钳制在 [MIN_SPAN, MAX_SPAN]。这样本地强台出现时，比它低 40~60 dB
        // 的弱台仍落在色带内，而不是被整体压成黑色。
        val hist = IntArray(256)
        var batchMax = 0
        for (v in data) {
            val u = v.toInt() and 0xFF
            hist[u]++
            if (u > batchMax) batchMax = u
        }
        val pctTarget = (data.size / 10).coerceAtLeast(1)
        var acc = 0
        var p10 = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= pctTarget) {
                p10 = i
                break
            }
        }
        // 慢速平滑，避免底色随强台闪烁
        wfFloor = if (wfFloor < 0) p10 else (wfFloor * 7 + p10) / 8
        wfPeak = maxOf(batchMax, wfPeak - 4)
        val floor = wfFloor
        val span = (wfPeak - floor + 12)
            .coerceIn(WaterfallColors.MIN_SPAN, WaterfallColors.MAX_SPAN)
        val invSpan = 255f / span

        val lut = WaterfallColors.rampLut
        for (i in 0 until shift) {
            val srcBase = (rows - shift + i) * bins
            val dstBase = (startRow + i) * bins
            for (b in 0 until bins) {
                val u = data[srcBase + b].toInt() and 0xFF
                val idx = ((u - floor) * invSpan).toInt().coerceIn(0, 255)
                pixels[dstBase + b] = lut[idx]
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

    private companion object {
        fun slotMsOf(protocol: Protocol): Int = if (protocol == Protocol.FT4) 7500 else 15000
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
 * `msToNextSlot` / `slotProgress` / `voxLevelDb` / `voxOpen` 只在跨越本窗口边界时发布一次，
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
     * FT8 报文 12.64 s 远短于时隙 15 s（FT4 4.48 s / 7.5 s），而解码结果在时隙结束后几百
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

/** 自动周期模式的前导余量：给播放流准备留出的额外时间（ms）。 */
internal const val AUTO_PARITY_LEAD_MARGIN_MS = 500L

/**
 * 时隙奇偶标记（0/1）：按 UTC 时隙起点取整后的槽位序号取奇偶。
 *
 * 每 60 s 恰好 4 个 15 s（FT8）时隙，因此：
 * **第 1、3 个时隙为 0，第 2、4 个时隙为 1**（FT4 为 8 个 7.5 s 时隙，同样逢奇为 1）。
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
