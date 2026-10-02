# JTDX 解码器移植方案与进度

> 目标：把 JTDX（WSJT-X 分支）的 FT8 解码算法移植到 Ft8Vox 的 native 层（C），
> 使 App 的「深档」达到 JTDX 级弱信号接收能力。当前基线（`ft8_lib` 解码）：
> 语料 `ft8_lib/test/wav` 共 1289 条期望报文，快档 recall 72.2%、深档 72.7%。
> 
> JTDX 源码参考副本：`D:\Desktop\jtdx`（`master`，仅作参考，不入本仓库）。

## 一、两条解码路线的差异

| 环节 | 现有 Ft8Vox（ft8_lib） | JTDX |
| --- | --- | --- |
| 前端 | 12k 音频 → STFT 瀑布（幅度） | 12k 音频 → 混频到基带、抽到 200Hz |
| 候选检测 | 瀑布上找 Costas 同步 | `sync8` 符号谱 + Costas 相关，三档阈值 |
| 精细同步 | 无 | `sync8d` 时间/频率微调 |
| 比特度量 | 非相干幅度 | **相干复数度量**（`ft8b`） |
| 纠错 | BP（`ldpc.c`） | BP（带 AP 掩码）+ **OSD 兜底** |
| 多遍 | 简单「±2 置零减谱」 | **真减法** `subtractft8` + 重解 |
| 先验 | 无 | **AP**（按 QSO 状态约束信息位） |

JTDX 的增益主要来自：相干度量 + OSD + 真减法 + AP。

## 二、移植进度

### ✅ 已完成：P0 骨架 + LDPC 核心（(174,91) BP + OSD + CRC14）

新增目录 `app/src/main/cpp/jtdx/`：

- `jtdx_ldpc_data.h`：从 JTDX 源码精确提取（脚本生成，勿手改）
  - `kJtdxMn[174][3]`、`kJtdxNm[83][7]`、`kJtdxNrw[83]`（校验矩阵）
  - `kJtdxGenHex[83]`（OSD 生成矩阵的 23 位十六进制串）
- `jtdx_ldpc.h` / `jtdx_ldpc.c`：
  - `jtdx_ldpc_bp_decode`（对应 `ft8v2/bpdecode174_91.f90`）
  - `jtdx_ldpc_osd_decode`（对应 `ft8v2/osd174_91.f90`，含 boxit/fetchit 二段预处理）
  - `jtdx_ldpc_encode`、`jtdx_chkcrc14`、`platanh` 近似
  - 位序与 ft8_lib 一致（前 91 位 = 77 信息 + 14 CRC），可与 `ft8_lib/ft8/message.c` 直接对接

构建接入：`CMakeLists.txt` 增加 `JTDX_SOURCES`；`tools/host_decode/build.py` 以 glob 纳入 `jtdx/*.c`。

**自测结果**（宿主机，随机消息 → 加 CRC → 编码 → 加高斯噪声 LLR → BP/OSD）：

| σ（LLR 噪声） | BP 成功 | OSD 补回 | 合计 /200 |
| --- | --- | --- | --- |
| 0.5 / 2.0 | 200 / 200 | 0 | 200 |
| 3.0 | 161 | 37 | 198 |
| 4.0 | 6 | 48 | 54 |
| 5.0 | 0 | 2 | 2 |

结论：BP + OSD 组合在 BP 失败时能明显补回（σ=4 时 BP 仅 6，OSD 补到 54），
验证了「OSD 兜底」这一核心增益点。CRC 与 ft8_lib 已交叉验证一致。

### ✅ 已完成：P1 前端 + P2 纠错链（端到端可用）

新增/补充 `app/src/main/cpp/jtdx/`：

