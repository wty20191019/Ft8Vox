# Ft8Vox QSO 自动系统设计

> 本文件由 `docs/NEW_QSO_.md`、`docs/FT8CN-QSO.md`、`docs/FT8CN_QSO_PLAN.md` 合并精简而来
> （**这些源文件已删除，历史版本见 git 记录**），
> 只保留当前生效的设计、算法口径、参数与「有意偏离」清单；过程叙述、逐条历史日志、逐行剖析已剔除。
> 对标基准：FT8CN（Android / Java）。
>
> **2026-09-30 大改**：全面回到 FT8CN 的**报文序号模型**（`functionOrder`）+ **5 路 OR 完成判据**，
> 删掉了此前的「收敛阶梯」「DX / 区域分级选台」等自定机制，详见 §7。
>
> **2026-10-01**：「自动跟踪 CQ（本波段）」恢复**自动写跟踪名单**、并在该台**通联完成后自动移除**
> （**有意偏离** FT8CN，用户决定，见 §5.1 / §7）。

---

## 1. 总览

**一句话**：本机的 QSO 自动系统 ＝「**六步报文序号（order 1..6）＋ 单档常开自动程序 ＋ 两个安全阀**」，
序号驱动、角色隐式、应答强制、进入序号 4/5 即落库，目标是与 FT8CN 同口径把通联跑完且任一阶段都不跑死。

**三层结构**：

| 层 | 名称 | 职责 | 主要实现 |
| --- | --- | --- | --- |
| 第 1 层 | 序号状态机 | 持有**一段** QSO 的全部状态；按「对方序号 + 1」推进六步；跑 FT8CN 的 5 路收尾判据；进入序号 4/5 即落库 | `qso/QsoEngine.kt` |
| 第 2 层 | 自动选台 / 调度 | 收集本批候选、决定「应答定向 / 换台 / 应答 CQ / 发 CQ」，跑发射监管；**不直接发射** | `qso/AutoProgram.kt` |
| 第 3 层 | 人工覆盖 | 手动呼叫 / 回复 / 忽略 / 查看日志；只改目标、复位监管，**不暂停**自动程序 | `ui/SessionViewModel.kt`、`ui/DecodeList.kt` |

**核心模型三件套**：

1. **六步报文序号**——`order` ＝「我下一条要发的报文序号」（FT8CN `functionOrder`），
   判据照抄 `GeneralVariables.checkFunOrder`（移植为 `qso/FunctionOrder.kt`）。
   转移只有一条：**我下一条 = 对方这条的序号 + 1**。所有转移由**报文载荷**驱动，角色由序号动态推断（隐式）。
2. **单档常开自动程序**——没有 AutoSeq 档位 / AutoMode / paused；**唯一闸门是「发送总开关」`txEnabled`**。
3. **两个安全阀**——**发射监管**（超时关闭 `txEnabled`）、**无回应兜底**（FT8CN 的 5 路 OR，
   **在引擎里**判定，第 2 层只负责「换台 / 回 CQ」）。

**关键约定**：

- **「一定应答」**：发给我方的定向报文一律入选并优先处理，不受两个开关、显示筛选、已通联影响
  （只有显式「忽略呼号」`ignoredCalls` 会挡）。**只有 `73` 例外**——它表示对方已收尾，不作为 QSO 起点；
  指名给我的 `RR73`/`RRR`（序号 4）**要接**（我回 73 收尾，照 FT8CN 只排除 `73`）。
- **角色隐式**：`QsoRole`（`CALLER`/`RESPONDER`）只供 UI，不驱动任何转移。
- **人工不暂停**：没有 `paused` / `manualIntervention`；人工操作只是改目标 + `resetSupervision`。
- **报文方向**：FT8 定向报文 = `<收方> <发方> <载荷>`；`K1ABC W2XYZ -08` ＝「发给 K1ABC、由 W2XYZ 发出」，
  我收到的报文里**我的呼号在第一位**。
- **时隙奇偶**：`EVEN(0)` = 00s/30s 起；`ODD(1)` = 15s/45s 起（FT8）。我发哪个周期由本台决定。
- **信号报告**：`-24 … +30 dB`，**一律本地实测值**，绝不回显对方给我的报告。

**三条硬要求**：

1. **必须能自动切换自己是 CQ 方还是应答方**（角色隐式，由报文推断）。
2. **两台机器不得在任一阶段跑死**（序号互推 + 逐条自听过滤 + 5 路收尾判据 + 两个安全阀）。
3. **对方呼叫我方时必须应答**（定向报文一律入选、最高优先）。

**实现映射**：

| 概念 | 实现位置 |
| --- | --- |
| 第 1 层 序号状态机 | `qso/QsoEngine.kt`（`QsoEngine` / `QsoState` / `QsoProgress` / `QsoLogEntry`） |
| 序号判据 | `qso/FunctionOrder.kt`（`checkFun1..5` / `of` / `byExtraInfo` / `NONE`） |
| 第 2 层 调度选台 | `qso/AutoProgram.kt`（`AutoScheduler` / `AutoProgramSelector` / `AutoProgramSettings` / `AutoTarget` / `AutoAction`） |
| 第 2 层 设置 UI | `ui/AutoProgramDialog.kt`（四项，照 FT8CN） |
| 第 3 层 人工覆盖 | `ui/SessionViewModel.kt`（`replyTo` / `setTxEnabled` / `resetSupervision`） |
| 报文解析 | `qso/Message.kt`（`ParsedMessage` / `MessageParser` / `CallMatch`） |
| Tx 槽与 CQ 前缀 | `qso/TxCompose.kt`（`TxMessageKind` / `DEFAULT_CQ_PREFIXES` / `TxScheduler`） |
| 显示筛选 / 已通联索引 | `qso/DecodeFilter.kt`、`qso/DecodeHighlight.kt`（`WorkedIndex`） |
| 忙时换台 | `AutoScheduler.directedTakeover`（FT8CN 循环 2） |

---

## 2. 报文序号与状态机

### 2.1 六步指令序列（`QsoEngine.order`，照 FT8CN `getFunctionCommand`）

| order | 我方发出的报文 | 触发（对方那条报文的序号） | 报告来源 |
| :---: | --- | --- | --- |
| 1 | `<对方> <我> [网格]` | `startResponderQso`（我应答对方的 CQ） | 进入前复位收发报告 |
| 2 | `<对方> <我> <±dd>` | 收到对方序号 **1**（网格）；或 `startCallerQso`（对方主动呼叫我） | **固定第一次测到的强度** |
| 3 | `<对方> <我> R<±dd>` | 收到对方序号 **2**（纯报告） | **与序号 2 同一个值** |
| 4 | `<对方> <我> RR73` | 收到对方序号 **3**（R 报告） | —（**进入即落库**，但本段未结束，见 §2.3） |
| 5 | `<对方> <我> 73` | 收到对方序号 **4**（`RR73`/`RRR`） | —（落库） |
| 6 | `CQ [修饰符] <我> [网格]` | `startCq` | 进入前复位收发报告 |

