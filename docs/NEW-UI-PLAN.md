# Ft8Vox 新 UI（new_ui.md）实施计划

> 依据：`docs/new_ui.md`（单操作页风格）
> 日期：2026-09-25
> 范围：按用户确认「全量按设计实现」，分阶段推进；每阶段独立可构建、可验证。

## 0. 总览

新 UI 相对现版（阶段 7）的核心变化：

| 维度 | 现版 | 新版 |
| --- | --- | --- |
| 主题 | Material 默认/动态色，浅色为主 | 固定深色板（bg `#12121A`、card `#1E1E2E`、文字 `#CDD6F4`、强调 `#89B4FA`） |
| 顶部 | 无 AppBar，标题在页内 | 固定 56dp AppBar：UTC 时间 / 频率·模式 / RX·TX 点 / 喇叭 |
| 底部 | 仅底部导航 | 底部导航 + **常驻状态条**（RX·TX / VOX / 解码速率 / 总数 / QSO / 队列 / 时间偏差） |
| Tab | 操作 / 日志 / 网格 / 设置 | 操作 / **地图** / 日志 / 设置 |
| 操作页 | 状态栏 + 瀑布 + Rx/Tx 面板 + 列表 + 折叠面板 | 瀑布 30% + **多选筛选 Chip** + **彩色解码卡片（手势）** + **底部发射抽屉** |
| 地图 | 深蓝底 + Maidenhead 网格线 + 绿/蓝历史 + 实时点 | 全屏深色**无网格线** + 蓝/黄/红标记 + 呼号标记 + CQ 红旗 + 信号连线 |
| 设置 | 平铺分区 | Android Preference 风格，6 组（电台/音频/FT8/高亮/外观/日志网络） |

新增能力中，部分需要动 native 或数据层，已单列 **U7**；UI 阶段未接后端前，相关控件先以「即将支持」占位或隐藏，接通后再点亮。

## 1. 阶段拆分

### U0 计划（本文档）
把 new_ui.md 拆为 U1–U7，明确每阶段交付与验收。

### U1 全局主题与外壳
- 色板与字体 token（呼号 16sp 粗、消息 13sp、辅助 11sp；触摸目标 ≥48dp）。
- 顶部 AppBar（56dp）：菜单（频段/模式/帮助）、UTC 时钟、`频率 MHz · 模式`、RX/TX 圆点（TX 脉动）、喇叭（音频/电平速览）。
- 底部常驻状态条：RX/TX、VOX 电平条（未接后端前显示占位）、解码 `x/min`、总数、QSO、队列、时间偏差红字。
- 底部导航改名/排序：操作 / 地图 / 日志 / 设置，选中高亮蓝。
- 验收：深色主题全局生效（无动态取色）；AppBar 与状态条在所有页常驻；UTC 每秒刷新；TX 时圆点红色脉动；四 Tab 可切换且不打断接收。

### U2 操作页重构
- 布局顺序：AppBar → 瀑布（约 30% 屏高）→ 筛选 Chip 行 → 解码列表（占满剩余）→ 发射控制条 → 底部状态条/导航。
- 瀑布：全宽 0–3000 Hz、归一化六段配色（深蓝→青→绿→黄→橙→红）、右侧 `Ref -50~-10dB`、顶部浮条（增益/噪抑/带宽/暂停）、单击设发射频、长按菜单、双指捏合缩放。
- 筛选 Chip：全部 / 与我有关 / CQ / 回复 / 73 / 已通联；「全部」互斥、其余多选、全不选→空态；角标计数；选中蓝底白字。
- 解码卡片：左侧色条（新解码/与我有关/CQ/重复/新呼号/新 DXCC·ITU/新网格/已通联/正在发射）+ 呼号 16sp 粗 / 网格 / 报告 / 时间；含我呼号红字。
- 手势：左滑呼叫、右滑忽略、单击半屏详情（距离/方位/网格/强度/呼叫·日志·备注）、双击跳地图、长按菜单（复制/宏/忽略）。
- 空态：无解码「等待解码…」；筛选项为空「没有符合条件的解码消息」。
- 验收：筛选/计数/高亮纯逻辑 JVM 单测全绿；手势与半屏详情在模拟器可演示；接收不因重构中断。

### U3 发射控制抽屉
- 收起态 56dp：目标 `→ JA1ABC` / 当前消息类型 / 大「发送」按钮。
- 展开 Bottom Sheet：目标信息（呼号/网格/频率/取消目标）；消息类型 2 行大按钮（CQ / 回复 / 交换 / RR73 / 73）；自定义文本（标签、`42/75` 计数、清除 X）；宏按钮 4×2（可编辑）；发送队列横向胶囊（删除/拖序/清空）；大发射按钮（待发摘要；本周期剩 >2.5s 立即发，否则下一周期；TX 时变红+倒计时）。
- 依赖：宏与发送队列为**新增状态**（纯 Kotlin，可先内存态，落 DataStore）。
- 验收：队列顺序与「立即发/排下一周期」阈值逻辑 JVM 单测；模拟器演示收起/展开与队列编辑。

### U4 地图页
- 全屏深色底图，**无网格线图层**；底部浮层图例+统计；右下角浮控（缩放 / 回我的位置）。
- 网格标记：蓝=本会话解码到信号（重启清除）、黄=日志已通联、红=日志已确认。
- 呼号标记：无网格信息时按呼号前缀归属地坐标立标，同样按蓝/黄/红着色（需前缀→坐标近似表，见 U7）。
- CQ 旗帜：解码到 CQ 立红旗，可设置是否带呼号/信号强度。
- 信号连线：上一时隙各方之间连线，线上内容沿方向运动（报告数字、`73`/`RR73` 文字）；可整体关闭文字→移动方块。
- 验收：标记颜色规则与连线动画纯逻辑单测；模拟器可演示标记与浮控（无信号时为空，真机验证实时层）。

### U5 日志页
- 顶部搜索 + 波段 + 模式 + 日期；表格化卡片（呼号/网格/时间/RST/模式）。
- 长按：编辑 / 删除。
- 底部统计：QSO 数、DXCC、网格、波段柱图。
- 菜单：新增通联、导入 / 导出 ADIF、清空日志（~~局域网后台地址~~已按用户要求删除，见 §收尾：删除三处占位功能）。
- 验收：筛选与统计 JVM 单测；长按编辑删除端到端。

### U6 设置页（Preference 风格）
- 6.1 电台（仅 VOX）：VOX 触发方式、延迟 50–1000ms、阈值 −60~−20dB、发射前导音开关+时长、输出声卡、测试音+电平条、PTT 延迟、看门狗。
- 6.2 音频：输入/输出设备、采样率 44100/48000/96000、输入增益。
- 6.3 FT8：模式 FT8/FT4（FST4 不做，见 U7 能力表）、时隙偏移 −2.5s–+2.5s（整个时隙一起偏移：解码窗口 + 发射起点；用法见 U7 补记）、解码深度（max_candidates / ldpc_iterations / rx_time_osr / rx_freq_osr）、自动序列参数。
- 6.4 高亮与提醒：新 CQ 区域 / 新 ITU / 新 DXCC / 新网格 / 新前缀 / 新呼号 / 已通联（删除线·下划线·隐藏）/ 含我呼号哔声 / 末端标记。
- 6.5 外观：暗/亮、字体小/中/大、恢复布局（~~瀑布配色~~已删除）。
- 6.6 日志（原「日志/网络」）：ADIF 路径、清空日志（~~CloudLog / LoTW / eQSL~~、~~局域网后台开关~~已删除）。
- 依赖：多数为新增设置项；真正生效依赖 U7。
- 验收：设置项持久化（DataStore）与回读一致；未接通后端的项标「需要 U7」。

### U7 后端能力补齐（与 UI 解耦，按需推进）
| 能力 | 说明 | 风险/备注 |
| --- | --- | --- |
| VOX | native 音频检测/静音检测、延迟、阈值、前导音、测试音、PTT 延迟、看门狗 | 需改 `audio_engine.c` + 新 JNI；PTT 无 CAT，靠 VOX |
| 音频路由/增益 | 输入输出设备选择、44100/48000/96000、输入增益 | AAudio 设备枚举与 `setDeviceId`（**已落地 U7c**） |
| ~~FST4~~ | 模式新增 | **不做**：`ft8_lib` 不含 FST4，需自研调制解调，量与风险不匹配 |
| DXCC/ITU 与新呼号/新前缀 | 实体表 + 呼号前缀映射、呼号归属地坐标 | 现为紧凑前缀近似；需实体数据 |
| 高亮与提醒 | 各提醒项接入操作页高亮、哔声、末端标记 | 依赖 U6 设置 |
| ~~局域网后台~~ | App 内 HTTP 服务、后台地址展示 | **不做**：需前台服务常驻与网络凭据；占位项已删除（见 §收尾：删除三处占位功能） |
| ~~在线日志~~ | CloudLog / LoTW / eQSL | **不做**：需网络、凭据与安全存储；占位项已删除（同上） |
| 离线地图瓦片 | ✅ 已实装（2026-09-26） | 单张 z5 Web Mercator 卫星底图（`assets/map/world_z5.jpg`，4.75 MB，q90），按可见区域流式解码 + ×0.7 暗化；见 §补记：离线卫星底图 |

## 2. 建议顺序与依赖

```
U1 → U2 → U3 → U4 → U5 → U6 → U7（按能力逐项）
```

- U1 是 U2–U6 的基座，必须最先做。
- U2/U3 共用筛选与发射状态；U3 的宏/队列是 U2 长按菜单的数据来源。
- U4 的呼号标记、CQ 旗帜依赖 U2 的解码分类结果。
- U6 的设置项为 U7 提供开关；U7 逐项接通后再回填 U6 的「需要 U7」标记。

## 3. 与现有文档的关系

- 本计划是 `docs/new_ui.md` 的落地拆解，实施状态回填到本文档与 `docs/ROADMAP.md`。
- 阶段 7 的 `docs/UI-DESIGN.md` 描述旧四页设计；新 UI 落地期间两者并存，全部替换后归档旧文档。
- 真机回归清单 `docs/REGRESSION.md` 在 UI 改版完成后需按新布局重写（U2–U6 完成时同步更新）。

## 4. 实施进度

