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

### ✅ 已完成：P3 减法多趟（subtractft8 + 多趟重解）

新增/修改 `app/src/main/cpp/jtdx/`：

- `jtdx_subtract.h/.c`（新增）：
  - `jtdx_subtract_init`：按 `cwfilter.f90` 构建频域低通 `cw`（NFILT1=4000，cos² 窗）
    与端部校正 `endcorr`（含 1/NFFT 归一化）。
  - `jtdx_subtract_xdt3`：对应 `ft8b.f90` 1989–2004 行的 `scorr` 计算
    （基带参考 3 点相关 → `peakup` 抛物线插值 → `xdt3=xdt+scorr*0.005`）。
  - `jtdx_subtract`：对应 `subtractft8.f90`：
    `cfilt = LPF[dd8·conjg(cref)]`，`dd8 -= 2·REAL(cref·cfilt)`；NFRAME=151680、NFFT=180000。
- `jtdx_decode.c`：改为多趟循环（默认 `npass=3`，即 JTDX `nft8cycles=1`）。
  - 每趟在**当前**（可能已被减过的）`dd8` 上重跑 `sync8`；前两趟 `isubp2` 解出后立即减法，
    第三趟不再减法（对应 `ft8_decode.f90` 183/191 行）。
  - 趟内已发生减法且新候选频率落在被减信号 ±50Hz 内时，重算长 FFT（对应 `lsubtracted/ldofft`）。
  - LLR 组选择随趟变化：ipass>1 时 `isubp2=1→llra`（ipass=1 为 `llrd`）。
  - `lhighsens` 按 `ft8_decode.f90` 223 行取值并传入下变频。
  - 重复报文仍执行减法（对应 JTDX `if(lsubtract)` 不带 `ldupemsg`）。
- `jtdx_sync.c`：符号谱度量按趟取模轮换（1/4/7 幅度、2/5/8 功率、3/6/9 L1）。
- `jtdx_metric.h/.c`：新增 `lreverse` 参数，ipass=2 走反相符号谱（对应 `ft8b.f90` 274–283 行）。
- `jtdx_decode.h`：`jtdx_decode_slot` 增加 `npass` 参数。
- `tools/host_decode/decode_cli.c`：新增 `--passes N`（默认 3）。

**语料回归**（60 文件 / 1289 条期望）：

| 配置 | recall | hit | extra |
| --- | --- | --- | --- |
| 快档基线 | 72.2% | 931/1289 | 50 |
| 深档基线 | 72.7% | 937/1289 | 53 |
| JTDX P1+P2（单趟，含 lhighsens 修正） | 80.3% | 1035/1289 | 49 |
| JTDX P3 三趟、**关闭减法** | 77.3% | 997/1289 | 44 |
| **JTDX P3 三趟、开启减法（默认 `--jtdx`）** | **96.4%** | **1242/1289** | **119** |

结论：
- 减法是多趟增益的主要来源（同为三趟：77.3% → 96.4%，+19.1pp），且新增解码里真命中远多于
  假阳性（新增约 +245 命中 / +75 extra），说明重建与相减在能量上正确。
- 开启减法后逐趟递减强信号、逐层显现弱信号，符合 JTDX 设计意图。
- extra 从 34 涨到 119：因为尚未移植 `chkfalse8` 假解码过滤（依调用呼号库做格式校验），
  且参考 `.txt` 来自灵敏度较低的旧解码器，部分「extra」实为真实信号。属已知缺口，
  后续补 `chkfalse8` 可显著压低假阳性（不影响 recall）。

性能：宿主 -O2 下三趟约 1.36s/文件（60 文件约 82s），约为单趟的 3 倍；减去 FFT 的额外
开销在可接受范围。Android 深度档可在后台线程运行。

### ✅ 已完成：P5 集成进 App（深档走 JTDX）

`ftx_session.c` / `audio_engine.c` 接线：

- `ftx_session` 新增整隙原始音频缓冲 `raw`（`raw_cap = max_blocks * block_size`，
  FT8 约 178560 点）：`ftx_session_process` 逐块追加、`ftx_session_reset` 清零。
- `ftx_frozen` 新增 `raw` 副本：`ftx_session_freeze` 随瀑布一起冻结整隙时域样本，
  后台解码线程因此可以脱离实时缓冲慢慢跑 JTDX（不阻塞采集）。
- `decode_waterfall` 增 `raw/raw_len` 参数，深档编排改为：
  1. 快跑（瀑布 + 快档迭代）——快/深共用；
  2. **FT8 且有时域样本**：对整隙音频跑 `jtdx_decode_slot(npass=3)`（相干度量 + OSD +
     减法多趟），结果与瀑布结果按明文去重合并、标记深档；
  3. 兜底（FT4 或无 raw）：保留原 FT8CN 式高迭代深跑 + 瀑布减谱循环。
- `jtdx_decode_slot` 增加 `hash_if` 参数（`ftx_callsign_hash_interface_t*`），
  App 深档用会话的呼号哈希表还原被压缩的非标呼号；CLI 传 NULL。
- 呼号哈希表、`--jtdx` CLI 与快档路径均不受影响；Kotlin 侧无需改动（沿用既有
  `deep` 开关与解码参数）。

**语料回归**（60 文件 / 1289 条期望）：

| 配置 | recall | hit | extra |
| --- | --- | --- | --- |
| App 深档路径（快跑 + JTDX，`decode_cli --deep`） | **97.2%** | **1253/1289** | **149** |

深档路径比纯 JTDX（96.4%）略高，因为快跑还额外兜住少数 JTDX 未解的候选；extra 同步
上升主要来自快跑多出的解码与尚未移植的 `chkfalse8`。

### ⏳ 待办：P4 + 假阳性过滤

- **P4 AP 先验**：按 Ft8Vox 已有六步 QSO 状态机填 `apmask`
- **假阳性过滤**：移植 `chkfalse8`（含 callsign/grid 校验，需引入呼号库）与 `ft8b` 的
  报文协议违规检查（1954–1987 行）
- **性能**：Android 真机实测深档耗时；必要时按机型/电量把 `npass` 降到 1~2

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