- `QsoProgress.order` 对外暴露当前序号（`0` = 无）；`TxCompose.TxMessageKind.order` 与之一致
  （`GRID=1 / REPORT=2 / ROGER=3 / RR73=4 / SEVENTY_THREE=5 / CQ=6`，`CUSTOM=0`）。
- CQ 前缀（`AppSettings.cqPrefixes` / `cqPrefixIndex`，抽屉 4×2 单选）由 `startCq(utcMs, cqPrefix)` 传入，
  渲染为 `CQ <前缀> <我> <网格>`（空串＝普通 CQ）；整段 CQ 阶段沿用同一条。
- `TxCompose.kindOf()` 映射：`isCq→6`、`grid→1`、`report→2`、`isRoger→3`、`isRr73→4`、`is73→5`。

### 2.2 序号判据（`qso/FunctionOrder.kt`，照 FT8CN `GeneralVariables`）

| 判据 | FT8CN 行 | 命中条件 | 序号 |
| --- | :--: | --- | :--: |
| `checkFun5` | `:443` | 载荷 == `73` | 5 |
| `checkFun4` | `:438` | 载荷 ∈ {`RR73`, `RRR`} | 4 |
| `checkFun3` | `:420` | 首字符 `R`、次字符非 `R`，余下可解析为整数（`R-10`） | 3 |
| `checkFun2` | `:407` | 至少 2 位、可解析为整数且 `!= 73`（`-10` / `+05`） | 2 |
| `checkFun1` | `:399` | `[A-Z][A-Z][0-9][0-9]`（**本机多认 6 位扩展网格**）、**或空载荷** | 1 |
| CQ | `checkFunOrder` `:391` | `isCq`（`callsignTo` 首段是 `CQ`/`DE`/`QRZ`） | 6 |

- 判定顺序照 FT8CN：**先判 CQ → 6，否则按 5→4→3→2→1 依次命中**，全不中返回 `NONE`（FT8CN `-1`）。
  所以 `73` 归 5（不会落进 2）、`RR73`/`RRR` 归 4（不会落进 3）。
- 判据**只看载荷原文**（`ParsedMessage.payload` ＝ FT8CN 的 `extraInfo`），与收方 / 发方无关；
  「是不是发给我的」由 `CallMatch` 另判。

### 2.3 推进与状态

- **推进规则**（照 `FT8TransmitSignal.parseMessageToFunction` `:862`）：
  **我下一条要发的报文序号 ＝ 对方这条报文的序号 + 1**。
  收到对方的 **73（序号 5）** → 通联完成（落库、不再发射）。
- `QsoState`（对外只读）：`IDLE` / `WAIT_REPLY`（序号 1、6）/ `WAIT_REPORT`（序号 2）/
  `WAIT_RR73`（序号 3）/ **`WAIT_FINAL`（序号 4：已发 RR73 并落库，仍等对方 73）** / `DONE` / `FAILED`。
  `QsoProgress.active = state ∉ {IDLE, DONE, FAILED}`。
  `FAILED` 已无来源（`retryLimit` 体系退役），仅保留枚举供 UI。
- **顺带记录**（照 FT8CN `checkFunctionOrdFromMessages` `:604-631`）：
  - 找回复时**从本批最后一条往前**扫，只要「收方是我 && 发方是当前目标」；
  - 命中即**清零** `noReplyCount`；
  - 对方给的报告（序号 2、3）记进 `reportReceived`（**后到的覆盖先到的**，照 FT8CN `receiveTargetReport` 的写法）；
  - 对方网格只在还空着时补记。

### 2.4 报告值口径

- **我方发出的报告**（序号 2 的 `-dd` 与序号 3 的 `R-dd`）＝**同一个值**：
  「定下这台时测到的强度」（FT8CN `toCallsign.snr`；`:260` 与 `:266` 是同一个变量）。
  取一次后**整段不刷新**——对方重发时强度会抖，报告不该跟着跳。
  正常流程由 `startResponderQso(call, grid, utcMs, snr)` / `startCallerQso(..., snr)` 传入；
  若开始时没给（如人工从半路接手），则在**第一次推进**时按触发报文的 SNR 补取一次。
- 报告 = 解码 SNR，clamp 到 `-24…+30`（`reportFromSnr`）。
- **`reportReceived`** 照 FT8CN：收到序号 2 / 3 就更新（最后一次为准）。

### 2.5 无回应计数

- `noReplyCount` 按**解码批次**累计：本批**没有**「对我的回复」就 +1，收到有效回复即清零。
- **空批（一条解码都没有）不计数**（照 FT8CN `FT8TransmitSignal.java:812`）；
  深度 / 弱信号解码不参与（FT8CN `isDeep`；本机 `DecodeResult.deep` 当前恒 `false`）。
- **序号 6（已发 CQ、等回应者）阶段**：永不放弃主叫、也不自增计数（照 FT8CN）。
- 计数的**唯一用途**是 5 路 OR 里的第 3 / 5 路（见 §2.6），只在**我方序号 == 4** 时生效。
- `QsoEngine.configure(myCall, myGrid, noReplyLimit)`——`noReplyLimit` 下发到引擎，因为判据在引擎里。

### 2.6 收尾判据：5 路 OR（照 FT8CN `:832-843`）

| 路 | 条件 | 结果 |
| :--: | --- | --- |
| 1 | 对方报文序号 == 5（对方发 73） | **正常完成**（`gaveUp = false`） |
| 2 | 我方序号 == 5 且对方沉默 | **结构上不可达**（发出 73 后状态已是 `DONE`） |
| 3 | 我方序号 == 4 且 `noReplyLimit > 0` 且 `noReplyCount > noReplyLimit × 2` | **作废换台**（`gaveUp = true`） |
| 4 | 我方序号 == 4 且**对方开始呼别人**（`targetCallingOthers`） | **作废换台**（`gaveUp = true`） |
| 5 | 我方序号 == 4 且 `noReplyLimit == 0` 且 `noReplyCount > 20` | **作废换台**（`gaveUp = true`） |

- `targetCallingOthers`（照 FT8CN `checkTargetCallMe` `:578`）：计数从 **1** 起，
  本批只要有一条「目标发给我」的报文就返回 false；否则目标每发言一条 +1，`> 1` 即判为「在呼别人」。
- 第 3~5 路命中时 `QsoProgress.gaveUp = true`，**第 2 层据此走 `onTargetGaveUp`（换台 / 回 CQ）**；
  第 1 路是正常完成，走 `onQsoFinished`（队列空了回 CQ）。
- **序号 4 不是终态**：发出 RR73 后本段仍在跑（照 FT8CN 每周期重发 RR73），
  直到对方的 73 或第 3~5 路兜底。

### 2.7 两机全自动时序（示意）

