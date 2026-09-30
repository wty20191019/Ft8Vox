package com.example.ft8vox.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.ft8vox.SessionService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主壳的五个页面（docs/Ft8Vox.md）：**操作 / 频谱 / 地图 / 日志 / 设置**。
 *
 * 频谱从操作页拆出来单独成页，是这一轮改造的核心诉求。
 */
enum class MainTab(val label: String) {
    OPERATE("操作"),
    SPECTRUM("频谱"),
    MAP("地图"),
    LOG("日志"),
    SETTINGS("设置"),
}

/**
 * 应用主壳（docs/Ft8Vox.md）：**竖屏，四行信息头 + 内容 + 底部导航**。
 *
 * - 全局锁定竖屏（`AndroidManifest` 的 `sensorPortrait`）。
 * - 三个 ViewModel 都是 Activity 作用域，切换页面不重建，接收与 QSO 流程不中断。
 * - **录音权限在此统一申请**：操作页的发射动作与频谱页的「开始接收」共用同一条授权链路。
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
    // 从解码菜单「查看日志」跳日志页时，要预填的搜索呼号（同样用序号触发）
    var logFocusCall by rememberSaveable { mutableStateOf<String?>(null) }
    var logFocusSeq by rememberSaveable { mutableStateOf(0) }
    var autoDialogOpen by rememberSaveable { mutableStateOf(false) }
    var helpDialogOpen by rememberSaveable { mutableStateOf(false) }
    // 跟踪列表弹窗由本壳持有（跨页跳转时保持状态）
    var followOpen by rememberSaveable { mutableStateOf(false) }

    val appSettings by settings.settings.collectAsState()
    val status by session.status.collectAsState()
    val messages by session.messages.collectAsState()
    val stats by log.stats.collectAsState()
    val nowMs = rememberUtcNowMs()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // ---- 录音权限：操作页与频谱页共用 ----
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // 录音权限申请期间暂存的待执行动作（点了就直接发，不再有确认弹窗）
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        val action = afterPermission
        afterPermission = null
        if (granted) action?.invoke()
    }

    /** 执行一个需要录音权限的动作：已授权直接执行，否则先申请、授权后补执行。 */
    val request: (action: () -> Unit) -> Unit = { action ->
        if (permissionGranted) {
            action()
        } else {
            afterPermission = action
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** 开始接收（操作页空态、频谱页空态、信息头都走这里）。 */
    val requestStart: () -> Unit = { request { session.start() } }

    // 已授权时自动开始接收（首次进入即可看到瀑布）
    LaunchedEffect(permissionGranted) {
        if (permissionGranted && !status.running) session.start()
    }

    // 通知权限（Android 13+）：前台服务通知需要它才可见；拒绝时服务照常运行，只是不显示通知
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) SessionService.refresh(context)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 返回键：接收中「退到后台继续接收」（由前台服务保活），未接收时保持默认行为（退出）。
    // 各对话框打开时返回键先关对话框（Compose 按「后注册者优先」处理）。
    BackHandler(enabled = status.running) {
        activity?.moveTaskToBack(true)
        Toast.makeText(context, "已退到后台继续接收，可在通知栏「停止接收」", Toast.LENGTH_SHORT).show()
    }

    val dateText = remember(nowMs / 1000L) {
        SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(nowMs))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        MobileInfoHeader(
            status = status,
            messages = messages,
            nowMs = nowMs,
            decodedTotal = status.decodedTotal,
            qsoCount = stats.total,
            queueCount = status.autoQueueSize + if (status.manualTxText != null) 1 else 0,
            dateText = dateText,
            onBandFreq = session::setBandFreq,
            onTxEnabledChange = session::setTxEnabled,
            onOpenSettings = { tab = MainTab.SETTINGS },
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .fillMaxHeight()
                // 内容页自绘（地图平移/缩放、瀑布）不裁剪会画到上方信息头上
                .clipToBounds(),
        ) {
            when (tab) {
                MainTab.OPERATE -> OperateScreen(
                    viewModel = session,
                    settings = appSettings,
                    hasPermission = permissionGranted,
                    request = request,
                    onRequestStart = requestStart,
                    onOpenSettings = { tab = MainTab.SETTINGS },
                    onOpenMap = { call ->
                        mapFocusCall = call
                        mapFocusSeq += 1
                        tab = MainTab.MAP
                    },
                    onOpenLog = { call ->
                        logFocusCall = call
                        logFocusSeq += 1
                        tab = MainTab.LOG
                    },
                    onOpenAutoProgram = { autoDialogOpen = true },
                    followOpen = followOpen,
                    onFollowOpenChange = { followOpen = it },
                )

                MainTab.SPECTRUM -> SpectrumScreen(
                    viewModel = session,
                    settings = appSettings,
                    hasPermission = permissionGranted,
                    onRequestStart = requestStart,
                    onOpenLog = { call ->
                        logFocusCall = call
                        logFocusSeq += 1
                        tab = MainTab.LOG
                    },
                )

                MainTab.MAP -> GridScreen(
                    log = log,
                    session = session,
                    settings = appSettings,
                    onOpenLog = { tab = MainTab.LOG },
                    focusCall = mapFocusCall,
                    focusSeq = mapFocusSeq,
                )

                MainTab.LOG -> LogScreen(
                    log = log,
                    myCall = appSettings.myCall,
                    myGrid = appSettings.myGrid.ifEmpty { null },
                    onOpenSettings = { tab = MainTab.SETTINGS },
                    focusCall = logFocusCall,
                    focusSeq = logFocusSeq,
                )

                MainTab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    log = log,
                    session = session,
                    onHelp = { helpDialogOpen = true },
                )
            }
        }

        MobileBottomNav(tab = tab, onTab = { tab = it })
    }

    if (autoDialogOpen) {
        AutoProgramDialog(
            program = status.autoProgram,
            onOption = session::setAutoOption,
            onDismiss = { autoDialogOpen = false },
        )
    }

    if (helpDialogOpen) {
        JtdxHelpDialog(onDismiss = { helpDialogOpen = false })
    }
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
