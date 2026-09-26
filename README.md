# Ft8Vox

在 Android 上原生运行 FT8 / FT4 数字通信的开源应用。

> **状态（2026-09-26）**：阶段 0–7 与「新 UI」U1–U9 已完成，功能可用（接收 / 发射 / 自动程序 / 日志 / 地图 / 设置）；
> **后台保活（前台服务）已实装**（切后台 / 息屏继续接收，SAF 选择器不再中断）；
> 真机实测已修掉输出声卡挂起（`f5215b5`）、两台机器互回信号报告死循环（`fbac634`）与顶栏「发射中」文案不符（`ee5cb6c`）；
> 剩余阶段 8（开源就绪与首个 MVP 发布）与阶段 9 的稳定性收尾，以及真机全流程回归。
> **第一次使用请看 [docs/How2use.md](docs/How2use.md)（用户手册）**。
> 路线图见 [docs/ROADMAP.md](docs/ROADMAP.md)，新 UI 计划与取舍见 [docs/NEW-UI-PLAN.md](docs/NEW-UI-PLAN.md)，
> 真机验收清单见 [docs/REGRESSION.md](docs/REGRESSION.md)。

## 简介

Ft8Vox 目标是把手机变成一台可用的 FT8 / FT4 终端：

- **接收**：采集电台音频并实时解码，展示瀑布图与解码报文列表（SNR / DT / DF / 报文 / 实体 / 距离）；
- **发射**：手动（CQ / 应答 / 报告 / R 报告 / RR73 + 宏 + 发送队列）或**自动程序**（模式即开关：0 手动 / 1 主叫 / 2 混合，三层架构按解码结果自动选台并走完整段 QSO）；
- **日志**：通联记录自动落库（含 `Distance: xxx km, QSO by Ft8Vox` 备注），ADIF **合并**导入 / 导出；
- **地图**：网格、CQ 分区、解码台站的距离与方位。

音频 I/O 基于 **AAudio**，DSP 在 native 层复用开源的 [ft8_lib](app/src/main/cpp/ft8_lib)（含 kissfft）；界面使用 **Jetpack Compose**（Material 3），底部 4 个 Tab：操作 / 地图 / 日志 / 设置。音频接入支持**声学耦合**与 **USB 声卡 / OTG** 两种。

**本项目不做 CAT 电台控制**：发射依赖电台 **VOX** 或手动 PTT，频率/模式/PTT 均不自动控制。

## 快速上手

> 详细图文步骤与全部设置项说明见 **[docs/How2use.md](docs/How2use.md)（用户手册）**。

1. **填台站**：设置 → 台站 → 填「呼号」与「网格（Maidenhead）」，选好「波段与频率」。
2. **接音频**：设置 → 音频 / 电台，输入 / 输出设备选「系统默认」（声学耦合）或插入的 **USB 声卡**；用「输入增益」把电平调到 −30 ~ −10 dB 一带。
3. **开始接收**：回「操作」页；首次需点一下解码列表中间的提示来授予麦克风权限，之后会自动开始接收。瀑布在滚动、解码列表出现报文即成功。
4. **开始发射**：点解码卡片上的 CQ 报文，**左滑「呼叫」**（或点开详情按「呼叫」），再把抽屉条上的**「发送总开关」**打开 —— 到你的发射周期就会自动发。
5. **看日志**：「日志」页会自动写入完成的通联；也可手动新增 / 编辑，或导入 / 导出 ADIF。

> 发射的唯一闸门是**「发送总开关」**（默认关＝只接收）；本应用没有「确认发射」弹窗，点下即排程。

## 功能现状

