package com.example.ft8vox.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.settings.WorkedStyle
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.grid.Geo
import com.example.ft8vox.qso.DecodeStyle
import com.example.ft8vox.qso.HighlightRole
import com.example.ft8vox.qso.ParsedMessage
import com.example.ft8vox.ui.theme.BarCq
import com.example.ft8vox.ui.theme.BarDuplicate
import com.example.ft8vox.ui.theme.BarNewCall
import com.example.ft8vox.ui.theme.BarNewDecode
import com.example.ft8vox.ui.theme.BarNewEntity
import com.example.ft8vox.ui.theme.BarNewGrid
import com.example.ft8vox.ui.theme.BarToMe
import com.example.ft8vox.ui.theme.BarTx
import com.example.ft8vox.ui.theme.BarWorked
import com.example.ft8vox.ui.theme.VoxError
import com.example.ft8vox.ui.theme.VoxRxGreen
import java.util.Locale
import kotlinx.coroutines.launch

/** 解码行展示模型（报文 + 解析 + 高亮分类）。 */
data class DecodeRow(
    val msg: DecodeResult,
    val parsed: ParsedMessage,
    val style: DecodeStyle,
)

/** 高亮类别 → 左侧色条颜色（new_ui.md §3.3）。 */
fun barColor(role: HighlightRole): Color = when (role) {
    HighlightRole.TX -> BarTx
    HighlightRole.TO_ME -> BarToMe
    HighlightRole.CQ -> BarCq
    HighlightRole.WORKED -> BarWorked
    HighlightRole.DUPLICATE -> BarDuplicate
    HighlightRole.NEW_GRID -> BarNewGrid
    HighlightRole.NEW_ENTITY -> BarNewEntity
    HighlightRole.NEW_CALL -> BarNewCall
    HighlightRole.NORMAL -> BarNewDecode
}

private val SwipeCallGreen = Color(0xFF2E7D32)
private val SwipeIgnoreGray = Color(0xFF455A64)

/**
 * 解码卡片（new_ui.md §3.3）：左侧色条 + 呼号/网格/信噪比/时间 + 报文。
 *
 * 手势：单击 → 详情；双击 → 地图；长按 → 菜单；左滑 → 呼叫；右滑 → 忽略。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DecodeCard(
    row: DecodeRow,
    myCall: String,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onSwipeCall: () -> Unit,
    onSwipeIgnore: () -> Unit,
    onCopy: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
    workedStyle: WorkedStyle = WorkedStyle.STRIKE,
    endMarkMyCall: Boolean = true,
    endMarkActive: Boolean = true,
) {
    val msg = row.msg
    val style = row.style
    val from = row.parsed.from
    val callText = from ?: msg.text.substringBefore(' ')
    val bar = barColor(style.role)
    val textColor = if (style.role == HighlightRole.TX) Color.Black else MaterialTheme.colorScheme.onSurface
    val cardBg = if (style.role == HighlightRole.TX) BarTx else MaterialTheme.colorScheme.surface
    val alpha = if (style.role == HighlightRole.DUPLICATE) 0.6f else 1f
    val workedDecoration = when (workedStyle) {
        WorkedStyle.STRIKE -> TextDecoration.LineThrough
        WorkedStyle.UNDERLINE -> TextDecoration.Underline
        WorkedStyle.HIDE -> null
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxSwipe = with(density) { 140.dp.toPx() }
    val threshold = with(density) { 76.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier.fillMaxWidth()) {
        // 滑动背景提示（内容左移露出右侧「呼叫」，右移露出左侧「忽略」）
        Row(Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (offsetX.value > 4f) SwipeIgnoreGray else Color.Transparent),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (offsetX.value > 4f) {
                    Text("忽略", color = Color.White, style = MaterialTheme.typography.labelMedium)
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
                .offsetX(offsetX.value)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                when {
                                    offsetX.value <= -threshold -> {
                                        onSwipeCall()
                                        offsetX.animateTo(0f)
                                    }
                                    offsetX.value >= threshold -> {
                                        onSwipeIgnore()
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
                .combinedClickable(
                    onClick = onClick,
                    onDoubleClick = onDoubleClick,
                    onLongClick = { menuOpen = true },
                )
                .background(cardBg)
                .drawBehind {
                    drawRect(color = bar, size = Size(4.dp.toPx(), size.height))
                }
                .padding(start = 9.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        callText,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (style.toMe) VoxError else textColor,
                        maxLines = 1,
                    )
                    if (row.parsed.grid != null) {
                        Text(
                            "  ${row.parsed.grid}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        String.format(Locale.US, "  %+3d dB", msg.snr),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (msg.snr >= 0) VoxRxGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(Modifier.weight(1f))
                    Text(
                        QsoTime.isoTime(msg.slotUtcMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        annotatedMessage(msg.text, myCall),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = textColor.copy(alpha = alpha),
                        textDecoration = if (style.worked) workedDecoration else null,
                        maxLines = 1,
                    )
                    Box(Modifier.weight(1f))
                    // 次级标记：与我有关 ▎ / 正通联 ▎ / 新网格 ● / 新实体 ●
                    if (style.toMe && endMarkMyCall) Marker(VoxError)
                    if (style.current && endMarkActive) Marker(MaterialTheme.colorScheme.primary)
                    if (style.newGrid) Marker(BarNewGrid)
                    if (style.newPrefix) Marker(BarNewEntity)
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("复制消息") },
                onClick = {
                    menuOpen = false
                    onCopy()
                },
            )
            DropdownMenuItem(
                text = { Text("加宏（U3）") },
                enabled = false,
                onClick = {},
            )
            DropdownMenuItem(
                text = { Text("忽略 $callText") },
                onClick = {
                    menuOpen = false
                    onIgnore()
                },
            )
        }
    }
}

/** 用 [Modifier.offset] 实现滑动位移（避免每帧重组）。 */
private fun Modifier.offsetX(x: Float): Modifier =
    this.offset { IntOffset(x.toInt(), 0) }

