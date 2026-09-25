package com.example.ft8vox.engine

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 输入/输出音频设备选项（U7c）。
 *
 * [id] 即 `AudioDeviceInfo.getId()`，与 AAudio `AAudioStreamBuilder_setDeviceId`
 * 使用同一套编号；[id] <= 0 表示不指定、走系统默认。
 */
data class AudioDevice(
    val id: Int,
    val name: String,
    /** 设备类型的中文标签（内置/耳机/USB 等）。 */
    val kind: String,
)

/**
 * 音频设备枚举（U7c）。
 *
 * 仅做枚举与字符串编解码，不持有 Context，方便单测覆盖 [parseId]/[formatId]。
 * 设备 id 可能随插拔/重启变化，因此「系统默认」是更稳妥的默认值。
 */
object AudioDevices {

    /** 系统默认（不指定设备）。 */
    val DEFAULT = AudioDevice(0, "系统默认", "")

    /** 当前可见的输入设备（首项恒为系统默认）。 */
    fun inputs(context: Context): List<AudioDevice> =
        list(context, AudioManager.GET_DEVICES_INPUTS)

    /** 当前可见的输出设备（首项恒为系统默认）。 */
    fun outputs(context: Context): List<AudioDevice> =
        list(context, AudioManager.GET_DEVICES_OUTPUTS)

    /** 把设置里保存的设备 id 字符串解析为 id；空/非法值回退 0（系统默认）。 */
    fun parseId(value: String): Int = value.trim().toIntOrNull()?.takeIf { it > 0 } ?: 0

    /** 把设备 id 编码为设置字符串；<=0 编码为空串（系统默认）。 */
    fun formatId(id: Int): String = if (id > 0) id.toString() else ""

    /** 已保存设备在给定设备表中的显示文本（找不到时回退「系统默认」）。 */
    fun label(devices: List<AudioDevice>, saved: String): String {
        val id = parseId(saved)
        val d = devices.firstOrNull { it.id == id } ?: return DEFAULT.name
        return if (d.kind.isEmpty()) d.name else "${d.name} · ${d.kind}"
    }

    private fun list(context: Context, which: Int): List<AudioDevice> {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return listOf(DEFAULT)
        val devices = try {
            manager.getDevices(which)
        } catch (_: Exception) {
            return listOf(DEFAULT)
        }
        // 同名同类设备可能重复（如多路 USB），按 id 去重并保持顺序
        val seen = HashSet<Int>()
        val options = ArrayList<AudioDevice>(devices.size + 1)
        options += DEFAULT
        for (d in devices) {
            if (!seen.add(d.id)) continue
            options += AudioDevice(d.id, deviceName(d), typeLabel(d.type))
        }
        return options
    }

    private fun deviceName(d: AudioDeviceInfo): String {
        val product = d.productName?.toString()?.trim().orEmpty()
        return if (product.isEmpty()) typeLabel(d.type) else product
    }

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机（无麦）"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙 SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙 A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 设备"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 配件"
        AudioDeviceInfo.TYPE_TELEPHONY -> "通话"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "模拟线路"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "数字线路"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "总线"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "远端混音"
        else -> "其他($type)"
    }
}
