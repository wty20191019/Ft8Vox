package com.example.ft8vox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.example.ft8vox.qso.AutoMode

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
    var autoDialogOpen by rememberSaveable { mutableStateOf(false) }
    // 待确认的「启用自动程序」模式（从「0 手动模式」切到 1/2 时的防误发确认）
    var confirmAutoMode by remember { mutableStateOf<AutoMode?>(null) }
    val appSettings by settings.settings.collectAsState()
    val status by session.status.collectAsState()
    val messages by session.messages.collectAsState()
    val stats by log.stats.collectAsState()
    val nowMs = rememberUtcNowMs()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // 返回键：接收中「退到后台继续接收」（由前台服务保活），未接收时保持默认行为（退出）。
    // Compose 的 BackHandler 只在没有弹窗 / 底部抽屉（各自独立窗口）消费返回键时才会触发。
    BackHandler(enabled = status.running) {
        activity?.moveTaskToBack(true)
        Toast.makeText(context, "已退到后台继续接收，可在通知栏「停止接收」", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Ft8VoxTopBar(
                status = status,
                appSettings = appSettings,
                nowMs = nowMs,
                onBandFreq = session::setBandFreq,
                onProtocol = session::selectProtocol,
                onOpenSettings = { tab = MainTab.SETTINGS },
                onAutoProgram = { autoDialogOpen = true },
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
                    onOpenAutoProgram = { autoDialogOpen = true },
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

    if (autoDialogOpen) {
        AutoProgramDialog(
            program = status.autoProgram,
            onSetMode = { m -> requestAutoMode(m, status, session) { confirmAutoMode = it } },
            onOption = session::setAutoOption,
            onDismiss = { autoDialogOpen = false },
        )
    }

    confirmAutoMode?.let { m ->
        AutoEnableConfirmDialog(
            mode = m,
            status = status,
            onConfirm = {
                confirmAutoMode = null
                session.setAutoMode(m)
            },
            onDismiss = { confirmAutoMode = null },
        )
    }
}

/**
 * 点击自动程序工作模式：从「0 手动模式」切到 1/2 时先记下待确认模式（由调用方渲染
 * [AutoEnableConfirmDialog]），其余情况直接生效。
 */
internal fun requestAutoMode(
    mode: AutoMode,
    status: ReceiverStatus,
    session: SessionViewModel,
    onNeedConfirm: (AutoMode) -> Unit,
) {
    if (mode.enabled && !status.autoProgram.mode.enabled) onNeedConfirm(mode)
    else session.setAutoMode(mode)
}

/** 取宿主 Activity（Compose 的 `LocalContext` 可能是被包装过的 Context）。 */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
