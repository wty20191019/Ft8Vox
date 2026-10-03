package com.example.ft8vox.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 真机时序取证：把少量关键行写进 App 外部私有目录文件。
 *
 * 背景：部分机型（如 vivo）系统级屏蔽第三方 App 的 logcat，`adb logcat -s Ft8VoxQso` 一行都拿不到，
 * 真机上的「解码网格 / 对齐 / 发射闸门」时序无法从日志观察。这里用文件补上：
 * `adb pull /sdcard/Android/data/com.example.ft8vox/files/diag.log` 即可取回。
 *
 * 仅 **debuggable** 构建生效（release 直接短路），单文件超过 [MAX_BYTES] 时整体清空重写，
 * 因此对正式包零影响、对调试包也不会无限增长。
 */
object Diag {

    /** 单个诊断文件上限：约几小时会话的量，超过就清空重来。 */
    private const val MAX_BYTES = 512 * 1024L

    @Volatile
    private var file: File? = null
    private val lock = Any()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 在引擎初始化时调用；非 debuggable 或取不到目录时静默关闭。 */
    fun init(context: Context?) {
        file = try {
            if (context == null) {
                null
            } else {
                val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                if (debuggable) context.getExternalFilesDir(null)?.resolve("diag.log") else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun line(msg: String) {
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.length() > MAX_BYTES) f.writeText("")
                f.appendText("${fmt.format(Date())} $msg\n")
            } catch (_: Throwable) {
                // 诊断失败不影响主流程
            }
        }
    }
}
