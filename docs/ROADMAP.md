# Ft8Vox 工程路线图（ROADMAP）

> 文档版本：v1.0
> 更新日期：2026-09-24
> 文档状态：草案（待评审）
> 参考项目：FT8CN（N0BOY / BG7YOZ，MIT）

---

## 1. 项目概览

### 1.1 目标

在 Android 手机上原生实现 **FT8 / FT4** 数字通信：

- 实时采集电台音频，解码出报文；
- 瀑布图 / 频谱可视化，解码报文列表；
- 手动发射（音频输出 + 电台 VOX/手动 PTT）；
- 通联日志（ADIF 导入导出）与基础配置持久化。

### 1.2 定位与约束

| 项 | 结论 |
| --- | --- |
| 项目定位 | **开源发布**（需许可证合规、文档、多语言、CI） |
| 开源许可证 | **GPL-3.0**（内嵌 ft8_lib 为 MIT、kissfft 为 BSD-3-Clause，均与 GPL-3.0 兼容） |
| 协议范围 | **FT8 + FT4** |
| 音频 API | **AAudio**（minSdk 26） |
| 电台控制 | **不做 CAT**；发射依赖电台 VOX 或手动 PTT |
| DSP | **复用 ft8_lib 的 `monitor.c` + kissfft**，在 native 完成 |
| 音频接入 | **声学耦合** + **USB 声卡 / OTG** 两种 |
| 瀑布渲染 | **Compose Canvas 自绘** |
| 日志 | **MVP 即含 ADIF 日志** |
| 调试环境 | **以模拟器为主**，真机做音频/硬件验证 |
| UI 框架 | Jetpack Compose（沿用现有工程） |

### 1.3 当前仓库起点（作为基线）

- Android 单模块工程 `:app`，Kotlin + Compose，界面仍是默认模板（`MainActivity.kt` 的 “Hello Android!”）。
- `ft8_lib`（YL3JG）已 vendored 在 `app/src/main/cpp/ft8_lib/`，但 **CMake 只编译了 `ft8/ft8/*.c`**。
- `jni_bridge.c` 只有一个占位函数 `Java_com_example_ft8vox_Ft8Engine_test`，其对应的 Kotlin 类 `Ft8Engine` **尚不存在**。
- 工程**尚无任何音频权限与录音代码**。
- 工具链偏新：AGP 9.3.2、Kotlin 2.2.10、Gradle 9.5.0、compileSdk 37、JVM toolchain 25。

---

## 2. 已确认技术决策一览

| 编号 | 决策项 | 结论 | 主要影响 |
| --- | --- | --- | --- |
| D1 | 音频 I/O | AAudio（采集 + 播放） | 需 SPSC 环形缓冲、实时线程纪律、采样率适配 |
| D2 | 电台控制 | 不做 CAT | 无频率/模式/PTT 自动控制；PTT 交给 VOX/手动 |
| D3 | DSP 位置 | native 复用 `monitor.c` + kissfft | 必须扩展 CMake；Kotlin 侧不做 FFT |
| D4 | 协议 | FT8 + FT4 | monitor 配置需支持协议切换，UI 需协议选择 |
| D5 | 瀑布渲染 | Compose Canvas | 先跑通；性能不足再评估 SurfaceView/OpenGL |
| D6 | 工具链 | 不降级，固定版本 | 保留 AGP 9.3.2 / Gradle 9.5.0 / Kotlin 2.2.10 / JDK 25；只做版本锁定与构建可复现验证 |
| D7 | 日志 | MVP 含 ADIF | 引入 Room 与 ADIF 读写 |
| D8 | 调试 | 模拟器为主 | 必须补 `x86_64` ABI，并提供“文件注入 / WAV 导出”调试通道 |
| D9 | 接入方式 | 声学耦合 + USB 声卡 | 音频路由需可切换；USB 相关只能真机验证 |
| D10 | 定位 | 开源发布，GPL-3.0 | 许可证、NOTICE、README、多语言、CI 与发布流程 |

---

## 3. 目标架构

### 3.1 分层

