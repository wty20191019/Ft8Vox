package com.example.ft8vox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.qso.TxCompose
import com.example.ft8vox.qso.TxMessageKind
import com.example.ft8vox.ui.theme.JtdxBorder
import com.example.ft8vox.ui.theme.JtdxButton
import com.example.ft8vox.ui.theme.JtdxGreen
import com.example.ft8vox.ui.theme.JtdxPanel
import com.example.ft8vox.ui.theme.JtdxPanelHi
import com.example.ft8vox.ui.theme.VoxError
import com.example.ft8vox.ui.theme.VoxTxRed

/**
 * 底部发射区（docs/UI-MOBILE.md §3.4）：竖屏**常驻**，不再折叠。
 *
 * - 第 1 行：`生成信息 · 自定义报文 · CQ 前缀 · 发送`
 * - 第 2 行：`停止发射 · 自动程序 · 正在发送`
 * - 第 3–4 行：**六个报文槽 3×2**（网格 / 报告 / R报告 / RR73 / 73 / CQ），槽上直接写报文内容。
 *
 * 闸门是信息头右上角的「发射 / 只接收」总开关（默认只接收），本组件不再自带开关。
 *
 * 「自定义报文」框**自动同步当前待发报文**（docs/UI-MOBILE.md §21）：待发一变就填进框，
 * 不用再点「生成信息」；手动改过则先按手动的，发送后 / 点「生成信息」回到自动同步。
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

    // 六个槽的报文：每帧现算，永远与六步序列一致（docs/UI-MOBILE.md §3.4）
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
    val cqText = slots.firstOrNull { it.first == TxMessageKind.CQ }?.second

    var custom by remember { mutableStateOf("") }
    // 是否手动改过框里的内容：改过就先按手动的，发送后 / 点「生成信息」回到自动同步
    var customEdited by remember { mutableStateOf(false) }
    var prefixOpen by remember { mutableStateOf(false) }
    val tone = status.displayTxText
    val pending = status.pendingTxText
    // 「生成信息」＝把当前待发报文填进框（没有待发时退到 CQ 报文），并交回自动同步
    val generate = {
        customEdited = false
        custom = pending ?: cqText ?: ""
    }
    // 待发报文一变就自动填进框（docs/UI-MOBILE.md §21）
    LaunchedEffect(pending) {
        if (!customEdited) custom = pending.orEmpty()
    }
    // 竖屏窄，槽内文字最多两行
    val portrait = LocalConfiguration.current.screenWidthDp < 600

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(JtdxPanel)
            .border(1.dp, JtdxBorder)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // ---- 第 1 行：生成信息 / 自定义报文 / CQ 前缀 / 发送 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            JtdxButton(text = "生成信息", onClick = generate)
            JtdxTextField(
                value = custom,
                onValueChange = { custom = it },
                modifier = Modifier.weight(1f),
                placeholder = tone ?: "自定义报文",
                textStyle = MaterialTheme.typography.labelSmall,
            )
            Box {
                JtdxButton(
                    text = "CQ ${cqPrefix.ifEmpty { "无" }} ▾",
                    onClick = { prefixOpen = true },
                    active = cqPrefix.isNotEmpty(),
                )
                DropdownMenu(expanded = prefixOpen, onDismissRequest = { prefixOpen = false }) {
                    for ((i, p) in settings.cqPrefixes.withIndex()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (i == settings.cqPrefixIndex) "● ${p.ifEmpty { "无（普通 CQ）" }}"
                                    else "　${p.ifEmpty { "无（普通 CQ）" }}",
                                )
                            },
                            onClick = {
                                prefixOpen = false
                                onCqPrefixIndex(i)
                            },
                        )
                    }
                }
            }
            JtdxButton(
                text = "发送",
                onClick = {
                    customEdited = false
                    onSendOnce(custom)
                },
                enabled = custom.isNotBlank(),
                active = status.txArmed && !status.txing,
            )
        }

        // ---- 第 2 行：停止发射 / 自动程序 / 正在发送 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            JtdxButton(
                text = "停止发射",
                onClick = onStopTx,
                enabled = status.txing || status.txArmed,
                accent = VoxTxRed,
                active = status.txing,
            )
            JtdxButton(
                text = "自动程序",
                onClick = onOpenAutoProgram,
                active = status.txEnabled,
            )
            // 正在发送 = 本时隙实际在播的那条（没有播就是灰色「空闲」）
            Text(
                text = if (status.txing) "正在发送：${status.lastTxText ?: tone ?: ""}" else "空闲",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (status.txing) VoxTxRed else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // ---- 第 3–4 行：六个槽 3×2 ----
        for (half in 0..1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (col in 0..2) {
                    val index = half * 3 + col
                    val (kind, text) = slots[index]
                    TxSlot(
                        index = index + 1,
                        kind = kind,
                        text = text,
                        lit = text != null && tone == text,
                        txing = status.txing,
                        maxLines = if (portrait) 2 else 3,
                        onClick = {
                            customEdited = false
                            if (kind == TxMessageKind.CQ) onStartCq() else text?.let(onSendOnce)
                        },
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
        } else if (!status.txEnabled) {
            Text(
                "只接收：打开信息头右上角「发射」开关后才允许发射",
                style = MaterialTheme.typography.labelSmall,
                color = VoxError,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 一个报文槽（3×2 网格中的一格）：编号 + 步骤名 + 报文内容，点一下即排程发射。 */
@Composable
private fun RowScope.TxSlot(
    index: Int,
    kind: TxMessageKind,
    text: String?,
    lit: Boolean,
    txing: Boolean,
    maxLines: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(JtdxPanelHi)
            .border(1.dp, if (lit) (if (txing) VoxTxRed else JtdxGreen) else JtdxBorder, RoundedCornerShape(2.dp))
            .clickable(enabled = text != null, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                JtdxLed(on = lit, color = if (txing) VoxTxRed else JtdxGreen)
                Spacer(Modifier.width(3.dp))
                Text(
                    "$index ${kind.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text ?: "—",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
