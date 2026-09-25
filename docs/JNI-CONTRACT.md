# JNI 接口契约（JNI-CONTRACT）

本文档定义 Ft8Vox 中 Kotlin 与 native（`libft8.so`）之间的接口约定。
阶段 2–4 的实现以此为准，避免音频、UI 与引擎反复返工。

## 1. 命名与符号

- Kotlin 入口类（均为 `object`，位于 `com.example.ft8vox.engine`）：
  - `Ft8Engine`：离线解码 / 编码。
  - `AudioEngine`：实时音频 I/O 与时隙调度。
- native 函数符号必须遵循 `Java_<包名下划线>_<类名>_<方法名>`。
  - 例：`Ft8Engine.test()` → `Java_com_example_ft8vox_engine_Ft8Engine_test`。
- **重命名类或包时，必须同步修改 `app/src/main/cpp/jni_bridge.c` / `audio_engine.c`。**
- 这些 JNI 类/方法必须在 `app/src/main/keepRules/rules.keep` 中保留（防 release 混淆）。

## 2. 数据格式约定

| 项 | 约定 |
| --- | --- |
| PCM 格式 | 单声道、`float32`、取值范围 `[-1.0, 1.0]` |
| 分析采样率 | **12 kHz**（FT8/FT4 标准） |
| 采集采样率 | 设备原生（常见 48 kHz），由 native 重采样到 12 kHz |
| 大数组传递 | **`FloatArray`**（`GetFloatArrayElements` 读取，块间无拷贝） |
| 文本编码 | UTF-8（报文为 ASCII 子集） |
| 协议 | 枚举 `Protocol { FT8, FT4 }` |

> 解码核心（monitor + 呼号哈希 + 候选/解码）已抽取为 native 模块 `ftx_session.c`，
> 离线（`jni_bridge.c`）与实时（`audio_engine.c`）两条路径共用。

## 3. 生命周期接口（Ft8Engine，离线）

| Kotlin 方法 | 职责 | 线程 |
| --- | --- | --- |
| `initialize(config: Ft8Config, decodeParams: DecodeParams = DecodeParams())` | 创建并初始化 monitor/waterfall（协议、采样率、频率范围、过采样率），并下发解码参数 | 初始化线程 |
| `setDecodeParams(params: DecodeParams)` | 更新**热生效**的解码参数（见 4.1），无需重建引擎 | 任意（内部无锁读写单字） |
| `reset()` | 清空当前时隙的 waterfall 与分块缓冲，准备下一周期 | 解码线程 |
| `release()` | 释放 native 资源 | 与 initialize 同线程 |

`Ft8Config` 字段（对应 `monitor_config_t`）：`protocol`、`sampleRate`、`fMin`、`fMax`、`timeOsr`、`freqOsr`。
**改动 `Ft8Config` 需重建引擎**；`DecodeParams` 则可在运行中调整。

## 4. 解码接口

| Kotlin 方法 | 职责 |
| --- | --- |
| `processAudio(samples, length)` | 接收任意长度的 12 kHz PCM；native 内部按 monitor 块大小累积，余数留待下次（支持流式分块喂入） |
| `decodeDetailed(): List<DecodeResult>` | 时隙结束时调用，执行候选查找 + 解码，返回带指标的报文列表 |
| `decode(): List<String>` | 便捷方法，等价于 `decodeDetailed().map { it.text }` |

`DecodeResult` 字段（对应 native `ftx_decode_result_t`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `text` | `String` | 解码出的报文明文 |
| `snr` | `Int` | 近似 SNR（dB，换算到 2500 Hz 参考带宽） |
| `dt` | `Float` | 相对时隙起点的**名义**时间偏移（秒），已减去 0.5 s 起始延迟 |
| `df` | `Int` | 音频频率偏移（Hz） |
| `score` | `Int` | 候选 Costas 同步得分 |
| `slotUtcMs` | `Long` | 所属时隙的 UTC 起点（毫秒）；离线解码为 0 |

