package com.example.ft8vox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.settings.DecodePreset
import com.example.ft8vox.data.settings.DecodeSettings
import com.example.ft8vox.data.settings.FontSize
import com.example.ft8vox.data.settings.OUTPUT_GAIN_MAX_DB
import com.example.ft8vox.data.settings.OUTPUT_GAIN_MIN_DB
import com.example.ft8vox.data.settings.SLOT_OFFSET_LIMIT_MS
import com.example.ft8vox.data.settings.SampleRatePref
import com.example.ft8vox.data.settings.ThemeMode
import com.example.ft8vox.data.settings.WaterfallHeight
import com.example.ft8vox.data.settings.WorkedStyle
import com.example.ft8vox.engine.AudioDevices
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.grid.Maidenhead
import com.example.ft8vox.qso.AutoMode
import com.example.ft8vox.ui.theme.BarCq
import com.example.ft8vox.ui.theme.BarDuplicate
import com.example.ft8vox.ui.theme.BarNewCall
import com.example.ft8vox.ui.theme.BarNewDecode
import com.example.ft8vox.ui.theme.BarNewEntity
import com.example.ft8vox.ui.theme.BarNewGrid
import com.example.ft8vox.ui.theme.BarToMe
import com.example.ft8vox.ui.theme.BarTx
import com.example.ft8vox.ui.theme.BarWorked
import com.example.ft8vox.ui.theme.VoxError
import com.example.ft8vox.ui.theme.VoxRxGreen
/**
 * 设置页（安卓 Preference 风格，new_ui.md §6）。
 *
 * 分组：台站 / 电台（仅 VOX）/ 音频 / FT8 / 高亮与提醒 / 外观 / 日志 / 关于。
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
    // 待确认的「启用自动程序」模式（「0 手动模式」→ 1/2 时的防误发确认）
    var confirmAutoMode by remember { mutableStateOf<AutoMode?>(null) }

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
                supporting = "自动写入新通联记录的 COMMENT 字段（可留空）；命中后还会追加「Distance: xx km, QSO by Ft8Vox」",
            )
        }

        // ---------- 6.1 电台（仅 VOX） ----------
        SettingsGroup("电台（仅 VOX）") {
            PrefNote(
                "无 CAT：App 无法直接控制 PTT，只能靠「前导静音 + 前导音」让电台 VOX 抢先键控，" +
                    "再把 FT8 数据对准时隙起点。"
            )
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
            PrefStepper(
                title = "输出音量",
                subtitle = "发射音频的数字衰减：0 dB = 数字满幅，只能往小调" +
                    "（波形本身已是满幅，要更大声请调电台/声卡的音量）。" +
                    "对 FT8/FT4 报文与「测试音」都生效，热生效。",
                value = app.outputGainDb,
                range = OUTPUT_GAIN_MIN_DB..OUTPUT_GAIN_MAX_DB,
                unit = " dB",
                onChange = { v -> settings.update { it.copy(outputGainDb = v) } },
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
                subtitle = "对采集样本生效（含输入电平读数），热生效",
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
                subtitle = "FT8 时隙 15 s、FT4 时隙 7.5 s。运行中切换会自动重建引擎（接收短暂中断，" +
                    "发送总开关状态保留）；未接收时下次开始接收生效。",
                options = Protocol.entries,
                selected = app.protocol,
                onSelect = { p -> settings.update { it.copy(protocolName = p.name) } },
                label = { it.name },
            )
            PrefDivider()
            PrefStepper(
                title = "时隙偏移",
                value = app.slotOffsetMs,
                range = -SLOT_OFFSET_LIMIT_MS..SLOT_OFFSET_LIMIT_MS,
                step = 100,
                unit = " ms",
                signed = true,
                subtitle = "整个时隙一起偏移（解码窗口 + 发射起点），用于校准本机时间/声卡时延：" +
                    "把操作页解码卡片的「时间差」原样填进来（+1.5s 就填 +1500 ms，负值照填），" +
                    "校到时间差约 0 即可。正值 = 推后，负值 = 提前；FT8 可到 ±2.5s，" +
                    "FT4 的搜索窗只有 ±0.5s，偏移过大会解不出。",
                onChange = { v -> settings.update { it.copy(slotOffsetMs = v) } },
            )
            PrefDivider()
            PrefChoice(
                title = "解码深度",
                subtitle = "预设一键改下面 6 项（时间/频率 OSR、最低得分、LDPC 迭代、候选上限、" +
                    "单时隙上限）；频率范围不随预设变化。单项手改后显示「自定义」。" +
                    "OSR 与频率范围需重开接收生效，其余即时生效。",
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
                subtitle = "每个符号在时间上再细分的份数（1–4）。调大：DT 时间分辨率更细、" +
                    "弱信号同步更稳，计算量成倍上升。需重开接收生效。",
                onChange = { v -> settings.updateDecode { it.copy(timeOsr = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "频率 OSR",
                value = app.decode.freqOsr,
                range = DecodeSettings.FREQ_OSR_RANGE,
                subtitle = "每个 6.25 Hz 频率格再细分的份数（1–4）。调大：DF 频率估计更准、" +
                    "邻近信号更易分开，计算量成倍上升。需重开接收生效。",
                onChange = { v -> settings.updateDecode { it.copy(freqOsr = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "最低得分",
                value = app.decode.minScore,
                range = DecodeSettings.MIN_SCORE_RANGE,
                subtitle = "Costas 同步候选的最低得分（4–40）。调高：候选更少、解码更快，" +
                    "但更容易漏掉弱信号；调低则更全更慢。",
                onChange = { v -> settings.updateDecode { it.copy(minScore = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "LDPC 迭代",
                value = app.decode.ldpcIterations,
                range = DecodeSettings.LDPC_RANGE,
                step = 5,
                subtitle = "纠错码最大迭代次数（5–60）。调高：误码多的弱信号更可能解出来，" +
                    "更慢；调低解码更快但弱台可能解不出。",
                onChange = { v -> settings.updateDecode { it.copy(ldpcIterations = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "候选上限",
                value = app.decode.maxCandidates,
                range = DecodeSettings.MAX_CANDIDATES_RANGE,
                step = 20,
                subtitle = "单时隙保留的同步候选数量上限（20–500）。调高：给弱信号更多机会，" +
                    "更慢；调低时名额先被强台占满、弱台漏解。",
                onChange = { v -> settings.updateDecode { it.copy(maxCandidates = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "单时隙上限",
                value = app.decode.maxDecoded,
                range = DecodeSettings.MAX_DECODED_RANGE,
                step = 5,
                subtitle = "一个时隙最多输出的报文条数（5–100）。拥挤波段（如 20m 高峰）" +
                    "调大可避免已解出的报文被丢弃。",
                onChange = { v -> settings.updateDecode { it.copy(maxDecoded = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "频率下限",
                value = app.decode.fMinHz,
                range = DecodeSettings.F_MIN_RANGE,
                step = 50,
                unit = " Hz",
                subtitle = "解码搜索与瀑布显示的下边界（默认 200 Hz，即 SSB 通带低端）。" +
                    "需重开接收生效。",
                onChange = { v -> settings.updateDecode { it.copy(fMinHz = v) } },
            )
            PrefDivider()
            PrefStepper(
                title = "频率上限",
                value = app.decode.fMaxHz,
                range = DecodeSettings.F_MAX_RANGE,
                step = 50,
                unit = " Hz",
                subtitle = "解码搜索与瀑布显示的上边界（默认 3000 Hz）。范围越窄解码越快，" +
                    "但超出范围的信号不会被解出。需重开接收生效。",
                onChange = { v -> settings.updateDecode { it.copy(fMaxHz = v) } },
            )
            PrefDivider()
            PrefSwitch(
                title = "同频发射",
                subtitle = "开：选台时发射频率（瀑布红线）跟到对方频率（「点谁打谁」）。" +
                    "关：异频发射（split），发射固定在红线位置，选台不改红线。",
                checked = app.sameFreqTx,
                onCheckedChange = { v -> settings.update { s -> s.copy(sameFreqTx = v) } },
            )
            PrefDivider()
            Text(
                "自动程序",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            AutoProgramPanel(
                program = app.auto,
                onSetMode = { m -> requestAutoMode(m, sessionStatus, session) { confirmAutoMode = it } },
                onOption = { f -> settings.update { s -> s.copy(auto = f(s.auto)) } },
                // 设置页本身可滚动：面板完全展开、跟随整页滚动，不再套一层内部滚动
                nestedScroll = false,
            )
            PrefNote(
                "第 1 层（QSO 引擎）按「重发机制」对同一目标重发；" +
                    "第 2 层（自动程序）负责选台、排队、主叫/混合模式切换与保护限制。",
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
            PrefNote(
                "每行只有一条色条，取命中的最高优先级：正在发射 > 与我有关/当前对手 > CQ > 已通联 > " +
                    "重复 > 新网格 > 新 DXCC/ITU/CQ 区域/新前缀 > 新呼号 > 其余新解码（绿）。" +
                    "关闭某类后它不再参与竞争，行尾圆点也随之消失。开关右侧圆点 = 该类对应的色条颜色。",
            )
            PrefSwitch(
                title = "新 CQ 区域",
                subtitle = "未通联过的 CQ 区域",
                checked = app.highlightNewCqZone,
                onCheckedChange = { v -> settings.update { it.copy(highlightNewCqZone = v) } },
                dotColor = BarNewEntity,
            )
            PrefDivider()
            PrefSwitch(
                title = "新 ITU 区域",
                subtitle = "未通联过的 ITU 区域",
                checked = app.highlightNewItu,
                onCheckedChange = { v -> settings.update { it.copy(highlightNewItu = v) } },
                dotColor = BarNewEntity,
            )
            PrefDivider()
            PrefSwitch(
                title = "新 DXCC",
                subtitle = "未通联过的 DXCC 实体（按呼号前缀映射）",
                checked = app.highlightNewEntity,
                onCheckedChange = { v -> settings.update { it.copy(highlightNewEntity = v) } },
                dotColor = BarNewEntity,
            )
            PrefDivider()
            PrefSwitch(
                title = "新网格",
                checked = app.highlightNewGrid,
                onCheckedChange = { v -> settings.update { it.copy(highlightNewGrid = v) } },
                dotColor = BarNewGrid,
            )
            PrefDivider()
            PrefSwitch(
                title = "新前缀",
                checked = app.highlightNewPrefix,
                onCheckedChange = { v -> settings.update { it.copy(highlightNewPrefix = v) } },
                dotColor = BarNewEntity,
            )
            PrefDivider()
            PrefSwitch(
                title = "新呼号",
                checked = app.highlightNewCall,
                onCheckedChange = { v -> settings.update { it.copy(highlightNewCall = v) } },
                dotColor = BarNewCall,
            )
            PrefDivider()
            PrefChoice(
                title = "已通联",
                subtitle = "已通联呼号在解码列表中的呈现方式（红条 + 删除线/下划线/隐藏）",
                options = WorkedStyle.entries,
                selected = app.workedStyle,
                onSelect = { v -> settings.update { it.copy(workedStyle = v) } },
                label = { it.label },
                dotColor = BarWorked,
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
                dotColor = VoxError,
            )
            PrefDivider()
            PrefSwitch(
                title = "末端标记：蓝=正通联",
                checked = app.endMarkActive,
                onCheckedChange = { v -> settings.update { it.copy(endMarkActive = v) } },
                dotColor = MaterialTheme.colorScheme.primary,
            )
            PrefDivider()
            HighlightLegend()
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
                subtitle = "瀑布高度 / 字体回到默认",
                buttonLabel = "恢复",
                onClick = {
                    settings.update {
                        it.copy(
                            waterfallHeight = WaterfallHeight.NORMAL,
                            fontSize = FontSize.MEDIUM,
                        )
                    }
                },
            )
        }

        // ---------- 6.6 日志 ----------
        SettingsGroup("日志") {
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

    confirmAutoMode?.let { m ->
        AutoEnableConfirmDialog(
            mode = m,
            status = sessionStatus,
            onConfirm = {
                confirmAutoMode = null
                session.setAutoMode(m)
            },
            onDismiss = { confirmAutoMode = null },
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

/**
 * 开关行。
 *
 * [dotColor] 非空时在**开关右侧**显示同色小圆点，标注该开关对应的高亮色（new_ui.md §3.3）。
 */
