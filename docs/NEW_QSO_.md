# QSO 自动系统设计文档（Ft8Vox · 对标 FT8CN）

> **版本**：4.0　**日期**：2026-09-27　**对标基准**：FT8CN 的 QSO 模型
> **权威参照**：[`docs/FT8CN-QSO.md`](FT8CN-QSO.md)（FT8CN 逐行剖析）、[`docs/FT8CN_QSO_PLAN.md`](FT8CN_QSO_PLAN.md)（改造方案与 21 项口径）
> **状态**：以【现有】〔代码已具备〕、【改造】〔本轮改动〕、【待建】〔尚无〕标注
>
> 本版把此前的「对标 JTDX」（AutoSeq 档位、DX 分级选台、半自动确认、看门狗等）**整体删除**：
> FT8CN 没有这些机制，本项目也不做。模型改为 FT8CN 的三件套：
> **六步指令序列 + 单档常开的自动程序 + 两个安全阀。**

| 维度 | FT8CN 口径 | Ft8Vox 采纳 |
| --- | --- | --- |
| 自动程度 | 单档常开；唯一的闸门是「发送总开关」 | **照搬**：没有档位、没有暂停，只有 `txEnabled` |
| 序列模型 | 六步 `functionOrder` 1..6，由**报文**推进 | **照搬**：收敛阶梯，角色隐式 |
| 选台 | 无 DX 分级：定向优先 → 换台 → S&P | **照搬**（CQ 修饰符只做同级 tie-break） |
| 应答 | 「有人呼叫我方**一定**应答」（三循环） | **照搬 + 加固**（逐条过滤、复合呼号宽松匹配） |
| 死锁 | 无重试上限；靠完成判定 + 监管 | **照搬** + 收敛阶梯 |
| 保护 | 发射监管、无回应限制 | **照搬**（超时关闭总开关） |
| 收尾 | 收到 73/RR73 立即完成，不弹确认、不关 TX | **照搬** |
| 落库 | 完成即写日志（会话内去重） | **照搬** |

---

## 0. 阅读指南

### 0.1 目标与范围

- **目标**：把 Ft8Vox 的 QSO 子系统按 FT8CN 的成熟模型重述并设计，做到
  **两台机器同时全自动也能互相完成一段通联**，且任一阶段都不跑死。
- **范围**：解码结果到手 → 收集候选 / 选台 → 锁目标 → 序列推进 → 发射 → 收尾落库 → 安全阀关闭。
  不含音频 DSP、UI 布局、电台 CAT。
- **三条硬要求**（`FT8CN_QSO_PLAN.md` §0）：
  1. **必须能自动切换自己是 CQ 方还是应答方**；
  2. **两台机器不得在任一阶段跑死**；
  3. **对方呼叫我方时必须应答**。

### 0.2 三条铁律

1. **报文方向**：FT8 定向报文 = `<收方> <发方> <载荷>`。
   `K1ABC W2XYZ -08` ＝「发给 K1ABC、由 W2XYZ 发出、载荷 -08」。**我收到的报文里我的呼号在第一位**。
   界面按人读习惯显示，内部绝不含糊。呼号匹配宽松（[`CallMatch`](../app/src/main/java/com/example/ft8vox/qso/Message.kt)，
   照 FT8CN 的 `contains` 口径，见 §六）。
2. **时隙奇偶**：`EVEN(0)` = 00s、30s 起；`ODD(1)` = 15s、45s 起（FT8，15 s）；FT4 为 7.5 s。
   我发哪个周期由本台决定；应答时**固定到对方时隙的相反周期**。
3. **信号报告**：`-24 … +30 dB`，且**一律是本地实测值**，绝不回显对方给我的报告。

### 0.3 实现映射

