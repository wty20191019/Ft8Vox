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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ft8vox.qso.AutoProgramSettings
import com.example.ft8vox.qso.NO_REPLY_LIMIT_RANGE
import com.example.ft8vox.qso.SUPERVISION_MINUTES

/**
 * 「自动程序」设置弹窗（顶栏菜单入口），照 FT8CN 四项重做（见 `docs/QSO.md` §5.1）。
 *
 * **没有档位、也没有启用确认**：自动程序的唯一闸门是「发送总开关」，打开即自动发射。
 * 本弹窗只改策略（发射监管 / 无回应次数 / 两个开关）。
 */
@Composable
fun AutoProgramDialog(
    program: AutoProgramSettings,
    onOption: ((AutoProgramSettings) -> AutoProgramSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动程序") },
        text = { AutoProgramPanel(program = program, onOption = onOption) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 自动程序策略面板（弹窗与设置页共用）。
 *
 * [onOption] 接收一个「就地变换」，便于对 [AutoProgramSettings] 做单字段 copy。
 *
 * [nestedScroll] = true 时面板自带内层滚动（弹窗需要，高度封顶）；嵌在设置页等
 * 本身可滚动的页面里时应传 false，让内容完全展开、跟随页面一起滚，避免出现「二级滑动菜单」。
 */
@Composable
fun AutoProgramPanel(
    program: AutoProgramSettings,
    onOption: ((AutoProgramSettings) -> AutoProgramSettings) -> Unit,
    nestedScroll: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (nestedScroll) {
                    Modifier.heightIn(max = 460.dp).verticalScroll(scroll)
                } else {
                    Modifier
                },
            ),
    ) {
        Text(
            "打开「发送总开关」即启用自动程序（无确认框）：定向报文一律应答；" +
                "CQ 台由下面两个开关与「关注名单」共同决定。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AutoSection("自动程序策略")

        // 发射监管（FT8CN launchSupervision：0 / 5 / 15 / … / 95 分钟，默认 10）
        AutoOptionStepperRow(
            title = "发射监管",
            value = program.supervisionMinutes,
            options = SUPERVISION_MINUTES,
            render = { v -> if (v <= 0) "不监管" else "$v 分钟" },
            subtitle = "连续发射超过该时长后自动关闭发送总开关",
            onChange = { v -> onOption { it.copy(supervisionMinutes = v) } },
        )

        // 无回应次数（FT8CN noReplyLimit：0=忽略，1..30）
        AutoStepperRow(
            title = "无回应次数（换台阈值）",
            value = program.noReplyLimit,
            range = NO_REPLY_LIMIT_RANGE,
            unit = if (program.noReplyLimit <= 0) " 次（忽略）" else " 次",
            enabled = true,
            onChange = { v -> onOption { it.copy(noReplyLimit = v) } },
        )
        Text(
            "对方连续无回应达到该次数后放弃当前目标：优先换到别的 CQ 台，没有就自己发 CQ。" +
                "0＝忽略（对同一目标一直重发，FT8CN 默认）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AutoOptionRow(
            title = "自动收录 CQ 台（本波段）",
            subtitle = "每解到一个本波段还没通联过的 CQ 台就自动加入 ⭐ 关注列表（" +
                "跨波段通联过的台在本波段仍会收录；自动收录最多 100 个，超出淘汰最早加入的；" +
                "手动关注的不受此限）",
            checked = program.autoAddCqToFollow,
            onChange = { v -> onOption { it.copy(autoAddCqToFollow = v) } },
        )
        AutoOptionRow(
            title = "自动呼叫 CQ 台",
            subtitle = "是否真的去呼叫候选里的 CQ 台（总闸）；关掉后只回应定向呼叫、自己发 CQ",
            checked = program.autoCallFollow,
            onChange = { v -> onOption { it.copy(autoCallFollow = v) } },
        )

        Text(
            "「关注」名单：在解码列表长按某台 →「关注」，或由「自动收录 CQ 台」自动加入；" +
                "点筛选条最右的 ⭐ 可查看 / 删除（右滑取消关注）；关注的台通联完成后会自动取消关注。\n" +
                "「自动收录 CQ 台」关掉时仍会自动呼叫名单里 CQ 台的 CQ。\n" +
                "两个开关都开＝自动应答本波段任何未通联的 CQ 台；只关「自动收录 CQ 台」＝只呼叫关注的台；" +
                "关掉「自动呼叫 CQ 台」＝完全不应答 CQ。无论怎样，自己发 CQ、" +
                "对方直接呼叫我方一定应答；本波段已通联的 CQ 台不再主动呼叫。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
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

/**
 * 档位行：`− [值] ＋`，在离散档位表（如发射监管的 `0/5/15/…/95`）里上下取一档。
 *
 * 当前值不在表中时（如默认 10 分钟不在 5/15/… 里），上下分别取相邻档，便于回归到标准档位。
 */
@Composable
private fun AutoOptionStepperRow(
    title: String,
    value: Int,
    options: List<Int>,
    render: (Int) -> String,
    subtitle: String,
    onChange: (Int) -> Unit,
) {
    val lower = options.lastOrNull { it < value }
    val higher = options.firstOrNull { it > value }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            AutoStepButton("−", lower != null) { lower?.let(onChange) }
            Text(
                render(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .width(96.dp)
                    .padding(horizontal = 4.dp),
            )
            AutoStepButton("＋", higher != null) { higher?.let(onChange) }
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** 发射抽屉里的一行自动程序摘要。 */
internal fun autoProgramSummary(p: AutoProgramSettings): String {
    val supervision = if (p.supervisionMinutes <= 0) "监管关" else "监管 ${p.supervisionMinutes} 分"
    val noReply = if (p.noReplyLimit <= 0) "无回应不限" else "无回应 ${p.noReplyLimit} 次换台"
    val follow = if (p.autoAddCqToFollow) "收录本波段新 CQ" else "不收录 CQ"
    val call = if (p.autoCallFollow) "自动呼叫" else "不自动呼叫"
    return "$supervision｜$noReply｜$follow｜$call"
}
