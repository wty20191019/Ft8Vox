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
 * - 第 3–4 行：**六个报文槽 3 列 × 2 行、列优先**（`1 3 5` / `2 4 6`），槽上直接写报文内容。
 *
 * 闸门是信息头右上角的「发射 / 只接收」总开关（默认只接收），本组件不再自带开关。
 *
 * 「自定义报文」框**自动同步当前待发报文**（docs/UI-MOBILE.md §21）：待发一变就填进框，
 * 不用再点「生成信息」。**一打字就进入「手动优先」**（§27），不会被自动同步冲掉；点「发送」
 * 即把框里这条排到下一个我方时隙（**不打断正在跑的 QSO**，只是在原序列里插一条），
 * 编不出来 / 被拒时底部红字当场说明，发送成功后框回到自动同步。
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
    // 一次性报文排程成功（待发报文正是框里这条）→ 交回自动同步：发完跟着显示真正的待发报文
    // （§27：点「发送」后框里清空 / 回到自动同步）
    LaunchedEffect(status.manualTxText) {
        if (status.manualTxText != null && status.manualTxText == custom.trim()) customEdited = false
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
                onValueChange = {
                    custom = it
                    // 手动输入即「手动优先」（§27）：不再被待发报文的自动同步冲掉。
                    // 清空输入框则回到自动同步。
                    customEdited = it.isNotBlank()
                },
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
                // 点「发送」＝发框里这条报文（不打断正在跑的 QSO，见 SessionViewModel.sendOnce）；
                // 编不出来 / 被拒都会在底部红字说明，所以按钮保持可点、不置灰。
                onClick = { onSendOnce(custom) },
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

        // ---- 第 3–4 行：六个槽 3 列 × 2 行，**列优先**（1 3 5 / 2 4 6）----
        for (row in 0..1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (col in 0..2) {
                    // 列优先：第 c 列第 r 行 = 第 c*2+r 个槽
                    val index = col * 2 + row
                    val (kind, text) = slots[index]
                    TxSlot(
                        index = index + 1,
                        kind = kind,
                        text = text,
                        led = slotLed(text, status.lastTxText, pending, status.txing),
                        maxLines = if (portrait) 2 else 3,
                        onClick = {
                            customEdited = false
                            if (kind == TxMessageKind.CQ) onStartCq() else text?.let(onSendOnce)
                        },
                    )
                }
            }
        }

        // 底部提示一行（§27）：**一次性发射被拒 / 编不出来的原因（红）** 优先，
        // 其次是常驻的「只接收」提醒（红），最后才是 QSO 进度（绿）。
        val notice = status.txNotice
        val hintText = when {
            notice != null -> notice
            !status.txEnabled -> "只接收：打开信息头右上角「发射」开关后才允许发射"
            status.qso.active -> "QSO：${status.qso.description}"
            else -> null
        }
        if (hintText != null) {
            Text(
                hintText,
                style = MaterialTheme.typography.labelSmall,
                color = if (notice != null || !status.txEnabled) VoxError else JtdxGreen,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 报文槽指示灯：正在发送（红）> 待发（绿）> 熄灭。 */
internal enum class SlotLed { OFF, QUEUED, ON_AIR }

/**
 * 判定某一格报文槽的指示灯（实机反馈：**发送中的那格红点、待发的那格绿点**）。
 *
 * @param text 本格报文（`null`＝该步现在没有报文，不点灯）
 * @param onAirText 本时隙**真正在播**的报文（`status.lastTxText`，只在 [txing] 时有意义）
 * @param pendingText 下一次要发的报文（`status.pendingTxText`）
 * @param txing 是否正在发射
 *
 * 「正在发送」优先级高于「待发」：收尾报文（RR73/73）已排定但上一条还在播时，
 * 会同时出现「红点在播的那格 + 绿点下一格」，这正是真机想要的读法。
 */
internal fun slotLed(
    text: String?,
    onAirText: String?,
    pendingText: String?,
    txing: Boolean,
): SlotLed = when {
    text == null -> SlotLed.OFF
    txing && text == onAirText -> SlotLed.ON_AIR
    text == pendingText -> SlotLed.QUEUED
    else -> SlotLed.OFF
}

/** 一个报文槽（3×2 网格中的一格）：编号 + 步骤名 + 报文内容，点一下即排程发射。 */
@Composable
private fun RowScope.TxSlot(
    index: Int,
    kind: TxMessageKind,
    text: String?,
    led: SlotLed,
    maxLines: Int,
    onClick: () -> Unit,
) {
    // 指示灯与边框同色：正在发送=红、待发=绿、其余=暗灰（实机反馈：发送中红点、待发绿点）
    val accent = when (led) {
        SlotLed.ON_AIR -> VoxTxRed
        SlotLed.QUEUED -> JtdxGreen
        SlotLed.OFF -> JtdxBorder
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(JtdxPanelHi)
            .border(1.dp, accent, RoundedCornerShape(2.dp))
            .clickable(enabled = text != null, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                JtdxLed(on = led != SlotLed.OFF, color = accent)
                Spacer(Modifier.width(3.dp))
                Text(
                    "$index ${kind.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (led == SlotLed.OFF) MaterialTheme.colorScheme.onSurfaceVariant else accent,
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