| 阶段 | 状态 | 提交 | 备注 |
| --- | --- | --- | --- |
| U0 计划 | ✅ | | 本文档 |
| U1 全局主题与外壳 | ✅ | | 色板/字体/AppBar/底部状态条/4 Tab 改名已落地 |
| U2 操作页重构 | ✅ | | 瀑布 0.30 屏高（六段配色/浮条/捏合缩放/长按）、多选筛选 Chip+角标、彩色解码卡片（左滑呼叫/右滑忽略/单击半屏详情/双击地图/长按菜单）、呼号红字、去重灰条；JVM 146 测试全绿 |
| U3 发射控制抽屉 | ✅ | | 收起 56dp（目标/类型/发送·停止）+ 上拉 Bottom Sheet（目标信息、消息类型 2 行、自定义 42·75 计数、宏 4×2 可编辑、队列胶囊、大发射按钮）；JVM 160 测试全绿 |
| U4 地图页 | ✅ | | 全屏无网格线深色底图 + 蓝/黄/红标记 + 呼号前缀定位 + CQ 红旗 + 上一时隙信号连线；JVM 164 测试全绿 |
| U5 日志页 | ✅ | | 顶栏搜索+波段/模式/日期 chip；表格化卡片（呼号/网格/时间/RST/模式，长按编辑·删除）；底部统计 QSO/DXCC/网格/确认 + 波段柱图；「⋮」菜单新增·导入·导出 ADIF·局域网地址(U7)·清空；JVM 164 测试全绿 |
| U6 设置页 | ✅ | | Preference 风格 8 组（台站/VOX/音频/FT8/高亮/外观/日志/关于；原「日志网络」，网络项已删除）；新增 30+ 设置项落 DataStore；外观「暗/亮」「字体小中大」即时生效；高亮开关接入解码列表；未接后端项标 U7 置灰；JVM 168 测试全绿 |
| U7a DXCC/ITU 实体表 | ✅ | | `qso/Dxcc.kt`：约 180 个实体（规范名 + 代表坐标 + CQ/ITU 区域）+ 呼号前缀映射（最长前缀优先，含常用呼号区扩展）；日志 DXCC 按实体去重；「新 DXCC / 新 ITU / 新 CQ 区域」开关点亮；JVM 180 测试全绿 |
| U7b VOX/PTT native | ✅ | | native 输入电平/VOX 触发判定（音频/静音检测 + 阈值 + 去抖）；发射前导静音 + 前导音（1 kHz），`planTx` 对齐时隙起点；写入看门狗；测试音；状态栏/音频速览显示 VOX 电平；设置页 6.1 除「输出声卡」外全部点亮；JVM 187 + 设备端 26 测试全绿 |
| U7c 音频路由与增益 | ✅ | | `engine/AudioDevices.kt` 枚举输入/输出设备（系统默认恒为首项）；AAudio `setDeviceId` 选择输入（`nativeStartCapture`）与输出（`nativeStartPlayback`）；`nativeSetInputGain` 采集增益在 DSP 线程热生效（含 VOX 电平，限幅防回绕）；设置页 6.1 输出声卡、6.2 输入设备/输入增益点亮，音频速览显示设备与增益；JVM 191 + 设备端 30 测试全绿 |
| U7d 含我呼号哔声 | ✅ | | 收到 `to == 我呼号` 的报文时用 `ToneGenerator`（通知流）短促提醒；`shouldAlertMyCall` 纯函数判定；设置页 6.4 开关点亮；JVM 198 测试全绿 |
| U7 其余能力 | ✅（结项） | | 局域网后台、在线日志：**不做**（占位项已删除）；离线瓦片：**已实装**（单张 z5 卫星底图，见 §补记：离线卫星底图）；FST4：**不做**（`ft8_lib` 无该模式） |
| U8 波段频率与自动发射 | ✅ | | 每波段多频率选择 + 自定义波段/频率 + 发送总开关（收起态条，默认只接收；U9 补记 4 定稿为**纯权限**）+ 时隙固定自动（按手机 UTC 选下一时隙，无设置项）+ 默认呼叫 CQ；JVM 全绿 |
| U9 自动程序 | ✅ | | 取代 Call 1st：等级 0 手动 / 1 首先解码 / 2 解码窗口择优 / 3 解码后择优 / 4+ 自动搜索（无可答目标自动 CQ）；策略开关 回答·呼叫已通联 / 优先新呼号 / 报告信息优先 / 最远距离取代最佳信噪比 / 单次通联；顶栏菜单 + 发射抽屉入口 + 设置页面板；窄带过滤按用户决定不做；JVM 全绿 |

### U2 落地说明（与设计的取舍）

- **筛选与查询**：Chip 改为「全部/与我有关/CQ/回复/73/已通联」多选 + 角标；原「呼号/前缀/网格」搜索框收起为 Chip 行尾的放大镜，点击展开，功能保留。
- **忽略名单**：右滑/长按菜单忽略的呼号持久化到 DataStore（`ignored_calls`），不显示且不参与 Call 1st。
- **高亮优先级**：正在发射 > 与我有关（含当前对手）> CQ > 已通联（删除线）> 重复（灰）> 新网格（紫）> 新实体/前缀（棕）> 新呼号（粉）> 新解码（绿）。
- **发射控制**：本阶段仍沿用折叠面板（含频率微调 ±10/±100、Hold Tx、周期、Call 1st）；§3.4 的底部抽屉与宏/队列留待 **U3**。原长按「逐条发送/自由文本」对话框随之移除，由 U3 的报文按钮承担。
- **浮条控件**：原「增益/噪抑/带宽」占位按钮与「▶/❚❚」接收开关均已移除（后端无对应能力 / 保持瀑布图纯净）；改为进入操作页且已授权时自动开始接收，未授权时点解码区空态文案「未开始接收：点此授权并开始接收」发起授权。
- **地图联动**：双击解码卡片切到地图页（暂不预选呼号，U4 接入）。

### U3 落地说明（与设计的取舍）

- **收起态**：56dp 条 = 左「→ 目标」/ 中「当前报文类型 + 发射倒计时」/ 右按钮。未发射时为「发送」（禁用条件：未填呼号，或自定义与队列皆空），`txArmed` 时变红为「停止」。点条身（左/中区域）上拉展开。
- **展开抽屉**：按设计顺序实现 1–6 项。大发射按钮在 `txArmed` 时变红并显示倒计时，点按即停止发射；非发射态显示「发送：<待发摘要>」。
- **立即发 / 排下一周期**：`TxScheduler.canSendNow(当前周期, 我方周期, 剩余毫秒)` = 周期一致且剩余 ≥ 2.5 s。满足则走新增的 `SessionViewModel.sendNow(text)`（**不按时隙对齐**，立即编码播放）；否则走原 `sendOnce(text)`（排到下一个我方周期）。抽屉底部给出当前判定文案。
- **消息类型按钮**：CQ 走 `startCq()`、回复走 `answer()`（两者仍经原有「防误发」确认弹窗）；交换/RR73/73 为一次性报文，走上面的调度逻辑；「交换」的报告取目标台最近一次解码的 SNR（clamp −24…+30）。
- **宏**：8 个模板存 DataStore（`macros`，`\n` 连接，默认见 `DEFAULT_MACROS`），占位符 `{call}/{mycall}/{mygrid}/{report}`；点击展开到自定义框，长按弹编辑对话框。
- **发送队列**：报文原文存 DataStore（`tx_queue`，`\n` 连接，上限 20）。胶囊标签 `序号:目标/类型`；点按载入自定义框、长按菜单（上移/下移/删除）、右侧 X 删除、顶部「清空」。主发送优先来自定义框，为空则取队首并在**直接发出**后出队（CQ/回复因走确认弹窗不出队）。
- **设计外补充（自动序列）**：抽屉尾部保留「发射周期（偶/奇）/ Hold Tx / Call 1st（关·最强 CQ·首个 CQ）」一行——§3.4 未列但属发射必备，移到 U6 设置前先置于此处。原折叠面板的频率微调（±10/±100）暂未在抽屉中提供，`selectedFreqHz` 仍可由瀑布图点选/长按调整。
- **状态来源**：目标 = 进行中 QSO 的对手，否则为操作页最近点选的解码行（单击/滑呼/详情「呼叫」均设置）；「取消目标」清空选择并停止发射。

### U4 落地说明（与设计的取舍）

- **底图**：整页单一深色（`#0B0B12`）+ 一层略亮的「世界矩形」，**完全去掉 Maidenhead 网格线与经纬轴**；无任何标记时整块保持空白。**（2026-09-26 起）**底图升级为离线 Web Mercator 卫星影像，投影随之由等距圆柱改为 Web Mercator；见 §补记：离线卫星底图。
- **数据层**：新增纯逻辑 `qso/MapModel.kt`（`MapTier` / `GridMarker` / `CallMarker` / `CqFlag` / `SignalLink` 与派生函数）与 `qso/CallLocation.kt`（呼号前缀 → 归属地近似坐标）。旧的地图专用 `SpotBuilder`/`LiveSpot`/`GridIndex`/`GridGranularity` 已删除，测试同步替换为 `MapModelTest`/`CallLocationTest`。
- **网格标记**：按 **4 字符方格**归并；优先级 红（日志已确认 QSL/LoTW）> 黄（日志已通联）> 蓝（本会话解码，重启清空）。世界视图下按最小像素放大，避免缩成一个点。
- **呼号标记**：报文无网格时，用呼号前缀映射实体表（`Dxcc`，最长前缀匹配，如 `EA8` > `EA`、`KH6` > `K`）的代表坐标立标；标记后带 `~` 表示「前缀近似」。坐标为实体代表点（非逐条呼号精确到台站）。
- **CQ 红旗**：解码到 CQ 立红旗，可在底部浮层切换「CQ 呼号 / CQ 强度」。
- **信号连线**：**只取最近一个时隙**的解码，方向 `发方 → 收方`；CQ（无收方）连到「我」（需已知我方网格）。线上内容：报告数字 / `R<报告>` / `RR73` / `73` 沿方向运动，其余载荷只跑一个移动方块；底部浮层「连线文字」可整体关闭文字。
- **交互**：单击命中最近的呼号标记/CQ 旗帜并在底部浮层显示详情（应答 / 日志 / 清除）；右下角浮控为放大、缩小、回我的位置；双指缩放/拖动平移沿用原实现。
- **跳转联动**：操作页双击解码卡片改为把呼号带入地图页并自动定位（`MainShell` 传 `focusCall`/`focusSeq`，U2 预留的接口就此接通）。
- **设置**：新增 `mapCqFlagShowCall` / `mapCqFlagShowSnr` / `mapShowLinkText` 三项，落 DataStore；开关放在地图页底部浮层（U6 设置页再收敛）。
- **模拟器验收**：地图页空态、右下浮控、三个显示开关、开关重启后回读一致均通过；实时标记需真机天线信号（模拟器无音频输入）。

### U5 落地说明（与设计的取舍）

