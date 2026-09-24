package com.example.ft8vox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.log.QsoEntity
import com.example.ft8vox.qso.MessageParser
import java.util.Locale

/** 日志页：统计、筛选、列表、补录/编辑、ADIF 导入导出。 */
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

    Column(modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text("日志", style = MaterialTheme.typography.titleLarge)

        StatsCard(stats)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { creating = true }) { Text("补录") }
            AdifActionRow(
                log = log,
                myCall = myCall,
                myGrid = myGrid,
                onStatus = { statusText = it },
            )
        }
        if (myCall.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "未设置呼号，导入的记录会缺少 MY_CALL。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onOpenSettings) { Text("去设置") }
            }
        }
        statusText?.let {
            Text(it, style = MaterialTheme.typography.labelSmall)
        }

        FilterRow(filter = filter, onChange = log::setFilter)

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("显示 ${entries.size} / 共 ${stats.total} 条", style = MaterialTheme.typography.labelMedium)

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
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries, key = { it.id }) { entity ->
                    LogRow(entity = entity, onClick = { editing = entity })
                }
            }
        }
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
}

@Composable
private fun StatsCard(stats: LogStats) {
    var expanded by remember { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("统计", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
            }
            if (expanded) {
                Text(
                    "通联 ${stats.total}｜呼号 ${stats.uniqueCalls}｜网格 ${stats.uniqueGrids}｜已确认 ${stats.confirmed}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (stats.byBand.isNotEmpty()) {
                    Text(
                        "波段  " + stats.byBand.joinToString("  ") { "${it.first} ${it.second}" },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (stats.byMode.isNotEmpty()) {
                    Text(
                        "模式  " + stats.byMode.joinToString("  ") { "${it.first} ${it.second}" },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterRow(filter: LogFilter, onChange: (LogFilter) -> Unit) {
    var bandMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    // 日期输入用本地文本，解析成功才写回筛选条件（避免半截输入被反复解析）
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = { onChange(filter.copy(query = it)) },
                label = { Text("呼号/网格") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Box {
                TextButton(onClick = { bandMenu = true }) { Text(filter.band ?: "全部波段") }
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
                TextButton(onClick = { modeMenu = true }) { Text(filter.mode ?: "全部模式") }
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
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = fromText,
                onValueChange = {
                    fromText = it
                    onChange(filter.copy(fromMs = QsoTime.parseUtc(it, "000000")))
                },
                label = { Text("起始日期") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = toText,
                onValueChange = {
                    toText = it
                    onChange(filter.copy(toMs = QsoTime.parseUtc(it, "235959")))
                },
                label = { Text("结束日期") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LogRow(entity: QsoEntity, onClick: () -> Unit) {
    val confirmed = entity.qslRcvd == "Y" || entity.lotwRcvd == "Y"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                QsoTime.isoDateTime(entity.utcMs),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${entity.band}  ${entity.mode}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entity.theirCall + (entity.theirGrid?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                String.format(
                    Locale.US,
                    "收 %s / 发 %s%s",
                    entity.reportReceived?.let { MessageParser.formatReport(it) } ?: "--",
                    entity.reportSent?.let { MessageParser.formatReport(it) } ?: "--",
                    if (confirmed) "  ✔" else "",
                ),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
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
                        OutlinedButton(onClick = { bandMenu = true }) { Text("波段 $band") }
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
