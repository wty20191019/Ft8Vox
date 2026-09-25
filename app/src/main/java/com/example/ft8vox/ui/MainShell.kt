package com.example.ft8vox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部导航的四个页面（new_ui.md §2：操作 / 地图 / 日志 / 设置）。
 */
enum class MainTab(val label: String) {
    OPERATE("操作"),
    MAP("地图"),
    LOG("日志"),
    SETTINGS("设置"),
}

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.OPERATE -> Icons.Filled.Home
    MainTab.MAP -> Icons.Filled.Place
    MainTab.LOG -> Icons.AutoMirrored.Filled.List
    MainTab.SETTINGS -> Icons.Filled.Settings
}

/**
 * 应用主壳（new_ui.md §0/§1/§2/§7）：固定顶栏 + 底部状态条 + 底部四页导航。
 *
 * 三个 ViewModel 都是 Activity 作用域，切换页面不会重建，因此接收与 QSO 流程不中断。
 */
@Composable
fun MainShell(
    session: SessionViewModel,
    log: LogViewModel,
    settings: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.OPERATE) }
    // 从操作页双击解码行跳地图时，要定位的呼号（与序号配合，便于重复触发）
    var mapFocusCall by rememberSaveable { mutableStateOf<String?>(null) }
    var mapFocusSeq by rememberSaveable { mutableStateOf(0) }
    val appSettings by settings.settings.collectAsState()
    val status by session.status.collectAsState()
    val messages by session.messages.collectAsState()
    val stats by log.stats.collectAsState()
    val nowMs = rememberUtcNowMs()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Ft8VoxTopBar(
                status = status,
                appSettings = appSettings,
                nowMs = nowMs,
                onBand = session::setBand,
                onProtocol = session::selectProtocol,
                onOpenSettings = { tab = MainTab.SETTINGS },
            )
        },
        bottomBar = {
            Column {
                BottomStatusBar(
                    running = status.running,
                    txing = status.txing,
                    decodePerMin = decodesPerMinute(messages, nowMs),
                    decodedTotal = status.decodedTotal,
                    qsoCount = stats.total,
                    queueCount = if (status.manualTxText != null) 1 else 0,
                    timeWarning = timeSyncWarning(messages.firstOrNull()?.dt),
                    voxLevelDb = status.voxLevelDb,
                    voxOpen = status.voxOpen,
                )
                NavigationBar {
                    for (t in MainTab.entries) {
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon(), contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (tab) {
                MainTab.OPERATE -> OperateScreen(
                    viewModel = session,
                    settings = appSettings,
                    onOpenSettings = { tab = MainTab.SETTINGS },
                    onOpenMap = { call ->
                        mapFocusCall = call
                        mapFocusSeq += 1
                        tab = MainTab.MAP
                    },
                    onOpenLog = { tab = MainTab.LOG },
                )
                MainTab.MAP -> GridScreen(
                    log = log,
                    session = session,
                    settings = appSettings,
                    onUpdateSettings = settings::update,
                    onOpenLog = { tab = MainTab.LOG },
                    focusCall = mapFocusCall,
                    focusSeq = mapFocusSeq,
                )
                MainTab.LOG -> LogScreen(
                    log = log,
                    myCall = appSettings.myCall,
                    myGrid = appSettings.myGrid.ifEmpty { null },
                    onOpenSettings = { tab = MainTab.SETTINGS },
                )
                MainTab.SETTINGS -> SettingsScreen(settings = settings, log = log, session = session)
            }
        }
    }
}
