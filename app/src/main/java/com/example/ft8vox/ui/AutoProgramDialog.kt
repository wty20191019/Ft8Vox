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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ft8vox.qso.AutoMode
import com.example.ft8vox.qso.AutoProgramSettings
import com.example.ft8vox.qso.AutoSort
import com.example.ft8vox.qso.DecodeTiming
import com.example.ft8vox.qso.MIXED_CQ_NO_REPLY_LIMIT

/**
 * 「自动程序」设置弹窗（顶栏菜单入口），对应《QSO 自动系统设计文档》§五菜单。
 *
 * **模式即开关**：选「0 手动模式」＝关闭，选 1 主叫 / 2 混合 ＝开启。调用方（`MainShell`）
 * 负责在「0 → 1/2」时先弹 [AutoEnableConfirmDialog] 防误发确认，再执行 `setAutoMode`。
 */
@Composable
fun AutoProgramDialog(
    program: AutoProgramSettings,
    onSetMode: (AutoMode) -> Unit,
    onOption: ((AutoProgramSettings) -> AutoProgramSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动程序") },
        text = {
            AutoProgramPanel(
                program = program,
                onSetMode = onSetMode,
                onOption = onOption,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 自动程序策略面板（弹窗与设置页共用）。
 *
 * [onOption] 接收一个「就地变换」，便于对 [AutoProgramSettings] 做单字段 copy。
 * 工作模式行只上报点击，防误发确认由调用方处理。
 */
@Composable
fun AutoProgramPanel(
    program: AutoProgramSettings,
    onSetMode: (AutoMode) -> Unit,
    onOption: ((AutoProgramSettings) -> AutoProgramSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = program.mode.enabled
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "模式即开关：「0 手动模式」＝关闭，1 主叫 / 2 混合 ＝开启。" +
                "第 2 层负责选台与排队：主叫模式持续发 CQ 并逐个完成回应者；" +
                "混合模式在连续 $MIXED_CQ_NO_REPLY_LIMIT 次无人回应后转为应答别人的 CQ，完成即切回主叫。" +
                "从「0」切到 1/2 会先弹防误发确认，确认后自动打开「发送总开关」（总开关本身只表示允许发射）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AutoSection("工作模式（单选）")
        for (m in AutoMode.entries) {
            AutoRadioRow(
                selected = program.mode == m,
                title = m.label,
                subtitle = m.subtitle,
                onClick = { onSetMode(m) },
            )
        }

        AutoSection("解码时机（单选）")
        for (t in DecodeTiming.entries) {
            AutoRadioRow(
                selected = program.decodeTiming == t,
                title = t.label,
                subtitle = t.subtitle,
                onClick = { onOption { it.copy(decodeTiming = t) } },
            )
        }

        AutoSection("应答选台规则（第 2 层使用）")
        AutoOptionRow(
            title = "允许重复通联",
            subtitle = "不勾选时从候选列表里剔除已通联过的呼号",
            checked = program.allowRepeat,
            onChange = { v -> onOption { it.copy(allowRepeat = v) } },
        )
        Text(
            "排序依据（单选）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        for (s in AutoSort.entries) {
            AutoRadioRow(
                selected = program.sortBy == s,
                title = s.label,
                subtitle = when (s) {
                    AutoSort.DECODE_ORDER -> "按本时隙解码到的先后顺序"
                    AutoSort.SNR -> "信号越强越靠前"
                    AutoSort.DISTANCE -> "距离越远越靠前（需填写我的网格）"
                },
                onClick = { onOption { it.copy(sortBy = s) } },
            )
        }
        AutoOptionRow(
            title = "报告信息优先",
            subtitle = "发给我方的定向报文优先于 CQ 与排队",
            checked = program.reportPriority,
            onChange = { v -> onOption { it.copy(reportPriority = v) } },
        )

        AutoSection("重发机制（第 1 层使用）")
        AutoOptionRow(
            title = "已选台回应无反应后放弃",
            subtitle = "关闭则对同一目标一直重发（受下面的保护限制约束）",
            checked = program.giveUpAfterRetry,
            onChange = { v -> onOption { it.copy(giveUpAfterRetry = v) } },
        )
        AutoStepperRow(
            title = "放弃前重试次数",
            value = program.retryLimit,
            range = 1..10,
            unit = " 次",
            enabled = program.giveUpAfterRetry,
            onChange = { v -> onOption { it.copy(retryLimit = v) } },
        )

        AutoSection("保护限制（第 2 层监控）")
        AutoOptionRow(
            title = "连续无有效 QSO 后自动停止发射",
            subtitle = "触发后停止第 2 层调度并切回「0 手动模式」",
            checked = program.stopAfterNoQso,
            onChange = { v -> onOption { it.copy(stopAfterNoQso = v) } },
        )
        AutoStepperRow(
            title = "无有效 QSO 时长",
            value = program.noQsoMinutes,
            range = 1..120,
            unit = " 分钟",
            enabled = program.stopAfterNoQso,
            onChange = { v -> onOption { it.copy(noQsoMinutes = v) } },
        )
        AutoOptionRow(
            title = "单次发射总时长超限后自动停止发射",
            subtitle = "触发后停止第 2 层调度并切回「0 手动模式」",
            checked = program.stopAfterTxTotal,
            onChange = { v -> onOption { it.copy(stopAfterTxTotal = v) } },
        )
        AutoStepperRow(
            title = "发射总时长上限",
            value = program.txTotalMinutes,
            range = 1..240,
            unit = " 分钟",
            enabled = program.stopAfterTxTotal,
            onChange = { v -> onOption { it.copy(txTotalMinutes = v) } },
        )

        if (enabled) {
            Text(
                "当前已启用（${program.mode.shortLabel}）；切到「0 手动模式」即关闭，「发送总开关」不变。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Text(
                "当前未启用：目标由你手动选择（长按解码行 / 抽屉里的发送按钮）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 小节标题 + 分隔线。 */
@Composable
private fun AutoSection(title: String) {
    HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 2.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun AutoRadioRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "●" else "○",
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AutoOptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 数值行：− [值] ＋（触摸目标 ≥48dp）。 */
@Composable
private fun AutoStepperRow(
    title: String,
    value: Int,
    range: IntRange,
    unit: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        AutoStepButton("−", enabled && value > range.first) { onChange(value - 1) }
        Text(
            "$value$unit",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(74.dp)
                .padding(horizontal = 4.dp),
        )
        AutoStepButton("＋", enabled && value < range.last) { onChange(value + 1) }
    }
}

@Composable
private fun AutoStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .width(48.dp)
            .height(40.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(top = 6.dp),
    )
}

/**
 * 「启用自动程序」防误发确认：工作模式从「0 手动模式」切到 1/2 时弹出。
 *
 * 顶栏弹窗与设置页两处共用同一实现（抽屉没有启用按钮，模式只能在这两处改），确认后由调用方执行
 * `SessionViewModel.setAutoMode`。
 */
@Composable
fun AutoEnableConfirmDialog(
    mode: AutoMode,
    status: ReceiverStatus,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启用自动程序") },
        text = {
            Text(
                buildString {
                    append("将按「${mode.label}」自动发射：选台、排队与整段 QSO 报文流程均由自动程序决定。\n")
                    append("呼号：${status.myCall}｜发射频率：${status.selectedFreqHz} Hz｜时隙：自动（下一个 ")
                    append(if (status.txParity == 0) "偶" else "奇")
                    append("）\n")
                    append(
                        if (status.txEnabled) "发送总开关：已开（只表示允许发射）。\n"
                        else "发送总开关：关 —— 确认启用时会自动打开。\n",
                    )
                    if (mode == AutoMode.MIXED) {
                        append("混合模式：连续 3 次无人回应后会转为应答别人的 CQ，随后自动切回主叫。\n")
                    }
                    append("请确认电台已就绪。")
                },
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("确认启用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 发射抽屉里的一行自动程序摘要。 */
internal fun autoProgramSummary(p: AutoProgramSettings): String {
    if (!p.mode.enabled) return "未启用：${p.mode.shortLabel}选择目标"
    val sort = when (p.sortBy) {
        AutoSort.DECODE_ORDER -> "解码先后"
        AutoSort.SNR -> "SNR 优先"
        AutoSort.DISTANCE -> "距离优先"
    }
    val flags = buildList {
        add(if (p.allowRepeat) "允许重复通联" else "跳过已通联")
        add(sort)
        if (p.reportPriority) add("报告优先")
        if (p.giveUpAfterRetry) add("重发 ${p.retryLimit} 次放弃") else add("不放弃")
    }
    return "模式：${p.mode.label}｜${flags.joinToString("｜")}"
}
