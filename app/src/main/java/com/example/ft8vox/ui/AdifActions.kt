package com.example.ft8vox.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ft8vox.data.QsoTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 由按钮或下拉菜单触发的 ADIF 导入 / 导出动作。
 *
 * 拆成回调对象是为了让调用方自由决定入口形态（按钮组或「⋮」菜单）。
 */
class AdifActions(
    val import: () -> Unit,
    val export: () -> Unit,
)

/**
 * 创建 ADIF 导入 / 导出动作（日志页与设置页共用）。
 *
 * 通过系统文件选择器（SAF）读写，不需要存储权限。
 * 打开选择器会让 Activity 进入后台，但接收由前台服务保活（阶段 9），不再中断。
 */
@Composable
fun rememberAdifActions(
    log: LogViewModel,
    myCall: String,
    myGrid: String?,
    onStatus: (String) -> Unit,
): AdifActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by rememberUpdatedState(onStatus)
    val call by rememberUpdatedState(myCall)
    val grid by rememberUpdatedState(myGrid)

    // 用 octet-stream 而非 text/plain：DocumentsUI 会按 text/plain 强制追加 .txt，
    // 把 ft8vox_*.adi 变成 ft8vox_*.adi.txt；octet-stream 不改扩展名。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = try {
                val text = log.exportAdif()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法写入所选文件")
                }
                "已导出 ADIF（${text.length} 字符）"
            } catch (e: Exception) {
                "导出失败：${e.message}"
            }
            status(message)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("无法读取所选文件")
                }
                val result = log.importAdif(text, call, grid?.ifEmpty { null })
                "导入完成：新增 ${result.added} 条，更新 ${result.updated} 条，跳过 ${result.skipped} 条（共 ${result.total}）"
            } catch (e: Exception) {
                "导入失败：${e.message}"
            }
            status(message)
        }
    }

    return remember(importLauncher, exportLauncher) {
        AdifActions(
            import = {
                importLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
            },
            export = {
                exportLauncher.launch("ft8vox_${QsoTime.date(QsoTime.nowUtcMs())}.adi")
            },
        )
    }
}

/** ADIF 导入 / 导出按钮组（设置页用；日志页把同样动作收进「⋮」菜单）。 */
@Composable
fun AdifActionRow(
    log: LogViewModel,
    myCall: String,
    myGrid: String?,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = rememberAdifActions(log, myCall, myGrid, onStatus)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = actions.import) {
            Text("导入 ADIF")
        }
        OutlinedButton(onClick = actions.export) {
            Text("导出 ADIF")
        }
    }
}
