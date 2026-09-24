package com.example.ft8vox.data.adif

/**
 * ADIF 读写（纯 Kotlin，便于 JVM 单测）。
 *
 * 兼容性要点：
 * - 标签大小写不敏感；
 * - 支持 `<TAG:len>value` 与 `<TAG:len:type>value`，也容忍缺失长度的 `<TAG>value`；
 * - 长度按 **UTF-8 字节数**计（ADIF 3.x 规定），因此解析在字节层面进行；
 * - 头部（`<EOH>` 之前）忽略，`<EOR>` 分隔记录。
 */
object AdifCodec {

    private const val ADIF_VERSION = "3.1.4"

    private val LT = '<'.code.toByte()
    private val GT = '>'.code.toByte()

    /** 仅出现在头部的标签，解析时忽略，避免被当成一条记录。 */
    private val HEADER_TAGS = setOf("ADIF_VER", "PROGRAMID", "PROGRAMVERSION", "CREATED_TIMESTAMP")

    /** 导出时的字段优先顺序，便于人工阅读与版本 diff。 */
    private val PREFERRED_ORDER = listOf(
        "CALL", "QSO_DATE", "TIME_ON", "BAND", "FREQ", "MODE", "SUBMODE",
        "RST_SENT", "RST_RCVD", "GRIDSQUARE", "QSL_RCVD", "LOTW_QSL_RCVD",
        "MY_CALL", "STATION_CALLSIGN", "MY_GRIDSQUARE", "COMMENT",
    )

    /** 生成 ADIF 文本（含头部）。 */
    fun encode(records: List<AdifRecord>, programId: String = "Ft8Vox"): String {
        val sb = StringBuilder()
        sb.append("ADIF export from ").append(programId).append('\n')
        sb.append(field("ADIF_VER", ADIF_VERSION)).append('\n')
        sb.append(field("PROGRAMID", programId)).append('\n')
        sb.append("<EOH>\n\n")
        for (record in records) {
            val ordered = LinkedHashMap<String, String>()
            for (tag in PREFERRED_ORDER) {
                record.fields[tag]?.takeIf { it.isNotEmpty() }?.let { ordered[tag] = it }
            }
            for ((tag, value) in record.fields) {
                if (tag !in ordered && value.isNotEmpty()) ordered[tag] = value
            }
            for ((tag, value) in ordered) sb.append(field(tag, value))
            sb.append("<EOR>\n")
        }
        return sb.toString()
    }

    /** 解析 ADIF 文本。 */
    fun decode(text: String): List<AdifRecord> = decode(text.toByteArray(Charsets.UTF_8))

    /** 解析 ADIF 字节流（长度按字节计，故以字节为解析单位）。 */
    fun decode(bytes: ByteArray): List<AdifRecord> {
        val records = mutableListOf<AdifRecord>()
        var current: MutableMap<String, String>? = null

        var i = if (
            bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            3 // 跳过 UTF-8 BOM
        } else {
            0
        }

        while (i < bytes.size) {
            if (bytes[i] != LT) {
                i++
                continue
            }
            val close = indexOfByte(bytes, GT, i + 1)
            if (close < 0) break

            val header = String(bytes, i + 1, close - i - 1, Charsets.US_ASCII)
            val colon = header.indexOf(':')
            val tag = (if (colon >= 0) header.substring(0, colon) else header).trim().uppercase()
            val declaredLen = if (colon >= 0) {
                header.substring(colon + 1).substringBefore(':').trim().toIntOrNull()
            } else {
                null
            }

            var pos = close + 1
            val value: String
            if (declaredLen != null) {
                val end = (pos + declaredLen).coerceAtMost(bytes.size)
                value = String(bytes, pos, end - pos, Charsets.UTF_8)
                pos = end
            } else {
                var scan = pos
                while (scan < bytes.size && bytes[scan] != LT) scan++
                value = String(bytes, pos, scan - pos, Charsets.UTF_8)
                pos = scan
            }
            i = pos

            when (tag) {
                "", "EOH" -> Unit
                "EOR" -> {
                    current?.let { if (it.isNotEmpty()) records.add(AdifRecord(it)) }
                    current = null
                }
                in HEADER_TAGS -> Unit
                else -> {
                    val map = current ?: mutableMapOf<String, String>().also { current = it }
                    map[tag] = value.trim()
                }
            }
        }
        current?.let { if (it.isNotEmpty()) records.add(AdifRecord(it)) }
        return records
    }

    private fun field(tag: String, value: String): String =
        "<$tag:${value.toByteArray(Charsets.UTF_8).size}>$value"

    private fun indexOfByte(bytes: ByteArray, target: Byte, from: Int): Int {
        var i = from
        while (i < bytes.size) {
            if (bytes[i] == target) return i
            i++
        }
        return -1
    }
}
