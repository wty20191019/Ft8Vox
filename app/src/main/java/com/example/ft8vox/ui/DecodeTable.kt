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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.grid.Geo
import com.example.ft8vox.qso.DecodeStyle
import com.example.ft8vox.qso.Dxcc
import com.example.ft8vox.qso.HighlightRole
import com.example.ft8vox.qso.ParsedMessage
import com.example.ft8vox.ui.theme.BarNewCall
import com.example.ft8vox.ui.theme.BarNewEntity
import com.example.ft8vox.ui.theme.BarNewGrid
import com.example.ft8vox.ui.theme.BarWorked
import com.example.ft8vox.ui.theme.HlCall
import com.example.ft8vox.ui.theme.HlCq
import com.example.ft8vox.ui.theme.HlDxcc
import com.example.ft8vox.ui.theme.HlGrid
import com.example.ft8vox.ui.theme.HlMyCall
import com.example.ft8vox.ui.theme.HlTx
import com.example.ft8vox.ui.theme.JtdxRow
import com.example.ft8vox.ui.theme.VoxError
import com.example.ft8vox.ui.theme.VoxRxGreen
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 解码表格（docs/Ft8Vox.md）：JTDX 风格的**紧凑表格**，
 * 列头 `时隙 / UTC / 分贝 / 时差 / 频率 / 信息`。
 *
 * 行手势（docs/Ft8Vox.md）：
 * 单击 = 设为目标并对频；双击 = 跳地图；长按 = 菜单；左滑 = 呼叫；右滑 = 删除。
 */

/** 解码行展示模型（报文 + 解析 + 高亮分类）。 */
data class DecodeRow(
    val msg: DecodeResult,
    val parsed: ParsedMessage,
    val style: DecodeStyle,
)

/** 整行底色的不透明度（深色板上把 JTDX 的亮色底压暗，保证浅色文字仍清晰）。 */
private const val HL_ALPHA = 0.42f

/**
 * 高亮类别 → **整行底色**（JTDX/WSJT-X 默认，docs/Ft8Vox.md）。
 *
 * 已通联 / 重复 / 普通不上底色（返回 null），用文字弱化表达。
 */
fun highlightRowColor(role: HighlightRole): Color? = when (role) {
    HighlightRole.TX -> HlTx
    HighlightRole.TO_ME -> HlMyCall
    HighlightRole.CQ -> HlCq
    HighlightRole.NEW_GRID -> HlGrid
    HighlightRole.NEW_ENTITY -> HlDxcc
    HighlightRole.NEW_CALL -> HlCall
    HighlightRole.WORKED -> null
    HighlightRole.DUPLICATE -> null
    HighlightRole.NORMAL -> null
}

/**
 * 「报文」列的文字颜色（docs/Ft8Vox.md）。
 *
 * - **与我有关**（发给我的报文，含网格应答 / 报告 / R报告 / 73 / RR73）→ **整列文字标红**，
 *   一眼看出哪几条是冲我来的（整行底色仍按「色卡」规则走，不受影响）。
 * - **已通联** → 红字 + 删除线（呈现方式固定，不再有设置开关）。
 * - 其余按行底色的明暗（重复行整体弱化）。
 */
fun decodeMessageColor(style: DecodeStyle, base: Color): Color = when {
    style.toMe -> ToMeRed
    style.worked -> BarWorked
    else -> base
}

/** 「与我有关」的报文文字红（比行底色更亮，浅底上也读得清）。 */
val ToMeRed = Color(0xFFFF5252)

/** 高亮类别 → 单色（供频谱页叠加的解码呼号文字用）。 */
fun highlightTextColor(role: HighlightRole): Color = when (role) {
    HighlightRole.TX -> HlTx
    HighlightRole.TO_ME -> HlMyCall
    HighlightRole.CQ -> HlCq
    HighlightRole.NEW_GRID -> HlGrid
    HighlightRole.NEW_ENTITY -> HlDxcc
    HighlightRole.NEW_CALL -> HlCall
    HighlightRole.WORKED -> Color(0xFF9AA0B5)
    HighlightRole.DUPLICATE -> Color(0xFF9AA0B5)
    HighlightRole.NORMAL -> Color(0xFFCDD6F4)
}

