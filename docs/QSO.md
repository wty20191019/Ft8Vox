# Ft8Vox QSO 自动系统设计

> 本文件由 `docs/NEW_QSO_.md`、`docs/FT8CN-QSO.md`、`docs/FT8CN_QSO_PLAN.md` 合并精简而来
> （**这些源文件已删除，历史版本见 git 记录**），
> 只保留当前生效的设计、算法口径、参数与「有意偏离」清单；过程叙述、逐条历史日志、逐行剖析已剔除。
> 对标基准：FT8CN（Android / Java）。

---

## 1. 总览

**一句话**：本机的 QSO 自动系统 ＝ 「**六步指令序列（order 1..6）＋ 单档常开自动程序 ＋ 两个安全阀**」，
角色隐式、应答强制、完成即落库，目标是与 FT8CN 同口径把通联跑完且任一阶段都不跑死。

**三层结构**：

| 层 | 名称 | 职责 | 主要实现 |
| --- | --- | --- | --- |
| 第 1 层 | 定向应答 / 序列引擎 | 持有**一段** QSO 的全部状态，按收到的报文推进六步序列，完成时产出日志 | `qso/QsoEngine.kt` |
| 第 2 层 | 自动选台 / 调度 | 收集本批候选、排序、决定「应答定向 / 换台 / 应答 CQ / 发 CQ」，跑两个安全阀；**不直接发射** | `qso/AutoProgram.kt` |
| 第 3 层 | 人工覆盖 | 手动呼叫 / 回复 / 忽略 / 查看日志；只改目标、复位监管，**不暂停**自动程序 | `ui/SessionViewModel.kt`、`ui/DecodeList.kt` |

**核心模型三件套**：

1. **六步 order 序列**——所有转移由**报文内容**驱动，角色由报文动态推断（隐式）。
2. **单档常开自动程序**——没有 AutoSeq 档位 / AutoMode / paused；**唯一闸门是「发送总开关」`txEnabled`**。
3. **两个安全阀**——**发射监管**（超时关闭 `txEnabled`）、**无回应限制**（超限换台 / 回 CQ）。

**关键约定**：

- **「一定应答」**：发给我方的定向报文一律入选并优先处理，不受两个开关、显示筛选、已通联影响
  （只有显式「忽略呼号」`ignoredCalls` 会挡）。
- **角色隐式**：`QsoRole`（`CALLER`/`RESPONDER`）只供 UI，不再驱动任何转移。
- **人工不暂停**：没有 `paused` / `manualIntervention`；人工操作只是改目标 + `resetSupervision`。
- **报文方向**：FT8 定向报文 = `<收方> <发方> <载荷>`；`K1ABC W2XYZ -08` ＝「发给 K1ABC、由 W2XYZ 发出」，
  我收到的报文里**我的呼号在第一位**。
- **时隙奇偶**：`EVEN(0)` = 00s/30s 起；`ODD(1)` = 15s/45s 起（FT8）。我发哪个周期由本台决定。
- **信号报告**：`-24 … +30 dB`，**一律本地实测值**，绝不回显对方给我的报告。

**三条硬要求**：

1. **必须能自动切换自己是 CQ 方还是应答方**（角色隐式，由报文推断）。
2. **两台机器不得在任一阶段跑死**（收敛阶梯 + 逐条自听过滤 + 两个安全阀）。
3. **对方呼叫我方时必须应答**（定向报文一律入选、最高优先）。

**实现映射**：

| 概念 | 实现位置 |
| --- | --- |
| 第 1 层 序列引擎 | `qso/QsoEngine.kt`（`QsoEngine` / `QsoState` / `QsoProgress` / `QsoLogEntry`） |
| 第 2 层 调度选台 | `qso/AutoProgram.kt`（`AutoScheduler` / `AutoProgramSelector` / `AutoProgramSettings` / `AutoTarget` / `AutoAction`） |
| 第 2 层 设置 UI | `ui/AutoProgramDialog.kt`（四项，照 FT8CN） |
| 第 3 层 人工覆盖 | `ui/SessionViewModel.kt`（`replyTo` / `setTxEnabled` / `resetSupervision`） |
| 报文解析 | `qso/Message.kt`（`ParsedMessage` / `MessageParser` / `CallMatch`） |
| Tx 槽与 CQ 前缀 | `qso/TxCompose.kt`（`TxMessageKind` / `DEFAULT_CQ_PREFIXES` / `TxScheduler`） |
| 显示筛选 / 已通联索引 | `qso/DecodeFilter.kt`、`qso/DecodeHighlight.kt`（`WorkedIndex`） |
| 忙时换台 | `AutoScheduler.directedTakeover`（FT8CN 循环 2） |

---

## 2. 报文序列与状态机

### 2.1 六步指令序列（`QsoEngine.Step`，`order` 按源码取值）

