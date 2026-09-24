package com.example.ft8vox.ui

import androidx.compose.foundation.layout.Box
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

/** 底部导航的四个页面。 */
enum class MainTab(val label: String) {
    OPERATE("操作"),
    LOG("日志"),
    GRID("网格"),
    SETTINGS("设置"),
}

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.OPERATE -> Icons.Filled.Home
    MainTab.LOG -> Icons.AutoMirrored.Filled.List
    MainTab.GRID -> Icons.Filled.Place
    MainTab.SETTINGS -> Icons.Filled.Settings
}

/**
 * 应用主壳：底部四页导航。
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
    val appSettings by settings.settings.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
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
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (tab) {
                MainTab.OPERATE -> OperateScreen(viewModel = session, settings = appSettings)
                MainTab.LOG -> LogScreen(
                    log = log,
                    myCall = appSettings.myCall,
                    myGrid = appSettings.myGrid.ifEmpty { null },
                    onOpenSettings = { tab = MainTab.SETTINGS },
                )
                MainTab.GRID -> GridScreen(log = log)
                MainTab.SETTINGS -> SettingsScreen(settings = settings, log = log)
            }
        }
    }
}