```
┌───────────────────────────────────────────────┐
│ UI 层（Compose）                               │
│ 瀑布/频谱 · 解码列表 · 收发面板 · 日志 · 设置   │
├───────────────────────────────────────────────┤
│ 会话层（ViewModel + 状态机）                   │
│ 时隙调度 · 收发切换 · QSO 流程                  │
├───────────────────────────────────────────────┤
│ 引擎封装层（Kotlin Ft8Engine）                 │
│ 加载 libft8.so · 喂 PCM · 取解码/发射数据       │
├───────────────────────────────────────────────┤
│ 原生层（jni_bridge.c + ft8_lib）               │
│ monitor/kissfft · decode · encode · message    │
├───────────────────────────────────────────────┤
│ 音频层（AAudio）                                │
│ 采集 · 播放 · 路由 · 重采样 · 环形缓冲           │
├───────────────────────────────────────────────┤
│ 数据层（Room / DataStore / ADIF）               │
└───────────────────────────────────────────────┘
```

### 3.2 建议目录结构（Kotlin 侧）

```
com/example/ft8vox/
├── MainActivity.kt            # 入口（后续承接导航）
├── engine/                    # Ft8Engine、JNI 数据类、协议枚举
├── audio/                     # AAudio 采集/播放、环形缓冲、重采样、路由
├── session/                   # 时隙调度、QSO 状态机、会话 ViewModel
├── radio/                     # （预留，本期无 CAT）
├── data/                      # Room 实体/DAO、DataStore、ADIF 读写、呼号归属
└── ui/                        # 主题、瀑布、列表、面板、日志、设置
```

### 3.3 线程模型（核心纪律）

- **AAudio 回调线程**：只做“写入环形缓冲”，绝不做 JNI 重活、加锁、分配内存。
- **DSP/解码线程**：从环形缓冲取 15 秒窗口，调用 native 解码（CPU 密集，禁止在主线程）。
- **发射线程**：按时隙读取 native 生成的发射 PCM，推送到 AAudio 播放流。
- **UI 主线程**：只渲染与交互，通过不可变状态对象接收结果。
- **数据同步**：音频线程与解码线程之间用**无锁 SPSC 环形缓冲**；解码结果用并发队列/Flow 抛给 UI。

---

## 4. 关键接口契约（JNI / native）

> 原则：**先把接口签名与数据格式定死，再做上层**，避免音频/UI 返工。

### 4.1 native 导出职责（描述性，非最终代码）

- **引擎生命周期**：初始化（配置协议、采样率、频率范围、过采样率）、重置、释放。
- **解码输入**：接收一块 PCM（float，单声道），内部调用 `monitor_process` 累积 waterfall。
- **解码触发**：一个时隙结束时调用，返回本时隙候选与成功解码列表。
- **解码结果字段**：文本、SNR、DT（时间偏移）、DF（频率偏移）、score、协议。
- **编码输入**：文本 + 目标音频频率 → 返回发射 PCM（12 kHz）。
- **调试接口**：`test()` 保留，用于验证 so 加载；后续可加版本号查询。

### 4.2 数据传递约定

- 大数组（PCM）**用 Direct `ByteBuffer` 或 native 分配**，避免每次 JNI 拷贝。
- **PCM 统一为 12 kHz 单声道 float**。若设备采集为 48 kHz，在 **native 内重采样到 12 kHz** 后再进 monitor（见 6.1）。
- 呼号哈希接口（`ftx_callsign_hash_interface_t`）**由 native 侧管理**，避免频繁 JNI 回调。
- 所有 native 对象**明确归属线程**；JNI 回调若跨线程需 `AttachCurrentThread`。

### 4.3 CMake 扩展（硬前提）

在 `app/src/main/cpp/CMakeLists.txt` 中：

- 新增源文件：`ft8_lib/common/monitor.c`、`ft8_lib/common/wave.c`（调试用 WAV 读写）、`ft8_lib/fft/*.c`（kissfft）。
- 新增 include：`ft8_lib/common`、`ft8_lib/fft`（注意 `monitor.h` 用 `<common/...>`、`<fft/...>`、`<ft8/...>` 形式引用）。
- **排除 `ft8_lib/common/audio.c`**：它 `#include <portaudio.h>`，依赖桌面音频库，Android 无法编译。
- 保持 `ABI` 增加 `x86_64`（模拟器）。

---

## 5. 分阶段路线图

