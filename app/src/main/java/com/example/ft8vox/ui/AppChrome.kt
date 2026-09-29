package com.example.ft8vox.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.data.settings.FontSize
import com.example.ft8vox.data.settings.ThemeMode
import com.example.ft8vox.engine.AudioDevices
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.qso.TxCompose
import com.example.ft8vox.ui.theme.JtdxBorder
import com.example.ft8vox.ui.theme.JtdxGreen
import com.example.ft8vox.ui.theme.JtdxPanel
import com.example.ft8vox.ui.theme.JtdxPanelHi
import com.example.ft8vox.ui.theme.JtdxValue
import com.example.ft8vox.ui.theme.VoxError
import com.example.ft8vox.ui.theme.VoxRxGreen
import com.example.ft8vox.ui.theme.VoxTxRed
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * JTDX 风格外壳（docs/UI-JTDX.md §2）：菜单栏 / 信息头 / 控制行 / 底部状态条。
 *
 * 保留原有工具函数（[rememberUtcNowMs] / [decodesPerMinute] / [timeSyncWarning] /
 * [decodeTimeLabel] / [voxLabel] / [BandFreqDialog]），供页面与单测继续使用。
 */

/** docs/UI.md §2.1：秒级 UTC 时钟（对齐到整秒刷新）。 */
@Composable
fun rememberUtcNowMs(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val t = System.currentTimeMillis()
            now = t
            delay((1000L - (t % 1000L)).coerceAtLeast(1L))
        }
    }
    return now
}

/** 最近 60 秒内的解码条数（状态条「解码 x/min」）。 */
fun decodesPerMinute(messages: List<DecodeResult>, nowMs: Long): Int =
    messages.count { it.slotUtcMs > 0 && it.slotUtcMs >= nowMs - 60_000L }

/** 时间偏差告警：最近一条解码的 DT 绝对值超阈值时返回红字文案，否则 null。 */
fun timeSyncWarning(latestDt: Float?): String? =
    if (latestDt != null && abs(latestDt) >= 1.5f) "时间不同步" else null

/**
 * 信息头「解码耗时」文案（`解码 118ms`）。纯显示用：调「解码深度」时看计算量代价。
 * 未开始接收或还没解码过（0）时返回 null（不占位）。
 */
fun decodeTimeLabel(lastDecodeMs: Long, running: Boolean): String? =
    if (running && lastDecodeMs > 0L) "解码 ${lastDecodeMs}ms" else null

/** 状态条「电平」文案：输入电平（dBFS）。 */
internal fun voxLabel(levelDb: Float, running: Boolean): String {
    if (!running) return "电平 --"
    val lv = if (levelDb <= -99.5f) "--" else "${levelDb.toInt()} dB"
    return "电平 $lv"
}

/** 时隙偏移文案（带正负号，`+0.0 秒`）。 */
internal fun slotOffsetLabel(ms: Int): String =
    String.format(Locale.US, "%+.1f 秒", ms / 1000.0)

/** RX/TX 圆点；TX 时红色脉动。 */
@Composable
fun TxRxDot(txing: Boolean, running: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "txDot")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "txAlpha",
    )
    val color = when {
        txing -> VoxTxRed
        running -> VoxRxGreen
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (txing) pulse else 1f)),
    )
}

// ---------------------------------------------------------------- 菜单栏

/** 一个菜单按钮 + 它自己的下拉菜单；[content] 收到 `close()` 以便点完即关。 */
@Composable
private fun MenuButton(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .clickable { open = true }
                .background(if (open) JtdxBorder else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
        }
    }
}

/** 菜单里一个「● 当前项 / 　其他项」样式的条目。 */
@Composable
private fun MenuChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(if (selected) "● $label" else "　$label") },
        onClick = onClick,
    )
}

/**
 * JTDX 风格菜单栏（docs/UI-JTDX.md §5）：只放本项目有效项。
 *
 * 文件（页面切换）/ 显示（主题·字体·瀑布高度）/ 模式 / 解码 / 自动程序 / 设置 / 帮助。
 */
