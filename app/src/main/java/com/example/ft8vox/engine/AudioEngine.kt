package com.example.ft8vox.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.example.ft8vox.data.settings.SLOT_OFFSET_LIMIT_MS
import com.example.ft8vox.data.settings.clampOutputGainDb
import com.ft8.nativecore.Ft8DecodeConfig
import com.ft8.nativecore.Ft8Message
import com.ft8.nativecore.Ft8Native
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PTT 配置（U7b）。
 *
 * 无 CAT 时 App 无法控制电台 PTT，只能通过发射音频序列间接键控：
 * [pttDelayMs] 前导静音 + [leadToneMs] 前导音先让电台 VOX 动作，
 * 随后 FT8 数据落在时隙起点。[watchdogMs] 为发射写入的卡死保护（Kotlin 侧保留字段）。
 */
data class VoxConfig(
    val pttDelayMs: Int = 0,
    val leadToneMs: Int = 0,
    val watchdogMs: Int = 10_000,
)

/** 实时音频引擎的状态快照（字段与旧 native 引擎保持一致）。 */
data class AudioState(
    /** 采集流是否在运行。 */
    val running: Boolean,
    /** 当前是否处于时隙累积窗口内。 */
    val inSlot: Boolean,
    val inputRate: Int,
    val outputRate: Int,
    val fedSamples: Long,
    val slotSamples: Long,
    val slotMs: Long,
    val utcNowMs: Long,
    val droppedSamples: Long,
    val slotsDecoded: Long,
    /** 最近一次**完成解码**的时隙序号（-1=还没解码过）。 */
    val lastDecodedSlot: Long,
    /** 最近一次解码耗时（ms，0=还没解码过）。 */
    val lastDecodeMs: Long,
    /** 平滑后的输入电平（dBFS，下限约 -100）；纯显示用。 */
    val voxLevelDb: Float,
) {
    /** 当前时隙已采集比例 0..1。 */
    val slotProgress: Float
        get() = if (slotSamples > 0) (fedSamples.toFloat() / slotSamples).coerceIn(0f, 1f) else 0f

    /** 距离下一个时隙起点的毫秒数。 */
    val msToNextSlot: Long
        get() = if (slotMs > 0) slotMs - (utcNowMs % slotMs) else 0L
}

/**
 * 实时音频引擎的 Kotlin 入口（U 改造：去掉 native 音频层）。
 *
 * 采集（AudioRecord）与播放（AudioTrack）都在 Kotlin 实现；编解码统一走
 * [Ft8Native]（ft8_w），不再有 native `audio_engine.c`。
 *
 * 数据流：
 * ```
 * 麦克风(48k/float) → 重采样 12k ─┬→ 按时隙累积 → 时隙结束拷一份
 *                                 │      → 后台线程 Ft8Native.decodeSlot → pollDecoded() 拉取
 *                                 └→ STFT（2048 点 Hann，80 ms/行）→ pollWaterfall() 拉取
 * Ft8Native.encode → playTx(前导静音 + 前导音 + 波形) → AudioTrack
 * ```
 *
 * 接口契约尽量与旧 native 版保持一致，便于上层（SessionViewModel）无感替换。
 */
object AudioEngine {

    private const val TAG = "Ft8VoxAudio"

    /** 解码采样率（FT8 标准）。 */
    private const val DECODE_RATE = 12000

    /** FT8 时隙长度（ms）。 */
    private const val SLOT_MS = 15_000

    /** 单时隙解码采样数。 */
    private const val SLOT_SAMPLES = DECODE_RATE * SLOT_MS / 1000

    @Volatile
    private var engine: RealtimeEngine? = null

    /** 初始化实时引擎；重复调用会先释放旧实例。 */
    fun initialize(
        config: Ft8Config = Ft8Config(),
        decodeParams: DecodeParams = DecodeParams(),
        context: Context? = null,
    ) {
        release()
        engine = RealtimeEngine(config, decodeParams, context?.applicationContext)
    }

