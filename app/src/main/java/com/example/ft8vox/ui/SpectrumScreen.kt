package com.example.ft8vox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.data.settings.WATERFALL_FLOOR_DB_RANGE
import com.example.ft8vox.data.settings.WATERFALL_RANGE_DB_RANGE
import com.example.ft8vox.data.settings.clampWaterfallFloorDb
import com.example.ft8vox.data.settings.clampWaterfallRangeDb
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.qso.DecodeHighlight
import com.example.ft8vox.qso.HighlightRole
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.qso.ParsedMessage
import com.example.ft8vox.ui.theme.JtdxBorder
import com.example.ft8vox.ui.theme.JtdxPanel
import com.example.ft8vox.ui.theme.JtdxValue
import java.util.Locale
import kotlin.math.abs

/**
 * 频谱独立页（docs/Ft8Vox.md）：整页瀑布 + 频率刻度 + 发射频率读数 + **解码呼号映射**。
 *
 * 交互：**按住水平拖动红线 = 设发射频率（红线 = 报文下边频）**；
 * 长按 = 先把红线移到按下处，再打开最近一条解码的详情。
 * 频谱上叠加的呼号**只读**，不响应点击（与操作页的表格区分，防误触）。
 */
@Composable
fun SpectrumScreen(
    viewModel: SessionViewModel,
    settings: AppSettings,
    settingsViewModel: SettingsViewModel,
    hasPermission: Boolean,
    onRequestStart: () -> Unit,
    onOpenLog: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by viewModel.status.collectAsState()
    val waterfall by viewModel.waterfall.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val worked by viewModel.workedIndex.collectAsState()
    var detailFor by remember { mutableStateOf<Pair<DecodeResult, ParsedMessage>?>(null) }
    val textMeasurer = rememberTextMeasurer()

    // ---- 频谱上叠加的解码呼号：最近 2–3 个时隙，锚定在各自的时间位置上 ----
    val duplicateKeys = remember(messages) { DecodeHighlight.duplicateRowKeys(messages) }
    val labels = remember(
        messages, worked, status.myCall, status.qso.theirCall, status.txing,
        status.lastTxText, status.slotMs, status.protocol, duplicateKeys,
    ) {
        val slotMs = status.slotMs.toLong().coerceAtLeast(1L)
        val newest = messages.maxOfOrNull { it.slotUtcMs } ?: 0L
        val txText = if (status.txing) status.lastTxText else null
        // 信号实际结束时刻：FT8 在时隙起点后 0.5 s 起播，波形长 protocol.messageMs
        val signalEndOffsetMs = 500L + status.protocol.messageMs
        val recent = messages.filter { it.slotUtcMs > 0 && it.slotUtcMs >= newest - slotMs * 2 }
        recent.mapNotNull { m ->
            val p = MessageParser.parse(m.text)
            val role = DecodeHighlight.classify(
                p, worked, status.qso.theirCall, status.myCall,
                DecodeHighlight.rowKey(m.text, m.slotUtcMs) in duplicateKeys, txText,
            ).role
            SpectrumLabel(
                text = m.text,
                df = m.df,
                role = role,
                signalEndMs = m.slotUtcMs + signalEndOffsetMs,
            )
        }
    }

    Column(modifier.fillMaxSize()) {
        // 发射频率读数（红线＝发射频率）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(JtdxPanel)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "发射频率 ${status.selectedFreqHz} Hz",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = JtdxValue,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${status.band.ifEmpty { "--" }} · ${status.protocol.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 瀑布强度窗口（固定阈值，可调；与设置页「瀑布」组联动）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(JtdxPanel)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "瀑布",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            WfTuneStepper(
                label = "底噪",
                value = settings.waterfallFloorDb,
                unit = " dB",
                canDec = settings.waterfallFloorDb > WATERFALL_FLOOR_DB_RANGE.first,
                canInc = settings.waterfallFloorDb < WATERFALL_FLOOR_DB_RANGE.last,
                onDelta = { d ->
                    settingsViewModel.update {
                        it.copy(waterfallFloorDb = clampWaterfallFloorDb(it.waterfallFloorDb + d))
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            WfTuneStepper(
                label = "动态",
                value = settings.waterfallRangeDb,
                unit = " dB",
                canDec = settings.waterfallRangeDb > WATERFALL_RANGE_DB_RANGE.first,
                canInc = settings.waterfallRangeDb < WATERFALL_RANGE_DB_RANGE.last,
                onDelta = { d ->
                    settingsViewModel.update {
                        it.copy(waterfallRangeDb = clampWaterfallRangeDb(it.waterfallRangeDb + d))
                    }
                },
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
        ) {
            WaterfallView(
                frame = waterfall,
                selectedFreqHz = status.selectedFreqHz,
                slotParity = status.slotParity,
                onMoveTxFreq = { viewModel.setTxFreq(it) },
                modifier = Modifier.fillMaxSize(),
                txing = status.txing,
                occupiedHz = status.protocol.occupiedHz,
                // 还没有第一帧时也要画网格 / 刻度 / 红线，避免启动瞬间一片黑
                fallbackFMinHz = settings.decode.fMinHz.toFloat(),
                fallbackMaxHz = settings.decode.fMaxHz.toFloat(),
                onLongPress = { hz ->
                    val near = messages.minByOrNull { abs(it.df - hz) }
                    if (near != null) {
                        val p = MessageParser.parse(near.text)
                        if (p.from != null || p.isCq) detailFor = near to p
                    }
                },
            )

            // 解码报文叠加（**竖排**：横排会互相叠字，顺转 90° 后只占一条窄缝）：
            // x 按频率定位、y 按「信号实际结束时刻」锚定 —— 文字底端压在信号结束处、
            // 自上而下读，随瀑布向上滚，滚出窗口（WF_ROWS × WF_ROW_MS ≈ 48 s，FT8 默认档约 3 个时隙）即消失。
            // 颜色＝JTDX 类别色；不垫底块（docs/Ft8Vox.md）。
            val frame = waterfall
            val span = frame?.let { it.bins * it.binHz }
            val windowMs = (WF_ROWS * WF_ROW_MS).toFloat()
            val nowWallMs = System.currentTimeMillis()
            val labelStyle = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                val f = frame
                if (f == null || span == null || span <= 0f) return@Canvas
                labels.forEach { label ->
                    val age = (nowWallMs - label.signalEndMs).toFloat()
                    if (age < 0f || age > windowMs) return@forEach
                    val layout = textMeasurer.measure(label.text, style = labelStyle)
                    val tw = layout.size.width.toFloat()
                    val th = layout.size.height.toFloat()
                    val frac = ((label.df - f.fMinHz) / span).coerceIn(0f, 1f)
                    val cx = frac * size.width
                    val yAnchor = size.height * (1f - age / windowMs)
                    if (yAnchor <= 0f || cx < 0f || cx > size.width) return@forEach
                    // 顺时针转 90°：文字自上而下读，底端锚在信号结束时刻、向上铺满信号的轨迹
                    val pivot = Offset(cx + th / 2f, yAnchor - tw)
                    rotate(degrees = 90f, pivot = pivot) {
                        // 先描一圈黑边（弱信号区也读得清），再填 JTDX 类别色
                        drawText(
                            textLayoutResult = layout,
                            color = Color.Black,
                            topLeft = pivot,
                            drawStyle = Stroke(width = 2.5f),
                        )
                        drawText(
                            textLayoutResult = layout,
                            color = highlightTextColor(label.role),
                            topLeft = pivot,
                        )
                    }
                }
            }

            // 发射中角标
            if (status.txing) {
                Text(
                    "发射中",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color(0xCCD32F2F))
                        .padding(horizontal = 4.dp),
                )
            }

            // 时隙进度条（贴底）
            if (status.running) {
                LinearProgressIndicator(
                    progress = { status.slotProgress },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp),
                )
            }

            // 未开始接收的空态提示（点按授权并开始接收）
            if (!status.running) {
                Text(
                    if (hasPermission) "未开始接收：点此开始接收" else "未开始接收：点此授权并开始接收",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp)
                        .clickable { onRequestStart() },
                )
            }
        }

        // 频率刻度（左 / 中 / 右，音频频率 Hz）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(JtdxPanel)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val a = waterfall?.fMinHz ?: settings.decode.fMinHz.toFloat()
            val b = waterfall?.maxHz ?: settings.decode.fMaxHz.toFloat()
            for (v in listOf(a, (a + b) / 2f, b)) {
                Text(
                    "${v.toInt()} Hz",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(JtdxBorder))
    }

    detailFor?.let { (msg, parsed) ->
        DecodeDetailDialog(
            msg = msg,
            parsed = parsed,
            myCall = status.myCall,
            myGrid = status.myGrid.ifEmpty { null },
            onCall = {
                val from = parsed.from
                detailFor = null
                if (from != null && !from.equals(status.myCall, ignoreCase = true)) {
                    viewModel.answer(from, parsed.grid, msg.df)
                }
            },
            onOpenLog = {
                val from = parsed.from
                detailFor = null
                onOpenLog(from)
            },
            onDismiss = { detailFor = null },
        )
    }
}

/** 频谱叠加的一条解码标签（报文 + 音频频率 + 高亮类别 + 信号结束时刻）。 */
private data class SpectrumLabel(
    val text: String,
    val df: Int,
    val role: HighlightRole,
    /** 该解码**信号实际结束**的时刻（ms）：用来锚定它在瀑布上的竖向位置。 */
    val signalEndMs: Long,
)

/** 频谱页的瀑布强度微调控件（−/值/+），步进 5，与设置页「瀑布」组共用同一份设置。 */
@Composable
private fun WfTuneStepper(
    label: String,
    value: Int,
    unit: String,
    canDec: Boolean,
    canInc: Boolean,
    onDelta: (Int) -> Unit,
    step: Int = 5,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "−",
            style = MaterialTheme.typography.titleMedium,
            color = if (canDec) JtdxValue else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable(enabled = canDec) { onDelta(-step) }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
            "$value$unit",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = JtdxValue,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 54.dp),
        )
        Text(
            "+",
            style = MaterialTheme.typography.titleMedium,
            color = if (canInc) JtdxValue else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable(enabled = canInc) { onDelta(step) }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
