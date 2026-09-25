package com.example.ft8vox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ft8vox.grid.GridCell
import com.example.ft8vox.grid.MapProjection
import com.example.ft8vox.grid.Maidenhead
import com.example.ft8vox.qso.HighlightRole
import com.example.ft8vox.qso.LiveSpot
import kotlin.math.abs

private val Ocean = Color(0xFF0E1A2B)
private val World = Color(0xFF16273D)
private val GridThin = Color(0x22405570)
private val GridMajor = Color(0x66405570)
private val AxisLine = Color(0x99E0E0E0)
private val CellWorked = Color(0x9966BB6A) // 60% 透明绿
private val CellConfirmed = Color(0xCC1E88E5) // 80% 透明蓝

/** 高亮语义色（与操作页色条一致，U4 地图页再按 §4.2 重构）。 */
private val SpotTx = Color(0xFFFFEB3B)
private val SpotToMe = Color(0xFF89B4FA)
private val SpotCq = Color(0xFFFF9800)
private val SpotWorked = Color(0xFFE53935)
private val SpotDuplicate = Color(0xFF757575)
private val SpotNewGrid = Color(0xFF9C27B0)
private val SpotNewEntity = Color(0xFF8D6E63)
private val SpotNewCall = Color(0xFFF06292)
private val SpotNormal = Color(0xFF4CAF50)

/**
 * 离线 Maidenhead 网格地图：历史通联着色 + 实时台站点。
 *
 * 本组件只负责绘制，视口（缩放/平移）与命中测试由 [GridScreen] 管理。
 */