@Composable
fun JtdxMenuBar(
    status: ReceiverStatus,
    appSettings: AppSettings,
    onTab: (MainTab) -> Unit,
    onProtocol: (Protocol) -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onAutoProgram: () -> Unit,
    onClearDecodes: () -> Unit,
    onOpenFilter: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(JtdxPanelHi)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MenuButton("文件") { close ->
            Text(
                "页面",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            for (t in MainTab.entries) {
                DropdownMenuItem(
                    text = { Text(t.label) },
                    onClick = {
                        close()
                        onTab(t)
                    },
                )
            }
        }
        MenuButton("显示") { close ->
            Text(
                "外观",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            for (m in ThemeMode.entries) {
                MenuChoice(m.label, appSettings.themeMode == m) {
                    close()
                    onUpdateSettings { it.copy(themeMode = m) }
                }
            }
            HorizontalDivider()
            Text(
                "字体",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            for (f in FontSize.entries) {
                MenuChoice(f.label, appSettings.fontSize == f) {
                    close()
                    onUpdateSettings { it.copy(fontSize = f) }
                }
            }
        }
        MenuButton("模式") { close ->
            Text(
                "协议（切换会重建引擎，接收短暂中断）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            for (p in Protocol.entries) {
                MenuChoice(p.name, status.protocol == p) {
                    close()
                    onProtocol(p)
                }
            }
        }
        MenuButton("解码") { close ->
            DropdownMenuItem(
                text = { Text("清除解码信息") },
                onClick = {
                    close()
                    onClearDecodes()
                },
            )
            DropdownMenuItem(
                text = { Text("呼号过滤…") },
                onClick = {
                    close()
                    onOpenFilter()
                },
            )
            DropdownMenuItem(
                text = { Text("解码深度…") },
                onClick = {
                    close()
                    onTab(MainTab.SETTINGS)
                },
            )
        }
        MenuButton("自动程序") { close ->
            DropdownMenuItem(
                text = { Text(if (status.txEnabled) "自动程序（运行中）" else "自动程序（待命）") },
                onClick = {
                    close()
                    onAutoProgram()
                },
            )
        }
        MenuButton("设置") { close ->
            DropdownMenuItem(
                text = { Text("打开设置页") },
                onClick = {
                    close()
                    onTab(MainTab.SETTINGS)
                },
            )
        }
        MenuButton("帮助") { close ->
            DropdownMenuItem(
                text = { Text("操作与手势") },
                onClick = {
                    close()
                    onHelp()
                },
            )
        }

        Spacer(Modifier.weight(1f))
        Text(
            status.status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- 信息头

/**
 * 信息头（对应 JTDX 的大频率 / 大时钟 / 时隙计数 / 功率表）。
 *
 * 大频率 = 刻度频率，大时钟 = UTC，`TX 已过秒/时隙秒`，右侧是输入电平竖条（原功率表位置）。
 */
@Composable
fun JtdxInfoHeader(
    status: ReceiverStatus,
    appSettings: AppSettings,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val dialHz = status.dialHz.takeIf { it > 0 } ?: BandPlan.resolveDialHz(status.band, 0L)
    val freqText = if (dialHz > 0) String.format(Locale.US, "%.6f", dialHz / 1_000_000.0) else "--.------"
    val slotSec = status.slotMs / 1000f
    val posSec = (status.slotProgress * slotSec).coerceIn(0f, slotSec)
    val big = MaterialTheme.typography.headlineSmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    )
    var audioOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(JtdxPanel)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(freqText, style = big, color = JtdxValue, maxLines = 1)
            JtdxCaption("${status.band.ifEmpty { "--" }} · ${status.protocol.name} · MHz")
        }
        Spacer(Modifier.width(18.dp))
        Column {
            Text(QsoTime.isoTime(nowMs), style = big, color = JtdxValue, maxLines = 1)
            JtdxCaption("UTC")
        }
        Spacer(Modifier.width(18.dp))
        Column {
            Text(
                String.format(Locale.US, "TX %.0f/%.0f", posSec, slotSec),
                style = big,
                color = if (status.txing) VoxTxRed else JtdxValue,
                maxLines = 1,
            )
            JtdxCaption(if (status.running) "发射周期 ${status.txParity}" else "未接收")
        }

        Spacer(Modifier.weight(1f))

        if (status.myCall.isEmpty()) {
            Text(
                "未设置呼号与网格",
                style = MaterialTheme.typography.labelSmall,
                color = VoxError,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
        }
        decodeTimeLabel(status.lastDecodeMs, status.running)?.let {
            JtdxCaption(it)
            Spacer(Modifier.width(8.dp))
        }
        JtdxCaption(voxLabel(status.voxLevelDb, status.running))
        Spacer(Modifier.width(4.dp))
        JtdxMeter(
            levelDb = status.voxLevelDb,
            running = status.running,
            modifier = Modifier.width(14.dp).height(38.dp),
        )
        TxRxDot(status.txing, status.running, Modifier.padding(start = 8.dp))
        JtdxButton(
            text = "音频",
            onClick = { audioOpen = true },
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    if (audioOpen) {
        AlertDialog(
            onDismissRequest = { audioOpen = false },
            title = { Text("音频 / VOX 速览") },
            text = { AudioQuickPanel(status, appSettings) },
            confirmButton = { TextButton(onClick = { audioOpen = false }) { Text("关闭") } },
        )
    }
}

// ---------------------------------------------------------------- 控制行

/**
 * 控制行（JTDX 信息头下面那条）：只保留本项目有对应功能的项。
 *
 * 波段 / 报告 / DX 呼号 / DX 网格 / 带宽 / 时差 / 同频异频。
 * `DX 呼号`、`DX 网格` 是**只读回显**（当前目标与该台最近一条解码的网格，见 docs/UI-JTDX.md §8）。
 */
@Composable
fun JtdxControlRow(
    status: ReceiverStatus,
    appSettings: AppSettings,
    messages: List<DecodeResult>,
    onBandFreq: (String, Long) -> Unit,
    onSameFreqTx: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onManualCall: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bandDialog by remember { mutableStateOf(false) }
    val target = status.qso.theirCall?.takeIf { it.isNotBlank() }
    val targetGrid = remember(messages, target) {
        target?.let { c ->
            messages.firstOrNull { MessageParser.parse(it.text).from?.equals(c, ignoreCase = true) == true }
                ?.let { MessageParser.parse(it.text).grid }
        }
    }
    val report = status.qso.reportSent ?: TxCompose.reportFor(messages, target)

    // ---- DX 呼号 / 网格：可手动输入，也可由「点选解码行」自动回填 ----
    // 回填规则：只有当输入框是空的、或仍是上一次自动填进去的值（用户没改过）时才覆盖，
    // 避免把用户正在敲的呼号冲掉。
    var dxCall by rememberSaveable { mutableStateOf("") }
    var dxGrid by rememberSaveable { mutableStateOf("") }
    var lastAutoCall by rememberSaveable { mutableStateOf("") }
    var lastAutoGrid by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(target) {
        val t = target.orEmpty()
        if (t.isNotEmpty() && t != lastAutoCall &&
            (dxCall.isBlank() || dxCall.equals(lastAutoCall, ignoreCase = true))
        ) {
            dxCall = t
        }
        lastAutoCall = t
    }
    LaunchedEffect(targetGrid) {
        val g = targetGrid.orEmpty()
        if (g.isNotEmpty() && g != lastAutoGrid &&
            (dxGrid.isBlank() || dxGrid.equals(lastAutoGrid, ignoreCase = true))
        ) {
            dxGrid = g
        }
        lastAutoGrid = g
    }
    val canCall = status.myCall.isNotBlank() && dxCall.isNotBlank()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(JtdxPanelHi)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        JtdxButton(
            text = "${status.band.ifEmpty { "波段" }} ▾",
            onClick = { bandDialog = true },
            modifier = Modifier.width(84.dp),
        )

        JtdxCaption("报告 ${MessageParser.formatReport(report)}")

        JtdxCaption("DX 呼号")
        JtdxTextField(
            value = dxCall,
            onValueChange = { dxCall = it.uppercase().take(12) },
            modifier = Modifier.width(88.dp),
            placeholder = "呼号",
            textStyle = MaterialTheme.typography.labelMedium,
        )

        JtdxCaption("网格")
        JtdxTextField(
            value = dxGrid,
            onValueChange = { dxGrid = it.uppercase().take(6) },
            modifier = Modifier.width(66.dp),
            placeholder = "网格",
            textStyle = MaterialTheme.typography.labelMedium,
        )
        JtdxButton(
            text = "呼叫",
            onClick = {
                onManualCall(
                    dxCall.trim().uppercase(),
                    dxGrid.trim().uppercase().ifEmpty { null },
                )
            },
            enabled = canCall,
            active = status.qso.active && status.qso.theirCall.equals(dxCall.trim(), ignoreCase = true),
        )

        JtdxButton(
            text = "带宽 ${appSettings.decode.fMinHz}-${appSettings.decode.fMaxHz}",
            onClick = onOpenSettings,
        )
        JtdxButton(
            text = "时差 ${slotOffsetLabel(appSettings.slotOffsetMs)}",
            onClick = onOpenSettings,
        )
        JtdxButton(
            text = if (appSettings.sameFreqTx) "同频发射" else "异频发射",
            onClick = { onSameFreqTx(!appSettings.sameFreqTx) },
            active = appSettings.sameFreqTx,
        )
    }

    if (bandDialog) {
        BandFreqDialog(
            currentBand = status.band,
            currentHz = status.dialHz,
            onConfirm = { name, hz ->
                bandDialog = false
                onBandFreq(name, hz)
            },
            onDismiss = { bandDialog = false },
        )
    }
}

// ---------------------------------------------------------------- 状态条

/**
 * JTDX 风格底部状态条（docs/UI-JTDX.md §2）：
 * `[接收/发射] 协议 解码 x/min 总 n QSO n 队列 n 时隙进度 日期 时间不同步`。
 */
@Composable
fun JtdxStatusBar(
    running: Boolean,
    txing: Boolean,
    protocol: Protocol,
    decodePerMin: Int,
    decodedTotal: Long,
    qsoCount: Int,
    queueCount: Int,
    slotProgress: Float,
    slotMs: Int,
    slotParity: Int,
    timeWarning: String?,
    dateText: String,
    modifier: Modifier = Modifier,
) {
    val stateText = when {
        txing -> "发射"
        running -> "接收"
        else -> "停止"
    }
    val stateColor = when {
        txing -> VoxTxRed
        running -> VoxRxGreen
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val slotSec = (slotMs / 1000f).coerceAtLeast(0.001f)
    val posSec = (slotProgress * slotSec).coerceIn(0f, slotSec)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(JtdxPanel)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(stateColor)
                .padding(vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stateText, style = MaterialTheme.typography.labelSmall, color = Color.Black)
        }
        Text(protocol.name, style = MaterialTheme.typography.labelSmall, color = JtdxGreen)
        JtdxCaption("解码 $decodePerMin/min")
        JtdxCaption("总 $decodedTotal")
        JtdxCaption("QSO $qsoCount")
        JtdxCaption("队列 $queueCount")
        JtdxCaption(String.format(Locale.US, "时隙 %d %.0f/%.0f", slotParity, posSec, slotSec))
        Spacer(Modifier.weight(1f))
        if (timeWarning != null) {
            Text(timeWarning, style = MaterialTheme.typography.labelSmall, color = VoxError)
        } else {
            JtdxCaption("时间同步")
        }
        JtdxCaption(dateText)
    }
}

// ---------------------------------------------------------------- 对话框

/** 「帮助」对话框：快捷键与手势速查（docs/UI-JTDX.md §6）。 */
@Composable
fun JtdxHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("操作与手势") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (line in listOf(
                    "解码表格：单击 = 设为目标并对频；双击 = 跳地图定位；长按 = 菜单（呼叫 / 回复 / 详情 / 日志 / 复制 / 关注 / 忽略）",
                    "解码表格：左滑 = 设为目标并呼叫；右滑 = 删除该条",
                    "频谱页：单击或水平拖动瀑布 = 设发射频率；长按 = 移到按下处并打开最近一条解码的详情",
                    "底发射区默认收起成一行（给解码表格让高度）；点「报文槽 ▾」展开 Tx1–Tx6 六个槽，点「收起 ▴」还原",
                    "底发射区：Tx1–Tx6 是六步报文（网格 / 报告 / R报告 / RR73 / 73 / CQ），点一下即排到下一个我方时隙",
                    "「生成信息」= 把当前该发的那条报文填进自定义框；「CQ」前缀影响所有 CQ 报文",
                    "发射的唯一闸门是「发送总开关」（默认关 = 只接收）",
                    "返回键：接收中退到后台继续接收；有弹窗时先关弹窗",
                    "本应用不做 CAT 电台控制，发射依赖电台 VOX 或手动 PTT",
                )) {
                    Text("· $line", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

/**
 * 「波段与频率」弹窗：列出各波段及常用 FT8/FT4 刻度频率，并支持自定义波段名与频率。
 *
 * [currentBand]/[currentHz] 用于标记当前项；[onConfirm] 回传最终（波段名, 频率 Hz）。
 */
@Composable
fun BandFreqDialog(
    currentBand: String,
    currentHz: Long,
    onConfirm: (String, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var customOpen by remember { mutableStateOf(false) }
    var customName by remember {
        mutableStateOf(if (BandPlan.contains(currentBand)) "" else currentBand)
    }
    var customMhz by remember {
        mutableStateOf(if (currentHz > 0) String.format(Locale.US, "%.4f", currentHz / 1_000_000.0) else "")
    }
    val customHz = BandPlan.parseFreqMhz(customMhz)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("波段与频率") },
        text = {
            Column {
                if (!customOpen) {
                    Text(
                        "选择波段与常用刻度频率（无 CAT，仅用于记录与 ADIF）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        for (b in BandPlan.bands) {
                            item(key = "band_${b.name}") {
                                Text(
                                    b.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                )
                            }
                            items(b.freqs, key = { "f_${b.name}_${it.hz}" }) { f ->
                                val selected = b.name == currentBand && f.hz == currentHz
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onConfirm(b.name, f.hz) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (selected) "● ${f.display}" else "　${f.display}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    TextButton(onClick = { customOpen = true }) { Text("自定义波段与频率…") }
                } else {
                    Text(
                        "自定义：填波段名与刻度频率（MHz，如 14.074 或 7.0475）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("波段名（如 20m / 试验）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = customMhz,
                        onValueChange = { customMhz = it },
                        label = { Text("频率 MHz（如 14.074）") },
                        singleLine = true,
                        isError = customMhz.isNotBlank() && customHz == null,
                        supportingText = {
                            if (customMhz.isNotBlank() && customHz == null) Text("请输入有效的正数频率")
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    TextButton(onClick = { customOpen = false }) { Text("返回列表") }
                }
            }
        },
        confirmButton = {
            if (customOpen) {
                Button(
                    onClick = {
                        val hz = customHz ?: return@Button
                        val name = customName.trim().ifEmpty { "自定义" }
                        onConfirm(name, hz)
                    },
                    enabled = customHz != null,
                ) { Text("确定") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/** 音频 / VOX 速览（显示采样率、VOX 电平与判定配置）。 */
@Composable
private fun AudioQuickPanel(status: ReceiverStatus, appSettings: AppSettings) {
    val context = LocalContext.current
    val inputName = remember(appSettings.inputDevice) {
        AudioDevices.label(AudioDevices.inputs(context), appSettings.inputDevice)
    }
    val outputName = remember(appSettings.outputDevice) {
        AudioDevices.label(AudioDevices.outputs(context), appSettings.outputDevice)
    }
    Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text("音频", style = MaterialTheme.typography.titleSmall)
        Text("输入设备  $inputName", style = MaterialTheme.typography.labelSmall)
        Text("输出设备  $outputName", style = MaterialTheme.typography.labelSmall)
        Text("输入采样率  ${if (status.inputRate > 0) "${status.inputRate} Hz" else "--"}", style = MaterialTheme.typography.labelSmall)
        Text("输出采样率  ${if (status.outputRate > 0) "${status.outputRate} Hz" else "--"}", style = MaterialTheme.typography.labelSmall)
        Text("输入增益  ${appSettings.inputGainDb} dB", style = MaterialTheme.typography.labelSmall)
        Text("输出音量  ${appSettings.outputGainDb} dB", style = MaterialTheme.typography.labelSmall)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("VOX", style = MaterialTheme.typography.titleSmall)
        Text("输入电平  ${if (!status.running) "--" else if (status.voxLevelDb <= -99.5f) "--" else "${status.voxLevelDb.toInt()} dB"}", style = MaterialTheme.typography.labelSmall)
        Text(
            "前导  静音 ${appSettings.pttDelayMs} ms + 单音 ${if (appSettings.txLeadTone) "${appSettings.txLeadToneMs} ms" else "关"}",
            style = MaterialTheme.typography.labelSmall,
        )
        Text("看门狗  ${appSettings.watchdogMs} ms", style = MaterialTheme.typography.labelSmall)
    }
}