| order | 我方发出的报文 | `Step` 常量 | 触发 / 语义 | 报告来源 |
| :---: | --- | --- | --- | --- |
| 1 | `<对方> <我> <4位网格>` | `GRID` | `startResponderQso`（我应答 CQ）/ `startCallerQso` 首包 | 进入前复位收发报告 |
| 2 | `<对方> <我> <±dd>` | `REPORT` | `startCallerQso`（我主叫 / 被呼方补发） | **每次重测最新** |
| 3 | `<对方> <我> R<±dd>` | `ROGER` | `respondToReport` / 收敛阶梯 4b | **复用 Tx2 已发出的快照** |
| 4 | `<对方> <我> RR73` | `RR73` | 收敛阶梯 3 / 4a | — |
| 5 | `<对方> <我> 73` | `SEVENTY3` | 收敛阶梯 1 / 2 | — |
| 6 | `CQ [修饰符] <我> <网格>` | `CQ` | `startCq` | 进入前复位收发报告 |

- `QsoProgress.order` 对外暴露当前序号（`0` = 无）；`TxCompose.TxMessageKind` 的 `order` 与之一致
  （`GRID=1 / REPORT=2 / ROGER=3 / RR73=4 / SEVENTY_THREE=5 / CQ=6`，`CUSTOM=0`）。
- CQ 前缀（`AppSettings.cqPrefixes` / `cqPrefixIndex`，抽屉 4×2 单选）由 `startCq(utcMs, cqPrefix)` 传入，
  渲染为 `CQ <前缀> <我> <网格>`（空串＝普通 CQ）；`TxCompose` 侧由 `DEFAULT_CQ_PREFIXES` + `compose(..., cqPrefix)` 提供同口径。
- `TxCompose.kindOf()` 映射：`isCq→6`、`grid→1`、`report→2`、`isRoger→3`、`isRr73→4`、`is73→5`。

### 2.2 `QsoState`（对外只读状态）

`IDLE` / `WAIT_REPLY`（已发本轮报文，等回复）/ `WAIT_REPORT`（已发报告，等 R）/ `WAIT_RR73`（已发 R，等 RR73/73）/
`DONE` / `FAILED`。`QsoProgress.active = state ∉ {IDLE, DONE, FAILED}`。
`FAILED` 已无重试耗尽来源，仅保留枚举供 UI。

### 2.3 收敛阶梯（`QsoProgress.advanced` 语义）

**设计**：对**任意非终态**都接受任意形态报文，按下列优先级算出下一步；角色由报文内容动态推断。
（早期「角色优先固定脚本」会在 `WAIT_RR73` ↔ `WAIT_REPORT` 间反复跑死。）

| 优先级 | 收到 | 我的动作 | 终态 |
| :---: | --- | --- | :---: |
| 1 | `73` | 无需再发，直接完成 | ✅ |
| 2 | `RR73` | 回 `73` 并完成 | ✅ |
| 3 | `R<报告>` | 回 `RR73` 并完成 | ✅ |
| 4a | 纯报告，但我已回过 R（对方没收到我的 R） | 回 `RR73` 收尾（打破死循环） | ✅ |
| 4b | 纯报告，我尚未回过 R | 转应答方，回**我自己实测的** `R<报告>` | — |
| 5 | 网格，且我还在 `NONE/CQ/GRID` | 转主叫，发**我自己实测的**报告 | — |
| 5′ | 网格，但我已发过报告及以后 | 忽略（重复 / 滞后） | — |

- `QsoProgress.advanced`（`lastAdvanced`）：**本次 `onDecoded` 是否推进**（收到当前对手的有效回复）。
  第 2 层据此识别「当前目标本批沉默」→ 触发循环 2 换台。
- 引擎**自身不因重试耗尽而放弃**（`retryLimit` 体系已退役）；是否换台 / 回 CQ 由第 2 层按 `noReplyLimit` 决定。
- 阶梯 **4a** 是「两端互回报告死循环」的根治（对撞纯报告而己方已发 R ⇒ 直接 `RR73`）。

### 2.4 报告值口径

- **Tx2 报告**：Ft8Vox 采用「**每次重测最新**」——处于 `REPORT` 步时，每个解码批次用当前对手最新 SNR 刷新
  `reportSent`；重发 `Tx2` 生效。**有意偏离 FT8CN 的「固定首次」**。
- **Tx3 的 R 报告**：**复用 Tx2 实际发出的快照** `lastSentReport`（`onTransmitted` 时冻结），不随之后重测而变。
- 报告 = 解码 SNR，clamp 到 `-24…+30`（`reportFromSnr`）。

### 2.5 无回应计数

- `noReplyCount` 按**解码批次**累计（FT8CN 口径）：任何一批没推进就 +1，收到有效回复即清零；
  **空批也计**；**纯深度 / 弱信号批次不计**。
- `QsoEngine.configure(myCall, myGrid)`；引擎只提供 `noReplyCount`，上限判断完全在第 2 层。

### 2.6 两机全自动时序（示意）