- **筛选入口**：顶部为「搜索呼号/网格」输入框（带清除 X）+ 波段 / 模式 / 日期三个 `FilterChip`（高度 48dp 满足触摸规范）。波段/模式为下拉菜单，日期改为**区间对话框**（起止两个输入框，可只填一侧；支持 `YYYY-MM-DD` 与纯数字 `YYYYMMDD`，闭区间按 UTC）。任一筛选项生效时行尾出现「清除」chip。
- **卡片布局**：`Card`（`surface` token 底、圆角）两栏——左侧呼号 16sp 粗 + 网格（未知显示 `----`）；右侧 `波段 模式`（强调蓝）/ 时间（等宽）/ `收 x / 发 y`（缺省 `--`）。已确认（QSL/LoTW=Y）时左侧竖条变绿并显示 ✔。卡片 `defaultMinSize(56dp)`，单击进编辑，**长按弹「编辑 / 删除」菜单**（锚定卡片）。
- **底部统计**：常驻面板显示 QSO / DXCC / 网格 / 确认 四格，其下为**波段柱图**（柱高按条数比例、可横向滚动）。DXCC 由 `Dxcc` 呼号前缀映射实体表去重（`LogStats.uniqueEntities`），面板内标注「DXCC 按呼号前缀映射实体表（常用实体精选子集）」。
- **「⋮」菜单**：新增通联、导入 ADIF、导出 ADIF、局域网后台地址（U7，弹说明框占位）、清空日志（二次确认，文案含条数并提示先导出）。ADIF 动作从原按钮组重构为可复用回调 `rememberAdifActions`（`AdifActionRow` 仍供设置页使用）。
- **移除**：原顶部独立「统计卡（可收起）」与「补录 / 导入 / 导出」按钮行并入上述菜单与底部统计；筛选框由两行文本输入改为 chip + 对话框。
- **模拟器验收**：搜索、波段、模式、日期区间筛选、长按编辑 / 删除、新增保存、清空/局域网对话框均通过；「显示 X / 共 Y」与底部统计随增删实时更新。日期字段在中文输入法下会把 `-` 上屏为全角破折号，故端到端用 `YYYYMMDD` 验证（解析两种格式都支持，非缺陷）。

### U6 落地说明（与设计的取舍）

- **分组**：按 §6 落地「台站 / 电台（仅 VOX）/ 音频 / FT8 / 高亮与提醒 / 外观 / 日志（原「日志与网络」）/ 关于」共 8 组（「日志与网络」在收尾时改名「日志」，见 §收尾：删除三处占位功能）。§6 未列台站信息（呼号/网格/波段/备注），但它属发射必备且无 CAT，故置于首组并注明「设计外补充」。
- **组件**：自建 Preference 风格原语——`SettingsGroup`（标题 + 圆角卡）、`PrefRow`（左标题/副标题 + 右控件，`minHeight 56dp`）、`PrefSwitch` / `PrefChoice`(FilterChip) / `PrefDropdown` / `PrefStepper`(`−/值/+`，按钮 48dp) / `PrefText` / `PrefAction` / `PrefInfo` / `PrefNote` / `U7Badge`。
- **新增设置项（落 DataStore）**：VOX 触发/延迟/阈值/前导音及时长/输出声卡/PTT 延迟/看门狗；输入设备/输入增益；时隙偏移；新 CQ 区域/新 ITU/新 DXCC/新网格/新前缀/新呼号/已通联样式（删除线·下划线·隐藏）/含我呼号哔声/末端红·蓝标记；主题/字体；~~瀑布配色~~；~~CloudLog/LoTW/eQSL/局域网后台~~（三项已删除，见 §收尾：删除三处占位功能）。`SampleRatePref` 增补 `96000`。
- **已接通**：解码深度全套 + `Hold Tx` / 发射周期 / `Call 1st` / 最大重试（自动序列参数由 U3 抽屉迁回本节）；**外观「暗/亮」**接通 `Ft8VoxTheme(darkTheme)`，新增亮色板 `VoxLight*`（强调蓝加深保白底对比度）；**字体小/中/大**通过覆盖 `LocalDensity.fontScale`（0.9/1.0/1.15）统一缩放 sp、不影响 dp；**「高亮与提醒」**中 新呼号/新网格/新 DXCC/新 ITU/新 CQ 区域/新前缀 接入 `DecodeHighlight.classify(..., HighlightPrefs)`（关闭后角色按 新网格→新实体（DXCC/ITU/CQ 区域/前缀）→新呼号→普通 顺序回退），**已通联样式**与**末端红·蓝标记**接入解码卡片（隐藏样式会在操作页过滤掉已通联行）；「恢复布局」重置瀑布高度/字体（原含瀑布配色，该设置项已删除）。
- **标记「U7」置灰**：无（原「发射偏移」已实装，见 §U7 补记：时隙偏移）。其余原占位项 —— 瀑布配色、CloudLog/LoTW/eQSL、局域网后台 —— 已按用户要求**删除**，见 §收尾：删除三处占位功能。`Protocol` 仅 FT8/FT4，FST4 已定为**不做**，不再显示占位说明。「新 CQ 区域 / 新 ITU」已在 **U7a** 点亮；**VOX 触发/延迟/阈值、发射前导音与时长、测试音、PTT 延迟、看门狗**已在 **U7b** 点亮；**输出声卡、输入设备与增益**已在 **U7c** 点亮；**含我呼号哔声**已在 **U7d** 点亮。
- **亮色主题的连带改造**：原 UI 直接引用硬编码 `VoxCard/VoxText/VoxOnSurfaceVariant/VoxSurfaceVariant/VoxBackground`，亮色下会失效；已全部改为 `MaterialTheme.colorScheme.{surface,onSurface,onSurfaceVariant,surfaceVariant,background}`，并把选中 Chip / 发送按钮 / 强调文字改用 `primary`。语义色（`barColor` 各高亮、`VoxRxGreen`/`VoxTxRed`/`VoxError`、地图标记、瀑布底层）保持固定，暗色表现不变；地图底图与瀑布图本身仍固定深色（设计如此）。
- **模拟器验收**：设置页全部 8 组滚动渲染正常；主题切「亮」全局即时生效且**重启后回读一致**；字体切「大」字号明显放大；呼号/网格/波段等旧值回读一致；无崩溃（`logcat` 无 FATAL）。未接后端的项均为禁用态 + `U7` 徽标。

### U7a 落地说明：DXCC / CQ / ITU 实体表

- **新增 `qso/Dxcc.kt`**：约 180 个 DXCC 实体，每个含规范中文名、代表中心坐标与代表 CQ / ITU 区域；前缀表按**最长前缀优先**匹配（`EA8` > `EA`、`KH6` > `K`、`KL` > `K`），并扩充常见呼号区（美国 `AA–AL`、日本 `JB–JS`、中国 `B`、德国 `DB–DR`、意大利 `IK–IY`、西班牙 `EB–EH/AM–AO`、俄罗斯 `UA–UI`、乌克兰 `US–UZ` 等），使同实体的不同呼号区正确归并。
- **边界与取舍**：表为**常用实体精选子集**，未收录前缀返回 `null`（不误判）；跨区实体（美/加/俄/南极等）取**代表区域**，用于「新 CQ 区域 / 新 ITU」的近似判定。自检 `Dxcc.unknownPrefixTargets()` 守护表内一致性（前缀目标必须存在于实体表）。
- **接入点**：
  - `CallLocation` 退化为 `Dxcc` 的薄封装（保留 `Place` / `locate`），地图呼号标记、`MapModel` 无需改动；
  - `LogQuery.computeStats` 的 DXCC 由「前缀名去重」改为 `Dxcc.resolve(...).name` 去重（同实体不同呼号区并为一个）；
  - `WorkedIndex` 新增 `entities` / `cqZones` / `ituZones` 与 `hasWorkedEntity` / `hasWorkedCqZone` / `hasWorkedItuZone`；
  - `DecodeHighlight` 新增 `newEntity` / `newItu` / `newCqZone` 标记（`newPrefix` 保留为粗口径），任一成立即归入 `NEW_ENTITY` 色条；`DecodeStyle.hasNewEntityMark` 供解码卡片显示棕色标记点；
  - 设置页「新 CQ 区域 / 新 ITU」解除置灰与 `U7` 徽标；`OperateScreen` 逐项传入 `HighlightPrefs`。
- **测试**：新增 `qso/DxccTest.kt`（实体/区域解析、最长前缀、呼号区归并、表内一致性、区域与坐标范围）、`WorkedIndex` 实体·区域用例、`LogQuery` 实体归并用例、`DecodeHighlight` 新开关回退与 `hasNewEntityMark` 用例；JVM **180/180** 全绿。

### U7b 落地说明：VOX / PTT native

- **背景**：本 App 无 CAT，无法直接控制电台 PTT，只能靠音频序列间接键控。U7b 把设置页 6.1 里原先「仅保存设置值」的参数落成 native 行为。
- **native（`audio_engine.c`）**：
  - DSP 线程对原始输入块计算 RMS 电平（dBFS，快攻击/慢释放平滑），按 `vox_trigger`（音频/静音检测）、`vox_threshold_db`、`vox_delay_ms`（去抖）维护 `vox_open`；结果经 `nativeGetState`（索引 10/11）暴露。
  - `nativePlayTx(pcm, pttSilenceMs, leadToneMs)`：数据前插入前导静音与 1 kHz 前导音（0.6 幅度），供 VOX 抢先键控。
  - `write_blocking()` 看门狗：实际阈值 = max(`watchdogMs`, 本段音频预期时长 + 3 s)，只作卡死保护，不截断合法整时隙发射。
  - `nativePlayTone(freqHz, ms)`：测试单音（首尾 10 ms 余弦包络）。
  - `nativeSetVox(...)` 热下发全部 VOX/PTT 参数。
- **Kotlin 调度**：新增纯函数 `planTx(now, slotMs, txParity, preambleMs)` 与 `effectivePreambleMs(...)`：
  - 无前导 → 就地发射（沿用原 1200 ms 起发窗口）；
  - 有前导 → 提前 `PTT 延迟 + 前导音时长` 启动播放，使 FT8 数据仍落在时隙起点；迟到时按迟到量缩短前导（优先保前导音），迟到超过「前导 + 1200 ms」则放弃本时隙。
  - `sendNow` / `transmitTest`（立即发）也走 `playTx` 带完整前导。
- **UI**：
  - 底部状态条 `VOX -xx dB ●`（触发点亮），音频速览显示电平/状态/触发方式/阈值/前导/看门狗；
  - 设置页 6.1：VOX 触发/延迟/阈值、发射前导音与时长、测试音（含实时电平条）、PTT 延迟、看门狗全部启用；**输出声卡**当时保留 `U7`（已由 **U7c** 点亮）。
- **诚实边界**：无 CAT 时无法读取电台真实键控状态，`voxOpen` 仅为**输入音频活动**的近似提示，不参与发射门控；前导音结束后到 FT8 波形之间还有 0.5 s 的 WSJT-X 保护静音，需电台 VOX 的释放延时（hang time）覆盖该间隔，否则可能中途掉键。真机 VOX 键控效果需实际电台验证（见 `REGRESSION.md`）。
- **测试**：新增 JVM `ui/VoxPlanTest.kt`（`planTx` 四种周期/前导组合、缩水前导、电平文案）；设备端 `VoxTxNativeTest.kt`（`playTx` 前导路径写入完成、测试音）。JVM **187/187**、设备端 **26/26** 全绿。

### U7c 落地说明：音频路由与增益

