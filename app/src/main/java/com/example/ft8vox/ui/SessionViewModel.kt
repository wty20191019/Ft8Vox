package com.example.ft8vox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ft8vox.container
import com.example.ft8vox.data.BandPlan
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
import com.example.ft8vox.qso.AutoDecision
import com.example.ft8vox.qso.AutoLevel
import com.example.ft8vox.qso.AutoProgramSelector
import com.example.ft8vox.qso.AutoProgramSettings
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
    /** 发射总开关：false=只接收（默认），true=允许发射；仅会话内有效，不持久化。 */
    val txEnabled: Boolean = false,
    /** 我方发射所在的周期：0=偶数，1=奇数（自动按手机 UTC 时间锁定）。 */
    val txParity: Int = 0,
    /** 已确认发射（防误发闸门）。 */
    val txArmed: Boolean = false,
    /** 距离下一个我方发射时隙的毫秒数。 */
    val txCountdownMs: Long = 0,
    /** 正在播放发射音频。 */
    val txing: Boolean = false,
    val qso: QsoProgress = QsoProgress(),
    /** 最近一次发射的报文与所属时隙起点。 */
    val lastTxText: String? = null,
    val lastTxSlotMs: Long = 0,
    /** 选中的收听/应答频率（对应瀑布绿线）；未选时为 null。 */
    val rxFreqHz: Int? = null,
    // ---- JTDX 风格操作（阶段 7c） ----
    /** 锁定发射频率（应答时 TX 不跟随 RX）。 */
    val holdTxFreq: Boolean = false,
    /** 自动程序策略（来自设置；对应 FT8CN「自动程序」菜单）。 */
    val autoProgram: AutoProgramSettings = AutoProgramSettings(),
    /** 自动程序是否已启用（需用户确认；按「单次通联」在 QSO 结束后自动解除）。 */
    val autoArmed: Boolean = false,
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

    /** 已通联索引（呼号/网格/前缀），供操作页过滤与高亮、Call 1st 共用。 */
    private val _worked = MutableStateFlow(WorkedIndex.EMPTY)
    val workedIndex: StateFlow<WorkedIndex> = _worked.asStateFlow()

    private val qsoEngine = QsoEngine(maxRetries = 6)

    private var pollJob: Job? = null
    private var txJob: Job? = null
    private var wfInfo: WaterfallInfo? = null
    private var wfPixels = IntArray(0)
    private var wfPeak = 0

    private var lastSlotsDecoded = 0L
    private var pendingDecodes = mutableListOf<DecodeResult>()
    private var lastTxSlotIndex = -1L
    private var txJustFinished = false
    /** 当前正在播放的报文是否为「一次性发射」（发完不再推进 QSO 状态机）。 */
    private var manualInFlight = false

    /** 自动周期模式下锁定的发射周期（0/1）；未锁定为 null。 */
    private var autoParity: Int? = null

    /**
     * 「设为目标」后固定的发射周期（目标接收时隙的相反周期）；null 表示未固定。
     *
     * 目标固定期间 [lockAutoParity] 直接返回该值，保证与目标交替收发（时隙自动对应）。
     */
    private var pinnedTxParity: Int? = null

    /** 最近一次读到的设置（供 start() 组装 native 配置）。 */
    private var latestSettings = AppSettings()

    /** 最近一次下发给 native 的解码参数，避免设置流每次发射都重复下发。 */
    private var lastDecodeParams = DecodeParams()

    /** 最近一次下发给 native 的 VOX/PTT 配置；引擎重建后置空以强制重下发。 */
    private var lastVoxConfig: VoxConfig? = null

    /** 最近一次下发给 native 的采集增益（dB），U7c。 */
    private var lastInputGainDb: Int? = null

    /** 最近一次生效的输出设备 id（U7c）；变化时需重开播放流。 */
    private var lastOutputDeviceId: Int? = null

    /** 最近一次生效的输入设备 id（U7c）；变化需重开采集流，运行中提示下次生效。 */
    private var lastInputDeviceId: Int? = null

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
                holdTxFreq = s.holdTxFreq,
                autoProgram = s.auto,
                autoArmed = if (s.auto.level == AutoLevel.MANUAL) false else cur.autoArmed,
                // 运行中不允许改协议（需重建引擎），忽略设置里的旧值
                protocol = if (cur.running) cur.protocol else s.protocol,
                slotMs = if (cur.running) cur.slotMs else slotMsOf(s.protocol),
            )
        }
        qsoEngine.configure(s.myCall, s.myGrid, s.maxRetries)
        applyDecodeParams(s)
        applyVox(s)
        applyAudio(s)
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
     * - 采集增益：热生效；
     * - 输出设备：变化时关闭播放流，下次 [armPlayback] 用新设备重开；
     * - 输入设备：需重开采集流，运行中时不打断，提示下次开始接收生效。
     */
    private fun applyAudio(s: AppSettings, force: Boolean = false) {
        if (force || s.inputGainDb != lastInputGainDb) {
            lastInputGainDb = s.inputGainDb
            AudioEngine.setInputGain(s.inputGainDb)
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

    private fun persist(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    // ---- 配置 ----

    fun selectProtocol(protocol: Protocol) {
        if (_status.value.running) return
        _status.update { it.copy(protocol = protocol, slotMs = slotMsOf(protocol)) }
        persist { it.copy(protocolName = protocol.name) }
    }

    fun selectFrequency(hz: Int) {
        val v = clampFreq(hz)
        val hold = _status.value.holdTxFreq
        _status.update {
            it.copy(rxFreqHz = v, selectedFreqHz = if (hold) it.selectedFreqHz else v)
        }
        if (!hold) persist { it.copy(selectedFreqHz = v) }
    }

    /** 直接微调我方发射频率（TX）。 */
    fun nudgeTxFreq(delta: Int) {
        val v = clampFreq(_status.value.selectedFreqHz + delta)
        _status.update { it.copy(selectedFreqHz = v) }
        persist { it.copy(selectedFreqHz = v) }
    }

    fun setHoldTxFreq(value: Boolean) {
        _status.update { it.copy(holdTxFreq = value) }
        persist { it.copy(holdTxFreq = value) }
    }

    /** 设置自动程序等级；切到「手动」时立即解除启用。 */
    fun setAutoLevel(level: AutoLevel) {
        _status.update {
            it.copy(
                autoProgram = it.autoProgram.copy(level = level),
                autoArmed = if (level == AutoLevel.MANUAL) false else it.autoArmed,
            )
        }
        persist { it.copy(auto = it.auto.copy(level = level)) }
    }

    /** 改一项自动程序策略开关。 */
    fun setAutoOption(transform: (AutoProgramSettings) -> AutoProgramSettings) {
        _status.update { it.copy(autoProgram = transform(it.autoProgram)) }
        persist { it.copy(auto = transform(it.auto)) }
    }

    fun setFilterTags(tags: Set<DecodeFilterTag>) {
        persist { it.copy(filterTags = tags) }
    }

    /** 忽略一个呼号（不再显示其解码，也不参与 Call 1st）。 */
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

    /** 启用自动程序（UI 需先弹防误发确认）。 */
    fun armAutoProgram() {
        val p = _status.value.autoProgram
        if (p.level == AutoLevel.MANUAL) {
            _status.update { it.copy(status = "请先选择自动程序等级") }
            return
        }
        _status.update { it.copy(autoArmed = true, status = "自动程序已启用（${p.level.label}）") }
    }

    fun disarmAutoProgram() {
        _status.update { it.copy(autoArmed = false, status = "自动程序已关闭") }
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
     * 发射总开关（默认关 = 只接收）。
     *
     * 关闭：立即停发并解除周期锁定；开启：按手机 UTC 时间锁定「下一个来得及准备的时隙」，
     * 随后在下一个我方时隙发射。因不再提供周期设置，用户通过在不同时机开关总开关来
     * 决定从哪个时隙开始发射。
     */
    fun setTxEnabled(enabled: Boolean) {
        if (!enabled) {
            autoParity = null
            pinnedTxParity = null
            stopTransmit()
            _status.update { it.copy(txEnabled = false, status = "发射已关闭（只接收）") }
            return
        }
        val p = lockAutoParity()
        _status.update { it.copy(txEnabled = true, txParity = p, status = "发射已开启（${parityLabel(p)}）") }
    }

    /** 按手机 UTC 时间锁定自动周期：下一个「距起点 >= 前导余量」的时隙。 */
    private fun lockAutoParity(): Int {
        pinnedTxParity?.let {
            autoParity = it
            return it
        }
        val st = _status.value
        val now = AudioEngine.utcNowMs()
        val p = nextSlotParity(now, st.slotMs.toLong(), txPreambleMs().toLong() + AUTO_PARITY_LEAD_MARGIN_MS)
        autoParity = p
        return p
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
        if (st.slotMs <= 0 || targetSlotUtcMs <= 0) {
            pinnedTxParity = null
            return
        }
        val targetParity = ((targetSlotUtcMs / st.slotMs) % 2L).toInt()
        val mine = 1 - targetParity
        pinnedTxParity = mine
        autoParity = mine
        _status.update { it.copy(txParity = mine, status = "已设为目标：时隙自动对应（${parityLabel(mine)}周期）") }
    }

    /** 清除「设为目标」时的时隙固定（取消目标 / 关闭发射时）。 */
    fun clearTargetSlot() {
        pinnedTxParity = null
    }

    /** 重新锁定自动周期（进入新 QSO / 一次性发射前调用）。 */
    private fun relockAutoParityIfNeeded() {
        _status.update { it.copy(txParity = lockAutoParity()) }
    }

    fun clearMessages() {
        _messages.value = emptyList()
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

        wfInfo = AudioEngine.waterfallInfo()
        wfPixels = IntArray(0)
        wfPeak = 0
        _waterfall.value = null

        // 引擎刚重建：强制把 VOX/PTT 配置下发给新实例
        applyVox(latestSettings, force = true)
        applyAudio(latestSettings, force = true)

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
    }

    fun stop() {
        stopTransmit()
        pollJob?.cancel()
        pollJob = null
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
                autoArmed = false,
                qso = qsoEngine.stop(),
            )
        }
    }

    // ---- 发射 / QSO ----

    /** 是否已配置呼号，可开始 QSO。 */
    val canOperate: Boolean get() = _status.value.myCall.isNotEmpty()

    /**
     * 开始呼叫 CQ（调用前应由 UI 弹出防误发确认）。
     * 若采集未启动会先自动启动。
     */
    fun startCq() {
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

    /** 应答指定 CQ（调用前应由 UI 弹出防误发确认）。 */
    fun answer(call: String, grid: String?, theirDf: Int? = null) {
        if (!canOperate) {
            _status.update { it.copy(status = "请先填写呼号") }
            return
        }
        if (!_status.value.txEnabled) {
            _status.update { it.copy(status = "请先打开「发射」开关") }
            return
        }
        // 未锁定时把 TX 跟到对方的 DF（点谁打谁）
        if (theirDf != null && theirDf > 0) {
            val hold = _status.value.holdTxFreq
            val v = clampFreq(theirDf)
            _status.update {
                it.copy(rxFreqHz = v, selectedFreqHz = if (hold) it.selectedFreqHz else v)
            }
            if (!hold) persist { it.copy(selectedFreqHz = v) }
        }
        if (!_status.value.running) start()
        if (!_status.value.running) return

        relockAutoParityIfNeeded()
        if (!armPlayback()) return
        lastTxSlotIndex = -1L
        val p = qsoEngine.answer(call, grid)
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
     */
    fun sendOnce(text: String) {
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

        txJob?.cancel()
        val (pttMs, leadMs) = txPreambleParts()
        txJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val st = _status.value
                val pcm = Ft8Engine.encode(trimmed, st.selectedFreqHz.toFloat(), st.protocol, 12000)
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

    /** 紧急停止发射：解除武装并中止可能正在进行的播放。 */
    fun stopTransmit() {
        txJob?.cancel()
        txJob = null
        AudioEngine.stopPlayback()
        autoParity = null
        val p = qsoEngine.stop()
        _status.update {
            it.copy(
                txArmed = false,
                txing = false,
                manualTxText = null,
                autoArmed = false,
                qso = p,
                txCountdownMs = 0,
                status = "已停止发射",
            )
        }
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
        txJob?.cancel()
        val (pttMs, leadMs) = txPreambleParts()
        txJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val pcm = Ft8Engine.encode(text, st.selectedFreqHz.toFloat(), st.protocol, 12000)
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
            // 发给我的报文自动把 RX 跟过去（便于瀑布绿线指示当前对手）
            val my = _status.value.myCall
            decoded.forEach { m ->
                if (m.df > 0 && MessageParser.parse(m.text).addressedTo(my)) {
                    _status.update { s -> s.copy(rxFreqHz = m.df) }
                }
            }
            // U7d：有报文叫我呼号时短促提示
            if (shouldAlertMyCall(latestSettings.beepOnMyCall, my, decoded.map { it.text })) {
                alertTone.beep()
            }
        }

        // 2) 状态
        val s = AudioEngine.state()
        if (s != null) {
            _status.update {
                it.copy(
                    inSlot = s.inSlot,
                    inputRate = s.inputRate,
                    slotMs = s.slotMs.toInt(),
                    msToNextSlot = s.msToNextSlot,
                    slotProgress = s.slotProgress,
                    slotParity = if (s.slotMs > 0) ((s.utcNowMs / s.slotMs) % 2L).toInt() else 0,
                    slotsDecoded = s.slotsDecoded,
                    droppedSamples = s.droppedSamples,
                    voxOpen = s.voxOpen,
                    voxLevelDb = s.voxLevelDb,
                )
            }

            // 3) 每完成一个接收时隙，交给 QSO 状态机或 Call 1st
            if (s.slotsDecoded > lastSlotsDecoded) {
                lastSlotsDecoded = s.slotsDecoded
                val st = _status.value
                val completedIdx = if (s.slotMs > 0) (s.utcNowMs / s.slotMs) - 1 else -1L
                val completedParity = if (completedIdx >= 0) (completedIdx % 2L).toInt() else -1
                val isReceiveSlot = completedParity == -1 || completedParity != st.txParity
                if (isReceiveSlot) {
                    val batch = pendingDecodes.toList()
                    if (st.qso.active) {
                        applyQsoProgress(qsoEngine.onDecoded(batch, s.utcNowMs))
                    } else if (st.autoArmed) {
                        runAutoProgram(batch)
                    }
                }
                pendingDecodes = mutableListOf()
            }
        }

        // 4) 瀑布行流
        pollWaterfall()

        // 5) 发射调度
        txTick()
    }

    /** 把状态机进度同步到 UI 状态，并把完成的通联写入日志库。 */
    private fun applyQsoProgress(p: QsoProgress) {
        val finished = p.state == QsoState.DONE || p.state == QsoState.FAILED
        if (finished) {
            autoParity = null
            pinnedTxParity = null
        }
        _status.update {
            it.copy(
                qso = p,
                status = "QSO：${p.description}",
                // 「单次通联」开启时，一次 QSO 结束即解除自动程序，避免无限自动呼叫
                autoArmed = if (finished && it.autoProgram.singleQso) false else it.autoArmed,
            )
        }
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
                // 台站备注作为默认 COMMENT 写入新记录（快照，可留空）
                comment = latestSettings.note.trim().ifEmpty { null },
            )
            viewModelScope.launch(Dispatchers.IO) {
                qsoRepo.add(entity)
            }
            _status.update { it.copy(status = "已记录通联：${entry.theirCall}") }
        }
    }

    /**
     * 自动程序：在「无进行中 QSO」时，按策略选台并自动应答 / 自动发 CQ。
     *
     * 等级 4+ 在无可答目标时自动发 CQ（自动搜索）；其余等级保持待命。
     */
    private fun runAutoProgram(batch: List<DecodeResult>) {
        val st = _status.value
        if (!st.txEnabled) return
        val decision = AutoProgramSelector.decide(
            messages = batch,
            program = st.autoProgram,
            filter = currentFilter(),
            worked = _worked.value,
            myCall = st.myCall,
            myGrid = st.myGrid,
        )
        when (decision) {
            is AutoDecision.AnswerCq -> startAutoTarget(decision.target)
            is AutoDecision.AnswerReport -> startAutoTarget(decision.target)
            AutoDecision.CallCq -> startAutoSearch()
            AutoDecision.None -> Unit
        }
    }

    /** 自动应答选中的目标（对方的 CQ，或对方直接发来的信号报告）。 */
    private fun startAutoTarget(t: AutoTarget) {
        val st = _status.value
        relockAutoParityIfNeeded()
        if (!armPlayback()) return

        lastTxSlotIndex = -1L
        val hold = st.holdTxFreq
        val v = clampFreq(t.df)
        _status.update {
            it.copy(rxFreqHz = v, selectedFreqHz = if (hold) it.selectedFreqHz else v)
        }
        if (!hold) persist { it.copy(selectedFreqHz = v) }

        val p = if (t.kind == AutoTargetKind.REPORT && t.report != null) {
            qsoEngine.respondToReport(t.call, t.report, t.snr)
        } else {
            qsoEngine.answer(t.call, t.grid)
        }
        if (!p.active) return
        _status.update {
            it.copy(qso = p, txArmed = true, status = "自动程序：应答 ${t.call}")
        }
    }

    /** 自动搜索：无可答目标时自动发 CQ（等级 4+）。 */
    private fun startAutoSearch() {
        if (!canOperate) return
        relockAutoParityIfNeeded()
        if (!armPlayback()) return
        lastTxSlotIndex = -1L
        val p = qsoEngine.startCq()
        _status.update { it.copy(qso = p, txArmed = true, status = "自动程序：搜索中（CQ）") }
    }

    /** 由设置构造显示过滤条件（操作页与 Call 1st 共用）。 */
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
                _status.update { it.copy(qso = p, txArmed = if (finished) false else it.txArmed) }
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
        val plan = planTx(now, slotMs, st.txParity, preambleMs)

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
        txJob = viewModelScope.launch(Dispatchers.IO) {
            transmit(text, plan.targetStartMs, effectivePreamble)
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
    private fun txPreambleMs(): Int = txPreambleParts().let { it.first + it.second }

    /** 阻塞式播放一段发射波形（调用线程为 IO，不阻塞 UI 与轮询）。 */
    private fun transmit(text: String, slotStartMs: Long, preambleMs: Long) {
        val st = _status.value
        val (pttMs, leadMs) = txPreambleParts()
        // 缩水后的前导：优先保证前导音（键控 VOX），剩余给前导静音
        val eff = preambleMs.coerceAtLeast(0L)
        val effLead = minOf(leadMs.toLong(), eff).toInt()
        val effPtt = minOf(pttMs.toLong(), eff - effLead).toInt()
        try {
            val pcm = Ft8Engine.encode(text, st.selectedFreqHz.toFloat(), st.protocol, 12000)
            _status.update { it.copy(txing = true, lastTxText = text, lastTxSlotMs = slotStartMs) }
            val written = AudioEngine.playTx(pcm, effPtt, effLead)
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

        // 自适应强度：跟踪滚动峰值，按固定动态范围拉伸，避免不同增益下过黑/过亮
        var batchMax = 0
        for (v in data) {
            val u = v.toInt() and 0xFF
            if (u > batchMax) batchMax = u
        }
        wfPeak = maxOf(batchMax, wfPeak - 4)
        val floor = (wfPeak - WaterfallColors.DYNAMIC_RANGE).coerceAtLeast(0)
        val span = (wfPeak - floor).coerceAtLeast(30)
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
        pollJob?.cancel()
        txJob?.cancel()
        AudioEngine.stopCapture()
        AudioEngine.release()
        alertTone.release()
        super.onCleared()
    }

    private companion object {
        /** 允许在时隙起点后多久内开始写播放（0.5 s 前导静音可吸收该延迟）。 */
        const val TX_START_WINDOW_MS = 1200L

        fun slotMsOf(protocol: Protocol): Int = if (protocol == Protocol.FT4) 7500 else 15000
    }
}

/** 一次发射的调度结果：目标时隙、数据起点与播放起点。 */
internal data class TxPlan(
    /** 目标时隙序号（`UTCms / slotMs`），用于去重。 */
    val targetSlotIndex: Long,
    /** 数据（FT8 波形）预定起点，等于目标时隙起点。 */
    val targetStartMs: Long,
    /** 播放起点，= 数据起点 − 前导时长。 */
    val startAtMs: Long,
)

/**
 * 计算本次发射的目标时隙与播放起点（纯函数，便于单测）。
 *
 * 无前导时就地发射；有前导时需提前 `preambleMs` 启动播放，让前导在
 * 数据到达前键控 VOX，因此只能瞄准后续的我方周期时隙。
 */
internal fun planTx(nowMs: Long, slotMs: Long, txParity: Int, preambleMs: Long): TxPlan {
    require(slotMs > 0) { "slotMs must be positive" }
    val slotIdx = nowMs / slotMs
    val parity = (slotIdx % 2L).toInt()
    val targetIdx = if (parity == txParity) {
        if (preambleMs <= 0) slotIdx else slotIdx + 2
    } else {
        slotIdx + 1
    }
    val targetStart = targetIdx * slotMs
    return TxPlan(targetIdx, targetStart, targetStart - preambleMs)
}

/** 迟到后缩水的前导：至少为 0，用于把数据重新对齐到时隙起点。 */
internal fun effectivePreambleMs(preambleMs: Long, latenessMs: Long): Long =
    (preambleMs - latenessMs).coerceAtLeast(0L)

/** 自动周期模式的前导余量：给播放流准备留出的额外时间（ms）。 */
internal const val AUTO_PARITY_LEAD_MARGIN_MS = 500L

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

/** 周期文案（用于状态提示）。 */
internal fun parityLabel(parity: Int): String =
    if (parity == TX_PARITY_ODD) "奇数周期" else "偶数周期"

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
