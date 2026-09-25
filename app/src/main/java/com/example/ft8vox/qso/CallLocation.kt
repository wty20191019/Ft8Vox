package com.example.ft8vox.qso

/**
 * 呼号前缀 → 归属地坐标（new_ui.md §4.3）。
 *
 * 仅用于「报文里没有网格信息」时在地图上立标。实体判定与坐标统一由 [Dxcc] 提供，
 * 本对象保留 [Place] / [locate] 供地图与既有调用方使用（U7 起改为精确实体表）。
 *
 * 纯 Kotlin，无 Android 依赖，便于 JVM 单测。
 */
object CallLocation {

    /** 归属地：中心坐标与规范实体名。 */
    data class Place(val lat: Double, val lon: Double, val name: String)

    /** 解析呼号归属地；无法识别返回 `null`。 */
    fun locate(call: String?): Place? =
        Dxcc.resolve(call)?.let { Place(it.lat, it.lon, it.name) }

    /** 解析呼号所属 DXCC 实体（含 CQ / ITU 区域）；无法识别返回 `null`。 */
    fun entity(call: String?): Dxcc.Entity? = Dxcc.resolve(call)
}