> 说明：MVP（接收+发射）大致覆盖 **阶段 0 ~ 阶段 8**；阶段 9、10 为发布后增强。
> 每个阶段都以“可运行 + 可验证”为完成标准，不做“全写完再联调”。

### 阶段 0：工程基线与工具链稳定化

**目标**：在稳定的工具链上，一条命令能构建、跑测试、出 APK。

任务：
- 工具链固定（不降级）：保留现有组合（AGP 9.3.2 / Gradle 9.5.0 / Kotlin 2.2.10 / JDK 25），不降级；在 `docs/BUILD.md` 中记录各组件版本。
- 构建可复现：校验 `gradle-wrapper.properties` 的 `distributionSha256Sum`，CI 使用固定 JDK。
- `ndk { abiFilters }` 增加 `x86_64`（模拟器）。
  - AGP 9 新 DSL 注意：`ndk { abiFilters }` 必须放在 `defaultConfig` 内；顶层的 `android { ndk { ... } }` 会报 `Unresolved reference 'ndk'`。
- 清理：删除 `jni_bridge.c.bak`；修正 `jni_bridge.c` 乱码注释；保留 `.gitignore`。
- 建立 CI：编译 + Kotlin 单测 + native 编译（Windows/Ubuntu 至少其一）。
- 仓库治理：分支策略、提交规范、`README`、`CONTRIBUTING`、`NOTICE`（ft8_lib 归属与许可证）。

验收：`gradlew assembleDebug` 与 `gradlew test` 成功；CI 绿灯；模拟器可安装运行。

### 阶段 1：打通 JNI 最小闭环 + CMake 扩展

**目标**：Kotlin 能调用 native 并拿到返回；native 库完整编入。

任务：
- 新建 `Ft8Engine`：`System.loadLibrary("ft8")`，声明 `external fun test()`。
- `MainActivity` 调用并显示返回值，验证加载链。
- 按 4.3 扩展 CMake，纳入 monitor / kissfft / wave。
- 定稿 4.1/4.2 的接口契约（写成文档或注释）。

验收：App 显示引擎就绪信息；`libft8.so` 内含 monitor/kissfft 符号；模拟器 x86_64 可加载。

### 阶段 2：离线解码引擎（FT8 + FT4）

**目标**：给定 WAV，稳定解出正确报文列表。

任务：
- 实现“PCM 窗口 → 解码结果列表”的 native 接口。
- 支持协议切换（FT8 / FT4）、频率范围与过采样配置。
- 呼号哈希表在 native 侧实现（含去重与老化，参考 `demo/decode_ft8.c`）。
- 用 `ft8_lib/test/wav/` 与配套 `.txt` 建立回归测试，断言解出的文本。

验收：对样本 WAV 的回归测试通过；FT8 与 FT4 各至少一组样本。

### 阶段 3：离线编码引擎（发射波形）

**目标**：输入文本得到“可被自己解码”的发射 PCM。

任务：
- native 接口：文本 → 77-bit 载荷 → tone → GFSK 波形（12 kHz）。
- 覆盖常用报文：CQ、呼叫、R/RR73/73、自由文本；FT8 与 FT4。
- 生成 WAV，用阶段 2 的解码器自回环验证。

验收：自产波形自解码成功率达到预期；支持标准 FT8/FT4 目标频率。

### 阶段 4：AAudio 实时音频 I/O + 时隙调度

**目标**：实时采集 → 解码连续运行；可按时隙发射音频。

任务：
- 权限与路由：`RECORD_AUDIO` 运行时申请；输出/输入设备选择（内置、USB、蓝牙）；音频模式（`MODE_IN_COMMUNICATION`）与音量/静音策略。
- 采集：优先请求 48 kHz（设备原生），**在 native 重采样到 12 kHz**；若设备支持 12 kHz 可直接用。
- 播放：AAudio 输出流，按时隙推送发射 PCM。
- 时隙调度：以 UTC 对齐 15 秒周期，区分**偶/奇周期**；采集窗口略小于整秒（参考 demo 的 0.4 s 余量）；处理时钟漂移、丢帧、超时。
- 环形缓冲与线程纪律按 3.3 落地。