```
时隙 0（我 EVEN）   发  CQ BG7ZJW OM89                 → 对方：S&P 应答（序号 1）
时隙 1（对方 ODD）  GB7ZJW BG7AAA JN25（序号 1）       → 我：对方序号 1 → 发序号 2（实测报告）
时隙 2（我 EVEN）   GB7AAA BG7ZJW -12（序号 2）        → 对方：我方序号 2 → 发序号 3（R 报告）
时隙 3（对方 ODD）  BG7ZJW BG7AAA R-09（序号 3）       → 我：序号 3 → 发序号 4（RR73）＋落库，状态 WAIT_FINAL
时隙 4（我 EVEN）   GB7AAA BG7ZJW RR73（序号 4）       → 对方：序号 4 → 发序号 5（73）＋落库
时隙 5（对方 ODD）  BG7ZJW BG7AAA 73（序号 5）         → 我：收到序号 5 → 完成（不再发射）
两端 onQsoFinished → 队列空 → 回 CQ
```

> 关键：**两端用的是同一条规则**（对方序号 + 1），所以即使阶段错位也能自然收敛——
> 对方还在发纯报告（序号 2）而我已经发过 R（序号 3）时，我按规则重发序号 3（**不跳 RR73**），
> 对方收到后会进到序号 4。若对方始终收不到，由 5 路 OR 的第 3~5 路（在序号 4）兜底收尾。
> 应答方 / 被呼时固定到对方时隙的相反周期。

### 2.8 防跑死检查表

| 风险 | 规避 |
| --- | --- |
| 两端阶段错位（一方等 RR73、一方还在发纯报告） | **同一条规则**「对方序号 + 1」⇒ 各自重发自己的那一步，收到即前进 |
| `RR73` 后对方已停 | 5 路 OR 的第 3 / 5 路（无回应上限 / 20 批硬上限）＋第 4 路（对方转呼别人） |
| 收到对方的 `RR73` 却开不出新 QSO | `AutoTargetKind.RR73`：指名给我的 `RR73`/`RRR` 直接回 73 收尾 |
| 目标整批解码被整批丢弃 | **逐条**过滤仅自听报文，不整批丢弃 |
| 复合同呼号漏匹配 | `CallMatch.isFrom` **双向**宽松 |
| 目标沉默导致再也听不到别人 | **循环 2**：目标本批沉默 → `directedTakeover` 换台 |
| 无人回应无限空转 | 5 路 OR（`noReplyLimit × 2`，或 `0` 时 20 批硬上限） |
| 无限发射 | 发射监管到点**关闭发送总开关** |
| 过期发射写入 | `engineGen` + `txAbortGen` 双代次校验 |
| 未填呼号 / 未开总开关 | 第 1 层拒绝启动、第 2 层待命，UI 提示 |

### 2.9 完成与落库

- `finish()` 写 `QsoLogEntry`（**幂等**，一段只记一条，照 FT8CN `record.saved`），`consumeCompleted()` 一次性取走。
- **进入序号 4 / 5（发出 RR73 / 73）即落库**（照 FT8CN `setCurrentFunctionOrder` `:542` / `:550`），
  不弹确认、不关 TX。
- `startUtcMs` = 本段第一次发射 / 应答的时刻（FT8CN `startTime`；ADIF `QSO_DATE`/`TIME_ON`）；取不到时回退为完成时间。
  `utcMs` = 完成时间（ADIF `QSO_DATE_OFF`/`TIME_OFF`）：优先用**触发收尾那条报文**的时隙起点，
  其次是显式传入的时间，再次是最近一次带时间戳的调用。
- **会话内同一呼号只写一条**：`SessionViewModel.sessionSavedCalls` ＋ `saved` 标记去重。
  落库取**引擎快照**（进入序号 4 时那一刻的收发报告）。
- 完成即写 `WorkedIndex`（本波段，`WorkedIndex.plus`），使「已通联」过滤立即生效；`stop()` / 换波段清空集合。
- 跟踪名单在**通联完成后自动移除**该台（**有意偏离 FT8CN**：FT8CN 不会自动移除，见 §7）。

### 2.10 `Protocol` 时隙与报文长度

| 协议 | `Protocol` 枚举 | 时隙长度 | 报文波形时长 |
| --- | --- | --- | --- |
| FT8 | `Protocol.FT8`（ordinal 0） | 15000 ms | `messageMs = 12_640`（79 符号 × 160 ms） |
| FT4 | `Protocol.FT4`（ordinal 1） | 7500 ms | `messageMs = 5_040`（105 符号 × 48 ms，与 native `FT4_SYMBOL_PERIOD` 一致） |

- 时隙奇偶由 UTC 时刻判定（`slotParityOf`）；`EVEN=0` / `ODD=1`。
- **应答 / 被呼时固定到对方时隙的相反周期**（`pinToTargetSlot`）。
- 发射闸门（`txTick`）：只在我方周期、时隙起始窗口 `TX_START_WINDOW_MS = 1200L` 内、且该时隙尚未发射过时触发；
  `TxScheduler.minSendNowMs`（≥ `MIN_SEND_NOW_MS = 2500L`）为「立即发射」所需最小余量。
- 前导对齐等 `AUTO_PARITY_LEAD_MARGIN_MS = 500L`。
- **发射排定必须晚于「上一时隙的解码处理完」**（`txTick` 的 `lastDecodedSlotIndex` 闸门）：
  native 的 FT8 解码窗口要到时隙末尾（14.88 s）才出结果，而前导提前量（`planTx.startAtMs`）
  在时隙边界**之前** —— 若此刻就把本时隙的报文排定，发出去的其实是上一时隙的旧报文，
  新报文就被挤到下下个周期（真机两例：「对方给我 RR73 我没回 73」、「别人呼叫我，我却在发 CQ」）。
  **QSO 进行中**（`txTextAwaitsDecode()`，含「已发 CQ、等回应者」）一律等到该解码处理完再发，
  此时 `planTx` 走「就地发射」：前导仍完整，数据起点后移 ≈ 前导 + 解码延迟（默认 ≈ +0.3 s DT）；
  解码若改判了报文，`startCqInternal`/`answerInternal`/`startAutoTarget` 会把 `lastTxSlotIndex`
  复位，同一时隙仍发得出去。收尾报文 / 手动一次性发送的文本不随后续解码变化，不拦；
  解码停摆超过两个时隙也不拦（避免采集异常时把自己锁死）。
- 发射音频由 native 分块写，`TX_WRITE_CHUNK_FRAMES = 4096`（约 85 ms @48 kHz，见 `app/src/main/cpp/audio_engine.c`）；
  `TX_WRITE_CHUNK_FRAMES` **在 C 源码中**，Kotlin 侧无同名常量。

---

## 3. FT8CN 机制要点

一句话：**FT8CN QSO ＝「六步指令序列（order 1..6）＋ 单一常开自动程序 ＋ 两个安全阀」**，
序号驱动、角色隐式、应答强制、完成即落库。全部自动决策集中在 `FT8TransmitSignal.parseMessageToFunction()`
（`ft8transmit/FT8TransmitSignal.java:808`）。

