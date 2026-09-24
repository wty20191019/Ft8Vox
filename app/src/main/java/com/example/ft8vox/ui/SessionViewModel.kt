package com.example.ft8vox.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ft8vox.engine.AudioEngine
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.engine.Ft8Config
import com.example.ft8vox.engine.Ft8Engine
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.engine.WaterfallInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 接收会话的非瀑布状态（供状态栏/控制区使用）。 */
data class ReceiverStatus(
    val running: Boolean = false,
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
)

/**
 * 会话状态管理：把实时引擎的轮询结果整理为不可变 UI 状态。
 *
 * - [messages]：解码列表（新→旧）
 * - [waterfall]：瀑布帧（滚动缓冲）
 * - [status]：状态栏与控制区
 *
 * 采用三个独立 StateFlow，避免瀑布/倒计时高频刷新带动解码列表重组。
 */
class SessionViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<DecodeResult>>(emptyList())
    val messages: StateFlow<List<DecodeResult>> = _messages.asStateFlow()

    private val _waterfall = MutableStateFlow<WaterfallFrame?>(null)
    val waterfall: StateFlow<WaterfallFrame?> = _waterfall.asStateFlow()

    private val _status = MutableStateFlow(ReceiverStatus())
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    private var pollJob: Job? = null
    private var wfInfo: WaterfallInfo? = null
    private var wfPixels = IntArray(0)
    private var wfPeak = 0

    fun selectProtocol(protocol: Protocol) {
        if (_status.value.running) return
        _status.update { it.copy(protocol = protocol, slotMs = if (protocol == Protocol.FT4) 7500 else 15000) }
    }

    fun selectFrequency(hz: Int) {
        _status.update { it.copy(selectedFreqHz = hz.coerceAtLeast(0)) }
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    fun start() {
        if (_status.value.running) return
        val protocol = _status.value.protocol

        try {
            AudioEngine.initialize(Ft8Config(protocol = protocol))
        } catch (e: Exception) {
            _status.update { it.copy(status = "初始化失败: ${e.message}") }
            return
        }

        wfInfo = AudioEngine.waterfallInfo()
        wfPixels = IntArray(0)
        wfPeak = 0
        _waterfall.value = null

        val rate = AudioEngine.startCapture(48000)
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
        pollJob?.cancel()
        pollJob = null
        AudioEngine.stopCapture()
        AudioEngine.release()
        wfInfo = null
        _status.update { it.copy(running = false, status = "已停止", msToNextSlot = 0, slotProgress = 0f) }
    }

    /** 发射测试：编码一段 CQ 并按输出采样率播放。 */
    fun transmitTest() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _status.value
            try {
                if (!current.running) {
                    AudioEngine.initialize(Ft8Config(protocol = current.protocol))
                }
                val pcm = Ft8Engine.encode(
                    "CQ F4FSY JN25",
                    current.selectedFreqHz.toFloat(),
                    current.protocol,
                    12000,
                )
                val rate = AudioEngine.startPlayback(48000)
                if (rate <= 0) {
                    _status.update { it.copy(status = "播放启动失败（错误码 $rate）") }
                    return@launch
                }
                val written = AudioEngine.play(pcm)
                _status.update { it.copy(outputRate = rate, status = "已发射测试 CQ（$written 帧 @ $rate Hz）") }
            } catch (e: Exception) {
                _status.update { it.copy(status = "发射失败: ${e.message}") }
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    // 1) 解码结果（新→旧）
                    val decoded = AudioEngine.pollDecoded()
                    if (decoded.isNotEmpty()) {
                        _messages.update { current -> (decoded + current).take(200) }
                    }

                    // 2) 瀑布行流
                    pollWaterfall()

                    // 3) 状态
                    AudioEngine.state()?.let { s ->
                        _status.update {
                            it.copy(
                                inputRate = s.inputRate,
                                slotMs = s.slotMs.toInt(),
                                msToNextSlot = s.msToNextSlot,
                                slotProgress = s.slotProgress,
                                slotParity = if (s.slotMs > 0) ((s.utcNowMs / s.slotMs) % 2L).toInt() else 0,
                                slotsDecoded = s.slotsDecoded,
                                droppedSamples = s.droppedSamples,
                            )
                        }
                    }
                } catch (e: Exception) {
                    // 轮询失败不应让进程崩溃
                    _status.update { it.copy(status = "轮询异常: ${e.message}") }
                }

                delay(80)
            }
        }
    }

    /** 取走新的 waterfall 行，滚动写入像素缓冲（旧行上移，新行在底部）。 */
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
        AudioEngine.stopCapture()
        AudioEngine.release()
        super.onCleared()
    }
}
