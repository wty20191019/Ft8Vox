package com.example.ft8vox.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.settings.AppSettings
import com.example.ft8vox.engine.DecodeResult
import com.example.ft8vox.qso.AutoProgramSettings
import com.example.ft8vox.qso.MessageParser
import com.example.ft8vox.qso.TxCompose
import com.example.ft8vox.qso.TxMessageKind
import com.example.ft8vox.qso.TxQueue
import com.example.ft8vox.qso.TxScheduler
import com.example.ft8vox.ui.theme.VoxError
import com.example.ft8vox.ui.theme.VoxTxRed

/**
 * 发射控制抽屉（new_ui.md §3.4）。
 *
 * 收起态为 56dp 条（目标 / 状态 / 发送总开关）；点击条身或「展开」上拉 Bottom Sheet，
 * 内含目标信息、报文类型、自定义文本、4×2 宏、发送队列与大发射按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxDrawer(
    status: ReceiverStatus,
    settings: AppSettings,
    messages: List<DecodeResult>,
    targetCall: String?,
    onClearTarget: () -> Unit,
    onStartCq: () -> Unit,
    onAnswer: (String, String?, Int?) -> Unit,
    onSendNow: (String) -> Unit,
    onSendOnce: (String) -> Unit,
    onStopTx: () -> Unit,
    onTxEnabledChange: (Boolean) -> Unit,
    onHoldTxChange: (Boolean) -> Unit,
    onOpenAutoProgram: () -> Unit,
    onArmAutoProgram: () -> Unit,
    onMacrosChange: (List<String>) -> Unit,
    onEnqueue: (String) -> Unit,
    onRemoveQueued: (Int) -> Unit,
    onMoveQueued: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var composeText by rememberSaveable { mutableStateOf("") }
    var editingMacro by remember { mutableStateOf<Int?>(null) }

    val target = status.qso.theirCall ?: targetCall
    val kind = TxCompose.kindOf(composeText.ifBlank { status.qso.txText }, status.myCall)
    val report = TxCompose.reportFor(messages, target)
    val canSendNow = TxScheduler.canSendNow(status.slotParity, status.txParity, status.msToNextSlot)

    fun targetInfo(): Pair<String?, Int?> {
        val t = target ?: return null to null
        val m = messages.firstOrNull {
            MessageParser.parse(it.text).from?.equals(t, ignoreCase = true) == true
        } ?: return null to null
        return MessageParser.parse(m.text).grid to m.df
    }

    fun dispatch(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return when (TxCompose.kindOf(t, status.myCall)) {
            TxMessageKind.CQ -> {
                onStartCq()
                false
            }
            TxMessageKind.REPLY -> {
                val (grid, df) = targetInfo()
                val to = MessageParser.parse(t).to
                if (to != null) {
                    onAnswer(to, grid, df)
                    false
                } else {
                    if (canSendNow) onSendNow(t) else onSendOnce(t)
                    true
                }
            }
            else -> {
                if (canSendNow) onSendNow(t) else onSendOnce(t)
                true
            }
        }
    }

    /** 主发送：自定义文本 > 队列头 > 默认动作（无目标呼叫 CQ / 有目标应答）。 */
    fun primarySend() {
        val fromQueue = composeText.isBlank()
        val text = if (fromQueue) settings.txQueue.firstOrNull().orEmpty() else composeText
        if (text.isBlank()) {
            // 默认呼叫 CQ；已选目标时默认应答对方
            if (target != null) {
                val (grid, df) = targetInfo()
                onAnswer(target, grid, df)
            } else {
                onStartCq()
            }
            return
        }
        val direct = dispatch(text)
        if (fromQueue && direct) onRemoveQueued(0)
    }

    // ---- 收起态 56dp 条 ----
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = { expanded = true },
                onLongClick = { expanded = true },
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "→ ${target ?: "无目标"}",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = if (target != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                kind?.label ?: "空闲",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (status.txEnabled) "允许发射" else "只接收",
                style = MaterialTheme.typography.labelSmall,
                color = if (status.txEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.txArmed) {
                Text(
                    if (status.txing) "发射中" else "%.1fs".format(status.txCountdownMs.coerceAtLeast(0) / 1000.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.txing) VoxTxRed else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 唯一的发射控制：「发送总开关」——只回答「能不能发」（关 = 只接收，开 = 允许发射），
        // 自己**不发任何报文**。发什么由「发送」按钮 / 解码卡片手势（手动）或自动程序（自动）决定。
        Switch(
            checked = status.txEnabled,
            onCheckedChange = onTxEnabledChange,
        )
    }

    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 1) 目标信息
                Text("目标", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        target ?: "未选择（可点解码卡片选台）",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (target != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    val (grid, df) = targetInfo()
                    if (grid != null) {
                        Text(grid, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                    if (df != null) {
                        Text("$df Hz", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                    if (target != null) {
                        OutlinedButton(onClick = { onClearTarget(); onStopTx() }) { Text("取消目标") }
                    }
                }

                // 2) 消息类型（2 行大按钮）
                Text("消息类型", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KindButton("CQ", Modifier.weight(1f)) {
                        composeText = TxCompose.compose(TxMessageKind.CQ, target, status.myCall, status.myGrid) ?: ""
                    }
                    KindButton("回复", Modifier.weight(1f), enabled = target != null) {
                        composeText = TxCompose.compose(TxMessageKind.REPLY, target, status.myCall, status.myGrid) ?: ""
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KindButton("交换", Modifier.weight(1f), enabled = target != null) {
                        composeText = TxCompose.compose(
                            TxMessageKind.EXCHANGE, target, status.myCall, status.myGrid, report,
                        ) ?: ""
                    }
                    KindButton("RR73", Modifier.weight(1f), enabled = target != null) {
                        composeText = TxCompose.compose(TxMessageKind.RR73, target, status.myCall, status.myGrid) ?: ""
                    }
                    KindButton("73", Modifier.weight(1f), enabled = target != null) {
                        composeText = TxCompose.compose(
                            TxMessageKind.SEVENTY_THREE, target, status.myCall, status.myGrid,
                        ) ?: ""
                    }
                }

                // 3) 自定义文本
                OutlinedTextField(
                    value = composeText,
                    onValueChange = { composeText = it },
                    label = { Text("自定义") },
                    singleLine = true,
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${composeText.length}/${TxCompose.MAX_TEXT_CHARS}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (composeText.length > TxCompose.MAX_TEXT_CHARS) VoxError else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (composeText.isNotEmpty()) {
                                IconButton(onClick = { composeText = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "清除")
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                // 4) 宏 4×2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("宏", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("长按编辑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (r in 0 until 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in 0 until 4) {
                            val idx = r * 4 + c
                            val template = settings.macros.getOrNull(idx)
                            MacroButton(
                                template = template,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (template != null) {
                                        composeText = TxCompose.expandMacro(
                                            template, target, status.myCall, status.myGrid, report,
                                        )
                                    }
                                },
                                onLongClick = { editingMacro = idx },
                            )
                        }
                    }
                }

                // 5) 发送队列
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("发送队列", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (settings.txQueue.isNotEmpty()) {
                        TextButton(onClick = onClearQueue) { Text("清空", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                if (settings.txQueue.isEmpty()) {
                    Text("（空）点「加入队列」排入待发报文", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        settings.txQueue.forEachIndexed { i, text ->
                            QueuePill(
                                label = TxQueue.label(i, text, status.myCall),
                                onLoad = { composeText = text },
                                onMoveUp = { onMoveQueued(i, i - 1) },
                                onMoveDown = { onMoveQueued(i, i + 1) },
                                onRemove = { onRemoveQueued(i) },
                            )
                        }
                    }
                }

                // 6) 大发送按钮（TX 时变红 + 倒计时，点按即停止）
                Button(
                    onClick = { if (status.txArmed) onStopTx() else primarySend() },
                    enabled = if (status.txArmed) {
                        true
                    } else {
                        status.txEnabled && status.myCall.isNotEmpty()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (status.txArmed) VoxTxRed else MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    val summary = composeText.ifBlank { settings.txQueue.firstOrNull().orEmpty() }
                    Text(
                        if (status.txArmed) {
                            if (status.txing) {
                                "停止发射（发射中）"
                            } else {
                                "停止发射 · ${"%.1f".format(status.txCountdownMs.coerceAtLeast(0) / 1000.0)}s"
                            }
                        } else if (summary.isBlank()) {
                            if (target != null) "发送：应答 $target" else "发送：CQ ${status.myCall}"
                        } else {
                            "发送：$summary"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { if (composeText.isNotBlank()) onEnqueue(composeText) },
                        enabled = composeText.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("加入队列", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = onStopTx,
                        enabled = status.txArmed,
                        modifier = Modifier.weight(1f),
                    ) { Text("停止发射", style = MaterialTheme.typography.labelMedium) }
                }
                Text(
                    when {
                        !status.txEnabled ->
                            "发送总开关已关：只接收，不发射任何报文。打开后由「发送」按钮 / 解码卡片手势或自动程序决定发什么。"
                        status.autoArmed ->
                            "发送总开关已开，自动程序已启用：选台与整段 QSO 报文流程由自动程序决定；手动「发送」仍可用。"
                        canSendNow -> "发送总开关已开；当前为我方周期且剩余 >2.5s：点「发送」将立即发射"
                        else -> "发送总开关已开；非我方周期或剩余不足：点「发送」将排到下一个我方发射周期"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // 7) 自动序列（Hold Tx / 自动程序）；发射周期固定为「自动」
                Text("自动序列", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = status.holdTxFreq,
                        onClick = { onHoldTxChange(!status.holdTxFreq) },
                        label = { Text("Hold Tx") },
                    )
                }
                Text(
                    "发射时隙：自动按手机 UTC 时间取下一个来得及的时隙，" +
                        "当前锁定${if (status.txParity == 0) "偶" else "奇"}周期；" +
                        "重新开关「发射」即可换时隙。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("自动程序", style = MaterialTheme.typography.labelMedium)
                    Text(
                        status.autoProgram.level.shortLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(onClick = onOpenAutoProgram) { Text("设置…") }
                    if (status.autoArmed) {
                        OutlinedButton(onClick = onArmAutoProgram) { Text("关闭") }
                    } else {
                        OutlinedButton(
                            onClick = onArmAutoProgram,
                            enabled = status.autoProgram.level.enabled,
                        ) { Text("启用") }
                    }
                }
                Text(
                    "「发送总开关」只表示允许发射；启用后由自动程序决定选台与整段 QSO 的报文流程。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    autoProgramSummary(status.autoProgram),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    editingMacro?.let { idx ->
        MacroEditDialog(
            initial = settings.macros.getOrNull(idx) ?: "",
            onConfirm = { value ->
                val list = settings.macros.toMutableList()
                while (list.size <= idx) list.add("")
                list[idx] = value
                onMacrosChange(list)
                editingMacro = null
            },
            onDismiss = { editingMacro = null },
        )
    }
}

/** 消息类型大按钮（支持长按编辑宏的复用样式）。 */
@Composable
private fun KindButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

/** 宏按钮：点展开到自定义框，长按编辑模板。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MacroButton(
    template: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            template?.take(12) ?: "—",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 队列胶囊：点加载到自定义框，长按菜单可上移/下移/删除，右侧 X 直接删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueuePill(
    label: String,
    onLoad: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(onClick = onLoad, onLongClick = { menu = true })
                .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "删除", modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("上移") }, onClick = { menu = false; onMoveUp() })
            DropdownMenuItem(text = { Text("下移") }, onClick = { menu = false; onMoveDown() })
            DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onRemove() })
        }
    }
}

/** 宏编辑对话框。 */
@Composable
private fun MacroEditDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑宏") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("模板") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "占位符：{call} {mycall} {mygrid} {report}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