### 3.1 三循环 `checkCQMeOrFollowCQMessage`（`:673`，优先级从高到低）

| 循环 | 语义 | 关键行 |
| :---: | --- | --- |
| 1 | **目标优先（防漂移）**：`to` 是我、`from == 当前目标`、非 73 的报文 → `setTransmit(order+1)` | `:678` |
| 2 | **任何人呼叫我一定应答 ★**：`to` 解析出我方呼号、且非 `73` → 立即按 `+1` 回包；不看是否已通联 / 显示筛选 / 是否已有目标。**指名给我的 `RR73`/`RRR` 也在此列**（只有 `73` 被排除） | `:697`、`:704-708` |
| 3 | **S&P 自动应答跟踪的 CQ**（仅当当前无目标）：`autoCallFollow` 开、`toCallsign != null`、`haveTargetCallsign()` 为假；遍历候选挑「是 CQ ＋（`autoCallFollow && autoFollowCQ` 或 我跟踪）＋ 未通联 ＋ 非自己」 | `:714`、`:718`、`:722`、`:728-746` |

- 循环 1 注释：多个台同时呼叫我方时，先锁定当前目标，**避免回复对象漂移**。
- `isExcludeMessage`（`:661`）过滤：与我发射同槽 / 异波段 / 排除字头。
- **`getNewTargetCallsign`（`:911`）**：在**当前解码批次**里挑新 CQ 台换台，条件＝「同波段 ＋ 是 CQ ＋ 非当前目标 ＋ 之前没通联成功过」；
  找不到就直接回 CQ（不在跟踪列表里找）。
- **三循环之外还有两个隐含阶段**：收到回复未完成 → 推进一格（`functionOrder = newOrder + 1`）；
  我在 CQ（`order==6`）时**永不放弃主叫、也不自增无回应计数**。

### 3.2 报文序号判据（`GeneralVariables.java`）

| 函数 | 行 | 命中条件 |
| --- | :--: | --- |
| `checkFun5` | `:443` | `extraInfo == "73"` |
| `checkFun4` | `:438` | `extraInfo ∈ {"RR73","RRR"}` |
| `checkFun3` | `:420` | 首字符 `R`、次字符非 `R`，且余下可 `parseInt`（如 `R-10`） |
| `checkFun2` | `:407` | 至少 2 位、可 `parseInt` 且 `!= 73`（如 `-10`） |
| `checkFun1` | `:399` | 匹配 `[A-Z][A-Z][0-9][0-9]` 且非 `RR73`，**或空串** |
| `checkIsCQ` | `Ft8Message.java:461` | `callsignTo` 首段是 `CQ` / `DE` / `QRZ` |
| `checkFunOrder` | `:391` | 先 `checkIsCQ`→6，否则 `checkFunOrderByExtraInfo` |
| `checkFunOrderByExtraInfo` | `:376` | 按 **5→4→3→2→1** 顺序判定，全不中返回 `-1` |

> 判据「从高到低依次命中」，所以 `RRR` 归 order 4、`73` 归 order 5 而不会落进 order 2。

### 3.3 六步报文与报告来源（`getFunctionCommand`，`:251`）

| order | 报文 | 语义 | 报告来源 |
| :---: | --- | --- | --- |
| 1 | `<对方> <我> <4位网格>` | 应答 CQ / 首次呼叫 | 进入前 `resetTargetReport()`（`:255`） |
| 2 | `<对方> <我> <±dd>` | 信号报告 | `toCallsign.snr`（`:260`，发送前不重测） |
| 3 | `<对方> <我> R±dd` | R 报告 | `toCallsign.snr`（`:266`，与 order 2 同一个值） |
| 4 | `<对方> <我> RR73` | 收尾 | — |
| 5 | `<对方> <我> 73` | 收尾 | — |
| 6 | `CQ [修饰符] <我> <网格>` | 主叫 | `resetTargetReport()`（`:279`）；修饰符取 `toModifier`（`:282`） |

- `generateFun()`（`:293`）生成 `1..6`，**唯独 order 6 只生成一条**（`:298-300` 的 `break`）；
  并 `noReplyCount = 0`（`:295`）。
- 进入 order 2/3 时顺手 `sentTargetReport = toCallsign.snr`，供落库取值。

### 3.4 两个安全阀

| 机制 | 位置 | 说明 |
| --- | --- | --- |
| **发射监管** `launchSupervision` | `GeneralVariables.java:150`、`:192`、`:365`；`FT8TransmitSignal.java:128` | 默认 **10 分钟**（`DEFAULT_LAUNCH_SUPERVISION = 10*60*1000`）；档位 `0(忽略) / 5 / 15 / 25 / … / 95` 分钟（`LaunchSupervisionSpinnerAdapter.java:28`）。超时 → `setActivated(false)` |
| **监管计时基准** | `GeneralVariables.java:352` | 自 `resetLaunchSupervision()` 起的**绝对计时**；人工操作（呼叫/回复/发 CQ）会复位 |
| **无回应限制** `noReplyLimit` | `GeneralVariables.java:194` | `0..30`；判据只在**我发过 RR73（order 4）**之后生效 |
| **无回应计数** `noReplyCount` | `GeneralVariables.java:196` | `generateFun()` / 收到回复时清零；**空批不计数**（`FT8TransmitSignal.java:812`）、**弱信号（深度解码）不累加** |
| **RR73 三重兜底** | `FT8TransmitSignal.java:834`、`:838`、`:840` | 阈值×2 / 对方已转呼别人 / 忽略时 `>20` |
| **深度解码不触发自动** | `MainViewModel.java:319` | `isDeep` 的解码不驱动自动程序 |

完成判定为 **5 路 OR**（任一命中即收尾回 CQ）：

1. `newOrder == 5`（对方发 73）；
2. `order == 5 && newOrder == -1`（我发过 73、对方沉默）；
3. `order == 4 && noReplyLimit > 0 && noReplyCount > noReplyLimit*2`；
4. `order == 4 && checkTargetCallMe > 1`（对方开始呼别人）；
5. `order == 4 && noReplyLimit == 0 && noReplyCount > 20`。

> **本机照搬全部 5 路**（见 §2.6）：第 1 路同时补上了「指名给我的 `RR73` 也要接 → 回 73」，
> 第 3~5 路在引擎里判定并用 `QsoProgress.gaveUp` 告诉第 2 层「该换台了」。

### 3.5 跟踪呼号与两个开关的真实语义

- **`followCallsigns` 跟踪名单**：独立 SQLite 表（`DatabaseOpr.java:142`），**只由用户手动**加入
  （呼叫列表 / 发射界面 / 地图 `GridTrackerMainActivity`），**永久保存**，且**只能在局域网 Web 后台删除**
  （`LogHttpServer.java:444`）或清缓存时清空。名单里的台**不受 `autoFollowCQ` 限制**，一定进列表、并在
  `autoCallFollow` 开时被自动呼叫。`callsignInFollow()`（`:327`）判是否在名单内。
