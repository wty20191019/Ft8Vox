package com.example.ft8vox.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.grid.MapProjection
import com.example.ft8vox.qso.CallMarker
import com.example.ft8vox.qso.CqFlag
import com.example.ft8vox.qso.GridMarker
import com.example.ft8vox.qso.MapModel
import com.example.ft8vox.qso.SignalLink
import com.example.ft8vox.ui.theme.VoxAccent
import kotlin.math.hypot
import kotlinx.coroutines.delay

private val LegendDecoded = Color(0xFF89B4FA)
private val LegendWorked = Color(0xFFF9E2AF)
private val LegendConfirmed = Color(0xFFE64553)
private val Ocean = Color(0xFF0B0B12)

/** 信号连线相位动画的刷新间隔（ms）：缓慢移动的光点不需要 60fps，约 12.5 fps 已足够顺滑。 */
private const val LINK_PHASE_FRAME_MS = 80L

/** 相位循环周期（ms）：光点沿连线从一端走到另一端的时间。 */
private const val LINK_PHASE_PERIOD_MS = 2200L

/**
 * 地图页（new_ui.md §4）：全屏深色底图 + 蓝/黄/红标记 + 呼号标记 + CQ 红旗 + 信号连线。
 *
 * - 蓝色 = 本会话解码（重启清空）；黄色 = 日志已通联；红色 = 日志已确认。
 * - 呼号无网格时用前缀归属地近似坐标（[com.example.ft8vox.qso.CallLocation]）。
 * - 右下浮控缩放 / 回我的位置；底部浮层图例、统计与显示开关。
 */