> `DecodeResult` 由 native 直接 `NewObject` 构造，构造签名固定在 `jni_common.h`：
> `(Ljava/lang/String;IFIIJ)V`。**修改 Kotlin 字段顺序/类型时必须同步更新该签名。**

SNR 估算口径（照搬 JTDX，与 WSJT-X 同一尺度）：对每个符号 i，取**该符号实际发送音调**所在 bin 的功率 `P_sig`（含信号 + 本底噪声），
与**同一符号内其余音调** bin 的平均功率 `P_noi`（只含噪声）之比 `r_i = P_sig / P_noi`；
在**功率域（线性域）** 对全部符号求平均得到 `mean_r`，扣除信号 bin 内自身的本底噪声得 `excess = mean_r − 1`，
再按 `snr = 10·log10(excess) − 26.5 + window_db` 折算到 2500 Hz 参考带宽（FT8：79 符号；FT4：105 符号），最后钳位。
噪声参考取算术均值、**不使用分布假设**，且与被测信号同符号、频率紧邻，天然抗带内强台与时间起伏。

> **为何换掉旧口径**：旧版取「信号两侧局部窗口（`±16 bin`，约 ±100 Hz，排除音调区间 `freq_offset-2 .. freq_offset+9`）的 **25 分位**，
> 再按 bin 功率服从指数分布 `÷ −ln(0.75)` 换算回均值」。该换算**只在理想白噪声下无偏**；
> 真实电台音频常见的脉冲/结构化噪声会让分位远低于均值 → 噪声底被低估 → **SNR 系统性虚高**。
> 注意 harness 用的正是理想白噪声，恰好是旧口径的适用边界，故其自测通过并不能证明旧口径在真机音频上无偏。

> **照搬原式的已知代价（本项目瀑布窗的限制）**：JTDX 的分析窗是 **1 个符号 + 矩形加权**，8 个音调在窗内**严格正交**，
> 其余音调 bin 确实只含噪声。本项目瀑布窗是**周期 Hann、窗长 = 1 符号周期 × freq_osr**（默认 `freq_osr=2`，约 2 个符号），
> 音调不再正交，其余音调 bin 会被**信号自身的泄漏**污染。泄漏比例很小（约 −31 dB，即信号功率的 0.08%），
> 但强台的 bin 信噪比可达 +35 dB，泄漏因此达噪声底的数倍，会把噪声底抬高，使读数随信号增强而**压缩**：
> `excess_meas ≈ (S/N) / (1 + α·S/N)`（`α·S` 为泄漏功率），弱台基本无偏、强台偏低。上表已量化该压缩（真值 ≥ −5 dB 时偏差 4~8 dB）。
> 若要消除这段压缩，只需把噪声窗口挪到音调区间之外（提交 `ba8a189` 保存了该替代实现）。

经验修正两项（对应 JTDX 的「经验曲线 + 钳位」）：

1. **去偏**：噪声参考是 n 个指数分布 bin 的**平均**，`E[1/mean_n] = n/((n−1)·N)`，比真值大 `n/(n−1)` 倍（FT8 `n=7` → `+16.7%`），
   故逐符号乘回 `(n−1)/n`；不加此项弱台会整体偏高。
2. **窗函数折算**：`window_db = 10·log10(RBW / 2500) + 26.5`，其中 `RBW = 1.5 · (1/符号周期) / freq_osr`
   （分析窗长 = 1 个符号周期 × `freq_osr`，周期 Hann 窗噪声等效带宽为 1.5 个 FFT bin）。
   与 JTDX 的 1 符号矩形窗（≈6.25 Hz）不同，故用该项把 `−26.5` 的基准换到本项目窗的等效噪声带宽；
   `freq_osr=2`（FT8「标准」）时 `RBW = 4.6875 Hz → window_db = +0.77`，**净折算 = 10·log10(RBW/2500) = −27.27 dB**，与 WSJT-X 的 `−27.0` 一致。
   > 若写死 `−27.0`：「快」(`freq_osr=1`) 会**偏低约 2.7 dB**、「深」(`freq_osr=4`) 会**偏高约 3.3 dB**；FT4 亦按同一 RBW 公式处理。
   > 常数定义在 `ftx_session.c`：`JTDX_SNR_CAL_DB 26.5`、`JTDX_SNR_MIN_DB −24`、`JTDX_SNR_MAX_DB +40`。