| 概念 | 实现位置 | 状态 |
| --- | --- | --- |
| 第 1 层 序列引擎 | `qso/QsoEngine.kt`（`QsoEngine` / `QsoState` / `QsoProgress` / `QsoLogEntry`） | 【现有】 |
| 第 2 层 调度选台 | `qso/AutoProgram.kt`（`AutoScheduler` / `AutoProgramSelector` / `AutoProgramSettings` / `AutoTarget` / `AutoAction`） | 【现有】 |
| 第 2 层 设置 | `ui/AutoProgramDialog.kt`（四项，照 FT8CN） | 【现有】 |
| 第 3 层 人工覆盖 | `ui/SessionViewModel.kt`（`replyTo` / `setTxEnabled` / `resetSupervision`） | 【现有】 |
| 报文解析 | `qso/Message.kt`（`ParsedMessage` / `MessageParser` / `CallMatch`） | 【现有】 |
| Tx 槽与宏 | `qso/TxCompose.kt`（`TxMessageKind` / `DEFAULT_MACROS` / `TxQueue`） | 【现有】 |
| 显示筛选 / 已通联索引 | `qso/DecodeFilter.kt`、`qso/DecodeHighlight.kt`（`WorkedIndex`） | 【现有】 |
| 忙时换台 | `AutoScheduler.directedTakeover`（FT8CN 循环 2） | 【改造】 |

---

## 一、模型总览

```
                    ┌──────────────────────────── 第 2 层 AutoScheduler ───────────────────────────┐
  解码批次 ───────► │  collect（定向一律入选 / CQ 按开关）→ rank（CQ 修饰符）→ 三循环决策           │
                    │  循环 1 目标优先 → 循环 2 任何人呼叫我 → 循环 3 S&P 应答 CQ                     │
                    │  两个安全阀：发射监管（超时→关总开关）、无回应限制（超限→换台/回 CQ）          │
                    └───────────────┬──────────────────────────────┬───────────────────────────────┘
                                    │ AutoAction                    │ 解码（有进行中 QSO 时）
                                    ▼                               ▼
                    ┌──────────────────── 第 1 层 QsoEngine ────────────────────────────┐
                    │ 六步序列：网格(1) → 报告(2) → R报告(3) → RR73(4) → 73(5) → CQ(6)  │
                    │ 收敛阶梯：收到任意形态报文都算出正确的下一步；推进/未推进          │
                    │ 完成 → QsoLogEntry（startUtcMs / reportSent / reportReceived）    │
                    └───────────────────────────────────────────────────────────────────┘
```

**关键约定**：

- **单档常开**：自动程序没有「档位」。它的开关就是 UI 的「发送总开关」`txEnabled`——
  打开即自动运行，关闭即停（`FT8CN_QSO_PLAN.md` §2.4）。没有 AutoSeq 等级，也没有 AutoMode。
- **角色隐式**：`QsoRole`（CALLER/RESPONDER）**不再驱动任何转移**，只供 UI 显示。
- **人工操作不暂停自动程序**：没有 `paused` / `manualIntervention`；人工只是改目标 + 复位监管计时。
- **「一定应答」**：发给我方的定向报文一律入选，不受两个开关、显示筛选、已通联影响（只有显式「忽略呼号」会挡）。

---

## 二、第 1 层：序列引擎 `QsoEngine`

### 2.1 职责

持有**一段** QSO 的全部状态，按报文推进六步序列，完成时产出 `QsoLogEntry`。
纯 Kotlin、无 Android 依赖、可 JVM 单测。

### 2.2 六步指令序列

| order | 我方发出的报文 | 引擎 `Step` | 触发方式 |
| --- | --- | --- | --- |
| 1 | `<对方> <我> <网格>` | `GRID` | `startResponderQso`（我应答 CQ） |
| 2 | `<对方> <我> <报告>` | `REPORT` | `startCallerQso`（我为主叫 / 被呼方补发） |
| 3 | `<对方> <我> R<报告>` | `ROGER` | `respondToReport` / 阶梯 4b |
| 4 | `<对方> <我> RR73` | `RR73` | 阶梯 3 / 4a |
| 5 | `<对方> <我> 73` | `SEVENTY3` | 阶梯 1 / 2 |
| 6 | `CQ <我> <网格>` | `CQ` | `startCq` |

### 2.3 收敛阶梯（收到任意报文都能收敛）

> 早期版本是「角色优先的固定脚本」，两台机器同时自动运行时会在 `WAIT_RR73` ↔ `WAIT_REPORT`
> 之间**反复跑死**（真机：`-08` / `R-10` 每 30 s 交替、永不结束）。现在对**任意非终态**都接受
> 任意形态报文，按下列优先级算出下一步，角色由报文内容动态推断。