验收：模拟器（主机音频）能实时解码；真机（声学/USB）能实时解码；发射时序在瀑布上可见。

> 进展（2026-09-24）：真机 vivo V2338A / Android 14 / arm64-v8a 实测**实时解码有结果、发射有声音**。
> 模拟器宿主无音频设备，AAudio 输入流打开失败（`AAUDIO_ERROR_INTERNAL`），相关用例在模拟器上跳过；
> 发射时序的瀑布可视化随阶段 5 落地。

### 阶段 5：瀑布 / 频谱与解码列表 UI

**目标**：可交互瀑布 + 实时解码列表。

任务：
- Compose Canvas 绘制瀑布（时间滚动、频率刻度、双缓冲避免卡顿）；频率范围/缩放；点击选频。
- 偶/奇周期背景分色。
- 解码列表：时间、SNR、DT、DF、文本；点击条目生成应答（为阶段 6 准备）。
- 状态管理：会话 ViewModel 暴露不可变 UI 状态。

验收：连续运行无明显掉帧；点击选频后发射频率随之更新（阶段 6 验证）。

> 进展（2026-09-24）：已落地 Compose 瀑布（滚动、按峰值自适应强度、点击选频，红色竖线标记）、
> 频率轴刻度、偶/奇周期分色（瀑布顶部色条 + 解码列表行底色）、解码列表（UTC 时间 / SNR / DT / DF / 文本，
> 点击条目即按该频率选频）、`SessionViewModel` 三路不可变状态（messages / waterfall / status）+
> 时隙进度、奇偶周期与"等待时隙对齐"提示。
> native 侧同时补齐解码指标与 waterfall 行流（见 `docs/JNI-CONTRACT.md` 第 4、6 节）。
> 待办：连续运行掉帧实测、真机瀑布观感/强度阈值调优、频谱（FFT 曲线）视图。

### 阶段 6：QSO 发射工作流

**目标**：能完成一次完整通联（接收 → 应答 → 报告 → 73）。

任务：
- QSO 状态机：CQ → 回应 → 报告 → R/73 → 完成；超时重发、重复抑制、避免误回。
- 发射控制面板：周期选择、发射音调、开始/停止、**防误发确认**、发射倒计时。
- PTT：无 CAT，依赖电台 VOX 或手动；App 侧保证音频在正确时隙窗口内播放、其余时间静音。
- 瀑布上叠加 QSO 标记与收发指示。

验收：与 WSJT-X / FT8CN（声学耦合或线缆）完成一次完整通联并记录日志。

> 进展（2026-09-24）：
> - **纯 Kotlin 报文解析**（`qso/Message.kt`）：CQ（含 DX/TEST 等修饰符）、网格交换、信号报告、`R<报告>`/`RRR`、`RR73`、`73`，其余归自由文本。
> - **QSO 状态机**（`qso/QsoEngine.kt`）：CALLER（CQ → 发报告 → 收 `R<报告>` → 发 `RR73`）与 RESPONDER（应答 → 收报告 → 发 `R<报告>` → 收 `RR73`/`73` → 发 `73`）两条序列；
>   只处理发给自己的报文、忽略自身呼号、只认当前对手；每个接收时隙无推进则累计重试，超过 6 次放弃；完成后产生一条待记录通联。**JVM 单测 20 项全部通过。**
> - **发射调度**：ViewModel 每 80 ms 按时隙判定，仅在我方周期（可切偶/奇）且处于起点窗口内调用 native 阻塞写；其余时间静音（见 `docs/JNI-CONTRACT.md` 第 6 节）。
> - **UI**：呼号/网格输入、发射周期选择、**防误发确认对话框**（显示呼号/频率/周期/协议）、发射倒计时与「发射中」指示、紧急「停止发射」、
>   解码列表一键「应答」、瀑布叠加对手频率绿线与发射红框、通联记录（内存态，阶段 7 落库）。
> - 模拟器实测：确认对话框、按时隙发射（`最近发射「CQ F4FSY JN25」`）、无回复时重试计数、紧急停止后回到空闲，均正常。
> 待办：ADIF 落库（阶段 7）、真机与 WSJT-X/FT8CN 完成一次真实通联、标准序列之外的变体（如 `TU;`、DXpedition）暂不支持。