    /** 更新热生效的解码参数。 */
    fun setDecodeParams(params: DecodeParams) {
        engine?.setDecodeParams(params.clamped())
    }

    /** 释放引擎（停止采集与播放、关闭解码线程）。可重复调用。 */
    fun release() {
        val e = engine ?: return
        engine = null
        e.release()
    }

    /**
     * 开始采集。
     * @param preferredRate 期望采样率（实际设备采样率以返回值为准）。
     * @param deviceId 指定输入设备 id；<=0 表示系统默认。
     * @return 实际采样率；负数表示失败（-1 未初始化，-2 无法建流，-3 打开失败，-4 无权限）。
     */
    fun startCapture(preferredRate: Int = 48000, deviceId: Int = 0): Int =
        engine?.startCapture(preferredRate, deviceId) ?: -1

    /** 停止采集。 */
    fun stopCapture() {
        engine?.stopCapture()
    }

    /** 取走并清空自上次调用以来解出的报文（含指标）。 */
    fun pollDecoded(): List<DecodeResult> = engine?.pollDecoded() ?: emptyList()

    /** 瀑布频率轴；未初始化时为 null。 */
    fun waterfallInfo(): WaterfallInfo? = engine?.waterfallInfo()

    /** 取走并清空新产生的瀑布行（每行 `bins` 字节）。 */
    fun pollWaterfall(maxRows: Int = 64): ByteArray = engine?.pollWaterfall(maxRows) ?: ByteArray(0)

    /**
     * 打开播放流（不播放音频）。
     * @return 设备实际采样率；负数表示失败。
     */
    fun startPlayback(preferredRate: Int = 48000, deviceId: Int = 0): Int =
        engine?.startPlayback(preferredRate, deviceId) ?: -1

    /** 关闭播放流。 */
    fun stopPlayback() {
        engine?.stopPlayback()
    }

    /** 作废正在进行的发射写入（非阻塞，可在主线程调用）。 */
    fun abortTx() {
        engine?.abortTx()
    }

    /** 播放一段 12 kHz PCM（内部按输出采样率重采样），返回写入的 12k 采样数。 */
    fun play(pcm: FloatArray): Int = engine?.play(pcm) ?: -1

    /** 播放发射 PCM，带 PTT 前导静音与发射前导音；返回写入的 12k 采样数。 */
    fun playTx(pcm: FloatArray, pttSilenceMs: Int = 0, leadToneMs: Int = 0): Int =
        engine?.playTx(pcm, pttSilenceMs, leadToneMs) ?: -1

    /** 播放一段测试单音，返回写入的 12k 采样数。 */
    fun playTone(freqHz: Int = 1000, durationMs: Int = 2000): Int =
        engine?.playTone(freqHz, durationMs) ?: -1

    /** 下发 PTT 配置（热生效）。 */
    fun setVox(config: VoxConfig) {
        engine?.setVox(config)
    }

    /** 下发采集增益（热生效，dB）。 */
    fun setInputGain(gainDb: Int) {
        engine?.setInputGain(gainDb)
    }

    /** 下发输出音量（热生效，dB）。 */
    fun setOutputGain(gainDb: Int) {
        engine?.setOutputGain(gainDb)
    }

    /** 下发时隙偏移（热生效，ms）。 */
    fun setSlotOffsetMs(offsetMs: Int) {
        engine?.setSlotOffsetMs(offsetMs)
    }

    /** 读取当前状态快照；未初始化时为 null。 */
    fun state(): AudioState? = engine?.state()

    /** 当前 UTC 毫秒时间。 */
    fun utcNowMs(): Long = System.currentTimeMillis()

    /** 测试用：把 input 从 inRate 重采样到 outRate。 */
    internal fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray =
        Resampler.convert(input, inRate, outRate)

    // =======================================================================
    // 内部实现
    // =======================================================================