- **背景**：设计 §6.1 的「输出声卡」与 §6.2 的「输入设备 / 输入增益」此前只存设置值。U7c 用系统 `AudioManager` 枚举设备，并让 AAudio 按所选设备建流。
- **设备枚举（`engine/AudioDevices.kt`）**：
  - `inputs()/outputs()` 读取 `AudioManager.getDevices(...)`，返回 `{id, 名称, 类型标签}`；首项恒为「系统默认」（id 0）。
  - 设备 id 即 `AudioDeviceInfo.getId()`，与 AAudio `setDeviceId` 同一编号；`parseId/formatId/label` 为纯函数（空串=默认，id 可能随插拔变化）。
  - 枚举在进入设置页时快照一次（`remember`），故 USB 声卡插拔后需重进本页刷新。
- **native（`audio_engine.c`）**：
  - `nativeStartCapture(handle, rate, deviceId)` / `nativeStartPlayback(handle, rate, deviceId)`：`deviceId > 0` 时 `AAudioStreamBuilder_setDeviceId`，否则系统默认。
  - `nativeSetInputGain(handle, gainDb)`：DSP 线程对原始采集块乘 `10^(dB/20)`（钳制 −12..30 dB），超过满幅限幅到 [−1,1] 防回绕；**该增益同时作用于 VOX 电平读数**。
- **Kotlin 接线（`SessionViewModel`）**：
  - `start()` → `startCapture(preferredRate(), inputDeviceId())`；`armPlayback()` → `startPlayback(preferredRate(), outputDeviceId())`。
  - `applyAudio()`：增益热生效；输出设备变化时关闭旧播放流、下次发射用新设备重开（发射中则提示「下次发射生效」）；输入设备变化需重开采集流，运行中提示「下次开始接收生效」。
  - 设置页 6.1 输出声卡、6.2 输入设备的下拉列出实际设备；输入增益步进器点亮；音频速览新增输入/输出设备与输入增益。
- **测试**：新增 JVM `engine/AudioDevicesTest.kt`（id 编解码、显示文本回退）与设备端 `AudioRoutingTest.kt`（枚举含默认首项且 id 唯一、显式设备 id 打开采集流、增益下发后状态有限）。JVM **191/191**、设备端 **30/30** 全绿。模拟器实测：输出下拉列出「系统默认 / 内置扬声器 / 通话」，输入下拉另含「内置麦克风 / 远端混音 / 其他」。

### U7d 落地说明：含我呼号哔声

- **语义**：设计 §6.4 的「含我呼号哔声」= 收到**直接呼叫我方呼号**的报文（`ParsedMessage.addressedTo(myCall)`，即报文首字段为我呼号）时给一声提示；CQ、他台之间的通联不触发。自己发射的报文不会被解码回来，故不会误报。
- **实现**：纯函数 `shouldAlertMyCall(beepOnMyCall, myCall, texts)`（开关关 / 呼号空 → 永假）；`engine/AlertTone.kt` 懒建 `ToneGenerator`（通知流，`TONE_PROP_BEEP` 150 ms）播放，构造或播放失败即静默降级并标记不再重试；`SessionViewModel.pollOnce` 有解码结果时判定并播放，`onCleared` 释放。
- **UI**：设置页 6.4「含我呼号哔声」开关点亮。
- **测试**：JVM `ui/MyCallAlertTest.kt`（开关/空呼号/发给他人/CQ/大小写/批量）7 例。JVM **198/198** 全绿。

> **FST4**：不做。`ft8_lib` 仅含 FT8/FT4 调制解调，FST4 需自研（LDPC/多种符号速率/GFSK），量与风险不匹配，已从模式菜单移除相关占位。**局域网后台 / 在线日志**：不做（分别依赖阶段 9 前台服务与网络凭据），占位设置项与菜单项已删除（见 §收尾：删除三处占位功能）。**离线瓦片**：已实装为单张 z5 Web Mercator 卫星底图（见 §补记：离线卫星底图）；仍**不做**在线瓦片（无网络依赖、无版权风险）。

### U8 落地说明：波段多频率 / 自定义 / 自动发射周期

- **波段多频率（`data/BandPlan.kt`）**：`Band` 新增 `freqs: List<DialFreq>`（每波段收录 FT8 / FT4 及少数 DX/Hound 常用刻度，如 20m = 14.074 FT8 / 14.080 FT4 / 14.090 FT8 DX / 14.095 FT8 Hound）；`dialHz` 仍为首项、作为波段默认。新增 `DialFreq.mhz`（4 位小数，可区分 FT4 的 7.0475）、`Band.containsHz`、`BandPlan.hasFreq` / `resolveDialHz` / `parseFreqMhz` / `MAX_FREQ_HZ`。
- **自定义波段与频率**：`AppSettings` 新增 `dialHz: Long`（0 = 用波段默认）与派生 `resolvedDialHz`；波段名不再限制为内置表（`SettingsRepository` 只做非空、≤16 字符与频率范围钳制），支持如「试验」+ 13.500 MHz。记录通联改用当前 `dialHz`（`SessionViewModel` 写入 `st.dialHz`），自定义波段的 ADIF 导出为自定义 BAND 名 + 精确 FREQ。
- **「波段与频率」弹窗（`ui/AppChrome.kt` → `BandFreqDialog`）**：顶栏菜单第一项打开；列出各波段与全部常用频率（点选即切换并关闭），底部「自定义波段与频率…」切换为「波段名 + MHz」输入（校验正数）。设置页「台站」组的「波段与频率」`PrefAction` 复用同一弹窗。
- **发射总开关（默认关 = 只接收，U9 补记 4 起定稿为「发送总开关」= 纯权限）**：`ReceiverStatus` 新增 `txEnabled`（**仅会话内有效、不持久化**，故每次启动都默认只接收）。开关放在**发射抽屉收起态 56dp 条**上（`Switch`），便于快速开关；**它只回答「能不能发」，打开时不发射任何报文**；关闭时立即停发、解除时隙锁定并解除目标时隙固定，但**不动自动程序等级**（没有权限时自动程序只是待命，开回总开关即按原等级继续，见 U9 补记 6）。总开关关闭时「发送」按钮禁用、防误发弹窗的「确认发射」禁用并给出提示。发什么由手动发送或自动程序决定（联动细节见 U9 补记 4 / 6）。
- **默认呼叫 CQ**：收起态与抽屉的发送按钮在「无自定义文本、无发送队列、无目标」时默认执行 CQ（走防误发确认弹窗）；已选目标且无文本时默认应答该目标。
- **时隙固定自动（不再提供设置）**：移除设置页「默认发射周期」与抽屉「偶/奇/自动」三档，`AppSettings.txParity` 与 DataStore `tx_parity` 键一并删除（`ReceiverStatus.txParityMode` 亦移除）。规则 = 纯函数 `nextSlotParity(now, slotMs, leadMs)`：按手机 UTC 时间取**下一个距起点 ≥ 前导余量（前导 + 500 ms）的时隙**（当前时隙来不及就跳到再下一个，即「再下一个时隙发射」）；在打开总开关、进入新 QSO / 一次性发射时锁定，QSO 期间保持不变，**QSO 结束后也保持不变**（不再释放；只有关闭总开关 / 停止发送 / 停止接收才清除，见 U9 补记 3）。用户通过重新开关总开关来切换发射时隙。**例外 = 应答目标**：解码在时隙结束后才到手，按当前时间推算会得到与被应答时隙相同的奇偶，因此应答（手工 `answer` / 自动 `startAutoTarget`）一律按**目标时隙的相反周期**锁定（见 U9 补记 2）。
- **接线**：`SessionViewModel.setBandFreq` / `setTxEnabled`、`effectiveTxParity` / `relockAutoParityIfNeeded`；`txTick` 以 `txEnabled` 为总闸并保证时隙已锁定；`applySettings` 同步 `band / dialHz / 生效 txParity`。
- **测试**：`BandPlanTest` 增补（每波段 ≥2 频率、首项 = 默认、频率在波段内、`resolveDialHz`、`parseFreqMhz`、`DialFreq.mhz`）；`ui/TxParityAutoTest`（下一时隙奇偶、前导余量跳过、边界、零时隙回退、文案）。JVM 全绿，`:app:assembleDebug` 通过。
- **模拟器实测**：顶栏弹窗列出多频率且切换生效（20m → 80m，3.573 生效）、自定义波段/频率表单可用；收起态条上的发射开关与「发送 CQ」按钮、抽屉「只接收」态符合预期。发射时序 / 自动周期与真实电台的配合需真机验证（见 `REGRESSION.md`）。

### U9 落地说明：自动程序（取代 Call 1st）

- **背景**：按 FT8CN「自动程序」菜单实现整套自动应答 / 自动搜索策略；用户确认采用「自动化递进」语义、**完全并入并取代 Call 1st**、**不做**「接收频率自动窄带过滤」。
- **等级（`qso/AutoProgram.kt` → `AutoLevel`）**：
  - `0 手动选择`：不自动应答（等价原 Call 1st 关）。
  - `1 呼叫首先解码`：取本时隙**最先解码到**的候选（不做排序）。
  - `2 解码至发射间隔期间` / `3 直到解码结束`：在整批解码中**择优**（最强信噪比 / 最远距离）。Ft8Vox 解码按接收时隙整批返回，故 2/3 语义一致，差异仅保留在菜单文案。
  - `4+ 自动搜索及自动回应别人的CQ信息`：在择优基础上，**无可答目标时自动发 CQ**（自动搜索）。
- **策略开关（`AutoProgramSettings`）**：回答曾经通联的电台 / 呼叫曾经通联过的电台（Ft8Vox 无跟踪列表，二者合并为 `allowsWorked = 任一开启`，默认**关**，即跳过已通联台）；优先选择新呼号（默认开，排序时新呼号优先）；报告信息优先（默认关，仅作**排序**：发给我方的定向报文优先于 CQ 排队）；最远距离取代最佳信噪比（默认关，选台准则由 `snr` 改为 `Geo` 大圆距离，需填写我的网格）；单次通联（**默认关 ＝ 连续通联**，完成一次 QSO 后自动接续下一台；开启则完成后把等级切回「0 手动选择」＝关闭自动程序，见 U9 补记 6）。
- **决策（`AutoProgramSelector`）**：候选与本时隙解码共用 `DecodeFilter`（忽略名单 / 筛选 chip / 搜索串一致），同呼号去重；`decide()` 返回 `AnswerCq` / `AnswerDirected` / `CallCq` / `None`。
- **接线**：`SessionViewModel` 以 `ReceiverStatus.autoProgram` 取代 `callFirst` / `callFirstArmed`（DataStore 键 `call_first` → `auto_level` 等）；接收时隙结束且无进行中 QSO 时调用 `runAutoProgram`；`startAutoTarget` / `startAutoSearch` 复用原 Call 1st 的时隙锁定与频率跟随逻辑。**是否运行只看等级**（`level.enabled`，U9 补记 6 起不再有独立的启用状态）。
- **UI**：顶栏菜单新增「自动程序」（打开 `AutoProgramDialog`）；发射抽屉「自动序列」区改为「自动程序」行（等级摘要 + 设置…）；设置页 6.3 用 `AutoProgramPanel` 内嵌同一套等级 + 策略开关。**等级即开关**（0 手动选择 = 关，1+ = 开），与「发送总开关」的关系见 U9 补记 4 / 6。
- **测试**：`qso/AutoProgramTest`（等级、自动 CQ、已通联门控、优先新呼号、最远距离、报告优先、定向报文、失败重试、过滤/去重/自呼号）；原 `DecodeFilterTest` 中的 CallFirst 用例移除。JVM 全绿，`:app:assembleDebug` 通过。