- **`autoFollowCQ`**（`GeneralVariables.java:205`，默认 true）：**自动跟踪 CQ**——把解码到的 CQ
  **推送到「呼叫」列表**；帮助文件 `auto_follow_help.txt` **明确说明「不写入跟踪名单」**。
- **`autoCallFollow`**（`:206`，默认 true）：**自动呼叫跟踪的呼号**——是否自动去呼叫 CQ，是**总闸**
  （`:714` 关掉即直接返回）；开启且 `autoFollowCQ` 也开 ⇒ **任何**未通联 CQ 台都可呼叫；
  `autoFollowCQ` 关 ⇒ **只**呼叫跟踪名单里的 CQ 台（`FT8TransmitSignal.java:733-737`）。
- **`checkQSLCallsign()`（`:273`）**：是否在本波段已通联（`QSL_Callsign_list`）；`checkQSLCallsign_OtherBand()`（`:283`）为其它波段。

> **本机偏离**：`autoFollowCQ`（本机 `autoAddCqToFollow`）除作 CQ 候选闸门，还会把**本波段未通联**的
> CQ 台**自动写进 ⭐ 跟踪名单**（FT8CN 不写名单）。详见 §5.1。

### 3.6 关键行号索引

**人工操作不会暂停自动程序**：FT8CN 没有「手动接管（paused）」概念，人工只改发射目标 / 指令，下一批解码照旧跑自动程序（入口如 `restTransmitting()` `:982` 重设 order 6、`setActivated(false)` `:945` 关发射、`resetLaunchSupervision()` `GeneralVariables.java:352` 复位监管）。

`parseMessageToFunction` `FT8TransmitSignal.java:808`；`getFunctionCommand` `:251`；`generateFun` `:293`；`doComplete` `:475`；
`checkCQMeOrFollowCQMessage` `:673`；`getNewTargetCallsign` `:911`；`setActivated` `:945`；`resetToCQ` `:1005`；
`setCurrentFunctionOrder` `:542`；`checkTargetCallMe` `:578`；`checkFunctionOrdFromMessages` `:604`；
`doOnSecTimer` `:126`；决策外层闸门 `MainViewModel.java:318`；`updateQSlRecordList` `:753`。

### 3.7 其它要点与已知风险（本机对应规避见 §6 / §7）

- **发射时隙**：`sequential = (toCallsign.sequential + 1) % 2`（`:231`），即「目标出现时隙的相反槽」；
  时隙 `UtcTimer.sequential(utc) = (((utc/1000)/15) % 2)`（FT8；FT4 7.5 s，`UtcTimer.java:253`），
  `Ft8Message.getSequence()` 加 750 ms 半槽补偿（`Ft8Message.java:324`）；心跳每 100 ms 检查秒边界（`UtcTimer.java:166`）。
- **解析分级**：前置过滤（`myCall` 长度 `< 3` / 空批 / `msgList[0].seq == sequential`，按**首条**判定）
  → 找「对方→我」的回复序号（命中即清零 `noReplyCount`）→ `updateQSlRecordList`（order 4/5 写日志）
  → 完成判定（5 路 OR，命中回 CQ）→ 未完成则 `functionOrder = newOrder + 1`
  → 无回复走 `checkCQMeOrFollowCQMessage`（order 6 时永不放弃、也不计数）→ 无回应累计（非空批、非弱信号）与换台。
- **风险点**：
  1. 外层 2 s 时窗闸门（`MainViewModel.java:318`）会漏报文 → 「有人叫我却不回」。本机**无此闸门**。
  2. 深度解码被排除（`:319`）。本机同规则（`deep` 恒 `false`，占位）。
  3. `msgList[0].getSequence() == sequential` 按**首条**整批丢弃 → 同批夹着对我的回复会被误伤。本机**逐条**过滤。
  4. `checkFunctionOrdFromMessages` 只认 `from == 当前目标`；复合呼号仅在目标带 `/` 时 `contains`。本机双向宽松。
  5. `getNewTargetCallsign` 只看本批解码 → 换台命中率受限（本机同）。
  6. `restTransmitting()` / `resetToCQ()` 的时隙「双重取反」疑点，以实机为准。

---

## 4. 本机实现

### 4.1 模块职责

| 文件 | 职责 |
| --- | --- |
| `qso/AutoProgram.kt` | 第 2 层：`AutoProgramSettings`（四项）、`SUPERVISION_MINUTES`、`NO_REPLY_LIMIT_RANGE`、`AutoTarget`/`AutoTargetKind`（含 `RR73`）、`AutoAction` 封闭接口、`AutoProgramSelector.collect` / `toTarget` / `rank`、`AutoScheduler` |
| `qso/QsoEngine.kt` | 第 1 层：`QsoState`（含 `WAIT_FINAL`）、`QsoProgress`（含 `order`/`noReplyCount`/`advanced`/`gaveUp`）、六步 `Step`、5 路 OR 收尾判据、报告快照、`finish()`/`consumeCompleted()` |
| `qso/FunctionOrder.kt` | 序号判据：`checkFun1..5` / `of` / `byExtraInfo` / `NONE` / `CQ` |
| `qso/TxCompose.kt` | `TxMessageKind`（六步）、`DEFAULT_CQ_PREFIXES`、`TxScheduler`（`MIN_SEND_NOW_MS` / `minSendNowMs`） |
| `qso/Message.kt` | `ParsedMessage`（含 `payload`）/ `MessageParser` / `CallMatch`（`shortCall` / `isCallingMe` / `isFrom`） |
| `qso/DecodeFilter.kt` / `qso/DecodeHighlight.kt` | 显示筛选 / `WorkedIndex` + `plus` |
| `ui/SessionViewModel.kt` | `pollOnce` 驱动、`replyTo`、`setTxEnabled`、`resetSupervision`、`applyQsoProgress`（按 `gaveUp` 派发）、`startAutoTarget`（五类目标）、`stopAutoBySupervision` |

### 4.2 `AutoScheduler` 决策

- `AutoAction`：`SendCq` / `AnswerCq(target)` / `HandleDirected(target)` / `Listen` / `Stop(reason)` / `None`。
- **`onDecoded`（最高优先顺序）**：
  1. 监管超时 → `AutoAction.Stop`；
  2. `protectedStop` → `None`；
  3. `collect` 收集候选；
  4. **定向候选非空 → `HandleDirected(rank 首个)`**（一律应答，最高优先，不受任何开关 / 筛选 / 已通联影响）；
  5. 无进行中 QSO 时：`autoCallFollow && 有未通联 CQ 候选` → `AnswerCq(首选)`，否则 `SendCq`。
- `rank`：**照 FT8CN 不做 DX / 区域分级**——候选保持解码顺序（跨时隙的先后由「一个时隙一批」的调用方式决定），
  调用方取 `.first()` 即「本批里最先解到的那台」。定向报文始终优先于 CQ。
