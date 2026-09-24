package com.example.ft8vox

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ft8vox.engine.AudioEngine
import com.example.ft8vox.engine.AudioState
import com.example.ft8vox.engine.Ft8Config
import com.example.ft8vox.engine.Ft8Engine
import com.example.ft8vox.engine.Protocol
import com.example.ft8vox.ui.theme.Ft8VoxTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ft8VoxTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ReceiverScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onDestroy() {
        // 后台不留驻：离开界面即释放音频引擎
        AudioEngine.release()
        super.onDestroy()
    }
}

@Composable
private fun ReceiverScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var protocol by remember { mutableStateOf(Protocol.FT8) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("就绪") }
    var messages by remember { mutableStateOf<List<String>>(emptyList()) }
    var audioState by remember { mutableStateOf<AudioState?>(null) }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) status = "缺少 RECORD_AUDIO 权限"
    }

    // 运行期间轮询解码结果与音频状态（拉取模型，无 native 回调）
    LaunchedEffect(running) {
        while (running) {
            val newMessages = AudioEngine.pollDecoded()
            if (newMessages.isNotEmpty()) {
                messages = (newMessages + messages).take(50)
            }
            audioState = AudioEngine.state()
            delay(200)
        }
    }

    fun startReceiving() {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        try {
            AudioEngine.initialize(Ft8Config(protocol = protocol))
        } catch (e: Exception) {
            status = "初始化失败: ${e.message}"
            return
        }
        val rate = AudioEngine.startCapture(48000)
        if (rate > 0) {
            running = true
            status = "接收中（设备采样率 $rate Hz）"
        } else {
            status = "采集启动失败（错误码 $rate）"
            AudioEngine.release()
        }
    }

    fun stopReceiving() {
        AudioEngine.stopCapture()
        AudioEngine.release()
        running = false
        audioState = null
        status = "已停止"
    }

    fun transmitTest() {
        try {
            if (!running) AudioEngine.initialize(Ft8Config(protocol = protocol))
            val pcm = Ft8Engine.encode("CQ F4FSY JN25", 1000f, protocol, 12000)
            val rate = AudioEngine.startPlayback(48000)
            if (rate <= 0) {
                status = "播放启动失败（错误码 $rate）"
                return
            }
            val written = AudioEngine.play(pcm)
            status = "已发射测试 CQ（$written 帧 @ $rate Hz）"
        } catch (e: Exception) {
            status = "发射失败: ${e.message}"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ft8Vox", style = MaterialTheme.typography.headlineSmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in Protocol.entries) {
                FilterChip(
                    selected = protocol == p,
                    enabled = !running,
                    onClick = { protocol = p },
                    label = { Text(p.name) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (running) stopReceiving() else startReceiving() }) {
                Text(if (running) "停止接收" else "开始接收")
            }
            Button(onClick = { transmitTest() }) {
                Text("发射测试")
            }
        }

        Text("状态：$status", style = MaterialTheme.typography.bodyMedium)

        audioState?.let { s ->
            Text(
                "时隙 ${s.slotMs} ms｜下一个时隙 ${s.msToNextSlot} ms｜" +
                    "进度 ${(s.slotProgress * 100).toInt()}%｜丢帧 ${s.droppedSamples}｜已解码 ${s.slotsDecoded} 个时隙",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()

        Text("解码结果（${messages.size}）", style = MaterialTheme.typography.titleSmall)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (messages.isEmpty()) {
                Text("（暂无）", style = MaterialTheme.typography.bodySmall)
            }
            for (message in messages) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
