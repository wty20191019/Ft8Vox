package com.example.ft8vox.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ft8vox.engine.AudioDevices

/**
 * 设置页（安卓 Preference 风格，docs/Ft8Vox.md）。
 *
 * 本文件只剩**页面骨架**：音频设备枚举、跨组状态与两个页面级弹窗。内容分两层放：
 * - 每个分组的内容 → `SettingsGroups.kt`（`GroupStation` / `GroupFt8` …）；
 * - 通用控件 → `SettingsWidgets.kt`（`PrefNumber` / `PrefText` / `PrefSwitch` …）。
 *
 * 分组：台站 / 电台（仅 VOX）/ 音频 / FT8 / 瀑布 / 高亮与提醒 / 日志 / 地图 / 关于
 * （「外观」组已随新竖屏外壳去掉，见 docs/Ft8Vox.md）。
 * 尚未接通后端能力的项统一置灰并标注「U7」。
 */
@Composable
fun SettingsScreen(
    settings: SettingsViewModel,
    log: LogViewModel,
    session: SessionViewModel,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app by settings.settings.collectAsState()

    // U7c：音频设备枚举（系统默认恒为首项）；设备插拔后需重进本页刷新
    val context = LocalContext.current
    val inputDevices = remember(context) { AudioDevices.inputs(context) }
    val outputDevices = remember(context) { AudioDevices.outputs(context) }

    // 这几个状态**只在本层持有、不在本层读**：读数下沉到真正需要它的叶子组合（见 `SettingsGroups.kt`）。
    // 接收中状态约 5 次/秒发布一次，导入进度、输入框每敲一个字也都会更新它们 ——
    // 在本层读就会连着整页 40 行一起重组（Compose 的「跳过未变化子树」对 ViewModel / AppSettings
    // 这类不稳定参数无效）。所以它们以 `MutableState` 形式传进各组，谁用谁读。
    val statusText = remember { mutableStateOf<String?>(null) }
    val confirmClear = remember { mutableStateOf(false) }
    val bandDialog = remember { mutableStateOf(false) }

    // 解码并行度的 CPU 核数（与顶栏读数、native 同一规则）；频率范围那一半在内层 FT8 组里算，
    // 好让「只有 FT8 组」依赖设置里的频率范围。
    val decodeCores = remember { Runtime.getRuntime().availableProcessors() }

    // 一组一个 item：首帧只组合可见的组，之后也只重组「确实读到变化」的那部分。
    // 滚动位置随 LazyListState 保存，切走再回来由 MainShell 的 SaveableStateHolder 还原。
    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // 页标题已去掉（底部导航「设置」已表明当前页，顶上还有信息头；docs/Ft8Vox.md）
        item(key = "station") { GroupStation(settings, session, app, bandDialog) }
        item(key = "radio") { GroupRadio(settings, session, app, outputDevices) }
        item(key = "audio") { GroupAudio(settings, session, app, inputDevices) }
        item(key = "ft8") { GroupFt8(settings, app, decodeCores) }
        item(key = "waterfall") { GroupWaterfall(settings, app) }
        item(key = "highlight") { GroupHighlight(settings, app) }
        item(key = "log") {
            GroupLog(settings, log, app, statusText, onClearAll = { confirmClear.value = true })
        }
        item(key = "map") { GroupMap(settings, app) }
        item(key = "about") { GroupAbout(onHelp) }
        item(key = "tail") { Spacer(Modifier.height(12.dp)) }
    }

    if (bandDialog.value) {
        BandFreqDialog(
            currentBand = app.band,
            currentHz = app.resolvedDialHz,
            onConfirm = { name, hz ->
                bandDialog.value = false
                settings.update { it.copy(band = name, dialHz = hz) }
            },
            onDismiss = { bandDialog.value = false },
        )
    }

    if (confirmClear.value) {
        ClearLogDialog(
            log = log,
            statusText = statusText,
            onDismiss = { confirmClear.value = false },
        )
    }
}

/** 清空确认弹窗；挂在设置页顶层，不跟着列表项被懒加载回收。 */
@Composable
private fun ClearLogDialog(
    log: LogViewModel,
    statusText: MutableState<String?>,
    onDismiss: () -> Unit,
) {
    val stats by log.stats.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空全部记录") },
        text = { Text("将删除全部 ${stats.total} 条通联记录，且不可撤销。建议先导出 ADIF 备份。") },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    log.clearAll { statusText.value = "已清空通联记录" }
                },
            ) { Text("确认清空") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