| 优先级 | 收到 | 我的动作 | 终态 |
| --- | --- | --- | --- |
| 1 | `73` | 无需再发，直接完成 | ✅ |
| 2 | `RR73` | 回 `73` 并完成 | ✅ |
| 3 | `R<报告>` | 回 `RR73` 并完成 | ✅ |
| 4a | `R<报告>` 之外的**纯报告**，但我已在 `ROGER`（对方没收到我的 R） | 回 `RR73` 收尾（打破死循环） | ✅ |
| 4b | 同上场景，我尚未回过 R | 转应答方，回**我自己实测的** `R<报告>` | — |
| 5 | **网格**，且我还在 `NONE/CQ/GRID` | 转主叫，发**我自己实测的**信号报告 | — |
| 5′ | **网格**，但我已发过报告及以后 | 忽略（重复 / 滞后报文） | — |

阶梯 4a 是**两端互回报告死循环**（提交 `fbac634`）的根治：只要检测到「对方还在发纯报告、
而我已经发过 R」，就直接用 `RR73` 收尾。

### 2.4 报告值口径

- **Tx2 的报告**：Ft8Vox 采用「**每次重测最新**」——未推进时用当前对手的最新 SNR 刷新 `reportSent`，
  重发 `Tx2` 时生效（**有意偏离 FT8CN 的「固定首次」**，见 §十三）。
- **Tx3 的 R 报告**：**复用 Tx2 实际发出的快照** `lastSentReport`（`onTransmitted` 时冻结），
  不随之后重测而变（`FT8CN_QSO_PLAN.md` §1.7）。
- 报告 = 解码 SNR，clamp 到 `-24…+30`（`reportFromSnr`）。

### 2.5 无回应与「未推进」信号

- `noReplyCount` 按**解码批次**累计（FT8CN 口径）：任何一批没推进就 +1，收到有效回复即清零；
  空批也计；纯深度/弱信号批次不计。
- `QsoProgress.advanced`（**本轮新增**）：标记「本次 `onDecoded` 是否推进」，
  第 2 层据此识别「当前目标本批沉默」→ 触发循环 2 换台（见 §3.4）。
- 引擎**自身不因重试耗尽而放弃**（`retryLimit` 体系已退役）；是否换台 / 回 CQ 由第 2 层按
  `noReplyLimit` 决定。`QsoState.FAILED` 已无来源，保留枚举仅供 UI。

### 2.6 完成与落库

- `finish()` 写 `QsoLogEntry`（**幂等**，一段只记一条），`consumeCompleted()` 一次性取走。
- `startUtcMs` = 本段第一次发射 / 应答的时刻（FT8CN `startTime`）；取不到时回退为完成时间。

---

## 三、第 2 层：调度与选台 `AutoScheduler`

### 3.1 职责

维护目标与定向队列、按设置决定动作、跑两个安全阀。**不直接发射**：每个决策点返回一个
`AutoAction`，由 `SessionViewModel` 转成第 1 层调用与实际发射。

### 3.2 候选收集 `AutoProgramSelector.collect`

| 报文 | 类型 | 是否入选 |
| --- | --- | --- |
| 对方的 `CQ` | `CQ` | 仅当 `autoFollowCq` 开、**或**发信人在「关注名单」里，**且**未通联（`workedCall` 硬过滤）；**不套用显示筛选** |
| `<我> <对方> <网格>` | `CALL` | **一律入选** |
| `<我> <对方> <报告>` | `REPORT` | **一律入选** |
| `<我> <对方> R<报告>` | `ROGER` | **一律入选** |
| `RR73` / `73` / `RRR`（`DecodeFilter.is73`） | — | 不作为新 QSO 起点 |
| 自听（`from == myCall`） | — | 跳过 |
| `deep`（弱信号二次解码） | — | 跳过（当前 native 单遍解码 ⇒ 恒 false，占位） |
| 显式「忽略呼号」`ignoredCalls` | — | 跳过（用户主动屏蔽，不是显示筛选） |