```
时隙 0（我 EVEN）   发  CQ BG7ZJW OM89                 → 对方循环 3：S&P 应答
时隙 1（对方 ODD）  GB7ZJW GB7AAA JN25（order 1）      → 我阶梯 5′：发实测报告
时隙 2（我 EVEN）   GB7AAA BG7ZJW -12                  → 对方阶梯 4b：发 R 报告
时隙 3（对方 ODD）  GB7ZJW GB7AAA R-09                 → 我阶梯 3：回 RR73 并落库
时隙 4（我 EVEN）   GB7AAA BG7ZJW RR73                 → 对方阶梯 2：回 73 并落库
时隙 5（对方 ODD）  BG7ZJW GB7AAA 73
两端 onQsoFinished → 队列空 → 回 CQ
```

> 关键：应答方发的是 `<收方> <发方> <网格>`，收方＝刚才发 CQ 的那台。两端都开着总开关时，
> 无论谁先 CQ，收敛阶梯都会把两端带到 `DONE`；即使阶段错位（一方已发 R、另一方还在发纯报告），
> 阶梯 4a 也会直接收尾。应答方 / 被呼时固定到对方时隙的相反周期。

### 2.7 防跑死检查表

| 风险 | 规避 |
| --- | --- |
| 两端互回报告死循环 | 收敛阶梯 4a：已回过 R 却再收纯报告 → 直接 `RR73` |
| `RR73` 后等对方 73 而对方已停 | `RR73` 一收到就回 `73` 并**立即完成**，不停留 |
| 目标整批解码被整批丢弃 | **逐条**过滤仅自听报文，不整批丢弃 |
| 复合同呼号漏匹配 | `CallMatch.isFrom` **双向**宽松 |
| 目标沉默导致再也听不到别人 | **循环 2**：目标本批沉默 → `directedTakeover` 换台 |
| 无人回应无限空转 | `noReplyLimit`（默认 0＝按 FT8CN 永久重试；可设 1..30） |
| 无限发射 | 发射监管到点**关闭发送总开关** |
| 过期发射写入 | `engineGen` + `txAbortGen` 双代次校验 |
| 未填呼号 / 未开总开关 | 第 1 层拒绝启动、第 2 层待命，UI 提示 |

### 2.8 完成与落库

- `finish()` 写 `QsoLogEntry`（**幂等**，一段只记一条），`consumeCompleted()` 一次性取走。
- `startUtcMs` = 本段第一次发射 / 应答的时刻（FT8CN `startTime`；ADIF `QSO_DATE`/`TIME_ON`）；取不到时回退为完成时间。
  `utcMs` = 完成时间（ADIF `QSO_DATE_OFF`/`TIME_OFF`）。
- **收到 73 / RR73 立即落库**，不弹确认、不关 TX（照 FT8CN）。
- **会话内同一呼号只写一条**：`SessionViewModel.sessionSavedCalls` ＋ `saved` 标记去重（取代旧 `QSO_LOG_DEDUP_MS` 时间窗）。
  落库取**会话内最后一次**（FT8CN 是回查历史报文）。
- 完成即写 `WorkedIndex`（本波段，`WorkedIndex.plus`），使「已通联」过滤立即生效；`stop()` / 换波段清空集合。

### 2.9 `Protocol` 时隙与报文长度

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
角色隐式、应答强制、完成即落库。全部自动决策集中在 `FT8TransmitSignal.parseMessageToFunction()`
（`ft8transmit/FT8TransmitSignal.java:808`）。

### 3.1 三循环 `checkCQMeOrFollowCQMessage`（`:673`，优先级从高到低）

| 循环 | 语义 | 关键行 |
| :---: | --- | --- |
| 1 | **目标优先（防漂移）**：`to` 是我、`from == 当前目标`、非 73 的报文 → `setTransmit(order+1)` | `:678` |
| 2 | **任何人呼叫我一定应答 ★**：`to` 解析出我方呼号、且非 `73` → 立即按 `+1` 回包；不看是否已通联 / 显示筛选 / 是否已有目标 | `:697`、`:704-708` |
| 3 | **S&P 自动应答关注的 CQ**（仅当当前无目标）：`autoCallFollow` 开、`toCallsign != null`、`haveTargetCallsign()` 为假；遍历关注列表挑「是 CQ ＋（`autoCallFollow && autoFollowCQ` 或 我关注）＋ 未通联 ＋ 非自己」 | `:714`、`:718`、`:722`、`:728-746` |

- 循环 1 注释：多个台同时呼叫我方时，先锁定当前目标，**避免回复对象漂移**。
- `isExcludeMessage`（`:661`）过滤：与我发射同槽 / 异波段 / 排除字头。
- **`getNewTargetCallsign`（`:911`）**：在**当前解码批次**里挑新 CQ 台换台，条件＝「同波段 ＋ 是 CQ ＋ 非当前目标 ＋ 之前没通联成功过」；
  找不到就直接回 CQ（不在关注列表里找）。
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
| **无回应限制** `noReplyLimit` | `GeneralVariables.java:194` | `0..30`，`0`＝忽略；超出（且 `>0`）后退回 CQ 或换台 |
| **无回应计数** `noReplyCount` | `GeneralVariables.java:196` | `generateFun()` / 收到回复时清零；**弱信号（深度解码）不累加** |
| **RR73 三重兜底** | `FT8TransmitSignal.java:834`、`:838`、`:840` | 阈值×2 / 对方已转呼别人 / 忽略时 `>20` |
| **深度解码不触发自动** | `MainViewModel.java:319` | `isDeep` 的解码不驱动自动程序 |

