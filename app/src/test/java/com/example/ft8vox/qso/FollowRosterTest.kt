package com.example.ft8vox.qso

import com.example.ft8vox.engine.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「自动跟踪 CQ（本波段）」自动收录的 JVM 单测（见 `FollowRoster`）。 */
class FollowRosterTest {

    private fun decoded(text: String) = DecodeResult(
        text = text,
        snr = -10,
        dt = 0f,
        df = 1000,
        score = 20,
        slotUtcMs = 0L,
    )

    // ---- pickCqCalls ----

    @Test
    fun pickOnlyCqSkippingSelfIgnoredAndAlreadyFollowed() {
        val picked = FollowRoster.pickCqCalls(
            messages = listOf(
                decoded("CQ W1AW FN42"),
                decoded("DL1ABC W1AW -12"), // 不是 CQ
                decoded("CQ F4FSY JN25"), // 自己
                decoded("CQ JA1ABC PM95"), // 已在跟踪名单
                decoded("CQ XX1XXX AA00"), // 已忽略
                decoded("CQ W1AW FN42"), // 同批重复
                decoded("73"),
            ),
            myCall = "F4FSY",
            ignoredCalls = setOf("XX1XXX"),
            followedCalls = setOf("JA1ABC"),
        )
        assertEquals(listOf("W1AW"), picked)
    }

    @Test
    fun pickKeepsBatchOrderNewestFirst() {
        val picked = FollowRoster.pickCqCalls(
            messages = listOf(decoded("CQ W1AW FN42"), decoded("CQ JA1ABC PM95")),
            myCall = "F4FSY",
        )
        assertEquals(listOf("W1AW", "JA1ABC"), picked)
    }

    @Test
    fun pickSkipsCallsWorkedOnCurrentBand() {
        val picked = FollowRoster.pickCqCalls(
            messages = listOf(
                decoded("CQ W1AW FN42"), // 本波段已通联 → 跳过
                decoded("CQ JA1ABC PM95"), // 本波段没通联过 → 收录
            ),
            myCall = "F4FSY",
            workedCalls = setOf("W1AW"),
        )
        assertEquals(listOf("JA1ABC"), picked)
    }

    // ---- merge ----

    @Test
    fun mergePrependsNewCallsKeepingNewestFirst() {
        val (calls, order) = FollowRoster.merge(
            followed = setOf("OLD111"),
            order = listOf("OLD111"),
            incoming = listOf("BBB222", "AAA111"), // 新→旧
        )
        assertEquals(setOf("OLD111", "AAA111", "BBB222"), calls)
        assertEquals(listOf("BBB222", "AAA111", "OLD111"), order)
    }

    @Test
    fun mergeSkipsCallsAlreadyFollowed() {
        val (calls, order) = FollowRoster.merge(
            followed = setOf("AAA111"),
            order = emptyList(),
            incoming = listOf("AAA111", "BBB222"),
        )
        assertEquals(setOf("AAA111", "BBB222"), calls)
        assertEquals(listOf("BBB222"), order)
    }

    @Test
    fun mergeEvictsOldestAutoCallBeyondCapButKeepsManual() {
        // 手动跟踪的 M 不在 order 里 ⇒ 永远不淘汰
        val first = FollowRoster.merge(setOf("M"), emptyList(), listOf("AAA111"), max = 2)
        assertEquals(setOf("M", "AAA111"), first.first)
        assertEquals(listOf("AAA111"), first.second)

        val second = FollowRoster.merge(first.first, first.second, listOf("BBB222"), max = 2)
        assertEquals(setOf("M", "AAA111", "BBB222"), second.first)
        assertEquals(listOf("BBB222", "AAA111"), second.second)

        // 再加一个：最早的 AAA111 被淘汰，手动跟踪的 M 仍在
        val third = FollowRoster.merge(second.first, second.second, listOf("CCC333"), max = 2)
        assertEquals(setOf("M", "BBB222", "CCC333"), third.first)
        assertEquals(listOf("CCC333", "BBB222"), third.second)
    }

    @Test
    fun mergeDropsStaleOrderEntriesNotInFollowed() {
        // order 里混进已不在名单里的呼号（历史脏数据）→ 忽略，且不会误删别的台
        val (calls, order) = FollowRoster.merge(
            followed = setOf("AAA111"),
            order = listOf("GONE999", "AAA111"),
            incoming = emptyList(),
        )
        assertEquals(setOf("AAA111"), calls)
        assertEquals(listOf("AAA111"), order)
    }

    @Test
    fun mergeCapHoldsAtMax() {
        var calls = emptySet<String>()
        var order = emptyList<String>()
        repeat(FollowRoster.AUTO_MAX + 25) { i ->
            val (c, o) = FollowRoster.merge(calls, order, listOf("CALL$i"))
            calls = c
            order = o
        }
        assertEquals(FollowRoster.AUTO_MAX, order.size)
        assertEquals(FollowRoster.AUTO_MAX, calls.size)
        // 最新收录的在最前，最早的已淘汰
        assertEquals("CALL${FollowRoster.AUTO_MAX + 24}", order.first())
        assertFalse("CALL0" in calls)
        assertTrue("CALL${FollowRoster.AUTO_MAX + 24}" in calls)
    }
}
