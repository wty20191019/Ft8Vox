package com.example.ft8vox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * 设置页的**通用控件层**（docs/Ft8Vox.md §3.6）。
 *
 * 这一层**不认识** `AppSettings` 与各 ViewModel：只收 `value` / `onValueChange` 这类回调，
 * 所以能脱离设置页复用；纯逻辑（解析、换算）都放在 `AppChrome.kt` 里，便于 JVM 单测。
 *
 * 按控件类型分类：
 * - **容器类**：[SettingsGroup]（标题 + 圆角卡片）、[PrefRow]（标题/副标题 + 右侧控件）、[PrefDivider]
 * - **说明类**：[PrefInfo]（只读行）、[PrefNote]（小字说明）
 * - **开关类**：[PrefSwitch]
 * - **选择类**：[PrefChoice]（横向 FilterChip）、[PrefDropdown]（下拉菜单）
 * - **数值类**：**[PrefNumber]（纯步进）** 与 **[PrefNumberInput]（步进 + 点数字就地输入）**——
 *   按项选用，逻辑只写一份；文本↔数值的换算由调用方以 `parse` / `format` 注入
 * - **文本类**：[PrefText]（整行表格输入框）；操作页那种紧凑小框是 `JtdxWidgets.kt` 的
 *   `JtdxTextField`。两者并存、语义不同，但参数口径统一为 `value` / `onValueChange` / `isError`
 * - **动作类**：[PrefAction]（标题 + 右侧按钮）
 *
 * 小件：[U7Badge] 徽标、[ColorDot] 色点、[LegendSection] / [LegendRow] 图例行。
 */
/** 设置分组：标题 + 圆角卡片。 */
@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                content = content,
            )
        }
    }
}

/** 组内分隔线（`outlineVariant` 淡化）。 */
@Composable
internal fun PrefDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** 「需要 U7」小徽标。 */
@Composable
internal fun U7Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** Preference 行：左侧标题/副标题，右侧可选控件。 */
@Composable
internal fun PrefRow(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    U7Badge(badge)
                }
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 组内纯说明行。 */
@Composable
internal fun PrefInfo(title: String, subtitle: String? = null, badge: String? = null) {
    PrefRow(title, subtitle, badge)
}

/** 组内小字说明。 */
@Composable
internal fun PrefNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/**
 * 开关行。
 *
 * [dotColor] 非空时在**开关右侧**显示同色小圆点，标注该开关对应的高亮色（docs/Ft8Vox.md）。
 */
@Composable
internal fun PrefSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    badge: String? = null,
    dotColor: Color? = null,
) {
    PrefRow(title, subtitle, badge, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            if (dotColor != null) {
                Spacer(Modifier.width(8.dp))
                ColorDot(dotColor)
            }
        }
    })
}

/** 高亮色小圆点（带一圈淡描边，亮色主题下也看得清）。 */
@Composable
internal fun ColorDot(color: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape),
    )
}

/** 图例里的小标题。 */
@Composable
internal fun LegendSection(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

/** 图例中的一行：色点 + 说明。 */
@Composable
internal fun LegendRow(color: Color, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(color)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 单选（FilterChip）行：标题在上，选项可横向滚动。 */
@Composable
internal fun <T> PrefChoice(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    subtitle: String? = null,
    enabled: Boolean = true,
    badge: String? = null,
    /** 非空时在标题行右端显示同色小圆点。 */
    dotColor: Color? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                U7Badge(badge)
            }
            if (dotColor != null) {
                Spacer(Modifier.weight(1f))
                ColorDot(dotColor)
            }
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (o in options) {
                FilterChip(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    enabled = enabled,
                    label = { Text(label(o)) },
                )
            }
        }
    }
}

/** 下拉选择行。 */
@Composable
internal fun <T> PrefDropdown(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    subtitle: String? = null,
    enabled: Boolean = true,
    badge: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    PrefRow(title, subtitle, badge, trailing = {
        Box {
            TextButton(onClick = { open = true }, enabled = enabled) {
                Text("${label(selected)} ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (o in options) {
                    DropdownMenuItem(
                        text = { Text(label(o)) },
                        onClick = {
                            open = false
                            onSelect(o)
                        },
                    )
                }
            }
        }
    })
}

/**
 * 数值步进行：`−` / 值 / `+`，触摸目标 48dp。
 *
 * 只能步进、**不能直接输入** —— 数值跨度大时（如时隙偏移要按十几次才到位）改用
 * [PrefNumberInput]（同为数值类，多一个「点数字就地输入」）。
 */