同呼号去重，保持解码顺序。

### 3.3 排序 `rank`（CQ 修饰符 tie-break）

> **FT8CN 没有 DX 分级选台**，它只按解码先后。Ft8Vox 在**同类型内**加了一层
> 「CQ 修饰符优先级」：`DX`=3 ＞ 与我所在区域匹配=2 ＞ 其它修饰符=1 ＞ 无=0；
> 用稳定的 `sortedByDescending`，同优先级保持解码先后。这是叠加的**加分项**，不影响「一定应答」。

### 3.4 三循环（FT8CN `checkCQMeOrFollowCQMessage`）

| 循环 | 语义 | Ft8Vox 落点 | 状态 |
| --- | --- | --- | --- |
| 1 | 优先回应「我的当前目标」 | 由第 1 层 `QsoEngine.onDecoded` 完成（非当前目标的定向报文不驱动引擎） | 【现有】 |
| 2 | **任何人呼叫我，一定应答** | 有进行中 QSO 且**目标本批沉默**时，`AutoScheduler.directedTakeover` 挑「其他定向台」并换台 | 【改造】 |
| 3 | S&P：自动应答未通联的 CQ 台 | `nextAction`：`autoCallFollow` 且候选非空 → `AnswerCq`，否则 `SendCq`（候选已由 `autoFollowCq` / 关注名单在 `collect` 里把关） | 【现有】 |

**循环 2 的触发条件**（新增）：

```
本次 onDecoded 未推进（advanced == false）且 QSO 仍在进行且 txEnabled
  → directedTakeover(本批, 当前目标, filter, worked)
  → 命中「非当前目标」的定向台 ⇒ 立即换台（保持 FT8CN 行为）
  → 未命中 ⇒ 才走无回应 / 放弃判定
```

- 与 `onDecoded` 的差别：**不看**两个开关、不看是否已有目标，只排除当前目标自身与本批已结束的报文。
- 当前目标**有**回应（`advanced == true`）时**不**调用 → 「有目标不换台」由调用方保证。
- 复合呼号用 `CallMatch.isFrom` 判定，`JA1ABC/P` 与当前目标 `JA1ABC` 视为同一台。

### 3.5 换台与回 CQ（`onTargetGaveUp`）

目标无回应计数超过 `noReplyLimit`（>0 才生效）后：**优先换台**（有未通联 CQ 就应答最优的），
**没有才回 CQ**。这正是 FT8CN 的 `getNewTargetCallsign`。

### 3.6 安全阀

| 安全阀 | 参数 | 行为 |
| --- | --- | --- |
| 发射监管 | `supervisionMinutes`（`0`=关，`5/15/…/95`，默认 10） | 自 `enable` / 人工操作起绝对计时；超时返回 `AutoAction.Stop` → **VM 关闭发送总开关** `txEnabled=false` |
| 无回应限制 | `noReplyLimit`（`0`=忽略，`1..30`，默认 0） | 超限 → 目标作废 → `onTargetGaveUp` 换台 / 回 CQ |

监管触发后进入 `protectedStop`，不再产出动作，直到重新打开总开关（`enable`）或人工操作（`resetSupervision`）。

### 3.7 定向队列

一段 QSO 结束时（`onQsoFinished`）：先清空 / 逐出队列里的定向目标（`HandleDirected`），
队列空了才回 CQ（FT8CN `resetToCQ`）。

---

## 四、第 3 层：人工覆盖

- **解码菜单**（`ui/DecodeList.kt`，长按）：`呼叫 / 回复`（`SessionViewModel.replyTo`）、`查看日志`、`忽略该呼号`。
- **顶栏左滑呼叫**、**就地发射**（`TxQueue`）。
- **设置页**：四项自动程序设置（`ui/AutoProgramDialog.kt`）。
- 人工操作**不暂停**自动程序，只 `resetSupervision` 复位监管计时。
- **唯一的闸门**是「发送总开关」`txEnabled`：关闭即停自动程序、停止本次发射；打开即恢复。
  打开 / 关闭**直接生效、不弹确认框**（`AutoEnableConfirmDialog` 已删）。

---

## 五、时隙模型

