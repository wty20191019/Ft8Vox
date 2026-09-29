package com.example.ft8vox.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.qso.DecodeHighlight
import com.example.ft8vox.qso.HighlightPrefs
import com.example.ft8vox.qso.HighlightRole
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.qso.ParsedMessage
import com.example.ft8vox.ui.theme.JtdxBorder
import com.example.ft8vox.ui.theme.JtdxPanel
import com.example.ft8vox.ui.theme.JtdxValue
import java.util.Locale
import kotlin.math.abs

/**
 * 频谱独立页（docs/UI-MOBILE.md §4）：整页瀑布 + 频率刻度 + 发射频率读数 + **解码呼号映射**。
 *
 * 交互：**按住水平拖动红线 = 设发射频率（红线 = 报文下边频）**；
 * 长按 = 先把红线移到按下处，再打开最近一条解码的详情。
 * 频谱上叠加的呼号**只读**，不响应点击（与操作页的表格区分，防误触）。
 */
@Composable
fun SpectrumScreen(
    viewModel: SessionViewModel,
    settings: AppSettings,
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

    // ---- 频谱上叠加的解码呼号：最近 2–3 个时隙（docs/UI-MOBILE.md §4）----
    val duplicateKeys = remember(messages) { DecodeHighlight.duplicateRowKeys(messages) }
    val highlightPrefs = HighlightPrefs(
        newCall = settings.highlightNewCall,
        newGrid = settings.highlightNewGrid,
        newEntity = settings.highlightNewEntity,
        newItu = settings.highlightNewItu,
        newCqZone = settings.highlightNewCqZone,
        newPrefix = settings.highlightNewPrefix,
    )
    val labels = remember(
        messages, worked, status.myCall, status.qso.theirCall, status.txing,
        status.lastTxText, status.slotMs, duplicateKeys, highlightPrefs,
    ) {
        val slotMs = status.slotMs.toLong().coerceAtLeast(1L)
        val newest = messages.maxOfOrNull { it.slotUtcMs } ?: 0L
        val txText = if (status.txing) status.lastTxText else null
        val recent = messages.filter { it.slotUtcMs > 0 && it.slotUtcMs >= newest - slotMs * 2 }
        recent.mapNotNull { m ->
            val p = MessageParser.parse(m.text)
            val role = DecodeHighlight.classify(
                p, worked, status.qso.theirCall, status.myCall,
                DecodeHighlight.rowKey(m.text, m.slotUtcMs) in duplicateKeys, txText, highlightPrefs,
            ).role
            val call = p.from ?: m.text.substringBefore(' ')
            SpectrumLabel(call = call, df = m.df, role = role)
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
                onLongPress = { hz ->
                    val near = messages.minByOrNull { abs(it.df - hz) }
                    if (near != null) {
                        val p = MessageParser.parse(near.text)
                        if (p.from != null || p.isCq) detailFor = near to p
                    }
                },
            )

            // 解码呼号叠加：按频率位置贴在下半屏，颜色＝JTDX 类别色
            val frame = waterfall
            val span = frame?.let { it.bins * it.binHz }
            val maxW = maxWidth
            val maxH = maxHeight
            labels.forEachIndexed { i, label ->
                val frac = if (frame != null && span != null && span > 0f) {
                    ((label.df - frame.fMinHz) / span).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val x = (maxW * frac - 22.dp).coerceIn(0.dp, (maxW - 46.dp).coerceAtLeast(0.dp))
                val y = (maxH - 18.dp - 13.dp * (i % 4)).coerceAtLeast(0.dp)
                Text(
                    label.call,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = highlightTextColor(label.role),
                    maxLines = 1,
                    modifier = Modifier
                        .offset(x = x, y = y)
                        .background(Color(0xCC000000))
                        .padding(horizontal = 2.dp),
                )
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

/** 频谱叠加的一条解码标签（呼号 + 音频频率 + 高亮类别）。 */
private data class SpectrumLabel(val call: String, val df: Int, val role: HighlightRole)