- `enable` / `disable` / `resetSupervision` / `checkSupervision` / `markProtectedStop` 管理监管计时与 `protectedStop`。
- `onQsoFinished`：先处理队列（`HandleDirected`），队列空了才 `SendCq`（FT8CN `resetToCQ`）。
- `onTargetGaveUp`：先清空队列，再**优先换台**（有未通联 CQ 就 `AnswerCq` 首个；有别的定向台就应答），
  没有才 `SendCq`（FT8CN `getNewTargetCallsign`）。

### 4.3 候选收集 `AutoProgramSelector.collect`

| 报文 | 类型 | 是否入选 |
| --- | --- | --- |
| 对方的 `CQ` | `CQ` | 仅当 `autoAddCqToFollow` 开、**或**发信人在「跟踪名单」里，**且**本波段未通联（`checkQSLCallsign` 口径）；**不套用显示筛选** |
| 发给我的网格 | `CALL` | **一律入选** |
| 发给我的报告 | `REPORT` | **一律入选** |
| 发给我的 `R<报告>` | `ROGER` | **一律入选** |
| 发给我的 `RR73` / `RRR` | `RR73` | **一律入选**（回 73 收尾，照 FT8CN 只排除 `73`） |
| `73`（`ParsedMessage.is73`） | — | 不作为新 QSO 起点 |
| 自听（`from == myCall`） | — | 跳过 |
| `deep`（弱信号二次解码） | — | 跳过（当前 native 单遍解码 ⇒ 恒 `false`，占位） |
| 显式「忽略呼号」`ignoredCalls` | — | 跳过（用户主动屏蔽，不是显示筛选） |

同呼号去重，保持解码顺序。`toTarget(msg, myCall)` 是单条版本，供解码菜单「呼叫 / 回复」使用，
同样**不受**两个开关 / 已通联 / 显示筛选影响；`RR73` 返回 `AutoTargetKind.RR73`、`73` 返回 null。

### 4.4 忙时换台（`advanced` + `directedTakeover`）

```
本次 onDecoded 未推进（advanced == false）且 QSO 仍在进行且 txEnabled
  → directedTakeover(本批, 当前目标, filter, worked)   // FT8CN 循环 2
  → 命中「非当前目标」的定向台 ⇒ 立即换台
  → 未命中 ⇒ 本批的收尾 / 无回应判定已在引擎里跑完（applyQsoProgress 按 gaveUp 派发）
```

- `directedTakeover` **不看**两个开关、不看是否已有目标；只排除当前目标自身（`CallMatch.isFrom`）。
  本批的 `73` 不作为起点，`RR73`/`RRR` 可以（回 73 收尾）。
- 当前目标**有**回应（`advanced == true`）时**不**调用 → 「有目标不换台」由调用方保证。

### 4.5 `SessionViewModel.pollOnce`

1. 取本批解码（新→旧）→ 汇入消息流。
2. 刷新状态读数（节流 + 量化后发布）。
3. **每个接收时隙结束**：
   - **逐条**过滤「落在我方刚发射那个时隙」的解码（不整批丢弃）；
   - 先检查发射监管，触发 → `stopAutoBySupervision`（关闭 `txEnabled`）；
   - `st.qso.active && !awaitingResponders`：`qsoEngine.onDecoded(incoming, utcNow)` →
     `applyQsoProgress(p, utcNow, incoming)`；若 `!advanced` 且仍 `txEnabled` → `directedTakeover`（换台）；
   - `awaitingResponders`（已发 CQ 等回应者）或 `!active && txEnabled`：`runAutoProgram`。
4. 瀑布行流、`txTick()`（发射调度）、前台服务通知。

### 4.6 人工覆盖（第 3 层）

- 解码列表长按菜单：**呼叫 / 回复**（`replyTo`）、**查看日志**、**跟踪 / 取消跟踪**、复制消息、忽略该呼号；
  **不加手动 73**。
- 顶栏左滑呼叫、就地发射（`TxScheduler`）。
- 设置页四项自动程序设置（`ui/AutoProgramDialog.kt`）。
- **唯一闸门**是「发送总开关」`txEnabled`（默认关、不持久化）：关闭即停、打开即恢复；
  **直接生效、不弹确认框**（`AutoEnableConfirmDialog` 已删）。
- 人工操作**不暂停**自动程序，只 `resetSupervision` 复位监管计时。

### 4.7 落库与收口

- `applyQsoProgress` 把状态机进度同步到 UI、写日志并收口第 2 层调度：
  - `p.gaveUp == true`（序号 4 的三路兜底之一命中）→ `scheduler.onTargetGaveUp(...)`（换台 / 回 CQ）；
  - 正常结束且 `txText == null` → `scheduler.onQsoFinished(...)`；
  - 正常结束但还有最后一条 `RR73`/`73` 要发 → 置 `pendingAutoFinish`，等它**真正发出去**后
    在 `txTick` 里收口；期间 `pollOnce` 也不让第 2 层启动新 QSO。
- `QsoEngine.onTransmitted(sentText, utcMs)` 只有在「刚发出去的就是当前应发报文」时才在 DONE/FAILED 下清空 `txText`：
  上一时隙抢发出去的旧报文（如重复的 `R 报告`）发完不得清掉还没发出去的 `73`/`RR73`。
  真机现象（2026-09-27 双机 logcat）：「对方给我 RR73，我不回 73，反而起 CQ」＝
  ① `txTick` 用旧报文抢占了该时隙；② 旧报文发完时清空了收尾的 `73`；③ 第 2 层 `SendCq` 又覆盖了它。
  另外 `onTransmitted` 还兼任「我方序号 4/5 即落库」（FT8CN `setCurrentFunctionOrder` `:550`）。
- `QsoLogEntry` → `QsoEntity`（Room 表 `qso`）立即写库：`theirCall`/`theirGrid`、`myCall`/`myGrid`（快照）、
  `utcMs`（完成）/`startUtcMs`（起始）、`band`/`freqHz`/`mode`、`reportSent`/`reportReceived`、`qslRcvd`/`lotwRcvd`、
  `comment`（`Distance: xxx km, Qso by Ft8Vox`）。
- ADIF 起止时间：导入 `TIME_ON`→`startUtcMs`、`TIME_OFF`（缺省回退 `TIME_ON`）→`utcMs`；导出仅在 `utcMs > startUtcMs` 时写 OFF；
  老记录 `startUtcMs = 0` 回退完成时间，往返不变。

---

## 5. 设置项与参数表

### 5.1 `AutoProgramSettings` 四项（默认值以源码为准）