- FT8 = 15 s，FT4 = 7.5 s；奇偶由 UTC 时刻判定（`slotParityOf`）。
- 我发哪个周期由本台决定；**应答 / 被呼时固定到对方时隙的相反周期**（`pinToTargetSlot`）。
- 发射闸门（`txTick`）：只在我方周期、时隙起始窗口 `TX_START_WINDOW_MS`(1200 ms) 内、
  且该时隙尚未发射过时触发；`MIN_SEND_NOW_MS`(2500 ms) 为「立即发射」的最小余量。
- 「同频发射」`sameFreqTx`：开＝把红线跟到目标频率；关＝保持设定频率（异频发射）。

---

## 六、报文协议

- **方向**：`<收方> <发方> <载荷>`；`ParsedMessage.to` / `.from` 严格区分。
- **把我方呼号在第一位**的报文判为「发给我的」（`ParsedMessage.addressedTo`）。
- **宽松匹配**（`CallMatch`，照 FT8CN）：
  - `isCallingMe`：`to` 含我方**短呼号**（`/` 分段最长者）即算呼叫我；
  - `isFrom`：**双向**——任一方带 `/` 就用 `contains`，否则精确相等。
    （FT8CN 只在目标带 `/` 时 `contains`；Ft8Vox 双向放宽，避免对方改用 `/P` 后缀时漏应答。）
- CQ 修饰符：`CQ DX <呼号> [网格]` → `cqModifier = "DX"`。

---

## 七、设置项（照 FT8CN 四项）

| 设置 | 键 | 默认 | 说明 |
| --- | --- | --- | --- |
| 发射监管 | `autoSupervisionMinutes` | 10 | `0,5,15,…,95` 分钟 |
| 无回应限制 | `autoNoReplyLimit` | 0 | `0..30`；0＝不换台 |
| 自动关注 CQ | `autoFollowCq` | true | 把解码到的 CQ 台**纳入候选**（照 FT8CN＝推送到呼叫列表）；关掉后**只**呼叫「关注名单」里的 CQ 台 |
| 自动呼叫关注的呼号 | `autoCallFollow` | true | 是否**真的去呼叫**候选里的 CQ 台（总闸）；关掉后只回应定向呼叫 + 自己发 CQ |

> **「关注名单」**（`AppSettings.followCalls`）：在解码列表**长按某台 →「关注 / 取消关注」**加入 / 移除；
> 点筛选条最右的 **⭐** 打开「**关注呼号列表**」面板查看（列表里**左滑＝呼叫、右滑＝取消关注**）。
> **它不是解码筛选**（不新增 chip）：照 FT8CN，它**不受 `autoFollowCq` 限制**（关掉开关仍会呼叫名单里 CQ 台的 CQ）。
> 与 FT8CN 的差异只有一处：FT8CN 的 `followCallsigns` 是独立 SQLite 表、**只能在局域网 Web 后台删除**，
> 本机存在设置里、App 内即可删。

旧持久化键静默保留（不做迁移、不删数据）。

---

## 八、时序图（两台机器全自动）

```
我（BG7ZJW，EVEN）                          对方（GB7AAA，ODD）
  │ startCq：order 6 → CQ BG7ZJW OM89        │
  │ ═══════════ 时隙 0（EVEN）═══════════►    │ 解码到我的 CQ（循环 3：S&P）
  │                                           │ startResponderQso：order 1
  │ ◄══════════ 时隙 1（ODD）════════════    │ GB7ZJW GB7AAA JN25
  │ 阶梯 5′：转主叫，发我实测的报告           │
  │ ═══════════ 时隙 2（EVEN）═══════════►    │
  │ GB7AAA BG7ZJW -12                         │ 阶梯 4b：转应答方，发 R 报告
  │ ◄══════════ 时隙 3（ODD）════════════    │
  │                                           │ GB7ZJW GB7AAA R-09
  │ 阶梯 3：回 RR73 并完成（落库）            │
  │ ═══════════ 时隙 4（EVEN）═══════════►    │
  │ GB7AAA BG7ZJW RR73                        │ 阶梯 2：回 73 并完成（落库）
  │ ◄══════════ 时隙 5（ODD）════════════    │
  │                                           │ BG7ZJW GB7AAA 73
  │ onQsoFinished → 队列空 → 回 CQ            │ onQsoFinished → 回 CQ
```

