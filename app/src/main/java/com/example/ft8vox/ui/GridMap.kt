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
import androidx.compose.ui.text.TextLayoutResult
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
import com.example.ft8vox.qso.CqWorked
import com.example.ft8vox.qso.GridMarker
import com.example.ft8vox.qso.MapTier
import com.example.ft8vox.qso.SignalLink
import com.example.ft8vox.ui.theme.MapLinkColor
import com.example.ft8vox.ui.theme.MapLinkMine
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- docs/UI.md §2.4 配色 ----
private val Ocean = Color(0xFF0B0B12)
private val World = Color(0xFF171C2B)
private val TierDecoded = Color(0xFF89B4FA) // 蓝：本会话解码
private val TierWorked = Color(0xFFF9E2AF) // 黄：日志已通联
private val TierConfirmed = Color(0xFFE64553) // 红：日志已确认
private val MyColor = Color(0xFF00E5FF)

// ---- CQ 旗面：**通联状态**色（照 FT8CN `tracker_cq_marker_*`，docs/UI-MOBILE.md §34） ----
// FT8CN 的第三档是「黑」（本波段已通联），在深色底图上不可见 → 本机改**灰**。
private val CqNewColor = Color(0xFFFF5252) // 红：还没通过
private val CqOtherBandColor = Color(0xFF64B5F6) // 蓝：只在别的波段通过
private val CqWorkedColor = Color(0xFF9E9E9E) // 灰：本波段已通过

/** 昼夜灰线（照 FT8CN `tracker_gray_line_color`；深色底图上略提亮）。 */
private val GrayLineColor = Color(0x3DFFFFFF)

/**
 * 层次细节（§31）：低于该缩放（相对「适应窗口」）不画**本会话解码**的网格方块。
 *
 * 世界视野下这些蓝格数量最大、只是糊成一片；放大到看清一片区域后再出现。
 */
private const val GRID_DECODED_ZOOM = 1.6

/** 呼号文字只在放大到该倍数（相对「适应窗口」）以上才参与落位（沿用手感，未改）。 */
private const val CALL_LABEL_ZOOM = 2.2

/** 文字落位优先级（§31）：小的先落位，落不下就不画。 */
private const val LABEL_MY_GRID = 0
private const val LABEL_SELECTED = 1
private const val LABEL_CQ = 2
private const val LABEL_CALL = 3

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
 * CQ 旗面颜色 = **通联状态**（照 FT8CN `tracker_cq_marker_*`，docs/UI-MOBILE.md §34）。
 *
 * 与 [tierColor] 的类别色**不共用**：网格方块的蓝/黄/红描述「这一格是解码/通联/确认」，
 * 旗色描述「这个喊 CQ 的呼号做过没有」—— 红旗=还没通过（优先应答）、蓝旗=别的波段做过、
 * 灰旗=本波段做过（自动程序也不会再呼叫它）。
 */
private fun cqFlagColor(worked: CqWorked): Color = when (worked) {
    CqWorked.NONE -> CqNewColor
    CqWorked.OTHER_BAND -> CqOtherBandColor
    CqWorked.THIS_BAND -> CqWorkedColor
}

