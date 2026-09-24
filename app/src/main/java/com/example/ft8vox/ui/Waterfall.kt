package com.example.ft8vox.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * 瀑布配色：把归一化强度 t∈[0,1] 映射为 黑→蓝→青→黄→红→白。
 *
 * 注意：实际使用时不做固定阈值，而是按“滚动峰值”做自适应拉伸
 * （见 [SessionViewModel.pollWaterfall]），因此不同设备增益下都能看清。
 */
object WaterfallColors {

    private val stops = arrayOf(
        0.00f to intArrayOf(0, 0, 16),
        0.25f to intArrayOf(0, 32, 160),
        0.45f to intArrayOf(0, 160, 200),
        0.62f to intArrayOf(60, 200, 120),
        0.78f to intArrayOf(240, 220, 60),
        0.90f to intArrayOf(240, 90, 30),
        1.00f to intArrayOf(255, 255, 255),
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
        return (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255
    }
}

/**
 * 瀑布视图：把 [frame] 画到 Canvas 上，点击可按 X 轴选频。
 *
 * [slotParity] 为当前时隙奇偶（0=偶数周期，1=奇数周期），用顶部色条区分。
 */
@Composable
fun WaterfallView(
    frame: WaterfallFrame?,
    selectedFreqHz: Int,
    slotParity: Int,
    onSelectFrequency: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(frame?.bins, frame?.rows) {
        Bitmap.createBitmap(
            (frame?.bins ?: 1).coerceAtLeast(1),
            (frame?.rows ?: 1).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    Canvas(
        modifier = modifier.pointerInput(frame?.bins, frame?.rows) {
            detectTapGestures { offset ->
                val f = frame ?: return@detectTapGestures
                if (f.bins <= 0 || size.width <= 0) return@detectTapGestures
                val frac = (offset.x / size.width).coerceIn(0f, 1f)
                onSelectFrequency((f.fMinHz + frac * f.bins * f.binHz).toInt())
            }
        }
    ) {
        val f = frame
        if (f != null && f.bins > 0 && f.rows > 0 && f.pixels.size >= f.bins * f.rows) {
            bitmap.setPixels(f.pixels, 0, f.bins, 0, 0, f.bins, f.rows)
            drawImage(
                image = image,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
            )
        }

        // 偶/奇周期色条（顶部）
        drawRect(
            color = if (slotParity == 0) Color(0xFF2962FF) else Color(0xFFFF6D00),
            size = Size(size.width, 4.dp.toPx()),
        )

        // 选中频率竖线
        if (f != null && f.bins > 0 && size.width > 0f) {
            val frac = ((selectedFreqHz - f.fMinHz) / (f.bins * f.binHz)).coerceIn(0f, 1f)
            val x = frac * size.width
            drawLine(
                color = Color(0xFFFF5252),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
            )
        }
    }
}