| 设置 | `AutoProgramSettings` 字段 | 取值 / 常量 | 默认 |
| --- | --- | --- | :---: |
| 发射监管 | `supervisionMinutes` | `SUPERVISION_MINUTES = listOf(0) + (5..95 step 10)`（`0`＝不监管） | 10 |
| 无回应次数（换台阈值） | `noReplyLimit` | `NO_REPLY_LIMIT_RANGE = 0..30`（`0`＝用内置硬上限 20 个批次；否则判据为 `> ×2`） | 0 |
| 自动跟踪 CQ | `autoAddCqToFollow` | 开关（CQ 候选闸门 + **自动写跟踪名单**，通联后移除） | true |
| 自动呼叫 CQ 台 | `autoCallFollow` | 开关（总闸） | true |

持久化键：`auto_supervision_minutes` / `auto_no_reply_limit` / `auto_follow_cq` / `auto_call_follow`；
旧键常量保留但不再读写。

> **2026-10-01**：「自动跟踪 CQ（本波段）」恢复**自动写跟踪名单**（`qso/FollowRoster.kt` +
> `SessionViewModel.autoCollectCqToFollow`），并在该台**通联完成后自动移除**。这是**有意偏离**
> FT8CN（其 `autoFollowCQ` 只推送到「呼叫」列表，帮助文件明确「该呼号不会被长久保存到关注的呼号
> 数据库中」）。
> `AppSettings.autoFollowOrder` 记录**自动收录**的顺序（最近在前、恒为 `followCalls` 子集），
> 用于超出 `FollowRoster.AUTO_MAX = 100` 时淘汰最早收录的，以及 ⭐ 列表里给自动加入的行打
> 「自动」标记；**手动跟踪**的呼号不在此列，永不被淘汰。

### 5.2 两个开关的语义（照 FT8CN）

- **自动跟踪 CQ（`autoAddCqToFollow`）**：把关「哪些 CQ 台能进候选被自动呼叫」——
  开 ⇒ 任何**本波段还没通联过**的 CQ 台都纳入候选（波段口径照 FT8CN `checkQSLCallsign` 的 `where band=?`；
  跨波段通联过的台在本波段仍算没通联过），**并自动写进跟踪名单**（`FollowRoster`：跳过自己 / 已忽略 /
  本波段已通联；同批去重；超 `AUTO_MAX = 100` 淘汰最早收录的）；关 ⇒ 不再新增，**只**纳入跟踪名单里的 CQ 台。
  **写名单是有意偏离 FT8CN**（其 `autoFollowCQ` 只推送到「呼叫」列表）。
- **自动呼叫 CQ 台（`autoCallFollow`）**：是否**真的去呼叫**候选里的 CQ 台（**总闸**，FT8CN `:714`）；
  关掉后只回应定向呼叫 ＋ 自己发 CQ。
- **跟踪名单** ＝ `AppSettings.followCalls`，两个来源：解码列表长按「跟踪 / 取消跟踪」手动加入；
  「自动跟踪 CQ」开着时自动写入本波段未通联的 CQ 台（顺序记在 `autoFollowOrder`）。
  筛选条最右 **⭐** 打开面板，列表里**左滑＝呼叫、右滑＝取消跟踪**，右上「全部清除」。
  它**不是解码筛选**；照 FT8CN，名单里的台**不受** `autoAddCqToFollow` 限制（开关关掉时仍会被自动呼叫）。
  **通联完成后自动移除**该台（**有意偏离 FT8CN**）。

### 5.3 关键常量与默认值

| 参数 / 常量 | 值 | 位置 / 说明 |
| --- | --- | --- |
| 发送总开关 `txEnabled` | `false`（不持久化） | 自动程序的**唯一闸门** |
| `QsoEngine.NO_REPLY_HARD_LIMIT` | `20` | `noReplyLimit == 0` 时，序号 4 的兜底批次上限（FT8CN `:840`） |
| `FollowRoster.AUTO_MAX` | `100` | 自动收录的上限，超出淘汰最早收录的（`qso/FollowRoster.kt`） |
| `TX_START_WINDOW_MS` | `1200L` | 时隙起始的发射窗口（`ui/SessionViewModel.kt`） |
| `MIN_SEND_NOW_MS` | `2500L` | 立即发射所需最小余量（`TxCompose.TxScheduler`） |
| `AUTO_PARITY_LEAD_MARGIN_MS` | `500L` | 前导对齐余量 |
| `TX_WRITE_CHUNK_FRAMES` | `4096`（帧，约 85 ms @48 kHz） | native `app/src/main/cpp/audio_engine.c` |
| `Protocol.messageMs` | FT8 `12_640` / FT4 `4_480` | `engine/Ft8Engine.kt` |
| 时隙长度 | FT8 `15000` / FT4 `7500` ms | `SessionViewModel.slotMsOf` |
| 报告范围 | `-24 … +30` dB | `reportFromSnr` |
| 同频发射 `sameFreqTx` | `true` | 开＝红线跟到目标频率；关＝保持设定频率 |

> **没有**「自动化程度 / AutoSeq 等级 / AutoMode / 只要新东西 / autoRxFilter / 半自动收尾」这些参数——FT8CN 没有，本项目也不做。

---

## 6. 与 FT8CN 对照表