### U9 补记：自动程序以「完成 QSO」为目标（被呼自动应答 / 时隙对应 / 失败续台）

- **目标澄清**：自动程序不只是「挑台起呼」，而是把**整段 QSO 跑完**（状态机 `QsoEngine` 负责 报告 → R报告 → RR73/73）。本次补齐三处衔接：
- **被呼自动应答**：候选不再只含 CQ，还包含**发给我方**的定向报文，按 `AutoTargetKind` 分四类：
  - `CQ` → `QsoEngine.answer`（应答 CQ）；
  - `CALL`（`<my> <their> <grid>`，对方呼叫我 / 应答我之前的 CQ）→ 新增 `QsoEngine.callBack`，直接补发报告进入「等待 R 报告」；
  - `REPORT`（`<my> <their> <report>`）→ `QsoEngine.respondToReport`（回 `R<报告>` 等 RR73）；
  - `ROGER`（`<my> <their> R<report>`）→ 新增 `QsoEngine.respondToRoger`，回 `RR73` 并**即时完成**（走 `applyQsoProgress` 统一写日志）。
  - `RR73`/`73`/`RRR`（`DecodeFilter.is73`）视为通联结束，不作为新 QSO 起点；发给别人的报文不算。
  - 「报告信息优先」由「是否纳入候选」改为「排序优先」。
- **时隙自动对应**：`startAutoTarget` 先按目标时隙固定发射周期到其**相反周期**（`pinToTargetSlot` → `oppositeSlotParity`），再 `relockAutoParityIfNeeded`，保证与目标交替收发。
- **失败续台 / 重试**：QSO 期间每个时隙重发当前报文，超过 `maxRetries`（6）判 `FAILED`；失败时 `applyQsoProgress` 记下对手 `retryCall`，自动程序下一批若仍能解到它就**优先重试**（同一对手最多再试 `AUTO_FAIL_RETRY_MAX = 1` 次，之后换台）；`DONE` / 关闭发射 / 停止 / 等级切回手动时清空。
- **默认值**：`AutoProgramSettings.singleQso` 默认由 `true` 改为 `false`（连续通联），与「自动完成 QSO」用途一致。
- **文案**：`AutoProgramPanel` 顶部说明改为「启用后自动完成整段 QSO：自动应答对方的 CQ，也自动应答发给我方的呼号 / 报告，完成后自动接续下一台；4+ 无可答目标时自动发 CQ」。
- **测试**：`AutoProgramTest` 增补定向报告 / 定向呼叫 / Roger / 73 忽略 / 发给他人的报文忽略 / 报告优先排序 / 失败重试优先与缺省 / 默认连续通联；`QsoEngineTest` 增补 `callBack` 全流程、`respondToRoger` 即时完成（含日志）、`respondToReport` 等 RR73。JVM 全绿，`:app:assembleDebug` 通过。
- **待真机**：被呼自动应答的实际触发与整段 QSO 落点、失败重试次数，需真机 + 对方电台验证（`REGRESSION.md` E 组）。

### U9 补记 2：修复「无法完成 QSO」的三处时序缺陷

现象：无论手工应答还是自动程序，对方都收不到我方应答，QSO 永远推进不下去。定位到三处（互相关联）：

1. **应答周期锁错（致命）**：手工 `answer()` 走 `relockAutoParityIfNeeded` → `lockAutoParity` → `nextSlotParity(now, …)`
   按**当前时间**推算。但解码是在**时隙结束之后**才交给 Kotlin 的（native 在喂满 `slot_samples`
   ≈14.88 s 触发 `ftx_session_decode`，解码本身再耗时数百 ms），此时 `now` 已落在**下一个时隙**，
   `now/slotMs + 1` 再跳一格 → 得到的奇偶**与被应答的那个时隙相同**。结果双方都在同一周期发射、
   又在同一周期收听 → 永远收不到对方。（自动程序路径已在 U9 补记里用 `pinToTargetSlot` 修正，手工路径遗漏。）
   - 修法：新增 `lastHeardSlotUtcMsOf(call)` 回查该台最近一条解码的时隙，`answer()` 改用它调
     `pinToTargetSlot`（= `oppositeSlotParity`）锁到**相反周期**；取不到再退回时间法。
2. **应答白等一个周期**：旧 `planTx` 只要 `preambleMs > 0` 就强制 `slotIdx + 2`，永不用「当前时隙」。
   而解码恰好在**我方时隙开头几百毫秒**到达，于是每条报文都多等一个完整周期，标准 75 s 的 QSO 变成 3 倍时长。
   - 修法：`planTx` 改为「我方周期且『时隙内已过时间 + 前导』≤ `TX_START_WINDOW_MS`(1200 ms) → 就地发射」，
     前导完整保留（数据起点 = 现在 + 前导）；超出窗口才等下一个我方周期。
3. **收尾报文发到错误周期**：`applyQsoProgress` 在 `DONE` 时清空 `autoParity`，导致最后一条 `RR73`/`73`
   被 `nextSlotParity` 重锁到错误周期，对方收不到而误判 `FAILED`。
   - 修法：`DONE` 时**保留**发射周期，等最后一条报文真正发完（`txJustFinished`）再处理；`FAILED` 时立即释放。
     （**U9 补记 3 起**：`DONE` / `FAILED` 都不再释放周期，只在收尾时解除「目标时隙固定」。）

附带：接收侧不再用 `txParity` 推断「这是不是我方发射时隙」（锁定周期可能恰与对方相同，会把对方整批报文丢掉），
改为比对解码自带的 `slotUtcMs` 与 `lastTxSlotIndex`，只丢弃「本批正好落在我方刚发射的那个时隙」的解码。

- **测试**：`TxParityAutoTest` 增补 `planTx` 就地发射 / 过窗口等待 / 对方周期顺延、以及
  「时间法算错奇偶 vs `oppositeSlotParity` 正确」与「应答落在紧邻时隙」的回归用例；
  `VoxPlanTest` 按「数据起点 = 播放起点 + 前导」的真实语义校正。JVM 全绿，`:app:assembleDebug` 通过。
- **待真机**：见 `REGRESSION.md` C 组新增的「双方绝不同周期发射」「标准节奏约 75 s」两项。

### U9 补记 3：QSO 完成后不再「跳时隙」（发射周期跨 QSO 保持）

现象（真机测试发现）：自动程序跑完一段 QSO 后，发射时隙的奇偶会变（偶 ↔ 奇来回跳）。

原因：QSO 收尾会把 `autoParity` / `pinnedTxParity` 一并清空 —— `txTick` 在最后一条（RR73/73）发完时清、`applyQsoProgress` 在 `FAILED` 时清；随后 `relockAutoParityIfNeeded()` 按 `nextSlotParity(now, …)` 用**当前时间**重锁，锁到的奇偶取决于「刚好赶上哪个时隙」，于是每段 QSO 之间随机跳一次。

修法：

- 新增纯函数 `effectiveTxParity(pinned, locked, now, slotMs, leadMs)`：有**固定周期**（设为目标 / 对齐目标时隙）用固定值；否则**已有锁定周期保持不变**；两者都没有（刚开总开关 / 刚停止接收）才按时间取下一个来得及的时隙。
- `relockAutoParityIfNeeded()` 改用它（不再无条件按时间重锁），原 `lockAutoParity()` 删除；`setTxEnabled(true)` 复用同一入口。
- QSO 收尾只做 `pinnedTxParity = null`（解除「目标时隙对应」），`autoParity` **保留** → 下一段 QSO / 自动搜索 CQ 沿用同一周期。
- 周期被清除的时机收敛为三处：关闭发送总开关、停止发送（`stopTransmit`）、停止接收（`stop`）。

语义：与 WSJT-X / FT8CN 一致 —— 一直用同一周期收发；**只有**下一目标在相反周期时才切换（`pinToTargetSlot` → `oppositeSlotParity`）。

- **测试**：`TxParityAutoTest` 增补「时间法算出的是奇，但已锁定偶 → 仍取偶」「固定周期优先于锁定周期」「`slotMs=0` 回退」三例。
- **待真机**：见 `REGRESSION.md` E 组「时隙稳定（不跳时隙）」。

### 收尾：删除三处占位功能（瀑布配色 / 日志上传 / 局域网后台）

用户要求删除三项「已定为不做、仅剩置灰占位」的功能，本次把代码、设置项与入口彻底移除：

- **瀑布配色**：删除 `WaterfallPalette` 枚举、`AppSettings.waterfallPalette` 与 DataStore 键 `waterfall_palette`；设置页 6.5 的「瀑布配色」`PrefChoice`（原置灰 + `U7` 徽标）删除；「恢复布局」只剩瀑布高度 / 字体。瀑布渐变本就固定在 native 生成，删除后显示行为不变。
- **日志上传（CloudLog / LoTW / eQSL）**：删除 `cloudLogEnabled` / `lotwEnabled` / `eqslEnabled` 三个设置项与对应 DataStore 键；设置页 6.6 的三个置灰 `PrefSwitch` 删除。**保留**日志里的 QSL / LoTW **确认状态**（`QsoEntity.qslRcvd` / `lotwRcvd`，ADIF 导入合并使用）——那是「确认结果」，与「上传」无关。
- **局域网后台**：删除 `lanServerEnabled` 设置项与键；设置页 6.6 的置灰开关删除；日志页「⋮」菜单的「局域网后台地址（U7）」占位项与说明弹窗（`lanDialog`）一并删除，「⋮」菜单只剩 新增通联 / 导入 ADIF / 导出 ADIF / 清空日志。
- 设置页 6.6 分组名由「日志与网络」改为**「日志」**（网络项已全部移除）。
- **验证**：清理后 `WaterfallPalette` / `cloudLogEnabled` / `lotwEnabled` / `eqslEnabled` / `lanServerEnabled` / `lanDialog` 在 `app/` 下已无命中（原 DataStore 键残留值不再读取，无害）；JVM 253 例全绿，`:app:assembleDebug` BUILD SUCCESSFUL。
- **取舍**：若日后要做这三项，需从零实现（局域网后台需前台服务；日志上传需网络与凭据安全存储），不再保留占位骨架。

### U9 补记 4：「发送总开关」= 纯权限；怎么 QSO 由自动程序决定

