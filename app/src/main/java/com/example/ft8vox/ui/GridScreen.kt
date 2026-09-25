package com.example.ft8vox.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.log.QsoEntity
import com.example.ft8vox.grid.GridGranularity
import com.example.ft8vox.grid.GridIndex
import com.example.ft8vox.grid.MapProjection
import com.example.ft8vox.grid.Maidenhead
import com.example.ft8vox.qso.HighlightRole
import com.example.ft8vox.qso.LiveSpot
import com.example.ft8vox.qso.SpotBuilder
import kotlin.math.hypot

/**
 * 网格页：离线 Canvas 地图。
 *
 * - 历史底图：已通联/已确认网格按 [GridGranularity] 着色；
 * - 实时层：本会话解码出的台站按其网格投点，颜色=高亮分类、半径/亮度=SNR；
 * - 交互：单指拖动平移、双指缩放、点击选取台站或网格。
 */
@Composable
fun GridScreen(
    log: LogViewModel,
    session: SessionViewModel,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val entries by log.entries.collectAsState()
    val stats by log.stats.collectAsState()
    val messages by session.messages.collectAsState()
    val worked by session.workedIndex.collectAsState()
    val status by session.status.collectAsState()

    var granularity by remember { mutableStateOf(GridGranularity.SQUARE) }
    var showRealtime by remember { mutableStateOf(true) }
    var selectedSpot by remember { mutableStateOf<LiveSpot?>(null) }
    var selectedGrid by remember { mutableStateOf<String?>(null) }

    // ---- 数据派生 ----
    val confirmedGrids = remember(entries) {
        entries.filter { it.qslRcvd == "Y" || it.lotwRcvd == "Y" }
            .mapNotNull { it.theirGrid }
            .toSet()
    }
    val workedGrids = remember(entries) {
        entries.mapNotNull { it.theirGrid?.takeIf { g -> g.isNotBlank() } }.toSet()
    }
    val cells = remember(workedGrids, confirmedGrids, granularity) {
        GridIndex.cells(workedGrids, confirmedGrids, granularity)
    }
    val gridCache = remember(entries) {
        val m = HashMap<String, String>()
        for (e in entries) {
            e.theirGrid?.takeIf { it.isNotBlank() }?.let { m[e.theirCall.uppercase()] = it }
        }
        m
    }
    val nowMs = System.currentTimeMillis()
    val spots = remember(messages, worked, status.qso.theirCall, status.myCall, showRealtime) {
        if (!showRealtime) {
            emptyList()
        } else {
            SpotBuilder.build(
                messages = messages,
                worked = worked,
                currentQsoCall = status.qso.theirCall,
                myCall = status.myCall,
                nowMs = nowMs,
                gridCache = gridCache,
            )
        }
    }
    val fieldCount = remember(workedGrids) {
        workedGrids.mapNotNull { Maidenhead.field(it) }.toSet().size
    }

    // 权限（网格页也能直接应答）
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // 待确认应答的台站（防误发闸门）
    var pendingReply by remember { mutableStateOf<LiveSpot?>(null) }
    // 已过确认、等待录音权限的台站
    var afterPermission by remember { mutableStateOf<LiveSpot?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        val spot = afterPermission
        afterPermission = null
        if (granted && spot != null) session.answer(spot.call, spot.grid, spot.df)
    }

    fun confirmReply(spot: LiveSpot) {
        if (permissionGranted) {
            session.answer(spot.call, spot.grid, spot.df)
        } else {
            afterPermission = spot
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ---- 视口 ----
    var projection by remember { mutableStateOf<MapProjection?>(null) }
    val density = LocalDensity.current

    Column(modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text("网格", style = MaterialTheme.typography.titleLarge)
        Text(
            "已通联 ${workedGrids.size}｜已确认 ${confirmedGrids.size}｜大网格 $fieldCount / 324｜实时 ${spots.size} 台",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        LinearProgressIndicator(
            progress = { fieldCount / 324f },
            modifier = Modifier.fillMaxWidth().height(5.dp).padding(top = 2.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (g in GridGranularity.entries) {
                FilterChip(
                    selected = granularity == g,
                    onClick = { granularity = g },
                    label = { Text(g.label) },
                )
            }
            FilterChip(
                selected = showRealtime,
                onClick = { showRealtime = !showRealtime },
                label = { Text("实时") },
            )
            OutlinedButton(
                onClick = {
                    projection = MapProjection.fit(
                        projection?.viewWidth ?: 1.0,
                        projection?.viewHeight ?: 1.0,
                    )
                },
            ) { Text("适应窗口") }
        }

        // ---- 地图（固定 2:1，与世界等距圆柱一致：不拉伸、默认铺满全球） ----
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f)
                .background(Color(0xFF0E1A2B)),
        ) {
            val wPx = with(density) { maxWidth.toPx() }.toDouble()
            val hPx = with(density) { maxHeight.toPx() }.toDouble()

            LaunchedEffect(wPx, hPx) {
                if (wPx > 0 && hPx > 0) {
                    val cur = projection
                    projection = if (cur == null) {
                        MapProjection.fit(wPx, hPx)
                    } else {
                        MapProjection(cur.viewWidth, cur.viewHeight, cur.scale, cur.centerLon, cur.centerLat)
                            .copy(viewWidth = wPx, viewHeight = hPx)
                    }
                }
            }

            val p = projection
            if (p != null) {
                val tapThreshold = with(density) { 26.dp.toPx() }
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
                                // 命中实时点（按屏幕距离）
                                var best: LiveSpot? = null
                                var bestD = Float.MAX_VALUE
                                for (s in spots) {
                                    val q = proj.toScreen(s.lat, s.lon)
                                    val d = hypot(q.x.toFloat() - offset.x, q.y.toFloat() - offset.y)
                                    if (d < bestD) {
                                        bestD = d
                                        best = s
                                    }
                                }
                                if (best != null && bestD <= tapThreshold) {
                                    selectedSpot = best
                                    selectedGrid = best!!.grid
                                } else {
                                    selectedSpot = null
                                    val (lat, lon) = proj.toGeo(offset.x.toDouble(), offset.y.toDouble())
                                    val precision = if (granularity == GridGranularity.FIELD) 2 else 4
                                    selectedGrid = Maidenhead.encode(
                                        lat.coerceIn(-90.0, 90.0),
                                        lon.coerceIn(-180.0, 180.0),
                                        precision,
                                    )
                                }
                            }
                        },
                ) {
                    GridMap(
                        projection = p,
                        cells = cells,
                        spots = spots,
                        myGrid = status.myGrid.ifEmpty { null },
                        nowMs = nowMs,
                        maxAgeMs = SpotBuilder.DEFAULT_WINDOW_MS,
                        showLabels = true,
                        selectedCall = selectedSpot?.call,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Legend()

        HorizontalDivider(Modifier.padding(vertical = 3.dp))

        if (selectedSpot != null || selectedGrid != null) {
            SelectionPanel(
                spot = selectedSpot,
                grid = selectedGrid,
                granularity = granularity,
                entries = entries,
                onReply = { spot -> pendingReply = spot },
                onOpenLog = { call ->
                    log.setFilter(LogFilter(query = call))
                    onOpenLog()
                },
            )
            TextButton(onClick = {
                selectedSpot = null
                selectedGrid = null
            }) { Text("清除选择") }
        } else {
            LiveList(
                spots = spots,
                onSelect = { s ->
                    selectedSpot = s
                    selectedGrid = s.grid
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    pendingReply?.let { spot ->
        TxConfirmDialog(
            pending = PendingTx.Reply(spot.call, spot.grid, spot.df),
            status = status,
            onConfirm = {
                pendingReply = null
                confirmReply(spot)
            },
            onDismiss = { pendingReply = null },
        )
    }
}

@Composable
private fun LiveList(
    spots: List<LiveSpot>,
    onSelect: (LiveSpot) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            "当前接收 ${spots.size} 台（最近 15 分钟）",
            style = MaterialTheme.typography.titleSmall,
        )
        if (spots.isEmpty()) {
            Text(
                "等待解码…（无天线信号时为空，真机或音频注入后出现）",
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(spots, key = { it.call }) { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(s) }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            String.format(
                                java.util.Locale.US,
                                "%+3d  %4d  %-4s  %-7s  %s",
                                s.snr,
                                s.df,
                                roleShort(s.style.role),
                                s.call,
                                s.grid,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Text(QsoTime.isoTime(s.utcMs), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun roleShort(role: HighlightRole): String = when (role) {
    HighlightRole.TX -> "发射"
    HighlightRole.TO_ME -> "给我"
    HighlightRole.CQ -> "CQ"
    HighlightRole.WORKED -> "已通"
    HighlightRole.DUPLICATE -> "重复"
    HighlightRole.NEW_GRID -> "新格"
    HighlightRole.NEW_ENTITY -> "新实体"
    HighlightRole.NEW_CALL -> "新呼号"
    HighlightRole.NORMAL -> "普通"
}

@Composable
private fun Legend() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("● 已通联", style = MaterialTheme.typography.labelSmall, color = Color(0xFF66BB6A))
        Text("● 已确认", style = MaterialTheme.typography.labelSmall, color = Color(0xFF1E88E5))
        Text("● 新网格", style = MaterialTheme.typography.labelSmall, color = Color(0xFF00C853))
        Text("● 发给我", style = MaterialTheme.typography.labelSmall, color = Color(0xFF00B0FF))
        Text("● 当前", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFAB00))
        Text("大小=SNR", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SelectionPanel(
    spot: LiveSpot?,
    grid: String?,
    granularity: GridGranularity,
    entries: List<QsoEntity>,
    onReply: (LiveSpot) -> Unit,
    onOpenLog: (String) -> Unit,
) {
    if (grid == null) {
        Text(
            "点击地图上的台站点查看详情；点击网格查看历史通联。",
            style = MaterialTheme.typography.labelSmall,
        )
        return
    }

    val matches = remember(entries, grid, granularity) {
        entries.filter { e ->
            val g = e.theirGrid?.uppercase() ?: return@filter false
            if (granularity == GridGranularity.FIELD) g.startsWith(grid)
            else g.take(4) == grid.take(4)
        }
    }

    Column {
        if (spot != null) {
            Text(
                String.format(
                    java.util.Locale.US,
                    "%s  %s  %+d dB  DT %+.1f  %d Hz  %s",
                    spot.call,
                    spot.grid,
                    spot.snr,
                    spot.dt,
                    spot.df,
                    QsoTime.isoTime(spot.utcMs),
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onReply(spot) }) { Text("应答") }
                OutlinedButton(onClick = { onOpenLog(spot.call) }) { Text("在日志中查看") }
            }
        } else {
            Text(
                "网格 $grid：${matches.size} 条通联",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (matches.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                for (e in matches.take(10)) {
                    Text(
                        String.format(
                            java.util.Locale.US,
                            "%s  %s%s  %s  %s",
                            QsoTime.isoDateTime(e.utcMs),
                            e.theirCall,
                            e.theirGrid?.let { " ($it)" } ?: "",
                            e.band,
                            e.mode,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (matches.size > 10) {
                    Text("…共 ${matches.size} 条", style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            Text("该网格暂无通联记录", style = MaterialTheme.typography.labelSmall)
        }
    }
}
