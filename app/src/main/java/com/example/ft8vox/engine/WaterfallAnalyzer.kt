package com.example.ft8vox.engine

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kotlin 侧实时瀑布分析器（STFT）。
 *
 * 设计（与用户确认的瀑布重建方案一致）：
 * - 纯 Kotlin radix-2 FFT，**不改动 vendored ft8_w**；
 * - 固定 [fftSize]=2048、Hann 窗，频率分辨率 `binHz = sampleRate / fftSize`（12 kHz ≈ 5.86 Hz）；
 * - 每 [rowMs]=80 ms（[hop] 个样本）产出一行，与旧 `WF_ROW_MS=80` 对齐；
 * - 只输出 `[fMinHz, fMaxHz]` 范围内的 bin，每行 [bins] 字节；
 * - 每 bin 写 `u = round((dBFS + 120) * 2)`（0.5 dB/单位，[-120, 0] dBFS，与 ft8_w 瀑布幅度编码同源），
 *   颜色阈值由 UI 侧按固定窗口换算（见 `waterfallIdx`）。
 *
 * 线程模型：`feed()` 只在采集线程调用；`poll()` 由轮询线程调用；行队列加锁。
 * 若消费端长时间不取，行队列最多保留 [MAX_ROWS] 行（丢最旧）。
 *
 * 注意：这是引擎内部工具，不构成稳定 API。
 */