| 功能 | 状态 | 说明 |
| --- | --- | --- |
| FT8 / FT4 解码引擎（native） | ✅ 已完成 | `monitor.c` 多声道解码，协议可切换；解码深度 快/标准/深 + 高级参数热更新 |
| 接收 SNR / DT / DF | ✅ 已完成 | SNR 口径**照搬 JTDX**（`10·log10(excess) − 26.5 + 窗口修正`），见 `docs/JNI-CONTRACT.md` §4 |
| 发射波形生成 + VOX 键控 / PTT 时序 | ✅ 已完成 | native 生成 FT8/FT4 波形；前导静音 + 前导单音键控 VOX，PTT 延迟 / 看门狗可调；**无 CAT，无法感知键控状态**（VOX 状态指示与三项设置已于 2026-09-26 删除） |
| 时隙偏移（整时隙校准） | ✅ 已完成（待真机验收） | −2.5s–+2.5s，解码窗口与发射起点一起平移；把解码卡片的「时间差 DT」原样填进去即可校准两端 |
| 实时音频采集 / 播放（AAudio） | ✅ 已完成 | 输入/输出设备可选（系统默认或 USB 声卡），采样率偏好，输入增益热生效；输出流走**非 MMAP** 路径 + 坏流自愈（真机「发几次测试音后再也发不出去」修复） |
| 瀑布图 / 频率轴 | ✅ 已完成 | Compose Canvas 自绘；频率轴固定铺满，亮度自适应拉伸（不做捏合缩放） |
| 解码报文列表 | ✅ 已完成 | 两行卡片 + 色条高亮 + 行尾标记；左滑设为目标 / 右滑删除 / 长按忽略 / 双击地图；筛选 Chip；**自动翻到最新**；筛选栏最左**一键清除** |
| QSO 发射工作流 | ✅ 已完成 | 手动发送 + 目标时隙自动对应（双方相反周期） |
| 自动程序（三层架构，取代 Call 1st） | ✅ 已完成（待真机验收） | **模式即开关**：0 手动 / 1 主叫 / 2 混合；第 2 层 `AutoScheduler` 负责选台 / 排队 / 主叫⇄应答切换，第 1 层 `QsoEngine` 负责重发机制，第 3 层用户手动接管；含保护限制（无有效 QSO / 发射总时长超限自动停） |
| DXCC / CQ / ITU 区域与前缀识别 | ✅ 已完成 | 内置前缀表；「新 DXCC / 新 ITU / 新 CQ 区域 / 新网格 / 新前缀 / 新呼号」高亮可分别开关 |
| 通联日志（Room） | ✅ 已完成 | 筛选/统计/波段柱图、单条删除二次确认、备注自动追加距离 |
| ADIF 导入 / 导出 | ✅ 已完成 | 导入为**合并**（确认 `Y` 具粘性、非空优先、时间容差 ±60s） |
| 地图页 | ✅ 已完成 | **离线 Web Mercator 卫星底图**（单张 z5，8192×8192，按可见区域流式解码 + ×0.7 暗化）+ 大圆距离/方位 + CQ 分区；**不做**在线瓦片 |
| 设置页（8 组，30+ 项） | ✅ 已完成 | DataStore 持久化；主题暗/亮、字体三档、瀑布高度即时生效 |
| 前台服务（后台持续接收） | ✅ 已完成（待真机验收） | `microphone` 类型前台服务 + 常驻通知（含「停止接收」动作）；切后台 / 息屏继续接收，SAF 选择器不再中断；**接收中按返回键退到后台**（弹提示），未接收时正常退出 |
| CI（GitHub Actions） | ⏸ 已停用 | 工作流改名为 `.github/workflows/android.yml.disabled`，只做本地构建与测试（见 `docs/BUILD.md` §5） |

**明确不做**：CAT 电台控制、Hound / Fox、FST4、接收频率自动窄带过滤、在线地图瓦片（离线栅格卫星底图已实装）、逐条呼号的精确 DXCC、日志上传（CloudLog / LoTW / eQSL）与局域网后台（后三项曾为占位，已连同代码与文档一并删除）。

## 技术栈

| 组件 | 版本 / 说明 |
| --- | --- |
| Kotlin + Jetpack Compose | Kotlin 2.2.10，Compose BOM 2026.02.01，Material 3 |
| 音频 | AAudio（Android API 26+），USB 声卡 / OTG 路由 |
| DSP | C / CMake / NDK（ft8_lib + kissfft），在 native 完成 |
| 数据 | Room（通联日志）、DataStore（设置）、ADIF（导入导出） |
| 工具链 | AGP 9.3.2 / Gradle 9.5.0 / JDK 25 / compileSdk 37 / minSdk 26 / NDK 28.2.13676358 |
| ABI | `arm64-v8a`、`armeabi-v7a`、`x86_64`（模拟器） |

版本以 [`gradle/libs.versions.toml`](gradle/libs.versions.toml) 与 [`app/build.gradle.kts`](app/build.gradle.kts) 为准；工具链**不降级**，只做版本锁定与可复现验证。

## 构建与运行

完整环境要求见 [docs/BUILD.md](docs/BUILD.md)。常用命令：

