package com.example.ft8vox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.engine.AudioDevices
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.ui.theme.VoxError
import com.example.ft8vox.ui.theme.VoxRxGreen
import com.example.ft8vox.ui.theme.VoxTxRed
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

/** new_ui.md §1：秒级 UTC 时钟（对齐到整秒刷新）。 */
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

/** 最近 60 秒内的解码条数（底部状态条「解码 x/min」）。 */
fun decodesPerMinute(messages: List<DecodeResult>, nowMs: Long): Int =
    messages.count { it.slotUtcMs > 0 && it.slotUtcMs >= nowMs - 60_000L }

/** 时间偏差告警：最近一条解码的 DT 绝对值超阈值时返回红字文案，否则 null。 */
fun timeSyncWarning(latestDt: Float?): String? =
    if (latestDt != null && abs(latestDt) >= 1.5f) "时间不同步" else null

/**
 * new_ui.md §1 顶部 AppBar（56dp）。
 *
 * 左：菜单（波段 / 模式 / 设置）；中：UTC 时钟 + `刻度 MHz · 模式`；右：RX/TX 圆点 + 音频速览。
 */
@Composable
fun Ft8VoxTopBar(
    status: ReceiverStatus,
    appSettings: AppSettings,
    nowMs: Long,
    onBand: (String) -> Unit,
    onProtocol: (Protocol) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var audioOpen by remember { mutableStateOf(false) }
    val dialHz = BandPlan.dialHz(status.band)
    val dialMhz = if (dialHz > 0) {
        String.format(Locale.US, "%.6f", dialHz / 1_000_000.0)
    } else {
        "--.------"
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = "菜单")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Text(
                        "波段",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    for (b in BandPlan.bands) {
                        DropdownMenuItem(
                            text = { Text(if (b.name == status.band) "● ${b.name}" else b.name) },
                            onClick = {
                                menuOpen = false
                                onBand(b.name)
                            },
                        )
                    }
                    HorizontalDivider()
                    Text(
                        "模式",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    for (p in Protocol.entries) {
                        DropdownMenuItem(
                            text = { Text(if (p == status.protocol) "● ${p.name}" else p.name) },
                            onClick = {
                                menuOpen = false
                                onProtocol(p)
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("设置") },
                        onClick = {
                            menuOpen = false
                            onOpenSettings()
                        },
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "${QsoTime.isoTime(nowMs)} UTC",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "$dialMhz MHz · ${status.protocol.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TxRxDot(txing = status.txing, running = status.running)

            Box {
                IconButton(onClick = { audioOpen = true }) {
                    Icon(Icons.Filled.Notifications, contentDescription = "音频 / 电平")
                }
                DropdownMenu(expanded = audioOpen, onDismissRequest = { audioOpen = false }) {
                    AudioQuickPanel(status, appSettings)
                }
            }
        }
    }
}

/** 音频 / VOX 速览（U7b：显示采样率、VOX 电平与触发配置）。 */
@Composable
private fun AudioQuickPanel(status: ReceiverStatus, appSettings: AppSettings) {
    val context = LocalContext.current
    val inputName = remember(appSettings.inputDevice) {
        AudioDevices.label(AudioDevices.inputs(context), appSettings.inputDevice)
    }
    val outputName = remember(appSettings.outputDevice) {
        AudioDevices.label(AudioDevices.outputs(context), appSettings.outputDevice)
    }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("音频", style = MaterialTheme.typography.titleSmall)
        Text("输入设备  $inputName", style = MaterialTheme.typography.labelSmall)
        Text("输出设备  $outputName", style = MaterialTheme.typography.labelSmall)
        Text("输入采样率  ${if (status.inputRate > 0) "${status.inputRate} Hz" else "--"}", style = MaterialTheme.typography.labelSmall)
        Text("输出采样率  ${if (status.outputRate > 0) "${status.outputRate} Hz" else "--"}", style = MaterialTheme.typography.labelSmall)
        Text("输入增益  ${appSettings.inputGainDb} dB", style = MaterialTheme.typography.labelSmall)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("VOX", style = MaterialTheme.typography.titleSmall)
        Text(
            "电平  ${if (!status.running) "--" else if (status.voxLevelDb <= -99.5f) "--" else "${status.voxLevelDb.toInt()} dB"}",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "状态  ${if (!status.running) "未运行" else if (status.voxOpen) "触发" else "空闲"}",
            style = MaterialTheme.typography.labelSmall,
            color = if (status.voxOpen) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("触发  ${appSettings.voxTrigger.label} / ${appSettings.voxThresholdDb} dB", style = MaterialTheme.typography.labelSmall)
        Text(
            "前导  静音 ${appSettings.pttDelayMs} ms + 单音 ${if (appSettings.txLeadTone) "${appSettings.txLeadToneMs} ms" else "关"}",
            style = MaterialTheme.typography.labelSmall,
        )
        Text("看门狗  ${appSettings.watchdogMs} ms", style = MaterialTheme.typography.labelSmall)
    }
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
            .size(12.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (txing) pulse else 1f)),
    )
}

/**
 * new_ui.md §7 底部常驻状态条。
 *
 * 内容过多时横向滚动，避免窄屏被裁切。
 */
@Composable
fun BottomStatusBar(
    running: Boolean,
    txing: Boolean,
    decodePerMin: Int,
    decodedTotal: Long,
    qsoCount: Int,
    queueCount: Int,
    timeWarning: String?,
    voxLevelDb: Float = -100f,
    voxOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TxRxDot(txing = txing, running = running)
            Text(
                if (txing) "TX" else "RX",
                style = MaterialTheme.typography.labelSmall,
                color = if (txing) VoxTxRed else VoxRxGreen,
            )
            Text(
                voxLabel(voxLevelDb, voxOpen, running),
                style = MaterialTheme.typography.labelSmall,
                color = if (voxOpen) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("解码 $decodePerMin/min", style = MaterialTheme.typography.labelSmall)
            Text("总数 $decodedTotal", style = MaterialTheme.typography.labelSmall)
            Text("QSO $qsoCount", style = MaterialTheme.typography.labelSmall)
            Text("队列 $queueCount", style = MaterialTheme.typography.labelSmall)
            if (timeWarning != null) {
                Text(timeWarning, style = MaterialTheme.typography.labelSmall, color = VoxError)
            } else {
                Text("时间同步", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 底部状态条「VOX」文案：电平（dBFS）+ 触发点。 */
internal fun voxLabel(levelDb: Float, open: Boolean, running: Boolean): String {
    if (!running) return "VOX --"
    val lv = if (levelDb <= -99.5f) "--" else "${levelDb.toInt()} dB"
    return if (open) "VOX $lv ●" else "VOX $lv"
}
