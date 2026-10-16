# QSO 系统「三层合一」改造方案

> 目标：把散落在**三个文件**里的 QSO 状态收敛成**一个显式状态机**，消除跨层时序补丁带来的 bug。
> 约束：**行为完全等价**，现有 JVM 单测是回归安全网。

---

## 1. 背景：现在的三层结构为什么容易出 bug

当前 QSO 自动系统的状态**不是一处**，而是被拆成三份，靠布尔标志与时序约定同步：

| 层 | 文件 | 它持有的状态 |
| --- | --- | --- |
| 第 1 层 序号状态机 | `qso/QsoEngine.kt`（660 行） | `order:Int`(1..6)、`state`、`theirCall`、`reportSent/Received`、`noReplyCount`、`awaitingResponders`、`gaveUp`、`logged` |
| 第 2 层 选台 / 调度 | `qso/AutoProgram.kt`（462 行） | `queue`（定向队列）、`supervisionSinceMs`、`protectedStop` |
| 第 3 层 人工 + 执行 | `ui/SessionViewModel.kt`（2424 行） | `pendingAutoFinish`、`pinnedTxParity`、`autoParity`、`manualInFlight`、`txArmed`、`lastTxSlotIndex`… |

**具体的 bug 温床**：

1. **状态用 `Int` 隐式表达**：`order` 1..6 同时承担「状态」「序号」「渲染依据」三种职责，`when` 分支散落在 `render()` / `syncState()` / `roleOfOrder()` 三处，改一处容易漏另一处。
2. **跨层时序约定**：`applyQsoProgress` 里「QSO 已结束但收尾报文（RR73/73）还没真正发出 → 先不通知第 2 层」这条规则，是靠 VM 的 `pendingAutoFinish` 标志硬撑的（见 `SessionViewModel.kt` 的注释「否则 startCq 会 reset 引擎把这条收尾报文覆盖掉」）——典型的补丁式修复。
3. **布尔标志传语义**：第 1 层把「本批是否推进」(`advanced`)、「是否按兜底收尾」(`gaveUp`) 以布尔形式交给第 2 层，第 2 层再决定换台 / 回 CQ。语义穿过两层边界时已经失真，只能靠注释维系。
4. **决策入口分散**：第 2 层有 `onDecoded` / `onQsoFinished` / `onTargetGaveUp` / `directedTakeover` 四个入口，调用时机由 VM 决定，无法一眼看出完整的转移图。

---

## 2. 目标架构：一个会话级状态机

新增**一个**纯 Kotlin 状态机 `QsoSessionMachine`（可 JVM 单测），把第 1、2 层的状态与转移全部收进来；

- **状态**：用 `sealed interface` 显式表达「会话阶段」，每个阶段再持有该阶段的数据；
- **事件**：外部只能通过 `onEvent(event)` 驱动，事件种类有限且可枚举；
- **动作**：状态机**不直接发射**，只产出「应发报文 + 落库记录 + 是否关闭总开关」等 `QsoEffect`，由 VM 执行。

保留：

- `AutoProgramSelector`（无状态纯函数，选台分类）—— 被状态机调用，逻辑与单测不变；
- 发射时隙对齐 / 音频播放 / 前导 —— 属于**执行层**，仍留在 VM；
- 对外视图 `QsoProgress`（UI 与 `TxCompose` 依赖它），由新状态机派生，字段不变。

### 2.1 状态（会话阶段）

```
sealed interface QsoPhase
├─ Idle          空闲：总开关关 / 未开始（对外 state = IDLE）
├─ Listening     总开关开、无 QSO、本批无可应答者（保持在监听，下次再判）
├─ CallingCq     主叫中（我方 order = 6），持有 CQ 前缀 + 定向队列
├─ AnsweringCq   应答对方 CQ（我方 order = 1）
└─ Working       定向 QSO 进行中，持有：
                 ├─ step ∈ {2,3,4,5}（我方下一跳序号）
                 ├─ 对手 / 报告 / 无回应计数
                 └─ finishing: Boolean（已判定结束，等收尾报文真正发出）
```

> 原第 1 层的 `WAIT_REPLY / WAIT_REPORT / WAIT_RR73 / WAIT_FINAL / DONE / FAILED`
> 仍是 `QsoProgress.state`，由 `QsoPhase + step + finishing` **派生**，对外不变。

### 2.2 事件

```
sealed interface QsoEvent
├─ DecodedBatch(messages, utcNowMs, countNoReply)   一个接收时隙结束
├─ Transmitted(text, utcNowMs)                      我方一条报文真正播出
├─ TxEnabledChanged(enabled, utcNowMs)              发送总开关变化
├─ ManualCq(cqPrefix, utcNowMs)                     人工点「CQ」
├─ ManualAnswer(call, grid, snr, utcNowMs)          人工应答 CQ / 左滑「呼叫」
├─ ManualReply(target, utcNowMs)                    人工菜单「回复」（按报文类型）
├─ ManualSendOnce(text)                             一次性「插一条」自定义报文
├─ TargetSlotHeard / Tick(...)                      时隙 / 监管推进
└─ Stop()                                           人工停止
```

