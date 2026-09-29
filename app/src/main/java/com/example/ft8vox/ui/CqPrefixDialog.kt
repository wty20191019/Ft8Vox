package com.example.ft8vox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.ft8vox.qso.CQ_PREFIX_SLOTS
import com.example.ft8vox.ui.theme.JtdxGreen

/**
 * 「CQ 前缀」编辑弹窗（发射区第 1 行 `CQ … ▾` 的入口，docs/UI-MOBILE.md §28）。
 *
 * 数据模型照旧（`AppSettings.cqPrefixes` + `cqPrefixIndex`）：[CQ_PREFIX_SLOTS] 个格子，前缀插在
 * `CQ` 与我方呼号之间（如 `CQ DX K1ABC FN42`），**空白格子＝普通 CQ**；点一行即选中该格，
 * 六格里的「CQ」槽与自动程序发 CQ 都用选中的那一格。
 *
 * 编辑与选中都先落在本地草稿上，点「确定」才一次性提交（不必每敲一个键就写一次 DataStore）。
 */
@Composable
fun CqPrefixDialog(
    prefixes: List<String>,
    selectedIndex: Int,
    onConfirm: (prefixes: List<String>, index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(prefixes) {
        mutableStateOf(List(CQ_PREFIX_SLOTS) { prefixes.getOrElse(it) { "" } })
    }
    var selected by remember(selectedIndex) {
        mutableStateOf(selectedIndex.coerceIn(0, CQ_PREFIX_SLOTS - 1))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CQ 前缀") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "前缀插在「CQ」与我方呼号之间（如 CQ DX K1ABC FN42）；留空＝普通 CQ。" +
                        "点一行选中它，六格里的「CQ」槽与自动程序发 CQ 都用这一格。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                for (i in 0 until CQ_PREFIX_SLOTS) {
                    val on = i == selected
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 点「●/○ + 序号」这一块＝选中该格（输入框要留给打字，不跟它抢点击）
                        Row(
                            modifier = Modifier
                                .width(44.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .clickable { selected = i }
                                .padding(horizontal = 2.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (on) "●" else "○",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (on) JtdxGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (on) JtdxGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        JtdxTextField(
                            value = draft[i],
                            onValueChange = { v -> draft = draft.toMutableList().also { it[i] = v } },
                            modifier = Modifier.weight(1f),
                            placeholder = "普通 CQ",
                            textStyle = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(draft, selected)
                    onDismiss()
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
