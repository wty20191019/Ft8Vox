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
 * 底部发射区（docs/Ft8Vox.md）：竖屏**常驻**，不再折叠。
 *
 * - 第 1 行：`自定义报文 · CQ 前缀 · 发送`（§28 去掉了「生成信息」键）
 * - 第 2 行：`停止发射 · 自动程序 · 正在发送`
 * - 第 3–4 行：**六个报文槽 3 列 × 2 行、列优先**（`1 3 5` / `2 4 6`），槽上直接写报文内容。
 *
 * 闸门是信息头右上角的「发射 / 只接收」总开关（默认只接收），本组件不再自带开关。
 *
 * **标准报文不用生成**（§28）：点下面六格槽，就是 `TxCompose` 现算出来的六步标准报文，
 * 点哪格发哪格。自定义报文框**平时是空的**（灰字占位回显当前待发报文），
 * 在里面写一条 → 点「发送」→ 排到下一个（或最近可发的）我方时隙，**不打断正在跑的 QSO**；
 * 发送成功后框清空、QSO 继续（见 `SessionViewModel.sendOnce`）。
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
    onCqPrefix: (prefixes: List<String>, index: Int) -> Unit,
    onOpenAutoProgram: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val myCall = status.myCall
    val myGrid = status.myGrid
    // 目标呼号：引擎在通联时以引擎的真正对手为准，空闲时用用户点选的目标（见 `TxCompose.targetFor`）。
    val target = TxCompose.targetFor(
        engineCall = status.qso.theirCall,
        engineBusy = status.qso.active || status.qso.txText != null,
        picked = targetCall,
    )
    val reportSent = status.qso.reportSent ?: TxCompose.reportFor(messages, target)
    val cqPrefix = settings.cqPrefix

    // 六个槽的报文：每帧现算，永远与六步序列**逐字一致**（docs/Ft8Vox.md：
    // 「格内文字就是真发的报文」）；组装规则统一在 `TxCompose.slots`，
    // 由单测锁死「槽内文字 == 引擎渲染的 txText」（红/绿点靠文本相等匹配）。
    val slots = remember(target, myCall, myGrid, reportSent, cqPrefix) {
        TxCompose.slots(target, myCall, myGrid, reportSent, cqPrefix)
    }

    var custom by remember { mutableStateOf("") }
    // 刚点「发送」的那条自定义报文：排程成功（status.manualTxText 正是它）时用来清空输入框
    var lastSent by remember { mutableStateOf<String?>(null) }
    var prefixDialog by remember { mutableStateOf(false) }
    val tone = status.displayTxText

    // 槽指示灯按**报文序号**判定，与运算层解耦（见 `slotLed` 的说明）：
    // 待发序号直接取引擎的 `QsoProgress.order`（1..6）；待发若是**自定义报文**，
    // 它不属于六步序列，六格都不点灯。引擎 order=0（空闲）同样不点灯。
    val queuedOrder = if (status.manualTxText.isNullOrBlank()) {
        status.qso.order.takeIf { it in 1..TxMessageKind.CQ.order }
    } else {
        null
    }
    // 在播序号从「本时隙实际播放的文本」反推：引擎的 order 表示**下一条**要发的，
    // 不能拿它判**这一条**（收尾报文已排定时两者不同）。
    val onAirOrder = if (status.txing) {
        TxCompose.kindOf(status.lastTxText, myCall)?.order?.takeIf { it in 1..TxMessageKind.CQ.order }
    } else {
        null
    }
    // 排程成功 → 清空自定义框（§28）；被拒 / 编不出来则保留，便于就地改（底部有红字说明原因）
    LaunchedEffect(status.manualTxText) {
        val sent = lastSent
        if (sent != null && status.manualTxText == sent && custom.trim() == sent) {
            custom = ""
            lastSent = null
        }
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
        // ---- 第 1 行：自定义报文 / CQ 前缀 / 发送 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            JtdxTextField(
                value = custom,
                onValueChange = { custom = it },
                modifier = Modifier.weight(1f),
                // 框平时是空的：灰字回显当前待发报文，作为「待发」的唯一文字读数（§28）
                placeholder = tone ?: "自定义报文",
                textStyle = MaterialTheme.typography.labelSmall,
            )
            JtdxButton(
                text = "CQ ${cqPrefix.ifEmpty { "无" }} ▾",
                onClick = { prefixDialog = true },
                active = cqPrefix.isNotEmpty(),
            )
            JtdxButton(
                text = "发送",
                // 点「发送」＝发框里这条报文（不打断正在跑的 QSO，见 SessionViewModel.sendOnce）；
                // 编不出来 / 被拒都会在底部红字说明，所以按钮保持可点、不置灰。
                onClick = {
                    val m = custom.trim()
                    lastSent = m
                    onSendOnce(m)
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
                        led = slotLed(kind.order, queuedOrder, onAirOrder),
                        maxLines = if (portrait) 2 else 3,
                        onClick = {
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

    // 「CQ 前缀」编辑弹窗（§28）：格子里能改、能选，确定时一次性提交
    if (prefixDialog) {
        CqPrefixDialog(
            prefixes = settings.cqPrefixes,
            selectedIndex = settings.cqPrefixIndex,
            onConfirm = { prefixes, index -> onCqPrefix(prefixes, index) },
            onDismiss = { prefixDialog = false },
        )
    }
}

/** 报文槽指示灯：正在发送（红）> 待发（绿）> 熄灭。 */
internal enum class SlotLed { OFF, QUEUED, ON_AIR }

/**
 * 判定某一格报文槽的指示灯（实机反馈：**发送中的那格红点、待发的那格绿点**）。
 *
 * **按「报文序号」判定，不看文本**：底层 [com.example.ft8vox.qso.QsoEngine] 已经给出
 * 我方下一条要发的序号（`QsoProgress.order`），UI 不该再用「自己拼的报文 == 引擎的报文」
 * 这种字符串比较来猜（那是 UI 与运算层耦合；一旦两边拼法有任何出入，灯就整格不亮）。
 *
 * @param kindOrder 本格对应的报文序号（1 网格 / 2 报告 / 3 R报告 / 4 RR73 / 5 73 / 6 CQ）
 * @param queuedOrder 我方下一条要发的报文序号；`null`＝没有待发，或待发的是**自定义报文**
 *   （不在六步序列里，六格都不点灯）
 * @param onAirOrder 本时隙**真正在播**的报文序号；`null`＝没在发射 / 在播的是自定义报文
 *
 * 「正在发送」优先级高于「待发」：收尾报文（RR73/73）已排定但上一条还在播时，
 * 会同时出现「红点在播的那格 + 绿点下一格」，这正是真机想要的读法。
 */
internal fun slotLed(
    kindOrder: Int,
    queuedOrder: Int?,
    onAirOrder: Int?,
): SlotLed = when (kindOrder) {
    onAirOrder -> SlotLed.ON_AIR
    queuedOrder -> SlotLed.QUEUED
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