    private class RealtimeEngine(
        config: Ft8Config,
        decodeParams: DecodeParams,
        private val context: Context?,
    ) {
        @Volatile
        private var decodeParams: DecodeParams = decodeParams

        @Volatile
        private var slotOffsetMs: Int = 0

        @Volatile
        private var inputGain: Float = 1f

        @Volatile
        private var outputGain: Float = 1f

        @Volatile
        private var vox: VoxConfig = VoxConfig()

        private val decodeConfig = Ft8DecodeConfig(
            fMinHz = config.fMin,
            fMaxHz = config.fMax,
            sampleRate = DECODE_RATE,
            timeOsr = config.timeOsr,
            freqOsr = config.freqOsr,
        )

        /** Kotlin 侧实时瀑布（STFT）；频率轴跟随解码设置，仅实时采集提供。 */
        private val waterfall = WaterfallAnalyzer(DECODE_RATE, config.fMin, config.fMax)

        // ---- 采集状态 ----
        @Volatile
        private var running = false
        private var captureThread: Thread? = null
        private var captureRate = DECODE_RATE
        /** 最近一次成功打开的录音源与采样格式（诊断用）。 */
        private var captureSource = MediaRecorder.AudioSource.MIC
        private var captureEncoding = AudioFormat.ENCODING_PCM_16BIT
        /** 采集诊断日志节流。 */
        private var diagBlocks = 0L
        private var diagLastLogMs = 0L
        @Volatile
        private var inSlot = false
        @Volatile
        private var fedSamples = 0
        @Volatile
        private var droppedSamples = 0L
        @Volatile
        private var voxLevelDb = -100f
        private var slotBuf = FloatArray(SLOT_SAMPLES)
        private var slotStartMs = 0L
        private var aligned = false
        private var alignRemaining = 0

        // ---- 解码 ----
        private val decoder: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ft8-decode").apply { isDaemon = true }
        }
        private val decodedLock = Any()
        private val decodedQueue = ArrayList<DecodeResult>()
        @Volatile
        private var slotsDecoded = 0L
        @Volatile
        private var lastDecodedSlot = -1L
        @Volatile
        private var lastDecodeMs = 0L

        // ---- 播放 ----
        private var audioTrack: AudioTrack? = null
        private var outputRate = 0
        @Volatile
        private var abortRequested = false
        private val writeLock = Any()

        fun setDecodeParams(p: DecodeParams) {
            decodeParams = p
        }

        fun setVox(cfg: VoxConfig) {
            vox = cfg
        }

        fun setInputGain(gainDb: Int) {
            inputGain = dbToLinear(gainDb)
        }

        fun setOutputGain(gainDb: Int) {
            outputGain = dbToLinear(clampOutputGainDb(gainDb))
        }

        fun setSlotOffsetMs(offsetMs: Int) {
            this.slotOffsetMs = offsetMs.coerceIn(-SLOT_OFFSET_LIMIT_MS, SLOT_OFFSET_LIMIT_MS)
        }

        fun state(): AudioState = AudioState(
            running = running,
            inSlot = inSlot,
            inputRate = captureRate,
            outputRate = outputRate,
            fedSamples = fedSamples.toLong(),
            slotSamples = SLOT_SAMPLES.toLong(),
            slotMs = SLOT_MS.toLong(),
            utcNowMs = System.currentTimeMillis(),
            droppedSamples = droppedSamples,
            slotsDecoded = slotsDecoded,
            lastDecodedSlot = lastDecodedSlot,
            lastDecodeMs = lastDecodeMs,
            voxLevelDb = voxLevelDb,
        )

        // ---- 采集 ----

        /**
         * 重新武装时隙对齐：丢弃到下一个 UTC 时隙边界后再开始累积 15 s 窗口。
         *
         * 只在采集线程（建流/换源）调用；`alignRemaining` 的单位是**重采样后的 12 kHz 样本**，
         * 因此必须用 [DECODE_RATE] 换算（详见 [startCapture] 里的说明）。
         */
        private fun armAlignment() {
            val now = System.currentTimeMillis() - slotOffsetMs
            val posInSlot = ((now % SLOT_MS) + SLOT_MS) % SLOT_MS
            alignRemaining = (((SLOT_MS - posInSlot) % SLOT_MS) * DECODE_RATE / 1000).toInt()
            aligned = false
            slotBuf.fill(0f)
            fedSamples = 0
        }