class WaterfallAnalyzer(
    private val sampleRate: Int = 12000,
    fMinHz: Float,
    fMaxHz: Float,
    private val fftSize: Int = 2048,
    private val rowMs: Int = 80,
) {

    init {
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0) { "fftSize 必须是 2 的幂" }
    }

    /** 每个 bin 的频率宽度（Hz）。 */
    val binHz: Float = sampleRate.toFloat() / fftSize

    /** 可见频率轴第一个 bin 的绝对 FFT 下标。 */
    private val firstBin: Int = ceil(fMinHz / binHz).toInt().coerceIn(0, fftSize / 2 - 1)

    /** 可见频率轴最后一个 bin 的绝对 FFT 下标（含）。 */
    private val lastBin: Int = (fMaxHz / binHz).toInt().coerceIn(firstBin, fftSize / 2 - 1)

    /** 每行字节数（可见 bin 数）。 */
    val bins: Int = (lastBin - firstBin + 1).coerceAtLeast(1)

    /** 可见频率轴左端（Hz）＝ 第一个 bin 的中心频率。 */
    val fMinHz: Float = firstBin * binHz

    /** 频率轴信息（供 UI 画刻度 / 定位解码叠加）。 */
    val info: WaterfallInfo = WaterfallInfo(bins = bins, binHz = binHz, fMinHz = fMinHz)

    /** 每行对应的样本数（80 ms @ 12 kHz = 960）。 */
    private val hop: Int = (sampleRate.toLong() * rowMs / 1000).coerceAtLeast(1).toInt()

    // ---- 窗与 FFT 预计算 ----
    private val window = FloatArray(fftSize) { 0.5f - 0.5f * cos(2f * PI.toFloat() * it / fftSize) }
    private val sumWindow = window.sum()
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val frame = FloatArray(fftSize)
    private val rev = IntArray(fftSize)
    private val cosTab = FloatArray(fftSize / 2)
    private val sinTab = FloatArray(fftSize / 2)

    init {
        val levels = Integer.numberOfTrailingZeros(fftSize)
        for (i in 0 until fftSize) {
            var x = i
            var r = 0
            for (b in 0 until levels) {
                r = (r shl 1) or (x and 1)
                x = x ushr 1
            }
            rev[i] = r
        }
        for (k in 0 until fftSize / 2) {
            val ang = 2.0 * PI * k / fftSize
            cosTab[k] = cos(ang).toFloat()
            sinTab[k] = sin(ang).toFloat()
        }
    }

    // ---- 滚动缓冲（环形，最旧样本在 widx） ----
    private val ring = FloatArray(fftSize)
    private var widx = 0
    private var filled = 0
    private var sinceHop = 0

    // ---- 行队列 ----
    private val lock = Any()
    private val rows = ArrayDeque<ByteArray>()

    /**
     * 追加一段 12 kHz 单声道 PCM（仅前 [n] 个样本生效）。
     * 内部按 [hop] 切窗，凑够一窗就产出一行。
     */
    fun feed(samples: FloatArray, n: Int = samples.size) {
        val count = n.coerceAtMost(samples.size).coerceAtLeast(0)
        for (i in 0 until count) {
            ring[widx] = samples[i]
            widx++
            if (widx == fftSize) widx = 0
            if (filled < fftSize) filled++
            sinceHop++
            if (sinceHop >= hop && filled == fftSize) {
                emitRow()
                sinceHop -= hop
            }
        }
    }

    /** 取走并清空全部待取行（最旧→最新）。[maxRows] 只保留最新若干行。 */
    fun poll(maxRows: Int): ByteArray {
        synchronized(lock) {
            if (rows.isEmpty()) return ByteArray(0)
            val take = maxRows.coerceAtLeast(0).coerceAtMost(rows.size)
            if (take == 0) {
                rows.clear()
                return ByteArray(0)
            }
            val start = rows.size - take
            val out = ByteArray(take * bins)
            var pos = 0
            var i = 0
            for (row in rows) {
                if (i >= start) {
                    System.arraycopy(row, 0, out, pos, bins)
                    pos += bins
                }
                i++
            }
            rows.clear()
            return out
        }
    }

    /** 清空滚动缓冲与待取行（重新开始接收时调用）。 */
    fun reset() {
        widx = 0
        filled = 0
        sinceHop = 0
        synchronized(lock) { rows.clear() }
    }

    // ---- 内部：产出一行 ----

    private fun emitRow() {
        // 展开成时间序：widx 是最旧样本
        for (i in 0 until fftSize) {
            var idx = widx + i
            if (idx >= fftSize) idx -= fftSize
            frame[i] = ring[idx] * window[i]
        }
        System.arraycopy(frame, 0, re, 0, fftSize)
        java.util.Arrays.fill(im, 0f)
        fft()

        val row = ByteArray(bins)
        for (b in 0 until bins) {
            val k = firstBin + b
            val p = re[k] * re[k] + im[k] * im[k]
            // 归一化到「正弦满幅 = 0 dBFS」：单边幅度 = 2|X|/sum(w)
            val amp = 2f * sqrt(p) / sumWindow
            val db = 20f * log10(amp.coerceAtLeast(1e-7f))
            val u = ((db + 120f) * 2f).roundToInt().coerceIn(0, 255)
            row[b] = u.toByte()
        }
        synchronized(lock) {
            rows.addLast(row)
            while (rows.size > MAX_ROWS) rows.removeFirst()
        }
    }

    /** 迭代式 radix-2 DIT FFT（原地，[re]/[im]）。 */
    private fun fft() {
        for (i in 0 until fftSize) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= fftSize) {
            val half = len / 2
            val step = fftSize / len
            var base = 0
            while (base < fftSize) {
                var k = 0
                var j = base
                val end = base + half
                while (j < end) {
                    val c = cosTab[k]
                    val s = sinTab[k]
                    val jr = re[j + half]
                    val ji = im[j + half]
                    // t = (c - i s) * (jr + i ji)
                    val tre = jr * c + ji * s
                    val tim = ji * c - jr * s
                    re[j + half] = re[j] - tre
                    im[j + half] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                    j++
                }
                base += len
            }
            len = len shl 1
        }
    }

    companion object {
        /** 待取行上限（与 UI 的 WF_ROWS 一致，约 48 s）。 */
        const val MAX_ROWS = 600
    }
}
