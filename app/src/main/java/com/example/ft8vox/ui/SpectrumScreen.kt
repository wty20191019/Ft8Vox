package com.example.ft8vox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.qso.ParsedMessage
import com.example.ft8vox.ui.theme.JtdxBorder
import com.example.ft8vox.ui.theme.JtdxPanel
import java.util.Locale
import kotlin.math.abs

/**
 * 频谱独立页（docs/UI-JTDX.md §1、§6）：整页瀑布 + 频率刻度 + 发射频率读数 + 时隙进度。
 *
 * 交互与操作页原瀑布一致：**单击 / 水平拖动 = 设发射频率（红线 = 报文下边频）**，
 * **长按 = 先把红线移到按下处，再打开最近一条解码的详情**。
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
    var detailFor by remember { mutableStateOf<Pair<DecodeResult, ParsedMessage>?>(null) }

    Column(modifier.fillMaxSize()) {
        Box(
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

            // 发射频率读数（右下；红线＝发射频率，拖动红线即可调整）
            Text(
                String.format(Locale.US, "TX %d Hz", status.selectedFreqHz),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = Color(0xCCFFFFFF),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )

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

        // 频率刻度（左 / 中 / 右）
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
