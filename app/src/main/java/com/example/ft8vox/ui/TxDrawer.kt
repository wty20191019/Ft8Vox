package com.example.ft8vox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 收起条高度（抽屉的「手柄」，也是 OperateScreen 给覆盖层让出的底部内边距）。 */
internal const val TX_DRAWER_STRIP_DP = 56

/** 展开后抽屉内容区的高度上限（跟手行程 = 这个高度）。 */
private const val DRAWER_PANEL_MAX_DP = 460

/** 松手时的甩动速度阈值（px/s）：上滑超过它就展开，下滑超过它就收起。 */
private const val DRAWER_FLING_VELOCITY = 800f

/** 收起动画大约多久结束（毫秒）；结束后再把面板内容滚回顶部。 */
private const val DRAWER_SETTLE_MS = 400L

/**
 * 发射控制抽屉（new_ui.md §3.4）。
 *
 * 收起态为 56dp 条（目标 / 状态 / 发送总开关）；**按住条身向上拖动即跟手展开**
 * （条身上移、面板从条身下方露出来），松手后按位置 / 甩动速度自动吸附到展开或收起；
 * 点击条身、长按条身同样展开。展开后条身就是「手柄」，把它向下拖 / 点遮罩 / 按返回键都能收起。
 * 面板内含目标信息、报文类型、自定义文本、4×2 宏、发送队列与大发射按钮。
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
    onSameFreqChange: (Boolean) -> Unit,
    onOpenAutoProgram: () -> Unit,
    onMacrosChange: (List<String>) -> Unit,
    onEnqueue: (String) -> Unit,
    onRemoveQueued: (Int) -> Unit,
    onMoveQueued: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var composeText by rememberSaveable { mutableStateOf("") }
    var editingMacro by remember { mutableStateOf<Int?>(null) }

    val target = status.qso.theirCall ?: targetCall
    // 下一次计划发射的完整报文（发射中＝实际在播的那条，见 ReceiverStatus.displayTxText）
    val displayTxText = status.displayTxText
    val report = TxCompose.reportFor(messages, target)
    // 立即发判据：报文波形 + 前导必须能在本时隙剩余时间内播完（否则排下一个我方周期）
    val canSendNow = TxScheduler.canSendNow(
        status.slotParity,
        status.txParity,
        status.msToNextSlot,
        TxScheduler.minSendNowMs(status.protocol.messageMs, settings.txPreambleMs),
    )

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

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        // 面板内容区高度＝跟手行程：屏幕装得下就 460dp，装不下就压到「可用高 − 条高 − 余量」
        val panelDp = (maxHeight - TX_DRAWER_STRIP_DP.dp - 24.dp)
            .coerceAtMost(DRAWER_PANEL_MAX_DP.dp)
            .coerceAtLeast(160.dp)
        val travelPx = with(density) { panelDp.toPx() }
        // 0 = 完全展开（面板贴底、条身升到面板上方）；travelPx = 完全收起（只剩 56dp 条身贴底）
        // 用普通状态而不是 Animatable：拖动要逐帧直接改值（DraggableState 的 onDelta 不是挂起函数），
        // 只有松手吸附那一段才用 animate() 插值。
        var offsetPx by remember(travelPx) { mutableFloatStateOf(if (expanded) 0f else travelPx) }
        val panelScroll = rememberScrollState()
        val dragState = remember(travelPx) {
            DraggableState { delta -> offsetPx = (offsetPx + delta).coerceIn(0f, travelPx) }
        }
        // 只暴露一个布尔量：拖动每帧都变的是 offsetPx，而它只在「收起 ↔ 露出」这一次翻转时触发重组
        val panelShown by remember(travelPx) {
            derivedStateOf { offsetPx < travelPx - 0.5f }
        }

        // 外部改动（点击 / 长按 / 返回键 / 旋转恢复）也走同一段弹性动画
        LaunchedEffect(expanded, travelPx) {
            val target = if (expanded) 0f else travelPx
            if (offsetPx != target) {
                animate(offsetPx, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                    offsetPx = v
                }
            }
        }
        // 面板露出时返回键先收起抽屉（本组件在 MainShell 的 BackHandler 之后注册，优先级更高）
        BackHandler(enabled = panelShown) { expanded = false }

        // 收起后把面板内容滚回顶部（此时面板在屏外，重置不可见）：下次展开总是从「目标」开始，
        // 与旧 ModalBottomSheet「每次重建内容」的表现一致；也顺带修掉首帧可能出现的滚动位置残留。
        // 延后到收起动画结束再重置，避免收起途中内容跳一下；期间若重新展开，本协程会被取消。
        LaunchedEffect(panelShown, travelPx) {
            if (!panelShown) {
                delay(DRAWER_SETTLE_MS)
                panelScroll.scrollTo(0)
            }
        }

        // 遮罩：随跟手进度渐显，点它收起（offsetPx 只在 draw 阶段读，不触发重组）
        if (panelShown) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(Color.Black.copy(alpha = 0.45f * (1f - offsetPx / travelPx)))
                    }
                    .pointerInput(Unit) { detectTapGestures { expanded = false } },
            )
        }

        // 抽屉本体：条身在上（＝手柄）、面板在下；整体下移 travelPx 后只剩条身露在屏幕底部
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, offsetPx.roundToInt()) }
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // ---- 收起态 56dp 条：跟手拖动的手柄（点击 / 长按仍可展开） ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TX_DRAWER_STRIP_DP.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            // 甩得够快就顺着甩的方向；否则看「有没有拉过一半」。初速只取与目标同向的那部分。
                            val fling = when {
                                velocity <= -DRAWER_FLING_VELOCITY -> 0f
                                velocity >= DRAWER_FLING_VELOCITY -> travelPx
                                else -> null
                            }
                            val target = fling ?: if (offsetPx < travelPx * 0.5f) 0f else travelPx
                            val initial = if ((target == 0f && velocity < 0f) || (target == travelPx && velocity > 0f)) {
                                velocity
                            } else {
                                0f
                            }
                            expanded = target == 0f
                            animate(
                                offsetPx,
                                target,
                                initialVelocity = initial,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            ) { v, _ -> offsetPx = v }
                        },
                    )
                    .combinedClickable(
                        onClick = { expanded = true },
                        onLongClick = { expanded = true },
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 左：目标 + 「下一次会发射什么」的完整报文（发射中冻结为实际在播的那条）
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "→ ${target ?: "无目标"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (target != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            displayTxText == null -> "空闲（无待发报文）"
                            status.txing -> "发射中 $displayTxText"
                            else -> "待发 $displayTxText"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (status.txing) VoxTxRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (status.txEnabled) "允许发射" else "只接收",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status.txEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status.txArmed && !status.txing) {
                        Text(
                            "%.1fs".format(status.txCountdownMs.coerceAtLeast(0) / 1000.0),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            // ---- 面板内容：固定高度 + 内部滚动（跟手行程就是 panelDp） ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelDp)
                    .verticalScroll(panelScroll)
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
                        status.autoProgram.mode.enabled ->
                            "发送总开关已开，自动程序已启用（${status.autoProgram.mode.shortLabel}）：" +
                                "选台与整段 QSO 报文流程由自动程序决定；手动「发送」仍可用。"
                        canSendNow -> "发送总开关已开；当前为我方周期且剩余时间够播完本条报文：点「发送」将立即发射"
                        else -> "发送总开关已开；非我方周期或剩余时间不足以播完本条报文：点「发送」将排到下一个我方发射周期"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // 7) 自动序列（同频/异频发射 / 自动程序）；发射周期固定为「自动」
                Text("自动序列", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = status.sameFreqTx,
                        onClick = { onSameFreqChange(true) },
                        label = { Text("同频发射") },
                    )
                    FilterChip(
                        selected = !status.sameFreqTx,
                        onClick = { onSameFreqChange(false) },
                        label = { Text("异频发射") },
                    )
                }
                Text(
                    if (status.sameFreqTx) {
                        "同频发射：「点谁打谁」——选台时红线（发射频率）跟到对方的频率。"
                    } else {
                        "异频发射：发射固定在红线位置，选台不改红线（拖动瀑布上的红线设定频率）。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                        status.autoProgram.mode.shortLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status.autoProgram.mode.enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onOpenAutoProgram) { Text("设置…") }
                }
                if (status.autoProgram.mode.enabled) {
                    Text(
                        "第 2 层：${status.autoPhaseLabel ?: "—"}" +
                            if (status.autoQueueSize > 0) "｜队列 ${status.autoQueueSize} 台" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "没有独立的启用开关：模式「0 手动模式」＝关闭，1 主叫 / 2 混合 ＝开启。" +
                        "切换模式会弹防误发确认；确认启用时若「发送总开关」为关会自动打开，" +
                        "随后选台、排队与整段 QSO 的报文流程都交给自动程序。",
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
