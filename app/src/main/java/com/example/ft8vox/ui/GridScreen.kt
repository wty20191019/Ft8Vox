package com.example.ft8vox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.ft8vox.grid.Maidenhead

/**
 * 网格页（阶段 7a 先给统计列表）。
 *
 * 阶段 7d 会在此处叠加离线 Canvas 网格地图：按 未通联/已通联/已确认 着色，
 * 支持缩放平移与点击查看该网格的通联。
 */
@Composable
fun GridScreen(log: LogViewModel, modifier: Modifier = Modifier) {
    val entries by log.entries.collectAsState()

    val grids = remember(entries) {
        entries.mapNotNull { it.theirGrid?.uppercase()?.takeIf { g -> g.isNotBlank() } }.toSet()
    }
    val fields = remember(grids) { grids.mapNotNull { Maidenhead.field(it) }.toSet() }
    val fieldCounts = remember(grids) {
        grids.groupingBy { Maidenhead.field(it) ?: "?" }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }
    val confirmedGrids = remember(entries) {
        entries.filter { it.qslRcvd == "Y" || it.lotwRcvd == "Y" }
            .mapNotNull { it.theirGrid?.uppercase()?.takeIf { g -> g.isNotBlank() } }
            .toSet()
    }

    Column(modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text("网格", style = MaterialTheme.typography.titleLarge)

        Text(
            "已通联网格 ${grids.size}｜已确认 ${confirmedGrids.size}｜大网格 ${fields.size} / 324",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        LinearProgressIndicator(
            progress = { fields.size / 324f },
            modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 4.dp),
        )
        Text(
            "离线网格地图将在阶段 7d 落地，这里先给出统计。",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        if (grids.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("还没有带网格的通联记录", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text("大网格", style = MaterialTheme.typography.titleSmall)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(fieldCounts, key = { it.first }) { (field, count) ->
                    val squares = grids.filter { Maidenhead.field(it) == field }.sorted()
                    Column(Modifier.padding(vertical = 2.dp)) {
                        Row {
                            Text(
                                field,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                "  ${count} 个网格",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            squares.joinToString(" "),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
