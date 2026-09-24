# 参与贡献（CONTRIBUTING）

感谢参与 Ft8Vox。请先阅读 [README](../README.md) 与 [docs/ROADMAP.md](ROADMAP.md)。

## 开发环境

- 工具链版本见 [docs/BUILD.md](BUILD.md)（AGP 9.3.2 / Gradle 9.5.0 / Kotlin 2.2.10 / JDK 25 / NDK 28.2）。
- 本地构建：`./gradlew :app:assembleDebug`（Windows 用 `gradlew.bat`）。
- 运行测试：`./gradlew test`。

## 分支与提交

- 主分支：`main`；开发分支：`F_APP`（当前）。
- 从开发分支拉取功能分支，命名建议：`feat/<简述>`、`fix/<简述>`、`docs/<简述>`。
- 提交信息使用中文，采用“类型：简述”形式，例如：
  - `阶段0：固定 NDK 版本，添加 Android CI 与构建文档`
  - `fix：修正 JNI 解码接口的返回值`
- 一次提交尽量只做一件事，保持可回滚。

## 代码规范

- Kotlin：遵循 `kotlin.code.style=official`；变量/函数/类名用英文，**注释用中文**。
- C：上游 `app/src/main/cpp/ft8_lib/` 视为第三方，**尽量不修改**；定制逻辑放在 `jni_bridge.c` 与 Kotlin 层。
- 新增依赖统一走版本目录 `gradle/libs.versions.toml`。
- JNI 类/方法必须在 `app/src/main/keepRules/rules.keep` 中保留，避免 release 混淆后崩溃。

## 提交前检查

1. `./gradlew :app:assembleDebug` 通过。
2. `./gradlew test` 通过。
3. 不改动无关文件；不提交 `local.properties`、`.idea/`、构建产物。

## 许可证

贡献的代码将按项目的 **GPL-3.0**（见 [LICENSE](../LICENSE)）授权。请勿引入与 GPL-3.0 不兼容的代码或依赖。
