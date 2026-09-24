package com.example.ft8vox.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ft8vox.data.BandPlan
import com.example.ft8vox.data.QsoTime
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.qso.MessageParser
import java.util.Locale

/** 操作页：瀑布、解码列表、选频与 QSO 发射。 */
@Composable
fun OperateScreen(
    viewModel: SessionViewModel,
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val status by viewModel.status.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val waterfall by viewModel.waterfall.collectAsState()
    val recentQso by viewModel.recentQso.collectAsState(initial = emptyList())

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var pendingTx by remember { mutableStateOf<PendingTx?>(null) }
    var afterPermission by remember { mutableStateOf<PendingTx?>(null) }

    fun execute(action: PendingTx) {
        when (action) {
            PendingTx.Cq -> viewModel.startCq()
            is PendingTx.Reply -> viewModel.answer(action.call, action.grid)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        val action = afterPermission
        afterPermission = null
        if (granted) {
            if (action == null) viewModel.start() else execute(action)
        }
    }

    fun request(action: PendingTx?) {
        if (permissionGranted) {
            if (action == null) viewModel.start() else execute(action)
        } else {
            afterPermission = action
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {

        // ---- 台站身份 + 波段 + 接收开关 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${status.myCall.ifEmpty { "（未设置呼号）" }} / ${status.myGrid.ifEmpty { "--" }}",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                )
                BandSelector(
                    band = status.band,
                    enabled = true,
                    onChange = { viewModel.setBand(it) },
                )
            }
            Button(onClick = { if (status.running) viewModel.stop() else request(null) }) {
                Text(if (status.running) "停止接收" else "开始接收")
            }
        }
        if (status.myCall.isEmpty()) {
            Text(
                "请到「设置」填写呼号与网格后再发射。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ---- 协议与选中频率 ----
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (p in Protocol.entries) {
                FilterChip(
                    selected = status.protocol == p,
                    enabled = !status.running,
                    onClick = { viewModel.selectProtocol(p) },
                    label = { Text(p.name) },
                )
            }
            Text("选中 ${status.selectedFreqHz} Hz", style = MaterialTheme.typography.bodySmall)
        }

        TxControlRow(
            txParity = status.txParity,
            armed = status.txArmed,
            canOperate = status.myCall.isNotEmpty(),
            onParityChange = { viewModel.setTxParity(it) },
            onStartCq = { pendingTx = PendingTx.Cq },
            onStopTx = { viewModel.stopTransmit() },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { viewModel.transmitTest() }) { Text("发射测试") }
            TextButton(onClick = { viewModel.clearMessages() }) { Text("清空列表") }
        }

        StatusBar(status)
        QsoStatusLine(status)

        // ---- 瀑布 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(settings.waterfallHeight.heightDp.dp)
                .background(Color.Black),
        ) {
            WaterfallView(
                frame = waterfall,
                selectedFreqHz = status.selectedFreqHz,
                slotParity = status.slotParity,
                onSelectFrequency = { viewModel.selectFrequency(it) },
                modifier = Modifier.fillMaxSize(),
                theirFreqHz = status.theirFreqHz,
                txing = status.txing,
            )
        }
        FrequencyAxis(waterfall?.fMinHz, waterfall?.maxHz)

        // ---- 最近通联（落库后的记录） ----
        if (recentQso.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("最近通联", style = MaterialTheme.typography.titleSmall)
            for (r in recentQso) {
                Text(
                    String.format(
                        Locale.US,
                        "%s  %s%s  收 %s / 发 %s  %s",
                        QsoTime.isoDateTime(r.utcMs),
                        r.theirCall,
                        r.theirGrid?.let { " ($it)" } ?: "",
                        r.reportReceived?.toString() ?: "--",
                        r.reportSent?.toString() ?: "--",
                        r.band,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("解码结果（${messages.size}）", style = MaterialTheme.typography.titleSmall)

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(messages) { message ->
                DecodeRow(
                    message = message,
                    slotMs = status.slotMs.toLong(),
                    onClick = { viewModel.selectFrequency(message.df) },
                    onReply = { call, grid -> pendingTx = PendingTx.Reply(call, grid) },
                )
            }
        }
    }

    pendingTx?.let { pending ->
        TxConfirmDialog(
            pending = pending,
            status = status,
            onConfirm = {
                pendingTx = null
                request(pending)
            },
            onDismiss = { pendingTx = null },
        )
    }
}

/** 波段选择（无 CAT，需人工指定，用于 ADIF 记录与导出）。 */
@Composable
private fun BandSelector(band: String, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text("波段 $band ▾", style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (b in BandPlan.bands) {
                DropdownMenuItem(
                    text = { Text(b.name) },
                    onClick = {
                        expanded = false
                        onChange(b.name)
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusBar(status: ReceiverStatus) {
    val parity = if (status.slotParity == 0) "偶数周期" else "奇数周期"
    val alignHint = if (status.running && !status.inSlot) "（等待时隙对齐…）" else ""
    Column {
        Text("状态：${status.status}$alignHint", style = MaterialTheme.typography.bodySmall)
        if (status.running) {
            Text(
                String.format(
                    Locale.US,
                    "时隙 %d ms｜%s｜下一时隙 %.1f s｜已解码时隙 %d｜丢帧 %d",
                    status.slotMs,
                    parity,
                    status.msToNextSlot / 1000.0,
                    status.slotsDecoded,
                    status.droppedSamples,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { status.slotProgress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
    }
}

@Composable
private fun FrequencyAxis(fMinHz: Float?, maxHz: Float?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val a = fMinHz ?: 0f
        val b = maxHz ?: 0f
        val mid = (a + b) / 2f
        for (v in listOf(a, mid, b)) {
            Text(
                "${v.toInt()} Hz",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun DecodeRow(
    message: DecodeResult,
    slotMs: Long,
    onClick: () -> Unit,
    onReply: (String, String?) -> Unit,
) {
    // 偶/奇周期背景分色（按该条报文所属时隙判定）
    val tint = if (message.slotUtcMs > 0 && slotMs > 0) {
        val even = ((message.slotUtcMs / slotMs) % 2L) == 0L
        if (even) Color(0x142962FF) else Color(0x14FF6D00)
    } else {
        Color.Transparent
    }
    val parsed = remember(message.text) { MessageParser.parse(message.text) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            String.format(
                Locale.US,
                "%s  %+3d dB  DT %+.1f  DF %4d  %s",
                QsoTime.isoTime(message.slotUtcMs),
                message.snr,
                message.dt,
                message.df,
                message.text,
            ),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        val canReply = parsed.from != null &&
            (parsed.isCq || (parsed.to != null && (parsed.report != null || parsed.isRoger || parsed.grid != null)))
        if (canReply) {
            TextButton(onClick = { onReply(parsed.from!!, parsed.grid) }) {
                Text("应答", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