### 阶段 7：多页 UI、解码参数、日志与网格（原「ADIF 日志与配置持久化」扩展）

**目标**：通联可持久化、可查询、可导出；解码参数可调；具备 JTDX 风格的操作能力与 GridTracker 风格的网格视图。

> 详细 UI 规格见 **`docs/UI-DESIGN.md`**（阶段 7 UI 设计 v1.0）。

任务：
- **多页导航**：底部 4 Tab —— 操作 / 日志 / 网格 / 设置；切页不打断接收与 QSO。
- **解码参数可设置**：三档预设（快/标准/深）+ 高级参数（分析频段、时间/频率过采样、最低同步分、LDPC 迭代次数、最大候选数、最大解码条数）；
  `monitor_config_t` 类参数重建引擎生效，解码类参数热生效。
- **JTDX 风格 QSO 能力**：Hold Tx Freq、Call 1st 自动应答、过滤与排除（CQ only / 排除已通联 / 呼号前缀）、
  颜色高亮（发给我的 / 新网格 / 新前缀 / 已通联 / 当前对手）、逐条手动发送（应答、报告、R 报告、RR73、73、自由文本）。
- **DataStore**：呼号、网格、协议、音频路由、解码参数、发射策略等。
- **Room**：通联记录实体/DAO；新增/编辑/删除/查询；QSO 完成自动落库。
- **ADIF 导入导出**：字段映射与容错解析，走系统文件选择器（SAF）。
- **日志页**：筛选（呼号/日期/波段/模式）、手动补录、统计仪表盘（总数/唯一呼号/网格/确认数/波段分布/近 30 天）。
- **网格页**：离线 Canvas 自绘 Maidenhead 网格图，按 未通联/已通联/已确认 着色，支持缩放平移与点击查看该网格通联；
  **并叠加实时层**——把本会话解码出的台站按其网格投点，颜色区分 CQ / 发给我 / 新网格 / 已通联 / 当前对手，SNR 决定点的大小与亮度，像 GridTracker2 那样显示「当前接收与信号」。

验收：完成通联自动落库；导出 ADIF 可被常见日志软件导入；解码参数改动可回读且生效；
过滤与 Call 1st 行为符合预期；已通联网格在网格页正确点亮；**网格页能实时显示当前接收的台站与信号**。