完成判定为 **5 路 OR**（任一命中即收尾回 CQ）：`newOrder==5` ／ `order==5 && newOrder==-1` ／
`order==4 && noReplyLimit>0 && noReplyCount>noReplyLimit*2` ／ `order==4 && checkTargetCallMe>1` ／
`order==4 && noReplyLimit==0 && noReplyCount>20`。

### 3.5 关注呼号与两个开关的真实语义

- **`followCallsigns` 关注名单**：独立 SQLite 表（`DatabaseOpr.java:142`），**用户手动**加入
  （呼叫列表 / 发射界面 / 地图 `GridTrackerMainActivity`），**永久保存**，且**只能在局域网 Web 后台删除**
  （`LogHttpServer.java:444`）或清缓存时清空。名单里的台**不受 `autoFollowCQ` 限制**，一定进列表、并在
  `autoCallFollow` 开时被自动呼叫。`callsignInFollow()`（`:327`）判是否在名单内。
- **`autoFollowCQ`**（`GeneralVariables.java:205`，默认 true）：**自动关注 CQ**——把解码到的 CQ
  **推送到「呼叫」列表**；帮助文件 `auto_follow_help.txt` **明确说明「不写入关注呼号表」**。
- **`autoCallFollow`**（`:206`，默认 true）：**自动呼叫关注的呼号**——是否自动去呼叫 CQ，是**总闸**
  （`:714` 关掉即直接返回）；开启且 `autoFollowCQ` 也开 ⇒ **任何**未通联 CQ 台都可呼叫；
  `autoFollowCQ` 关 ⇒ **只**呼叫关注名单里的 CQ 台（`FT8TransmitSignal.java:733-737`）。
- **`checkQSLCallsign()`（`:273`）**：是否在本波段已通联（`QSL_Callsign_list`）；`checkQSLCallsign_OtherBand()`（`:283`）为其它波段。

### 3.6 关键行号索引

**人工操作不会暂停自动程序**：FT8CN 没有「手动接管（paused）」概念，人工只改发射目标 / 指令，下一批解码照旧跑自动程序（入口如 `restTransmitting()` `:982` 重设 order 6、`setActivated(false)` `:945` 关发射、`resetLaunchSupervision()` `GeneralVariables.java:352` 复位监管）。

`parseMessageToFunction` `FT8TransmitSignal.java:808`；`getFunctionCommand` `:251`；`generateFun` `:293`；`doComplete` `:475`；
`checkCQMeOrFollowCQMessage` `:673`；`getNewTargetCallsign` `:911`；`setActivated` `:945`；`resetToCQ` `:1005`；
`doOnSecTimer` `:126`；决策外层闸门 `MainViewModel.java:318`；`updateQSlRecordList` `:753`。

### 3.7 其它要点与已知风险（本机对应规避见 §6 / §7）

- **发射时隙**：`sequential = (toCallsign.sequential + 1) % 2`（`:231`），即「目标出现时隙的相反槽」；
  时隙 `UtcTimer.sequential(utc) = (((utc/1000)/15) % 2)`（FT8；FT4 7.5 s，`UtcTimer.java:253`），
  `Ft8Message.getSequence()` 加 750 ms 半槽补偿（`Ft8Message.java:324`）；心跳每 100 ms 检查秒边界（`UtcTimer.java:166`）。
- **解析分级**：前置过滤（`myCall` 长度 `< 3` / 空批 / `msgList[0].seq == sequential`，按**首条**判定）
  → 找「对方→我」的回复序号（命中即清零 `noReplyCount`）→ `updateQSlRecordList`（order 4/5 写日志）
  → 完成判定（5 路 OR，命中回 CQ）→ 未完成则 `functionOrder = newOrder + 1`
  → 无回复走 `checkCQMeOrFollowCQMessage`（order 6 时永不放弃、也不计数）→ 无回应累计（非弱信号批次 `++`）与换台。
- **风险点**：
  1. 外层 2 s 时窗闸门（`MainViewModel.java:318`）会漏报文 → 「有人叫我却不回」。本机**无此闸门**。
  2. 深度解码被排除（`:319`），不驱动自动。
  3. `msgList[0].getSequence() == sequential` 按**首条**整批丢弃 → 同批夹着对我的回复会被误伤。本机**逐条**过滤。
  4. `checkFunctionOrdFromMessages` 只认 `from == 当前目标`；复合呼号仅在目标带 `/` 时 `contains`。
  5. `getNewTargetCallsign` 只看本批解码 → 换台命中率受限。
  6. `restTransmitting()` / `resetToCQ()` 的时隙「双重取反」疑点，以实机为准。

---

## 4. 本机实现

### 4.1 模块职责

