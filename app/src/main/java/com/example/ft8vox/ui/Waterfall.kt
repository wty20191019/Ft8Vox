package com.example.ft8vox.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** 瀑布可见行数（约 300 * 0.08 s ≈ 24 s 的滚动窗口）。 */
const val WF_ROWS = 300

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
 * 瀑布配色：把归一化强度 t∈[0,1] 映射为 深蓝→青→绿→黄→橙→红（new_ui.md §3.1）。
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

    /** 动态范围：峰值向下 35 dB（mag 为 2*dB 步进，故 70 个 mag 单位）。 */
    const val DYNAMIC_RANGE = 70

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
 * 瀑布视图：把 [frame] 画到 Canvas 上，点击可按 X 轴选频，并叠加 QSO 标记。
 *
 * - 单击：按当前可视频率窗口选频（`onSelectFrequency`）；
 * - 长按：回调 `onLongPress(freqHz)`（呼叫 / 看详情 / 忽略菜单）；
 * - 双指捏合：缩放频率轴（1x–6x），单指拖动可平移可视窗口。
 *
 * [slotParity] 为当前时隙奇偶（0=偶数周期，1=奇数周期），用顶部色条区分。
 * [rxFreqHz] 为选中的收听/应答频率（绿色竖线）；[txing] 为真时画红色边框表示正在发射。
 */
@Composable
fun WaterfallView(
    frame: WaterfallFrame?,
    selectedFreqHz: Int,
    slotParity: Int,
    onSelectFrequency: (Int) -> Unit,
    modifier: Modifier = Modifier,
    rxFreqHz: Int? = null,
    txing: Boolean = false,
    onLongPress: ((Int) -> Unit)? = null,
) {
    val bitmap = remember(frame?.bins, frame?.rows) {
        Bitmap.createBitmap(
            (frame?.bins ?: 1).coerceAtLeast(1),
            (frame?.rows ?: 1).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    // 频率轴缩放/平移：zoom=1 时铺满，centerFrac 为可视窗口中心（0–1）
    var zoom by remember { mutableStateOf(1f) }
    var centerFrac by remember { mutableStateOf(0.5f) }
    val viewSpan = 1f / zoom

    fun fracToHz(frac: Float): Int {
        val f = frame ?: return selectedFreqHz
        return (f.fMinHz + frac.coerceIn(0f, 1f) * f.bins * f.binHz).toInt()
    }

    Canvas(
        modifier = modifier
            .pointerInput(frame?.bins, frame?.rows) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val oldSpan = 1f / zoom
                    val newZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                    val newSpan = 1f / newZoom
                    // 拖动以「可视窗口比例」换算，捏合时保持中心
                    val moved = centerFrac - pan.x / size.width * oldSpan
                    zoom = newZoom
                    centerFrac = moved.coerceIn(newSpan / 2f, 1f - newSpan / 2f)
                }
            }
            .pointerInput(frame?.bins, frame?.rows) {
                detectTapGestures(
                    onTap = { offset ->
                        if (size.width <= 0f) return@detectTapGestures
                        val frac = centerFrac - viewSpan / 2f + offset.x / size.width * viewSpan
                        onSelectFrequency(fracToHz(frac))
                    },
                    onLongPress = { offset ->
                        if (size.width <= 0f) return@detectTapGestures
                        val frac = centerFrac - viewSpan / 2f + offset.x / size.width * viewSpan
                        onLongPress?.invoke(fracToHz(frac))
                    },
                )
            },
    ) {
        val f = frame
        if (f != null && f.bins > 0 && f.rows > 0 && f.pixels.size >= f.bins * f.rows) {
            bitmap.setPixels(f.pixels, 0, f.bins, 0, 0, f.bins, f.rows)
            val srcW = (f.bins * viewSpan).toInt().coerceIn(1, f.bins)
            val srcX = ((centerFrac - viewSpan / 2f) * f.bins).toInt().coerceIn(0, f.bins - srcW)
            drawImage(
                image = image,
                srcOffset = IntOffset(srcX, 0),
                srcSize = IntSize(srcW, f.rows),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
            )
        }

        // 偶/奇周期色条（顶部）
        drawRect(
            color = if (slotParity == 0) Color(0xFF2962FF) else Color(0xFFFF6D00),
            size = Size(size.width, 4.dp.toPx()),
        )

        if (f != null && f.bins > 0 && size.width > 0f) {
            val span = f.bins * f.binHz
            fun hzToX(hz: Int): Float {
                val frac = (hz - f.fMinHz) / span
                return ((frac - (centerFrac - viewSpan / 2f)) / viewSpan).coerceIn(0f, 1f) * size.width
            }

            // 选中频率竖线（我方发射频率，红）
            drawLine(
                color = Color(0xFFFF5252),
                start = Offset(hzToX(selectedFreqHz), 0f),
                end = Offset(hzToX(selectedFreqHz), size.height),
                strokeWidth = 2f,
            )

            // 对手频率竖线（绿）
            if (rxFreqHz != null) {
                drawLine(
                    color = Color(0xFF4CAF50),
                    start = Offset(hzToX(rxFreqHz), 0f),
                    end = Offset(hzToX(rxFreqHz), size.height),
                    strokeWidth = 2f,
                )
            }
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