**实测校核（合成信号，白噪声，同一份数据对照 `HEAD` 旧实现）**：以「真值」`SNR = P_tone/(N0·2500)` 为准，

| 真值 | −20 | −15 | −10 | −5 | 0 | +5 | +10 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 旧（p25 局部窗口） | −21.29 | −15.48 | −11.32 | −6.54 | −1.20 | +3.85 | +8.34 |
| 新（照搬 JTDX 原式） | −21.79 | −16.42 | −12.62 | −9.35 | −6.28 | −2.76 | +2.39 |
| 对真值偏差 | −1.8 | −1.4 | −2.6 | **−4.4** | **−6.3** | **−7.8** | **−7.6** |

即**弱台（≤ −20 dB）几乎不变，强台被压缩 4~8 dB**（原因见上「已知代价」；`matchedRect_t4` 等独立对照支路可证明真值列无偏）。
强台场景（弱台真值 −15 dB + 1000 Hz 处带内强台）：旧「全带均值」在强台 ≥ +20 dB 时被压到 −24 dB 下限，新实现稳定在 −20 ~ −21 dB。
若真机对比仍偏高，优先排查**采集链路**而非公式：
① AAudio 采集预设（见 §6，已强制 `UNPROCESSED`，避免系统降噪把噪声底压掉）；
② `fMin/fMax` 设得比实际音频通带更宽时，噪声底会被安静频段拉低 → SNR 虚高。

> 参考：WSJT-X `ft8b.f90` 用 `xsig/xnoi − 1`（单 bin 噪声）与默认支路 `xsnr2 = xsig/xbase/3.0e6 − 1`
> （`sbase` 为 `baseline.f90` 拟合的**局部**噪声基线），其提交说明亦强调「带内强台不应把弱台判低」。

说明：呼号哈希表由 **native 侧管理**（去重、老化），不通过 JNI 回调，避免频繁跨语言调用。

### 4.1 可调解码参数（`DecodeParams` ↔ `ftx_decode_params_t`）

| 字段 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- |
| `minScore` | 10 | 4..40 | Costas 同步最低得分，越高候选越少越快 |
| `maxCandidates` | 140 | 20..500 | 单时隙候选上限 |
| `ldpcIterations` | 25 | 5..60 | LDPC 最大迭代次数，越高越慢、弱信号解码率越高 |
| `maxDecoded` | 50 | 5..100 | 单时隙最多解出的报文条数 |

- 这些参数**只影响搜索与 LDPC 迭代，不改变 STFT 结构**，因此可在接收过程中热更新（`setDecodeParams`）。
- native 侧 `sanitize_decode_params()` 会再次钳制；候选/结果数组按上限（512 / 128）静态分配，调参不会改变内存占用。
- 与 `monitor_config_t`（`fMin`/`fMax`/`timeOsr`/`freqOsr`）不同：后者改动需重建引擎（重启接收）。
- 设置页的预设（快 / 标准 / 深）即这三组值的组合；手动改任一项会标记为「自定义」。

## 5. 编码接口

| Kotlin 方法 | 职责 |
| --- | --- |
| `encode(text, frequencyHz, protocol, sampleRate): FloatArray` | 文本 → 77-bit 载荷 → tone → GFSK 波形，返回**一个完整时隙**的 12 kHz PCM（含首尾静音填充） |

