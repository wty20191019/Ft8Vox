package com.example.ft8vox.data.settings

/**
 * 清理旧版「自动收录 CQ 台」写进 ⭐ 跟踪名单的呼号（纯逻辑，可 JVM 单测）。
 *
 * 背景：该功能已于 `3ed6562` 整个删除（照 FT8CN 改为不再自动写名单），但**当时已经写进
 * `follow_calls` 的条目不会被自动清掉**。而「自动跟踪 CQ（本波段）」在
 * `AutoProgramSelector.collect` 里把 ⭐ 名单当作例外（照 FT8CN `callsignInFollow`）——
 * 于是这些残留条目会让那个开关**看起来关不掉**（关掉后仍自动呼叫名单里的 CQ 台）。
 *
 * 这里按旧版 `auto_follow_order` 记录（只含自动收录、手动跟踪的不在其中）把残留从名单里
 * 剔除，让名单恢复成「只由用户手动增删」的口径。呼号比较统一大写去空白。
 *
 * @param followCalls 持久化的跟踪名单原值（可能含残留）
 * @param legacyAutoFollow 旧版 `auto_follow_order` 记录的自动收录呼号
 * @return 剔除残留后的跟踪名单
 */
internal fun purgeLegacyAutoFollow(
    followCalls: Set<String>,
    legacyAutoFollow: Collection<String>,
): Set<String> {
    if (legacyAutoFollow.isEmpty()) return followCalls
    val legacy = legacyAutoFollow
        .mapNotNull { it.trim().uppercase().takeIf { c -> c.isNotEmpty() } }
        .toSet()
    return followCalls - legacy
}
