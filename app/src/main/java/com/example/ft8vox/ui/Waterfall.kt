package com.example.ft8vox.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlinx.coroutines.withTimeoutOrNull

/** 瀑布可见行数（约 300 * 0.08 s ≈ 24 s 的滚动窗口）。 */
const val WF_ROWS = 300

/**
 * 瀑布每一行对应的时间（毫秒）＝ 80 ms（native 侧 `K_WF_RING_ROWS=600 ≈ 48 s` 同源）。
 *
 * 频谱页据此把解码呼号**锚定到它在瀑布上的时间位置**，随瀑布向上滚动，而不是固定贴在某个高度。
 */
const val WF_ROW_MS = 80

/**
 * 一帧瀑布快照。
 *
 * [pixels] 为 ARGB_8888 行主序，长度 = bins * rows，最早的行在前、最新的行在后。
 */
class WaterfallFrame(
    val bins: Int,
    val rows: Int,
    val binHz: Float,
    val fMinHz: Float,
    val pixels: IntArray,
) {
    val maxHz: Float get() = fMinHz + bins * binHz
}

/**
 * 瀑布配色：把归一化强度 t∈[0,1] 映射为 深蓝→青→绿→黄→橙→红（docs/UI.md §2.3）。
 *
 * 注意：实际使用时不做固定阈值，而是按“滚动峰值”做自适应拉伸
 * （见 [SessionViewModel.pollWaterfall]），因此不同设备增益下都能看清。
 */
object WaterfallColors {

    private val stops = arrayOf(
        0.00f to intArrayOf(10, 10, 35), // 深蓝
        0.25f to intArrayOf(24, 52, 140), // 蓝
        0.50f to intArrayOf(0, 170, 200), // 青
        0.63f to intArrayOf(70, 200, 110), // 绿
        0.75f to intArrayOf(225, 215, 60), // 黄
        0.88f to intArrayOf(240, 120, 30), // 橙
        1.00f to intArrayOf(235, 60, 25), // 红
    )

    /** 预计算的 256 级渐变查找表：rampLut[i] 对应 t = i/255。 */
    val rampLut: IntArray = IntArray(256) { ramp(it / 255f) }

    /**
     * 动态范围上限：峰值向下 60 dB（mag 为 0.5 dB 步进，故 120 个 mag 单位）。
     *
     * 弱台常比本地强台低 40~60 dB，范围太窄就会被压成黑色而"看不见"。
     */
    const val MAX_SPAN = 120

    /** 动态范围下限：至少覆盖 25 dB，避免噪声纹理占满整条色带。 */
    const val MIN_SPAN = 50

