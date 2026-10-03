package com.example.ft8vox.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.engine.AudioDevices
import com.example.ft8vox.engine.DecodeResult
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
 * 手机竖屏外壳（docs/Ft8Vox.md）：**四行信息头 / 底部导航**。
 *
 * 取代旧横屏外壳的「菜单栏 + 控制行 + 左侧竖导航」。
 * 保留原有工具函数（[rememberUtcNowMs] / [decodesPerMinute] / [timeSyncWarning] /
 * [decodeTimeLabel] / [voxLabel] / [BandFreqDialog]），供页面与单测继续使用。
 */

/** docs/Ft8Vox.md：秒级 UTC 时钟（对齐到整秒刷新）。 */
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

/**
 * 信息头第 3 行右侧 DX 栏文案（实机反馈：不能再自相矛盾）。
 *
 * - 有目标：`DX→ 呼号 网格 报告`
 * - 没目标但**正在呼叫 CQ（等回应）**：不能只写「未设目标」——否则与发射区那句
 *   「CQ 已发出 / CQ 待发，等待回应」互相打架，看起来像「没设目标却在通联」。
 *   此时如实说明「在叫 CQ、还没人回应」。
 * - 其余：`DX→ 未设目标`。
 */
internal fun dxTargetLabel(
    target: String?,
    targetGrid: String?,
    report: Int,
    awaitingResponders: Boolean,
): String = when {
    target != null -> "DX→ $target ${targetGrid ?: "--"} ${MessageParser.formatReport(report)}"
    awaitingResponders -> "CQ 呼叫中，暂无目标"
    else -> "DX→ 未设目标"
}

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
            .size(10.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (txing) pulse else 1f)),
    )
}

/** 等宽字（大频率 / 时钟）。 */
@Composable
private fun mono(fontSize: Int) = MaterialTheme.typography.labelLarge.copy(
    fontFamily = FontFamily.Monospace,
    fontSize = fontSize.sp,
    fontWeight = FontWeight.Bold,
)

// ---------------------------------------------------------------- 信息头（三行）

/**
 * 竖屏信息头（docs/Ft8Vox.md），三行紧凑：
 *
 * 1. 大频率 + 波段·模式 + **发送总开关**（唯一发射闸门）
 * 2. UTC 时钟 + 时隙进度 + **我方发射时序 `TX0/TX1`** + 输入电平 + 收发状态点
 * 3. 我方呼号/网格 + DX 目标回显（未设呼号时红字「请到设置填写」）
 * 4. 统计读数（解码速率 / 解码总数 / 通联数 / 队列）+ 时间同步 + 日期
 *
 * 第二行原先的「音频」「?」两个小按钮已收走（实机太挤，见 docs/Ft8Vox.md）：
 * 音频速览移进设置页「音频」组，手势速查移进设置页「关于」组。
 *
 * 第 4 行是**原底部细状态条**（§26）：整条搬到顶端，并去掉与上三行重复的
 * 「模式」（行 1 已有 `波段 · 模式`）、「时隙 N」（行 2 已有）与「接收/发射/停止」文字块
 * （行 2 的收发状态点即它）。这样全局状态只有一处，底部不再占一条。
 */
