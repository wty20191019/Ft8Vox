package com.example.ft8vox.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.log.QsoEntity
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.ui.theme.VoxAccent
import com.example.ft8vox.ui.theme.VoxCard
import com.example.ft8vox.ui.theme.VoxOnSurfaceVariant
import com.example.ft8vox.ui.theme.VoxRxGreen
import java.util.Locale

/**
 * 日志页（new_ui.md §5）。
 *
 * 顶部搜索 + 波段 / 模式 / 日期筛选；表格化卡片列表（呼号 / 网格 / 时间 / RST / 模式）；
 * 长按卡片编辑或删除；底部常驻统计（QSO / DXCC / 网格 / 波段柱图）；
 * 「⋮」菜单里放新增、导入 / 导出 ADIF、局域网后台地址（U7）与清空日志。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogScreen(
    log: LogViewModel,
    myCall: String,
    myGrid: String?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by log.filtered.collectAsState()
    val stats by log.stats.collectAsState()
    val filter by log.filter.collectAsState()

    var editing by remember { mutableStateOf<QsoEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var lanDialog by remember { mutableStateOf(false) }

    val adif = rememberAdifActions(log, myCall, myGrid) { statusText = it }

    Column(modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("日志", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                "共 ${stats.total} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("新增通联") },
                        onClick = {
                            menuOpen = false
                            creating = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导入 ADIF") },
                        onClick = {
                            menuOpen = false
                            adif.import()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出 ADIF") },
                        onClick = {
                            menuOpen = false
                            adif.export()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("局域网后台地址（U7）") },
                        onClick = {
                            menuOpen = false
                            lanDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("清空日志") },
                        onClick = {
                            menuOpen = false
                            clearDialog = true
                        },
                    )
                }
            }
        }

        SearchRow(filter = filter, onChange = log::setFilter)
        FilterChips(filter = filter, onChange = log::setFilter)

        if (myCall.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "未设置呼号，新记录会缺少 MY_CALL。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onOpenSettings) { Text("去设置") }
            }
        }
        statusText?.let {
            Text(it, style = MaterialTheme.typography.labelSmall)
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
            "显示 ${entries.size} / 共 ${stats.total} 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (stats.total == 0) "还没有通联记录" else "没有符合筛选条件的记录",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entries, key = { it.id }) { entity ->
                    LogCard(
                        entity = entity,
                        onEdit = { editing = entity },
                        onDelete = { log.delete(entity) },
                    )
                }
            }
        }

        StatsPanel(stats)
    }

    if (creating) {
        QsoEditDialog(
            entity = null,
            myCall = myCall,
            myGrid = myGrid,
            onSave = {
                log.add(it)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }

    editing?.let { entity ->
        QsoEditDialog(
            entity = entity,
            myCall = myCall,
            myGrid = myGrid,
            onSave = {
                log.update(it)
                editing = null
            },
            onDelete = {
                log.delete(entity)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("清空日志") },
            text = { Text("将删除全部 ${stats.total} 条通联记录，且不可撤销。建议先导出 ADIF。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearDialog = false
                        log.clearAll { statusText = "已清空日志" }
                    },
                ) { Text("删除全部") }
            },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("取消") } },
        )
    }

    if (lanDialog) {
        AlertDialog(
            onDismissRequest = { lanDialog = false },
            title = { Text("局域网后台地址") },
            text = {
                Text(
                    "U7 提供：App 内 HTTP 服务与局域网访问地址，需前台服务常驻。当前版本未启用。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = { lanDialog = false }) { Text("知道了") } },
        )
    }
}

/** 搜索框 + 清除按钮。 */
@Composable
private fun SearchRow(filter: LogFilter, onChange: (LogFilter) -> Unit) {
    OutlinedTextField(
        value = filter.query,
        onValueChange = { onChange(filter.copy(query = it)) },
        placeholder = { Text("搜索呼号 / 网格") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (filter.query.isNotEmpty()) {
                IconButton(onClick = { onChange(filter.copy(query = "")) }) {
                    Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 波段 / 模式 / 日期筛选 chip（触摸高度对齐 48dp）。 */
@Composable
private fun FilterChips(filter: LogFilter, onChange: (LogFilter) -> Unit) {
    var bandMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf(false) }
    val hasFilter = filter.band != null || filter.mode != null ||
        filter.fromMs != null || filter.toMs != null

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FilterChip(
                selected = filter.band != null,
                onClick = { bandMenu = true },
                label = { Text(filter.band ?: "波段") },
                modifier = Modifier.height(48.dp),
            )
            DropdownMenu(expanded = bandMenu, onDismissRequest = { bandMenu = false }) {
                DropdownMenuItem(
                    text = { Text("全部波段") },
                    onClick = {
                        bandMenu = false
                        onChange(filter.copy(band = null))
                    },
                )
                for (b in BandPlan.bands) {
                    DropdownMenuItem(
                        text = { Text(b.name) },
                        onClick = {
                            bandMenu = false
                            onChange(filter.copy(band = b.name))
                        },
                    )
                }
            }
        }
        Box {
            FilterChip(
                selected = filter.mode != null,
                onClick = { modeMenu = true },
                label = { Text(filter.mode ?: "模式") },
                modifier = Modifier.height(48.dp),
            )
            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                DropdownMenuItem(
                    text = { Text("全部模式") },
                    onClick = {
                        modeMenu = false
                        onChange(filter.copy(mode = null))
                    },
                )
                for (m in listOf("FT8", "FT4")) {
                    DropdownMenuItem(
                        text = { Text(m) },
                        onClick = {
                            modeMenu = false
                            onChange(filter.copy(mode = m))
                        },
                    )
                }
            }
        }
        FilterChip(
            selected = filter.fromMs != null || filter.toMs != null,
            onClick = { dateDialog = true },
            label = { Text(dateLabel(filter)) },
            modifier = Modifier.height(48.dp),
        )
        if (hasFilter) {
            FilterChip(
                selected = false,
                onClick = { onChange(LogFilter()) },
                label = { Text("清除") },
                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, Modifier.size(16.dp)) },
                modifier = Modifier.height(48.dp),
            )
        }
    }

    if (dateDialog) {
        DateRangeDialog(
            filter = filter,
            onApply = {
                onChange(filter.copy(fromMs = it.first, toMs = it.second))
                dateDialog = false
            },
            onClear = {
                onChange(filter.copy(fromMs = null, toMs = null))
                dateDialog = false
            },
            onDismiss = { dateDialog = false },
        )
    }
}

