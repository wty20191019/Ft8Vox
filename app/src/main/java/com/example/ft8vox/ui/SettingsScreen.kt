package com.example.ft8vox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.data.settings.CallFirstMode
import com.example.ft8vox.data.settings.DecodePreset
import com.example.ft8vox.data.settings.DecodeSettings
import com.example.ft8vox.data.settings.SampleRatePref

/** 设置页：台站信息、日志与 ADIF、关于。 */
@Composable
fun SettingsScreen(
    settings: SettingsViewModel,
    log: LogViewModel,
    modifier: Modifier = Modifier,
) {
    val app by settings.settings.collectAsState()
    val entries by log.entries.collectAsState()

    var statusText by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var bandMenu by remember { mutableStateOf(false) }

    // 文本框用本地状态：DataStore 是异步往返，直接绑 Flow 值会在回显前把刚输入的字吞掉
    var call by remember { mutableStateOf(app.myCall) }
    var grid by remember { mutableStateOf(app.myGrid) }
    var note by remember { mutableStateOf(app.note) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        // ---- 台站 ----
        Text("台站", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = call,
            onValueChange = { v ->
                val u = v.trim().uppercase()
                call = u
                settings.update { it.copy(myCall = u) }
            },
            label = { Text("呼号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = grid,
            onValueChange = { v ->
                val u = v.trim().uppercase()
                grid = u
                settings.update { it.copy(myGrid = u) }
            },
            label = { Text("网格（Maidenhead，如 JN25）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("当前波段", style = MaterialTheme.typography.bodyMedium)
            Box {
                TextButton(onClick = { bandMenu = true }) { Text("${app.band} ▾") }
                DropdownMenu(expanded = bandMenu, onDismissRequest = { bandMenu = false }) {
                    for (b in BandPlan.bands) {
                        DropdownMenuItem(
                            text = { Text(b.name) },
                            onClick = {
                                bandMenu = false
                                settings.update { it.copy(band = b.name) }
                            },
                        )
                    }
                }
            }
            Text(
                "无 CAT，需人工指定",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        OutlinedTextField(
            value = note,
            onValueChange = { v ->
                note = v
                settings.update { it.copy(note = v) }
            },
            label = { Text("备注") },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // ---- 发射 ----
        Text("发射", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Hold Tx Freq",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = app.holdTxFreq,
                onCheckedChange = { v -> settings.update { s -> s.copy(holdTxFreq = v) } },
            )
        }
        Text(
            "开启后点解码行只改 RX、不跟随对方频率（split 场景）；关闭则「点谁打谁」。",
            style = MaterialTheme.typography.labelSmall,
        )
        Text("Call 1st 自动应答", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in CallFirstMode.entries) {
                FilterChip(
                    selected = app.callFirst == m,
                    onClick = { settings.update { s -> s.copy(callFirst = m) } },
                    label = { Text(m.label) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "最大重试次数",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { settings.update { s -> s.copy(maxRetries = (s.maxRetries - 1).coerceAtLeast(1)) } },
            ) { Text("−") }
            Text("  ${app.maxRetries}  ", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { settings.update { s -> s.copy(maxRetries = (s.maxRetries + 1).coerceAtMost(20)) } },
            ) { Text("+") }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // ---- 解码 ----
        Text("解码", style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (p in listOf(DecodePreset.FAST, DecodePreset.STANDARD, DecodePreset.DEEP)) {
                FilterChip(
                    selected = app.decodePreset == p,
                    onClick = {
                        settings.update { s ->
                            s.copy(decode = s.decode.applyPreset(p), decodePreset = p)
                        }
                    },
                    label = { Text(p.label) },
                )
            }
            if (app.decodePreset == DecodePreset.CUSTOM) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(DecodePreset.CUSTOM.label) },
                )
            }
        }
        Text(
            "「快」省电、候选更少；「深」更慢但弱信号解码率更高。时间/频率 OSR 与频率范围需重启接收生效，其余参数即时生效。",
            style = MaterialTheme.typography.labelSmall,
        )

        StepperRow("时间 OSR", app.decode.timeOsr, DecodeSettings.TIME_OSR_RANGE) { v ->
            settings.updateDecode { it.copy(timeOsr = v) }
        }
        StepperRow("频率 OSR", app.decode.freqOsr, DecodeSettings.FREQ_OSR_RANGE) { v ->
            settings.updateDecode { it.copy(freqOsr = v) }
        }
        StepperRow("最低得分", app.decode.minScore, DecodeSettings.MIN_SCORE_RANGE) { v ->
            settings.updateDecode { it.copy(minScore = v) }
        }
        StepperRow("LDPC 迭代", app.decode.ldpcIterations, DecodeSettings.LDPC_RANGE, step = 5) { v ->
            settings.updateDecode { it.copy(ldpcIterations = v) }
        }
        StepperRow(
            "候选上限",
            app.decode.maxCandidates,
            DecodeSettings.MAX_CANDIDATES_RANGE,
            step = 20,
        ) { v -> settings.updateDecode { it.copy(maxCandidates = v) } }
        StepperRow(
            "单时隙上限",
            app.decode.maxDecoded,
            DecodeSettings.MAX_DECODED_RANGE,
            step = 5,
        ) { v -> settings.updateDecode { it.copy(maxDecoded = v) } }
        StepperRow("频率下限 Hz", app.decode.fMinHz, DecodeSettings.F_MIN_RANGE, step = 50) { v ->
            settings.updateDecode { it.copy(fMinHz = v) }
        }
        StepperRow("频率上限 Hz", app.decode.fMaxHz, DecodeSettings.F_MAX_RANGE, step = 50) { v ->
            settings.updateDecode { it.copy(fMaxHz = v) }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // ---- 音频 ----
        Text("音频", style = MaterialTheme.typography.titleSmall)
        Text("采样率偏好", style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (p in SampleRatePref.entries) {
                FilterChip(
                    selected = app.sampleRate == p,
                    onClick = { settings.update { s -> s.copy(sampleRate = p) } },
                    label = { Text(p.label) },
                )
            }
        }
        Text(
            "改动在下次「开始接收」时生效；设备实际采样率可能与此不同（以状态栏显示为准）。输入/输出设备目前用系统默认，未做设备路由。",
            style = MaterialTheme.typography.labelSmall,
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // ---- 日志与 ADIF ----
        Text("日志与 ADIF", style = MaterialTheme.typography.titleSmall)
        Text(
            "共 ${entries.size} 条记录",
            style = MaterialTheme.typography.bodySmall,
        )
        AdifActionRow(
            log = log,
            myCall = app.myCall,
            myGrid = app.myGrid.ifEmpty { null },
            onStatus = { statusText = it },
        )
        statusText?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        OutlinedButton(onClick = { confirmClear = true }) { Text("清空全部记录") }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // ---- 关于 ----
        Text("关于", style = MaterialTheme.typography.titleSmall)
        val version = rememberAppVersion()
        Text("Ft8Vox $version", style = MaterialTheme.typography.bodySmall)
        Text("许可：GPL-3.0", style = MaterialTheme.typography.bodySmall)
        Text(
            "解码内核基于 ft8_lib。发射前请确认符合所在地区的无线电管理法规，并对发射行为负责。",
            style = MaterialTheme.typography.labelSmall,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部记录") },
            text = { Text("将删除全部 ${entries.size} 条通联记录，且不可撤销。建议先导出 ADIF 备份。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        log.clearAll { statusText = "已清空通联记录" }
                    },
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

/** 设置页的「−/值/+」步进行。 */
@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { onChange((value - step).coerceAtLeast(range.first)) },
            enabled = value > range.first,
        ) { Text("−") }
        Text("  $value  ", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = { onChange((value + step).coerceAtMost(range.last)) },
            enabled = value < range.last,
        ) { Text("+") }
    }
}

/** 改一项高级解码参数：自动钳制并标记为「自定义」预设。 */
private fun SettingsViewModel.updateDecode(transform: (DecodeSettings) -> DecodeSettings) {
    update { s ->
        s.copy(decode = transform(s.decode).clamped(), decodePreset = DecodePreset.CUSTOM)
    }
}

@Composable
private fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
    }
}