        @SuppressLint("MissingPermission")
        fun startCapture(preferredRate: Int, deviceId: Int): Int {
            if (running) return captureRate
            val rate = preferredRate.takeIf { it > 0 } ?: 48000
            val opened = try {
                openRecord(rate, deviceId)
            } catch (e: SecurityException) {
                Log.w(TAG, "无录音权限", e)
                return -4
            } catch (e: Exception) {
                Log.w(TAG, "创建采集流失败", e)
                return -2
            } ?: return -3
            captureRate = opened.rec.sampleRate
            // 对齐到下一个时隙边界（单位与说明见 armAlignment）。
            // 关键：alignRemaining 是作用在**重采样后的 12 kHz 样本**（onSamples 里的 at12k）上，
            // 因此必须按 DECODE_RATE 换算，绝不能用设备采样率 captureRate——
            // 否则 48 kHz 设备会把待丢弃样本数放大 4 倍，导致解码窗口停在随机相位、
            // 且之后一直保持该错位（表现为「有电平、有解码耗时，却一条都解不出」）。
            armAlignment()
            droppedSamples = 0
            voxLevelDb = -100f
            diagBlocks = 0L
            diagLastLogMs = 0L
            waterfall.reset()
            running = true
            captureThread = Thread({ captureLoop(opened) }, "ft8-capture").apply {
                isDaemon = true
                start()
            }
            return captureRate
        }