/** 日期区间对话框（UTC 日期，闭区间）。 */
@Composable
private fun DateRangeDialog(
    filter: LogFilter,
    onApply: (Pair<Long?, Long?>) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var fromText by remember { mutableStateOf(filter.fromMs?.let { QsoTime.isoDate(it) } ?: "") }
    var toText by remember { mutableStateOf(filter.toMs?.let { QsoTime.isoDate(it) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("日期范围（UTC）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fromText,
                    onValueChange = { fromText = it },
                    label = { Text("起始 YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = toText,
                    onValueChange = { toText = it },
                    label = { Text("结束 YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val from = fromText.trim().let { t ->
                        if (t.isEmpty()) null else QsoTime.parseUtc(t, "000000")
                    }
                    val to = toText.trim().let { t ->
                        if (t.isEmpty()) null else QsoTime.parseUtc(t, "235959")
                    }
                    if (fromText.isNotBlank() && from == null) {
                        error = "起始日期格式不正确"
                        return@Button
                    }
                    if (toText.isNotBlank() && to == null) {
                        error = "结束日期格式不正确"
                        return@Button
                    }
                    if (from != null && to != null && from > to) {
                        error = "起始日期不能晚于结束日期"
                        return@Button
                    }
                    onApply(from to to)
                },
            ) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (filter.fromMs != null || filter.toMs != null) {
                    TextButton(onClick = onClear) { Text("清除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun dateLabel(filter: LogFilter): String {
    val from = filter.fromMs
    val to = filter.toMs
    return when {
        from != null && to != null -> "${QsoTime.isoDate(from)}~${QsoTime.isoDate(to)}"
        from != null -> "${QsoTime.isoDate(from)} 起"
        to != null -> "至 ${QsoTime.isoDate(to)}"
        else -> "日期"
    }
}

/** 表格化卡片：呼号 / 网格 / 时间 / RST / 模式。长按弹出编辑、删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogCard(entity: QsoEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    val confirmed = entity.qslRcvd == "Y" || entity.lotwRcvd == "Y"
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .combinedClickable(onClick = onEdit, onLongClick = { menuOpen = true }),
            colors = CardDefaults.cardColors(containerColor = VoxCard),
        ) {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                // 左侧确认色条
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (confirmed) VoxRxGreen else VoxCard),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entity.theirCall, style = MaterialTheme.typography.titleMedium)
                        if (confirmed) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "✔",
                                style = MaterialTheme.typography.labelSmall,
                                color = VoxRxGreen,
                            )
                        }
                    }
                    Text(
                        entity.theirGrid ?: "----",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${entity.band}  ${entity.mode}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = VoxAccent,
                    )
                    Text(
                        QsoTime.isoDateTime(entity.utcMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        String.format(
                            Locale.US,
                            "收 %s / 发 %s",
                            entity.reportReceived?.let { MessageParser.formatReport(it) } ?: "--",
                            entity.reportSent?.let { MessageParser.formatReport(it) } ?: "--",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("编辑") },
                onClick = {
                    menuOpen = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** 底部常驻统计：QSO / DXCC / 网格 / 确认 + 波段柱图。 */
@Composable
private fun StatsPanel(stats: LogStats) {
    Surface(
        color = VoxCard,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                StatCell("QSO", stats.total.toString(), Modifier.weight(1f))
                StatCell("DXCC", stats.uniqueEntities.toString(), Modifier.weight(1f))
                StatCell("网格", stats.uniqueGrids.toString(), Modifier.weight(1f))
                StatCell("确认", stats.confirmed.toString(), Modifier.weight(1f))
            }
            if (stats.byBand.isNotEmpty()) {
                BandBarChart(stats.byBand, Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp))
            }
            Text(
                "DXCC 按呼号前缀近似（U7 精确实体表）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/** 波段柱图：每根柱高按条数比例，可横向滚动。 */
@Composable
private fun BandBarChart(bands: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    val max = bands.maxOf { it.second }.coerceAtLeast(1)
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for ((name, count) in bands) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height((6 + 26 * count / max).dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(VoxAccent),
                )
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = VoxOnSurfaceVariant,
                )
            }
        }
    }
}

/** 新增 / 编辑一条通联记录。 */
@Composable
private fun QsoEditDialog(
    entity: QsoEntity?,
    myCall: String,
    myGrid: String?,
    onSave: (QsoEntity) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val now = remember { QsoTime.nowUtcMs() }
    var call by remember { mutableStateOf(entity?.theirCall ?: "") }
    var grid by remember { mutableStateOf(entity?.theirGrid ?: "") }
    var dateText by remember { mutableStateOf(QsoTime.isoDate(entity?.utcMs ?: now)) }
    var timeText by remember { mutableStateOf(QsoTime.isoTime(entity?.utcMs ?: now)) }
    var band by remember {
        mutableStateOf(entity?.band?.takeIf { it.isNotEmpty() } ?: BandPlan.DEFAULT_BAND)
    }
    var mode by remember { mutableStateOf(entity?.mode ?: "FT8") }
    var sentText by remember {
        mutableStateOf(entity?.reportSent?.let { MessageParser.formatReport(it) } ?: "")
    }
    var rcvdText by remember {
        mutableStateOf(entity?.reportReceived?.let { MessageParser.formatReport(it) } ?: "")
    }
    var qsl by remember { mutableStateOf(entity?.qslRcvd == "Y") }
    var lotw by remember { mutableStateOf(entity?.lotwRcvd == "Y") }
    var comment by remember { mutableStateOf(entity?.comment ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var bandMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entity == null) "新增通联" else "编辑通联") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = call,
                    onValueChange = { call = it.uppercase() },
                    label = { Text("对方呼号 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = grid,
                    onValueChange = { grid = it.uppercase() },
                    label = { Text("对方网格") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("日期 (UTC)") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f),
                    )
                    OutlinedTextField(
                        value = timeText,
                        onValueChange = { timeText = it },
                        label = { Text("时间 (UTC)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box {
                        TextButton(onClick = { bandMenu = true }) { Text("波段 $band") }
                        DropdownMenu(expanded = bandMenu, onDismissRequest = { bandMenu = false }) {
                            for (b in BandPlan.bands) {
                                DropdownMenuItem(
                                    text = { Text(b.name) },
                                    onClick = {
                                        bandMenu = false
                                        band = b.name
                                    },
                                )
                            }
                        }
                    }
                    FilterChip(
                        selected = mode == "FT8",
                        onClick = { mode = "FT8" },
                        label = { Text("FT8") },
                    )
                    FilterChip(
                        selected = mode == "FT4",
                        onClick = { mode = "FT4" },
                        label = { Text("FT4") },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = sentText,
                        onValueChange = { sentText = it },
                        label = { Text("我方发报告") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = rcvdText,
                        onValueChange = { rcvdText = it },
                        label = { Text("对方发报告") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = qsl,
                        onClick = { qsl = !qsl },
                        label = { Text("已收 QSL") },
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = lotw,
                        onClick = { lotw = !lotw },
                        label = { Text("LoTW 确认") },
                    )
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val c = call.trim().uppercase()
                    if (c.isEmpty()) {
                        error = "对方呼号不能为空"
                        return@Button
                    }
                    val utcMs = QsoTime.parseUtc(dateText, timeText)
                    if (utcMs == null) {
                        error = "日期或时间格式不正确（UTC）"
                        return@Button
                    }
                    val existingFreq = entity?.freqHz ?: 0L
                    onSave(
                        QsoEntity(
                            id = entity?.id ?: 0,
                            theirCall = c,
                            theirGrid = grid.trim().uppercase().ifEmpty { null },
                            myCall = entity?.myCall?.takeIf { it.isNotEmpty() } ?: myCall,
                            myGrid = entity?.myGrid ?: myGrid,
                            utcMs = utcMs,
                            band = band,
                            freqHz = if (existingFreq > 0) existingFreq else BandPlan.dialHz(band),
                            mode = mode,
                            reportSent = parseReport(sentText),
                            reportReceived = parseReport(rcvdText),
                            qslRcvd = if (qsl) "Y" else null,
                            lotwRcvd = if (lotw) "Y" else null,
                            comment = comment.trim().ifEmpty { null },
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 解析信号报告输入；RST（如 599）不属 FT8 刻度，忽略。 */
private fun parseReport(text: String): Int? {
    val value = text.trim().removePrefix("R").toIntOrNull() ?: return null
    return if (value in -40..40) value else null
}