```bash
# 构建 Debug APK（任一平台）
./gradlew :app:assembleDebug            # Windows: .\gradlew.bat :app:assembleDebug

# 构建 + 跑 JVM 单测（当前 284 例 / 31 suite 全绿）
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
```

- 产物路径：`app/build/outputs/apk/debug/app-debug.apk`
- 设备端测试（可选，需连接设备/模拟器）：`./gradlew :app:connectedDebugAndroidTest`
  （含 `Ft8DecodeTest` / `Ft8EncodeTest` / `VoxTxNativeTest` / `AudioRoutingTest` 等）
- 单测只覆盖纯逻辑（时隙、报文解析/组装、QSO 状态机、自动程序、ADIF、网格与地图、筛选与高亮等）；
  **触发音频、VOX 键控、真实通联**必须在真机 + 电台上验证，清单见 [docs/REGRESSION.md](docs/REGRESSION.md)。
- **CI 暂不配置**：出包与测试均本地执行。

## 目录结构

```
app/src/main/
├── java/com/example/ft8vox/
│   ├── AppContainer.kt / MainActivity.kt   # 入口与依赖装配
│   ├── data/                               # 波段表、UTC/时隙时间
│   │   ├── adif/                           # ADIF 编解码、字段映射
│   │   ├── log/                            # Room 实体·DAO·仓库、导入合并、备注生成
│   │   └── settings/                       # DataStore 设置项与仓库
│   ├── engine/                             # Ft8Engine / AudioEngine（JNI 封装）、设备枚举、提示音
│   ├── grid/                               # Maidenhead 网格、大圆几何、地图投影
│   ├── qso/                                # 报文解析/组装、DXCC、QSO 状态机、自动程序、筛选与高亮
│   └── ui/                                 # Compose 页面与组件（四页 + 顶栏/状态条 + theme/）
├── cpp/                                    # native：jni_bridge.c、audio_engine.c、ftx_session.c + ft8_lib
└── res/                                    # 资源
docs/                                       # 路线图、用户手册、构建、JNI 契约、UI 设计、回归清单
```

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/How2use.md](docs/How2use.md) | **用户手册（How2use）**：装机 / 接线 / 首次设置 / 通联 / 自动程序 / 日志 / 地图 / 全部设置项 / 排错 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 阶段路线图、技术决策（D1–D10）、架构与阶段 0–10 |
| [docs/BUILD.md](docs/BUILD.md) | 工具链版本、构建命令、CI 停用说明 |
| [docs/JNI-CONTRACT.md](docs/JNI-CONTRACT.md) | Kotlin ↔ native 接口契约、SNR 口径、采集预设 |
| [docs/new_ui.md](docs/new_ui.md) | 新 UI 设计（配色、字号、顶栏/底栏、四页布局） |
| [docs/NEW-UI-PLAN.md](docs/NEW-UI-PLAN.md) | U1–U9 实施计划与补记（含每条决策的取舍与后果） |
| [docs/NEW_QSO_.md](docs/NEW_QSO_.md) | 自动程序（第 2 层）设计依据：三层架构、流程 / 重试 / 时隙规则、§五菜单 |
| [docs/REGRESSION.md](docs/REGRESSION.md) | 真机/模拟器回归清单（A–S 组）与结果记录 |
| [docs/UI-DESIGN.md](docs/UI-DESIGN.md) | 阶段 7 版 UI 设计（已被 `new_ui.md` 取代，保留存档） |
| [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) | 贡献指南（分支、提交信息、代码规范） |

## 许可证

本项目采用 **GNU General Public License v3.0**，见 [LICENSE](LICENSE)。

第三方组件：

- [ft8_lib](app/src/main/cpp/ft8_lib) — MIT License，Copyright (c) 2018 Kārlis Goba
- [kissfft](app/src/main/cpp/ft8_lib/fft) — BSD-3-Clause，Copyright (c) 2003-2010 Mark Borgerding

详见 [NOTICE](NOTICE)。

## 免责声明

本项目仅用于学习与研究。使用本应用进行无线电发射须遵守所在国家/地区的法律法规（在中国大陆请遵守《中华人民共和国无线电管理条例》），并持有相应操作资质。使用者自行承担一切后果。

## 致谢

- Karlis Goba (YL3JG)：ft8_lib 的作者。
- Mark Borgerding：kissfft 的作者。
- FT8CN (BG7YOZ / N0BOY)：本项目在功能与交互上参考了该开源安卓 FT8 应用。
