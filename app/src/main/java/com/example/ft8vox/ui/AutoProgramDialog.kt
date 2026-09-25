package com.example.ft8vox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ft8vox.qso.AutoLevel
import com.example.ft8vox.qso.AutoProgramSettings

/**
 * 「自动程序」设置弹窗（顶栏菜单入口），对应 FT8CN 的自动程序菜单。
 *
 * 只编辑策略；启用/关闭在发射抽屉的「自动程序」行完成（需防误发确认）。
 */
@Composable
fun AutoProgramDialog(
    program: AutoProgramSettings,
    armed: Boolean,
    onSetLevel: (AutoLevel) -> Unit,
    onOption: ((AutoProgramSettings) -> AutoProgramSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动程序") },
        text = {
            AutoProgramPanel(
                program = program,
                armed = armed,
                onSetLevel = onSetLevel,
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
 */
@Composable
fun AutoProgramPanel(
    program: AutoProgramSettings,
    armed: Boolean,
    onSetLevel: (AutoLevel) -> Unit,
    onOption: ((AutoProgramSettings) -> AutoProgramSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "启用后自动完成整段 QSO：自动应答对方的 CQ，也自动应答发给我方的呼号 / 报告，" +
                "完成后自动接续下一台；4+ 在无可答目标时自动发 CQ（自动搜索）。" +
                "启用/关闭请在发射抽屉的「自动程序」行操作（启用会自动打开「发送总开关」；关闭只停止自动化，不影响总开关）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.heightIn(min = 4.dp))
        for (lv in AutoLevel.entries) {
            val selected = program.level == lv
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSetLevel(lv) }
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selected) "●" else "○",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    lv.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        AutoOptionRow(
            title = "回答曾经通联的电台",
            subtitle = "允许自动应答已通联台发来的 CQ",
            checked = program.answerWorked,
            onChange = { v -> onOption { it.copy(answerWorked = v) } },
        )
        AutoOptionRow(
            title = "呼叫曾经通联过的电台",
            subtitle = "允许自动程序把已通联台纳入目标",
            checked = program.callWorked,
            onChange = { v -> onOption { it.copy(callWorked = v) } },
        )
        AutoOptionRow(
            title = "优先选择新呼号发来的呼叫",
            subtitle = "候选中优先从未通联过的台",
            checked = program.preferNewCall,
            onChange = { v -> onOption { it.copy(preferNewCall = v) } },
        )
        AutoOptionRow(
            title = "报告信息优先",
            subtitle = "发给我方的定向报文优先于 CQ 排队",
            checked = program.reportPriority,
            onChange = { v -> onOption { it.copy(reportPriority = v) } },
        )
        AutoOptionRow(
            title = "最远距离取代最佳信噪比",
            subtitle = "选台准则由最强信噪比改为最远距离（需填写我的网格）",
            checked = program.farthestOverSnr,
            onChange = { v -> onOption { it.copy(farthestOverSnr = v) } },
        )
        AutoOptionRow(
            title = "单次通联",
            subtitle = "完成一次 QSO 后自动停止自动程序（默认关＝连续通联）",
            checked = program.singleQso,
            onChange = { v -> onOption { it.copy(singleQso = v) } },
        )
        if (armed) {
            Text(
                "当前已启用；切到「0 手动选择」会立即停止。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
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

/** 发射抽屉里的一行自动程序摘要。 */
internal fun autoProgramSummary(p: AutoProgramSettings): String {
    if (!p.level.enabled) return "未启用：手动选择目标"
    val flags = buildList {
        if (p.answerWorked) add("答已通联")
        if (p.callWorked) add("呼已通联")
        if (p.preferNewCall) add("新呼号优先")
        if (p.reportPriority) add("报告优先")
        add(if (p.farthestOverSnr) "最远距离" else "最强信噪比")
        add(if (p.singleQso) "单次通联" else "连续通联")
    }
    return "等级：${p.level.label}｜${flags.joinToString("｜")}"
}
