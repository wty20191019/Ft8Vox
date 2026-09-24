package com.example.ft8vox

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.ui.ReceiverStatus
import com.example.ft8vox.ui.SessionViewModel
import com.example.ft8vox.ui.WaterfallView
import com.example.ft8vox.ui.theme.Ft8VoxTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: SessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ft8VoxTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ReceiverScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onStop() {
        // 后台不留驻采音（Android 14 起后台麦克风需前台服务）
        viewModel.stop()
        super.onStop()
    }
}

@Composable
private fun ReceiverScreen(viewModel: SessionViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val status by viewModel.status.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val waterfall by viewModel.waterfall.collectAsState()

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (granted) viewModel.start()
    }

    fun onStartStopClicked() {
        if (status.running) {
            viewModel.stop()
        } else if (permissionGranted) {
            viewModel.start()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Text("Ft8Vox", style = MaterialTheme.typography.headlineSmall)

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
            Text(
                "选中 ${status.selectedFreqHz} Hz",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { onStartStopClicked() }) {
                Text(if (status.running) "停止接收" else "开始接收")
            }
            Button(onClick = { viewModel.transmitTest() }) {
                Text("发射测试")
            }
            TextButton(onClick = { viewModel.clearMessages() }) {
                Text("清空列表")
            }
        }

        StatusBar(status)

        // 瀑布
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color.Black),
        ) {
            WaterfallView(
                frame = waterfall,
                selectedFreqHz = status.selectedFreqHz,
                slotParity = status.slotParity,
                onSelectFrequency = { viewModel.selectFrequency(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        FrequencyAxis(waterfall?.fMinHz, waterfall?.maxHz)

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

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
private fun DecodeRow(message: DecodeResult, slotMs: Long, onClick: () -> Unit) {
    val time = if (message.slotUtcMs > 0) {
        val secs = message.slotUtcMs / 1000
        String.format(
            Locale.US,
            "%02d:%02d:%02d",
            (secs / 3600) % 24,
            (secs / 60) % 60,
            secs % 60,
        )
    } else {
        "--:--:--"
    }
    // 偶/奇周期背景分色（按该条报文所属时隙判定）
    val tint = if (message.slotUtcMs > 0 && slotMs > 0) {
        val even = ((message.slotUtcMs / slotMs) % 2L) == 0L
        if (even) Color(0x142962FF) else Color(0x14FF6D00)
    } else {
        Color.Transparent
    }
    Text(
        String.format(
            Locale.US,
            "%s  %+3d dB  DT %+.1f  DF %4d  %s",
            time,
            message.snr,
            message.dt,
            message.df,
            message.text,
        ),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .background(tint)
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp, horizontal = 4.dp),
    )
}