- `jtdx_fft.h/.c`：kissfft 封装 `four2a` 等价接口（r2c / c2c 正反，均不缩放），带计划缓存
- `jtdx_wave.h/.c`：`gfsk_pulse` + `gen_ft8wave`（生成互相关参考序列 csync/csynccq，供 P3 复用）
- `jtdx_tables.h/.c`：`windowx/windowc1/facx/facc1/icos7/graymap/csync/csynccq/ctwkw/ctwkn/ctwk256`
  （初始化对应 `cwfilter.f90`；`csync` 用真实 CQ 报文经 `ft8_encode` 生成）
- `jtdx_downsample.h/.c`：`ft8_downsample`（192000 r2c → 频带选取 → 3200 c2c 逆 → c0）
- `jtdx_sync.h/.c`：`sync8`（非 lagcc 分支）、`sync8d`、`twkfreq1`、`indexx`
- `jtdx_metric.h/.c`：`ft8b` 符号谱提取 + sp 归一化 + srr + 四组相干 LLR（llra/b/c/d）
- `jtdx_decode.h/.c`：时隙解码入口（sync8 → 下变频 → sync8d 精调 → twkfreq1 → 度量 → BP/OSD → 解包）

宿主 CLI：`decode_cli --jtdx`（见 `tools/host_decode/decode_cli.c`）。

**关键实现说明**：

- **时间基准**：`ft8b` 初始 `i0=nint((xdt+0.5)*fs2)`，其中 `xdt` 为 `sync8` 的候选时间。
  `sync8` 的 Costas 相关从 `j+jstrt`（`jstrt=12.5` 截断为 12）开始，恰好抵消 +0.5s 的大部分，
  残差 4 个 200Hz 样点落在 `sync8d` 的 ±8 搜索内，故端到端自洽。
- **默认路径**：`swl=.false.`、`lagcc=.false.`、`lqsothread=.false.`、`filter=.false.`、`ipass=1`、
  `nweak=1`（故 `isubp1=1`，`isubp2` 只试 1/2/3，分别用 `llrd/llrb/llrc`）。
- 暂未移植 `ft8b` 中 300–600 行的同步启发式过滤与 `chkfalse8` 假解码过滤（属假阳性抑制，
  不影响 recall），后续可补。

**语料回归**（`tools/host_decode`）：

| 配置 | recall | hit | extra |
| --- | --- | --- | --- |
| 快档基线 | 72.2% | 931/1289 | 50 |
| 深档基线 | 72.7% | 937/1289 | 53 |
| **JTDX P1+P2（`--jtdx`）** | **75.8%** | **977/1289** | **34** |

结论：相干度量 + OSD 已带来 +3.1 个百分点、且假阳性更少，超过深档基线验收标准。

### ⏳ 待办：P3–P5

- **P3 减法多趟**：`gen_ft8wave` + `subtractft8`，强信号解出后重建并减去，再解
- **P4 AP 先验**：按 Ft8Vox 已有六步 QSO 状态机填 `apmask`
- **P5 集成**：`ftx_session_decode` 可选 JTDX 或 ft8_lib（各自有各自的挡位）

### 验收方式

统一用 `tools/host_decode/`：`build.py` 编译、`bench.py` 跑语料统计 recall/假阳性，
以「超过 72.7% 深档基线」为每阶段通过标准。

## 三、注意事项

1. **CRC 约定**：JTDX 的 `chkcrc14a` 用 `boost::augmented_crc`，但最终判定必须与
   ft8_lib/WSJT-X 一致；本移植直接采用 ft8_lib 的位级算法以保证互通（已交叉验证）。
2. **位序**：JTDX 的 `cw` 与 ft8_lib 的 174 位顺序相同；receive 端可复用 ft8_lib 的
   `ftx_message_decode` 解包，无需移植 `packjt77.f90`。
3. **性能**：`sync8` 每时隙要做数百次 3840 点 FFT，Android 上需实测耗时；必要时
   用候选上限 / 分档控制（深度档才全跑）。
4. **AP 依赖**：`ft8b` 的 AP 逻辑绑定 JTDX 的 QSO 状态；P4 再处理，P1–P3 用空掩码。