### 2.3 动作（副作用）

```
sealed interface QsoEffect
├─ Transmit(text, kind)                把这条报文排到下一个我方时隙
├─ LogQso(entry)                       落库一条通联
├─ DisableTx(reason)                   发射监管触发 → 关总开关
├─ FollowedQsoDone(call)               通联完成 → 取消跟踪
└─ None
```

### 2.4 转移图（会话级）

```
Idle ──TxEnabledChanged(true)──────────────► Listening
Listening ──DecodedBatch(有定向候选)───────► Working(收到定向)   ← 「一定应答」最高优先
Listening ──DecodedBatch(有 CQ 候选 && 允许)► AnsweringCq
Listening ──DecodedBatch(无候选)───────────► CallingCq            ← 回 CQ
CallingCq ──DecodedBatch(有回应者)─────────► Working
CallingCq ──(每周期)───────────────────────► CallingCq            ← 重发 CQ，永不放弃主叫
Working ──DecodedBatch(对手推进)───────────► Working(step+1)
Working ──DecodedBatch(对手沉默 && 有别的定向)─► Working(换台)
Working ──(收到 73 / 兜底判据)─────────────► Working(finishing)
Working(finishing) ──Transmitted(收尾)─────► CallingCq / Listening ← 队列空则回 CQ
任意 ──监管超时──────────────────────────────► Idle(总开关关)
```

**核心收益**：`pendingAutoFinish` 这条跨层补丁消失 —— 「收尾报文还没发」在状态机里就是 `Working(finishing = true)` 这个**显式状态**，`Transmitted(收尾)` 事件到达才离开它。VM 不再需要「什么时候通知第 2 层」的时序约定。

---

## 3. 分阶段实施（每阶段独立 commit，保持编译 + 单测全绿）

| 阶段 | 内容 | 风险 | 回归安全网 |
| --- | --- | --- | --- |
| **1** | `QsoEngine` 内部显式化：`order:Int` + `when` → `sealed Phase` + 转移函数；对外 API 与 `QsoProgress` 不变 | 低 | `QsoEngineTest`（681 行）原样通过 |
| **2** | 新增 `QsoSessionMachine`（纯 Kotlin），合并 `QsoEngine` 的一段 QSO 子状态机与 `AutoScheduler` 的选台/队列/监管；`AutoProgramSelector` 保留 | 中 | 新增单测 + `AutoProgramTest` 语义等价 |
| **3** | VM 接入新状态机：`pollOnce` / `applyQsoProgress` / `runAutoProgram` 改为「构造事件 → `onEvent` → 执行 `QsoEffect`」；删除 `pendingAutoFinish` 等散落标志 | 高 | `TxParityAutoTest` / `TxSendGateTest` + 手工用例 |
| **4** | 删除/收敛旧门面（`QsoEngine`、`AutoScheduler`），同步 `docs/Ft8Vox.md` 与 `README.md` | 中 | 全量单测 |

**行为等价的定义**：同一串事件序列，新旧实现产出的「应发报文序列 + 落库记录 + 状态文案」逐字相同。

---

## 4. 不变式（改造中必须始终成立）

1. 报文**文本**是发射区红/绿点匹配的唯一依据（`TxCompose.slots` 必须与状态机 `txText` 逐字一致）。
2. 报告值**只取首次测得**，重发不刷新；序号 3 的 `R<报告>` 复用序号 2 的值。
3. 落库**幂等**：进入序号 4/5 与「真正发出」各记一次、去重，一段只出一条。
4. 无回应计数在**除 CQ 主叫外各阶段**生效，阈值取字面值，空批也计。
5. 「一定应答」：发给我的定向报文一律优先，不受开关 / 筛选 / 已通联影响（`73` 例外）。
6. 完成后**保留发射周期**，不得跳时隙。

---

## 5. 风险与缓解

- **风险**：VM 的发射周期（`pinnedTxParity`/`autoParity`）与状态机解耦时，可能改变「QSO 完成后跳时隙」的行为 → 缓解：阶段 3 保留 `effectiveTxParity` 纯函数与其单测，只搬「标志」，不搬「算法」。
- **风险**：跨时隙的解码延迟（"等上一格解码再排定本时隙"）与状态机事件顺序耦合 → 缓解：该判据 `txShouldWaitForDecode` 已是纯函数，留在 VM，状态机只消费事件。
- **风险**：改动量集中在一个会话内难以全部验证 → 缓解：分 4 阶段独立提交，每阶段跑全量单测。
