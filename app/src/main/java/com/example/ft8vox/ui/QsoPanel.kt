package com.example.ft8vox.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 待确认的发射动作（防误发闸门）。 */
sealed interface PendingTx {
    /** 由我发起 CQ 呼叫。 */
    data object Cq : PendingTx

    /** 应答对方的 CQ。 */
    data class Reply(val call: String, val grid: String?, val df: Int? = null) : PendingTx
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
                Text(
                    "时隙：自动（下一个 ${if (status.txParity == 0) "偶" else "奇"}）" +
                        "｜协议：${status.protocol.name}",
                )
                if (!status.txEnabled) {
                    Text(
                        "发送总开关当前为「关」：请先在操作页打开「发送」开关再确认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB3261E),
                    )
                }
                Text(
                    "请确认电台已就绪（频率/模式/VOX）。发射期间请勿离开。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = status.txEnabled) { Text("确认发射") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