> 关键：**应答方发的是 `<收方> <发方> <网格>`，收方＝刚才发 CQ 的那台**。
> 两端都开着总开关时，无论谁先 `CQ`，收敛阶梯都会把两端带到 `DONE`；即使阶段错位
> （如一方已发 R、另一方还在发纯报告），阶梯 4a 也会直接收尾。

---

## 九、异常与边界（防跑死检查表）

| # | FT8CN 曾出现的风险 | Ft8Vox 的规避 |
| --- | --- | --- |
| 1 | 两端互回报告死循环 | 收敛阶梯 4a：已回过 R 却再收纯报告 → 直接 `RR73` |
| 2 | `RR73` 后等对方 73 而对方已停 | `RR73` 一收到就回 `73` 并**立即完成**，不停留 |
| 3 | 目标整批解码在 `getNowSequential` 前一槽被整批丢弃 | **逐条**过滤仅自听报文，不整批丢弃 |
| 4 | 复合同呼号漏匹配 | `CallMatch.isFrom` **双向**宽松 |
| 5 | 目标沉默导致再也听不到别人 | **循环 2**：目标本批沉默 → `directedTakeover` 换台 |
| 6 | 无人回应无限空转 | `noReplyLimit`（默认 0＝按 FT8CN 永久重试；用户可设 1..30） |
| 7 | 无限发射 | 发射监管到点**关闭发送总开关** |
| 8 | 过期发射写入 | `engineGen` + `txAbortGen` 双代次校验 |
| 9 | 未填呼号 / 未开总开关 | 第 1 层拒绝启动、第 2 层待命，UI 提示 |

> **RR73 三重兜底**（FT8CN 靠 `checkTargetCallMe` 等兜底）：在本引擎里**结构上不可达**——
> `RR73` 不滞留（收到即回 73 完成），所以不需要那段兜底代码（`FT8CN_QSO_PLAN.md` §1.8）。

---

## 十、日志与 QSO history

### 10.1 通联记录 `QsoEntity`（Room 表 `qso`）【现有】

`theirCall/theirGrid`、`myCall/myGrid`（快照）、`utcMs`、`startUtcMs`、`band/freqHz/mode`、
`reportSent/reportReceived`、`qslRcvd/lotwRcvd`、`comment`（`QsoComment.auto`：
`Distance: xxx km, Qso by Ft8Vox`）。

### 10.2 落库规则【现有】

- **收到 73 / RR73 立即落库**，不弹确认、不关 TX（照 FT8CN）。
- **会话内同一呼号只写一条**（`sessionSavedCalls` + `saved` 标记去重）；
  落库取**会话内最后一次**（FT8CN 是回查历史报文，见 §十三）。
- 完成即写 `WorkedIndex`（本波段），使「已通联」过滤立即生效；DB 回流幂等重算。

### 10.3 ADIF 导出【现有】

标准标签 `<CALL:n>` / `<EOR>`；老记录 `startUtcMs=0` 时回退完成时间，往返不变。

---

## 十一、配置参数默认值

| 参数 | 默认 | 状态 |
| --- | --- | --- |
| 发送总开关 `txEnabled` | false（不持久化） | 【现有】 |
| 发射监管 `supervisionMinutes` | 10 | 【现有】 |
| 无回应限制 `noReplyLimit` | 0（忽略） | 【现有】 |
| 自动关注 CQ `autoFollowCq` | true | 【现有】 |
| 自动呼叫关注的呼号 `autoCallFollow` | true | 【现有】 |
| 同频发射 `sameFreqTx` | — | 【现有】 |
| 报告范围 | -24…+30 | 【现有】 |

> **没有**「自动化程度 / AutoSeq 等级 / 只要新东西 / autoRxFilter / 半自动收尾」这些参数——
> FT8CN 没有，本项目也不做。

---

## 十二、测试要点

