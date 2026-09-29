package com.example.ft8vox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.DragInteraction
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.data.settings.WorkedStyle
import com.example.ft8vox.qso.DecodeFilter
import com.example.ft8vox.qso.DecodeFilterState
import com.example.ft8vox.qso.DecodeFilterTag
import com.example.ft8vox.qso.DecodeHighlight
import com.example.ft8vox.qso.HighlightPrefs
import com.example.ft8vox.qso.HighlightRole
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.ui.theme.JtdxPanelHi
import com.example.ft8vox.ui.theme.VoxError
import com.example.ft8vox.ui.theme.VoxTxRed

/**
 * 操作页（docs/UI-JTDX.md §2）：**信息头 + 过滤行 + 左表格 + 右控制列 + 底发射区**。
 *
 * 信息头/控制行/状态条由 [MainShell] 统一提供，本页只负责「表格 + 控制列 + 发射区」。
 */
@Composable
fun OperateScreen(
    viewModel: SessionViewModel,
    settings: AppSettings,
    hasPermission: Boolean,
    request: (action: () -> Unit) -> Unit,
    onRequestStart: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: (String?) -> Unit,
    onOpenLog: (String?) -> Unit,
    onOpenAutoProgram: () -> Unit,
    filterOpen: Boolean,
    onFilterOpenChange: (Boolean) -> Unit,
    followOpen: Boolean,
    onFollowOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val status by viewModel.status.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val worked by viewModel.workedIndex.collectAsState()

    var detailFor by remember { mutableStateOf<DecodeRow?>(null) }
    // 当前发射目标（点选解码行 / 左滑呼叫 / 详情「呼叫」设置）
    var targetCall by rememberSaveable { mutableStateOf<String?>(null) }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FT8 消息", text))
    }

    // 过滤 + 高亮（纯逻辑，见 DecodeFilter / DecodeHighlight）
    val filter = DecodeFilterState(
        tags = settings.filterTags,
        query = settings.callFilter,
        ignoredCalls = settings.ignoredCalls,
        followedCalls = settings.followCalls,
    )
    val counts = remember(messages, worked, status.myCall, settings.ignoredCalls) {
        DecodeFilter.counts(messages, worked, status.myCall, settings.ignoredCalls)
    }
    val duplicateKeys = remember(messages) { DecodeHighlight.duplicateRowKeys(messages) }
    val highlightPrefs = HighlightPrefs(
        newCall = settings.highlightNewCall,
        newGrid = settings.highlightNewGrid,
        newEntity = settings.highlightNewEntity,
        newItu = settings.highlightNewItu,
        newCqZone = settings.highlightNewCqZone,
        newPrefix = settings.highlightNewPrefix,
    )
    val rows = remember(
        messages, filter, worked, status.myCall, status.qso.theirCall,
        status.txing, status.lastTxText, duplicateKeys, highlightPrefs, settings.workedStyle,
    ) {
        val txText = if (status.txing) status.lastTxText else null
        messages.mapNotNull { m ->
            val p = MessageParser.parse(m.text)
            if (!DecodeFilter.matches(p, filter, worked, status.myCall)) return@mapNotNull null
            val dup = DecodeHighlight.rowKey(m.text, m.slotUtcMs) in duplicateKeys
            val style = DecodeHighlight.classify(
                p, worked, status.qso.theirCall, status.myCall, dup, txText, highlightPrefs,
            )
            if (settings.workedStyle == WorkedStyle.HIDE && style.role == HighlightRole.WORKED) {
                return@mapNotNull null
            }
            DecodeRow(m, p, style)
        }
    }

    // ---- 自动翻到最新（docs/UI.md §3.3）----
    val listState = rememberLazyListState()
    var followNewest by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followNewest = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            if (idx == 0) followNewest = true
        }
    }
    val newestKey = rows.firstOrNull()?.let { it.msg.text + "@" + it.msg.slotUtcMs }
    LaunchedEffect(newestKey) {
        if (newestKey != null && followNewest && !listState.isScrollInProgress) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(4.dp)) {
        // ---- 表格上方只留一行：过滤 / 清除 / 关注 / 时间告警 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            JtdxButton(
                text = "过滤…",
                onClick = { onFilterOpenChange(true) },
                active = filterOpen,
            )
            JtdxButton(
                text = "清除",
                onClick = { viewModel.clearMessages() },
                enabled = messages.isNotEmpty(),
            )
            JtdxButton(
                text = "关注 ${settings.followCalls.size}",
                onClick = { onFollowOpenChange(true) },
                active = followOpen,
            )
            val warn = timeSyncWarning(messages.firstOrNull()?.dt)
            if (warn != null) {
                Text(warn, style = MaterialTheme.typography.labelSmall, color = VoxError)
            } else {
                JtdxCaption("时间同步")
            }
            Spacer(Modifier.weight(1f))
            JtdxCaption(filterSummary(filter, counts))
            if (settings.callFilter.isNotBlank()) {
                JtdxButton(
                    text = "搜索：${settings.callFilter}",
                    onClick = { viewModel.setCallFilter("") },
                )
            }
        }

        DecodeTableHeader()

        // ---- 左表格 + 右控制列 ----
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                if (rows.isEmpty()) {
                    Text(
                        decodeEmptyHint(status, messages.size, filter.isEmptySelection),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .then(if (!status.running) Modifier.clickable { onRequestStart() } else Modifier),
                    )
                } else {
                    DecodeTable(
                        rows = rows,
                        myCall = status.myCall,
                        listState = listState,
                        followed = { it in settings.followCalls },
                        onRowClick = { row ->
                            viewModel.selectTargetFreq(row.msg.df)
                            val from = row.parsed.from
                            if (from != null && !from.equals(status.myCall, ignoreCase = true)) {
                                targetCall = from
                                viewModel.alignTxToTarget(row.msg.slotUtcMs)
                            }
                        },
                        onRowDoubleClick = { row -> onOpenMap(row.parsed.from) },
                        onCall = { row ->
                            val from = row.parsed.from
                            if (from != null && !from.equals(status.myCall, ignoreCase = true)) {
                                targetCall = from
                                viewModel.selectTargetFreq(row.msg.df)
                                viewModel.alignTxToTarget(row.msg.slotUtcMs)
                                request { viewModel.answer(from, row.parsed.grid, row.msg.df) }
                            }
                        },
                        onReply = { row ->
                            val from = row.parsed.from
                            if (from != null && !from.equals(status.myCall, ignoreCase = true)) {
                                targetCall = from
                                viewModel.selectTargetFreq(row.msg.df)
                                viewModel.alignTxToTarget(row.msg.slotUtcMs)
                                request { viewModel.replyTo(row.msg) }
                            }
                        },
                        onDetail = { detailFor = it },
                        onOpenLog = { onOpenLog(it.parsed.from) },
                        onSwipeDelete = { viewModel.removeMessage(it.msg) },
                        onCopy = { copyToClipboard(it.msg.text) },
                        onIgnore = { it.parsed.from?.let { c -> viewModel.ignoreCall(c) } },
                        onToggleFollow = { it.parsed.from?.let { c -> viewModel.toggleFollow(c) } },
                        workedStyle = settings.workedStyle,
                        endMarkMyCall = settings.endMarkMyCall,
                        endMarkActive = settings.endMarkActive,
                        slotMs = status.slotMs,
                        myGrid = status.myGrid,
                    )
                }
            }

            // ---- 右控制列（JTDX 那一竖排按钮；只放本项目已有功能）----
            Column(
                modifier = Modifier
                    .width(92.dp)
                    .fillMaxHeight()
                    .padding(start = 4.dp)
                    .background(JtdxPanelHi),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(2.dp))
                JtdxButton(
                    text = if (status.running) "监听 ▣" else "监听",
                    onClick = { if (status.running) viewModel.stop() else onRequestStart() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    active = status.running,
                )
                JtdxButton(
                    text = "停止发射",
                    onClick = { viewModel.stopTransmit() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    enabled = status.txing || status.txArmed,
                    accent = VoxTxRed,
                    active = status.txing,
                )
                JtdxButton(
                    text = "清除解码",
                    onClick = { viewModel.clearMessages() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    enabled = messages.isNotEmpty(),
                )
                JtdxButton(
                    text = "关注列表",
                    onClick = { onFollowOpenChange(true) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    active = followOpen,
                )
                JtdxButton(
                    text = "自动程序",
                    onClick = onOpenAutoProgram,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    active = status.txEnabled,
                )
                if (status.myCall.isEmpty()) {
                    JtdxButton(
                        text = "去设置",
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        accent = VoxError,
                    )
                }
                Spacer(Modifier.weight(1f))
                JtdxCaption("DX 目标", modifier = Modifier.padding(horizontal = 4.dp))
                JtdxReadOnly(
                    value = targetCall ?: status.qso.theirCall.orEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(bottom = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(3.dp))

        // ---- 底发射区（取代发射抽屉）----
        TxPanel(
            status = status,
            settings = settings,
            messages = messages,
            targetCall = targetCall,
            onSendOnce = { request { viewModel.sendOnce(it) } },
            onStartCq = { request { viewModel.startCq() } },
            onStopTx = { viewModel.stopTransmit() },
            onTxEnabledChange = { viewModel.setTxEnabled(it) },
            onCqPrefixIndex = { viewModel.setCqPrefixIndex(it) },
            onOpenAutoProgram = onOpenAutoProgram,
        )
    }

    if (filterOpen) {
        DecodeFilterDialog(
            filter = filter,
            counts = counts,
            callFilter = settings.callFilter,
            onCallFilter = { viewModel.setCallFilter(it) },
            onToggle = { viewModel.setFilterTags(filter.toggle(it).tags) },
            onClearSearch = { viewModel.setCallFilter("") },
            onDismiss = { onFilterOpenChange(false) },
        )
    }

    if (followOpen) {
        AlertDialog(
            onDismissRequest = { onFollowOpenChange(false) },
            title = { Text("关注呼号列表") },
            text = {
                Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 320.dp)) {
                    FollowListPanel(
                        follows = settings.followCalls,
                        messages = messages,
                        myGrid = status.myGrid,
                        autoFollowed = settings.autoFollowOrder.toSet(),
                        onCall = { call, grid, df ->
                            targetCall = call
                            onFollowOpenChange(false)
                            request { viewModel.answer(call, grid, df) }
                        },
                        onUnfollow = { viewModel.unfollowCall(it) },
                        onClose = { onFollowOpenChange(false) },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onFollowOpenChange(false) }) { Text("关闭") } },
        )
    }

    detailFor?.let { row ->
        DecodeDetailDialog(
            msg = row.msg,
            parsed = row.parsed,
            myCall = status.myCall,
            myGrid = status.myGrid.ifEmpty { null },
            onCall = {
                val from = row.parsed.from
                detailFor = null
                if (from != null && !from.equals(status.myCall, ignoreCase = true)) {
                    targetCall = from
                    viewModel.alignTxToTarget(row.msg.slotUtcMs)
                    request { viewModel.answer(from, row.parsed.grid, row.msg.df) }
                }
            },
            onOpenLog = {
                val from = row.parsed.from
                detailFor = null
                onOpenLog(from)
            },
            onDismiss = { detailFor = null },
        )
    }
}

/**
 * 解码列表空态提示文案（纯函数，便于 JVM 单测）。
 *
 * 四种情况：未选筛选项 / 未开始接收 / 已接收但无解码 / 有解码但被过滤。
 */
fun decodeEmptyHint(
    status: ReceiverStatus,
    decodedTotal: Int,
    filterEmptySelection: Boolean = false,
): String = when {
    filterEmptySelection -> "请至少开启一个筛选"
    !status.running -> "未开始接收：点此授权并开始接收"
    decodedTotal == 0 -> "等待解码…\n（未接天线 / 无音频输入时不会出现解码）"
    else -> "没有符合当前筛选条件的解码消息"
}

/** 当前筛选摘要（顶部那一行的右侧小字）。 */
private fun filterSummary(filter: DecodeFilterState, counts: Map<DecodeFilterTag, Int>): String {
    val tags = DecodeFilterTag.entries.filter { filter.isSelected(it) }
    if (tags.isEmpty()) return "筛选：无（列表为空）"
    return "筛选：" + tags.joinToString(" / ") { "${it.label} ${counts[it] ?: 0}" }
}

/** 过滤弹窗（docs/UI-JTDX.md §1）：六个 Chip + 呼号/前缀/网格搜索。 */
@Composable
private fun DecodeFilterDialog(
    filter: DecodeFilterState,
    counts: Map<DecodeFilterTag, Int>,
    callFilter: String,
    onCallFilter: (String) -> Unit,
    onToggle: (DecodeFilterTag) -> Unit,
    onClearSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("过滤解码列表") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (tag in DecodeFilterTag.entries) {
                        FilterChip(
                            selected = filter.isSelected(tag),
                            onClick = { onToggle(tag) },
                            label = {
                                Text(
                                    "${tag.label} ${counts[tag] ?: 0}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                JtdxCaption("全部 之外为多选并集；一个都没选时列表为空", modifier = Modifier.padding(top = 6.dp))
                JtdxTextField(
                    value = callFilter,
                    onValueChange = onCallFilter,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = "呼号 / 前缀 / 网格（逗号分隔）",
                )
                if (callFilter.isNotBlank()) {
                    TextButton(onClick = onClearSearch) { Text("清除搜索") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
