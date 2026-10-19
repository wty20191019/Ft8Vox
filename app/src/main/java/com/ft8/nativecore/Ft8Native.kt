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
    val blockN: Int = 0,
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
    /** 候选上限按「按段均分」分配（false，默认）还是按「全频谱全局择优」分配（true）。 */
    var globalCandidates: Boolean = false,
    /** 论文式 N=1/2/3 分组检测最大块长：0=关闭，1..3=启用。 */
    var blockMaxN: Int = 0,
    /** 分组检测起始块长（诊断用，默认 1）。 */
    var blockMinN: Int = 1,
    /** 诊断：跳过瀑布域 N=1，直接走时域分组检测。 */
    var blockSkipFast: Boolean = false,
    /** 触发分组检测的 Costas 同步分下限。 */
    var blockMinScore: Int = 10,
    /** 触发分组检测的 BP 残余错误上限。 */
    var blockMaxBpErrors: Int = 48,
    /** 每遍最多对多少个候选做分组检测（0=不限）。 */
    var maxBlockCandidates: Int = 40,
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        fMinHz, fMaxHz, sampleRate.toFloat(), timeOsr.toFloat(), freqOsr.toFloat(),
        maxCandidates.toFloat(), minSyncScore.toFloat(), ldpcIterations.toFloat(),
        decodeDepth.toFloat(), apMode.toFloat(), if (enableSubtract) 1f else 0f,
        numThreads.toFloat(), if (returnDuplicates) 1f else 0f,
        osdDepth.toFloat(), if (enableLlrRefine) 1f else 0f,
        numBands.toFloat(), if (globalCandidates) 1f else 0f,
        blockMaxN.toFloat(), blockMinN.toFloat(), if (blockSkipFast) 1f else 0f,
        blockMinScore.toFloat(), blockMaxBpErrors.toFloat(), maxBlockCandidates.toFloat()
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
            blockN = p.getOrElse(9) { "0" }.toIntOrNull() ?: 0,
        )
    }
}
