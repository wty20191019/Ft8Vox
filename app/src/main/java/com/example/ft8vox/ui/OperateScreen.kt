package com.example.ft8vox.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.data.settings.CallFirstMode
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.qso.DecodeFilter
import com.example.ft8vox.qso.DecodeFilterState
import com.example.ft8vox.qso.DecodeHighlight
import com.example.ft8vox.qso.DecodeStyle
import com.example.ft8vox.qso.HighlightRole
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.qso.ParsedMessage
import com.example.ft8vox.qso.WorkedIndex
import java.util.Locale

// ---- 高亮语义色（与 docs/UI-DESIGN.md 第 4.1 节一致） ----
private val HighlightCurrent = Color(0xFFFFAB00)
private val HighlightToMe = Color(0xFF00B0FF)
private val HighlightNewGrid = Color(0xFF00C853)
private val HighlightNewPrefix = Color(0xFFD50000)

/**
 * 操作页：JTDX / FT8CN 风格的分区布局。
 *
 * 自上而下：台站身份/波段 → 时隙进度 → 瀑布 → 频率轴 → RX/TX 面板 → 过滤条 → 解码列表 → QSO 控制面板。
 */
@Composable
fun OperateScreen(
    viewModel: SessionViewModel,
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val status by viewModel.status.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val waterfall by viewModel.waterfall.collectAsState()
    val recentQso by viewModel.recentQso.collectAsState(initial = emptyList())
    val worked by viewModel.workedIndex.collectAsState()

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var pendingTx by remember { mutableStateOf<PendingTx?>(null) }
    var afterPermission by remember { mutableStateOf<PendingTx?>(null) }
    var manualFor by remember { mutableStateOf<DecodeResult?>(null) }
    var freeTextFor by remember { mutableStateOf<String?>(null) }
    var confirmCallFirst by remember { mutableStateOf(false) }
    var controlExpanded by rememberSaveable { mutableStateOf(false) }

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

    // 过滤 + 高亮（纯逻辑，见 DecodeFilter / DecodeHighlight）
    val filter = DecodeFilterState(
        cqOnly = settings.cqOnly,
        excludeWorked = settings.excludeWorked,
        query = settings.callFilter,
    )
    val rows = remember(
        messages, filter, worked, status.myCall, status.qso.theirCall,
    ) {
        messages.mapNotNull { m ->
            val p = MessageParser.parse(m.text)
            if (!DecodeFilter.matches(p, filter, worked, status.myCall)) return@mapNotNull null
            DecodeRowModel(m, p, DecodeHighlight.classify(p, worked, status.qso.theirCall, status.myCall))
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {

        HeaderRow(
            status = status,
            onOpenSettings = onOpenSettings,
            onToggleRunning = { if (status.running) viewModel.stop() else request(null) },
            onBandChange = { viewModel.setBand(it) },
        )

        StatusBar(status)

        // ---- 瀑布 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(settings.waterfallHeight.heightDp.dp)
                .background(Color.Black),
        ) {
            WaterfallView(
                frame = waterfall,
                selectedFreqHz = status.selectedFreqHz,
                slotParity = status.slotParity,
                onSelectFrequency = { viewModel.selectFrequency(it) },
                modifier = Modifier.fillMaxSize(),
                rxFreqHz = status.rxFreqHz,
                txing = status.txing,
            )
        }
        FrequencyAxis(waterfall?.fMinHz, waterfall?.maxHz)

        FreqPanel(
            rxFreqHz = status.rxFreqHz,
            txFreqHz = status.selectedFreqHz,
            holdTxFreq = status.holdTxFreq,
            onNudge = { viewModel.nudgeTxFreq(it) },
            onHoldChange = { viewModel.setHoldTxFreq(it) },
        )

        FilterRow(
            filter = filter,
            shown = rows.size,
            total = messages.size,
            onCqOnly = { viewModel.setCqOnly(it) },
            onExcludeWorked = { viewModel.setExcludeWorked(it) },
            onQuery = { viewModel.setCallFilter(it) },
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (rows.isEmpty()) {
                Text(
                    decodeEmptyHint(status, messages.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(rows, key = { it.msg.text + "@" + it.msg.slotUtcMs }) { row ->
                        DecodeRow(
                            model = row,
                            slotMs = status.slotMs.toLong(),
                            manualEnabled = !status.qso.active,
                            onClick = { viewModel.selectFrequency(row.msg.df) },
                            onReply = { pendingTx = PendingTx.Reply(row.parsed.from!!, row.parsed.grid, row.msg.df) },
                            onLongPress = { manualFor = row.msg },
                        )
                    }
                }
            }
        }

        ControlPanel(
            status = status,
            expanded = controlExpanded,
            onToggle = { controlExpanded = !controlExpanded },
            onParityChange = { viewModel.setTxParity(it) },
            onStartCq = { pendingTx = PendingTx.Cq },
            onStopTx = { viewModel.stopTransmit() },
            onSetCallFirst = { viewModel.setCallFirst(it) },
            onRequestArmCallFirst = { if (status.callFirstArmed) viewModel.disarmCallFirst() else confirmCallFirst = true },
            recentQso = recentQso,
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

    manualFor?.let { msg ->
        val parsed = MessageParser.parse(msg.text)
        ManualSendDialog(
            parsed = parsed,
            snr = msg.snr,
            myCall = status.myCall,
            onAnswer = {
                manualFor = null
                pendingTx = PendingTx.Reply(parsed.from!!, parsed.grid, msg.df)
            },
            onSend = {
                manualFor = null
                viewModel.sendOnce(it)
            },
            onFreeText = {
                manualFor = null
                freeTextFor = parsed.from
            },
            onDismiss = { manualFor = null },
        )
    }

    freeTextFor?.let { to ->
        FreeTextDialog(
            to = to,
            myCall = status.myCall,
            onSend = {
                freeTextFor = null
                viewModel.sendOnce(it)
            },
            onDismiss = { freeTextFor = null },
        )
    }

    if (confirmCallFirst) {
        AlertDialog(
            onDismissRequest = { confirmCallFirst = false },
            title = { Text("启用 Call 1st") },
            text = {
                Text(
                    "将按「${status.callFirst.label}」自动应答满足条件的 CQ。\n" +
                        "呼号：${status.myCall}｜发射频率：${status.selectedFreqHz} Hz｜周期：" +
                        "${if (status.txParity == 0) "偶数" else "奇数"}\n" +
                        "一次 QSO 结束后会自动停止，请确认电台已就绪。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmCallFirst = false
                        viewModel.armCallFirst()
                    },
                ) { Text("确认启用") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCallFirst = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 解码列表空态提示文案（纯函数，便于 JVM 单测）。
 *
 * 三种情况：未开始接收 / 已接收但本时隙无解码 / 有解码但被过滤掉。
 */
fun decodeEmptyHint(status: ReceiverStatus, decodedTotal: Int): String = when {
    !status.running -> "未开始接收：点右上角「开始接收」"
    decodedTotal == 0 -> "本时隙暂无解码，等待信号…\n（未接天线 / 无音频输入时不会出现解码）"
    else -> "没有符合当前过滤条件的解码"
}

/** 解码行 + 解析结果 + 高亮分类。 */
private data class DecodeRowModel(
    val msg: DecodeResult,
    val parsed: ParsedMessage,
    val style: DecodeStyle,
)

@Composable
private fun HeaderRow(
    status: ReceiverStatus,
    onOpenSettings: () -> Unit,
    onToggleRunning: () -> Unit,
    onBandChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${status.myCall.ifEmpty { "（未设置呼号）" }} / ${status.myGrid.ifEmpty { "--" }}",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(onClick = onOpenSettings),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                BandSelector(band = status.band, enabled = true, onChange = onBandChange)
                Text(if (status.running) "● 接收中" else "○ 已停止", style = MaterialTheme.typography.labelMedium)
            }
        }
        Button(onClick = onToggleRunning) {
            Text(if (status.running) "停止接收" else "开始接收")
        }
    }
    if (status.myCall.isEmpty()) {
        Text(
            "请到「设置」填写呼号与网格后再发射。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun BandSelector(band: String, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text("波段 $band ▾", style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (b in BandPlan.bands) {
                DropdownMenuItem(
                    text = { Text(b.name) },
                    onClick = {
                        expanded = false
                        onChange(b.name)
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusBar(status: ReceiverStatus) {
    val parity = if (status.slotParity == 0) "偶数周期" else "奇数周期"
    val alignHint = if (status.running && !status.inSlot) "（等待时隙对齐…）" else ""
    Column {
        Text("状态：${status.status}$alignHint", style = MaterialTheme.typography.bodySmall)
        if (status.running) {
            Text(
                String.format(
                    Locale.US,
                    "时隙 %d ms｜%s｜下一时隙 %.1f s｜已解码时隙 %d｜丢帧 %d",
                    status.slotMs,
                    parity,
                    status.msToNextSlot / 1000.0,
                    status.slotsDecoded,
                    status.droppedSamples,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
            LinearProgressIndicator(
                progress = { status.slotProgress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
    }
}

@Composable
private fun FrequencyAxis(fMinHz: Float?, maxHz: Float?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val a = fMinHz ?: 0f
        val b = maxHz ?: 0f
        val mid = (a + b) / 2f
        for (v in listOf(a, mid, b)) {
            Text(
                "${v.toInt()} Hz",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** RX / TX 频率面板：点瀑布或解码行设 RX，步进按钮调 TX，Hold Tx 决定是否联动。 */
@Composable
private fun FreqPanel(
    rxFreqHz: Int?,
    txFreqHz: Int,
    holdTxFreq: Boolean,
    onNudge: (Int) -> Unit,
    onHoldChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "RX  ${rxFreqHz?.let { "$it Hz" } ?: "--"}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF2E7D32),
                modifier = Modifier.weight(1f),
            )
            Text(
                "TX  $txFreqHz Hz",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFC62828),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (d in intArrayOf(-100, -10, 10, 100)) {
                OutlinedButton(
                    onClick = { onNudge(d) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (d > 0) "+$d" else "$d", style = MaterialTheme.typography.labelSmall)
                }
            }
            FilterChip(
                selected = holdTxFreq,
                onClick = { onHoldChange(!holdTxFreq) },
                label = { Text("Hold Tx", style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun FilterRow(
    filter: DecodeFilterState,
    shown: Int,
    total: Int,
    onCqOnly: (Boolean) -> Unit,
    onExcludeWorked: (Boolean) -> Unit,
    onQuery: (String) -> Unit,
) {
    var query by remember { mutableStateOf(filter.query) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = filter.cqOnly,
                onClick = { onCqOnly(!filter.cqOnly) },
                label = { Text("CQ only", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = filter.excludeWorked,
                onClick = { onExcludeWorked(!filter.excludeWorked) },
                label = { Text("排除已通联", style = MaterialTheme.typography.labelSmall) },
            )
            Text("显示 $shown / $total", style = MaterialTheme.typography.labelSmall)
        }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onQuery(it)
            },
            placeholder = { Text("呼号/前缀过滤（逗号分隔多个）", style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DecodeRow(
    model: DecodeRowModel,
    slotMs: Long,
    manualEnabled: Boolean,
    onClick: () -> Unit,
    onReply: () -> Unit,
    onLongPress: () -> Unit,
) {
    val message = model.msg
    val style = model.style

    // 偶/奇周期背景分色（按该条报文所属时隙判定）
    val tint = if (message.slotUtcMs > 0 && slotMs > 0) {
        val even = ((message.slotUtcMs / slotMs) % 2L) == 0L
        if (even) Color(0x142962FF) else Color(0x14FF6D00)
    } else {
        Color.Transparent
    }
    val barColor = when (style.role) {
        HighlightRole.CURRENT_QSO -> HighlightCurrent
        HighlightRole.TO_ME -> HighlightToMe
        HighlightRole.NEW_GRID -> HighlightNewGrid
        HighlightRole.NEW_PREFIX -> HighlightNewPrefix
        HighlightRole.NORMAL -> Color(0x33FFFFFF)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint)
            .combinedClickable(
                enabled = true,
                onClick = onClick,
                onLongClick = { if (manualEnabled) onLongPress() },
            )
            .drawBehind {
                drawRect(color = barColor, size = Size(3.dp.toPx(), size.height))
            }
            .padding(start = 6.dp, top = 2.dp, bottom = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildString {
                append(QsoTime.isoTime(message.slotUtcMs))
                append(String.format(Locale.US, "  %+3d  DT %+.1f  %4d  ", message.snr, message.dt, message.df))
                if (style.toMe) append("▎")
                if (style.newGrid) append("●")
                if (style.newPrefix) append("●")
                append(" ")
                append(message.text)
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (style.role == HighlightRole.TO_ME) HighlightToMe else Color.Unspecified,
            modifier = Modifier.weight(1f),
        )
        val canReply = model.parsed.from != null &&
            (model.parsed.isCq ||
                (model.parsed.to != null &&
                    (model.parsed.report != null || model.parsed.isRoger || model.parsed.grid != null)))
        if (canReply) {
            TextButton(onClick = onReply) {
                Text("应答", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 折叠式 QSO 控制面板：折叠态一行摘要，展开态含周期、Call 1st 与最近通联。 */
@Composable
private fun ControlPanel(
    status: ReceiverStatus,
    expanded: Boolean,
    onToggle: () -> Unit,
    onParityChange: (Int) -> Unit,
    onStartCq: () -> Unit,
    onStopTx: () -> Unit,
    onSetCallFirst: (CallFirstMode) -> Unit,
    onRequestArmCallFirst: () -> Unit,
    recentQso: List<com.example.ft8vox.data.log.QsoEntity>,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "QSO ${status.qso.description}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Call 1st ${status.callFirst.label}${if (status.callFirstArmed) "●" else ""}",
                style = MaterialTheme.typography.labelSmall,
            )
            TextButton(onClick = onToggle) { Text(if (expanded) "收起" else "展开") }
        }
        if (!expanded) return@Column

        HorizontalDivider(Modifier.padding(vertical = 2.dp))
        QsoStatusLine(status)
        TxControlRow(
            txParity = status.txParity,
            armed = status.txArmed,
            canOperate = status.myCall.isNotEmpty(),
            onParityChange = onParityChange,
            onStartCq = onStartCq,
            onStopTx = onStopTx,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Call 1st", style = MaterialTheme.typography.labelMedium)
            for (m in CallFirstMode.entries) {
                FilterChip(
                    selected = status.callFirst == m,
                    onClick = { onSetCallFirst(m) },
                    label = { Text(m.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
            Spacer(Modifier.weight(1f))
            if (status.callFirstArmed) {
                OutlinedButton(onClick = onRequestArmCallFirst) { Text("关闭") }
            } else {
                OutlinedButton(
                    onClick = onRequestArmCallFirst,
                    enabled = status.callFirst != CallFirstMode.OFF,
                ) { Text("启用") }
            }
        }
        if (status.manualTxText != null) {
            Text(
                "待发一次性：${status.manualTxText}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (recentQso.isNotEmpty()) {
            Text("最近通联", style = MaterialTheme.typography.titleSmall)
            for (r in recentQso) {
                Text(
                    String.format(
                        Locale.US,
                        "%s  %s%s  收 %s / 发 %s  %s",
                        QsoTime.isoDateTime(r.utcMs),
                        r.theirCall,
                        r.theirGrid?.let { " ($it)" } ?: "",
                        r.reportReceived?.toString() ?: "--",
                        r.reportSent?.toString() ?: "--",
                        r.band,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** 长按解码行的逐条发送菜单。 */
@Composable
private fun ManualSendDialog(
    parsed: ParsedMessage,
    snr: Int,
    myCall: String,
    onAnswer: () -> Unit,
    onSend: (String) -> Unit,
    onFreeText: () -> Unit,
    onDismiss: () -> Unit,
) {
    val from = parsed.from
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("逐条发送") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(parsed.raw, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (from == null) {
                    Text("该条没有可识别的呼号，只能发自由文本。", style = MaterialTheme.typography.labelSmall)
                } else if (!parsed.isCq) {
                    TextButton(onClick = onAnswer) { Text("应答 $from") }
                }
                if (from != null) {
                    TextButton(onClick = { onSend("$from $myCall ${MessageParser.formatReport(snr)}") }) {
                        Text("发报告 ${MessageParser.formatReport(snr)}")
                    }
                    TextButton(onClick = { onSend("$from $myCall R${MessageParser.formatReport(snr)}") }) {
                        Text("发 R 报告")
                    }
                    TextButton(onClick = { onSend("$from $myCall RR73") }) { Text("发 RR73") }
                    TextButton(onClick = { onSend("$from $myCall 73") }) { Text("发 73") }
                }
                TextButton(onClick = onFreeText) { Text("发自由文本…") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 自由文本发送（预填收方呼号）。 */
@Composable
private fun FreeTextDialog(
    to: String?,
    myCall: String,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(if (to != null) "$to $myCall " else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发送自由文本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.uppercase() },
                    label = { Text("报文") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "自由文本需 4 个以上 token 才会被编码（如 CQ TEST）。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(text) },
                enabled = text.isNotBlank(),
            ) { Text("发送") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
