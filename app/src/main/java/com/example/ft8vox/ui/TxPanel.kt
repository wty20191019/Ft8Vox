package com.example.ft8vox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.qso.TxCompose
import com.example.ft8vox.qso.TxMessageKind
import com.example.ft8vox.ui.theme.JtdxBorder
import com.example.ft8vox.ui.theme.JtdxGreen
import com.example.ft8vox.ui.theme.JtdxPanel
import com.example.ft8vox.ui.theme.VoxError
import com.example.ft8vox.ui.theme.VoxTxRed

/**
 * 底发射区（docs/UI-JTDX.md §4）：取代原来的「发射抽屉」。
 *
 * 左列 = 发送总开关 / 生成信息 / CQ 前缀 / 自定义报文 / 发送·停止·自动程序 / 待发预览；
 * 右列 = **六个报文槽 Tx1–Tx6**，内容即现有六步报文（网格 / 报告 / R报告 / RR73 / 73 / CQ），
 * 点一下即排到下一个我方时隙（CQ 槽走 `startCq`）。
 *
 * 闸门仍是「发送总开关」（默认关 = 只接收）。
 */
@Composable
fun TxPanel(
    status: ReceiverStatus,
    settings: AppSettings,
    messages: List<DecodeResult>,
    targetCall: String?,
    onSendOnce: (String) -> Unit,
    onStartCq: () -> Unit,
    onStopTx: () -> Unit,
    onTxEnabledChange: (Boolean) -> Unit,
    onCqPrefixIndex: (Int) -> Unit,
    onOpenAutoProgram: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val myCall = status.myCall
    val myGrid = status.myGrid
    val target = targetCall?.takeIf { it.isNotBlank() } ?: status.qso.theirCall
    val reportSent = status.qso.reportSent ?: TxCompose.reportFor(messages, target)
    val reportReceived = status.qso.reportReceived ?: reportSent
    val cqPrefix = settings.cqPrefix

    // 六个槽的报文：每帧现算，永远与六步序列一致（docs/UI-JTDX.md §4）
    val slots = remember(target, myCall, myGrid, reportSent, reportReceived, cqPrefix) {
        listOf(
            TxMessageKind.GRID to TxCompose.compose(TxMessageKind.GRID, target, myCall, myGrid),
            TxMessageKind.REPORT to TxCompose.compose(TxMessageKind.REPORT, target, myCall, myGrid, reportSent),
            TxMessageKind.ROGER to TxCompose.compose(TxMessageKind.ROGER, target, myCall, myGrid, reportReceived),
            TxMessageKind.RR73 to TxCompose.compose(TxMessageKind.RR73, target, myCall, myGrid),
            TxMessageKind.SEVENTY_THREE to TxCompose.compose(TxMessageKind.SEVENTY_THREE, target, myCall, myGrid),
            TxMessageKind.CQ to TxCompose.compose(TxMessageKind.CQ, null, myCall, myGrid, cqPrefix = cqPrefix),
        )
    }

    var custom by remember { mutableStateOf("") }
    var prefixOpen by remember { mutableStateOf(false) }
    val tone = status.displayTxText

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(JtdxPanel)
            .border(1.dp, JtdxBorder)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- 左列：闸门与报文构造 ----
            Column(
                modifier = Modifier.width(232.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = status.txEnabled,
                        onCheckedChange = onTxEnabledChange,
                        colors = SwitchDefaults.colors(checkedThumbColor = JtdxGreen),
                    )
                    Text("发送总开关", style = MaterialTheme.typography.labelMedium)
                    JtdxCaption(
                        if (status.txEnabled) "允许发射" else "只接收",
                        color = if (status.txEnabled) JtdxGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    JtdxButton(
                        text = "生成信息",
                        onClick = {
                            custom = tone
                                ?: slots.firstOrNull { it.first == TxMessageKind.CQ }?.second
                                ?: ""
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        JtdxButton(
                            text = "CQ：${cqPrefix.ifEmpty { "无" }} ▾",
                            onClick = { prefixOpen = true },
                            modifier = Modifier.width(96.dp),
                            active = cqPrefix.isNotEmpty(),
                        )
                        DropdownMenu(expanded = prefixOpen, onDismissRequest = { prefixOpen = false }) {
                            for ((i, p) in settings.cqPrefixes.withIndex()) {
                                DropdownMenuItem(
                                    text = { Text(if (i == settings.cqPrefixIndex) "● ${p.ifEmpty { "无（普通 CQ）" }}" else "　${p.ifEmpty { "无（普通 CQ）" }}") },
                                    onClick = {
                                        prefixOpen = false
                                        onCqPrefixIndex(i)
                                    },
                                )
                            }
                        }
                    }
                }

                JtdxTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "自定义报文（「生成信息」可自动填入）",
                    textStyle = MaterialTheme.typography.labelSmall,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    JtdxButton(
                        text = "发送",
                        onClick = { onSendOnce(custom) },
                        modifier = Modifier.weight(1f),
                        enabled = custom.isNotBlank(),
                        active = status.txArmed && !status.txing,
                    )
                    JtdxButton(
                        text = "停止发射",
                        onClick = onStopTx,
                        modifier = Modifier.weight(1f),
                        enabled = status.txing,
                        accent = VoxTxRed,
                        active = status.txing,
                    )
                    JtdxButton(
                        text = "自动程序",
                        onClick = onOpenAutoProgram,
                        modifier = Modifier.weight(1f),
                        active = status.txEnabled,
                    )
                }

                Text(
                    "待发：${tone ?: "空闲（无下次发送）"}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (status.txing) VoxTxRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            VerticalDivider(
                modifier = Modifier.heightIn(min = 100.dp),
                color = JtdxBorder,
            )

            // ---- 右列：六个报文槽 ----
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for ((index, slot) in slots.withIndex()) {
                    val (kind, text) = slot
                    val isCq = kind == TxMessageKind.CQ
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        JtdxLed(
                            on = text != null && tone == text,
                            color = if (status.txing) VoxTxRed else JtdxGreen,
                        )
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(12.dp),
                        )
                        JtdxButton(
                            text = text ?: "—",
                            onClick = { if (isCq) onStartCq() else text?.let(onSendOnce) },
                            modifier = Modifier.weight(1f),
                            enabled = text != null,
                            textStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                        JtdxCaption(kind.label, modifier = Modifier.width(34.dp))
                    }
                }
                if (!status.txEnabled) {
                    Text(
                        "发送总开关关闭：只接收（打开后才允许发射）",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoxError,
                        maxLines = 1,
                    )
                }
            }
        }

        if (status.qso.active) {
            Text(
                "QSO：${status.qso.description}",
                style = MaterialTheme.typography.labelSmall,
                color = JtdxGreen,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