/** 小圆点标记。 */
@Composable
private fun Marker(color: Color) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(7.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 高亮报文中的「我的呼号」为红字。 */
fun annotatedMessage(text: String, myCall: String): AnnotatedString {
    val my = myCall.trim().uppercase()
    if (my.isEmpty()) return AnnotatedString(text)
    val up = text.uppercase()
    if (!up.contains(my)) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val idx = up.indexOf(my, i)
            if (idx < 0) {
                append(text.substring(i))
                break
            }
            append(text.substring(i, idx))
            withStyle(SpanStyle(color = VoxError, fontWeight = FontWeight.Bold)) {
                append(text.substring(idx, idx + my.length))
            }
            i = idx + my.length
        }
    }
}

/**
 * 解码详情半屏（new_ui.md §3.3）：距离、方位、网格、强度、快捷按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecodeDetailSheet(
    row: DecodeRow,
    myCall: String,
    myGrid: String?,
    onCall: () -> Unit,
    onOpenLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    val msg = row.msg
    val parsed = row.parsed
    val dist = Geo.betweenGrids(myGrid, parsed.grid)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                parsed.from ?: "未知呼号",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(msg.text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DetailLine("网格", parsed.grid ?: "--")
            DetailLine("类型", parsed.kind)
            DetailLine("信噪比", String.format(Locale.US, "%+d dB", msg.snr))
            DetailLine("时间偏差", String.format(Locale.US, "%+.1f s", msg.dt))
            DetailLine("音频频率", "${msg.df} Hz")
            DetailLine("时隙时间", QsoTime.isoDateTime(msg.slotUtcMs))
            if (dist != null) {
                DetailLine(
                    "距离 / 方位",
                    String.format(Locale.US, "%.0f km / %.0f° %s", dist.first, dist.second, Geo.compass(dist.second)),
                )
            } else {
                DetailLine("距离 / 方位", "网格未知")
            }
            DetailLine("强度曲线", "U4 地图页接入")

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCall, enabled = parsed.from != null) { Text("呼叫") }
                OutlinedButton(onClick = onOpenLog, enabled = parsed.from != null) { Text("日志") }
                OutlinedButton(onClick = {}, enabled = false) { Text("备注") }
            }
            Text(
                "呼号 $myCall",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.6f))
    }
}
