package com.example.ft8vox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ft8vox.grid.MapProjection
import com.example.ft8vox.grid.Maidenhead
import com.example.ft8vox.qso.CallMarker
import com.example.ft8vox.qso.CqFlag
import com.example.ft8vox.qso.GridMarker
import com.example.ft8vox.qso.MapTier
import com.example.ft8vox.qso.SignalLink
import kotlin.math.ceil
import kotlin.math.floor

// ---- new_ui.md §4 配色 ----
private val Ocean = Color(0xFF0B0B12)
private val World = Color(0xFF171C2B)
private val TierDecoded = Color(0xFF89B4FA) // 蓝：本会话解码
private val TierWorked = Color(0xFFF9E2AF) // 黄：日志已通联
private val TierConfirmed = Color(0xFFE64553) // 红：日志已确认
private val CqRed = Color(0xFFE64553)
private val LinkColor = Color(0x9989B4FA)
private val MyColor = Color(0xFF00E5FF)

/** 底图暗化：卫星影像偏亮，乘 0.7 让它退到 UI 之后（不透明度不变）。 */
private val BaseMapDarken: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToScale(0.7f, 0.7f, 0.7f, 1f) },
)

private fun tierColor(tier: MapTier): Color = when (tier) {
    MapTier.DECODED -> TierDecoded
    MapTier.WORKED -> TierWorked
    MapTier.CONFIRMED -> TierConfirmed
}

/**
 * 离线深色地图（new_ui.md §4.1）：底层为 **Web Mercator 卫星底图**（`assets/map/world_z5.jpg`，
 * 按可见区域流式解码，乘 0.7 暗化），其上叠加网格标记、呼号、CQ 旗帜与信号连线，**无网格线图层**。
 *
 * 底图资产缺失/解码失败时退回原来的纯色世界矩形，保证地图页始终可用。
 * 本组件只负责绘制，视口（缩放/平移）与命中测试由 [GridScreen] 管理。
 * [linkPhase] 为 0–1 的循环相位，用于让连线内容沿通信方向运动。
 */