> 进展：
> - **2026-09-24 UI 设计定稿**（`docs/UI-DESIGN.md`），拆为 7a 数据底座 / 7b 解码参数 / 7c 操作页强化 / 7d 网格页与仪表盘 / 7e 收尾。
> - **2026-09-24 7a 数据底座完成**：
>   - 依赖：DataStore Preferences `1.2.1`、Room `2.8.5`（KSP `2.2.10-2.0.2`，与 Kotlin 版本严格配对）、`material-icons-core`（图标只用内置 core 集）。
>   - **构建注意（重要）**：AGP 9 使用**内置 Kotlin 编译**，KSP 仍通过 `kotlin.sourceSets` 注册生成目录会被拒，需在 `gradle.properties` 设 `android.disallowKotlinSourceSets=false`（AGP 有「实验性」警告，属预期）。
>   - 新增数据层：`data/settings/`（`AppSettings` + `SettingsRepository`，DataStore 为设置唯一事实来源）、
>     `data/log/`（`QsoEntity`/`QsoDao`/`AppDatabase`/`QsoRepository`）、`data/adif/`（`AdifCodec` 字节级解析 + `AdifMapper`）、
>     `data/BandPlan.kt`、`data/QsoTime.kt`、`grid/Maidenhead.kt`。
>   - 新增 UI：`MainShell`（底部 4 Tab）、`OperateScreen`（原单页拆出 + 波段选择 + 瀑布高度档位）、
>     `LogScreen`（统计卡 + 筛选 + 列表 + 补录/编辑 + ADIF 导入导出）、`GridScreen`（7a 先做统计列表，地图留 7d）、
>     `SettingsScreen`（台站 / 日志与 ADIF / 关于）；`AppContainer` + `Ft8VoxApplication` 做手工依赖注入。
>   - 呼号/网格/波段**下沉到设置页**并 DataStore 持久化；QSO 完成自动写入 Room；操作页「最近通联」改读 Room。
>   - 即时生效：`start()` 会用设置里的 `fMin/fMax/timeOsr/freqOsr` 初始化引擎（7b 的设置界面待补）。
>   - **ADIF 细节**：长度按 **UTF-8 字节数**（ADIF 3.x 规定）解析/生成；导入判重口径为「呼号 + 完成时间 + 波段 + 模式」；
>     导出用 `CreateDocument("application/octet-stream")`——用 `text/plain` 会被 DocumentsUI 强制追加 `.txt`，把 `.adi` 变成 `.adi.txt`。
>   - **已知取舍**：打开 SAF 文件选择器会让 Activity 进入后台，`onStop` 会停止接收（阶段 9 上前台服务后消除）。
>   - **验证**：`assembleDebug` 通过；JVM 单测 62 项全过（新增 ADIF/Maidenhead/QsoTime/BandPlan/日志筛选统计）；
>     instrumented 20 项全过（新增 6 项 Room 仓库集成测试：增删改查、导入去重、导出往返、已通联索引）；
>     模拟器实测：四 Tab 切换不打断接收（切页时「已解码时隙」持续增长）、设置持久化（杀进程重启后呼号/网格仍在）、
>     导入同一 ADIF 两次为「新增 2 / 跳过 2」、导出文件名与内容正确。
> - **2026-09-25 需求细化与顺序调整**：用户确认操作页按 **JTDX/FT8CN 经典分区**做（彩色分类行 + 独立 Rx/Tx 频率面板 + Hold Tx），
>   网格页必须是**地图网格**并**实时显示当前接收的台站与信号**（实时点叠加历史底图，参考 FT8CN / GridTracker2）；
>   子阶段顺序调整为 **7c → 7d → 7b**（先落地本轮强调的两块）。详见 `docs/UI-DESIGN.md` 第 5.1 / 5.3 节。
> - **2026-09-25 7c 操作页强化完成**（`8af302f`）：
>   - 新增纯逻辑：`qso/DecodeHighlight.kt`（`CallPrefix` 紧凑前缀、`WorkedIndex`、`HighlightRole`/`DecodeStyle`/`DecodeHighlight`，
>     高亮优先级 当前 QSO(琥珀) > 发给我(亮蓝加粗) > 新网格(绿) > 新前缀(红) > 普通(灰)）、
>     `qso/DecodeFilter.kt`（`DecodeFilterState`/`DecodeFilter`/`CallFirstSelector`，CQ only / 排除已通联 / 呼号过滤）。
>   - `SessionViewModel`：Rx（`rxFreqHz`）/ Tx（`selectedFreqHz`）频率分离、`workedIndex` 派生、显示过滤、
>     逐条「一次性发射」`sendOnce`、Call 1st 武装/解除与自动应答、`clampFreq`、`currentFilter`；
>     `QsoEngine.configure(myCall, myGrid, maxRetries)`。
>   - `OperateScreen` 重写为经典分区（标题/状态栏/瀑布/频率轴/Rx-Tx 面板/过滤行/解码列表/折叠控制面板 + 三类弹窗）；
>     `AppSettings.holdTxFreq` 默认改为 **false**（点谁打谁）。
>   - **验证**：JVM 单测 85 项全过（新增高亮/过滤分类）；模拟器新版面渲染与 Call 1st 防误发弹窗文案正确。
> - **2026-09-25 7d 网格页完成**（`e4c6df5`）：
>   - 新增纯逻辑：`grid/MapProjection.kt`（等距圆柱投影 + 视口，`fit`/`fill`、缩放锚点、平移钳制）、
>     `grid/GridIndex.kt`（`GridGranularity` 大网格/小网格归并、已确认优先）、
>     `qso/SpotBuilder.kt`（本会话解码构造实时台站：15 分钟窗口、同呼号取最近、`gridCache` 补全网格、排除自己）。
>   - 新增渲染 `ui/GridMap.kt`（Canvas：世界网格线 + 经纬网 + 历史着色单元 + 实时点，最小可见尺寸/SNR 半径）。
>   - 重写 `ui/GridScreen.kt`：2:1 全球地图、手势缩放平移、点击命中选取台站或网格、粒度/实时切换、适应窗口、
>     图例、选中详情（可应答 / 跳日志）、当前接收列表；`MainShell` 传入 `session` 与跳日志回调。
>   - **验证**：JVM 单测 107 项全过（新增投影/网格索引/实时台站 21 项）；模拟器实测地图渲染、点击选取与详情面板、
>     粒度切换、图例与当前接收列表。
>   - **已知限制**：模拟器无信号，「实时 N 台」恒为 0，实时点绘制与命中交由单测覆盖，真机回归放 7e。
> - 本阶段**明确不做**：Hound/Fox（DXpedition）、在线地图瓦片、精确 DXCC 实体表、云日志上传。