| 文件 | 职责 |
| --- | --- |
| `qso/AutoProgram.kt` | 第 2 层：`AutoProgramSettings`（四项）、`SUPERVISION_MINUTES`、`NO_REPLY_LIMIT_RANGE`、`AutoTarget`/`AutoTargetKind`、`AutoAction` 封闭接口、`AutoProgramSelector.collect` / `toTarget` / `rank` / `modifierPriority` / `areaOfGrid`、`AutoScheduler` |
| `qso/QsoEngine.kt` | 第 1 层：`QsoState`、`QsoProgress`（含 `order`/`noReplyCount`/`advanced`）、六步 `Step`、收敛阶梯、报告快照、`finish()`/`consumeCompleted()` |
| `qso/TxCompose.kt` | `TxMessageKind`（六步）、`DEFAULT_CQ_PREFIXES`、`TxScheduler`（`MIN_SEND_NOW_MS` / `minSendNowMs`） |
| `qso/FollowRoster.kt` | 「自动收录 CQ 台」纯逻辑：`pickCqCalls`（跳过**本波段**已通联）/ `merge` / `AUTO_MAX` |
| `qso/Message.kt` | `ParsedMessage` / `MessageParser` / `CallMatch`（`shortCall` / `isCallingMe` / `isFrom`） |
| `qso/DecodeFilter.kt` / `qso/DecodeHighlight.kt` | 显示筛选 / `WorkedIndex` + `plus` |
| `ui/SessionViewModel.kt` | `pollOnce` 驱动、`replyTo`、`setTxEnabled`、`resetSupervision`、`autoCollectCqToFollow`、`maybeGiveUpTarget`、`stopAutoBySupervision` |

### 4.2 `AutoScheduler` 决策

- `AutoAction`：`SendCq` / `AnswerCq(target)` / `HandleDirected(target)` / `Listen` / `Stop(reason)` / `None`。
- **`onDecoded`（最高优先顺序）**：
  1. 监管超时 → `AutoAction.Stop`；
  2. `protectedStop` → `None`；
  3. `collect` 收集候选；
  4. **定向候选非空 → `HandleDirected(rank 最优)`**（一律应答，最高优先，不受任何开关 / 筛选 / 已通联影响）；
  5. 无进行中 QSO 时：`autoCallFollow && 有未通联 CQ 候选` → `AnswerCq(最优)`，否则 `SendCq`。
- `rank`：降序键 = `(CQ 修饰符优先级, 解码顺序)`；`modifierPriority`：`DX`=3 ＞ 与我所在区域匹配=2
  ＞ 其它修饰符=1 ＞ 无=0；用稳定的 `sortedByDescending`，同优先级保持解码先后。定向报文始终优先于 CQ。
- `enable` / `disable` / `resetSupervision` / `checkSupervision` / `markProtectedStop` 管理监管计时与 `protectedStop`。
- `onQsoFinished`：先处理队列（`HandleDirected`），队列空了才 `SendCq`（FT8CN `resetToCQ`）。
- `onTargetGaveUp`：超限 → 优先换台（有未通联 CQ 就 `AnswerCq` 最优的），没有才 `SendCq`（FT8CN `getNewTargetCallsign`）。

### 4.3 候选收集 `AutoProgramSelector.collect`

| 报文 | 类型 | 是否入选 |
| --- | --- | --- |
| 对方的 `CQ` | `CQ` | 仅当 `autoAddCqToFollow` 开、**或**发信人在「关注名单」里，**且**本波段未通联（`checkQSLCallsign` 口径）；**不套用显示筛选** |
| 发给我的网格 | `CALL` | **一律入选** |
| 发给我的报告 | `REPORT` | **一律入选** |
| 发给我的 `R<报告>` | `ROGER` | **一律入选** |
| `RR73` / `73` / `RRR`（`DecodeFilter.is73`） | — | 不作为新 QSO 起点 |
| 自听（`from == myCall`） | — | 跳过 |
| `deep`（弱信号二次解码） | — | 跳过（当前 native 单遍解码 ⇒ 恒 `false`，占位） |
| 显式「忽略呼号」`ignoredCalls` | — | 跳过（用户主动屏蔽，不是显示筛选） |

同呼号去重，保持解码顺序。`toTarget(msg, myCall)` 是单条版本，供解码菜单「呼叫 / 回复」使用，同样**不受**两个开关 / 已通联 / 显示筛选影响。

### 4.4 忙时换台（`advanced` + `directedTakeover`）

```
本次 onDecoded 未推进（advanced == false）且 QSO 仍在进行且 txEnabled
  → directedTakeover(本批, 当前目标, filter, worked)   // FT8CN 循环 2
  → 命中「非当前目标」的定向台 ⇒ 立即换台
  → 未命中 ⇒ 才走无回应 / 放弃判定
```

- `directedTakeover` **不看**两个开关、不看是否已有目标；只排除当前目标自身（`CallMatch.isFrom`）与本批已结束的 `RR73/73/RRR`。
- 当前目标**有**回应（`advanced == true`）时**不**调用 → 「有目标不换台」由调用方保证。

