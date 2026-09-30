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
import androidx.compose.material3.Button
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
import com.example.ft8vox.qso.DecodeFilter
import com.example.ft8vox.qso.DecodeFilterState
import com.example.ft8vox.qso.DecodeHighlight
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.ui.theme.JtdxPanelHi
import com.example.ft8vox.ui.theme.VoxError
import com.example.ft8vox.ui.theme.VoxTxRed

/**
 * 操作页（docs/UI-MOBILE.md §3）：**控制行 + 解码表 + 常驻发射区**。
 *
 * 信息头/底部导航/状态条由 [MainShell] 统一提供，本页只负责「控制行 + 表格 + 发射区」。
 * 第五轮起无筛选 / 搜索（§20），解码表永远显示全部解码。
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
    followOpen: Boolean,
    onFollowOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val status by viewModel.status.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val txRecords by viewModel.txRecords.collectAsState()
    val worked by viewModel.workedIndex.collectAsState()

    var detailFor by remember { mutableStateOf<DecodeRow?>(null) }
    // 当前发射目标（点选解码行 / 左滑呼叫 / 详情「呼叫」设置）
    var targetCall by rememberSaveable { mutableStateOf<String?>(null) }
    // 「跟踪列表 → 全部清除」的二次确认
    var clearFollowConfirm by remember { mutableStateOf(false) }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FT8 消息", text))
    }

    // 高亮 + 忽略名单（docs/UI-MOBILE.md §20：操作页不再做筛选 / 搜索，解码表永远显示全部）
    val filter = DecodeFilterState(
        ignoredCalls = settings.ignoredCalls,
        followedCalls = settings.followCalls,
    )
    val duplicateKeys = remember(messages) { DecodeHighlight.duplicateRowKeys(messages) }
    // 接收解码行
    val rxRows = remember(
        messages, filter, worked, status.myCall, status.qso.theirCall,
        status.txing, status.lastTxText, duplicateKeys,
    ) {
        val txText = if (status.txing) status.lastTxText else null
        messages.mapNotNull { m ->
            val p = MessageParser.parse(m.text)
            if (!DecodeFilter.matches(p, filter, worked, status.myCall)) return@mapNotNull null
            val dup = DecodeHighlight.rowKey(m.text, m.slotUtcMs) in duplicateKeys
            val style = DecodeHighlight.classify(
                p, worked, status.qso.theirCall, status.myCall, dup, txText,
            )
            DecodeRow(m, p, style)
        }
    }
    // 与「我方发射行」按同一时间轴混排（docs/UI-MOBILE.md §37）；两类共用一个 200 条上限。
    val rows = remember(rxRows, txRecords) { mergeActivity(rxRows, txRecords) }

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
    val newestKey = rows.firstOrNull()?.key
    LaunchedEffect(newestKey) {
        if (newestKey != null && followNewest && !listState.isScrollInProgress) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(4.dp)) {
        // ---- 表格上方只留一行：监听 / 清除 / 跟踪 / 时间告警 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            JtdxButton(
                text = if (status.running) "监听 ▣" else "监听",
                onClick = { if (status.running) viewModel.stop() else onRequestStart() },
                active = status.running,
            )
            JtdxButton(
                text = "清除",
                onClick = { viewModel.clearMessages() },
                enabled = messages.isNotEmpty() || txRecords.isNotEmpty(),
            )
            JtdxButton(
                text = "跟踪 ${settings.followCalls.size}",
                onClick = { onFollowOpenChange(true) },
                active = followOpen,
            )
            val warn = timeSyncWarning(messages.firstOrNull()?.dt)
            if (warn != null) {
                Text(warn, style = MaterialTheme.typography.labelSmall, color = VoxError)
            } else {
                JtdxCaption("时间同步")
            }
        }

        // 表头已去掉（两行制里含义自明，省一行高度，docs/UI-MOBILE.md §14）

        // ---- 解码表格（竖屏整宽）----
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (rows.isEmpty()) {
                    Text(
                        decodeEmptyHint(status, messages.size),
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
                        onToggleFollow = { it.parsed.from?.let { c -> viewModel.toggleFollow(c) } },
                        slotMs = status.slotMs,
                        myGrid = status.myGrid,
                    )
                }
            }
        }

        Spacer(Modifier.height(3.dp))

        // ---- 底发射区（竖屏常驻，docs/UI-MOBILE.md §3.4）----
        TxPanel(
            status = status,
            settings = settings,
            messages = messages,
            targetCall = targetCall,
            onSendOnce = { request { viewModel.sendOnce(it) } },
            onStartCq = { request { viewModel.startCq() } },
            onStopTx = { viewModel.stopTransmit() },
            onCqPrefix = { prefixes, index -> viewModel.setCqPrefix(prefixes, index) },
            onOpenAutoProgram = onOpenAutoProgram,
        )
    }

    if (followOpen) {
        AlertDialog(
            onDismissRequest = { onFollowOpenChange(false) },
            title = { Text("跟踪 CQ 列表") },
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
                        onClearAll = { clearFollowConfirm = true },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onFollowOpenChange(false) }) { Text("关闭") } },
        )
    }

    if (clearFollowConfirm) {
        AlertDialog(
            onDismissRequest = { clearFollowConfirm = false },
            title = { Text("清空跟踪名单") },
            text = { Text("将移除全部 ${settings.followCalls.size} 个跟踪呼号，不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        clearFollowConfirm = false
                        viewModel.clearFollowCalls()
                    },
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { clearFollowConfirm = false }) { Text("取消") }
            },
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
 * `filterEmptySelection` 自 §20 起操作页已不再传（筛选弹窗删除），保留参数与分支仅为兼容既有单测。
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