### 阶段 8：开源就绪与首个 MVP 发布

**目标**：达到可对外开源发布的 MVP 质量。

任务：
- 文档：`README`（中/英）、构建与使用说明、`docs/`（本 ROADMAP、接口契约）。
- 多语言：中/英起步，字符串外置。
- 许可证合规：保留 ft8_lib 的 LICENSE 与归属；项目自身采用 **GPL-3.0**；生成 `NOTICE`。
- R8/混淆：JNI 类与方法 keep（`keepRules/rules.keep`），否则 release 崩溃。
- 发布：签名配置、版本号、GitHub Release / tag；CI 产出 APK。
- 免责声明与合规提示（发射相关）。

验收：Release APK 在模拟器与真机稳定运行；仓库可被他人克隆构建。

### 阶段 9：稳定性与性能（MVP 之后）

- 前台服务保证息屏续跑；省电与发热优化。
- 长时间运行内存稳定（避免每时隙大分配、JNI 引用泄漏）。
- 崩溃/ANR 监控与日志。
- 真机机型矩阵验证（尤其 USB 音频与重采样路径）。

### 阶段 10：功能增强（对标 FT8CN 的后续项）

- 呼号地区归属（中国省级可参考 JTDX 石家庄版数据）。
- 全类型报文：DXpedition（Hound）、ARRL Field Day、RTTY RU 等。
- 蓝牙音频 / 单线音频等更多接入方式。
- 云日志上传（Cloudlog / QRZ）——需用户自配凭据与隐私说明。

---

## 6. 技术专题

### 6.1 采样率与重采样

- FT8 标准分析率为 12 kHz，而设备常见原生率为 48 kHz。
- 策略：**采集用设备原生率，native 内做高质量重采样到 12 kHz** 再进 `monitor_process`。
- 理由：内存与 CPU 约为 12 kHz 的 1/4；避免 AAudio 不支持 12 kHz 时直接失败。
- monitor 的 `sample_rate` 配置与 `block_size` 由代码按实际分析率计算，需与重采样后一致。

### 6.2 时隙与时序

- FT8：15 秒一个时隙，偶/奇周期交替；FT4：7.5 秒。
- 以 UTC 对齐；采集窗口略小于时隙，留出解码与切换时间。
- 必须处理：系统时钟漂移、设备音频时钟抖动、解码耗时波动、退后台限流。

### 6.3 发射与安全（无 CAT 前提）

- 无 CAT => 频率/模式/PTT 均由操作者或电台 VOX 负责。
- App 只保证：**在正确时隙播放正确音频，其余时间绝对静音**；提供防误发与紧急停止。
- 开源文档与 App 内需有免责声明与合规提示。

### 6.4 模拟器为主的调试通道（因 D8）

- 补 `x86_64` ABI 后模拟器可跑 native。
- 提供两条替代通道，绕开 USB/OTG 无法在模拟器验证的问题：
  1. **文件注入**：把 WAV 当作“麦克风数据”喂给解码链（复用阶段 2 接口）。
  2. **WAV 导出**：把发射 PCM 落盘，供离线检查与自回环测试。
- USB 音频、声学耦合、时延与抖动等**必须真机验证**，作为发布前的硬门槛。

### 6.5 日志与 ADIF

- Room 存储结构化通联；ADIF 负责交换。
- 关注字段：CALL、BAND、FREQ、MODE、RST_SENT/RST_RCVD、QSO_DATE、TIME_ON、GRIDSQUARE、COMMENT 等。
- 导入需容错（缺字段、编码异常）。

### 6.6 开源合规