/**
 * 离线深色地图（docs/UI.md §2.4）：底层为 **Web Mercator 卫星底图**（`assets/map/world_z5.jpg`，
 * 按可见区域流式解码，乘 0.7 暗化），其上叠加网格标记、呼号、CQ 旗帜与信号连线，**无网格线图层**。
 *
 * 底图资产缺失/解码失败时退回原来的纯色世界矩形，保证地图页始终可用。
 * 本组件只负责绘制，视口（缩放/平移）与命中测试由 [GridScreen] 管理。
 * [linkPhase] 为 0–1 的循环相位，用于让连线内容沿通信方向运动；**以 lambda 传入**，
 * 使其只在 Canvas 的绘制作用域被读取 —— 相位变化因此只触发重绘、不触发整页重组。
 *
 * 去密设计（docs/UI-MOBILE.md §31，手机屏小、标注一多就糊）：
 * 1. **一个台站只画一处标记**：喊过 CQ 的台站画旗子，其他台站画圆点；两者都与呼号文字一一对应，
 *    不会出现「同一个点又画旗又画点、还出两行文字」；
 * 2. **文字统一最后落位 + 防重叠**（[selectLabels]）：优先级 我的位置 > 选中台 > CQ 台 > 其余呼号，
 *    挤掉只挤文字，点/旗照旧（点一下仍能看详情）；
 * 3. **网格方块分级**：世界视野只画已通联/已确认，放大后才补上本会话解码的蓝格。
 *
 * 另有两项照 FT8CN（docs/UI-MOBILE.md §34）：
 * - **昼夜灰线**（[grayLine]，`GrayLine.terminator` 算出的 `(lon, lat)` 折线）画在底图之上、所有标记之下；
 * - **CQ 旗面颜色 = 通联状态**（红=未通联 / 蓝=他波段 / 灰=本波段），与网格方块的类别色无关。
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
    grayLine: List<Pair<Double, Double>> = emptyList(),
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
    /** 「与我有关」的连线文字：红色（§29）。 */
    val linkMineStyle = remember { linkStyle.copy(color = MapLinkMine) }
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

            // ---- 昼夜灰线（照 FT8CN computeDayNightTerminator，§34） ----
            // 画在底图之上、所有标记之下；相邻点取「离上一个点最近的世界副本」连接，
            // 跨 180° 接缝不会横穿整张地图。
            if (grayLine.size >= 2) {
                val stroke = 1.6.dp.toPx()
                var lastUV: Pair<Double, Double>? = null
                var lastX = 0f
                var lastY = 0f
                for (g in grayLine) {
                    val u = MapProjection.mercX(g.first)
                    val v = MapProjection.mercY(g.second)
                    val prevUV = lastUV
                    val uv = if (prevUV == null) {
                        p.nearestCopy(u, v)
                    } else {
                        p.copyNearestTo(prevUV.first, prevUV.second, u, v)
                    }
                    val s = p.toScreenUV(uv)
                    val x = s.x.toFloat()
                    val y = s.y.toFloat()
                    if (prevUV != null) {
                        drawLine(GrayLineColor, Offset(lastX, lastY), Offset(x, y), stroke)
                    }
                    lastUV = uv
                    lastX = x
                    lastY = y
                }
            }

            // ---- 网格标记（蓝/黄/红，世界视图下保证最小可见尺寸） ----
            // 层次细节（§31）：全球视野**不画「本会话解码」的蓝格** —— 它们数量最大，缩到世界尺度
            // 只会糊成一片；已通联（黄）/ 已确认（红）数量少且是重点，任何缩放都画。
            val minCell = 2.4.dp.toPx()
            val showDecodedGrids = p.scale >= p.fitScale * GRID_DECODED_ZOOM
            for (m in gridMarkers) {
                if (m.tier == MapTier.DECODED && !showDecodedGrids) continue
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
            // 「与我有关」的连线（报告 / R报告 / 73 / RR73）线身与文字都标红（§29）
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
                    color = if (l.mine) MapLinkMine.copy(alpha = 0.8f) else MapLinkColor,
                    start = Offset(fx, fy),
                    end = Offset(tx, ty),
                    strokeWidth = if (l.mine) 2f else 1.4.dp.toPx(),
                )
                val mx = fx + (tx - fx) * phase
                val my = fy + (ty - fy) * phase
                val label = l.label
                if (showLinkText && label != null) {
                    val style = if (l.mine) linkMineStyle else linkStyle
                    val laid = measurer.measure(AnnotatedString(label), style)
                    drawText(
                        textLayoutResult = laid,
                        topLeft = Offset(mx - laid.size.width / 2f, my - laid.size.height / 2f),
                    )
                } else {
                    val half = 3.dp.toPx()
                    drawRect(
                        color = if (l.mine) Color(0xFFFFC9C9) else Color.White.copy(alpha = 0.9f),
                        topLeft = Offset(mx - half, my - half),
                        size = Size(half * 2, half * 2),
                    )
                }
            }

            // ---- 台站标记：**一个台站只画一处**（§31） ----
            // 喊过 CQ 的台站用「旗子」当标记：不再叠一个圆点、也不再出两行文字；其余台站画圆点。
            // 圆点颜色 = **类别**（蓝=本会话解码 / 黄=已通联 / 红=已确认）；
            // 旗面颜色 = **通联状态**（红=未通联 / 蓝=他波段 / 灰=本波段，§34）——「在 CQ」由**形状**（旗）表达，
            // 这样「红花+红点」那种重合就没了，且不看文字也知道这个 CQ 台值不值得应答。
            // 遍历以**呼号标记**为准：CQ 台一定也有呼号标记（同一张定位表、且呼号标记的时间窗更宽，
            // 见 MapModel.gridLookup）；极端情况（标记数超过 maxMarkers 上限被截断）下宁可不画旗，
            // 也不画一面没有对应台站的旗。
            val flagByCall = HashMap<String, CqFlag>(cqFlags.size * 2)
            for (f in cqFlags) flagByCall[f.call.trim().uppercase()] = f
            val baseR = 3.2.dp.toPx()
            val spanR = 5.0.dp.toPx()
            val poleH = 12.dp.toPx()
            val flagW = 9.dp.toPx()
            val flagH = 6.dp.toPx()
            val showCall = p.scale >= p.fitScale * CALL_LABEL_ZOOM
            // 文字统一最后画：这里只收集，最后一起防重叠落位
            val pending = ArrayList<PendingLabel>(callMarkers.size + 1)
            // 注意：用下标倒序，避免每帧 `toList().asReversed()` 的额外分配（旧的先画，新的压在上面）
            for (ci in callMarkers.indices.reversed()) {
                val m = callMarkers[ci]
                val s = p.toScreen(m.lat, m.lon)
                val x = s.x.toFloat()
                val y = s.y.toFloat()
                if (x < -20f || x > size.width + 20f || y < -20f || y > size.height + 20f) continue
                val flag = flagByCall[m.call.trim().uppercase()]
                val snr = flag?.snr ?: m.snr
                val r = baseR + spanR * ((snr + 24).coerceIn(0, 34) / 34f)
                val color = tierColor(m.tier)
                if (flag != null) {
                    // 旗子（杆 + 三角旗面）
                    drawLine(
                        color = Color(0xFFB0BEC5),
                        start = Offset(x, y),
                        end = Offset(x, y - poleH),
                        strokeWidth = 1.2.dp.toPx(),
                    )
                    drawPath(
                        Path().apply {
                            moveTo(x, y - poleH)
                            lineTo(x + flagW, y - poleH + flagH / 2f)
                            lineTo(x, y - poleH + flagH)
                            close()
                        },
                        // 旗面颜色 = **通联状态**（§34），不是网格方块的类别色
                        color = cqFlagColor(flag.worked),
                    )
                } else {
                    drawCircle(color = color.copy(alpha = 0.9f), radius = r, center = Offset(x, y))
                }
                if (m.call == selectedCall) {
                    drawCircle(
                        color = Color.White,
                        radius = r + 2.5.dp.toPx(),
                        center = Offset(x, y),
                        style = Stroke(2.dp.toPx()),
                    )
                }
                // 一个台站只出一条文字：CQ 台是「呼号 + 可选强度」，其余只出呼号（放大后）
                val callTxt = if (m.fromPrefix) "${m.call}~" else m.call
                val txt = buildString {
                    if (flag == null) {
                        if (showCall) append(callTxt)
                    } else {
                        if (showCqCall || showCall) append(callTxt)
                        if (showCqSnr) {
                            if (isNotEmpty()) append(' ')
                            append(if (snr >= 0) "+" else "")
                            append(snr)
                        }
                    }
                }
                if (txt.isEmpty()) continue
                val laid = measurer.measure(AnnotatedString(txt), if (flag != null) cqStyle else callStyle)
                pending.add(
                    PendingLabel(
                        priority = when {
                            m.call == selectedCall -> LABEL_SELECTED
                            flag != null -> LABEL_CQ
                            else -> LABEL_CALL
                        },
                        layout = laid,
                        left = x + (if (flag != null) flagW else r) + 2.dp.toPx(),
                        top = if (flag != null) y - poleH else y - 6.dp.toPx(),
                    ),
                )
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
                    val laid = measurer.measure(AnnotatedString(myGrid.uppercase()), myLabelStyle)
                    pending.add(
                        PendingLabel(
                            priority = LABEL_MY_GRID,
                            layout = laid,
                            left = x + r + 2.dp.toPx(),
                            top = y - 6.dp.toPx(),
                        ),
                    )
                }
            }

            // ---- 文字统一落位（防重叠，§31） ----
            // 优先级：我的位置 > 选中的台站 > CQ 台 > 其余呼号；同优先级内**新解码的优先**。
            // 挤掉的只是文字，点/旗仍在，点一下照样看详情。
            pending.reverse() // 上面是「旧→新」收集的，倒过来变成「新→旧」
            val ordered = pending.sortedBy { it.priority } // sortedBy 稳定，同优先级保持「新→旧」
            val boxes = ordered.map {
                LabelBox(it.left, it.top, it.left + it.layout.size.width, it.top + it.layout.size.height)
            }
            val keep = selectLabels(boxes, gap = 1.dp.toPx())
            for (i in ordered.indices) {
                if (!keep[i]) continue
                drawText(
                    textLayoutResult = ordered[i].layout,
                    topLeft = Offset(ordered[i].left, ordered[i].top),
                )
            }
        }
    }
}

/** 待落位的地图文字（§31）：[priority] 小的先落位，落不下的（与已落位文字重叠）直接不画。 */
private data class PendingLabel(
    val priority: Int,
    val layout: TextLayoutResult,
    val left: Float,
    val top: Float,
)