        /** 候选录音源顺序：优先绕开系统语音处理（降噪 / AGC / 高通）。 */
        private val captureSources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )

        /** 已打开的采集流及其来源信息。 */
        private class OpenedRecord(
            val rec: AudioRecord,
            val sourceIndex: Int,
            val source: Int,
            val encoding: Int,
            val deviceId: Int,
        )

        /**
         * 从 [startIndex] 起依次打开采集流。
         *
         * FT8 是纯数据信号，**必须优先绕开系统的语音处理链路**（降噪 / AGC / 高通）：
         * vivo 等机型在 `MIC` 预设下会把弱信号当噪声抑制掉，现象就是「收不到音频」。
         * 因此按 `UNPROCESSED → VOICE_RECOGNITION → MIC` 依次尝试（与旧 native 引擎一致），
         * 每种预设再按 `FLOAT → 16-bit` 回退采样格式。
         *
         * 建流本身很快；「能建流但恒返回全 0」由采集线程里的静音检测处理（见 [readStream]）。
         */
        @SuppressLint("MissingPermission")
        private fun openRecord(rate: Int, deviceId: Int, startIndex: Int = 0): OpenedRecord? {
            for (i in startIndex until captureSources.size) {
                val source = captureSources[i]
                for (encoding in intArrayOf(
                    AudioFormat.ENCODING_PCM_FLOAT,
                    AudioFormat.ENCODING_PCM_16BIT,
                )) {
                    val rec = tryOpenRecord(rate, source, encoding, deviceId) ?: continue
                    captureSource = source
                    captureEncoding = encoding
                    Log.i(
                        TAG,
                        "采集流已打开：source=$source encoding=${encodingName(encoding)} " +
                            "rate=$rate device=$deviceId",
                    )
                    return OpenedRecord(rec, i, source, encoding, deviceId)
                }
                Log.w(TAG, "录音源不可用：source=$source（继续尝试下一个）")
            }
            return null
        }

        /** 用指定录音源 + 采样格式尝试建流并启动；失败返回 null（不抛异常）。 */
        @SuppressLint("MissingPermission")
        private fun tryOpenRecord(rate: Int, source: Int, encoding: Int, deviceId: Int): AudioRecord? {
            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding)
            val bufBytes = max(minBuf, rate / 10 * bytesPerSample) * 2
            val rec = buildRecord(rate, source, encoding, bufBytes) ?: return null
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { rec.release() }
                return null
            }
            applyInputDevice(rec, deviceId)
            return try {
                rec.startRecording()
                rec
            } catch (e: Exception) {
                Log.w(TAG, "startRecording 失败（source=$source encoding=$encoding）", e)
                runCatching { rec.release() }
                null
            }
        }

        private fun encodingName(encoding: Int): String =
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) "FLOAT" else "I16"

        @SuppressLint("MissingPermission")
        private fun buildRecord(rate: Int, source: Int, encoding: Int, bufBytes: Int): AudioRecord? = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufBytes)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.Builder 失败（source=$source encoding=$encoding）", e)
            null
        }

        private fun applyInputDevice(rec: AudioRecord, deviceId: Int) {
            if (deviceId <= 0 || context == null) return
            val dev = findDevice(deviceId, input = true) ?: return
            runCatching { rec.setPreferredDevice(dev) }
        }

        /**
         * 采集线程：读取当前流；若检测到「恒返回全 0」则自动回退到下一个录音源。
         *
         * 静音检测放在采集线程而不是建流时同步探测，避免在主线程阻塞（最坏接近 ANR）。
         */
        private fun captureLoop(first: OpenedRecord) {
            var opened: OpenedRecord? = first
            while (running && opened != null) {
                val cur = opened
                val silent = readStream(cur)
                val rate = cur.rec.sampleRate
                runCatching { cur.rec.stop() }
                runCatching { cur.rec.release() }
                opened = null
                if (!running || !silent) break
                Log.w(TAG, "录音源 ${cur.source} 恒返回全 0，回退下一个源")
                opened = openRecord(rate, cur.deviceId, cur.sourceIndex + 1)
                opened?.let {
                    captureRate = it.rec.sampleRate
                    // 换源后新流的起点与墙钟不再连续，重新对齐，避免把旧流的采样计数偏差带进来
                    armAlignment()
                }
                if (running && opened == null) {
                    Log.e(TAG, "所有录音源均返回静音，采集无法继续（请检查系统麦克风权限/隐私开关）")
                }
            }
            // 停止请求恰好在换源建流之后到达时，补一次释放，避免泄漏
            opened?.let {
                runCatching { it.rec.stop() }
                runCatching { it.rec.release() }
            }
        }

        /**
         * 读取一个采集流，直到停止 / 出错 / 开头静音检测窗口内持续全 0。
         *
         * @return true 表示检测到「恒返回全 0」（需要换源）；false 表示正常结束或致命错误。
         */
        private fun readStream(opened: OpenedRecord): Boolean {
            val rec = opened.rec
            val isFloat = opened.encoding == AudioFormat.ENCODING_PCM_FLOAT
            val chunk = max(rec.sampleRate / 10, 1024)
            val floatBuf = FloatArray(chunk)
            val shortBuf = ShortArray(chunk)
            // 前 ~1 s 做静音检测：真麦克风即使安静也有量化底噪，整段全 0 只可能是源被静音
            var probeRemaining = rec.sampleRate
            var probeNonZero = false
            Log.i(TAG, "采集线程启动：encoding=${encodingName(opened.encoding)} chunk=$chunk")
            try {
                while (running) {
                    val n = if (isFloat) {
                        rec.read(floatBuf, 0, floatBuf.size, AudioRecord.READ_BLOCKING)
                    } else {
                        rec.read(shortBuf, 0, shortBuf.size, AudioRecord.READ_BLOCKING)
                    }
                    if (n < 0) {
                        Log.w(TAG, "AudioRecord.read 返回错误码 $n")
                        // ERROR_DEAD_OBJECT：流已失效，尝试换源；其它错误直接结束
                        return n == AudioRecord.ERROR_DEAD_OBJECT
                    }
                    if (n == 0) {
                        Thread.sleep(5)
                        continue
                    }
                    if (probeRemaining > 0) {
                        if (isFloat) {
                            for (i in 0 until n) if (floatBuf[i] != 0f) { probeNonZero = true; break }
                        } else {
                            for (i in 0 until n) if (shortBuf[i].toInt() != 0) { probeNonZero = true; break }
                        }
                        probeRemaining -= n
                        if (probeRemaining <= 0 && !probeNonZero) return true
                    }
                    val samples = if (isFloat) floatBuf else shortToFloat(shortBuf, n)
                    onSamples(samples, n)
                }
            } catch (e: InterruptedException) {
                // 正常停止
            } catch (e: Exception) {
                Log.w(TAG, "采集循环异常", e)
            }
            return false
        }

        private fun shortToFloat(src: ShortArray, n: Int): FloatArray {
            val out = FloatArray(n)
            for (i in 0 until n) out[i] = src[i] / 32768f
            return out
        }

        /** 处理一段「设备采样率」的原始样本：增益 → 电平 → 重采样 → 时隙累积。 */
        private fun onSamples(input: FloatArray, n: Int) {
            // 输入增益 + 电平
            var sumSq = 0.0
            val g = inputGain
            val raw = FloatArray(n)
            for (i in 0 until n) {
                val v = input[i] * g
                raw[i] = v
                sumSq += (v * v).toDouble()
            }
            val rms = sqrt(sumSq / n.coerceAtLeast(1)).toFloat().coerceAtLeast(1e-5f)
            val db = (20f * log10(rms)).coerceIn(-100f, 0f)
            voxLevelDb = voxLevelDb * 0.8f + db * 0.2f

            // 诊断：每 5 s 打一次录音源/格式/电平，便于真机上区分「没数据」与「被降噪压制」
            diagBlocks++
            val nowDiagMs = System.currentTimeMillis()
            if (nowDiagMs - diagLastLogMs >= 5000) {
                diagLastLogMs = nowDiagMs
                Log.i(
                    TAG,
                    "采集诊断：源=$captureSource 格式=" +
                        "${if (captureEncoding == AudioFormat.ENCODING_PCM_FLOAT) "FLOAT" else "I16"} " +
                        "电平=%.1f dBFS 块=%d".format(db, diagBlocks),
                )
            }

            val at12k = if (captureRate == DECODE_RATE) raw else Resampler.convert(raw, captureRate, DECODE_RATE)

            // 瀑布：对 12 kHz 样本做 STFT（对齐丢弃的样本也喂，保证滚动连续）
            waterfall.feed(at12k)

            // 对齐：先丢弃到下一个时隙边界
            var idx = 0
            if (!aligned) {
                if (at12k.size <= alignRemaining) {
                    alignRemaining -= at12k.size
                    return
                }
                idx = alignRemaining
                alignRemaining = 0
                aligned = true
                val now = System.currentTimeMillis() - slotOffsetMs
                slotStartMs = Math.floorDiv(now, SLOT_MS.toLong()) * SLOT_MS + slotOffsetMs
                slotBuf.fill(0f)
                fedSamples = 0
            }

            while (idx < at12k.size) {
                val take = minOf(at12k.size - idx, SLOT_SAMPLES - fedSamples)
                System.arraycopy(at12k, idx, slotBuf, fedSamples, take)
                fedSamples += take
                idx += take
                if (fedSamples >= SLOT_SAMPLES) {
                    inSlot = false
                    submitDecode(slotBuf.copyOf(SLOT_SAMPLES), slotStartMs)
                    // 立即进入下一个时隙
                    slotStartMs += SLOT_MS
                    fedSamples = 0
                    slotBuf.fill(0f)
                } else {
                    inSlot = true
                }
            }
        }

        private fun submitDecode(snapshot: FloatArray, atSlotMs: Long) {
            val cfg = buildDecodeConfig()
            decoder.execute {
                val t0 = System.currentTimeMillis()
                val messages: List<Ft8Message> = try {
                    Ft8Native.decode(snapshot, cfg)
                } catch (e: Exception) {
                    Log.w(TAG, "时隙解码异常", e)
                    emptyList()
                }
                val slotIdx = atSlotMs / SLOT_MS
                synchronized(decodedLock) {
                    for (m in messages) {
                        decodedQueue.add(
                            DecodeResult(
                                text = m.text,
                                snr = Math.round(m.snr),
                                dt = m.dt - 0.5f,
                                df = Math.round(m.freq),
                                score = m.score,
                                slotUtcMs = atSlotMs,
                            ),
                        )
                    }
                }
                lastDecodeMs = System.currentTimeMillis() - t0
                lastDecodedSlot = slotIdx
                slotsDecoded += 1
            }
        }

        fun pollDecoded(): List<DecodeResult> = synchronized(decodedLock) {
            if (decodedQueue.isEmpty()) return emptyList()
            val out = ArrayList(decodedQueue)
            decodedQueue.clear()
            out
        }

        fun waterfallInfo(): WaterfallInfo = waterfall.info

        fun pollWaterfall(maxRows: Int): ByteArray = waterfall.poll(maxRows)

        private fun buildDecodeConfig(): Ft8DecodeConfig {
            val p = decodeParams
            return Ft8DecodeConfig(
                fMinHz = decodeConfig.fMinHz,
                fMaxHz = decodeConfig.fMaxHz,
                sampleRate = decodeConfig.sampleRate,
                timeOsr = decodeConfig.timeOsr,
                freqOsr = decodeConfig.freqOsr,
                maxCandidates = p.maxCandidates,
                minSyncScore = p.minScore,
                ldpcIterations = p.ldpcIterations,
                decodeDepth = p.passes,
                enableSubtract = p.passes > 1,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                returnDuplicates = false,
                osdDepth = 2,
            )
        }

        fun stopCapture() {
            if (!running) return
            running = false
            inSlot = false
            captureThread?.let { runCatching { it.join(1000) } }
            captureThread = null
            aligned = false
            fedSamples = 0
        }

        // ---- 播放 ----

        @SuppressLint("MissingPermission")
        fun startPlayback(preferredRate: Int, deviceId: Int): Int {
            synchronized(writeLock) {
                val r = preferredRate.takeIf { it > 0 } ?: 48000
                audioTrack?.let { runCatching { it.stop() }; runCatching { it.release() } }
                val track = buildTrack(r, deviceId) ?: return -2
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    track.release()
                    return -3
                }
                outputRate = track.sampleRate
                audioTrack = track
                track.play()
                abortRequested = false
                return outputRate
            }
        }

        @SuppressLint("MissingPermission")
        private fun buildTrack(rate: Int, deviceId: Int): AudioTrack? {
            val minBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
            )
            val bufBytes = max(minBuf, rate / 5 * 4) * 2
            val track = try {
                AudioTrack.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack.Builder 失败", e)
                return null
            }
            if (deviceId > 0 && context != null) {
                findDevice(deviceId, input = false)?.let { runCatching { track.setPreferredDevice(it) } }
            }
            return track
        }

        fun stopPlayback() {
            synchronized(writeLock) {
                abortRequested = true
                audioTrack?.let {
                    runCatching { it.stop() }
                    runCatching { it.flush() }
                    runCatching { it.release() }
                }
                audioTrack = null
                outputRate = 0
            }
        }

        fun abortTx() {
            abortRequested = true
        }

        fun play(pcm: FloatArray): Int = writeSamples(pcm, abortAware = false, returned = pcm.size)

        fun playTx(pcm: FloatArray, pttSilenceMs: Int, leadToneMs: Int): Int {
            abortRequested = false
            val ptt = msToSamples(pttSilenceMs)
            val lead = msToSamples(leadToneMs)
            val total = FloatArray(ptt + lead + pcm.size)
            // 前导音：约 1000 Hz 正弦（键控 VOX）
            if (lead > 0) {
                for (i in 0 until lead) {
                    total[ptt + i] = sin(2.0 * PI * 1000.0 * i / DECODE_RATE).toFloat() * 0.8f
                }
            }
            System.arraycopy(pcm, 0, total, ptt + lead, pcm.size)
            val written = writeSamples(total, abortAware = true, returned = pcm.size)
            // 播放尾部收尾（写 0 让缓冲播放完）不在阻塞路径里做，调用方只关心数据是否播出
            return written
        }

        fun playTone(freqHz: Int, durationMs: Int): Int {
            abortRequested = false
            val n = msToSamples(durationMs)
            val tone = FloatArray(n)
            for (i in 0 until n) {
                tone[i] = sin(2.0 * PI * freqHz * i / DECODE_RATE).toFloat() * 0.8f
            }
            return writeSamples(tone, abortAware = true, returned = n)
        }

        /**
         * 将 12 kHz 样本（内部重采样到输出采样率并施加输出音量）写入声卡。
         * 分块写入以便及时响应 [abortRequested]；被中止时返回 0。
         */
        private fun writeSamples(pcm: FloatArray, abortAware: Boolean, returned: Int): Int {
            if (pcm.isEmpty()) return 0
            synchronized(writeLock) {
                val track = audioTrack ?: return -1
                val resampled = if (outputRate == DECODE_RATE) pcm
                else Resampler.convert(pcm, DECODE_RATE, outputRate)
                val g = outputGain
                val chunk = max(outputRate / 20, 512)
                var off = 0
                while (off < resampled.size) {
                    if (abortAware && abortRequested) return 0
                    val len = minOf(chunk, resampled.size - off)
                    val block = FloatArray(len)
                    for (i in 0 until len) block[i] = resampled[off + i] * g
                    val w = track.write(block, 0, len, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) return -1
                    off += w
                    if (w == 0) break
                }
                return returned
            }
        }

        private fun msToSamples(ms: Int): Int =
            if (ms <= 0) 0 else ms * DECODE_RATE / 1000

        fun release() {
            stopCapture()
            stopPlayback()
            decoder.shutdownNow()
        }

        private fun findDevice(id: Int, input: Boolean): AudioDeviceInfo? {
            val ctx = context ?: return null
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            val which = if (input) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
            return runCatching { am.getDevices(which).firstOrNull { it.id == id } }.getOrNull()
        }

        private fun dbToLinear(db: Int): Float = Math.pow(10.0, db / 20.0).toFloat()
    }
}

