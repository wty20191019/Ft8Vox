# JNI 接口契约（JNI-CONTRACT）

本文档定义 Ft8Vox 中 Kotlin 与 native（`libft8.so`）之间的接口约定。
阶段 2–4 的实现以此为准，避免音频、UI 与引擎反复返工。

## 1. 命名与符号

- Kotlin 入口类：`com.example.ft8vox.engine.Ft8Engine`（当前为 `object`）。
- native 函数符号必须遵循 `Java_<包名下划线>_<类名>_<方法名>`。
  - 例：`Ft8Engine.test()` → `Java_com_example_ft8vox_engine_Ft8Engine_test`。
- **重命名类或包时，必须同步修改 `app/src/main/cpp/jni_bridge.c`。**
- 这些 JNI 类/方法必须在 `app/src/main/keepRules/rules.keep` 中保留（防 release 混淆）。

## 2. 数据格式约定

| 项 | 约定 |
| --- | --- |
| PCM 格式 | 单声道、`float32`、取值范围 `[-1.0, 1.0]` |
| 分析采样率 | **12 kHz**（FT8/FT4 标准） |
| 采集采样率 | 设备原生（常见 48 kHz），由 native 重采样到 12 kHz |
| 大数组传递 | 优先 Direct `ByteBuffer` 或 native 分配，避免每次 JNI 拷贝 |
| 文本编码 | UTF-8（报文为 ASCII 子集） |
| 协议 | 枚举 `Protocol { FT8, FT4 }` |

## 3. 生命周期接口

| Kotlin 方法 | 职责 | 线程 |
| --- | --- | --- |
| `initialize(config: Ft8Config)` | 创建并初始化 monitor/waterfall（协议、采样率、频率范围、过采样率） | 初始化线程 |
| `reset()` | 清空当前时隙的 waterfall 与解码缓存，准备下一周期 | 解码线程 |
| `release()` | 释放 native 资源 | 与 initialize 同线程 |

`Ft8Config` 字段（对应 `monitor_config_t`）：`protocol`、`sampleRate`、`fMin`、`fMax`、`timeOsr`、`freqOsr`。

## 4. 解码接口

| Kotlin 方法 | 职责 |
| --- | --- |
| `processAudio(samples, length)` | 接收一块 12 kHz PCM，内部调用 `monitor_process` 累积 waterfall |
| `decode(): List<DecodeResult>` | 时隙结束时调用，执行候选查找 + 解码，返回本周期结果 |

`DecodeResult` 字段：

| 字段 | 说明 |
| --- | --- |
| `text` | 解码出的报文明文 |
| `snr` | 信噪比（dB，近似） |
| `dt` | 时间偏移（秒） |
| `df` | 频率偏移（Hz） |
| `score` | 候选同步得分 |
| `protocol` | `FT8` / `FT4` |

说明：呼号哈希表由 **native 侧管理**（去重、老化），不通过 JNI 回调，避免频繁跨语言调用。

## 5. 编码接口

| Kotlin 方法 | 职责 |
| --- | --- |
| `encode(text, frequencyHz): FloatArray` | 文本 → 77-bit 载荷 → tone → GFSK 波形（12 kHz PCM） |

- 覆盖报文：标准报文（CQ、呼叫、R/RR73/73）、自由文本；FT8 与 FT4。
- 生成的 PCM 可直接交给 AAudio 播放，或落盘为 WAV 供离线验证。

## 6. 调试接口

| Kotlin 方法 | 职责 |
| --- | --- |
| `test(): String` | 占位握手，验证 so 加载（阶段 1） |
| `version(): String` | 返回引擎版本（后续可加） |

## 7. 线程模型

- **AAudio 回调线程**：只把 PCM 写入无锁 SPSC 环形缓冲，绝不调用重 JNI、加锁或分配内存。
- **DSP/解码线程**：从环形缓冲取满一个时隙后调用 `processAudio` / `decode`。
- **发射线程**：调用 `encode` 生成 PCM，写入 AAudio 播放流。
- native 对象（monitor 等）与创建它的线程绑定；若跨线程回调需 `AttachCurrentThread`。
- MVP 阶段解码结果**同步返回**，不引入 native → Kotlin 回调。

## 8. 错误处理

- 参数非法（采样率、频率范围、协议）在 `initialize` 时校验并抛出异常。
- 解码失败（LDPC/CRC 未通过）不抛异常，仅不产生结果；调试信息（`ldpc_errors`、CRC）通过日志输出。
- native 崩溃需最小化：所有输入数组长度先校验再访问。

## 9. 待定

- 大块 PCM 用 `FloatArray` 还是 Direct `ByteBuffer`（阶段 2 定稿）。
- 重采样算法（native 自写 vs 轻量库）。
- 是否提供 native → Kotlin 的增量解码回调（当前不需要）。
