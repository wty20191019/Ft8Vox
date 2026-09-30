# Ft8Vox

在 Android 上原生运行 FT8 / FT4 数字通信的开源应用。

Ft8Vox 把 Android 手机变成一台 FT8 / FT4 终端：采集电台音频实时解码，手动或自动完成通联并落库。
手机**强制竖屏**，音频 I/O 用 **AAudio**，DSP 在 native 层复用开源的 [ft8_lib](app/src/main/cpp/ft8_lib)（含 kissfft），
界面用 **Jetpack Compose**（Material 3）。音频接入支持**声学耦合**与 **USB 声卡 / OTG**。

> **无 CAT**：不控制电台频率 / 模式 / PTT，发射靠电台 **VOX** 或手动 PTT。
> 对标基准是 [FT8CN](https://github.com/bg7yoz/ft8cn)（BG7YOZ / N0BOY），凡有意偏离处均在文档中逐条列出。

## 功能概览

- **接收**：实时解码 + 瀑布图 + 解码报文列表（时隙 / UTC / SNR / dT / 报文 / 音频频率 / 国家 / 距离）。
- **发射**：手动（CQ / 应答 / 报告 / R 报告 / RR73 / 73 / 自定义报文）或**自动程序**（自动选台、自动跑完整段 QSO）。
- **QSO 自动系统**：六步报文序号状态机 + 单档常开自动程序 + 两个安全阀（发射监管、无回应兜底）。
- **日志**：通联自动落库（含距离备注），ADIF 合并导入 / 导出。
- **地图**：离线世界卫星底图 + 网格 / 呼号 / CQ 旗 / 信号连线 / 昼夜灰线。
- **跟踪 CQ 列表**：手动或自动收录的呼号名单，是自动程序呼叫 CQ 的例外名单。

完整的界面说明、操作方式、设置项、JNI 契约与验收清单见 **[docs/Ft8Vox.md](docs/Ft8Vox.md)**。

## 技术栈

| 组件 | 版本 / 说明 |
| --- | --- |
| Kotlin + Jetpack Compose | Kotlin 2.2.10，Compose BOM 2026.02.01，Material 3 |
| 音频 | AAudio（Android API 26+），USB 声卡 / OTG 路由 |
| DSP | C / CMake / NDK（ft8_lib + kissfft），在 native 完成 |
| 数据 | Room（通联日志）、DataStore（设置）、ADIF（导入导出） |
| 工具链 | AGP 9.3.2 / Gradle 9.5.0 / JDK 25 / compileSdk 37 / minSdk 26 / NDK 28.2.13676358 |
| ABI | `arm64-v8a`、`armeabi-v7a`、`x86_64`（模拟器） |

版本以 [gradle/libs.versions.toml](gradle/libs.versions.toml) 与 [app/build.gradle.kts](app/build.gradle.kts) 为准；工具链**不降级**，只做版本锁定与可复现验证。

## 构建与运行

环境要求：JDK 25、Android SDK（compileSdk 37）、NDK 28.2.13676358、CMake（随 SDK 安装即可）。

```bash
# 构建 Debug APK
./gradlew :app:assembleDebug            # Windows: .\gradlew.bat :app:assembleDebug

# 构建 + 跑 JVM 单测
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
```

- 产物路径：`app/build/outputs/apk/debug/app-debug.apk`
- 设备端测试（可选，需连接设备 / 模拟器）：`./gradlew :app:connectedDebugAndroidTest`
- 单测只覆盖纯逻辑（报文解析 / 组装、QSO 状态机、自动程序、ADIF、网格与地图、筛选与高亮等）；
  **触发音频、VOX 键控、真实通联**必须在真机 + 电台上验证，清单见 [docs/Ft8Vox.md](docs/Ft8Vox.md) 第 10 节。
- **不配 CI**：出包与测试均本地执行。

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
│   ├── grid/                               # Maidenhead 网格、大圆几何、地图投影、昼夜灰线
│   ├── qso/                                # 报文解析/组装、DXCC、QSO 状态机、自动程序、筛选与高亮
│   └── ui/                                 # Compose 页面与组件（操作/频谱/地图/日志/设置 + theme/）
├── cpp/                                    # native：jni_bridge.c、audio_engine.c、ftx_session.c + ft8_lib
└── res/                                    # 资源
docs/Ft8Vox.md                              # 唯一文档：使用与设计
```

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