    private fun ramp(t: Float): Int {
        for (i in 0 until stops.size - 1) {
            val (p0, c0) = stops[i]
            val (p1, c1) = stops[i + 1]
            if (t <= p1) {
                val k = if (p1 > p0) (t - p0) / (p1 - p0) else 0f
                val r = (c0[0] + (c1[0] - c0[0]) * k).toInt()
                val g = (c0[1] + (c1[1] - c0[1]) * k).toInt()
                val b = (c0[2] + (c1[2] - c0[2]) * k).toInt()
                return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return (0xFF shl 24) or (235 shl 16) or (60 shl 8) or 25
    }
}

/**
 * 瀑布视图：把 [frame] 画到 Canvas 上，并叠加发射频带（红线 + 半透明红条）与 QSO 标记。
 *
 * **发射频率就是唯一可调频率**：在瀑布上**单击或水平拖动**即把发射频率移到该处
 * （回调 [onMoveTxFreq]），**长按**回调 `onLongPress(freqHz)` 打开最近一条解码的详情。
 * 不再画「接收频率」绿线。频率轴固定铺满 `fMin–fMax`，**不做缩放与平移**（避免误触捏合改变频率刻度）。
 *
 * 发射频率在瀑布上画成两件东西（见 [txBandPx]）：
 * - **红色竖线**＝报文**下边频**（就是报文音频频率本身，拖动它改频率的那条线）；
 * - **半透明红条**＝整条报文实际占用的带宽 `[f, f + occupiedHz]`（FT8 50 Hz / FT4 83 Hz），
 *   用来一眼看出这条报文会占掉频谱的哪一段。
 *
 * [slotParity] 为当前时隙奇偶（0=偶数周期，1=奇数周期），用顶部色条区分。
 * [txing] 为真时画红色边框表示正在发射。
 *
 * **基线常驻**（docs/UI-MOBILE.md §23）：频率网格 + 刻度 + 发射红线**不依赖瀑布帧**。
 * 刚启动 / 还没收到第一帧时（[frame] == null）用 [fallbackFMinHz]–[fallbackMaxHz] 这套频率轴
 * 照画，否则启动瞬间整片区域是黑的，连红线都看不到。
 */
@Composable
fun WaterfallView(
    frame: WaterfallFrame?,
    selectedFreqHz: Int,
    slotParity: Int,
    onMoveTxFreq: (Int) -> Unit,
    modifier: Modifier = Modifier,
    txing: Boolean = false,
    occupiedHz: Int = 50,
    onLongPress: ((Int) -> Unit)? = null,
    /** 还没有瀑布帧时的频率轴左端（Hz），一般取设置里的解码下边界。 */
    fallbackFMinHz: Float = 0f,
    /** 还没有瀑布帧时的频率轴右端（Hz），一般取设置里的解码上边界。 */
    fallbackMaxHz: Float = 3000f,
) {
    val bitmap = remember(frame?.bins, frame?.rows) {
        Bitmap.createBitmap(
            (frame?.bins ?: 1).coerceAtLeast(1),
            (frame?.rows ?: 1).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    // 频率轴固定铺满 fMin–fMax，不做缩放/平移；没有帧时用兜底轴，保证红线与刻度照画。
    val fMinHz = frame?.fMinHz ?: fallbackFMinHz
    val spanHz = frame?.let { it.bins * it.binHz }
        ?: (fallbackMaxHz - fallbackFMinHz).coerceAtLeast(1f)

    // 频率刻度（网格线 + 小字）：步长按总跨度挑，线数落在 4~8 条
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
    )
    val gridStep = remember(spanHz) { gridStepHz(spanHz) }
    val axisMarks = remember(spanHz, fMinHz, gridStep) {
        buildList {
            var hz = ceil(fMinHz / gridStep) * gridStep
            while (hz <= fMinHz + spanHz) {
                add(((hz - fMinHz) / spanHz) to textMeasurer.measure(hz.toInt().toString(), style = axisStyle))
                hz += gridStep
            }
        }
    }

    fun fracToHz(frac: Float): Int =
        (fMinHz + frac.coerceIn(0f, 1f) * spanHz).toInt()

    Canvas(
        modifier = modifier
            .pointerInput(frame?.bins, frame?.rows) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (size.width <= 0f) return@awaitEachGesture
                    // 按下即把红线移到这里；随后水平拖动持续跟随（单击与拖动是同一套手势）
                    onMoveTxFreq(fracToHz(down.position.x / size.width))
                    down.consume()
                    var longFired = false
                    var moved = false
                    while (true) {
                        // 静置达到长按时限 → 打开详情（红线已在按下时移好）
                        val event = if (longFired) {
                            awaitPointerEvent()
                        } else {
                            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                awaitPointerEvent()
                            }
                        }
                        if (event == null) {
                            if (!moved) {
                                longFired = true
                                onLongPress?.invoke(fracToHz(down.position.x / size.width))
                            }
                            continue
                        }
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                        }
                        if (ch.positionChanged()) {
                            onMoveTxFreq(fracToHz(ch.position.x / size.width))
                            ch.consume()
                        }
                        if (!ch.pressed) {
                            ch.consume()
                            break
                        }
                    }
                }
            },
    ) {
        val f = frame
        if (f != null && f.bins > 0 && f.rows > 0 && f.pixels.size >= f.bins * f.rows) {
            bitmap.setPixels(f.pixels, 0, f.bins, 0, 0, f.bins, f.rows)
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(f.bins, f.rows),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
                // 最近邻：每个 FFT bin / 行本来就是离散色块，放大后不用双线性（省掉每像素 4 次采样，
                // 在软件 / 翻译层 GL 上尤其明显），视觉上反而更锐利。
                filterQuality = FilterQuality.None,
            )
        }

        // 频率网格 + 刻度（**不依赖瀑布帧**：启动瞬间也有东西可看）
        if (size.width > 0f && spanHz > 0f) {
            val labelTop = 5.dp.toPx()
            axisMarks.forEach { (frac, layout) ->
                val x = frac * size.width
                drawLine(
                    color = Color(0x1FFFFFFF),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                drawRect(
                    color = Color(0x80000000),
                    topLeft = Offset(x + 1f, labelTop - 1f),
                    size = Size(layout.size.width + 4f, layout.size.height + 2f),
                )
                drawText(layout, color = Color(0xCCFFFFFF), topLeft = Offset(x + 3f, labelTop))
            }
        }

        // 偶/奇周期色条（顶部）
        drawRect(
            color = if (slotParity == 0) Color(0xFF2962FF) else Color(0xFFFF6D00),
            size = Size(size.width, 4.dp.toPx()),
        )

        if (spanHz > 0f && size.width > 0f) {
            // 发射频带：线＝下边频（可拖动调整），条＝整条报文占用的带宽（FT8 50 Hz / FT4 83 Hz）
            val (xStart, xEnd) = txBandPx(selectedFreqHz, occupiedHz, fMinHz, spanHz, size.width)
            if (xEnd > xStart) {
                drawRect(
                    color = Color(0x40FF5252),
                    topLeft = Offset(xStart, 0f),
                    size = Size(xEnd - xStart, size.height),
                )
            }
            drawLine(
                color = Color(0xFFFF5252),
                start = Offset(xStart, 0f),
                end = Offset(xStart, size.height),
                strokeWidth = 2f,
            )
        }

        // 发射中：整圈红框提示
        if (txing) {
            val w = 3.dp.toPx()
            drawRect(
                color = Color(0xFFFF1744),
                topLeft = Offset(w / 2f, w / 2f),
                size = Size(size.width - w, size.height - w),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = w),
            )
        }
    }
}

