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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.BandPlan

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

@Composable
private fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
    }
}
