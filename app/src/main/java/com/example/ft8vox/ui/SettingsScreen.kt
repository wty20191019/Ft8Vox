package com.example.ft8vox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.data.settings.DecodePreset
import com.example.ft8vox.data.settings.DecodeSettings
import com.example.ft8vox.data.settings.OUTPUT_GAIN_MAX_DB
import com.example.ft8vox.data.settings.OUTPUT_GAIN_MIN_DB
import com.example.ft8vox.data.settings.SLOT_OFFSET_LIMIT_MS
import com.example.ft8vox.data.settings.SampleRatePref
import com.example.ft8vox.data.settings.WATERFALL_FLOOR_DB_RANGE
import com.example.ft8vox.data.settings.WATERFALL_RANGE_DB_RANGE
import com.example.ft8vox.data.settings.clampWaterfallFloorDb
import com.example.ft8vox.data.settings.clampWaterfallRangeDb
import com.example.ft8vox.engine.AudioDevices
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.engine.decodeParallelism
import com.example.ft8vox.grid.Maidenhead
import com.example.ft8vox.ui.theme.BarWorked
import com.example.ft8vox.ui.theme.HlCq
import com.example.ft8vox.ui.theme.HlTx
import com.example.ft8vox.ui.theme.MapLinkColor
import com.example.ft8vox.ui.theme.MapLinkMine
import com.example.ft8vox.ui.theme.VoxRxGreen
import com.example.ft8vox.ui.theme.WorkedOtherBandBlue

