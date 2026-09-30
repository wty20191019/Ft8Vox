package com.example.ft8vox.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.grid.Geo
import com.example.ft8vox.qso.MessageParser
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 「跟踪 CQ 列表」面板（操作页控制行的「跟踪 N」按钮打开）。
 *
 * 与**解码列表**是两回事：这里列的是**呼号**（[follows] 名单），每行带入本会话最近一次听到的
 * 网格 / 信号 / 时间；手势 **左滑 = 呼叫该呼号**、**右滑 = 取消跟踪（从名单移除）**，
 * 标题栏右侧「全部清除」可一次清空（带二次确认）。
 */
@Composable
fun FollowListPanel(
    follows: Set<String>,
    messages: List<DecodeResult>,
    myGrid: String,
    onCall: (call: String, grid: String?, df: Int?) -> Unit,
    onUnfollow: (String) -> Unit,
    /** 点「全部清除」时回调（弹确认框的逻辑由调用方负责）。 */
    onClearAll: () -> Unit,
    /** **旧版遗留**：由已删除的「自动收录 CQ 台」写过的呼号（行内显示「自动」标记）。 */
    autoFollowed: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val rows = remember(follows, messages, myGrid, autoFollowed) {
        buildFollowRows(follows, messages, myGrid, autoFollowed)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("跟踪 CQ ${rows.size}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text(
                "左滑呼叫 · 右滑取消跟踪",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rows.isNotEmpty()) {
                TextButton(onClick = onClearAll) { Text("全部清除") }
            }
        }
        HorizontalDivider()
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有跟踪的呼号。\n在解码列表长按某台 →「跟踪」，就会出现在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(rows, key = { it.call }) { row ->
                    FollowCard(
                        row = row,
                        myGrid = myGrid,
                        onCall = { onCall(row.call, row.grid, row.df) },
                        onUnfollow = { onUnfollow(row.call) },
                    )
                }
            }
        }
    }
}

/** 跟踪列表的一行：呼号 + 本会话最近一次听到的信息（没有则为空）。 */
private data class FollowRow(
    val call: String,
    val grid: String? = null,
    val snr: Int? = null,
    val df: Int? = null,
    val slotUtcMs: Long? = null,
    val distKm: Double? = null,
    /** **旧版遗留**：由已删除的「自动收录 CQ 台」自动加入。 */
    val auto: Boolean = false,
)

/** 每个跟踪呼号取本会话**最新**一条解码（`messages` 为新→旧），最近听到的排前面。 */
private fun buildFollowRows(
    follows: Set<String>,
    messages: List<DecodeResult>,
    myGrid: String,
    autoFollowed: Set<String>,
): List<FollowRow> {
    val heard = HashMap<String, FollowRow>()
    for (m in messages) {
        val p = MessageParser.parse(m.text)
        val from = p.from?.trim()?.uppercase() ?: continue
        if (from !in follows || from in heard) continue
        heard[from] = FollowRow(
            call = from,
            grid = p.grid,
            snr = m.snr,
            df = m.df,
            slotUtcMs = m.slotUtcMs.takeIf { it > 0 },
            distKm = Geo.betweenGrids(myGrid, p.grid)?.first,
            auto = from in autoFollowed,
        )
    }
    val heardRows = heard.values.sortedByDescending { it.slotUtcMs ?: Long.MIN_VALUE }
    val unheardRows = follows.filter { it !in heard }.sorted()
        .map { FollowRow(call = it, auto = it in autoFollowed) }
    return heardRows + unheardRows
}

/** 呼号行（左滑呼叫 / 右滑取消跟踪），滑动机制与解码卡片一致。 */
@Composable
private fun FollowCard(
    row: FollowRow,
    myGrid: String,
    onCall: () -> Unit,
    onUnfollow: () -> Unit,
) {
    val density = LocalDensity.current
    val maxSwipe = with(density) { 140.dp.toPx() }
    val threshold = with(density) { 76.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 手势回调走 rememberUpdatedState 取**最新**：节点按呼号复用（`key = it.call`），
    // 而同一呼号的网格/频率会随后续解码刷新；`pointerInput(Unit)` 协程不重启，
    // 直接捕获的 onCall 会停留在首次组合那一帧的 grid/df —— 真机现象：
    // 左滑呼叫用的是**过期**的网格与发射频率。
    val latestOnCall by rememberUpdatedState(onCall)
    val latestOnUnfollow by rememberUpdatedState(onUnfollow)

    Box(Modifier.fillMaxWidth()) {
        // 滑动背景提示：右移露出左侧「取消跟踪」，左移露出右侧「呼叫」
        Row(Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (offsetX.value > 4f) SwipeDeleteGray else Color.Transparent),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (offsetX.value > 4f) {
                    Text("取消跟踪", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (offsetX.value < -4f) SwipeCallGreen else Color.Transparent),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (offsetX.value < -4f) {
                    Text("呼叫", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .offset { IntOffset(offsetX.value.toInt(), 0) }
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                when {
                                    offsetX.value <= -threshold -> {
                                        latestOnCall()
                                        offsetX.animateTo(0f)
                                    }
                                    offsetX.value >= threshold -> {
                                        latestOnUnfollow()
                                        offsetX.animateTo(0f)
                                    }
                                    else -> offsetX.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-maxSwipe, maxSwipe))
                            }
                        },
                    )
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.call,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (row.auto) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "自动",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    followSubLine(row),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 行第二行：网格 · SNR · 音频频率 · 时间 · 距离；没有听过则提示。 */
private fun followSubLine(row: FollowRow): String {
    if (row.slotUtcMs == null) return "本次会话未听到"
    val parts = ArrayList<String>()
    row.grid?.let { parts += it }
    row.snr?.let { parts += String.format(Locale.US, "%+d dB", it) }
    row.df?.let { parts += "$it Hz" }
    row.slotUtcMs?.let { parts += QsoTime.isoTime(it) }
    row.distKm?.let { parts += String.format(Locale.US, "%.0f km", it) }
    return if (parts.isEmpty()) "本次会话未听到" else parts.joinToString(" · ")
}
