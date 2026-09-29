package com.example.ft8vox.ui.theme

import androidx.compose.ui.graphics.Color

// ---- docs/UI.md §1 深色板 ----
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

// ---- docs/UI.md §2.6 亮色板（深色板的对偶，供「外观 · 亮」使用） ----
val VoxLightBackground = Color(0xFFF4F4FA)
val VoxLightCard = Color(0xFFFFFFFF)
val VoxLightText = Color(0xFF1E1E2E)
val VoxLightSurfaceVariant = Color(0xFFE7E7F0)
val VoxLightOnSurfaceVariant = Color(0xFF5A5A6E)
val VoxLightOutline = Color(0xFFB8B8C8)
val VoxLightAccent = Color(0xFF3A6FD8)

// ---- JTDX 风格外壳（docs/UI-JTDX.md §1） ----
// 只借 JTDX 的「方块控件 + 绿色高亮 + 面板分割」形态，主色仍沿用上面的深色板。
/** 面板底色（信息头 / 表格 / 发射区）。 */
val JtdxPanel = Color(0xFF171722)
/** 面板高亮带（表头 / 分组标题 / 控制行）。 */
val JtdxPanelHi = Color(0xFF22223A)
/** 方块按钮底色。 */
val JtdxButton = Color(0xFF2B2B3D)
/** 方块按钮描边。 */
val JtdxBorder = Color(0xFF3C3C55)
/** JTDX 绿：激活按钮 / 电平条 / 指示灯。 */
val JtdxGreen = Color(0xFF3DDC5B)
/** 大号数值（刻度频率 / UTC 时钟）青蓝。 */
val JtdxValue = Color(0xFF7FE3FF)
/** 表格行底色（比卡片略深，贴近 JTDX 列表）。 */
val JtdxRow = Color(0xFF1A1A26)

// ---- 解码卡片左侧色条（docs/UI.md §3.1） ----
val BarNewDecode = Color(0xFF4CAF50) // 绿：新解码
val BarToMe = Color(0xFF89B4FA) // 蓝：与我有关
val BarCq = Color(0xFFFF9800) // 橙：CQ
val BarDuplicate = Color(0xFF757575) // 灰：重复
val BarNewCall = Color(0xFFF06292) // 粉：新呼号
val BarNewEntity = Color(0xFF8D6E63) // 棕：新 DXCC / 新 ITU / 新 CQ 区域
val BarNewGrid = Color(0xFF9C27B0) // 紫：新网格
val BarWorked = Color(0xFFE53935) // 红：已通联（删除线）
val BarTx = Color(0xFFFFEB3B) // 黄底黑字：正在发射