- `protocol` / `sampleRate` 默认沿用最近一次 `initialize` 的配置。
- 报文无法解析/编码时抛 `IllegalArgumentException`。
- 时隙定位遵循 WSJT-X 约定：波形在时隙起点后 **0.5 s** 开始，末尾留白填满整个时隙（不能居中，否则 FT4 数据段会超出解码器候选搜索窗口）。
- 覆盖报文：标准报文（CQ、呼叫、R/RR73/73）、自由文本；FT8 与 FT4。
- 生成的 PCM 可直接交给 AAudio 播放，或落盘为 WAV 供离线验证。

## 6. 实时音频接口（AudioEngine）

| Kotlin 方法 | 职责 |
| --- | --- |
| `initialize(config: Ft8Config, decodeParams: DecodeParams = DecodeParams())` | 创建实时引擎（内部建 monitor 会话并下发解码参数）；重复调用先释放 |
| `setDecodeParams(params: DecodeParams)` | 运行中热更新解码参数（见 4.1） |
| `release()` | 停止采集/播放并释放 |
| `startCapture(preferredRate = 48000, deviceId = 0): Int` | 打开 AAudio 采集流并启动 DSP 线程；`deviceId > 0` 时用 `AAudioStreamBuilder_setDeviceId` 指定输入设备（见 6.2），否则系统默认；返回**设备实际采样率**，负数为错误码 |
| `stopCapture()` | 停止采集并释放采集侧资源 |
| `pollDecoded(): List<DecodeResult>` | 取走并清空自上次调用以来解出的报文（**拉取模型**，无 native 回调） |
| `waterfallInfo(): WaterfallInfo?` | waterfall 频率轴：`bins`、`binHz`（FT8/FT4 为 6.25 Hz）、`fMinHz` |
| `pollWaterfall(maxRows = 64): ByteArray` | 取走新产生的 waterfall 行；每行 `bins` 字节（uint8 幅度，`2·dB+240`），行按时间先后排列 |
| `startPlayback(preferredRate = 48000, deviceId = 0): Int` | 以阻塞写模式打开 AAudio 播放流；`deviceId > 0` 时用 `AAudioStreamBuilder_setDeviceId` 指定输出设备，否则系统默认；返回设备实际采样率，负数为错误码 |
| `stopPlayback()` | 关闭播放流 |
| `play(pcm: FloatArray): Int` | 播放一个时隙的 12 kHz PCM（内部重采样到输出采样率），返回写入帧数 |
| `playTx(pcm: FloatArray, pttSilenceMs = 0, leadToneMs = 0): Int` | 发射播放：在数据前插入 `pttSilenceMs` 静音与 `leadToneMs` 单音（前导音），带看门狗写入；返回写入帧数 |
| `playTone(freqHz = 1000, durationMs = 2000): Int` | 播放测试单音（VOX 键控/音量联调），返回写入帧数 |
| `setVox(config: VoxConfig)` | 下发 VOX/PTT 配置（热生效，见 6.1） |
| `setInputGain(gainDb: Int)` | 下发采集增益（热生效，−12..30 dB，见 6.2） |
| `state(): AudioState?` | 状态快照（`running` / `inSlot` / 输入输出采样率 / 时隙进度 / 丢帧 / 已解码时隙数 / UTC 时间 / `voxOpen` / `voxLevelDb`） |
| `utcNowMs(): Long` | 当前 UTC 毫秒时间（用于时隙倒计时/对齐） |

`startCapture` 错误码：`-1` 未初始化、`-2` 无法创建 stream builder、`-3` 打开输入流失败、`-4` DSP 线程创建失败、`-5` 启动输入流失败。
`startPlayback` 错误码：`-1` 未初始化、`-2` 无法创建 builder、`-3` 打开输出流失败、`-4` 启动失败。

实现约定：

- **采集**：请求 `preferredRate`（优先 48 kHz），native 重采样到 12 kHz。
  降采样先过 **4 阶 Butterworth 低通**（截止约 `0.35 × outRate`，抗混叠），再线性插值；升采样直接线性插值。