internal val SwipeCallGreen = Color(0xFF2E7D32)
internal val SwipeDeleteGray = Color(0xFF455A64)

// 表格列宽（竖屏窄：固定列尽量收，把宽度让给「报文」列）
private val COL_SLOT = 22.dp
private val COL_UTC = 54.dp
private val COL_SNR = 36.dp
private val COL_DT = 38.dp

/**
 * 「报文」列文本（docs/Ft8Vox.md）。
 *
 * 两行制里报文固定占**第一行**：一行放不下「呼号+网格+报告」时**逐级缩小字号**
 * （12sp → 9sp）塞进去，而不是省略号截断（长报文少见，缩一点比看不清好）。
 * 第二行的「声音频率 · 国家 · 距离」由 [DecodeTableRow] 单独画。
 */
@Composable
private fun InfoText(
    text: AnnotatedString,
    color: Color,
    decoration: TextDecoration?,
    modifier: Modifier = Modifier,
) {
    var fontSize by remember(text) { mutableStateOf(12) }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
        ),
        color = color,
        textDecoration = decoration,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && fontSize > 9) fontSize -= 1
        },
    )
}

/**
 * 解码表格主体（新→旧，最新在上）。
 *
 * 空列表由调用方处理空态文案（[decodeEmptyHint]）。
 */
@Composable
fun DecodeTable(
    rows: List<ActivityRow>,
    myCall: String,
    listState: LazyListState,
    rowHeightMin: Int = 20,
    followed: (String) -> Boolean,
    onRowClick: (DecodeRow) -> Unit,
    onRowDoubleClick: (DecodeRow) -> Unit,
    onCall: (DecodeRow) -> Unit,
    onReply: (DecodeRow) -> Unit,
    onDetail: (DecodeRow) -> Unit,
    onOpenLog: (DecodeRow) -> Unit,
    onSwipeDelete: (DecodeRow) -> Unit,
    onCopy: (DecodeRow) -> Unit,
    onToggleFollow: (DecodeRow) -> Unit,
    slotMs: Int,
    myGrid: String,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(rows) { row ->
            when (row) {
                is ActivityRow.Rx -> DecodeTableRow(
                    row = row.row,
                    myCall = myCall,
                    slotMs = slotMs,
                    minHeightDp = rowHeightMin,
                    myGrid = myGrid,
                    followed = row.row.parsed.from?.let(followed) ?: false,
                    onClick = { onRowClick(row.row) },
                    onDoubleClick = { onRowDoubleClick(row.row) },
                    onCall = { onCall(row.row) },
                    onReply = { onReply(row.row) },
                    onDetail = { onDetail(row.row) },
                    onOpenLog = { onOpenLog(row.row) },
                    onSwipeDelete = { onSwipeDelete(row.row) },
                    onCopy = { onCopy(row.row) },
                    onToggleFollow = { onToggleFollow(row.row) },
                )

                // 我方发射行（TX 行，§37）：只占一行、纯展示（无点击 / 无滑动手势）。
                is ActivityRow.Tx -> TxTableRow(
                    rec = row.rec,
                    slotMs = slotMs,
                    minHeightDp = rowHeightMin,
                )
            }
        }
    }
}

/**
 * 我方发射行（TX 行，docs/Ft8Vox.md）：`时隙 | TX | 报文`，**只占一行**。
 *
 * - 行底色＝ [HighlightRole.TX] 那一档（黄底，与「自己发的报文 / 正在发射的那条」一致）。
 * - 「TX」顶替解码行的 **UTC / SNR / dT** 三段（合并居中）——发射没有信噪比与时差。
 * - 被「停止发射」/ 换目标作废的那次在行尾标**「未发完」**。
 * - **纯展示**：不加 `combinedClickable` / `pointerInput`，避免左滑误呼叫自己、右滑误删自己。
 */