@Composable
fun GridMap(
    projection: MapProjection,
    gridMarkers: List<GridMarker>,
    callMarkers: List<CallMarker>,
    cqFlags: List<CqFlag>,
    links: List<SignalLink>,
    myGrid: String?,
    showCqCall: Boolean,
    showCqSnr: Boolean,
    showLinkText: Boolean,
    selectedCall: String?,
    linkPhase: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 底图解碼器是应用级单例（见 WorldBaseMapState）：只打开一次、永不 recycle，
    // 切页不再重建解码器 / 重解码整屏，也不会与后台解码竞态崩溃。
    LaunchedEffect(Unit) { WorldBaseMapState.prepare(context) }
    val baseMapReady = WorldBaseMapState.ready

    val measurer = rememberTextMeasurer()
    val callStyle = remember {
        TextStyle(color = Color(0xFFECEFF1), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
    val cqStyle = remember {
        TextStyle(
            color = Color(0xFFFFD9DB),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
    val linkStyle = remember {
        TextStyle(
            color = Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
    val myLabelStyle = remember {
        TextStyle(color = MyColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }.toDouble()
        val hPx = with(density) { maxHeight.toPx() }.toDouble()

        // 预取当前视口需要的底图块（缺失的异步解码，画布只画已就绪的）
        val needed = remember(projection, wPx, hPx, baseMapReady) {
            if (baseMapReady == true) WorldBaseMap.visibleBlocks(projection, wPx, hPx) else emptyList()
        }
        LaunchedEffect(needed) { WorldBaseMapState.ensure(needed) }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val p = projection
            drawRect(Ocean, size = size)

            if (baseMapReady != true) {
                // 无底图：退回纯色世界矩形（仅作空间参照）
                val wtl = p.toScreen(MapProjection.MAX_LAT, -180.0)
                val wbr = p.toScreen(-MapProjection.MAX_LAT, 180.0)
                drawRect(
                    color = World,
                    topLeft = Offset(wtl.x.toFloat(), wtl.y.toFloat()),
                    size = Size(
                        (wbr.x - wtl.x).toFloat().coerceAtLeast(0f),
                        (wbr.y - wtl.y).toFloat().coerceAtLeast(0f),
                    ),
                )
            } else {
                for (block in WorldBaseMap.visibleBlocks(p, size.width.toDouble(), size.height.toDouble())) {
                    val img = WorldBaseMapState.bitmap(block) ?: continue
                    val dim = WorldBaseMap.SIZE shr block.level
                    val x0 = block.bx * WorldBaseMap.BLOCK
                    val y0 = block.by * WorldBaseMap.BLOCK
                    val bw = minOf(WorldBaseMap.BLOCK, dim - x0)
                    val bh = minOf(WorldBaseMap.BLOCK, dim - y0)
                    val tl = p.toScreenUV(x0.toDouble() / dim, y0.toDouble() / dim)
                    val br = p.toScreenUV((x0 + bw).toDouble() / dim, (y0 + bh).toDouble() / dim)
                    // 向外取整，让相邻块轻微重叠，避免缩放时出现 1px 缝
                    val dx = floor(tl.x).toInt()
                    val dy = floor(tl.y).toInt()
                    val dw = ceil(br.x).toInt() - dx
                    val dh = ceil(br.y).toInt() - dy
                    if (dw <= 0 || dh <= 0) continue
                    drawImage(
                        image = img,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(dx, dy),
                        dstSize = IntSize(dw, dh),
                        filterQuality = FilterQuality.Low,
                        colorFilter = BaseMapDarken,
                    )
                }
            }

            // ---- 网格标记（蓝/黄/红，世界视图下保证最小可见尺寸） ----
            val minCell = 2.4.dp.toPx()
            for (m in gridMarkers) {
                val a = p.toScreen(m.bounds.maxLat, m.bounds.minLon)
                val b = p.toScreen(m.bounds.minLat, m.bounds.maxLon)
                val w = (b.x - a.x).toFloat()
                val h = (b.y - a.y).toFloat()
                if (w <= 0f || h <= 0f) continue
                if (a.x > size.width || b.x < 0f || a.y > size.height || b.y < 0f) continue
                val cx = (a.x + b.x).toFloat() / 2f
                val cy = (a.y + b.y).toFloat() / 2f
                val dw = w.coerceAtLeast(minCell)
                val dh = h.coerceAtLeast(minCell)
                val color = tierColor(m.tier)
                drawRect(
                    color = color.copy(alpha = 0.30f),
                    topLeft = Offset(cx - dw / 2f, cy - dh / 2f),
                    size = Size(dw, dh),
                )
                drawRect(
                    color = color.copy(alpha = 0.65f),
                    topLeft = Offset(cx - dw / 2f, cy - dh / 2f),
                    size = Size(dw, dh),
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }

            // ---- 信号连线（只画最近一个时隙） ----
            val phase = linkPhase.coerceIn(0f, 1f)
            for (l in links) {
                val fa = p.toScreen(l.fromLat, l.fromLon)
                val tb = p.toScreen(l.toLat, l.toLon)
                val fx = fa.x.toFloat()
                val fy = fa.y.toFloat()
                val tx = tb.x.toFloat()
                val ty = tb.y.toFloat()
                if ((fx < -40f && tx < -40f) || (fx > size.width + 40f && tx > size.width + 40f)) continue
                if ((fy < -40f && ty < -40f) || (fy > size.height + 40f && ty > size.height + 40f)) continue

                drawLine(
                    color = LinkColor,
                    start = Offset(fx, fy),
                    end = Offset(tx, ty),
                    strokeWidth = 1.4.dp.toPx(),
                )
                val mx = fx + (tx - fx) * phase
                val my = fy + (ty - fy) * phase
                val label = l.label
                if (showLinkText && label != null) {
                    val laid = measurer.measure(AnnotatedString(label), linkStyle)
                    drawText(
                        textLayoutResult = laid,
                        topLeft = Offset(mx - laid.size.width / 2f, my - laid.size.height / 2f),
                    )
                } else {
                    val half = 3.dp.toPx()
                    drawRect(
                        color = Color.White.copy(alpha = 0.9f),
                        topLeft = Offset(mx - half, my - half),
                        size = Size(half * 2, half * 2),
                    )
                }
            }

            // ---- 呼号标记（蓝/黄/红，半径随 SNR） ----
            val baseR = 3.2.dp.toPx()
            val spanR = 5.0.dp.toPx()
            for (m in callMarkers.toList().asReversed()) { // 旧的先画，新的压在上面
                val s = p.toScreen(m.lat, m.lon)
                val x = s.x.toFloat()
                val y = s.y.toFloat()
                if (x < -20f || x > size.width + 20f || y < -20f || y > size.height + 20f) continue
                val r = baseR + spanR * ((m.snr + 24).coerceIn(0, 34) / 34f)
                val color = tierColor(m.tier)
                drawCircle(color = color.copy(alpha = 0.9f), radius = r, center = Offset(x, y))
                if (m.call == selectedCall) {
                    drawCircle(
                        color = Color.White,
                        radius = r + 2.5.dp.toPx(),
                        center = Offset(x, y),
                        style = Stroke(2.dp.toPx()),
                    )
                }
                val showCall = p.scale >= p.fitScale * 2.2
                if (showCall) {
                    val txt = if (m.fromPrefix) "${m.call}~" else m.call
                    drawText(
                        textMeasurer = measurer,
                        text = txt,
                        topLeft = Offset(x + r + 2.dp.toPx(), y - 6.dp.toPx()),
                        style = callStyle,
                    )
                }
            }

            // ---- CQ 红旗（new_ui.md §4.4） ----
            val poleH = 12.dp.toPx()
            val flagW = 9.dp.toPx()
            val flagH = 6.dp.toPx()
            for (f in cqFlags) {
                val s = p.toScreen(f.lat, f.lon)
                val x = s.x.toFloat()
                val y = s.y.toFloat()
                if (x < -30f || x > size.width + 30f || y < -30f || y > size.height + 30f) continue
                drawLine(
                    color = Color(0xFFB0BEC5),
                    start = Offset(x, y),
                    end = Offset(x, y - poleH),
                    strokeWidth = 1.2.dp.toPx(),
                )
                val path = Path().apply {
                    moveTo(x, y - poleH)
                    lineTo(x + flagW, y - poleH + flagH / 2f)
                    lineTo(x, y - poleH + flagH)
                    close()
                }
                drawPath(path, color = CqRed)
                if (showCqCall || showCqSnr) {
                    val txt = buildString {
                        if (showCqCall) append(f.call)
                        if (showCqSnr) {
                            if (isNotEmpty()) append(' ')
                            append(if (f.snr >= 0) "+" else "")
                            append(f.snr)
                        }
                    }
                    drawText(
                        textMeasurer = measurer,
                        text = txt,
                        topLeft = Offset(x + flagW + 2.dp.toPx(), y - poleH),
                        style = cqStyle,
                    )
                }
            }

            // ---- 我方台站 ----
            if (myGrid != null) {
                Maidenhead.center(myGrid)?.let { (mlat, mlon) ->
                    val s = p.toScreen(mlat, mlon)
                    val x = s.x.toFloat()
                    val y = s.y.toFloat()
                    val r = 5.dp.toPx()
                    drawCircle(MyColor, radius = r, center = Offset(x, y), style = Stroke(2.dp.toPx()))
                    drawLine(MyColor, Offset(x - r, y), Offset(x + r, y), strokeWidth = 1.5.dp.toPx())
                    drawLine(MyColor, Offset(x, y - r), Offset(x, y + r), strokeWidth = 1.5.dp.toPx())
                    drawText(
                        textMeasurer = measurer,
                        text = myGrid.uppercase(),
                        topLeft = Offset(x + r + 2.dp.toPx(), y - 6.dp.toPx()),
                        style = myLabelStyle,
                    )
                }
            }
        }
    }
}