| 维度 | FT8CN | 本机 | 差异 |
| --- | --- | --- | --- |
| 六步序列 | 1 网格 / 2 报告 / 3 R / 4 RR73 / 5 73 / 6 CQ | 同（`QsoEngine.order`、`TxDrawer` 六格、`TxCompose` 六种） | 照搬 |
| 序号判据 | `checkFunOrder`（5→4→3→2→1，先判 CQ） | `FunctionOrder` 逐条移植（多认 6 位扩展网格） | 照搬（+扩展） |
| 推进规则 | 对方序号 + 1 | 同 | 照搬 |
| 收尾判据 | 5 路 OR（`order 4/5` 的三种兜底） | 同，**在引擎里**判定并用 `gaveUp` 交给第 2 层 | 照搬 |
| 序号 4 之后 | 仍等对方 73，等不到重发 RR73 / 兜底 | 同（`QsoState.WAIT_FINAL`，进入即落库） | 照搬 |
| 自动化档位 | 无档位；两个布尔开关 | 单档常开 + 四项设置；删 `AutoMode` | 照搬 |
| 角色 | 隐式（`order==6` 即主叫） | 同（`order`/`role` 只供 UI） | 照搬 |
| 定向一律应答 | 循环 2，只排除 `73` | 定向分支优先级最高，**逐条**判 `addressedTo(myCall)`，`RR73` 也接 | 超集 / 加固 |
| 目标优先（防漂移） | 循环 1 | 保留 | 照搬 |
| 有目标不换台 | 循环 3 前置 `haveTargetCallsign()` | 保留（仅当目标**本批有回应**时才不换台） | 照搬 |
| 忙时目标沉默 | 循环 2：目标无回应 → 应答其他呼叫我方者 | `QsoProgress.advanced` + `AutoScheduler.directedTakeover` | 照搬 |
| 已通联 CQ 台不自动应答 | `checkQSLCallsign` 过滤 | 硬编码跳过本波段已通联；**定向报文仍一律应答** | 照搬 |
| 无回应计数 | 按解码批次；空批不计 | 同；序号 6 阶段不计数 | 照搬 |
| 换台动作 | 超限 → 本批找 CQ → 否则回 CQ | 同（`onTargetGaveUp`） | 照搬 |
| 监管超时动作 | `setActivated(false)` | 关**发送总开关**（`txEnabled=false`） | 照搬 |
| 落库时机 | 进入序号 4/5 即时 | 同（`setOrder(4/5)` 与 `onTransmitted` 双保险） | 照搬 |
| 落库去重 | `QslRecordList` + `saved` 标记 | 会话内一条 + `saved` 标记 | 照搬 |
| 报告取值（我方发） | `toCallsign.snr`（序号 2、3 同一值） | 同（固定第一次测得的强度） | 照搬 |
| 报告取值（对方给） | 命中一条就覆盖（`receiveTargetReport`） | 同（序号 2/3 都更新） | 照搬 |
| 已通联标记 | `addQSLCallsign`（本波段表） | 完成即写 `_worked`（跨波段，供高亮/筛选）与 `workedCallsByBand`（本波段，供自动程序） | 照搬 |
| 日志字段 | 起止时间 / 波段 / 基频 / 收发报告 | 补齐 `startUtcMs` + 保留 `Distance: … km, Qso by Ft8Vox` | 照搬 |
| 深度解码 / 弱信号 | `isDeep` 不驱动自动、弱信号不计无回应 | 预留 `DecodeResult.deep` 钩子（当前恒 `false`），同规则 | 照搬（占位） |
| 手动接管 paused | 无此概念，人工不暂停自动 | 取消 `paused`；人工只改目标 / 指令 | 照搬 |
| 发射确认框 | 无 | 删 `AutoEnableConfirmDialog`；`txEnabled` 即唯一闸门 | 照搬 |
| 选台排序 | 无 DX 分级 | `rank` 保持解码顺序（不再按 DX / 区域加权） | 照搬 |
| 两个 CQ 开关 | `autoFollowCQ`＝推送到呼叫列表（**不写名单**）；`autoCallFollow`＝是否自动呼叫（总闸） | 名字对应（`autoAddCqToFollow` / `autoCallFollow`）；`autoAddCqToFollow` 额外**写跟踪名单** | 开关口径照搬，写名单有意偏离 |
| 跟踪名单写入 | 只在用户手动操作时写入 | 手动 + 「自动跟踪 CQ」自动写入本波段未通联的 CQ（`FollowRoster`） | 有意偏离 |
| 跟踪名单移除 | 不自动移除 | 通联完成后自动移除该台 | 有意偏离 |
| 跟踪名单删除 | 只能在局域网 Web 后台 / 清缓存 | ⭐ 面板内即可删（含「全部清除」） | 有意偏离 |
| 复合呼号匹配 | 目标带 `/` 时 `contains`（单向） | **双向**（任一方带 `/` 即 `contains`） | 超集 / 加固 |
| 2 秒时窗闸门 | 有（漏应答根因） | **无**（逐条判定，晚到 / 大批量也能触发） | 有意偏离（避隐患） |
| 首条整批丢弃 | 有 | **逐条**自听过滤（仅丢本槽那一条） | 有意偏离（避隐患） |
| 6 位扩展网格 | `checkFun1` 不认 → 不推进 | 认（照 `GRID6`）；`MessageParser` 本就解析 6 位网格 | 有意偏离（加固） |
| 我发 `73` 后对方沉默（第 2 路） | 兜底收尾 | 结构上不可达（我方序号 5 即 `DONE`），无需补码 | 等价 |

**FT8CN 有意没有、本机也不引入的**：Hound/Fox、逐条精确 DXCC 分级、多档 AutoSeq、完成确认卡。

---

## 7. 有意偏离清单

1. **落库取引擎快照**：`QsoLogEntry.reportSent/reportReceived` 取「进入序号 4 那一刻」的值，
   FT8CN 是落库前回查历史报文（两者通常一致）。
2. **复合呼号双向宽松**：`CallMatch.isFrom` 任一方带 `/` 即 `contains`，防对方改用 `/P` 时漏应答。
3. **无 2 s 时窗闸门**：逐条判定，晚到 / 大批量解码也能触发应答（FT8CN 的漏应答根因）。
4. **逐条自听过滤**：只丢「落在我方发射那个时隙」的解码，保留同批中对方的回复（不整批丢弃）。
5. **跟踪名单可从 App 内删除**：存 `AppSettings.followCalls`，App 内即可删；FT8CN 是独立表、只能在局域网 Web 后台删。
6. **6 位扩展网格也算序号 1**：`FunctionOrder.GRID6`；FT8CN `checkFun1` 只认 4 位，收到 6 位网格会解析不出序号而卡住。
7. **深度解码占位**：`DecodeResult.deep` 当前恒 `false`（native 单遍解码），仅按 FT8CN 口径预留「不驱动自动」的钩子。
8. **「自动跟踪 CQ」自动写跟踪名单**：本波段未通联的 CQ 台由 `FollowRoster` 写入 `followCalls`，
   超 `AUTO_MAX = 100` 淘汰最早收录的（手动跟踪的不淘汰）；FT8CN 的 `autoFollowCQ` 只把 CQ 推送到
   「呼叫」列表、不写名单。
9. **跟踪台通联完成后自动移除**：该台与本机通联落库后从名单（含 `autoFollowOrder`）剔除；FT8CN 不会自动移除。

> **2026-09-30 从偏离清单里移除的机制**（改为照搬 FT8CN）：
> 收敛阶梯（含「收到纯报告而己方已回过 R → 直接 RR73」的 4a）、
> CQ 修饰符/DX 区域分级选台（`modifierPriority` / `areaOfGrid`）、
> 「Tx3 的 R 报告复用快照」（现在与 FT8CN 同为「首次测到的强度」）。
>
> **2026-10-01 重新加回**（用户决定，属有意偏离）：
> 「自动跟踪 CQ」写跟踪名单（`FollowRoster`）、「跟踪台通联完成后自动移除」。

---

## 8. 明确不做

以下为**当前明确不做**的清单（FT8CN 没有，或本机不引入）：

- Hound / Fox 模式。
- 逐条精确 DXCC 分级（选台不做精确 DXCC）。
- FST4 等其它协议。
- 自动窄带过滤。
- AutoSeq 档位（`AutoMode` / `AUTOSORT` / `DecodeTiming` / 工作模式单选等）。
- DX 分级选台（照 FT8CN 不做）。
- LoTW 呼号集 / `LoTW` 相关。
- Auto RX filter。
- QSO history。
- 半自动 Log 确认卡。
- 看门狗（watchdog）。
- `AutoEnableConfirmDialog`（发送总开关确认框）。
- `retryLimit` / `giveUpAfterRetry` / `QsoProgress.retries` 重试体系（已退役）。