| 测试文件 | 覆盖 | 状态 |
| --- | --- | --- |
| `QsoEngineTest` | 六步序列、收敛阶梯、报告值口径、无回应计数、`advanced` 标记、完成/落库 | 【现有】 |
| `AutoProgramTest` | collect / rank、三循环、队列、换台、监管、`directedTakeover` | 【现有】 |
| `MessageParserTest` | CQ 修饰符、网格/报告/Roger/RR73/73、方向 | 【现有】 |
| `CallMatchTest` | 复合呼号双向宽松匹配 | 【现有】 |
| `DecodeHighlightTest` | `WorkedIndex`、新实体判定 | 【现有】 |
| `DxccTest` | 实体与区域表 | 【现有】 |
| `TxComposeTest` / `TxParityAutoTest` / `VoxPlanTest` | 报文构造、时隙奇偶、`planTx` | 【现有】 |
| `AdifCodecTest` / `AdifMapperTest` / `QsoMergeTest` | 日志导出、`startUtcMs` 往返 | 【现有】 |

**用例注意**：我 = `W2XYZ` 时，发给我的报文**我的呼号在第一位**（`W2XYZ K1ABC R-12`），
不要写成 `K1ABC W2XYZ ...`。

---

## 十三、与 FT8CN 的差异（有意偏离）

| 项 | FT8CN | Ft8Vox | 原因 |
| --- | --- | --- | --- |
| 报告值 | 设为目标那条解码的 SNR，**固定首次**，发送前不重测 | **每次重测最新**（重发 Tx2 刷新） | 用户选定；R 报告仍复用已发 Tx2 快照 |
| 落库值 | 回查**历史报文**取该段记录 | 取**会话内最后一次** | 会话内已满足「报告信息优先」 |
| 复合呼号 | 目标带 `/` 才 `contains` | **双向** `contains` | 防对方改用 `/P` 时漏应答 |
| 显示筛选对自动的影响 | `isExcludeMessage` 过滤整批 | 只保留**显式忽略呼号**；显示筛选只影响显示 | 「有人呼叫我方一定应答」是硬要求 |
| `RR73` 兜底 | `checkTargetCallMe` 等三重兜底 | **结构上不可达**（不滞留 RR73） | 等价防死，代码更少 |
| 选台 | 无优先级，按解码先后 | **CQ 修饰符**同级 tie-break | 叠加加分，不影响应答 |
| 深度解码 | 不驱动自动（有闸门） | 不驱动（`deep` 恒 false，占位） | 同口径 |
| 关注呼号名单 | `followCallsigns` 表（手动 / 地图 / Web 后台删除，持久保存） | `AppSettings.followCalls`（解码长按「关注」，⭐ 列表里可删；**不是解码筛选**） | 同口径；本机多一个 App 内删除入口 |
| 2 s 时窗闸门 | 有（`MainViewModel:318`） | **无**，逐条处理 | 整批闸门会丢对方回复 |

---

## 附录 A：Tx 槽 ↔ order ↔ 报文

| Tx 槽 | order | 报文模板（`TxCompose.DEFAULT_MACROS` 对应项） |
| --- | --- | --- |
| Tx1 | 1 | `{call} {mycall} {mygrid}` |
| Tx2 | 2 | `{call} {mycall} {report}` |
| Tx3 | 3 | `{call} {mycall} R{report}` |
| Tx4 | 4 | `{call} {mycall} RR73` |
| Tx5 | 5 | `{call} {mycall} 73` |
| Tx6 | 6 | `CQ {mycall} {mygrid}`（另有 `CQ DX …` / `CQ TEST …` 宏） |

顶栏显示 `TX：n`（n = 我方时隙号 0/1 或当前 order，见 UI 文档）。

---

## 附录 B：参考

- `docs/FT8CN-QSO.md`：FT8CN 的 `FT8TransmitSignal` / `GeneralVariables` / `MainViewModel` 逐行剖析
  （六步序列、`parseMessageToFunction` 伪代码、三循环、6 个跑死风险点、常量默认值表）。
- `docs/FT8CN_QSO_PLAN.md`：本轮改造的 21 项口径、逐文件清单、分期与进度。
- `docs/REGRESSION.md`：A–U 组真机回归清单（T 组＝本轮新口径，U 组＝人工菜单与日志）。