@Composable
fun GridMap(
    projection: MapProjection,
    cells: List<GridCell>,
    spots: List<LiveSpot>,
    myGrid: String?,
    nowMs: Long,
    maxAgeMs: Long,
    showLabels: Boolean,
    selectedCall: String?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember {
        TextStyle(color = Color(0xFFECEFF1), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
    val myLabelStyle = remember {
        TextStyle(color = Color(0xFF00E5FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val p = projection
        drawRect(Ocean, size = size)

        // 世界矩形（保证缩放/平移后有参照，且让海洋/陆地有区分）
        val wtl = p.toScreen(90.0, -180.0)
        val wbr = p.toScreen(-90.0, 180.0)
        val worldTop = wtl.y
        val worldBottom = wbr.y
        val worldLeft = wtl.x
        val worldRight = wbr.x
        drawRect(
            color = World,
            topLeft = Offset(worldLeft.toFloat(), worldTop.toFloat()),
            size = Size(
                (worldRight - worldLeft).toFloat().coerceAtLeast(0f),
                (worldBottom - worldTop).toFloat().coerceAtLeast(0f),
            ),
        )

        // ---- 历史着色单元（世界视图下按最小可见尺寸放大，避免只剩一个像素） ----
        val minCell = 1.6.dp.toPx()
        for (c in cells) {
            val a = p.toScreen(c.bounds.maxLat, c.bounds.minLon)
            val b = p.toScreen(c.bounds.minLat, c.bounds.maxLon)
            val w = (b.x - a.x).toFloat()
            val h = (b.y - a.y).toFloat()
            if (w <= 0f || h <= 0f) continue
            if (a.x > size.width || b.x < 0f || a.y > size.height || b.y < 0f) continue
            val cx = (a.x + b.x).toFloat() / 2f
            val cy = (a.y + b.y).toFloat() / 2f
            val dw = w.coerceAtLeast(minCell)
            val dh = h.coerceAtLeast(minCell)
            drawRect(
                color = if (c.confirmed) CellConfirmed else CellWorked,
                topLeft = Offset(cx - dw / 2f, cy - dh / 2f),
                size = Size(dw, dh),
            )
        }

        // ---- 网格线（裁剪到世界矩形内） ----
        val squareVisible = p.scale >= p.fitScale * 2.5
        val thin = 0.6.dp.toPx()
        val major = 1.4.dp.toPx()

        fun drawVertical(lon: Double, strong: Boolean) {
            val x = p.toScreen(0.0, lon).x.toFloat()
            if (x >= -1f && x <= size.width + 1f) {
                val y0 = worldTop.toFloat().coerceIn(0f, size.height)
                val y1 = worldBottom.toFloat().coerceIn(0f, size.height)
                drawLine(
                    color = if (strong) GridMajor else GridThin,
                    start = Offset(x, y0),
                    end = Offset(x, y1),
                    strokeWidth = if (strong) major else thin,
                )
            }
        }

        fun drawHorizontal(lat: Double, strong: Boolean) {
            val y = p.toScreen(lat, 0.0).y.toFloat()
            if (y >= -1f && y <= size.height + 1f) {
                val x0 = worldLeft.toFloat().coerceIn(0f, size.width)
                val x1 = worldRight.toFloat().coerceIn(0f, size.width)
                drawLine(
                    color = if (strong) GridMajor else GridThin,
                    start = Offset(x0, y),
                    end = Offset(x1, y),
                    strokeWidth = if (strong) major else thin,
                )
            }
        }

        var lon = -180.0
        while (lon <= 180.0 + 1e-6) {
            drawVertical(lon, strong = abs(lon % 20.0) < 1e-6)
            lon += 20.0
        }
        var lat = -90.0
        while (lat <= 90.0 + 1e-6) {
            drawHorizontal(lat, strong = abs(lat % 10.0) < 1e-6)
            lat += 10.0
        }
        if (squareVisible) {
            var slon = -180.0
            while (slon <= 180.0 + 1e-6) {
                if (abs(slon % 20.0) > 1e-6) drawVertical(slon, strong = false)
                slon += 2.0
            }
            var slat = -90.0
            while (slat <= 90.0 + 1e-6) {
                if (abs(slat % 10.0) > 1e-6) drawHorizontal(slat, strong = false)
                slat += 1.0
            }
        }

        // 赤道 / 本初子午线（同样裁剪到世界矩形内）
        val eqY = p.toScreen(0.0, 0.0).y.toFloat()
        if (eqY >= 0f && eqY <= size.height) {
            drawLine(
                AxisLine,
                Offset(worldLeft.toFloat().coerceIn(0f, size.width), eqY),
                Offset(worldRight.toFloat().coerceIn(0f, size.width), eqY),
                strokeWidth = 1.2.dp.toPx(),
            )
        }
        val pmX = p.toScreen(0.0, 0.0).x.toFloat()
        if (pmX >= 0f && pmX <= size.width) {
            drawLine(
                AxisLine,
                Offset(pmX, worldTop.toFloat().coerceIn(0f, size.height)),
                Offset(pmX, worldBottom.toFloat().coerceIn(0f, size.height)),
                strokeWidth = 1.2.dp.toPx(),
            )
        }

        // ---- 我方台站 ----
        if (myGrid != null) {
            Maidenhead.center(myGrid)?.let { (mlat, mlon) ->
                val s = p.toScreen(mlat, mlon)
                val r = 5.dp.toPx()
                drawCircle(Color(0xFF00E5FF), radius = r, center = Offset(s.x.toFloat(), s.y.toFloat()), style = Stroke(2.dp.toPx()))
                drawLine(Color(0xFF00E5FF), Offset(s.x.toFloat() - r, s.y.toFloat()), Offset(s.x.toFloat() + r, s.y.toFloat()), strokeWidth = 1.5.dp.toPx())
                drawLine(Color(0xFF00E5FF), Offset(s.x.toFloat(), s.y.toFloat() - r), Offset(s.x.toFloat(), s.y.toFloat() + r), strokeWidth = 1.5.dp.toPx())
                if (showLabels) {
                    drawText(
                        textMeasurer = measurer,
                        text = myGrid.uppercase(),
                        topLeft = Offset(s.x.toFloat() + r + 2.dp.toPx(), s.y.toFloat() - 6.dp.toPx()),
                        style = myLabelStyle,
                    )
                }
            }
        }

        // ---- 实时台站点 ----
        val baseR = 3.dp.toPx()
        val spanR = 6.dp.toPx()
        for (spot in spots.toList().asReversed()) { // 旧的先画，新的压在上面
            val s = p.toScreen(spot.lat, spot.lon)
            val x = s.x.toFloat()
            val y = s.y.toFloat()
            if (x < -20f || x > size.width + 20f || y < -20f || y > size.height + 20f) continue
            val snrFactor = ((spot.snr + 24).coerceIn(0, 34) / 34f)
            val r = baseR + spanR * snrFactor
            val age = if (nowMs > 0L && spot.utcMs > 0L) (nowMs - spot.utcMs).coerceAtLeast(0L) else 0L
            val alpha = if (maxAgeMs > 0L) (1f - 0.65f * (age.toFloat() / maxAgeMs)).coerceIn(0.3f, 1f) else 1f
            val color = spotColor(spot.style.role).copy(alpha = alpha)
            drawCircle(color = color, radius = r, center = Offset(x, y))
            if (spot.call == selectedCall) {
                drawCircle(
                    color = Color.White,
                    radius = r + 2.dp.toPx(),
                    center = Offset(x, y),
                    style = Stroke(2.dp.toPx()),
                )
            }
            if (showLabels && p.scale >= p.fitScale * 2.5) {
                drawText(
                    textMeasurer = measurer,
                    text = spot.call,
                    topLeft = Offset(x + r + 2.dp.toPx(), y - 6.dp.toPx()),
                    style = labelStyle,
                )
            }
        }
    }
}

private fun spotColor(role: HighlightRole): Color = when (role) {
    HighlightRole.TX -> SpotTx
    HighlightRole.TO_ME -> SpotToMe
    HighlightRole.CQ -> SpotCq
    HighlightRole.WORKED -> SpotWorked
    HighlightRole.DUPLICATE -> SpotDuplicate
    HighlightRole.NEW_GRID -> SpotNewGrid
    HighlightRole.NEW_ENTITY -> SpotNewEntity
    HighlightRole.NEW_CALL -> SpotNewCall
    HighlightRole.NORMAL -> SpotNormal
}