- `ft8_lib` 为上游 vendored 代码：**保留 LICENSE 与版权声明**，尽量不改上游，定制放在 `jni_bridge.c` 与 Kotlin 层。
- 参考 FT8CN 为 MIT，但**不要直接搬运其代码**（授权与风格问题），只借鉴功能设计。
- 需要 `LICENSE`、`NOTICE`、`README`、`CONTRIBUTING`。

---

## 7. 测试策略

| 层级 | 内容 |
| --- | --- |
| native 回归 | `ft8_lib/test/wav/` 样本解码断言（FT8 + FT4） |
| 编码自回环 | 自产波形用阶段 2 解码器验证 |
| Kotlin 单测 | 报文解析、QSO 状态机、ADIF 读写、时隙计算 |
| UI 测试 | Compose 关键界面（列表、面板） |
| 集成/真机 | AAudio 采集播放、声学/USB、时序与长时间稳定性 |

模拟器用于日常开发与 UI/native 逻辑；**真机用于音频与硬件相关的门禁验证**。

---

## 8. 风险登记

| # | 风险 | 影响 | 应对 |
| --- | --- | --- | --- |
| R1 | 工具链较新（AGP 9.3.2 / compileSdk 37 / JDK 25） | 依赖不兼容、构建不可复现 | 阶段 0 锁定版本、校验 wrapper、CI 固定 JDK 并验证构建 |
| R2 | AAudio 采样率/路由在模拟器与真机差异大 | 采集不可用或失真 | native 重采样 + 真机验证 + 文件注入兜底 |
| R3 | 音频时钟抖动 / 时隙漂移 | 解码率下降或漏解 | 动态对齐、窗口余量、丢帧统计与补偿 |
| R4 | `audio.c` 依赖 PortAudio | 若误加入编译会失败 | CMake 明确排除，只编 monitor/wave/kissfft/ft8 |
| R5 | 无 CAT 导致 PTT/频率依赖人工 | 使用体验受限、误发风险 | 依赖 VOX/手动；加强发射窗口控制与防误发 |
| R6 | 模拟器无法验证 USB/OTG | 真机问题发现晚 | 建立文件注入/WAV 导出通道，真机门禁前置 |
| R7 | CPU/功耗高（持续解码 + 瀑布） | 发热、掉电快 | native 高效运算、UI 限帧、可调过采样率与候选数 |
| R8 | 发射合规与执照 | 法律风险 | 免责声明 + 文档提示 + 防误发 |
| R9 | 混淆导致 JNI 名称被改 | release 崩溃 | `keepRules/rules.keep` 明确 keep JNI 类/方法 |
| R10 | 上游 ft8_lib 更新与本地改动的冲突 | 维护成本 | 定制隔离在 bridge/Kotlin；记录上游版本 |

---

## 9. 里程碑总览

| 里程碑 | 对应阶段 | 标志 |
| --- | --- | --- |
| M0 基线就绪 | 0 | 稳定工具链 + CI + 可跑 APK |
| M1 桥打通 | 1 | Kotlin 调到 native，库完整编入 |
| M2 解码可用 | 2 | WAV 回归通过（FT8+FT4） |
| M3 编码可用 | 3 | 自回环解码成功 |
| M4 实时链路 | 4 | 实时采集解码 + 按时隙发射 |
| M5 可视化 | 5 | 瀑布 + 解码列表可交互 |
| M6 能通联 | 6 | 完成一次完整 QSO |
| M7 数据完整 | 7 | ADIF 落库与导出 |
| **MVP** | 0~8 | 开源发布的可用版本 |
| M8 稳定 | 9 | 长时间运行稳定 |
| M9 增强 | 10 | 逐步对标 FT8CN 高级功能 |

---

## 10. 待定事项（后续决策）

1. 重采样实现：native 自写 FIR/多相 vs 引入轻量重采样库。
2. 瀑布性能不足时的升级路径（SurfaceView / OpenGL）。
3. 目标频段与默认频率表（是否内置常用波段）。
4. 发射功率/ALC 提示策略（无 CAT，只能提示）。
5. 云日志上传是否纳入，及隐私与凭据存储方案。
6. 多语言首批语言范围（中/英之外）。
7. 呼号归属数据来源与体积/更新方式。