@Composable
fun GridScreen(
    log: LogViewModel,
    session: SessionViewModel,
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onOpenLog: () -> Unit,
    focusCall: String? = null,
    focusSeq: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val entries by log.entries.collectAsState()
    val messages by session.messages.collectAsState()
    val status by session.status.collectAsState()

    // ---- 日志派生：已通联 / 已确认（网格与呼号） ----
    val confirmedEntries = remember(entries) {
        entries.filter { it.qslRcvd == "Y" || it.lotwRcvd == "Y" }
    }
    val workedGrids = remember(entries) { entries.mapNotNull { it.theirGrid?.takeIf { g -> g.isNotBlank() } } }
    val confirmedGrids = remember(confirmedEntries) {
        confirmedEntries.mapNotNull { it.theirGrid?.takeIf { g -> g.isNotBlank() } }
    }
    val workedCalls = remember(entries) { entries.map { it.theirCall.trim().uppercase() }.toSet() }
    val confirmedCalls = remember(confirmedEntries) { confirmedEntries.map { it.theirCall.trim().uppercase() }.toSet() }
    val gridCache = remember(entries) {
        val m = HashMap<String, String>()
        for (e in entries) {
            e.theirGrid?.takeIf { it.isNotBlank() }?.let { m[e.theirCall.trim().uppercase()] = it }
        }
        m
    }

    // ---- 地图层（整会话，不做时间窗；消息上限 200 条） ----
    val decodedSquares = remember(messages) { MapModel.decodedSquares(messages) }
    val gridMarkers = remember(decodedSquares, workedGrids, confirmedGrids) {
        MapModel.gridMarkers(decodedSquares, workedGrids, confirmedGrids)
    }
    val callMarkers = remember(messages, workedCalls, confirmedCalls, status.myCall, gridCache) {
        MapModel.callMarkers(
            messages = messages,
            workedCalls = workedCalls,
            confirmedCalls = confirmedCalls,
            myCall = status.myCall,
            windowMs = 0L,
            gridCache = gridCache,
        )
    }
    val cqFlags = remember(messages, status.myCall, gridCache) {
        MapModel.cqFlags(messages, myCall = status.myCall, windowMs = 0L, gridCache = gridCache)
    }
    val links = remember(messages, status.myCall, status.myGrid, gridCache) {
        MapModel.signalLinks(
            messages = messages,
            myCall = status.myCall,
            myGrid = status.myGrid.ifEmpty { null },
            gridCache = gridCache,
        )
    }

    // 连线内容沿通信方向循环运动。
    //
    // 性能（2026-09-26 优化）：原实现用 `rememberInfiniteTransition` **无条件**以 60fps 推进相位，
    // 使地图页即使一条连线都没有也整页 60fps 重组 + 重绘（模拟器实测 60.7 fps、GPU 18ms/帧、
    // 占整机约 34% CPU，是操作页的 2.6 倍）。现在改为：
    //   1) 只有存在连线时才推进相位 —— 没有连线时地图完全静止（0 帧），一帧都不画；
    //   2) 相位更新限流到约 12.5 fps，且相位只在 GridMap 的 draw 作用域被读取（`() -> Float`），
    //      因此相位变化只触发重绘、不触发整页重组。
    // 视觉上连线光点仍在动，只是刷新率从 60fps 降到 12.5fps。
    val linkPhaseState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(links.isNotEmpty()) {
        if (links.isEmpty()) {
            linkPhaseState.floatValue = 0f
            return@LaunchedEffect
        }
        while (true) {
            linkPhaseState.floatValue =
                (System.nanoTime() / 1_000_000L % LINK_PHASE_PERIOD_MS) / LINK_PHASE_PERIOD_MS.toFloat()
            delay(LINK_PHASE_FRAME_MS)
        }
    }

    // ---- 交互状态 ----
    var projection by remember { mutableStateOf<MapProjection?>(null) }
    var selectedCall by remember { mutableStateOf<String?>(null) }
    var overlayHeightPx by remember { mutableStateOf(0) }
    var pendingFocus by remember { mutableStateOf<String?>(null) }

    // 权限（地图页也能直接应答）
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // 应答动作在等录音权限期间暂存（不再有「确认发射」弹窗：点了就直接发）
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        val action = afterPermission
        afterPermission = null
        if (granted) action?.invoke()
    }

    /** 应答该台：已授权直接执行，否则先申请录音权限、授权后补执行。 */
    fun replyTo(call: String, grid: String?, df: Int?) {
        val action = { session.answer(call, grid, df) }
        if (permissionGranted) action() else {
            afterPermission = action
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 从操作页双击解码行跳转：定位到该呼号
    LaunchedEffect(focusSeq) {
        if (!focusCall.isNullOrEmpty()) pendingFocus = focusCall
    }
    LaunchedEffect(pendingFocus, projection, callMarkers) {
        val target = pendingFocus ?: return@LaunchedEffect
        val p = projection ?: return@LaunchedEffect
        val m = callMarkers.firstOrNull { it.call.equals(target, ignoreCase = true) } ?: return@LaunchedEffect
        selectedCall = m.call
        projection = centeredOn(p, m.lat, m.lon)
        pendingFocus = null
    }

    val markersState = rememberUpdatedState(callMarkers)
    val flagsState = rememberUpdatedState(cqFlags)

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wPx = with(density) { maxWidth.toPx() }.toDouble()
            val hPx = with(density) { maxHeight.toPx() }.toDouble()

            LaunchedEffect(wPx, hPx) {
                if (wPx > 0 && hPx > 0) {
                    val cur = projection
                    projection = if (cur == null) {
                        MapProjection.fill(wPx, hPx)
                    } else {
                        MapProjection(cur.viewWidth, cur.viewHeight, cur.scale, cur.centerLon, cur.centerLat)
                            .copy(viewWidth = wPx, viewHeight = hPx)
                    }
                }
            }

            val p = projection
            if (p != null) {
                val tapThreshold = with(density) { 30.dp.toPx() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val base = projection ?: return@detectTransformGestures
                                var np = base
                                if (pan.x != 0f || pan.y != 0f) {
                                    np = np.panBy(pan.x.toDouble(), pan.y.toDouble())
                                }
                                if (zoom != 1f) {
                                    np = np.zoomBy(zoom.toDouble(), centroid.x.toDouble(), centroid.y.toDouble())
                                }
                                projection = np
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val proj = projection ?: return@detectTapGestures
                                selectedCall = hitTest(
                                    proj = proj,
                                    x = offset.x,
                                    y = offset.y,
                                    threshold = tapThreshold,
                                    markers = markersState.value,
                                    flags = flagsState.value,
                                )
                            }
                        },
                ) {
                    GridMap(
                        projection = p,
                        gridMarkers = gridMarkers,
                        callMarkers = callMarkers,
                        cqFlags = cqFlags,
                        links = links,
                        myGrid = status.myGrid.ifEmpty { null },
                        showCqCall = settings.mapCqFlagShowCall,
                        showCqSnr = settings.mapCqFlagShowSnr,
                        showLinkText = settings.mapShowLinkText,
                        selectedCall = selectedCall,
                        linkPhase = { linkPhaseState.floatValue },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // 右下浮控：放大 / 缩小 / 回我的位置（浮于底部浮层之上）
            val bottomPad = with(density) { overlayHeightPx.toDp() }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = bottomPad + 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MapFab(onClick = {
                    projection?.let { projection = it.zoomBy(1.6, it.viewWidth / 2, it.viewHeight / 2) }
                }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                MapFab(onClick = {
                    projection?.let { projection = it.zoomBy(1 / 1.6, it.viewWidth / 2, it.viewHeight / 2) }
                }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                MapFab(onClick = {
                    val p2 = projection ?: return@MapFab
                    val c = status.myGrid.ifEmpty { null }?.let { com.example.ft8vox.grid.Maidenhead.center(it) }
                        ?: return@MapFab
                    projection = centeredOn(p2, c.first, c.second)
                }) { Icon(Icons.Filled.Place, contentDescription = "回我的位置") }
            }
        }

        MapOverlay(
            gridMarkers = gridMarkers,
            callMarkers = callMarkers,
            cqFlags = cqFlags,
            links = links,
            selectedCall = selectedCall,
            settings = settings,
            onUpdateSettings = onUpdateSettings,
            onReply = { call, grid, df -> replyTo(call, grid, df) },
            onOpenLog = { call ->
                log.setFilter(LogFilter(query = call))
                onOpenLog()
            },
            onClearSelection = { selectedCall = null },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .onGloballyPositioned { overlayHeightPx = it.size.height },
        )
    }
}

/** 以某点为中心、放大到「适应窗口 × 6」的视口。 */
private fun centeredOn(p: MapProjection, lat: Double, lon: Double): MapProjection {
    val scale = (p.fitScale * 6.0).coerceIn(p.minScale, p.maxScale)
    return MapProjection(p.viewWidth, p.viewHeight, scale, lon, lat).clamped()
}

/** 命中最近的呼号标记 / CQ 旗帜；超出阈值返回 null。 */
private fun hitTest(
    proj: MapProjection,
    x: Float,
    y: Float,
    threshold: Float,
    markers: List<CallMarker>,
    flags: List<CqFlag>,
): String? {
    var best: String? = null
    var bestD = Float.MAX_VALUE
    for (m in markers) {
        val q = proj.toScreen(m.lat, m.lon)
        val d = hypot(q.x.toFloat() - x, q.y.toFloat() - y)
        if (d < bestD) {
            bestD = d
            best = m.call
        }
    }
    for (f in flags) {
        val q = proj.toScreen(f.lat, f.lon)
        val d = hypot(q.x.toFloat() - x, q.y.toFloat() - y)
        if (d < bestD) {
            bestD = d
            best = f.call
        }
    }
    return if (best != null && bestD <= threshold) best else null
}

@Composable
private fun MapFab(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun MapOverlay(
    gridMarkers: List<GridMarker>,
    callMarkers: List<CallMarker>,
    cqFlags: List<CqFlag>,
    links: List<SignalLink>,
    selectedCall: String?,
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onReply: (String, String?, Int) -> Unit,
    onOpenLog: (String) -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val decoded = gridMarkers.count { it.tier == com.example.ft8vox.qso.MapTier.DECODED }
    val worked = gridMarkers.count { it.tier == com.example.ft8vox.qso.MapTier.WORKED }
    val confirmed = gridMarkers.count { it.tier == com.example.ft8vox.qso.MapTier.CONFIRMED }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (selectedCall != null) {
                val marker = callMarkers.firstOrNull { it.call.equals(selectedCall, ignoreCase = true) }
                val flag = cqFlags.firstOrNull { it.call.equals(selectedCall, ignoreCase = true) }
                SelectionInfo(
                    call = selectedCall,
                    marker = marker,
                    flag = flag,
                    onReply = { onReply(selectedCall, marker?.grid, marker?.df ?: 0) },
                    onOpenLog = { onOpenLog(selectedCall) },
                    onClear = onClearSelection,
                )
            }

            // 图例
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(LegendDecoded, "解码")
                LegendDot(LegendWorked, "通联")
                LegendDot(LegendConfirmed, "确认")
                LegendDot(LegendConfirmed, "CQ")
                Text(
                    "大小=SNR",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "网格 蓝 $decoded · 黄 $worked · 红 $confirmed｜呼号 ${callMarkers.size}｜CQ ${cqFlags.size}｜连线 ${links.size}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            // 显示开关
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = settings.mapCqFlagShowCall,
                    onClick = { onUpdateSettings { it.copy(mapCqFlagShowCall = !it.mapCqFlagShowCall) } },
                    label = { Text("CQ 呼号") },
                )
                FilterChip(
                    selected = settings.mapCqFlagShowSnr,
                    onClick = { onUpdateSettings { it.copy(mapCqFlagShowSnr = !it.mapCqFlagShowSnr) } },
                    label = { Text("CQ 强度") },
                )
                FilterChip(
                    selected = settings.mapShowLinkText,
                    onClick = { onUpdateSettings { it.copy(mapShowLinkText = !it.mapShowLinkText) } },
                    label = { Text("连线文字") },
                )
            }
        }
    }
}

@Composable
private fun SelectionInfo(
    call: String,
    marker: CallMarker?,
    flag: CqFlag?,
    onReply: () -> Unit,
    onOpenLog: () -> Unit,
    onClear: () -> Unit,
) {
    val snr = marker?.snr ?: flag?.snr
    val df = marker?.df
    val time = marker?.utcMs ?: flag?.utcMs
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            String.format(
                java.util.Locale.US,
                "%s%s%s%s%s",
                call,
                marker?.grid?.let { "  $it" } ?: (if (marker?.fromPrefix == true) "  (前缀)" else ""),
                snr?.let { String.format(java.util.Locale.US, "  %+d dB", it) } ?: "",
                df?.let { "  $it Hz" } ?: "",
                time?.let { "  " + QsoTime.isoTime(it) } ?: "",
            ),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            color = VoxAccent,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onReply) { Text("应答") }
            OutlinedButton(onClick = onOpenLog) { Text("日志") }
            TextButton(onClick = onClear) { Text("清除") }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
}
