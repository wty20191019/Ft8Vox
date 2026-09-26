package com.example.ft8vox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * [linkPhase] 为 0–1 的循环相位，用于让连线内容沿通信方向运动；**以 lambda 传入**，
 * 使其只在 Canvas 的绘制作用域被读取 —— 相位变化因此只触发重绘、不触发整页重组。
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
    linkPhase: () -> Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 注意：必须用 .value 取出再传给 DisposableEffect —— 若用 `by` 委托，onDispose 里读到的
    // 是「当前」值而非 effect 创建时的值，key 变化时会把刚建好的解码器提前 recycle。
    val baseMapState = produceState<WorldBaseMapState?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            WorldBaseMap.open(context)?.let { WorldBaseMapState(it) }
        }
    }
    val baseMap = baseMapState.value
    DisposableEffect(baseMap) {
        val state = baseMap
        onDispose { state?.close() }
    }

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

        // 预取当前视口需要的底图块（含跨界线的世界副本；缺失的异步解码，画布只画已就绪的）
        val needed = remember(projection, wPx, hPx, baseMap) {
            baseMap?.let { WorldBaseMap.blockDraws(projection) } ?: emptyList()
        }
        LaunchedEffect(needed, baseMap) { baseMap?.ensure(needed) }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val p = projection
            drawRect(Ocean, size = size)

            val bm = baseMap
            if (bm == null) {
                // 无底图：退回纯色世界矩形（按副本铺满视口，仍保持「无缝循环」）
                val r = p.visibleWorldRange()
                for (kv in floor(r.minV).toInt()..floor(r.maxV).toInt()) {
                    for (ku in floor(r.minU).toInt()..floor(r.maxU).toInt()) {
                        val tl = p.toScreenUV(ku.toDouble(), kv.toDouble())
                        val br = p.toScreenUV(ku + 1.0, kv + 1.0)
                        drawRect(
                            color = World,
                            topLeft = Offset(tl.x.toFloat(), tl.y.toFloat()),
                            size = Size(
                                (br.x - tl.x).toFloat().coerceAtLeast(0f),
                                (br.y - tl.y).toFloat().coerceAtLeast(0f),
                            ),
                        )
                    }
                }
            } else {
                // 复用上面 remember 出来的绘制清单，避免每帧重复枚举副本
                for (d in needed) {
                    val img = bm.bitmap(d.block) ?: continue
                    val dim = WorldBaseMap.SIZE shr d.block.level
                    val x0 = d.block.bx * WorldBaseMap.BLOCK
                    val y0 = d.block.by * WorldBaseMap.BLOCK
                    val bw = minOf(WorldBaseMap.BLOCK, dim - x0)
                    val bh = minOf(WorldBaseMap.BLOCK, dim - y0)
                    val tl = p.toScreenUV(x0.toDouble() / dim + d.ku, y0.toDouble() / dim + d.kv)
                    val br = p.toScreenUV((x0 + bw).toDouble() / dim + d.ku, (y0 + bh).toDouble() / dim + d.kv)
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
                        filterQuality = FilterQuality.Medium,
                        colorFilter = BaseMapDarken,
                    )
                }
            }

            // ---- 网格标记（蓝/黄/红，世界视图下保证最小可见尺寸；只画离视口中心最近的一份） ----
            val minCell = 2.4.dp.toPx()
            for (m in gridMarkers) {
                val rect = p.cellRect(m.bounds.minLat, m.bounds.maxLat, m.bounds.minLon, m.bounds.maxLon)
                val w = rect.w.toFloat()
                val h = rect.h.toFloat()
                if (w <= 0f || h <= 0f) continue
                val cx = rect.cx.toFloat()
                val cy = rect.cy.toFloat()
                val dw = w.coerceAtLeast(minCell)
                val dh = h.coerceAtLeast(minCell)
                if (cx - dw / 2f > size.width || cx + dw / 2f < 0f) continue
                if (cy - dh / 2f > size.height || cy + dh / 2f < 0f) continue
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

            // ---- 信号连线（只画最近一个时隙；跨 180° 走短弧） ----
            val phase = linkPhase().coerceIn(0f, 1f)
            for (l in links) {
                val (fa, tb) = p.linkEnds(l.fromLat, l.fromLon, l.toLat, l.toLon)
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
            // 注意：用下标倒序，避免每帧 `toList().asReversed()` 的额外分配
            for (ci in callMarkers.indices.reversed()) { // 旧的先画，新的压在上面
                val m = callMarkers[ci]
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