### 4.5 `SessionViewModel.pollOnce`

1. 取本批解码（新→旧）→ 汇入消息流；`autoCollectCqToFollow(decoded)` 把**本波段未通联**的新 CQ 呼号并入关注名单。
2. 刷新状态读数（节流 + 量化后发布）。
3. **每个接收时隙结束**：
   - **逐条**过滤「落在我方刚发射那个时隙」的解码（不整批丢弃）；
   - 先检查发射监管，触发 → `stopAutoBySupervision`（关闭 `txEnabled`）；
   - `st.qso.active && !awaitingResponders`：`qsoEngine.onDecoded` → `applyQsoProgress`；
     若 `!advanced` 且仍 `txEnabled` → `directedTakeover`（换台）否则 `maybeGiveUpTarget`（换台 / 回 CQ）；
   - `awaitingResponders`（已发 CQ 等回应者）或 `!active && txEnabled`：`runAutoProgram`。
4. 瀑布行流、`txTick()`（发射调度）、前台服务通知。

### 4.6 人工覆盖（第 3 层）

- 解码列表长按菜单：**呼叫 / 回复**（`replyTo`）、**查看日志**、**关注 / 取消关注**、复制消息、忽略该呼号；
  **不加手动 73**。
- 顶栏左滑呼叫、就地发射（`TxScheduler`）。
- 设置页四项自动程序设置（`ui/AutoProgramDialog.kt`）。
- **唯一闸门**是「发送总开关」`txEnabled`（默认关、不持久化）：关闭即停、打开即恢复；
  **直接生效、不弹确认框**（`AutoEnableConfirmDialog` 已删）。
- 人工操作**不暂停**自动程序，只 `resetSupervision` 复位监管计时。

### 4.7 落库与收口

- `applyQsoProgress` 把状态机进度同步到 UI、写日志并收口第 2 层调度；QSO 结束时若还有最后一条 `RR73`/`73` 要发，
  置 `pendingAutoFinish`，等它**真正发出去**后在 `txTick` 里收口（否则下一个动作会覆盖状态机、丢掉最后一条）。
  期间 `pollOnce` 也不让第 2 层启动新 QSO（`qso.txText != null` 时跳过 `runAutoProgram`）。
- `QsoEngine.onTransmitted(sentText)` 只有在「刚发出去的就是当前应发报文」时才在 DONE/FAILED 下清空 `txText`：
  上一时隙抢发出去的旧报文（如重复的 `R 报告`）发完不得清掉还没发出去的 `73`/`RR73`。
  真机现象（2026-09-27 双机 logcat）：「对方给我 RR73，我不回 73，反而起 CQ」＝
  ① `txTick` 用旧报文抢占了该时隙；② 旧报文发完时清空了收尾的 `73`；③ 第 2 层 `SendCq` 又覆盖了它。
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
| 无回应限制 | `noReplyLimit` | `NO_REPLY_LIMIT_RANGE = 0..30`（`0`＝忽略） | 0 |
| 自动收录 CQ 台 | `autoAddCqToFollow` | 开关 | true |
| 自动呼叫 CQ 台 | `autoCallFollow` | 开关 | true |

持久化键：`auto_supervision_minutes` / `auto_no_reply_limit` / `auto_follow_cq` / `auto_call_follow`；
旧键常量保留但不再读写。

### 5.2 两个开关的新语义（本机**有意偏离** FT8CN）

- **自动收录 CQ 台（`autoAddCqToFollow`）**：
  - 只收录**当前波段还没通联过**的 CQ 台（波段口径照 FT8CN `checkQSLCallsign` 的 `where band=?`；
    跨波段通联过的台在本波段仍算没通联过），每解到一个就**写入关注名单**（⭐ 列表），
    封顶 `FollowRoster.AUTO_MAX = 100`，超出淘汰**最早收录**的（**手动关注的不淘汰**）；
  - 同时是 `collect` 的 CQ 候选闸门：开 ⇒ 任何**本波段未通联**的 CQ 台都纳入候选；关 ⇒ **只**纳入关注名单里的 CQ 台。
  - FT8CN 的 `autoFollowCQ` **只推送到呼叫列表、不写关注名单**（帮助文件明确）——本机**有意偏离**。
- **自动呼叫 CQ 台（`autoCallFollow`）**：是否**真的去呼叫**候选里的 CQ 台（**总闸**）；
  关掉后只回应定向呼叫 ＋ 自己发 CQ。
- **关注名单** ＝ `AppSettings.followCalls`（手动 ＋ 自动）＋ `AppSettings.autoFollowOrder`
  （自动收录顺序，**恒为 `followCalls` 子集**，持久化键 `auto_follow_order`，换行分隔）。
  它**不是解码筛选**；照 FT8CN，名单里的台**不受** `autoAddCqToFollow` 限制。
  入口：解码长按「关注 / 取消关注」，或由「自动收录 CQ 台」自动加入（带「自动」标记）；
  筛选条最右 **⭐** 打开「关注呼号列表」面板（列表里**左滑＝呼叫、右滑＝取消关注**）。
  名单里的台**通联完成后自动取消关注**（落库时从 `followCalls` + `autoFollowOrder` 移除）。