起点是用户的两条要求：先「让自动程序的启动关闭也能控制发送总开关」，随后「有没有更好的方案？把『空闲只接收』的开关改成发送总开关，具体怎么 QSO 由自动程序控制」。

- **模型（定稿）**：把两件事彻底分开 ——
  - **能不能发 = `txEnabled`「发送总开关」**（发射抽屉收起态 56dp 条上唯一的控制）：关 = 只接收，开 = 允许发射。**它自己不发任何报文**；早期「打开开关即按默认动作发射（无目标→CQ / 有目标→应答 / 有文本或队列→发它）」的行为**已移除**。
  - **发什么 = 自动程序**（等级 + 策略 + `QsoEngine` 状态机）：选台、应答、报告 / R 报告 / RR73 的整段流程都由它决定。未启用自动程序时由**手动发送**决定：展开面板的「发送」大按钮、报文类型 / 4×2 宏 / 自定义文本 / 发送队列、以及解码卡片手势（左滑设为目标、详情面板应答）。
- **联动（U9 补记 6 起：等级即开关，无独立启用状态）**：
  - **开启自动程序**＝选一个 1+ 等级：先弹防误发确认（`AutoEnableConfirmDialog`），确认后若总开关为关则自动 `setTxEnabled(true)`（顺带按 UTC 时间锁定发射时隙），并把等级写入设置；并做「呼号非空」校验（与 CQ / 应答 / 一次性发射一致）。
  - **关闭总开关**（`setTxEnabled(false)`）→ 停发 + 解除时隙锁定 / 目标固定；**不动自动程序等级**（没有权限时自动程序只是待命，开回总开关即按原等级继续）。
  - **关闭自动程序**＝把等级切回「0 手动选择」→ **不动总开关**（手动发射仍需要这个权限），只清失败重试记忆。
- **为何改掉 d74aec4 的对称写法**：那一版让「关闭自动程序」也关总开关，于是立刻需要两个例外 —— 切到「手动」等级（可能还有进行中的 QSO）、「单次通联」在 QSO 结束后自动停止（还有最后一条 RR73/73 待发，关开关会清掉 `txArmed`，对方收不到收尾报文）。例外一多说明模型错了：权限与策略本就该分开；改单向联动后例外自然消失，无需任何特判。
- **文案**：抽屉收起态第二行显示「允许发射 / 只接收」；展开面板提示按四种状态（开关关 / 自动程序已启用 / 我方周期可立即发 / 需排下一周期）分别说明；自动程序行标注「等级即开关」并注明确认启用会自动打开总开关；防误发确认框写明「发送总开关：已开（只表示允许发射）」或「关 —— 确认启用时会自动打开」；`AutoProgramPanel` 说明同步；`QsoPanel` 的防误发提示同步改称「发送总开关」。
- **验证**：`:app:assembleDebug` BUILD SUCCESSFUL，JVM 253 例全绿（联动在 ViewModel，无单测覆盖；真机项见 `REGRESSION.md` E / M 组）。本节「启用/关闭」的写法已被 U9 补记 6 取代。

### U9 补记 5：顶栏时隙指示（0/1 号，三行）

用户要求：顶部状态栏里加「发射时隙」和「正在发送的文本内容」；随后追加两条要求 ——「不用奇偶时隙，用 0/1 时隙，例如 `RX：1`」「发射中 CQ DX0ABC / 待发 CQ DX0ABC 写到第三行」。

- **三行布局**（顶栏中间）：
  - 第 1 行：`13:45:55 UTC`
  - 第 2 行：`14.074000 MHz · FT8 · RX：1`（我方发射时隙为 `· TX：0`）
  - 第 3 行：`待发 CQ DX0ABC`（强调蓝）/ `发射中 CQ DX0ABC`（红）—— **只在我方发射时隙、且有待发报文或正在发射时出现**；接收时隙没有这一行。
- **编号（0/1，不用奇/偶）**：`TX：n` 的 n 取当前锁定的发射周期 `status.txParity`；`RX：n` 的 n 取当前时隙 `status.slotParity`（与 `slotParityOf` 的 0/1 定义一致：`:00/:30` 为 0，`:15/:45` 为 1）。
- **显示条件**：`status.running` 才显示时隙号；「我方发射时隙」= `status.txEnabled && slotParity == txParity`。关闭发送总开关或未开始接收时第 2/3 行的时隙段不显示。
- **文本来源**：`manualTxText`（一次性发射）→ `qso.txText`（QSO 待发报文）→ 发射中再回退到 `lastTxText`（刚发出的那条，因为 `QsoEngine.onTransmitted()` 会把 DONE/FAILED 的 `txText` 清空）。
- **排版细节**：三行都设紧凑 `lineHeight`（时钟 20sp、辅助行 13sp，合计约 46dp < 56dp），行容器由 `height(56.dp)` 改为 `heightIn(min = 56.dp)`，因此第 3 行出现/消失时**顶栏高度不跳动**（3 行时时钟上移约 6dp，可接受；系统字体放大时顶栏才增长，不会裁字）。配色：TX 段/待发 = `colorScheme.primary`，发射中 = `VoxTxRed`；RX 段与频率行同色。第 2 行用 `buildAnnotatedString` + `SpanStyle` 混色，`maxLines = 1` + 省略号；颜色须在组合体里先取好（`MaterialTheme` 不能在非组合 lambda 内调用）。
- **实现**：`AppChrome.kt` 的 `Ft8VoxTopBar`；无新增状态字段（全部由 `ReceiverStatus` 现有字段推导，`slotParity` 由轮询每帧刷新）。
- **验证**：`:app:assembleDebug` BUILD SUCCESSFUL、JVM 253 例全过（按用户要求只到构建成功，真机/模拟器验收项见 `REGRESSION.md` M 组）。

### U9 补记 6：自动程序取消独立「启用/关闭」—— 等级即开关

用户要求：「自动程序的开关直接不要了，直接由『0 手动选择』表示关」。

- **模型**：删除 `ReceiverStatus.autoArmed` 字段与 `armAutoProgram()` / `disarmAutoProgram()` 两个方法。
  自动程序是否运行**只由等级决定**：`level == MANUAL`（「0 手动选择」）＝关闭，1+ ＝开启
  （判据统一为 `AutoProgramSettings.level.enabled`）。UI 上的「启用 / 关闭」按钮随之删除，发射抽屉只留
  「自动程序　〈等级摘要〉　［设置…］」，`AutoProgramDialog` / `AutoProgramPanel` 的 `armed` 参数一并移除。
- **防误发确认保留、触发点后移到等级选择**：由「点启用按钮」改为「等级从 0 切到 1+ 时」。新增共用弹窗
  `AutoEnableConfirmDialog`（`ui/AutoProgramDialog.kt`），顶栏自动程序弹窗（`MainShell`）与设置页 6.3
  （`SettingsScreen`）各自在本页渲染一次；确认后调 `setAutoLevel(level)` —— 它写入等级、校验呼号，
  并在总开关为关时自动 `setTxEnabled(true)`（总开关此前未锁定时顺带按 UTC 时间锁定时隙）。
- **关闭总开关不再改等级**：`setTxEnabled(false)` 只停发 / 解除时隙锁定 / 解除目标固定，状态栏提示
  「发送已关闭（只接收）｜自动程序待命（开总开关即继续）」。等级是持久化设置，「关总开关顺便清等级」
  会让配置凭空消失。
- **「停止发射」只停本次**：`stopTransmit()` 不再动等级（要全停请关「发送总开关」或把等级切回 0）；
  因为等级 ≥1 时自动程序下一时隙会继续，状态栏明确写「已停止发射｜自动程序仍开启（要全停请关「发送总开关」）」。
- **「单次通联」＝把等级切回 0**：`applyQsoProgress` 在 QSO 结束且开启单次通联时，把持久化等级写回
  「0 手动选择」（这是「自动程序已停止」在新模型下的唯一表达），状态栏说明「单次通联完成，自动程序已关闭（等级切回「0 手动选择」）」。
- **取舍（已知后果）**：①等级是持久化设置 —— 重启后等级仍在，但「发送总开关」默认关，所以不会自行发射，
  打开开关即按原等级继续（不再有「启用」这一步）；②顶部弹窗与设置页改等级都立即生效（两处都会弹确认）；
  ③在 1+ 等级之间切换不弹确认，直接生效；④要「关掉自动程序」只能把等级切回 0（这也是设计意图）。
- **验证**：`:app:assembleDebug` BUILD SUCCESSFUL、JVM 253 例全过（无新增单测；真机项见 `REGRESSION.md` E / M 组）。

### U9 补记 7：设置页「高亮与提醒」加颜色小圆点 + 固定颜色图例

用户要求：「给每个开关右边加一个颜色小圆点；不受这些开关控制的颜色加一个颜色图例。」
起因是原说明只写「具体颜色见设计说明」，设置页里根本看不到颜色表，无法把开关和颜色对上。

- **开关右侧色点**：`SettingsScreen.kt` 的 `PrefSwitch` 新增可选参数 `dotColor: Color?`，非空时在 `Switch`
  **右侧**渲染 10dp 圆点（`ColorDot`，带一圈 `outline` 60% 淡描边，暗/亮主题都看得清）；`PrefChoice`
  （「已通联」不是开关）也同样加 `dotColor`，色点右对齐到标题行末端，使同组色点排成一列。
- **映射**（取自 `ui/theme/Color.kt:39-47`，与 `DecodeHighlight` 的色条角色一致）：
  新 CQ 区域 / 新 ITU 区域 / 新 DXCC / 新前缀 = 棕 `BarNewEntity`；新网格 = 紫 `BarNewGrid`；
  新呼号 = 粉 `BarNewCall`；已通联 = 红 `BarWorked`；末端标记「红=有我」= `VoxError`、
  「蓝=正通联」= `colorScheme.primary`；「含我呼号哔声」无颜色，因此**不加**圆点。
- **固定颜色图例**：组尾新增 `HighlightLegend()`（前面一条 `PrefDivider`），列出**不受这些开关控制**的颜色：
  黄 `BarTx`＝正在发射（整行黄底黑字）、蓝 `BarToMe`＝与我有关 / 当前 QSO 对手（呼号同时红字）、
  橙 `BarCq`＝CQ、灰 `BarDuplicate`＝重复解码（整行变淡）、绿 `BarNewDecode`＝其余新解码（兜底）。
- **顶部说明改写**：原「关闭某类后…具体颜色见设计说明」改为给出完整优先级链
  （正在发射 > 与我有关/当前对手 > CQ > 已通联 > 重复 > 新网格 > 新 DXCC/ITU/CQ 区域/新前缀 > 新呼号 > 其余新解码），
  并说明「关闭某类即不再参与竞争，行尾圆点也随之消失；开关右侧圆点 = 该类对应的色条颜色」。
