package com.example.ft8vox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 会话 ↔ 前台服务之间的**进程内**单向通道（阶段 9：后台保活）。
 *
 * 会话状态仍由 [com.example.ft8vox.ui.SessionViewModel] 持有并驱动；本服务只负责
 * 「以 `microphone` 类型进入前台 + 常驻通知」。两者活在同一个进程里，因此无需
 * `bindService` 或广播，用两个流传递即可：
 *
 * - [statusText]：ViewModel → 服务，通知副标题文案；
 * - [stopRequests]：服务 → ViewModel，用户在通知里点「停止接收」。
 */
object SessionServiceBridge {
    /** 通知栏副标题（会话状态文案），由 ViewModel 写入。 */
    val statusText = MutableStateFlow("接收中")

    /**
     * 通知栏「停止接收」动作：ViewModel 收集后停止会话。
     *
     * 用无重放的 [MutableSharedFlow]：只有会话进行中（有订阅者）时该动作才有意义。
     */
    val stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/**
 * 接收前台服务：让采集 / 解码在**息屏或切到后台**后继续运行。
 *
 * 为什么需要它：Android 14+ 起，应用进入后台后只有**前台服务**才能继续访问麦克风，
 * 否则采集会被系统静音。本服务同时把进程提升为前台服务优先级，降低被回收的概率。
 *
 * 服务本身不碰音频引擎（引擎由 ViewModel 持有），只做两件事：
 * 1. `startForeground`（类型 `microphone`）；
 * 2. 常驻通知显示状态，并提供「停止接收」快捷动作。
 *
 * 生命周期与会话同步：`SessionViewModel.start()` 启动本服务，`stop()` / `onCleared()`
 * 停止本服务；服务用 `START_NOT_STICKY`，进程被杀后不会在没有引擎的情况下空跑。
 */
class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 是否已经进入前台 —— 未进入前拒绝刷新通知，避免「空会话」也弹通知。 */
    private var foregrounded = false

    /** 已开始收尾（收到停止请求 / 正在销毁）：此后不再刷新通知，避免残留僵尸通知。 */
    private var stopping = false

    private var collecting = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 通知栏「停止接收」：交给 ViewModel 停止会话（它才是引擎的主人），随后自停
                stopping = true
                SessionServiceBridge.stopRequests.tryEmit(Unit)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH -> {
                // 通知权限刚授予：只把通知重贴一次，不改会话状态
                if (!foregrounded) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            else -> Unit
        }
        foregrounded = true
        enterForeground(buildNotification(SessionServiceBridge.statusText.value))
        startCollecting()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** 状态文案变化即刷新通知（`StateFlow` 只在值真的变化时发射，天然去抖）。 */
    private fun startCollecting() {
        if (collecting) return
        collecting = true
        scope.launch {
            SessionServiceBridge.statusText.collect { text ->
                if (foregrounded && !stopping) enterForeground(buildNotification(text))
            }
        }
    }

    /** 进入前台。API 30+ 显式声明 `microphone` 类型；更低版本沿用清单里声明的类型。 */
    private fun enterForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ft8vox)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "停止接收", stop)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "接收服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "在后台继续 FT8 / FT4 接收与发射"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "ft8vox.session"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.example.ft8vox.action.STOP_SESSION"
        private const val ACTION_REFRESH = "com.example.ft8vox.action.REFRESH_SESSION"

        /** 启动前台服务（会话开始接收时调用）。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        }

        /** 停止前台服务（会话停止或 ViewModel 销毁时调用）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, SessionService::class.java))
        }

        /**
         * 重贴一次通知。
         *
         * 用于「首次运行」：通知权限弹窗通常在服务启动之后才被授予，此前 `startForeground`
         * 的请求会被系统拦下且不会自动补发，需要授权后主动刷新一次。服务未在前台时无操作。
         */
        fun refresh(context: Context) {
            try {
                context.startService(
                    Intent(context, SessionService::class.java).setAction(ACTION_REFRESH),
                )
            } catch (_: IllegalStateException) {
                // 后台禁止启动服务：忽略；通知会在下次会话启动时正常出现
            }
        }
    }
}