### 5.3 关键常量与默认值

| 参数 / 常量 | 值 | 位置 / 说明 |
| --- | --- | --- |
| 发送总开关 `txEnabled` | `false`（不持久化） | 自动程序的**唯一闸门** |
| `FollowRoster.AUTO_MAX` | `100` | 自动收录上限，超出淘汰最早收录 |
| `TX_START_WINDOW_MS` | `1200L` | 时隙起始的发射窗口（`ui/SessionViewModel.kt`） |
| `MIN_SEND_NOW_MS` | `2500L` | 立即发射所需最小余量（`TxCompose.TxScheduler`） |
| `AUTO_PARITY_LEAD_MARGIN_MS` | `500L` | 前导对齐余量 |
| `TX_WRITE_CHUNK_FRAMES` | `4096`（帧，约 85 ms @48 kHz） | native `app/src/main/cpp/audio_engine.c` |
| `Protocol.messageMs` | FT8 `12_640` / FT4 `4_480` | `engine/Ft8Engine.kt` |
| 时隙长度 | FT8 `15000` / FT4 `7500` ms | `SessionViewModel.slotMsOf` |
| 报告范围 | `-24 … +30` dB | `reportFromSnr` |
| 同频发射 `sameFreqTx` | `true` | 开＝红线跟到目标频率；关＝保持设定频率 |
| `noReplyLimit == 0` | 永不因无回应放弃 | 仅由收敛阶梯 / 监管兜底 |

> **没有**「自动化程度 / AutoSeq 等级 / AutoMode / 只要新东西 / autoRxFilter / 半自动收尾」这些参数——FT8CN 没有，本项目也不做。

---

## 6. 与 FT8CN 对照表

| 维度 | FT8CN | 本机 | 差异 |
| --- | --- | --- | --- |
| 六步序列 | 1 网格 / 2 报告 / 3 R / 4 RR73 / 5 73 / 6 CQ | 同（`QsoEngine.Step.order`、`TxDrawer` 六格、`TxCompose` 六种） | 照搬 |
| 自动化档位 | 无档位；两个布尔开关 | 单档常开 + 四项设置；删 `AutoMode` | 照搬 |
| 角色 | 隐式（`order==6` 即主叫） | 同（`step`/`target`） | 照搬 |
| 定向一律应答 | 循环 2，几乎无例外 | 定向分支优先级最高，**逐条**判 `addressedTo(myCall)` | 超集 / 加固 |
| 目标优先（防漂移） | 循环 1 | 保留 | 照搬 |
| 有目标不换台 | 循环 3 前置 `haveTargetCallsign()` | 保留（仅当目标**本批有回应**时才不换台） | 照搬 |
| 忙时目标沉默 | 循环 2：目标无回应 → 应答其他呼叫我方者 | `QsoProgress.advanced` + `AutoScheduler.directedTakeover` | 照搬 |
| 已通联 CQ 台不自动应答 | `checkQSLCallsign` 过滤 | 硬编码跳过本波段已通联；**定向报文仍一律应答** | 照搬 |
| 换台计数 | 按解码批次 `noReplyCount` | 同；弱信号批次不计 | 照搬 |
| 换台动作 | 超限 → 本批找 CQ → 否则回 CQ | 同（`onTargetGaveUp`） | 照搬 |
| 监管超时动作 | `setActivated(false)` | 关**发送总开关**（`txEnabled=false`） | 照搬 |
| 完成收尾 | 收到 73/RR73 立即落库、不弹框、不关 TX | 同 | 照搬 |
| 落库去重 | `QslRecordList` + `saved` 标记 | 会话内一条 + `saved` 标记 | 照搬 |
| 已通联标记 | `addQSLCallsign`（本波段表） | 完成即写 `_worked`（跨波段，供高亮/筛选）与 `workedCallsByBand`（本波段，供自动程序） | 照搬 |
| 日志字段 | 起止时间 / 波段 / 基频 / 收发报告 | 补齐 `startUtcMs` + 保留 `Distance: … km, Qso by Ft8Vox` | 照搬 |
| 深度解码 / 弱信号 | `isDeep` 不驱动自动、弱信号不计无回应 | 预留 `DecodeResult.deep` 钩子（当前恒 `false`），同规则 | 照搬（占位） |
| 手动接管 paused | 无此概念，人工不暂停自动 | 取消 `paused`；人工只改目标 / 指令 | 照搬 |
| 发射确认框 | 无 | 删 `AutoEnableConfirmDialog`；`txEnabled` 即唯一闸门 | 照搬 |
| Tx2 报告值 | 固定首次测得的 SNR | **每次重测最新** | 有意偏离 |
| Tx3 的 R 报告 | 与 Tx2 同一变量 | **复用 Tx2 已发出的快照** | 有意偏离 |
| 落库报告取值 | 落库前回查历史报文 | **用会话内最后一次值** | 有意偏离 |
| 复合呼号匹配 | 目标带 `/` 时 `contains`（单向） | **双向**（任一方带 `/` 即 `contains`） | 超集 / 加固 |
| 2 秒时窗闸门 | 有（漏应答根因） | **无**（逐条判定，晚到 / 大批量也能触发） | 有意偏离（避隐患） |
| 首条整批丢弃 | 有 | **逐条**自听过滤（仅丢本槽那一条） | 有意偏离（避隐患） |
| 选台排序 | 无 DX 分级 | `rank`：`DX > 我所在区域 > 其它修饰符 > 无`（稳定保序） | 超集（自定） |
| 关注呼号名单 | `followCallsigns` 表：手动关注、持久保存、App 内不能删；不受 `autoFollowCQ` 限制 | `AppSettings.followCalls` + `autoFollowOrder`：手动或自动加入；⭐ 面板内可删；**通联完成后自动取消关注**；不受 `autoAddCqToFollow` 限制 | 有意偏离 |
| 两个 CQ 开关 | `autoFollowCQ`＝推送到呼叫列表（**不写名单**）；`autoCallFollow`＝是否自动呼叫（总闸） | `autoAddCqToFollow`＝**写入关注名单** + 把关 CQ 候选；`autoCallFollow`＝把关是否呼叫 | 有意偏离 |
| RR73 三重兜底 | ③④⑤ | 引擎结构上 `RR73` 不滞留，兜底不可达；以「收敛兜底 + 逐条过滤」代替 | 有意偏离（等价防死） |

