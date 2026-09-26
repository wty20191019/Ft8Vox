package com.example.ft8vox.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ft8vox.SessionService
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.data.settings.WaterfallHeight
import com.example.ft8vox.data.settings.WorkedStyle
import com.example.ft8vox.qso.DecodeFilter
import com.example.ft8vox.qso.DecodeFilterState
import com.example.ft8vox.qso.DecodeFilterTag
import com.example.ft8vox.qso.DecodeHighlight
import com.example.ft8vox.qso.HighlightPrefs
import com.example.ft8vox.qso.HighlightRole
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.qso.WorkedIndex
import com.example.ft8vox.ui.theme.VoxError
import java.util.Locale

/**
 * 操作页（new_ui.md §3）：水位图 → 筛选条 → 解码列表 → 发射控制。
 *
 * 顶栏（波段/模式/UTC）与底部状态条由 [MainShell] 统一提供，本页不再重复。
 */
@Composable
fun OperateScreen(
    viewModel: SessionViewModel,
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    onOpenMap: (String?) -> Unit,
    onOpenLog: () -> Unit,
    onOpenAutoProgram: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val status by viewModel.status.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val waterfall by viewModel.waterfall.collectAsState()
    val worked by viewModel.workedIndex.collectAsState()

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var pendingTx by remember { mutableStateOf<PendingTx?>(null) }
    var afterPermission by remember { mutableStateOf<PendingTx?>(null) }
    var queryOpen by rememberSaveable { mutableStateOf(false) }
    var detailFor by remember { mutableStateOf<DecodeRow?>(null) }
    // 发射抽屉当前目标（点选解码行 / 滑呼 / 详情「呼叫」设置）
    var targetCall by rememberSaveable { mutableStateOf<String?>(null) }

    fun execute(action: PendingTx) {
        when (action) {
            PendingTx.Cq -> viewModel.startCq()
            is PendingTx.Reply -> viewModel.answer(action.call, action.grid, action.df)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        val action = afterPermission
        afterPermission = null
        if (granted) {
            if (action == null) viewModel.start() else execute(action)
        }
    }

    fun request(action: PendingTx?) {
        if (permissionGranted) {
            if (action == null) viewModel.start() else execute(action)
        } else {
            afterPermission = action
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 进入操作页且已授权时自动开始接收（水位图不再提供 ▶ 按钮）
    LaunchedEffect(permissionGranted) {
        if (permissionGranted && !status.running) viewModel.start()
    }

    // 通知权限（Android 13+）：前台服务通知需要它才可见；拒绝时服务照常运行，只是不显示通知
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 首次运行时服务可能已先启动（通知被系统拦下），授权后补贴一次
        if (granted) SessionService.refresh(context)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FT8 消息", text))
    }

    // 过滤 + 高亮（纯逻辑，见 DecodeFilter / DecodeHighlight）
    val filter = DecodeFilterState(
        tags = settings.filterTags,
        query = settings.callFilter,
        ignoredCalls = settings.ignoredCalls,
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
            // 「已通联（隐藏）」时不再显示已通联行
            if (settings.workedStyle == WorkedStyle.HIDE && style.role == HighlightRole.WORKED) {
                return@mapNotNull null
            }
            DecodeRow(m, p, style)
        }
    }

    // ---- §3.3 自动翻到最新 ----
    // 解码列表为新→旧（最新在 index 0）；LazyColumn 默认按 key 锚定，前插新解码时视口会停在
    // 旧消息上，所以每批新解码到达时主动回到顶部，用户无需手动上滑。
    // 用户手动拖动（翻看历史）时暂停跟随，滑回顶部后自动恢复。
    val listState = rememberLazyListState()
    var followNewest by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followNewest = false
        }
    }
    LaunchedEffect(listState) {
        // 回到顶部即恢复跟随（只置 true，锚定导致的 index 漂移不会误关）
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

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {

        if (status.myCall.isEmpty()) {
            Text(
                "未设置呼号与网格，点此前往设置后再发射。",
                style = MaterialTheme.typography.labelSmall,
                color = VoxError,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp)
                    .clickable(onClick = onOpenSettings),
            )
        }

        // ---- §3.1 水位图（约 0.30 屏高）+ 顶部浮条 ----
        WaterfallBox(
            waterfall = waterfall,
            status = status,
            settings = settings,
            onMoveTxFreq = { viewModel.setTxFreq(it) },
            onLongPress = { hz ->
                val near = rows.minByOrNull { kotlin.math.abs(it.msg.df - hz) }
                if (near != null && (near.parsed.from != null || near.parsed.isCq)) detailFor = near
            },
        )

        FrequencyAxis(waterfall?.fMinHz, waterfall?.maxHz)

        // ---- §3.2 筛选 Chip 行 ----
        FilterChipRow(
            filter = filter,
            counts = counts,
            queryOpen = queryOpen,
            canClear = messages.isNotEmpty(),
            onClear = { viewModel.clearMessages() },
            onToggleSearch = { queryOpen = !queryOpen },
            onToggle = { viewModel.setFilterTags(filter.toggle(it).tags) },
        )
        if (queryOpen) {
            var query by remember { mutableStateOf(settings.callFilter) }
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.setCallFilter(it)
                },
                placeholder = { Text("呼号 / 前缀 / 网格（逗号分隔）", style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 3.dp))

        // ---- §3.3 解码列表 ----
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
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
                        .then(
                            if (!status.running) Modifier.clickable { request(null) } else Modifier,
                        ),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(rows, key = { it.msg.text + "@" + it.msg.slotUtcMs }) { row ->
                        val from = row.parsed.from
                        DecodeCard(
                            row = row,
                            myCall = status.myCall,
                            onClick = {
                                viewModel.selectTargetFreq(row.msg.df)
                                if (from != null && !from.equals(status.myCall, ignoreCase = true)) {
                                    targetCall = from
                                    viewModel.alignTxToTarget(row.msg.slotUtcMs)
                                }
                                detailFor = row
                            },
                            onDoubleClick = { onOpenMap(from) },
                            onSwipeTarget = {
                                if (from != null && !from.equals(status.myCall, ignoreCase = true)) {
                                    targetCall = from
                                    viewModel.selectTargetFreq(row.msg.df)
                                    viewModel.alignTxToTarget(row.msg.slotUtcMs)
                                }
                            },
                            onSwipeDelete = { viewModel.removeMessage(row.msg) },
                            onCopy = { copyToClipboard(row.msg.text) },
                            onIgnore = { from?.let { viewModel.ignoreCall(it) } },
                            workedStyle = settings.workedStyle,
                            endMarkMyCall = settings.endMarkMyCall,
                            endMarkActive = settings.endMarkActive,
                            slotMs = status.slotMs,
                            myGrid = status.myGrid,
                        )
                    }
                }
            }
        }

        // ---- §3.4 发射控制（发射抽屉：收起 56dp / 上拉展开） ----
        TxDrawer(
            status = status,
            settings = settings,
            messages = messages,
            targetCall = targetCall,
            onClearTarget = {
                targetCall = null
                viewModel.clearTargetSlot()
            },
            onStartCq = { pendingTx = PendingTx.Cq },
            onAnswer = { call, grid, df -> pendingTx = PendingTx.Reply(call, grid, df) },
            onSendNow = { viewModel.sendNow(it) },
            onSendOnce = { viewModel.sendOnce(it) },
            onStopTx = { viewModel.stopTransmit() },
            onTxEnabledChange = { viewModel.setTxEnabled(it) },
            onSameFreqChange = { viewModel.setSameFreqTx(it) },
            onOpenAutoProgram = onOpenAutoProgram,
            onMacrosChange = { viewModel.setMacros(it) },
            onEnqueue = { viewModel.enqueueTx(it) },
            onRemoveQueued = { viewModel.removeQueuedTx(it) },
            onMoveQueued = { from, to -> viewModel.moveQueuedTx(from, to) },
            onClearQueue = { viewModel.clearTxQueue() },
        )
    }

    pendingTx?.let { pending ->
        TxConfirmDialog(
            pending = pending,
            status = status,
            onConfirm = {
                pendingTx = null
                request(pending)
            },
            onDismiss = { pendingTx = null },
        )
    }

    detailFor?.let { row ->
        DecodeDetailSheet(
            row = row,
            myCall = status.myCall,
            myGrid = status.myGrid.ifEmpty { null },
            onCall = {
                val from = row.parsed.from
                detailFor = null
                if (from != null && !from.equals(status.myCall, ignoreCase = true)) {
                    targetCall = from
                    viewModel.alignTxToTarget(row.msg.slotUtcMs)
                    pendingTx = PendingTx.Reply(from, row.parsed.grid, row.msg.df)
                }
            },
            onOpenLog = {
                detailFor = null
                onOpenLog()
            },
            onDismiss = { detailFor = null },
        )
    }
}