/**
 * 设置页（安卓 Preference 风格，docs/Ft8Vox.md）。
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

    // 这几个状态**只在本层持有、不在本层读**：读数下沉到真正需要它的叶子组合（见文末 `*Row` / `*Block`）。
    // 接收中状态约 5 次/秒发布一次，导入进度、输入框每敲一个字也都会更新它们 ——
    // 在本层读就会连着整页 40 行一起重组（Compose 的「跳过未变化子树」对 ViewModel / AppSettings
    // 这类不稳定参数无效）。
    val statusText = remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var bandDialog by remember { mutableStateOf(false) }

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

        // ---------- 台站（设计外补充：无 CAT，台站信息必填） ----------
        item(key = "station") {
            SettingsGroup("台站") {
                MyCallField(settings, app.myCall)
                PrefDivider()
                MyGridField(settings, app.myGrid)
                PrefDivider()
                PrefAction(
                    title = "波段与频率",
                    subtitle = "无 CAT，需人工指定；当前：${app.band} · " +
                        String.format(java.util.Locale.US, "%.4f MHz", app.resolvedDialHz / 1_000_000.0),
                    buttonLabel = "选择",
                    onClick = { bandDialog = true },
                )
                PrefDivider()
                NoteField(settings, app.note)
                PrefDivider()
                ManualCallBlock(session = session, myCall = app.myCall)
            }
        }

        // ---------- 6.1 电台（仅 VOX） ----------
        item(key = "radio") {
            SettingsGroup("电台（仅 VOX）") {
                PrefNote(
                    "无 CAT：App 无法直接控制 PTT —— 键控只能靠 FT8 波形本身（WSJT-X 约定：" +
                        "波形在时隙起点后 0.5 s 才开始，那之前是静音）。「前导时长」再往前垫一段" +
                        "等长静音：它不出声、不会提前键控电台，作用是让播放流提前起流，" +
                        "避免起流延迟削掉报文起点。"
                )
                PrefStepper(
                    title = "前导时长",
                    subtitle = "发射前垫一段等长静音（不出声）：让播放流提前起流、不削报文起点；不会推后报文",
                    value = app.txPreambleMs,
                    range = 0..2000,
                    step = 50,
                    unit = " ms",
                    onChange = { v -> settings.update { it.copy(txPreambleMs = v) } },
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
                TestToneRow(session)
                VoxLevelRow(session)
            }
        }

        // ---------- 6.2 音频 ----------
        item(key = "audio") {
            SettingsGroup("音频") {
                // 音频 / VOX 速览（从信息头收进来：实机太挤；docs/Ft8Vox.md）
                AudioQuickPanelBlock(session, app)
                PrefDivider()
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
        }

        // ---------- 6.3 FT8 ----------
        item(key = "ft8") {
            // 频率范围那一半在这里算：只有 FT8 组跟着「频率范围」重组
            val decodeParallel = remember(app.decode.fMinHz, app.decode.fMaxHz, decodeCores) {
                decodeParallelism(app.decode.fMinHz, app.decode.fMaxHz, decodeCores)
            }
            SettingsGroup("FT8") {
                PrefChoice(
                    title = "模式",
                    subtitle = "当前仅支持 FT8（时隙 15 s）。",
                    options = Protocol.entries,
                    selected = app.protocol,
                    onSelect = { p -> settings.update { it.copy(protocolName = p.name) } },
                    label = { it.name },
                )
                PrefDivider()
                PrefOffsetInput(
                    title = "时隙偏移",
                    value = app.slotOffsetMs,
                    range = -SLOT_OFFSET_LIMIT_MS..SLOT_OFFSET_LIMIT_MS,
                    step = 100,
                    unit = " ms",
                    subtitle = "整个时隙一起偏移（解码窗口 + 发射起点），用于校准本机时间/声卡时延：" +
                        "把操作页解码卡片的「时间差」原样填进来（+1.5s 就填 +1500 ms，负值照填），" +
                        "校到时间差约 0 即可。正值 = 推后，负值 = 提前（FT8 可到 ±2.5 s）。" +
                        "改完最迟一个时隙内生效，无需重开接收。" +
                        "点中间的数字可直接输入（只收整数毫秒；回车 / ✓ 才生效，± 一键翻正负）。",
                    onChange = { v -> settings.update { it.copy(slotOffsetMs = v) } },
                )
                PrefDivider()
                PrefChoice(
                    title = "解码深度",
                    subtitle = "预设「快」＝下面的默认值。" +
                        "想解得更全就逐项调大（LDPC 迭代 / 候选上限 / 单时隙上限），改任一项会显示「自定义」；" +
                        "「快」按钮可一键恢复上面 7 项的默认值（频率范围不随预设变化）。" +
                        "时间/频率 OSR、频率范围需重开接收生效，其余即时生效。",
                    options = buildList {
                        add(DecodePreset.FAST)
                        if (app.decodePreset == DecodePreset.CUSTOM) add(DecodePreset.CUSTOM)
                    },
                    selected = app.decodePreset,
                    onSelect = { p ->
                        if (p != DecodePreset.CUSTOM) {
                            settings.update { s -> s.copy(decode = s.decode.applyPreset(p)) }
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
                    subtitle = "「整个频谱」的同步候选名额上限（20–500）：名额属于整个频谱，不属于任何一段。" +
                        "调高：给弱信号更多机会，更慢；调低：名额先被强信号占满、弱台漏解。" +
                        (if (app.decode.globalCandidates)
                            "当前「全局择优」：各段先各取一个宽松候选池，汇总跨段去重后按同步得分" +
                                "全局排序取前 ${app.decode.maxCandidates} 个，再分发回各段解码。" +
                                "某段再拥挤也不会挤掉别的段分数更高的弱台。"
                        else
                            "当前「按段均分」：${decodeParallel.bands} 段 → 每段约 " +
                                "${decodeParallel.candidatesPerBand(app.decode.maxCandidates)} 个，" +
                                "某段配额用尽就不再收了。") +
                        "即时生效。",
                    onChange = { v -> settings.updateDecode { it.copy(maxCandidates = v) } },
                )
                PrefDivider()
                PrefSwitch(
                    title = "候选全局择优",
                    subtitle = "关（默认）：候选名额「按段均分」（总名额 ÷ 段数）—— 各段负载天然均衡、" +
                        "耗时更稳，这也是加这个开关之前的行为。" +
                        "开：名额改为按「全频谱全局择优」分配 —— 各段先各取宽松池，" +
                        "汇总后跨段去重（同一信号最多留 3 个代表，100 Hz 重叠区被两段各搜一次的重复也折掉），" +
                        "再按同步得分全局排序取前 N 个，每个减谱趟都重做。" +
                        "名额越小、频段越拥挤，多解出的弱台越多（语料实测 20 名额 +2.1pp，" +
                        "默认 120 名额仅 +0.4pp），代价是段间负载不均、耗时约 ×1.8。",
                    checked = app.decode.globalCandidates,
                    onCheckedChange = { v -> settings.updateDecode { it.copy(globalCandidates = v) } },
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
                    title = "减谱重解趟数",
                    value = app.decode.passes,
                    range = DecodeSettings.PASSES_RANGE,
                    subtitle = "SIC（多趟减谱重解）趟数（1–4）。解完一趟后把已解出的报文从瀑布幅度上" +
                        "抹掉，在残留谱上再搜一趟，把被强台压住的同频弱信号挖出来。" +
                        "1＝关（单趟，最快）；2＝默认（离线 60 个官方音频 956→989 条，+3.5%，零回归）；" +
                        "再往上增益很小、耗时近似成倍。即时生效。",
                    onChange = { v -> settings.updateDecode { it.copy(passes = v) } },
                )
                PrefDivider()
                PrefStepper(
                    title = "频率下限",
                    value = app.decode.fMinHz,
                    range = DecodeSettings.F_MIN_RANGE,
                    step = 50,
                    unit = " Hz",
                    subtitle = "解码搜索与瀑布显示的下边界（默认 100 Hz，即 SSB 通带低端）。" +
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
                PrefInfo(
                    title = "解码并行",
                    subtitle = "按 CPU 核数（$decodeCores）自动分配、没有设置项：频率分段数 × 每段线程数。" +
                        "段数只决定并行度与各段自己的 STFT/瀑布，候选名额默认按段均分" +
                        "（打开「候选全局择优」才改由全频谱统一分配）；频率范围收窄会自动减少段数" +
                        "（单段最小 200 Hz，窄到 400 Hz 以内就不分段了）。",
                    badge = decodeParallel.label,
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
                    onOption = { f -> settings.update { s -> s.copy(auto = f(s.auto)) } },
                    // 设置页本身可滚动：面板完全展开、跟随整页滚动，不再套一层内部滚动
                    nestedScroll = false,
                )
                PrefNote(
                    "自动程序没有独立开关：打开「发送总开关」即自动发射。" +
                        "第 1 层（QSO 引擎）跑六步报文序列；第 2 层负责选台、应答与发射监管。",
                )
                PrefDivider()
                PrefAction(
                    title = "恢复默认",
                    subtitle = "重置全部设置，保留呼号 / 网格 / 备注",
                    buttonLabel = "恢复",
                    onClick = { settings.resetToDefaults() },
                )
            }
        }

        // ---------- 6.4 瀑布显示（固定阈值，可调；频谱页也可直接调） ----------
        item(key = "waterfall") {
            SettingsGroup("瀑布") {
                PrefNote(
                    "瀑布强度按固定窗口映射到色带：低于噪声底＝深蓝，达到「底噪 + 动态范围」＝红，" +
                        "不再随信号自适应。频谱页顶部同样可以直接调这两项。"
                )
                PrefDivider()
                PrefStepper(
                    title = "噪声底",
                    value = app.waterfallFloorDb,
                    range = WATERFALL_FLOOR_DB_RANGE,
                    step = 5,
                    unit = " dBFS",
                    subtitle = "低于此值的 bin 画成最深蓝（默认 -90 dBFS）。调高：噪声被压黑、信号对比更强。",
                    onChange = { v -> settings.update { it.copy(waterfallFloorDb = clampWaterfallFloorDb(v)) } },
                )
                PrefDivider()
                PrefStepper(
                    title = "动态范围",
                    value = app.waterfallRangeDb,
                    range = WATERFALL_RANGE_DB_RANGE,
                    step = 5,
                    unit = " dB",
                    subtitle = "色带跨度（默认 50 dB）：窗口顶端 = 噪声底 + 本值，达到即画成红。" +
                        "调大：弱信号更可见，但底色纹理更明显。",
                    onChange = { v -> settings.update { it.copy(waterfallRangeDb = clampWaterfallRangeDb(v)) } },
                )
            }
        }

        // ---------- 6.5 高亮与提醒（docs/Ft8Vox.md：颜色恒启用，开关全部取消） ----------
        item(key = "highlight") {
            SettingsGroup("高亮与提醒") {
                PrefNote(
                    "所有颜色都已固定启用，不再需要逐个开关。每行只有一条色卡（整行底色），取命中的" +
                        "最高优先级：正在发射 > 与我有关/当前对手 > CQ > 已通联 > 重复 > 新网格 > " +
                        "新 DXCC/ITU/CQ 区域/新前缀 > 新呼号 > 其余新解码。行尾小圆点同样恒开。下方列出每种颜色的含义。",
                )
                HighlightLegend()
                PrefDivider()
                PrefSwitch(
                    title = "含我呼号哔声",
                    subtitle = "有报文直接叫我呼号时，用系统提示音提醒",
                    checked = app.beepOnMyCall,
                    onCheckedChange = { v -> settings.update { it.copy(beepOnMyCall = v) } },
                )
                PrefDivider()
            }
        }

        // ---------- 6.6 外观：已随新竖屏外壳去掉（docs/Ft8Vox.md：无亮/暗主题、无字体档位） ----------

        // ---------- 6.7 日志 ----------
        item(key = "log") {
            SettingsGroup("日志") {
                PrefInfo(
                    title = "ADIF 路径",
                    subtitle = "通过系统文件选择器（SAF）导入 / 导出，不需要存储权限",
                )
                PrefDivider()
                AdifBlock(log, app.myCall, app.myGrid.ifEmpty { null }, statusText)
                PrefDivider()
                ClearAllRow(log, onClear = { confirmClear = true })
            }
        }

        // ---------- 6.8 地图（原地图页浮层的三个显示开关，收进设置；docs/Ft8Vox.md） ----------
        item(key = "map") {
            SettingsGroup("地图") {
                PrefSwitch(
                    title = "CQ 旗帜显示呼号",
                    checked = app.mapCqFlagShowCall,
                    onCheckedChange = { v -> settings.update { it.copy(mapCqFlagShowCall = v) } },
                )
                PrefDivider()
                PrefSwitch(
                    title = "CQ 旗帜显示强度",
                    checked = app.mapCqFlagShowSnr,
                    onCheckedChange = { v -> settings.update { it.copy(mapCqFlagShowSnr = v) } },
                )
                PrefDivider()
                PrefSwitch(
                    title = "连线显示文字",
                    checked = app.mapShowLinkText,
                    onCheckedChange = { v -> settings.update { it.copy(mapShowLinkText = v) } },
                )
            }
        }

        // ---------- 关于 ----------
        item(key = "about") {
            SettingsGroup("关于") {
                PrefAction(
                    title = "操作与手势",
                    subtitle = "解码行点按 / 长按 / 左右滑；频谱拖红线设发射频率；报文槽点击发射等",
                    buttonLabel = "查看",
                    onClick = onHelp,
                )
                PrefDivider()
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
        }

        item(key = "tail") { Spacer(Modifier.height(12.dp)) }
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
        ClearLogDialog(
            log = log,
            statusText = statusText,
            onDismiss = { confirmClear = false },
        )
    }
}

// ---- 设置页里「读运行时状态」的几个小组件 ----
//
// 它们各自订阅 SessionViewModel.status / LogViewModel.stats，把重组范围限制在自己这一小块：
// 接收中状态约 5 次/秒发布一次、导入进度连续刷新，若在 SettingsScreen 那一层读，
// 整页 40 行会跟着一起重组（Compose 的「跳过未变化子树」对 ViewModel / AppSettings 参数无效）。

/** 呼号输入行：本地文本状态留在**行内**，敲字只重组本行（同时解决 DataStore 异步回显吞字）。 */
@Composable
private fun MyCallField(settings: SettingsViewModel, initial: String) {
    var call by rememberSaveable { mutableStateOf(initial) }
    val callError = call.isEmpty()
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
}