@Composable
fun MobileInfoHeader(
    status: ReceiverStatus,
    messages: List<DecodeResult>,
    nowMs: Long,
    decodedTotal: Long,
    qsoCount: Int,
    queueCount: Int,
    dateText: String,
    onBandFreq: (String, Long) -> Unit,
    onTxEnabledChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dialHz = status.dialHz.takeIf { it > 0 } ?: BandPlan.resolveDialHz(status.band, 0L)
    val freqText = if (dialHz > 0) String.format(Locale.US, "%.6f", dialHz / 1_000_000.0) else "--.------"
    var bandDialog by remember { mutableStateOf(false) }

    // DX 目标回显：目标呼号 + 最近一条该台的网格 + 报告
    val target = status.qso.theirCall?.takeIf { it.isNotBlank() }
    val targetGrid = remember(messages, target) {
        target?.let { c ->
            messages.firstOrNull { MessageParser.parse(it.text).from?.equals(c, ignoreCase = true) == true }
                ?.let { MessageParser.parse(it.text).grid }
        }
    }
    val report = status.qso.reportSent ?: TxCompose.reportFor(messages, target)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(JtdxPanel)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 行 1
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                freqText,
                style = mono(21),
                color = JtdxValue,
                maxLines = 1,
                modifier = Modifier.clickable { bandDialog = true },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${status.band.ifEmpty { "--" }} · ${status.protocol.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.clickable { bandDialog = true },
            )
            Spacer(Modifier.weight(1f))
            MasterSwitch(status.txEnabled, onTxEnabledChange)
        }

        // 行 2
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(QsoTime.isoTime(nowMs), style = mono(15), color = JtdxValue, maxLines = 1)
            Column(Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { status.slotProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
                Text(
                    "时隙 ${status.slotParity}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                voxLabel(status.voxLevelDb, status.running),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            // 我方发射时序徽标：与「时隙 N」同一语义，放同一行（原在底部细状态条，§26 搬上来）
            TxParityBadge(
                parity = status.txParity,
                on = status.txing || (status.running && status.slotParity == status.txParity),
            )
            TxRxDot(status.txing, status.running)
        }

        // 行 3
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (status.myCall.isEmpty()) {
                Text(
                    "请到设置填写呼号与网格",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoxError,
                    maxLines = 1,
                    modifier = Modifier.clickable { onOpenSettings() },
                )
            } else {
                Text(
                    "${status.myCall} ${status.myGrid}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            decodeTimeLabel(status.lastDecodeMs, status.running)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                dxTargetLabel(target, targetGrid, report, status.qso.awaitingResponders),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (target != null || status.qso.awaitingResponders) {
                    JtdxGreen
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 行 4：统计读数 + 时间同步 + 日期（原底部细状态条整条搬上来，§26）
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            JtdxCaption("解码${decodesPerMinute(messages, nowMs)}/min")
            JtdxCaption("总$decodedTotal")
            JtdxCaption("QSO$qsoCount")
            JtdxCaption("队列$queueCount")
            Spacer(Modifier.weight(1f))
            val syncWarning = timeSyncWarning(messages.firstOrNull()?.dt)
            if (syncWarning != null) {
                Text(
                    syncWarning,
                    style = MaterialTheme.typography.labelSmall,
                    color = VoxError,
                    maxLines = 1,
                )
            } else {
                JtdxCaption("时间同步")
            }
            JtdxCaption(dateText)
        }
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

/**
 * 发送总开关（唯一发射闸门）：开＝绿色「发射·开」，关＝红色「只接收」。
 *
 * 常驻在信息头第 1 行右侧，任何时候都看得见当前是收还是发。
 */
@Composable
private fun MasterSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    JtdxButton(
        text = if (enabled) "发射·开" else "只接收",
        onClick = { onChange(!enabled) },
        modifier = Modifier.width(78.dp),
        active = true,
        accent = if (enabled) JtdxGreen else VoxError,
        textStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
    )
}

// ---------------------------------------------------------------- 底部导航

/** 页面图标（`material-icons-core` 现有图标，docs/Ft8Vox.md）。 */
private fun tabIcon(tab: MainTab): ImageVector = when (tab) {
    MainTab.OPERATE -> Icons.AutoMirrored.Filled.List
    MainTab.SPECTRUM -> Icons.Filled.Refresh
    MainTab.MAP -> Icons.Filled.Place
    MainTab.LOG -> Icons.Filled.DateRange
    MainTab.SETTINGS -> Icons.Filled.Settings
}

/** 底部 5 项导航（图标 + 文字，选中项绿色）。 */
@Composable
fun MobileBottomNav(
    tab: MainTab,
    onTab: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(JtdxPanelHi)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(JtdxBorder))
        Row(Modifier.fillMaxWidth()) {
            for (t in MainTab.entries) {
                val selected = t == tab
                val color = if (selected) JtdxGreen else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTab(t) }
                        .padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    TabIcon(tab = t, selected = selected, color = color)
                    Text(t.label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
                }
            }
        }
    }
}