- **采集预设（绕开系统语音处理）**：AAudio 输入预设默认就是 `VOICE_RECOGNITION`（部分机型带降噪/AGC），
  会压低噪声底（SNR 虚高）并吞掉弱信号。故按 `UNPROCESSED → VOICE_RECOGNITION → GENERIC` 依次回退，
  成功即用（日志打印实际预设）。`setInputPreset` 自 API 28 才提供，minSdk 26/27 上用 `dlopen` 解析符号，
  解析不到则保持系统默认。
- **时隙调度**：按 UTC 对齐（FT8 = 15 s，FT4 = 7.5 s）。仅在时隙起点后 200 ms 内开始采集，累积满 `slot_samples` 后解码；若跨入下个时隙则先解码已采集部分并重新对齐。
- **线程**：AAudio 回调只写入无锁 SPSC 环形缓冲（不分配、不加锁）；DSP 线程负责重采样与解码。
- **限流**：环形缓冲满时丢弃样本并累加 `droppedSamples` 统计。
- **waterfall 行流**：每处理完一个 monitor block，将 `time_osr` 行（取 `freq_sub=0`）写入 native 环形缓冲；
  FT8 下约每 80 ms 一行。Kotlin 侧用 `pollWaterfall()` 拉取，若落后太多则自动丢弃被覆盖的旧行。
- **发射时序（Kotlin 侧调度，不新增 native 线程）**：`playTx()` 是阻塞写，由 Kotlin 轮询线程按时隙触发：
  1. 操作者确认（防误发）后先 `startPlayback()` 建好播放流，避免发射瞬间才建流而错过时隙；
  2. 每 80 ms 检查一次，计算目标发射时隙与播放起点（见 6.1）：无前导时就地在「当前时隙奇偶 == 我方发射周期」且起点后 **1200 ms** 内发射；有前导时提前 `PTT 延迟 + 前导音时长` 启动，使 FT8 数据仍落在时隙起点。同一时隙绝不重复写；
  3. 迟到时按迟到量缩短前导（`effectivePreambleMs`）以保持数据对齐；迟到超过 `前导 + 1200 ms` 则放弃本时隙；
  4. 发射波形自带 0.5 s 前导静音，用于吸收「上一接收时隙解码完成 → 决定本轮报文」的数百毫秒延迟；
  5. 其余时间不向播放流写数据（保持静音）；`stopPlayback()` 可中止进行中的阻塞写，用于紧急停止。

### 6.1 VOX / PTT（U7b）

无 CAT 时 App 无法直接控制电台 PTT，只能通过发射音频序列间接键控。`VoxConfig` 字段：

| 字段 | 默认 | 范围 | 说明 |
| --- | --- | --- | --- |
| `mode` | `AUDIO` | `AUDIO` / `SILENCE` | 电平判定口径：音频检测 / 静音检测 |
| `thresholdDb` | -40 | -60..-20 | 触发门限（dBFS） |
| `delayMs` | 300 | 0..2000 | 候选状态翻转去抖时长 |
| `pttDelayMs` | 0 | 0..500 | 数据前的前导静音（留给声卡路由/电台起键） |
| `leadToneMs` | 0 | 0..2000 | 数据前的前导单音（1 kHz），用于抢先键控 VOX |
| `watchdogMs` | 10000 | 1000..60000 | 发射写入看门狗（卡死保护） |

- **VOX 电平/触发**：native 在 DSP 线程对原始输入块计算 RMS 电平（快攻击/慢释放平滑），按 `mode`/`thresholdDb`/`delayMs` 维护 `voxOpen`。无 CAT 无法读取电台真实键控状态，该状态仅为**输入音频活动的近似**，用于状态栏与音频速览显示，**不参与发射门控**（发射仍按时隙）。
- **前导与对齐**：`playTx()` 把 `pttDelayMs` 静音与 `leadToneMs` 单音插在数据之前；调用方（`planTx`）相应提前播放起点，保证数据落在时隙起点。
- **看门狗**：`write_blocking()` 以「本段音频预期播放时长 + 3 s」为实际阈值（不低于 `watchdogMs`），避免截断合法的整时隙发射；仅在音频设备卡死时中止写循环。
- `nativeGetState` 返回 12 个 `Long`：索引 0–9 同前，`[10]=vox_open`、`[11]=vox_level_db×10`。