**FT8CN 有意没有、本机也不引入的**：Hound/Fox、逐条精确 DXCC 分级、多档 AutoSeq、完成确认卡。

---

## 7. 有意偏离清单

1. **Tx2 报告值「每次重测最新」**：未推进时用当前对手最新 SNR 刷新 `reportSent`，重发 `Tx2` 生效。
2. **Tx3 的 R 报告复用快照**：`lastSentReport` 在 `onTransmitted` 时冻结，不随之后重测而变（与 Tx2 保持一致）。
3. **落库用「会话内最后一次」**：`QsoLogEntry.reportSent/reportReceived` 直接取引擎快照，不回查历史报文。
4. **复合呼号双向宽松**：`CallMatch.isFrom` 任一方带 `/` 即 `contains`，防对方改用 `/P` 时漏应答。
5. **无 2 s 时窗闸门**：逐条判定，晚到 / 大批量解码也能触发应答（FT8CN 的漏应答根因）。
6. **逐条自听过滤**：只丢「落在我方发射那个时隙」的解码，保留同批中对方的回复（不整批丢弃）。
7. **CQ 修饰符 tie-break**：`DX` ＞ 我所在区域 ＞ 其它 ＞ 无；只做同级加分，不影响「一定应答」。
8. **深度解码不驱动自动**：`DecodeResult.deep` 当前恒 `false`，仅预留钩子（与 FT8CN 同口径）。
9. **`autoFollowCQ` 改成真写名单**：`autoAddCqToFollow` 每解到一个**本波段未通联**的新 CQ 台就写入关注名单（封顶 100、
   淘汰最早收录；手动关注不淘汰），FT8CN 只推送到呼叫列表、不写名单。
10. **关注名单可从 App 内删除**：存 `AppSettings.followCalls`，App 内即可删；FT8CN 是独立表、只能在局域网 Web 后台删。
11. **RR73 三重兜底不补码**：本引擎 `RR73` 不滞留（收到即回 73 完成），三条兜底**结构上不可达**，
    防跑死由「收敛阶梯 4a + 逐条自听过滤」承担。
12. **收敛阶梯 4a**：收到纯报告而己方已回过 R → 直接 `RR73` 收尾（FT8CN 无此分支），根治两端互回报告死循环。
13. **关注名单「通联完成后自动取消关注」**：QSO 落库时把对方从 `followCalls` + `autoFollowOrder` 移除
    （开关一开名单只增不减会把已做过的台一直留在⭐里）；FT8CN 无此行为。

---

## 8. 明确不做

以下为**当前明确不做**的清单（FT8CN 没有，或本机不引入）：

- Hound / Fox 模式。
- 逐条精确 DXCC 分级（选台不做精确 DXCC）。
- FST4 等其它协议。
- 自动窄带过滤。
- AutoSeq 档位（`AutoMode` / `AUTOSORT` / `DecodeTiming` / 工作模式单选等）。
- DX 分级选台。
- LoTW 呼号集 / `LoTW` 相关。
- Auto RX filter。
- QSO history。
- 半自动 Log 确认卡。
- 看门狗（watchdog）。
- `AutoEnableConfirmDialog`（发送总开关确认框）。
- `checkTargetCallMe` 兜底：因 `RR73` 在本引擎结构上不滞留而**不可达**，**不补码**。
- `retryLimit` / `giveUpAfterRetry` / `QsoProgress.retries` 重试体系（已退役）。
