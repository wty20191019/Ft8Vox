// Ft8Native.kt —— ft8_w JNI 桥的 Kotlin 侧封装（原样取自 ft8_w/android/Ft8Native.kt）
//
// 说明：包名 com.ft8.nativecore 与 ft8w/jni/ft8_jni.cpp 的 JNI 函数名
// （Java_com_ft8_nativecore_Ft8Native_*）严格对应，两者必须同时保持。
// 接入方式（预编译 .so 或源码 CMake）与完整 API 说明见 ft8_w 文档。

package com.ft8.nativecore

/** 单条解码结果。 */
data class Ft8Message(
    val text: String,
    val snr: Float,
    val dt: Float,
    val freq: Float,
    val score: Int,
    val ldpcErrors: Int,
    val pass: Int,
    val apType: Int,
    val crc: Int,
)

/** 编码配置（对应 ft8_encode_config_t）。 */
data class Ft8EncodeConfig(
    var sampleRate: Int = 12000,
    var baseFreqHz: Float = 1000f,
    var amplitude: Float = 0.5f,
    var symbolBt: Float = 2.0f,
    var leadInSec: Float = 0f,   // VOX 起控保护静音
    var tailSec: Float = 0f,     // VOX 释放保护静音
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        sampleRate.toFloat(), baseFreqHz, amplitude, symbolBt, leadInSec, tailSec
    )
}

/** 解码配置（对应 ft8_decode_config_t）。 */
data class Ft8DecodeConfig(
    var fMinHz: Float = 200f,
    var fMaxHz: Float = 3000f,
    var sampleRate: Int = 12000,
    var timeOsr: Int = 2,
    var freqOsr: Int = 2,
    var maxCandidates: Int = 140,
    var minSyncScore: Int = 10,
    var ldpcIterations: Int = 25,
    var decodeDepth: Int = 0,
    var apMode: Int = 0,
    var enableSubtract: Boolean = false,
    var numThreads: Int = 4,
    var returnDuplicates: Boolean = false,
    var osdDepth: Int = 2,
    var enableLlrRefine: Boolean = false,
    var numBands: Int = 1,
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        fMinHz, fMaxHz, sampleRate.toFloat(), timeOsr.toFloat(), freqOsr.toFloat(),
        maxCandidates.toFloat(), minSyncScore.toFloat(), ldpcIterations.toFloat(),
        decodeDepth.toFloat(), apMode.toFloat(), if (enableSubtract) 1f else 0f,
        numThreads.toFloat(), if (returnDuplicates) 1f else 0f,
        osdDepth.toFloat(), if (enableLlrRefine) 1f else 0f,
        numBands.toFloat()
    )
}

object Ft8Native {
    init {
        System.loadLibrary("ft8")
    }

    // --- 基础 ---
    external fun version(): String
    external fun hashClear()

    // --- 配置默认值 ---
    external fun decodeConfigDefault(): FloatArray
    external fun encodeConfigDefault(): FloatArray

    // --- 编码 ---
    external fun encodeLength(message: String, cfg: FloatArray): Int
    external fun encode(message: String, cfg: FloatArray, out: FloatArray): Int
    external fun encodeTones(message: String): ByteArray

    // --- 整时隙解码 ---
    external fun decodeSlot(
        samples: FloatArray,
        cfg: FloatArray,
        myCall: String?,
        hisCall: String?,
        hisGrid: String?,
    ): Array<String>

    external fun decodeSlotDirect(
        samples: java.nio.FloatBuffer,
        length: Int,
        cfg: FloatArray,
        myCall: String?,
        hisCall: String?,
        hisGrid: String?,
    ): Array<String>

    // --- 流式会话 ---
    external fun sessionCreate(
        cfg: FloatArray,
        myCall: String?,
        hisCall: String?,
        hisGrid: String?,
    ): Long

    external fun sessionFeed(handle: Long, samples: FloatArray): Int
    external fun sessionFinalize(handle: Long): Array<String>
    external fun sessionReset(handle: Long)
    external fun sessionDestroy(handle: Long)

    // ------------------------------------------------------------------
    // 便捷封装：把 JNI 返回的 "text|snr|dt|freq|..." 解析为数据类
    // ------------------------------------------------------------------
    fun decode(
        samples: FloatArray,
        cfg: Ft8DecodeConfig = Ft8DecodeConfig(),
        myCall: String? = null,
        hisCall: String? = null,
        hisGrid: String? = null,
    ): List<Ft8Message> =
        decodeSlot(samples, cfg.toFloatArray(), myCall, hisCall, hisGrid).map(::parse)

    fun decode(
        buffer: java.nio.FloatBuffer,
        length: Int,
        cfg: Ft8DecodeConfig = Ft8DecodeConfig(),
        myCall: String? = null,
        hisCall: String? = null,
        hisGrid: String? = null,
    ): List<Ft8Message> =
        decodeSlotDirect(buffer, length, cfg.toFloatArray(), myCall, hisCall, hisGrid).map(::parse)

    private fun parse(record: String): Ft8Message {
        val p = record.split('|')
        return Ft8Message(
            text = p.getOrElse(0) { "" },
            snr = p.getOrElse(1) { "0" }.toFloatOrNull() ?: 0f,
            dt = p.getOrElse(2) { "0" }.toFloatOrNull() ?: 0f,
            freq = p.getOrElse(3) { "0" }.toFloatOrNull() ?: 0f,
            score = p.getOrElse(4) { "0" }.toIntOrNull() ?: 0,
            ldpcErrors = p.getOrElse(5) { "0" }.toIntOrNull() ?: 0,
            pass = p.getOrElse(6) { "0" }.toIntOrNull() ?: 0,
            apType = p.getOrElse(7) { "0" }.toIntOrNull() ?: 0,
            crc = p.getOrElse(8) { "0" }.toIntOrNull() ?: 0,
        )
    }
}