@Composable
internal fun PrefNumber(
    title: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    step: Int = 1,
    unit: String = "",
    /** 显示正号（用于可正可负的偏移量，如 `+1500 ms`）。 */
    signed: Boolean = false,
    subtitle: String? = null,
    enabled: Boolean = true,
    badge: String? = null,
) {
    PrefRow(title, subtitle, badge, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onChange((value - step).coerceAtLeast(range.first)) },
                enabled = enabled && value > range.first,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(48.dp),
            ) { Text("−") }
            Text(
                if (signed && value >= 0) "+$value$unit" else "$value$unit",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 62.dp),
            )
            OutlinedButton(
                onClick = { onChange((value + step).coerceAtMost(range.last)) },
                enabled = enabled && value < range.last,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(48.dp),
            ) { Text("+") }
        }
    })
}

/**
 * 数值输入行：默认是 `−` / 数值 / `+` 步进，**点中间的数字就地输入**。
 *
 * 与 [PrefNumber] 同族、按项选用：只有数值跨度大（如时隙偏移 ±2500 ms 按 100 ms 一步，从 0 到
 * +1500 要按 15 次）才值得开「直接输入」。
 *
 * 单位与「文本 ↔ 数值」的换算都交给调用方：
 * - [format] 把值渲染成文本（既作占位符、也是非编辑态的读数，**必须能被 [parse] 解析回同一值**）；
 * - [parse] 把输入解析成值，返回 null 表示非法。
 *
 * 输入是**提交式**（回车「完成」/ 点 `✓` / 输入框失焦），**绝不边输边写**：调用方多半是把值写进
 * 设置，而设置一变就可能触发重活 —— 时隙偏移就是典型（会请求采集网格热重排，边输边写会让
 * `1500` 连重排 4 次：1 → 15 → 150 → 1500，那一格全废）。非法输入（[parse] 返回 null）提交时
 * **不写盘**、直接退回原值（输入过程中 [JtdxTextField] 的 `isError` 会先红框提示）。
 *
 * **进编辑态时字段是空的、当前值只作占位符**：预填当前值会把光标停在末尾，接着打字就成了
 * `+1500` + `2000` = `+15002000`；而 `BasicTextField` 只能收 `String`、没法把预填文本整体选中。
 *
 * `±` 按钮＝「用输进来的数（没输就是当前值）取反」并立即提交：数字键盘不一定给 `-` 键，
 * 这样任何输入法都能填负值。
 */
@Composable
internal fun PrefNumberInput(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    unit: String,
    subtitle: String?,
    onChange: (Int) -> Unit,
    /** 文本 → 值；返回 null 表示非法（不写盘、退回原值）。 */
    parse: (String) -> Int? = { it.trim().toIntOrNull() },
    /** 值 → 文本；必须能被 [parse] 解析回同一值（`value ∈ range` 时）。 */
    format: (Int) -> String = { it.toString() },
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    // 进编辑态时 onFocusChanged 会先来一次 isFocused=false，用 focused 兜住，
    // 否则一进输入框就被"失焦"提交掉。
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // editing 是闸门：回车 / ✓ / 失焦谁先到谁生效，后面再来直接 return（不会重复写盘）
    fun commit() {
        if (!editing) return
        editing = false
        parse(text)?.let(onChange)
    }

    LaunchedEffect(editing) {
        if (editing) focus.requestFocus()
    }

    PrefRow(title, subtitle, trailing = {
        if (editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        // 没输就用当前值，取反后立即落地
                        val cur = parse(text) ?: value
                        editing = false
                        onChange((-cur).coerceIn(range.first, range.last))
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp),
                ) { Text("±") }
                Spacer(Modifier.width(4.dp))
                JtdxTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = format(value),
                    isError = text.isNotBlank() && parse(text) == null,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .width(74.dp)
                        .focusRequester(focus)
                        .onFocusChanged { st ->
                            if (st.isFocused) {
                                focused = true
                            } else if (focused) {
                                focused = false
                                commit()
                            }
                        },
                )
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    onClick = { commit() },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp),
                ) { Text("✓") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onChange((value - step).coerceAtLeast(range.first)) },
                    enabled = value > range.first,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp),
                ) { Text("−") }
                Box(
                    modifier = Modifier
                        .widthIn(min = 62.dp)
                        .height(48.dp)
                        .clickable {
                            text = ""
                            focused = false
                            editing = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // 下划线 + 强调色 = 可点输入（其余数值项仍是纯步进）
                    Text(
                        format(value) + unit,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                }
                OutlinedButton(
                    onClick = { onChange((value + step).coerceAtMost(range.last)) },
                    enabled = value < range.last,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp),
                ) { Text("+") }
            }
        }
    })
}

/** 文本输入行。 */
@Composable
internal fun PrefText(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    supporting: String,
    isError: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = isError,
            supportingText = { Text(supporting) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 动作行：标题 + 右侧按钮。 */
@Composable
internal fun PrefAction(
    title: String,
    buttonLabel: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    badge: String? = null,
) {
    PrefRow(title, subtitle, badge, trailing = {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(buttonLabel) }
    })
}
