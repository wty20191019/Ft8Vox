package com.example.ft8vox.ui.theme

import androidx.compose.ui.graphics.Color

// ---- new_ui.md §0 深色板 ----
/** 背景。 */
val VoxBackground = Color(0xFF12121A)
/** 卡片 / 表面。 */
val VoxCard = Color(0xFF1E1E2E)
/** 主文字。 */
val VoxText = Color(0xFFCDD6F4)
/** 强调蓝。 */
val VoxAccent = Color(0xFF89B4FA)

// ---- 派生色 ----
/** 次级表面（输入框、未选 chip）。 */
val VoxSurfaceVariant = Color(0xFF2A2A3C)
/** 次级文字。 */
val VoxOnSurfaceVariant = Color(0xFF9AA0B5)
/** 分隔线 / 描边。 */
val VoxOutline = Color(0xFF3A3A4E)
/** RX 绿。 */
val VoxRxGreen = Color(0xFF4CAF50)
/** TX 红。 */
val VoxTxRed = Color(0xFFF44336)
/** 错误粉。 */
val VoxError = Color(0xFFF38BA8)

// ---- 解码卡片左侧色条（new_ui.md §3.3） ----
val BarNewDecode = Color(0xFF4CAF50) // 绿：新解码
val BarToMe = Color(0xFF89B4FA) // 蓝：与我有关
val BarCq = Color(0xFFFF9800) // 橙：CQ
val BarDuplicate = Color(0xFF757575) // 灰：重复
val BarNewCall = Color(0xFFF06292) // 粉：新呼号
val BarNewEntity = Color(0xFF8D6E63) // 棕：新 DXCC / 新 ITU
val BarNewGrid = Color(0xFF9C27B0) // 紫：新网格
val BarWorked = Color(0xFFE53935) // 红：已通联（删除线）
val BarTx = Color(0xFFFFEB3B) // 黄底黑字：正在发射
