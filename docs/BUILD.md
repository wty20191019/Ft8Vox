# 构建说明（BUILD）

本文档记录 Ft8Vox 的构建环境、依赖版本与常用命令，保证本地与 CI 一致、可复现。

## 1. 工具链版本

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Android Gradle Plugin (AGP) | 9.3.2 | 见 `gradle/libs.versions.toml` |
| Gradle | 9.5.0 | 见 `gradle/wrapper/gradle-wrapper.properties`（含 SHA-256 校验） |
| Kotlin | 2.2.10 | 见 `gradle/libs.versions.toml` |
| Compose BOM | 2026.02.01 | 见 `gradle/libs.versions.toml` |
| JDK | 25 | 见 `gradle/gradle-daemon-jvm.properties`（`toolchainVersion=25`） |
| compileSdk / targetSdk | 37 | Android SDK Platform `android-37.0` |
| minSdk | 26 | AAudio 的最低要求 |
| NDK | 28.2.13676358 | 在 `app/build.gradle.kts` 中 `ndkVersion` 固定 |
| CMake | 3.22.1 | 见 `app/build.gradle.kts` 的 `externalNativeBuild` |
| ABI | `arm64-v8a` / `armeabi-v7a` / `x86_64` | 真机 ARM + 模拟器 x86_64 |

## 2. 前置条件

- 已安装 Android SDK，且在 `local.properties` 中配置 `sdk.dir`（该文件不入库）。
- 已安装上表所列的 NDK 与 CMake（可通过 SDK Manager 安装）。
- 可用 JDK 25（或允许 Gradle 通过 foojay 自动解析 toolchain）。

## 3. 常用命令

```bash
# 构建 Debug APK
./gradlew :app:assembleDebug

# 运行 JVM 单元测试
./gradlew test

# 清理
./gradlew clean
```

Windows 下使用 `gradlew.bat` 替代 `./gradlew`。

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 4. 原生库

- 原生代码位于 `app/src/main/cpp/`，由 CMake 构建为共享库 `libft8.so`。
- 当前 `CMakeLists.txt` 仅编译 `jni_bridge.c` 与 `ft8_lib/ft8/*.c`。
- 后续阶段需按 ROADMAP 阶段 1 扩展，加入 `ft8_lib/common/`（monitor/wave）与 `ft8_lib/fft/`（kissfft）；
  **注意排除 `ft8_lib/common/audio.c`**，它依赖 PortAudio，Android 无法编译。

## 5. CI

- 工作流：`.github/workflows/android.yml`。
- 环境：`ubuntu-latest` + JDK 25 + Android SDK + 上述 NDK/CMake。
- 执行：`./gradlew assembleDebug test`。
