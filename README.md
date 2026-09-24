# Ft8Vox

在 Android 上原生运行 FT8 / FT4 数字通信的开源应用。

> 状态：早期开发中。工程基线（阶段 0）已完成，FT8/FT4 引擎接入与功能实现正在推进。
> 详细计划见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 简介

Ft8Vox 目标是把手机变成一台可用的 FT8 / FT4 终端：

- 采集电台音频并实时解码，展示瀑布图与解码报文列表；
- 手动发射（音频输出，配合电台 VOX 或手动 PTT）；
- 记录通联日志并支持 ADIF 导入导出。

音频 I/O 基于 **AAudio**，DSP 复用开源的 [ft8_lib](app/src/main/cpp/ft8_lib)（含 kissfft），在 native 层完成；界面使用 **Jetpack Compose**。本应用**暂不做 CAT 电台控制**。

## 功能规划

| 功能 | 状态 |
| --- | --- |
| FT8 / FT4 解码引擎（native） | 规划中 |
| 发射波形生成（native） | 规划中 |
| 实时音频采集/播放（AAudio） | 规划中 |
| 瀑布图 / 频谱（Compose Canvas） | 规划中 |
| 解码报文列表 | 规划中 |
| QSO 发射工作流 | 规划中 |
| ADIF 通联日志 | 规划中 |
| 呼号地区归属 / 云日志（后续） | 规划中 |

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- AAudio（Android，API 26+）
- C / CMake / NDK（ft8_lib + kissfft）
- Room / DataStore / ADIF（日志与配置）

## 构建与运行

见 [docs/BUILD.md](docs/BUILD.md)。简述：

```bash
./gradlew :app:assembleDebug
```

## 目录结构

```
app/src/main/
├── java/com/example/ft8vox/   # Kotlin：UI、引擎封装、音频、会话、数据
├── cpp/                       # 原生：jni_bridge.c + ft8_lib
└── res/                       # 资源
docs/                          # 路线图、构建说明
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
