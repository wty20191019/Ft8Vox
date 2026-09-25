package com.example.ft8vox.engine

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * 「含我呼号哔声」提醒音（U7d）。
 *
 * 使用系统 [ToneGenerator]（通知流）播放短促提示音，无需打包音频资源；
 * 与 FT8 发射流（媒体流）互不占用。设备/系统不支持时静默降级，绝不抛出。
 */
class AlertTone {

    private var generator: ToneGenerator? = null
    private var failed = false

    /** 播放一声短提示音（约 150 ms）。 */
    fun beep() {
        if (failed) return
        try {
            val g = generator ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME).also {
                generator = it
            }
            g.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Exception) {
            // 某些模拟器/设备无提示音通道：标记失败后不再重试
            failed = true
        }
    }

    /** 释放底层资源（ViewModel 清理时调用）。 */
    fun release() {
        try {
            generator?.release()
        } catch (_: Exception) {
            // 忽略释放异常
        }
        generator = null
    }

    private companion object {
        /** 音量（0..100）；不宜过大，避免声学耦合时被电台拾取。 */
        const val VOLUME = 70
    }
}