### 6.2 音频路由与增益（U7c）

- **设备枚举（Kotlin，`engine/AudioDevices.kt`）**：用 `AudioManager.getDevices(GET_DEVICES_INPUTS/OUTPUTS)` 列出设备，返回 `{id, 名称, 类型标签}`；首项恒为「系统默认」（id 0）。设备 id 即 `AudioDeviceInfo.getId()`，与 AAudio `setDeviceId` 使用同一编号。
- **路由**：`startCapture(preferredRate, deviceId)` / `startPlayback(preferredRate, deviceId)` 在 `deviceId > 0` 时调用 `AAudioStreamBuilder_setDeviceId`；`<= 0` 走系统默认。设备选择只在**建流时**生效：
  - 输入设备变化需重开采集流（运行中提示「下次开始接收生效」）；
  - 输出设备变化时 `stopPlayback()` 关闭旧流，下次发射用新设备重开（发射中提示「下次发射生效」）。
- **采集增益**：`nativeSetInputGain(handle, gainDb)` 钳制 −12..30 dB，DSP 线程对原始采集块乘 `10^(dB/20)`，超过满幅限幅到 [−1,1] 防回绕。增益作用于**包含 VOX 电平判定**的整条链路，故调增益会同时改变状态栏 VOX 读数。
- **设备 id 易变**：设备 id 可能随插拔/重启变化，存的是原始 id 字符串（空串=默认），失配时 `label` 回退「系统默认」。

## 7. 调试接口

| Kotlin 方法 | 职责 |
| --- | --- |
| `Ft8Engine.test(): String` | 占位握手，验证 so 加载（阶段 1） |
| `AudioEngine.resample(input, inRate, outRate): FloatArray` | 测试用重采样，验证抗混叠链路（内部接口） |

## 8. 线程模型

- **AAudio 回调线程**：只把 PCM 写入无锁 SPSC 环形缓冲，绝不调用重 JNI、加锁或分配内存。
- **DSP/解码线程**：从环形缓冲取数据 → 重采样 → 按块累积 waterfall → 时隙结束解码，结果进入待轮询队列。
- **发射线程**：调用 `encode` 生成 PCM，`play()` 内部重采样后阻塞写入 AAudio 播放流。
- native 对象（monitor 等）与创建/使用它的线程绑定；若跨线程调用 JNI 需 `AttachCurrentThread`。
- MVP 阶段解码结果**拉取返回**，不引入 native → Kotlin 回调。

## 9. 错误处理

- 参数非法（采样率、频率范围、协议）在 `initialize` 时校验并抛出异常。
- 解码失败（LDPC/CRC 未通过）不抛异常，仅不产生结果；调试信息（`ldpc_errors`、CRC）通过日志输出。
- `startCapture` / `startPlayback` 失败返回负错误码而非崩溃，UI 据此提示（如模拟器无音频设备）。
- native 崩溃需最小化：所有输入数组长度先校验再访问。

## 10. 待定

- ~~大块 PCM 用 `FloatArray` 还是 Direct `ByteBuffer`~~ → **已定稿：`FloatArray`**（阶段 2 采用，`GetFloatArrayElements` 读取，块间无拷贝）。若后续实测发现拷贝开销明显，再评估 Direct `ByteBuffer`。
- ~~重采样算法（native 自写 vs 轻量库）~~ → **已定稿：native 自写**（阶段 4：4 阶 Butterworth 抗混叠 + 线性插值，无第三方依赖）。
- 是否提供 native → Kotlin 的增量解码回调（当前采用拉取模型，暂不需要）。
- 设备路由选择（内置 / USB / 蓝牙）与音频模式（`MODE_IN_COMMUNICATION`）：属 U7「音频路由与增益」项，尚未接通；当前使用系统默认输入/输出。VOX/PTT 前导与电平已在 U7b 接通（见 6.1）。
