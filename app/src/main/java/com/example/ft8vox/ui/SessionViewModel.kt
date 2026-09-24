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
import com.example.ft8vox.engine.AudioEngine
import com.example.ft8vox.engine.DecodeParams
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.engine.Ft8Config
import com.example.ft8vox.engine.Ft8Engine
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.engine.WaterfallInfo
import com.example.ft8vox.qso.CallFirstSelector
import com.example.ft8vox.qso.DecodeFilterState
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.qso.QsoEngine
import com.example.ft8vox.qso.QsoLogEntry
import com.example.ft8vox.qso.QsoProgress
import com.example.ft8vox.qso.QsoState
import com.example.ft8vox.qso.WorkedIndex
import com.example.ft8vox.data.settings.CallFirstMode
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
    val selectedFreqHz: Int = 1000,
    // ---- 台站（来自设置） ----
    val myCall: String = "",
    val myGrid: String = "",
    /** 当前波段（无 CAT，由用户指定，用于记录与 ADIF 导出）。 */
    val band: String = BandPlan.DEFAULT_BAND,
    // ---- 发射/QSO ----
    /** 我方发射所在的周期：0=偶数，1=奇数。 */
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
    /** Call 1st 自动应答策略（来自设置）。 */
    val callFirst: CallFirstMode = CallFirstMode.OFF,
    /** Call 1st 是否已武装（需用户确认；QSO 结束后自动解除）。 */
    val callFirstArmed: Boolean = false,
    /** 待发的一次性报文（长按解码行选择；发完即清空）。 */
    val manualTxText: String? = null,
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

    /** 最近一次读到的设置（供 start() 组装 native 配置）。 */
    private var latestSettings = AppSettings()

    /** 最近一次下发给 native 的解码参数，避免设置流每次发射都重复下发。 */
    private var lastDecodeParams = DecodeParams()

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
                selectedFreqHz = s.selectedFreqHz,
                txParity = s.txParity,
                holdTxFreq = s.holdTxFreq,
                callFirst = s.callFirst,
                callFirstArmed = if (s.callFirst == CallFirstMode.OFF) false else cur.callFirstArmed,
                // 运行中不允许改协议（需重建引擎），忽略设置里的旧值
                protocol = if (cur.running) cur.protocol else s.protocol,
                slotMs = if (cur.running) cur.slotMs else slotMsOf(s.protocol),
            )
        }
        qsoEngine.configure(s.myCall, s.myGrid, s.maxRetries)
        applyDecodeParams(s)
    }

    /** 运行中把「热生效」的解码参数下发给 native（频率范围/OSR 需重启，见 [start]）。 */
    private fun applyDecodeParams(s: AppSettings) {
        val p = s.decodeParams
        if (p == lastDecodeParams) return
        lastDecodeParams = p
        if (_status.value.running) AudioEngine.setDecodeParams(p)
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

    fun setCallFirst(mode: CallFirstMode) {
        _status.update {
            it.copy(callFirst = mode, callFirstArmed = if (mode == CallFirstMode.OFF) false else it.callFirstArmed)
        }
        persist { it.copy(callFirst = mode) }
    }

    fun setCqOnly(value: Boolean) {
        persist { it.copy(cqOnly = value) }
    }

    fun setExcludeWorked(value: Boolean) {
        persist { it.copy(excludeWorked = value) }
    }

    fun setCallFilter(value: String) {
        persist { it.copy(callFilter = value) }
    }

    /** 武装 Call 1st（UI 需先弹防误发确认）。 */
    fun armCallFirst() {
        val mode = _status.value.callFirst
        if (mode == CallFirstMode.OFF) {
            _status.update { it.copy(status = "请先选择 Call 1st 策略") }
            return
        }
        _status.update { it.copy(callFirstArmed = true, status = "Call 1st 已启用（${mode.label}）") }
    }

    fun disarmCallFirst() {
        _status.update { it.copy(callFirstArmed = false, status = "Call 1st 已关闭") }
    }

    fun setBand(name: String) {
        if (!BandPlan.contains(name)) return
        _status.update { it.copy(band = name) }
        persist { it.copy(band = name) }
    }

    fun setTxParity(parity: Int) {
        val v = parity.coerceIn(0, 1)
        _status.update { it.copy(txParity = v) }
        persist { it.copy(txParity = v) }
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

        val rate = AudioEngine.startCapture(preferredRate())
        if (rate <= 0) {
            AudioEngine.release()
            wfInfo = null
            _status.update { it.copy(status = "采集启动失败（错误码 $rate）") }
            return
        }

        _status.update {
            it.copy(running = true, inputRate = rate, status = "接收中（设备采样率 $rate Hz）")
        }
        startPolling()
    }

    fun stop() {
        stopTransmit()
        pollJob?.cancel()
        pollJob = null
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
                txArmed = false,
                txing = false,
                manualTxText = null,
                callFirstArmed = false,
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
        if (!_status.value.running) start()
        if (!_status.value.running) return

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
        if (_status.value.qso.active) {
            _status.update { it.copy(status = "QSO 进行中，暂不逐条发送") }
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!_status.value.running) start()
        if (!_status.value.running) return

        if (!armPlayback()) return
        lastTxSlotIndex = -1L
        _status.update {
            it.copy(manualTxText = trimmed, txArmed = true, status = "待发（一次性）：$trimmed")
        }
    }

    /** 紧急停止发射：解除武装并中止可能正在进行的播放。 */
    fun stopTransmit() {
        txJob?.cancel()
        txJob = null
        AudioEngine.stopPlayback()
        val p = qsoEngine.stop()
        _status.update {
            it.copy(
                txArmed = false,
                txing = false,
                manualTxText = null,
                callFirstArmed = false,
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
        txJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val pcm = Ft8Engine.encode(text, st.selectedFreqHz.toFloat(), st.protocol, 12000)
                _status.update { it.copy(txing = true) }
                val written = AudioEngine.play(pcm)
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
        val rate = AudioEngine.startPlayback(preferredRate())
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
            pendingDecodes.addAll(decoded)
            // 发给我的报文自动把 RX 跟过去（便于瀑布绿线指示当前对手）
            val my = _status.value.myCall
            decoded.forEach { m ->
                if (m.df > 0 && MessageParser.parse(m.text).addressedTo(my)) {
                    _status.update { s -> s.copy(rxFreqHz = m.df) }
                }
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
                    } else if (st.callFirstArmed) {
                        autoCallFirst(batch)
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
        _status.update {
            it.copy(
                qso = p,
                status = "QSO：${p.description}",
                // 一次自动 QSO 结束即解除 Call 1st，避免无限自动呼叫
                callFirstArmed = if (finished) false else it.callFirstArmed,
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
                freqHz = BandPlan.dialHz(st.band),
                mode = st.protocol.name,
                reportSent = entry.reportSent,
                reportReceived = entry.reportReceived,
            )
            viewModelScope.launch(Dispatchers.IO) {
                qsoRepo.add(entity)
            }
            _status.update { it.copy(status = "已记录通联：${entry.theirCall}") }
        }
    }

    /** Call 1st：从本时隙解码中挑一个 CQ 自动应答（需已武装）。 */
    private fun autoCallFirst(batch: List<DecodeResult>) {
        val st = _status.value
        val cand = CallFirstSelector.pick(
            messages = batch,
            mode = st.callFirst,
            filter = currentFilter(),
            worked = _worked.value,
            myCall = st.myCall,
        ) ?: return
        if (!armPlayback()) return

        lastTxSlotIndex = -1L
        val hold = st.holdTxFreq
        val v = clampFreq(cand.df)
        _status.update {
            it.copy(rxFreqHz = v, selectedFreqHz = if (hold) it.selectedFreqHz else v)
        }
        if (!hold) persist { it.copy(selectedFreqHz = v) }

        val p = qsoEngine.answer(cand.call, cand.grid)
        if (!p.active) return
        _status.update {
            it.copy(qso = p, txArmed = true, status = "Call 1st：应答 ${cand.call}")
        }
    }

    /** 由设置构造显示过滤条件（操作页与 Call 1st 共用）。 */
    private fun currentFilter(): DecodeFilterState {
        val s = latestSettings
        return DecodeFilterState(
            cqOnly = s.cqOnly,
            excludeWorked = s.excludeWorked,
            query = s.callFilter,
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

        val st = _status.value
        if (!st.txArmed || !st.running) return
        if (txJob?.isActive == true) return
        val text = st.manualTxText ?: st.qso.txText ?: return
        val slotMs = st.slotMs.toLong()
        if (slotMs <= 0) return

        val now = AudioEngine.utcNowMs()
        val slotIdx = now / slotMs
        val msIntoSlot = now % slotMs
        val parity = (slotIdx % 2L).toInt()

        // 距离下一个我方发射时隙的倒计时（用于 UI）
        val waitMs = if (parity == st.txParity) {
            maxOf(0L, slotMs - msIntoSlot)
        } else {
            slotMs - msIntoSlot + slotMs
        }
        _status.update { it.copy(txCountdownMs = waitMs) }

        if (parity != st.txParity) return
        if (msIntoSlot > TX_START_WINDOW_MS) return
        if (slotIdx == lastTxSlotIndex) return

        lastTxSlotIndex = slotIdx
        manualInFlight = st.manualTxText != null
        txJob = viewModelScope.launch(Dispatchers.IO) { transmit(text, slotIdx * slotMs) }
    }

    /** 阻塞式播放一段发射波形（调用线程为 IO，不阻塞 UI 与轮询）。 */
    private fun transmit(text: String, slotStartMs: Long) {
        val st = _status.value
        try {
            val pcm = Ft8Engine.encode(text, st.selectedFreqHz.toFloat(), st.protocol, 12000)
            _status.update { it.copy(txing = true, lastTxText = text, lastTxSlotMs = slotStartMs) }
            val written = AudioEngine.play(pcm)
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
        super.onCleared()
    }

    private companion object {
        /** 允许在时隙起点后多久内开始写播放（0.5 s 前导静音可吸收该延迟）。 */
        const val TX_START_WINDOW_MS = 1200L

        fun slotMsOf(protocol: Protocol): Int = if (protocol == Protocol.FT4) 7500 else 15000
    }
}