@Composable
private fun TxTableRow(rec: TxRecord, slotMs: Int, minHeightDp: Int) {
    val slotLabel = slotParityOf(rec.slotUtcMs, slotMs.toLong())?.toString() ?: "--"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeightDp.dp)
            .background(HlTx.copy(alpha = HL_ALPHA))
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            slotLabel,
            style = mono(MaterialTheme.typography.labelSmall),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(COL_SLOT),
        )
        Text(
            "TX",
            style = mono(MaterialTheme.typography.labelSmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(COL_UTC + COL_SNR + COL_DT),
        )
        Text(
            rec.text,
            style = mono(MaterialTheme.typography.labelMedium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        )
        if (rec.outcome == TxOutcome.ABORTED) {
            Text(
                "未发完",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DecodeTableRow(
    row: DecodeRow,
    myCall: String,
    slotMs: Int,
    minHeightDp: Int,
    myGrid: String,
    followed: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onCall: () -> Unit,
    onReply: () -> Unit,
    onDetail: () -> Unit,
    onOpenLog: () -> Unit,
    onSwipeDelete: () -> Unit,
    onCopy: () -> Unit,
    onToggleFollow: () -> Unit,
) {
    val msg = row.msg
    val style = row.style
    val from = row.parsed.from
    val callText = from ?: msg.text.substringBefore(' ')
    val hl = highlightRowColor(style.role)
    val textColor = MaterialTheme.colorScheme.onSurface
    val bg = hl?.copy(alpha = HL_ALPHA) ?: JtdxRow
    val alpha = if (style.role == HighlightRole.DUPLICATE) 0.6f else 1f
    // 已通联＝红字 + 删除线（固定，不再有「高亮/下划线/隐藏」三选一，§29）
    val workedDecoration = TextDecoration.LineThrough
    val slotLabel = slotParityOf(msg.slotUtcMs, slotMs.toLong())?.toString() ?: "--"
    // 报文列颜色（§29）：与我有关＝整体标红；已通联＝红字；其余按「色卡」明暗
    val msgBase = textColor.copy(alpha = alpha)
    val msgColor = decodeMessageColor(style, msgBase)
    val msgCallColor = if (style.toMe) msgColor else VoxError
    // 第二行：声音频率 · 国家 · 距离（单项之间只留一个空格，docs/Ft8Vox.md）
    val metaLine = remember(row, myGrid) {
        val entity = from?.let { Dxcc.resolve(it)?.name }
        val distKm = Geo.betweenGrids(myGrid, row.parsed.grid)?.first
        buildString {
            append("${msg.df}Hz")
            entity?.takeIf { it.isNotEmpty() }?.let { append(" $it") }
            distKm?.let { append(String.format(Locale.US, " %.0fkm", it)) }
        }
    }

    val density = LocalDensity.current
    val maxSwipe = with(density) { 140.dp.toPx() }
    val threshold = with(density) { 76.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }

    // 手势回调走 rememberUpdatedState 取**最新**：LazyColumn 按索引复用节点时，
    // `pointerInput(Unit)` 的协程不会重启，直接捕获的 `onCall` / `onSwipeDelete` 会停留在
    // 首次组合的那条 row 上 —— 真机现象：左滑最新那条，结果呼叫（或删除）了上一秒
    // 占同一位置的旧条目（解码列表每 15 s 前插一条，index 0 一直在换人）。
    val latestOnCall by rememberUpdatedState(onCall)
    val latestOnSwipeDelete by rememberUpdatedState(onSwipeDelete)

    Box(Modifier.fillMaxWidth()) {
        // 滑动背景提示（左移露出右侧「呼叫」，右移露出左侧「删除」）
        Row(Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (offsetX.value > 4f) SwipeDeleteGray else Color.Transparent),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (offsetX.value > 4f) {
                    Text("删除", color = Color.White, style = MaterialTheme.typography.labelSmall)
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
                    Text("呼叫", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeightDp.dp)
                .offset { IntOffset(offsetX.value.toInt(), 0) }
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
                                        latestOnSwipeDelete()
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
                .background(bg)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 两行：第一行＝时隙 / UTC / 信号 / dT / 报文；第二行＝声音频率 / 国家 / 距离
            // （第二行从行首开始，单项之间只留一个空格；表头已去掉，见 docs/Ft8Vox.md）
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 时隙（0/1）
                    Text(
                        slotLabel,
                        style = mono(labelSmallAlpha(alpha)),
                        color = textColor.copy(alpha = alpha),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(COL_SLOT),
                    )
                    // UTC
                    Text(
                        QsoTime.isoTime(msg.slotUtcMs),
                        style = mono(labelSmallAlpha(alpha)),
                        color = textColor.copy(alpha = alpha),
                        maxLines = 1,
                        modifier = Modifier.width(COL_UTC),
                    )
                    // 信号（dB）
                    Text(
                        String.format(Locale.US, "%+3d", msg.snr),
                        style = mono(labelSmallAlpha(alpha)),
                        color = if (msg.snr >= 0) VoxRxGreen else textColor.copy(alpha = alpha),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.width(COL_SNR),
                    )
                    // 时差
                    Text(
                        String.format(Locale.US, "%+.1f", msg.dt),
                        style = mono(labelSmallAlpha(alpha)),
                        color = textColor.copy(alpha = alpha),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.width(COL_DT),
                    )
                    // 报文（一行；放不下则逐级缩小字号）
                    // 与我有关 → 整列标红（我的呼号同色加粗）；已通联 → 红字 + 删除线（§29）
                    InfoText(
                        text = annotatedMessage(msg.text, myCall, msgCallColor),
                        color = msgColor,
                        decoration = if (style.worked) workedDecoration else null,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                    if (style.toMe) Marker(VoxError)
                    if (style.current) Marker(MaterialTheme.colorScheme.primary)
                    if (style.newGrid) Marker(BarNewGrid)
                    if (style.hasNewEntityMark) Marker(BarNewEntity)
                    if (followed) Marker(BarNewCall)
                }
                // 声音频率 · 国家 · 距离
                Text(
                    metaLine,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = textColor.copy(alpha = 0.6f * alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("呼叫 $callText") },
                enabled = from != null,
                onClick = {
                    menuOpen = false
                    onCall()
                },
            )
            DropdownMenuItem(
                text = { Text("回复 $callText") },
                enabled = from != null,
                onClick = {
                    menuOpen = false
                    onReply()
                },
            )
            DropdownMenuItem(
                text = { Text("详情…") },
                enabled = from != null,
                onClick = {
                    menuOpen = false
                    onDetail()
                },
            )
            DropdownMenuItem(
                text = { Text("查看日志") },
                enabled = from != null,
                onClick = {
                    menuOpen = false
                    onOpenLog()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("复制消息") },
                onClick = {
                    menuOpen = false
                    onCopy()
                },
            )
            DropdownMenuItem(
                text = { Text(if (followed) "取消跟踪 $callText" else "跟踪 $callText") },
                enabled = from != null,
                onClick = {
                    menuOpen = false
                    onToggleFollow()
                },
            )
        }
    }
}

/** 表格单元格等宽小字。 */
@Composable
private fun mono(style: androidx.compose.ui.text.TextStyle) =
    style.copy(fontFamily = FontFamily.Monospace)

@Composable
private fun labelSmallAlpha(alpha: Float) =
    MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
    )

/** 小圆点标记。 */
@Composable
private fun Marker(color: Color) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * 高亮报文中的「我的呼号」为 [myCallColor]（默认红，docs/Ft8Vox.md）。
 *
 * 「与我有关」的行整列都是红的，此时把呼号也用同一色（只保留加粗）—— 免得一条红报文里
 * 嵌着另一种红。
 */
fun annotatedMessage(text: String, myCall: String, myCallColor: Color = VoxError): AnnotatedString {
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
            withStyle(SpanStyle(color = myCallColor, fontWeight = FontWeight.Bold)) {
                append(text.substring(idx, idx + my.length))
            }
            i = idx + my.length
        }
    }
}

/**
 * 解码详情对话框（原底部半屏改为 JTDX 式居中对话框）：距离、方位、网格、强度、快捷按钮。
 */
@Composable
fun DecodeDetailDialog(
    msg: DecodeResult,
    parsed: ParsedMessage,
    myCall: String,
    myGrid: String?,
    onCall: () -> Unit,
    onOpenLog: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dist = Geo.betweenGrids(myGrid, parsed.grid)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(parsed.from ?: "未知呼号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                DetailLine("呼号", myCall)
            }
        },
        confirmButton = { TextButton(onClick = onCall, enabled = parsed.from != null) { Text("呼叫") } },
        dismissButton = { TextButton(onClick = onOpenLog, enabled = parsed.from != null) { Text("日志") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
