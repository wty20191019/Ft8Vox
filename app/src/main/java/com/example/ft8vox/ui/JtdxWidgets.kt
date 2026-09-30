package com.example.ft8vox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ft8vox.ui.theme.JtdxBorder
import com.example.ft8vox.ui.theme.JtdxButton
import com.example.ft8vox.ui.theme.JtdxGreen
import com.example.ft8vox.ui.theme.JtdxPanel
import com.example.ft8vox.ui.theme.JtdxPanelHi
import com.example.ft8vox.ui.theme.VoxTxRed

/**
 * JTDX 风格方块控件集（docs/Ft8Vox.md）。
 *
 * 借的是 JTDX 的**形态**：方角、1dp 描边、灰底、激活时整块绿色高亮；
 * **配色仍用本项目的深色板**，不照搬 JTDX 的浅灰 Windows 风。
 */

/** 方块按钮（JTDX 的 `监听` / `停止` / `解码` 那一类）。[active] 时整块绿底黑字。 */
@Composable
fun JtdxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    accent: Color = JtdxGreen,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
) {
    val bg = when {
        !enabled -> JtdxButton.copy(alpha = 0.35f)
        active -> accent
        else -> JtdxButton
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        active -> Color.Black
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(bg)
            .border(1.dp, if (active) accent else JtdxBorder, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = textStyle, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 面板容器（1dp 描边 + 深底），对应 JTDX 里被分割出来的各个功能区。 */
@Composable
fun JtdxPanel(
    modifier: Modifier = Modifier,
    color: Color = JtdxPanel,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.background(color).border(1.dp, JtdxBorder), content = content)
}

/** 分组标题带（表头 / 小标题）。 */
@Composable
fun JtdxCaption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 小号单行输入框（JTDX 的 `DX 呼号` / 自定义报文那一类）。 */
@Composable
fun JtdxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
) {
    Box(
        modifier = modifier
            .height(26.dp)
            .background(Color(0xFF0E0E16))
            .border(1.dp, JtdxBorder)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = textStyle.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(JtdxGreen),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 只读回显框（`DX 呼号` / `DX 网格`）。 */
@Composable
fun JtdxReadOnly(
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String = "--",
) {
    Box(
        modifier = modifier
            .height(26.dp)
            .background(Color(0xFF0E0E16))
            .border(1.dp, JtdxBorder)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            value.ifEmpty { placeholder },
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 小型指示灯（六个报文槽 / 收发状态）。 */
@Composable
fun JtdxLed(on: Boolean, modifier: Modifier = Modifier, color: Color = JtdxGreen) {
    Box(
        modifier = modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(if (on) color else JtdxBorder)
            .border(1.dp, JtdxBorder, CircleShape),
    )
}

/**
 * 竖直电平条（对应 JTDX 右侧的功率表）：把 [levelDb]（−60…0 dB）画成自下而上的彩条。
 *
 * 颜色自下而上 绿 → 黄 → 红（与 JTDX 的功率表一致）。
 */
@Composable
fun JtdxMeter(
    levelDb: Float,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val frac = if (!running || levelDb <= -99.5f) 0f else ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF0B0B12))
        // 6 格刻度
        for (i in 0..6) {
            val y = h * i / 6f
            drawLine(JtdxBorder, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        val barH = h * frac
        if (barH > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(VoxTxRed, Color(0xFFFFC107), JtdxGreen),
                    startY = 0f,
                    endY = h,
                ),
                topLeft = Offset(0f, h - barH),
                size = Size(w, barH),
            )
        }
    }
}

/** 表格列头（`时隙 / UTC / 分贝 / 时差 / 频率 / 信息`）。 */
@Composable
fun JtdxTableHeader(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(JtdxPanelHi)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}
