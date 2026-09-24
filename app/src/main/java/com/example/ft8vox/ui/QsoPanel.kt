package com.example.ft8vox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.util.Locale

/** 待确认的发射动作（防误发闸门）。 */
sealed interface PendingTx {
    /** 由我发起 CQ 呼叫。 */
    data object Cq : PendingTx

    /** 应答对方的 CQ。 */
    data class Reply(val call: String, val grid: String?, val df: Int? = null) : PendingTx
}

/** 时隙起点（UTC 毫秒）→ HH:MM:SS 文本。 */
private fun formatUtcHms(utcMs: Long): String {
    if (utcMs <= 0) return "--:--:--"
    val secs = utcMs / 1000
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        (secs / 3600) % 24,
        (secs / 60) % 60,
        secs % 60,
    )
}

/** 发射周期选择 + 开始/停止。 */
@Composable
fun TxControlRow(
    txParity: Int,
    armed: Boolean,
    canOperate: Boolean,
    onParityChange: (Int) -> Unit,
    onStartCq: () -> Unit,
    onStopTx: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("发射周期", style = MaterialTheme.typography.labelMedium)
        FilterChip(
            selected = txParity == 0,
            enabled = !armed,
            onClick = { onParityChange(0) },
            label = { Text("偶") },
        )
        FilterChip(
            selected = txParity == 1,
            enabled = !armed,
            onClick = { onParityChange(1) },
            label = { Text("奇") },
        )
        Button(onClick = onStartCq, enabled = !armed && canOperate) { Text("开始 CQ") }
        Button(
            onClick = onStopTx,
            enabled = armed,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
        ) {
            Text("停止发射")
        }
    }
}

/** 当前 QSO 进度、发射倒计时与最近一次发射。 */
@Composable
fun QsoStatusLine(status: ReceiverStatus) {
    val qso = status.qso
    Column {
        Text(
            "QSO：${qso.description}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (qso.theirCall != null) {
            Text(
                String.format(
                    Locale.US,
                    "对方 %s%s｜报告 收 %s / 发 %s",
                    qso.theirCall,
                    qso.theirGrid?.let { " ($it)" } ?: "",
                    qso.reportReceived?.toString() ?: "--",
                    qso.reportSent?.toString() ?: "--",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (status.txArmed) {
            val countdown = if (status.txing) {
                "发射中…"
            } else {
                "下一发射时隙 %.1f s".format(status.txCountdownMs.coerceAtLeast(0) / 1000.0)
            }
            Text(
                "发射：$countdown｜待发「${qso.txText ?: "--"}」",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (status.lastTxText != null) {
            Text(
                "最近发射「${status.lastTxText}」@${formatUtcHms(status.lastTxSlotMs)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * 防误发确认对话框。
 *
 * 无 CAT 前提下 App 无法确认电台状态，因此每次开始自动发射前都必须由操作者确认。
 */
@Composable
fun TxConfirmDialog(
    pending: PendingTx,
    status: ReceiverStatus,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val what = when (pending) {
        PendingTx.Cq -> "开始呼叫 CQ"
        is PendingTx.Reply -> "应答 ${pending.call}${pending.grid?.let { " ($it)" } ?: ""}"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认发射") },
        text = {
            Column {
                Text(what)
                Text("呼号：${status.myCall}${if (status.myGrid.isNotEmpty()) " / ${status.myGrid}" else ""}")
                Text("频率：${status.selectedFreqHz} Hz")
                Text("周期：${if (status.txParity == 0) "偶数" else "奇数"}｜协议：${status.protocol.name}")
                Text(
                    "请确认电台已就绪（频率/模式/VOX）。发射期间请勿离开。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("确认发射") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