/**
 * 重采样：整数倍抽取走 Hann 窗 sinc 低通（抗混叠），非整数倍走线性插值。
 * 采集默认 48k→12k（4 倍抽取），保证 FT8 频段不被镜像污染。
 */
private object Resampler {

    fun convert(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate <= 0 || outRate <= 0 || input.isEmpty()) return FloatArray(0)
        if (inRate == outRate) return input
        return if (inRate % outRate == 0 && inRate / outRate in 2..16) {
            decimate(input, inRate / outRate)
        } else {
            linear(input, inRate, outRate)
        }
    }

    /** ratio 倍抽取（ratio>1）：半窗宽 8*ratio 个输入样本的 Hann 窗 sinc 低通。 */
    private fun decimate(input: FloatArray, ratio: Int): FloatArray {
        val taps = 8 * ratio
        val h = FloatArray(2 * taps + 1)
        val fc = 0.5f / ratio // 归一化到输入采样率的截止频率
        var sum = 0f
        for (i in -taps..taps) {
            val x = i.toFloat()
            val sinc = if (i == 0) 2f * fc else sin(2f * PI.toFloat() * fc * x) / (PI.toFloat() * x)
            val w = 0.5f - 0.5f * kotlin.math.cos(2f * PI.toFloat() * (i + taps) / (2f * taps))
            h[i + taps] = sinc * w
            sum += h[i + taps]
        }
        if (sum != 0f) for (i in h.indices) h[i] /= sum

        val outLen = input.size / ratio
        val out = FloatArray(outLen)
        for (n in 0 until outLen) {
            val center = n * ratio
            var acc = 0f
            for (k in -taps..taps) {
                val idx = center + k
                if (idx in input.indices) acc += input[idx] * h[k + taps]
            }
            out[n] = acc
        }
        return out
    }

    /** 通用线性插值（非整数倍），仅作兜底。 */
    private fun linear(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        val outLen = (input.size.toLong() * outRate / inRate).toInt()
        if (outLen <= 0) return FloatArray(0)
        val out = FloatArray(outLen)
        val step = inRate.toDouble() / outRate
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }
}
