package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult

/**
 * 「自动收录 CQ 台」的**纯逻辑**（可 JVM 单测）。
 *
 * 与 FT8CN 的**有意偏离**：FT8CN 的 `autoFollowCQ` 只把 CQ 报文推送到「呼叫」列表，其帮助文件
 * `auto_follow_help.txt` 明确写「该呼号不会被长久保存到关注的呼号数据库中」；本机改为**真的写入
 * 关注名单**（2026-09-27 用户决定），这样 ⭐「关注呼号列表」会随解码自动增长，关掉开关后这些台
 * 仍按「名单例外」被自动呼叫。
 */
object FollowRoster {

    /**
     * 自动收录的容量上限；超出后淘汰**最早收录**的。
     *
     * **手动关注**（长按菜单「关注」）的呼号不占此额度、也永远不会被淘汰——它们不在
     * `AppSettings.autoFollowOrder` 里。
     */
    const val AUTO_MAX = 100

    /**
     * 从一批解码里挑出可自动收录的呼号（保持入参顺序；解码列表为「新→旧」，故结果即「最近在前」）。
     *
     * 只收 **CQ** 报文；跳过自己、[ignoredCalls]、以及[followedCalls]里已有的；同一批去重。
     */
    fun pickCqCalls(
        messages: List<DecodeResult>,
        myCall: String,
        ignoredCalls: Set<String> = emptySet(),
        followedCalls: Set<String> = emptySet(),
    ): List<String> {
        val out = ArrayList<String>()
        for (m in messages) {
            val p = MessageParser.parse(m.text)
            if (!p.isCq) continue
            val from = p.from?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: continue
            if (from.equals(myCall, ignoreCase = true)) continue
            if (from in ignoredCalls || from in followedCalls || from in out) continue
            out += from
        }
        return out
    }

    /**
     * 把 [incoming] 并入名单，返回新的 `(关注呼号集合, 自动收录顺序)`。
     *
     * [order] ＝「自动收录」的呼号、**最近在前**，恒为 [followed] 的子集；超出 [max] 时从尾部
     * （最早收录）淘汰，并从 [followed] 一并移除。手动关注的呼号不在 [order] 里，故不会被淘汰。
     */
    fun merge(
        followed: Set<String>,
        order: List<String>,
        incoming: List<String>,
        max: Int = AUTO_MAX,
    ): Pair<Set<String>, List<String>> {
        var calls = followed
        // 先修正不变式 order ⊆ calls，避免历史脏数据在淘汰时误删手动关注的台
        var ord = order.filter { it in calls }.distinct()
        // [incoming] 为「新→旧」；整段放到最前（保持内部顺序），即最近的仍在最前
        val fresh = incoming.filter { it !in calls }.distinct()
        if (fresh.isNotEmpty()) {
            calls = calls + fresh
            ord = fresh + ord
        }
        while (ord.size > max) {
            val oldest = ord.last()
            ord = ord.dropLast(1)
            calls = calls - oldest
        }
        return calls to ord
    }
}
