package com.example.ft8vox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.settings.CallFirstMode
import com.example.ft8vox.data.settings.DecodePreset
import com.example.ft8vox.data.settings.DecodeSettings
import com.example.ft8vox.data.settings.FontSize
import com.example.ft8vox.data.settings.SampleRatePref
import com.example.ft8vox.data.settings.ThemeMode
import com.example.ft8vox.data.settings.VoxTrigger
import com.example.ft8vox.data.settings.WaterfallHeight
import com.example.ft8vox.data.settings.WaterfallPalette
import com.example.ft8vox.data.settings.WorkedStyle
import com.example.ft8vox.engine.AudioDevices
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.grid.Maidenhead
import com.example.ft8vox.ui.theme.VoxRxGreen
/**
 * 设置页（安卓 Preference 风格，new_ui.md §6）。
 *
 * 分组：台站 / 电台（仅 VOX）/ 音频 / FT8 / 高亮与提醒 / 外观 / 日志与网络 / 关于。
 * 尚未接通后端能力的项统一置灰并标注「U7」。
 */
@Composable
fun SettingsScreen(
    settings: SettingsViewModel,
    log: LogViewModel,
    session: SessionViewModel,
    modifier: Modifier = Modifier,
) {
    val app by settings.settings.collectAsState()
    val entries by log.entries.collectAsState()
    val sessionStatus by session.status.collectAsState()

    // U7c：音频设备枚举（系统默认恒为首项）；设备插拔后需重进本页刷新
    val context = LocalContext.current
    val inputDevices = remember(context) { AudioDevices.inputs(context) }
    val outputDevices = remember(context) { AudioDevices.outputs(context) }

    var statusText by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var bandDialog by remember { mutableStateOf(false) }

    // 文本框用本地状态：DataStore 是异步往返，直接绑 Flow 值会在回显前把刚输入的字吞掉
    var call by remember { mutableStateOf(app.myCall) }
    var grid by remember { mutableStateOf(app.myGrid) }
    var note by remember { mutableStateOf(app.note) }

    // 错误态：呼号必填；网格允许留空，但填了就必须合法（Maidenhead 2/4/6/8 位）
    val callError = call.isEmpty()
    val gridError = grid.isNotEmpty() && !Maidenhead.isValid(grid)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 4.dp),
        )

        // ---------- 台站（设计外补充：无 CAT，台站信息必填） ----------
        SettingsGroup("台站") {
            PrefText(
                label = "呼号",
                value = call,
                onValueChange = { v ->
                    val u = v.trim().uppercase()
                    call = u
                    settings.update { it.copy(myCall = u) }
                },
                supporting = if (callError) "发射前必须填写呼号" else "用于生成与识别报文",
                isError = callError,
            )
            PrefDivider()
            PrefText(
                label = "网格（Maidenhead，如 JN25）",
                value = grid,
                onValueChange = { v ->
                    val u = v.trim().uppercase()
                    grid = u
                    settings.update { it.copy(myGrid = u) }
                },
                supporting = if (gridError) {
                    "格式不正确（2/4/6/8 位，如 JN25、JN25AA）"
                } else {
                    "可留空"
                },
                isError = gridError,
            )
            PrefDivider()
            PrefAction(
                title = "波段与频率",
                subtitle = "无 CAT，需人工指定；当前：${app.band} · " +
                    String.format(java.util.Locale.US, "%.4f MHz", app.resolvedDialHz / 1_000_000.0),
                buttonLabel = "选择",
                onClick = { bandDialog = true },
            )
            PrefDivider()
            PrefText(
                label = "备注",
                value = note,
                onValueChange = { v ->
                    note = v
                    settings.update { it.copy(note = v) }
                },
                supporting = "自动写入新通联记录的 COMMENT 字段（可留空）",
            )
        }

        // ---------- 6.1 电台（仅 VOX） ----------
        SettingsGroup("电台（仅 VOX）") {
            PrefNote(
                "无 CAT：App 无法直接控制 PTT，只能靠「前导静音 + 前导音」让电台 VOX 抢先键控，" +
                    "再把 FT8 数据对准时隙起点。VOX 电平与触发状态由 native 依据输入音频估算，仅作提示。"
            )
            PrefChoice(
                title = "VOX 触发",
                subtitle = "音频检测：有声视为触发；静音检测：无声视为空闲",
                options = VoxTrigger.entries,
                selected = app.voxTrigger,
                onSelect = { v -> settings.update { it.copy(voxTrigger = v) } },
                label = { it.label },
            )
            PrefDivider()
            PrefStepper(
                title = "VOX 延迟",
                subtitle = "状态翻转去抖时长",
                value = app.voxDelayMs,
                range = 50..1000,
                step = 50,
                unit = " ms",
                onChange = { v -> settings.update { it.copy(voxDelayMs = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "VOX 阈值",
                value = app.voxThresholdDb,
                range = -60..-20,
                unit = " dB",
                onChange = { v -> settings.update { it.copy(voxThresholdDb = v) } },
            )
            PrefDivider()
            PrefSwitch(
                title = "发射前导音",
                subtitle = "发射前先放一段单音，让 VOX 抢先触发",
                checked = app.txLeadTone,
                onCheckedChange = { v -> settings.update { it.copy(txLeadTone = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "前导音时长",
                value = app.txLeadToneMs,
                range = 0..2000,
                step = 50,
                unit = " ms",
                onChange = { v -> settings.update { it.copy(txLeadToneMs = v) } },
                enabled = app.txLeadTone,
            )
            PrefDivider()
            PrefDropdown(
                title = "输出声卡",
                subtitle = "指定发射音频输出；USB 声卡插入后需重进本页刷新。设备 id 可能随插拔变化",
                options = outputDevices.map { AudioDevices.formatId(it.id) },
                selected = AudioDevices.formatId(AudioDevices.parseId(app.outputDevice)),
                onSelect = { v -> settings.update { it.copy(outputDevice = v) } },
                label = { id -> AudioDevices.label(outputDevices, id) },
            )
            PrefDivider()
            PrefAction(
                title = "测试音",
                subtitle = "发送 1 kHz 单音（2 s），用于 VOX 键控与音量联调",
                buttonLabel = "播放",
                onClick = { session.playTestTone() },
            )
            VoxLevelRow(sessionStatus)
            PrefDivider()
            PrefStepper(
                title = "PTT 延迟",
                subtitle = "数据前插入的静音，留给声卡路由/电台起键",
                value = app.pttDelayMs,
                range = 0..500,
                step = 10,
                unit = " ms",
                onChange = { v -> settings.update { it.copy(pttDelayMs = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "看门狗超时",
                subtitle = "发射写入卡死保护；实际会抬高到本次发射时长以上，不会截断合法发射",
                value = app.watchdogMs,
                range = 1000..60000,
                step = 1000,
                unit = " ms",
                onChange = { v -> settings.update { it.copy(watchdogMs = v) } },
            )
        }

        // ---------- 6.2 音频 ----------
        SettingsGroup("音频") {
            PrefDropdown(
                title = "输入设备",
                subtitle = "指定接收音频输入；USB 声卡插入后需重进本页刷新。运行中切换需重新「开始接收」",
                options = inputDevices.map { AudioDevices.formatId(it.id) },
                selected = AudioDevices.formatId(AudioDevices.parseId(app.inputDevice)),
                onSelect = { v -> settings.update { it.copy(inputDevice = v) } },
                label = { id -> AudioDevices.label(inputDevices, id) },
            )
            PrefDivider()
            PrefChoice(
                title = "采样率偏好",
                subtitle = "下次「开始接收」生效；设备实际值可能不同（以状态栏为准）",
                options = SampleRatePref.entries,
                selected = app.sampleRate,
                onSelect = { v -> settings.update { it.copy(sampleRate = v) } },
                label = { it.label },
            )
            PrefDivider()
            PrefStepper(
                title = "输入增益",
                subtitle = "对采集样本生效（含 VOX 电平读数），热生效",
                value = app.inputGainDb,
                range = -12..30,
                unit = " dB",
                onChange = { v -> settings.update { it.copy(inputGainDb = v) } },
            )
        }

        // ---------- 6.3 FT8 ----------
        SettingsGroup("FT8") {
            PrefChoice(
                title = "模式",
                options = Protocol.entries,
                selected = app.protocol,
                onSelect = { p -> settings.update { it.copy(protocolName = p.name) } },
                label = { it.name },
            )
            PrefDivider()
            PrefStepper(
                title = "发射偏移",
                value = app.txOffsetMs,
                range = 0..15000,
                step = 100,
                unit = " ms",
                subtitle = "在一个时隙内后移发射起点",
                onChange = { v -> settings.update { it.copy(txOffsetMs = v) } },
                enabled = false,
                badge = "U7",
            )
            PrefDivider()
            PrefChoice(
                title = "解码深度",
                subtitle = "「快」省电、候选更少；「深」更慢但弱信号解码率更高。" +
                    "OSR 与频率范围需重启接收生效，其余参数即时生效。",
                options = buildList {
                    add(DecodePreset.FAST)
                    add(DecodePreset.STANDARD)
                    add(DecodePreset.DEEP)
                    if (app.decodePreset == DecodePreset.CUSTOM) add(DecodePreset.CUSTOM)
                },
                selected = app.decodePreset,
                onSelect = { p ->
                    if (p != DecodePreset.CUSTOM) {
                        settings.update { s -> s.copy(decode = s.decode.applyPreset(p), decodePreset = p) }
                    }
                },
                label = { it.label },
            )
            PrefDivider()
            PrefStepper(
                title = "时间 OSR",
                value = app.decode.timeOsr,
                range = DecodeSettings.TIME_OSR_RANGE,
                onChange = { v -> settings.updateDecode { it.copy(timeOsr = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "频率 OSR",
                value = app.decode.freqOsr,
                range = DecodeSettings.FREQ_OSR_RANGE,
                onChange = { v -> settings.updateDecode { it.copy(freqOsr = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "最低得分",
                value = app.decode.minScore,
                range = DecodeSettings.MIN_SCORE_RANGE,
                onChange = { v -> settings.updateDecode { it.copy(minScore = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "LDPC 迭代",
                value = app.decode.ldpcIterations,
                range = DecodeSettings.LDPC_RANGE,
                step = 5,
                onChange = { v -> settings.updateDecode { it.copy(ldpcIterations = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "候选上限",
                value = app.decode.maxCandidates,
                range = DecodeSettings.MAX_CANDIDATES_RANGE,
                step = 20,
                onChange = { v -> settings.updateDecode { it.copy(maxCandidates = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "单时隙上限",
                value = app.decode.maxDecoded,
                range = DecodeSettings.MAX_DECODED_RANGE,
                step = 5,
                onChange = { v -> settings.updateDecode { it.copy(maxDecoded = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "频率下限",
                value = app.decode.fMinHz,
                range = DecodeSettings.F_MIN_RANGE,
                step = 50,
                unit = " Hz",
                onChange = { v -> settings.updateDecode { it.copy(fMinHz = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "频率上限",
                value = app.decode.fMaxHz,
                range = DecodeSettings.F_MAX_RANGE,
                step = 50,
                unit = " Hz",
                onChange = { v -> settings.updateDecode { it.copy(fMaxHz = v) } },
            )
            PrefDivider()
            PrefSwitch(
                title = "Hold Tx Freq",
                subtitle = "开启后点解码行只改 RX、不跟随对方频率（split 场景）；关闭则「点谁打谁」。",
                checked = app.holdTxFreq,
                onCheckedChange = { v -> settings.update { s -> s.copy(holdTxFreq = v) } },
            )
            PrefDivider()
            PrefChoice(
                title = "Call 1st 自动应答",
                options = CallFirstMode.entries,
                selected = app.callFirst,
                onSelect = { m -> settings.update { s -> s.copy(callFirst = m) } },
                label = { it.label },
            )
            PrefDivider()
            PrefStepper(
                title = "最大重试次数",
                value = app.maxRetries,
                range = 1..20,
                unit = " 次",
                onChange = { v -> settings.update { s -> s.copy(maxRetries = v) } },
            )
            PrefDivider()
            PrefAction(
                title = "恢复默认",
                subtitle = "重置全部设置，保留呼号 / 网格 / 备注",
                buttonLabel = "恢复",
                onClick = { settings.resetToDefaults() },
            )
        }

        // ---------- 6.4 高亮与提醒 ----------
        SettingsGroup("高亮与提醒") {
            PrefNote("关闭某类后，该类不再抢占最高优先级色条；具体颜色见设计说明。")
            PrefSwitch(
                title = "新 CQ 区域",
                subtitle = "未通联过的 CQ 区域",
                checked = app.highlightNewCqZone,
                onCheckedChange = { v -> settings.update { it.copy(highlightNewCqZone = v) } },
            )
            PrefDivider()
            PrefSwitch(
                title = "新 ITU 区域",
                subtitle = "未通联过的 ITU 区域",
                checked = app.highlightNewItu,
                onCheckedChange = { v -> settings.update { it.copy(highlightNewItu = v) } },
            )
            PrefDivider()
            PrefSwitch(
                title = "新 DXCC",
                subtitle = "未通联过的 DXCC 实体（按呼号前缀映射）",
                checked = app.highlightNewEntity,
                onCheckedChange = { v -> settings.update { it.copy(highlightNewEntity = v) } },
            )
            PrefDivider()
            PrefSwitch(
                title = "新网格",
                checked = app.highlightNewGrid,
                onCheckedChange = { v -> settings.update { it.copy(highlightNewGrid = v) } },
            )
            PrefDivider()
            PrefSwitch(
                title = "新前缀",
                checked = app.highlightNewPrefix,
                onCheckedChange = { v -> settings.update { it.copy(highlightNewPrefix = v) } },
            )
            PrefDivider()
            PrefSwitch(
                title = "新呼号",
                checked = app.highlightNewCall,
                onCheckedChange = { v -> settings.update { it.copy(highlightNewCall = v) } },
            )
            PrefDivider()
            PrefChoice(
                title = "已通联",
                subtitle = "已通联呼号在解码列表中的呈现方式",
                options = WorkedStyle.entries,
                selected = app.workedStyle,
                onSelect = { v -> settings.update { it.copy(workedStyle = v) } },
                label = { it.label },
            )
            PrefDivider()
            PrefSwitch(
                title = "含我呼号哔声",
                subtitle = "有报文直接叫我呼号时，用系统提示音提醒",
                checked = app.beepOnMyCall,
                onCheckedChange = { v -> settings.update { it.copy(beepOnMyCall = v) } },
            )
            PrefDivider()
            PrefSwitch(
                title = "末端标记：红=有我",
                checked = app.endMarkMyCall,
                onCheckedChange = { v -> settings.update { it.copy(endMarkMyCall = v) } },
            )
            PrefDivider()
            PrefSwitch(
                title = "末端标记：蓝=正通联",
                checked = app.endMarkActive,
                onCheckedChange = { v -> settings.update { it.copy(endMarkActive = v) } },
            )
        }

        // ---------- 6.5 外观 ----------
        SettingsGroup("外观") {
            PrefChoice(
                title = "主题",
                options = ThemeMode.entries,
                selected = app.themeMode,
                onSelect = { v -> settings.update { it.copy(themeMode = v) } },
                label = { it.label },
            )
            PrefDivider()
            PrefChoice(
                title = "字体",
                options = FontSize.entries,
                selected = app.fontSize,
                onSelect = { v -> settings.update { it.copy(fontSize = v) } },
                label = { it.label },
            )
            PrefDivider()
            PrefChoice(
                title = "瀑布配色",
                subtitle = "渐变在 native 生成，切换依赖 U7",
                options = WaterfallPalette.entries,
                selected = app.waterfallPalette,
                onSelect = { v -> settings.update { it.copy(waterfallPalette = v) } },
                label = { it.label },
                enabled = false,
                badge = "U7",
            )
            PrefDivider()
            PrefChoice(
                title = "瀑布高度",
                subtitle = "改动回到操作页立即生效（占用解码列表的可视高度）",
                options = WaterfallHeight.entries,
                selected = app.waterfallHeight,
                onSelect = { v -> settings.update { it.copy(waterfallHeight = v) } },
                label = { it.label },
            )
            PrefDivider()
            PrefAction(
                title = "恢复布局",
                subtitle = "瀑布高度 / 字体 / 瀑布配色回到默认",
                buttonLabel = "恢复",
                onClick = {
                    settings.update {
                        it.copy(
                            waterfallHeight = WaterfallHeight.NORMAL,
                            fontSize = FontSize.MEDIUM,
                            waterfallPalette = WaterfallPalette.CLASSIC,
                        )
                    }
                },
            )
        }

        // ---------- 6.6 日志 / 网络 ----------
        SettingsGroup("日志与网络") {
            PrefInfo(
                title = "ADIF 路径",
                subtitle = "通过系统文件选择器（SAF）导入 / 导出，不需要存储权限",
            )
            PrefDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                AdifActionRow(
                    log = log,
                    myCall = app.myCall,
                    myGrid = app.myGrid.ifEmpty { null },
                    onStatus = { statusText = it },
                )
                statusText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            PrefDivider()
            PrefAction(
                title = "清空全部记录",
                subtitle = "共 ${entries.size} 条，删除后不可撤销",
                buttonLabel = "清空",
                onClick = { confirmClear = true },
            )
            PrefDivider()
            PrefSwitch(
                title = "CloudLog 上传",
                subtitle = "需要服务器地址与 API Key",
                checked = app.cloudLogEnabled,
                onCheckedChange = { v -> settings.update { it.copy(cloudLogEnabled = v) } },
                enabled = false,
                badge = "U7",
            )
            PrefDivider()
            PrefSwitch(
                title = "LoTW 上传",
                subtitle = "需要 TQSL 凭据",
                checked = app.lotwEnabled,
                onCheckedChange = { v -> settings.update { it.copy(lotwEnabled = v) } },
                enabled = false,
                badge = "U7",
            )
            PrefDivider()
            PrefSwitch(
                title = "eQSL 上传",
                subtitle = "需要账号凭据",
                checked = app.eqslEnabled,
                onCheckedChange = { v -> settings.update { it.copy(eqslEnabled = v) } },
                enabled = false,
                badge = "U7",
            )
            PrefDivider()
            PrefSwitch(
                title = "局域网后台",
                subtitle = "需要前台服务常驻（阶段 9）",
                checked = app.lanServerEnabled,
                onCheckedChange = { v -> settings.update { it.copy(lanServerEnabled = v) } },
                enabled = false,
                badge = "U7",
            )
        }

        // ---------- 关于 ----------
        SettingsGroup("关于") {
            val version = rememberAppVersion()
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("Ft8Vox $version", style = MaterialTheme.typography.bodySmall)
                Text("许可：GPL-3.0", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "解码内核基于 ft8_lib。发射前请确认符合所在地区的无线电管理法规，并对发射行为负责。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    if (bandDialog) {
        BandFreqDialog(
            currentBand = app.band,
            currentHz = app.resolvedDialHz,
            onConfirm = { name, hz ->
                bandDialog = false
                settings.update { it.copy(band = name, dialHz = hz) }
            },
            onDismiss = { bandDialog = false },
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

/** 设置分组：标题 + 圆角卡片。 */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                content = content,
            )
        }
    }
}

/** 组内分隔线（`outlineVariant` 淡化）。 */
@Composable
private fun PrefDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** 「需要 U7」小徽标。 */
@Composable
private fun U7Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** Preference 行：左侧标题/副标题，右侧可选控件。 */
@Composable
private fun PrefRow(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    U7Badge(badge)
                }
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 组内纯说明行。 */
@Composable
private fun PrefInfo(title: String, subtitle: String? = null, badge: String? = null) {
    PrefRow(title, subtitle, badge)
}

/** 组内小字说明。 */
@Composable
private fun PrefNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** 开关行。 */
@Composable
private fun PrefSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    badge: String? = null,
) {
    PrefRow(title, subtitle, badge, trailing = {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    })
}

/** 单选（FilterChip）行：标题在上，选项可横向滚动。 */
@Composable
private fun <T> PrefChoice(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    subtitle: String? = null,
    enabled: Boolean = true,
    badge: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                U7Badge(badge)
            }
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (o in options) {
                FilterChip(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    enabled = enabled,
                    label = { Text(label(o)) },
                )
            }
        }
    }
}

/** 下拉选择行。 */
@Composable
private fun <T> PrefDropdown(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    subtitle: String? = null,
    enabled: Boolean = true,
    badge: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    PrefRow(title, subtitle, badge, trailing = {
        Box {
            TextButton(onClick = { open = true }, enabled = enabled) {
                Text("${label(selected)} ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (o in options) {
                    DropdownMenuItem(
                        text = { Text(label(o)) },
                        onClick = {
                            open = false
                            onSelect(o)
                        },
                    )
                }
            }
        }
    })
}

/** 数值步进行：−/值/+，触摸目标 48dp。 */
@Composable
private fun PrefStepper(
    title: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    step: Int = 1,
    unit: String = "",
    subtitle: String? = null,
    enabled: Boolean = true,
    badge: String? = null,
) {
    PrefRow(title, subtitle, badge, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onChange((value - step).coerceAtLeast(range.first)) },
                enabled = enabled && value > range.first,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(48.dp),
            ) { Text("−") }
            Text(
                "$value$unit",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 62.dp),
            )
            OutlinedButton(
                onClick = { onChange((value + step).coerceAtMost(range.last)) },
                enabled = enabled && value < range.last,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(48.dp),
            ) { Text("+") }
        }
    })
}

/** 文本输入行。 */
@Composable
private fun PrefText(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    supporting: String,
    isError: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = isError,
            supportingText = { Text(supporting) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 动作行：标题 + 右侧按钮。 */
@Composable
private fun PrefAction(
    title: String,
    buttonLabel: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    badge: String? = null,
) {
    PrefRow(title, subtitle, badge, trailing = {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(buttonLabel) }
    })
}

/**
 * VOX 输入电平条（6.1「测试音」下方）。
 *
 * 电平来自 native 对输入音频的估算，-60 dB 为满格基准；触发时条色用强调色。
 */
@Composable
private fun VoxLevelRow(status: ReceiverStatus) {
    val run = status.running
    val db = status.voxLevelDb
    val label = when {
        !run -> "未运行"
        db <= -99.5f -> "--"
        else -> "${db.toInt()} dB"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp)) {
        Text(
            "VOX 电平  $label${if (run && status.voxOpen) "  （触发）" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = if (run && status.voxOpen) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val frac = if (run) ((db + 60f) / 60f).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (frac > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (status.voxOpen) MaterialTheme.colorScheme.primary else VoxRxGreen),
                )
            }
        }
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