/** 水位图高度档位 → 屏高比例（new_ui.md §3.1：约 0.30）。 */
fun WaterfallHeight.screenFraction(): Float = when (this) {
    WaterfallHeight.COMPACT -> 0.24f
    WaterfallHeight.NORMAL -> 0.30f
    WaterfallHeight.TALL -> 0.38f
}

/**
 * 水位图区块：Canvas + 顶部浮条（增益 / 噪抑 / 带宽 / 暂停）+ 参考电平文字 + TX 读数。
 */
@Composable
private fun WaterfallBox(
    waterfall: WaterfallFrame?,
    status: ReceiverStatus,
    settings: AppSettings,
    onMoveTxFreq: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val wfHeight: Dp = (screenHeight * settings.waterfallHeight.screenFraction()).coerceAtLeast(150.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(wfHeight)
            .background(Color.Black),
    ) {
        WaterfallView(
            frame = waterfall,
            selectedFreqHz = status.selectedFreqHz,
            slotParity = status.slotParity,
            onMoveTxFreq = onMoveTxFreq,
            modifier = Modifier.fillMaxSize(),
            txing = status.txing,
            onLongPress = onLongPress,
        )

        // 参考电平（右上）
        Text(
            "Ref -50~-10dB",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xCCFFFFFF),
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        )

        // 发射频率读数（右下；红线＝发射频率，拖动红线即可调整）
        Text(
            String.format(Locale.US, "TX %d Hz", status.selectedFreqHz),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xCCFFFFFF),
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        )

        // 时隙进度条（贴底）
        if (status.running) {
            LinearProgressIndicator(
                progress = { status.slotProgress },
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp),
            )
        }
    }
}

/** 频率轴刻度（左 / 中 / 右）。 */
@Composable
private fun FrequencyAxis(fMinHz: Float?, maxHz: Float?) {
    val a = fMinHz ?: 0f
    val b = maxHz ?: 0f
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for (v in listOf(a, (a + b) / 2f, b)) {
            Text(
                "${v.toInt()} Hz",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 筛选 Chip 行（横向滚动）：最左侧「清除解码信息」，其余 Chip 全部互斥、多选，附角标计数。 */
@Composable
private fun FilterChipRow(
    filter: DecodeFilterState,
    counts: Map<DecodeFilterTag, Int>,
    queryOpen: Boolean,
    canClear: Boolean,
    onClear: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggle: (DecodeFilterTag) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 最左：清除本会话已解析/已显示的信息（只清显示列表，不写日志、不影响自动程序）
        IconButton(onClick = onClear, enabled = canClear) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "清除解码信息",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canClear) 1f else 0.38f),
            )
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
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
            IconButton(onClick = onToggleSearch) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "呼号过滤",
                    tint = if (queryOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