- **验证**：`:app:assembleDebug` BUILD SUCCESSFUL（无新增单测，纯 UI）；模拟器 `emulator-5554` 实拍暗/亮主题各一张，
  色点与图例渲染正常、无布局跳动（真机项见 `REGRESSION.md` A 组与 §4）。

### U9 补记 8：解码列表自动翻到最新 + 筛选栏最左「清除解码信息」

用户要求：「让操作页解析的信息自动翻到最新」「加一个清除解析信息的（按钮）到筛选栏最左侧」。

- **为什么原来不会自动到最新**：列表是**新→旧**（`_messages` 前插，`SessionViewModel:881`，最多 200 条），
  最新在 `index 0`。但 `LazyColumn` 默认按 item key **锚定视口** —— 前端插入新条目时，为保持「正在看的
  那条」不动，视口会停在旧消息上（新消息在视口上方），所以用户每批解码后都得手动上滑一次。
- **自动翻到最新**（`OperateScreen.kt`）：给 `LazyColumn` 接 `rememberLazyListState()`，用
  `LaunchedEffect(最新行 key) → animateScrollToItem(0)` 在每批新解码到达时回顶。key 取
  「报文文本 + 时隙 UTC」，`rows` 因高亮/筛选重算但最新行没变时不会重复滚动。
- **不打断翻历史**：收集 `listState.interactionSource` 的 `DragInteraction.Start` —— 用户一旦手动拖动就
  `followNewest = false`（暂停跟随）；`snapshotFlow { firstVisibleItemIndex }` 在滑回 `index 0` 时恢复跟随。
  注意**只在 `index == 0` 时置 `true`**：锚定导致的 index 漂移（0→1）不能误判成「用户翻走了」。
  另外用户手指正在拖动（`isScrollInProgress`）时本轮不抢滚动，避免和手势打架。
- **清除解码信息**：`SessionViewModel.clearMessages()`（原有方法，此前无 UI 入口）接到筛选栏
  **最左侧固定的 `IconButton`**（`Icons.Filled.Delete`，core 图标集，项目只依赖 `material-icons-core`）。
  按钮不放进横向滚动的 Chip 行里，所以 Chip 横滚时它始终可见；`messages.isEmpty()` 时 `enabled = false`
  并淡化到 38% 透明度。**不做二次确认**：解码列表是本会话的临时显示数据（与「右滑删除单条」一致），
  不是日志；组内不做确认也避免误触成本。
- **清除的边界（重要）**：只清 `_messages`（显示列表）。`status.decodedTotal`（状态栏「已解码时隙 / 解码
  n/min」）是接收健康度计数，保留；自动程序用的是自己每时隙的 `pendingDecodes` 批（`runAutoProgram(batch)`），
  不受影响；只有 `lastHeardSlotUtcMsOf()`（按解码列表回查目标台时隙）会查不到而回退 0，与「列表里没有该台」同路径。
  清空后空态回到「等待解码…」而不是「没有符合筛选条件」，因为空态提示取的是 `messages.size`。
- **验证**：`:app:assembleDebug` BUILD SUCCESSFUL；模拟器实拍筛选条布局（按钮在最左、空列表时置灰）。
  自动翻页需真机/模拟器有解码输入才能观察（模拟器无音频输入），列入 `REGRESSION.md` B 组。


### U7 补记：时隙偏移（整个时隙，发射 + 解码窗口）

用户要求：「实现 U7 发射偏移 —— 应该是让**整个时隙**偏移（手动调整，如果操作页解析的信息
时间差为 `+1.5` 则在时间偏移里填 `+1.5` 即可校准，这个使用方法写在对应的设置位置）」。

- **口径**：解码卡片的「时间差 DT」= native `out->dt = time_sec - 0.5f`，即对端信号在本机
  采集窗口内的位置减去名义 0.5s。本机的**时钟偏差 + 声卡时延**会同步体现在两端：收到的信号
  在窗口里偏晚（DT 正），而我方发射在对端看来也偏了同样的量。因此把 DT **原样填进偏移** =
  把整个时隙网格平移 —— 解码窗口起点随之移动（DT 读数同步归零），发射起点也一起移动，两端
  一次校准。正值 = 推后，负值 = 提前（`-1.5s` 就填 `-1500 ms`）。
- **范围为什么是 ±2500 ms**：`ftx_find_candidates` 的 `time_offset ∈ [-10, +19]` 个符号块，
  FT8 符号周期 0.16s → 相对窗口起点约 `[-1.6, +3.04]s`，减去名义 0.5s 后 DT 可测范围约
  `[-2.1, +2.5]s`。FT4 符号周期仅 0.048s → 搜索窗只有约 ±0.5s，所以设置项副标题注明
  「FT4 偏移过大会解不出」。
- **设置项**：`AppSettings.slotOffsetMs`（DataStore `slot_offset_ms`，与 `SLOT_OFFSET_LIMIT_MS`
  一起钳制），**取代**原 `txOffsetMs`（0–15000，`U7` 置灰占位）；`PrefStepper` 新增 `signed`
  参数显示 `+1500 ms` / `-1500 ms`。用法写在设置项**副标题**里（用户要求）：看操作页解码卡片的
  「时间差」原样填进来，校到约 0 即可。旧安装包残留的 `tx_offset_ms` 键不再被读取（无害）。
- **native**（`audio_engine.c`）：`audio_engine_t.slot_offset_ms`（`_Atomic int64_t`）+
  `nativeSetSlotOffsetMs(handle, ms)`（钳制 ±2500，热生效）；`feed_slot()` 用
  `utc_now_ms() - slot_offset_ms` 判定时隙边界 → **采集窗口起点整体平移**。上报的
  `slot_start_ms` 仍是名义 UTC 时隙起点（时隙序号不变），所以 0/1 号显示、「双方相反周期」判定
  与自动程序解耦于本偏移。改设置在时隙中途时，当前时隙会错位到下一个网格点才稳定（属预期）。
- **Kotlin**：`AudioEngine.setSlotOffsetMs`（`initialize` 前 no-op）；`SessionViewModel.applySlotOffset`
  （沿用 `applyVox`/`applyAudio` 的 `force` 模式，`start()` 里对新引擎强制重下发）；
  `planTx(..., slotOffsetMs)` 在「偏移后的时间轴」上判断、再把起点加回 offset —— 于是目标时隙
  **序号不变**（`lastTxSlotIndex` 去重、`effectiveTxParity`/`nextSlotParity`/解码时隙奇偶全部
  保持名义 UTC 口径），只有窗口/起点平移。
- **验证**：JVM `TxParityAutoTest` 新增 2 例（目标时隙不变且起点整体平移 1500ms；「就地发射」
  窗口随偏移移动、负偏移则等下一个同周期时隙），**255 例全过**；`:app:assembleDebug`
  BUILD SUCCESSFUL；模拟器确认设置项可步进（`+0 ms` → `+300 ms` → `-200 ms`，带符号显示）、
  持久化（DataStore 写出 `slot_offset_ms`）、开启接收后无 `UnsatisfiedLinkError`（`llvm-nm`
  确认 `Java_..._nativeSetSlotOffsetMs` 已导出）。**校准效果需真机 + 对方电台**，见
  `REGRESSION.md` A 组「时隙偏移校准」。


### 补记：6.3 解码深度各参数的作用说明（设置页）

用户要求「在设置页说明一下解码深度各个参数的作用」。

- **做法**：不改布局原语，直接用 `PrefStepper` 已有的 `subtitle` 给 8 个参数行各加一行作用说明
  （和「时隙偏移」的用法说明同一模式，说明就贴在对应设置项下方）：
  * **时间 OSR**：每符号时间细分数（1–4）→ DT 分辨率/弱信号同步，代价是计算量成倍上升；
  * **频率 OSR**：每 6.25 Hz 频率格细分数（1–4）→ DF 精度/邻近信号分辨；
  * **最低得分**：Costas 同步门限（4–40）→ 调高更快但易漏弱台；
  * **LDPC 迭代**：纠错迭代上限（5–60）→ 调高弱信号解码率上升、更慢；
  * **候选上限**：单时隙同步候选数（20–500）→ 调高给弱台更多名额、更慢；
  * **单时隙上限**：单时隙最多输出条数（5–100）→ 拥挤波段调大避免已解出的报文被丢弃；
  * **频率上/下限**：解码搜索与瀑布显示范围（默认 200–3000 Hz）→ 越窄越快，超出不解；
  * 需重建引擎的三项（时间/频率 OSR、频率范围）在说明里注明「需重开接收生效」。
- **「解码深度」预设行的副标题**改写为说明：预设一键改下面 6 项（时间/频率 OSR、最低得分、
  LDPC 迭代、候选上限、单时隙上限），**频率范围不随预设变化**，单项手改后变「自定义」
  （与 `SettingsViewModel.updateDecode` 的行为一致）。
- **注意**：Compose 的 `Text` 不解析 Markdown，说明文字里不用 `**加粗**`（会显示成星号）。
- **验证**：`:app:assembleDebug` BUILD SUCCESSFUL（纯文案改动，逻辑与单测未动）。


### 补记：地图页 CQ 不画信号连线

用户要求「地图页修改：cq 不要有连线」。

- **原因**：`MapModel.signalLinks` 原先对 CQ 报文（无收方）走的是「连到**我**」的分支，
  一旦收到多条 CQ，蓝色连线会全部汇聚到本机位置，既挡住地图又传达不了信息。
  与 `new_ui.md` §4.5「在**各方之间**拉直线」的口径也不一致。
- **改法**：`signalLinks` 里 `to = parsed.to ?: continue`，即**没有收方的报文直接跳过**；
  CQ 的位置信息仍由 §4.4 的**红旗**表达，不受影响。「连线」计数随之只统计双方报文。
- **保留**：`myGrid` 参数仍有意义 —— 收方是「我」时用它定位本机（避免落到呼号前缀归属地）。
- **测试**：原 `signalLinksCqConnectsToMe`（断言 CQ 连到我）替换为 `signalLinksSkipCqMessages`
  （CQ → 0 条），原 `signalLinksCqSkippedWithoutMyGrid` 替换为 `signalLinksSkipsCqWithinSameSlot`
  （同一时隙 CQ + 双方报文 → 只画后者）；255 例全过。
- **验证**：`:app:assembleDebug` BUILD SUCCESSFUL。


### 补记：设置页「输出音量」（发射音频数字衰减）

用户要求「设置页：『输出声卡』下加一个输出音频的音量（增益？）设置」。

- **关键约束 —— 只能衰减**：发射波形由 `jni_bridge.c` 的 `synth_gfsk()` 合成，输出是
  `sinf(phi)`，**峰值 1.0 ≈ 0 dBFS**，本身已是数字满幅。所以正增益只会削顶、破坏 FT8 频谱
  （对端直接解不出），设置范围定为 **−30…0 dB**、默认 0（= 原样输出，行为与之前一致）。
  要更大声只能调电台/声卡的音量 —— 这一点写进了设置项副标题。