/**
 * 频率网格步长：按总跨度挑一个让网格线数量落在 4~8 条之间的步长（Hz）。
 *
 * 音频频率轴一般只覆盖 200–3000 Hz，所以 100/200/250/500/1000/2000 Hz 六档就够。
 */
internal fun gridStepHz(spanHz: Float): Float {
    for (step in floatArrayOf(100f, 200f, 250f, 500f, 1000f, 2000f)) {
        if (spanHz / step <= 8f) return step
    }
    return 5000f
}

/**
 * 发射频带 `[selectedFreqHz, selectedFreqHz + occupiedHz]` 在瀑布横轴上的**像素区间**
 * （左＝下边频、右＝上边频），供 [WaterfallView] 画半透明红条与红线。
 *
 * 频率轴固定铺满 `fMinHz … fMinHz + spanHz`：越界自动夹到 `[0, width]`（红线贴在边缘上），
 * 因此下边频已到最右侧时返回的区间宽度为 0（此时不画条，只画线）。
 *
 * @param fMinHz 频率轴左端（Hz）
 * @param spanHz 频率轴总跨度（Hz）＝`bins × binHz`
 * @param width 瀑布像素宽度
 */
internal fun txBandPx(
    selectedFreqHz: Int,
    occupiedHz: Int,
    fMinHz: Float,
    spanHz: Float,
    width: Float,
): Pair<Float, Float> {
    fun x(hz: Float): Float {
        if (spanHz <= 0f) return 0f
        return ((hz - fMinHz) / spanHz).coerceIn(0f, 1f) * width
    }
    return x(selectedFreqHz.toFloat()) to x((selectedFreqHz + occupiedHz.coerceAtLeast(0)).toFloat())
}