/** 底部导航图标：频谱用自绘「瀑布 + 发射线」，其余用 `material-icons-core` 的 Material 图标。 */
@Composable
private fun TabIcon(tab: MainTab, selected: Boolean, color: Color) {
    if (tab == MainTab.SPECTRUM) {
        WaterfallTabIcon(selected)
    } else {
        Icon(
            imageVector = tabIcon(tab),
            contentDescription = tab.label,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 自绘「频谱」图标：4 条横向瀑布色带（蓝 → 青 → 绿 → 黄）+ 一条红色发射线。
 *
 * `material-icons-core` 里没有瀑布图标，所以这一项自己画（docs/Ft8Vox.md）；
 * 未选中时整体降透明度，跟其它 Material 图标观感一致。
 */
@Composable
private fun WaterfallTabIcon(selected: Boolean) {
    val a = if (selected) 1f else 0.5f
    val bands = listOf(
        Color(0xFF1E3A8A), // 深蓝
        Color(0xFF00A2C7), // 青
        Color(0xFF3FBF6F), // 绿
        Color(0xFFE0D63C), // 黄
    )
    Canvas(Modifier.size(20.dp)) {
        val h = size.height / bands.size
        bands.forEachIndexed { i, c ->
            drawRect(
                color = c.copy(alpha = a),
                topLeft = Offset(0f, i * h + 0.5f),
                size = Size(size.width, (h - 1.2f).coerceAtLeast(1f)),
            )
        }
        drawLine(
            color = Color(0xFFFF5252).copy(alpha = if (selected) 1f else 0.75f),
            start = Offset(size.width * 0.45f, 0f),
            end = Offset(size.width * 0.45f, size.height),
            strokeWidth = 2f,
        )
    }
}

// ---------------------------------------------------------------- 发射时序徽标

/**
 * 信息头第 2 行的「我方发射时序」徽标（docs/Ft8Vox.md）：`TX0` / `TX1`。
 *
 * @param parity 我方发射时隙奇偶
 * @param on 现在轮到我方（或正在发射）时红底黑字，否则深灰底灰字
 */
@Composable
private fun TxParityBadge(parity: Int, on: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(if (on) VoxTxRed else JtdxPanelHi)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "TX$parity",
            style = MaterialTheme.typography.labelSmall,
            color = if (on) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- 对话框

/** 「帮助」对话框：快捷键与手势速查（docs/Ft8Vox.md）。 */
@Composable
fun JtdxHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("操作与手势") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (line in listOf(
                    "竖屏五页：底部导航「操作 / 频谱 / 地图 / 日志 / 设置」",
                    "解码表格：单击 = 设为目标并对频；双击 = 跳地图定位；长按 = 菜单（呼叫 / 回复 / 详情 / 日志 / 复制 / 跟踪）",
                    "解码表格：左滑 = 设为目标并呼叫；右滑 = 删除该条",
                    "解码行底色 = JTDX 默认高亮：黄 = 自己发的、红 = 叫我、绿 = CQ、品红 = 新 DXCC、橙 = 新网格、青 = 新呼号",
                    "跟踪列表：左滑 = 呼叫；右滑 = 取消跟踪；右上「全部清除」清空整份名单",
                    "频谱页：按住水平拖动红线 = 设发射频率；瀑布上按频率位置叠加最近解码的报文（竖排、随瀑布上滚）",
                    "发射区：六个报文槽排成 3 列 × 2 行、列优先（1 3 5 / 2 4 6），点哪一格就发那一格的报文",
                    "发射区：自定义报文框会自动同步当前待发报文（手动改过就先按手动的，发送完自动回到同步）",
                    "发射区：「生成信息」= 按当前待发报文重新生成（没有待发时退到 CQ 报文）；CQ 前缀影响所有 CQ 报文",
                    "发射区：「正在发送」红字 = 本时隙实际在播的报文；灰字「空闲」= 没在发",
                    "底部状态条：TX0 / TX1 = 我方发射时隙，红色表示现在就轮到我发",
                    "发射的唯一闸门是信息头右上角的「发射 / 只接收」总开关（默认只接收）",
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
 * 「波段与频率」弹窗：列出各波段及常用 FT8 刻度频率，并支持自定义波段名与频率。
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

/** 音频 / VOX 速览（显示采样率、VOX 电平与判定配置）；已从信息头收进设置页「音频」组。 */
@Composable
fun AudioQuickPanel(status: ReceiverStatus, appSettings: AppSettings) {
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
    }
}