- **生效范围**：`write_blocking()` 是**所有**发射音频的唯一出口（`play_pcm` 的报文/前导音 +
  `nativePlayTone` 的测试音），故在写入前统一 `apply_output_gain()` 原地乘 `10^(dB/20)`
  并限幅到 [−1,1]（≤0 dB 不会触发，仅兜底）。这样「测试音」也能用来试音量 —— 正是它本来的用途。
- **热生效**：`_Atomic int out_gain_db`，改设置后**下一次播放**即生效（不打断本次发射）。
- **改动清单**：
  * `AppSettings.kt`：`outputGainDb`（默认 0）+ 顶层 `OUTPUT_GAIN_MIN_DB/MAX_DB` 与 `clampOutputGainDb()`（一处钳制，仓库与引擎共用）；
  * `SettingsRepository.kt`：key `output_gain_db`（读时钳制）；
  * `AudioEngine.kt`：`setOutputGain()` + `nativeSetOutputGain`；
  * `audio_engine.c`：`out_gain_db` 字段、`apply_output_gain()`、`nativeSetOutputGain`（钳 −30..0）；
  * `SessionViewModel.applyAudio()`：`lastOutputGainDb` 去重下发（`start()` 用 force 重下发）；
  * `SettingsScreen.kt`：6.1「输出声卡」正下方新增「输出音量」步进器；`AppChrome.kt` 音频速览加一行「输出音量 X dB」。
- **测试**：新增 `OutputGainTest`（范围常量、钳制、正增益一律回 0）→ **258 例 / 29 suite 全过**；
  `llvm-nm` 确认 `Java_..._nativeSetOutputGain` 已导出。
- **验证**：`:app:assembleDebug` BUILD SUCCESSFUL。真机需确认：−12 dB 时「测试音」明显变小、
  发射报文对方可解（`REGRESSION.md` A 组新增该项）。


### 补记：模式切换（FT8 ⇄ FT4）在运行中也能生效

用户反馈「操作页左上角的设置里，模式只能 FT8」。

- **根因**：`selectProtocol()` 开头是 `if (running) return`，而 U8 起「进操作页且已授权即自动
  `start()`」，所以 App 几乎总是处于接收中 → 在顶栏菜单里点 FT4 被静默忽略，看起来就像
  「模式只能是 FT8」。设置页 6.3「模式」同因（`applySettings` 里 `protocol = if (cur.running) cur.protocol else s.protocol`）。
- **为什么不能热切换**：协议决定 native monitor 的**时隙长度（15 s / 7.5 s）与调制方式**，
  在建引擎（`AudioEngine.initialize(Ft8Config(protocol=…))`）时就固定了，只能重建。
- **改法（单一规则）**：`selectProtocol()` 退化为「只持久化」；由 `applySettings()` 统一判断
  —— 若 `running && s.protocol != status.protocol`，调用新增的 `restartForProtocol()`：
  `stop()` → 更新 `protocol`/`slotMs` → `start()` → 按切换前状态恢复「发送总开关」。
  这样顶栏菜单与设置页**两个入口**同一条路径，且顺带修掉了「冷启动首帧设置未到就 start()」
  导致的协议不一致。
- **副作用（可接受且已写在 UI 提示里）**：接收短暂中断；`stop()` 会结束当前 QSO（换协议本就
  不该继续）；总开关状态保留（换协议不收回发射授权）；周期锁定按新协议重新对齐。
- **UI 提示**：顶栏菜单「模式」标题改为「模式（切换会重建引擎，接收短暂中断）」；设置页 6.3
  「模式」副标题说明「FT8 时隙 15 s、FT4 时隙 7.5 s，运行中切换自动重建引擎（总开关保留）」。
- **测试**：FT4 时隙长度（7500 ms）与奇偶规则已由 `TxParityAutoTest` / `VoxPlanTest` 覆盖；
  本次改动是 ViewModel 生命周期行为（依赖 JNI 引擎），故补在 `REGRESSION.md` 的**模拟器**可验证项
  （协议切换会重建引擎，模拟器能直接看到顶栏变 `· FT4` 与状态提示）。
- **验证**：`:app:assembleDebug` BUILD SUCCESSFUL；JVM 258 例 / 29 suite 全过。

### 补记：修复「运行中切模式直接崩溃」（native use-after-free）

用户反馈上一节做完后「切换直接崩溃」。

- **取证**：真机 crash buffer 显示 8 次 tombstone（今天 20:32–23:37，含旧包 uid 10347 与新包
  uid 10352，故**不是本次改动引入**），backtrace 一致：
  `SIGSEGV in libaudioclient AudioTrack::write` ← `libaaudio AudioStreamTrack::write` ←
  `libft8.so nativePlayTx` ← `AudioEngine.playTx` ← `SessionViewModel.transmit` ← `txTick`。
- **根因**：`playTx()` 是阻塞 JNI，长时间卡在 `AAudioStream_write`；此时另一线程（`restartForProtocol`
  的 `stop()`、点「停止发射」、Activity 销毁 `onCleared`）执行 `AAudioStream_close` / `free(引擎)`，
  写入线程便踩到已释放的 AudioTrack 共享缓冲。`txJob.cancel()` 打断不了已开始的阻塞写。
- **改法（native，`audio_engine.c`）**：新增 `tx_mutex` / `tx_cond` / `tx_active` / `out_gen`。
  `write_blocking` 只在持锁时读 `out_stream`，每次写前校验 `out_gen`（单次写超时改 500 ms）；
  `tx_stop_and_close()` 关流前先 `out_gen+1`；`nativeStartPlayback` 开流也 `out_gen+1` 并在锁内挂流；
  `nativePlayTx`/`nativePlayTone` 由 `tx_enter`/`tx_leave` 包裹；`nativeDestroy` 先自保
  `nativeStopCapture` + 关流，再 `tx_wait_idle(3000)`，**超时则泄漏引擎不 free**（宁漏不崩）。
- **改法（Kotlin）**：`AudioEngine.handle` 加 `@Volatile`，`release()` 先清零句柄再销毁；
  `SessionViewModel` 新增 `engineGen`（`start()` 时 +1）、`stopPollingAndJoin()`（`stop()`/`onCleared()`
  `cancel()` 后 `runBlocking { join() }`，因轮询也走 JNI 但不在 native 播放保护内）、
  `transmit(..., genAtPlan)` / `transmitTest()` 的**代次守卫**（跨引擎迟到发射直接丢弃，
  不置 `txing` 避免误推进 QSO 状态机）；`Ft8Engine.encode` 移出原 try 单独 catch。
- **UI 文案**：`restartForProtocol()` 的提示区分是否中止了本次发射
  （「已切换到 FTx（重建引擎，已中止本次发射）」）。
- **文档**：新增 `JNI-CONTRACT.md` §6.4（播放/关流的线程安全约定）；`REGRESSION.md` 新增
  **N 组**（发射中切模式 / 停止发射 / 离开 App 不崩）与模拟器可预演项。
- **验证**：`:app:assembleDebug` BUILD SUCCESSFUL（三 ABI），JVM 258 例 / 29 suite 全过；
  真机项见 `REGRESSION.md` N 组。

### 补记：地图页离线卫星底图（Web Mercator + BitmapRegionDecoder）

用户提供一张 **z5 世界卫星图**（高德「影像无标注」，中心经纬度 `(0,0)`），要求**离线打包栅格底图**。

- **素材实测**：`app/map/tile-merge/z=5.png` = 8192×8192 **PNG，45.6 MB**。8192² × 4B = **268 MB**
  无法整图解码（必 OOM）；PNG 无损存卫星照片严重浪费体积。**重编码 JPEG q90 仅 4.75 MB**，肉眼无差。
  已核对地理配准：中心 `(4096,4096)` = 几内亚湾海面、伦敦点 = 不列颠陆地、上下缘 = 北极洋/南极洲
  → 标准 **Web Mercator 全世界图**（顶左 `85.05112878°N, 180°W`）。
- **决策（用户拍板）**：①单文件 + `BitmapRegionDecoder`（不切片）；②底图暗化 **×0.7**；③**仅测试自用**，
  将来公开版换公有领域影像；④JPEG **q90**。
- **投影改为 Web Mercator**（`grid/MapProjection.kt`）：世界改为 **1×1 归一化 Mercator 方形**
  （`u=mercX(lon)`、`v=mercY(lat)`，纬度钳 `±MAX_LAT=85.05112878`），`scale` = 每世界单位的屏幕像素；
  `fit/fill/zoomBy/panBy/clamped/toGeo` 全部按新投影重写并补 `toScreenUV/toUV/mercX/mercY/latOfV/lonOfU`。
  `maxScale = max(WORLD_IMAGE_PX*2, fitScale*6)`（允许底图 1:1 再放大 2×）。**标记绘制代码零改动**
  （仍只调 `toScreen`）。
- **底图渲染**（新增 `ui/WorldBaseMap.kt`）：首次把 asset 复制到 `cacheDir`（APK 内资产不可随机定位）
  再开 `BitmapRegionDecoder`；`levelFor(scale)` 用 `inSampleSize = 2^level`（0..5）得到 z0–z5 天然多级；
  `visibleBlocks()` 反算可见的 512 px 块，`WorldBaseMapState` 在 `Dispatchers.Default` 解码并按 **LRU 16 块**
  缓存（内存有界，约 16 MB）；块画到画布时**向外取整**避免相邻块 1px 缝。资产缺失/失败**回退原来的纯色世界矩形**。
- **暗化**：`drawImage` 施加 `ColorFilter.colorMatrix(ColorMatrix().setToScale(0.7,0.7,0.7,1))`。
- **踩坑（重要）**：`DisposableEffect(baseMap) { onDispose { baseMap?.close() } }` 里若 `baseMap` 是
  `produceState` 的 **`by` 委托**，`onDispose` 读的是**当前值**而非 effect 创建时的值；key 从
  `null → state` 变化时旧 effect 的 `onDispose` 会把**刚建好的解码器提前 recycle**，导致
  `decodeRegion called on recycled region decoder`、底图全黑。改为 `.value` + 局部 `val` 捕获后正常。
- **体积**：APK 由约 13.5 MB → **18.2 MB**（asset 4,977,579 B，PNG/JPEG 默认未压缩存储）。
- **测试**：`:app:assembleDebug` BUILD SUCCESSFUL；JVM **262 例 / 29 suite** 全过
  （`MapProjectionTest` 重写为 Mercator 断言，+4 项：极区钳制、墨卡托往返、与等距圆柱的差异、最大缩放覆盖底图）。
- **模拟器验证**：地图页正确显示卫星底图（非洲/欧洲居中、`MO89` 台站标记与地理位置对齐），无解码告警。
- **版权与仓库**：高德影像仅本地自用；原始素材目录 `app/map/` 已加入 `.gitignore`（不进仓库），
  进包的是重编码后的 `app/src/main/assets/map/world_z5.jpg`。公开版应替换为公有领域影像（如 NASA
  Blue Marble / 夜间灯光，天然深色更配主题）。