/** 网格输入行：允许留空，但填了就必须合法（Maidenhead 2/4/6/8 位）。 */
@Composable
private fun MyGridField(settings: SettingsViewModel, initial: String) {
    var grid by rememberSaveable { mutableStateOf(initial) }
    val gridError = grid.isNotEmpty() && !Maidenhead.isValid(grid)
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
}

/** 备注输入行（本地文本状态同上）。 */
@Composable
private fun NoteField(settings: SettingsViewModel, initial: String) {
    var note by rememberSaveable { mutableStateOf(initial) }
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

/** 「测试音」行：只有它要显示当前发射频率，所以在这里订阅状态（只重组本行）。 */
@Composable
private fun TestToneRow(session: SessionViewModel) {
    val status by session.status.collectAsState()
    PrefAction(
        title = "测试音",
        subtitle = "发送 ${status.selectedFreqHz} Hz 单音（2 s），用于 VOX 键控与音量联调",
        buttonLabel = "播放",
        onClick = { session.playTestTone() },
    )
}

/** 「音频 / VOX 速览」：状态订阅收在这一块，别让它牵动整页。 */
@Composable
private fun AudioQuickPanelBlock(session: SessionViewModel, appSettings: AppSettings) {
    val status by session.status.collectAsState()
    AudioQuickPanel(status, appSettings)
}

/**
 * ADIF 导入 / 导出 + 状态文字。
 *
 * [statusText] 由设置页持有、**只在这里读**：导入进度「导入中 n/N」会连续刷新，
 * 读在上层会把整页一起重组。
 */
@Composable
private fun AdifBlock(
    log: LogViewModel,
    myCall: String,
    myGrid: String?,
    statusText: MutableState<String?>,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        AdifActionRow(
            log = log,
            myCall = myCall,
            myGrid = myGrid,
            onStatus = { statusText.value = it },
        )
        statusText.value?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 「清空全部记录」行：条数只在本行读（`log.stats` 是按整表算的，别让整表进设置页的组合状态）。 */
@Composable
private fun ClearAllRow(log: LogViewModel, onClear: () -> Unit) {
    val stats by log.stats.collectAsState()
    PrefAction(
        title = "清空全部记录",
        subtitle = "共 ${stats.total} 条，删除后不可撤销",
        buttonLabel = "清空",
        onClick = onClear,
    )
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
 * [dotColor] 非空时在**开关右侧**显示同色小圆点，标注该开关对应的高亮色（docs/Ft8Vox.md）。
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

/** 手动输入 DX 呼号 / 网格并立即呼叫（docs/Ft8Vox.md：从旧操作页控制行搬来）。 */
@Composable
private fun ManualCallBlock(session: SessionViewModel, myCall: String) {
    var dxCall by remember { mutableStateOf("") }
    var dxGrid by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = dxCall,
                onValueChange = { dxCall = it.trim().uppercase().take(12) },
                label = { Text("DX 呼号") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = dxGrid,
                onValueChange = { dxGrid = it.trim().uppercase().take(6) },
                label = { Text("DX 网格") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedButton(
            onClick = {
                session.answer(dxCall.trim().uppercase(), dxGrid.trim().uppercase().ifEmpty { null })
            },
            enabled = myCall.isNotBlank() && dxCall.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("呼叫") }
        Text(
            "手动输入呼号/网格并立即呼叫（竖屏操作页不放输入框，避免占高度）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
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
 * 「颜色说明」图例（docs/Ft8Vox.md）：列出每个颜色的含义。
 *
 * 这些颜色**全部固定启用**（原来的逐个开关已取消），所以这里既是说明、也是唯一的口径来源。
 */
@Composable
private fun HighlightLegend() {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        LegendSection("解码列表 · 整行底色（照 FT8CN）")
        LegendRow(HlCq, "橙底：CQ 呼叫")
        LegendRow(HlTx, "黄底：自己发的报文 / 正在发射的那条")

        LegendSection("解码列表 · 文字与标志")
        LegendRow(ToMeRed, "红字：与我有关（报文里有本台呼号，只标「报文」那一列）")
        LegendRow(TargetFrameRed, "红框：当前通联目标（DX）发出的报文行（CQ 时是橙底 + 红框）")
        LegendRow(WorkedOtherBandBlue, "蓝字：其它波段已通联过（本波段还没通过）")
        LegendRow(BarWorked, "红框字母 C / D / I：未通联过的 CQ / DXCC / ITU 分区")
        LegendRow(MaterialTheme.colorScheme.onSurface, "删除线：本波段已通联过（文字保持默认色）")
        LegendRow(Color(0xFF9AA0B5), "灰字弱化：重复解码（同一条报文又解到一次）")

        LegendSection("地图")
        LegendRow(MapLinkMine, "红线 / 红字：报文里有我的连线（我发出的 / 发给我的）")
        LegendRow(MapLinkColor, "蓝线：其它台站之间的连线")
    }
}

/** 图例里的小标题。 */
@Composable
private fun LegendSection(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
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

/**
 * 「时隙偏移」行：默认是 `−` / 数值 / `+` 步进，**点中间的数字就地输入**（只收整数毫秒）。
 *
 * 为什么不直接扩 [PrefStepper]：只有这一项需要输入 —— ±2500 ms 按 100 ms 一步，从 0 到
 * +1500 要按 15 次；单独实现就不会动到共用组件的另外 14 处调用。
 *
 * 输入是**提交式**（回车「完成」/ 点 `✓` / 输入框失焦），**绝不边输边写**：偏移一变就会触发
 * 采集网格热重排（`AudioEngine.setSlotOffsetMs` → `realignRequested`），输 `1500` 会连重排
 * 4 次（1 → 15 → 150 → 1500），那一格全废。非法输入（空、字母、`1.5`）提交时**不写盘**、
 * 直接退回原值（输入过程中 [JtdxTextField] 的 `isError` 会先红框提示）。
 *
 * **进编辑态时字段是空的、当前值只作占位符**：预填当前值会把光标停在末尾，接着打字就成了
 * `+1500` + `2000` = `+15002000` → 被夹到 +2500（一键校准设过值之后就是这条路），
 * 而 `BasicTextField` 只能收 `String`、没法把预填文本整体选中。
 *
 * `±` 按钮＝「用输进来的数（没输就是当前值）取反」并立即提交：数字键盘不一定给 `-` 键，
 * 这样任何输入法都能填负偏移。
 */
@Composable
private fun PrefOffsetInput(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    unit: String,
    subtitle: String?,
    onChange: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    // 进编辑态时 onFocusChanged 会先来一次 isFocused=false，用 focused 兜住，
    // 否则一进输入框就被"失焦"提交掉。
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // editing 是闸门：回车 / ✓ / 失焦谁先到谁生效，后面再来直接 return（不会重复写盘）
    fun commit() {
        if (!editing) return
        editing = false
        parseSlotOffsetInput(text)?.let(onChange)
    }

    LaunchedEffect(editing) {
        if (editing) focus.requestFocus()
    }

    PrefRow(title, subtitle, trailing = {
        if (editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        // 没输就用当前值，取反后立即落地
                        val cur = parseSlotOffsetInput(text) ?: value
                        editing = false
                        onChange((-cur).coerceIn(range.first, range.last))
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp),
                ) { Text("±") }
                Spacer(Modifier.width(4.dp))
                JtdxTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = slotOffsetInputText(value),
                    isError = text.isNotBlank() && parseSlotOffsetInput(text) == null,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .width(74.dp)
                        .focusRequester(focus)
                        .onFocusChanged { st ->
                            if (st.isFocused) {
                                focused = true
                            } else if (focused) {
                                focused = false
                                commit()
                            }
                        },
                )
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    onClick = { commit() },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp),
                ) { Text("✓") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onChange((value - step).coerceAtLeast(range.first)) },
                    enabled = value > range.first,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp),
                ) { Text("−") }
                Box(
                    modifier = Modifier
                        .widthIn(min = 62.dp)
                        .height(48.dp)
                        .clickable {
                            text = ""
                            focused = false
                            editing = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // 下划线 + 强调色 = 可点输入（其余数值项仍是纯步进）
                    Text(
                        slotOffsetInputText(value) + unit,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                }
                OutlinedButton(
                    onClick = { onChange((value + step).coerceAtMost(range.last)) },
                    enabled = value < range.last,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp),
                ) { Text("+") }
            }
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
private fun VoxLevelRow(session: SessionViewModel) {
    val status by session.status.collectAsState()
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

/** 改一项高级解码参数：自动钳制（预设显示由 `AppSettings.decodePreset` 反推，无需手工标记）。 */
private fun SettingsViewModel.updateDecode(transform: (DecodeSettings) -> DecodeSettings) {
    update { s -> s.copy(decode = transform(s.decode).clamped()) }
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