@Composable
private fun PrefSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    badge: String? = null,
    dotColor: Color? = null,
) {
    PrefRow(title, subtitle, badge, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            if (dotColor != null) {
                Spacer(Modifier.width(8.dp))
                ColorDot(dotColor)
            }
        }
    })
}

/** 高亮色小圆点（带一圈淡描边，亮色主题下也看得清）。 */
@Composable
private fun ColorDot(color: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape),
    )
}

/**
 * 固定高亮色图例：这些颜色**不受**「高亮与提醒」里的开关控制，恒生效（new_ui.md §3.3）。
 */
@Composable
private fun HighlightLegend() {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "固定颜色（不受上面开关影响）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LegendRow(BarTx, "正在发射（整行黄底黑字）")
        LegendRow(BarToMe, "与我有关 / 当前 QSO 对手（呼号同时显示为红字）")
        LegendRow(BarCq, "CQ")
        LegendRow(BarDuplicate, "重复解码（同一文本在更早时隙出现过；整行变淡）")
        LegendRow(BarNewDecode, "其余新解码（兜底）")
    }
}

/** 图例中的一行：色点 + 说明。 */
@Composable
private fun LegendRow(color: Color, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(color)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
    /** 非空时在标题行右端显示同色小圆点。 */
    dotColor: Color? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                U7Badge(badge)
            }
            if (dotColor != null) {
                Spacer(Modifier.weight(1f))
                ColorDot(dotColor)
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
    /** 显示正号（用于可正可负的偏移量，如 `+1500 ms`）。 */
    signed: Boolean = false,
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
                if (signed && value >= 0) "+$value$unit" else "$value$unit",
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
 * 输入电平条（6.1「测试音」下方）。
 *
 * 电平来自 native 对输入音频的估算，-60 dB 为满格基准，**纯显示用**
 * （观察输入、校准 6.2 的「输入增益」）；不做任何触发判定。
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
            "输入电平  $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        .background(VoxRxGreen),
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
