# QSO 系统「三层合一」改造记录

> 目标：把散落在**三个文件**里的 QSO 状态收敛成**一个显式状态机**，消除跨层时序补丁带来的 bug。
> 约束：**行为完全等价**，现有 JVM 单测是回归安全网。

---

## 1. 背景：原来的三层结构为什么容易出 bug

改造前，QSO 自动系统的状态**不是一处**，而是被拆成三份，靠布尔标志与时序约定同步：

| 层 | 文件 | 它持有的状态 |
| --- | --- | --- |
| 第 1 层 序号状态机 | `qso/QsoEngine.kt` | `order:Int`(1..6)、`state`、`theirCall`、`reportSent/Received`、`noReplyCount`、`awaitingResponders`、`gaveUp`、`logged` |
| 第 2 层 选台 / 调度 | `qso/AutoProgram.kt`（`AutoScheduler`） | `queue`（定向队列）、`supervisionSinceMs`、`protectedStop` |
| 第 3 层 人工 + 执行 | `ui/SessionViewModel.kt` | `pendingAutoFinish`、`pinnedTxParity`、`autoParity`、`manualInFlight`、`txArmed`、`lastTxSlotIndex`… |

**具体的 bug 温床**：

1. **状态用 `Int` 隐式表达**：`order` 1..6 同时承担「状态」「序号」「渲染依据」三种职责，`when` 分支散落在 `render()` / `syncState()` / `roleOfOrder()` 三处。
2. **跨层时序约定**：`applyQsoProgress` 里「QSO 已结束但收尾报文（RR73/73）还没真正发出 → 先不通知第 2 层」这条规则，是靠 VM 的 `pendingAutoFinish` 标志硬撑的（注释原话「否则 startCq 会 reset 引擎把这条收尾报文覆盖掉」）——典型的补丁式修复。
3. **布尔标志传语义**：第 1 层把「本批是否推进」(`advanced`)、「是否按兜底收尾」(`gaveUp`) 以布尔形式交给第 2 层，第 2 层再决定换台 / 回 CQ。语义穿过两层边界时已经失真，只能靠注释维系。
4. **决策入口分散**：第 2 层有 `onDecoded` / `onQsoFinished` / `onTargetGaveUp` / `directedTakeover` 四个入口，调用时机由 VM 决定，无法一眼看出完整的转移图。

---

## 2. 实际架构：一个会话级状态机

**`AutoScheduler` 已并入 `QsoEngine`**——现在 `qso/QsoEngine.kt` 的**一个类**同时持有：

- 一段 QSO 的六步序号推进、落库、5 路收尾判据（原第 1 层）；
- 选台 / 定向队列 / 发射监管 / 换台（原第 2 层）。

对外由**显式阶段** `QsoPhase`（定义在 `qso/QsoEngine.kt`）表达会话此刻在做什么：

| 阶段 | 含义 | 取代了原来的 |
| --- | --- | --- |
| `IDLE` | 无进行中的 QSO、也没有收尾报文待发 | `!active && txText == null` |
| `CQ` | 我方主叫中（序号 6），等回应者由选台逻辑收集 | `awaitingResponders` |
| `QSO` | 一段定向 QSO 进行中（序号 1~5） | `active && !awaitingResponders` |
| `FINISHING` | QSO 已判定结束，但收尾报文（RR73/73）**还没真正发出** | `pendingAutoFinish` |

`SessionViewModel.pollOnce` 现在只按 `qsoEngine.phase` 分支（一个 `when`），不再自行组合三个标志：

```kotlin
when (qsoEngine.phase) {
    QsoPhase.CQ        -> runAutoProgram(...)          // 等回应者：选台
    QsoPhase.IDLE      -> if (txEnabled) runAutoProgram(...)
    QsoPhase.FINISHING -> { /* 收尾报文待发：本时隙不启动新 QSO */ }
    QsoPhase.QSO       -> { onDecoded + 换台 / 兜底 }
}
```

保留：

- `AutoProgramSelector`（无状态纯函数，选台分类）—— 被状态机调用，逻辑与单测不变；
- 发射时隙对齐 / 音频播放 / 前导 —— 属于**执行层**，仍留在 VM；
- 对外视图 `QsoProgress`（UI 与 `TxCompose` 依赖它）—— 字段不变，新增派生属性 `finishing`。

### 2.1 转移图（会话级）

```
IDLE ──总开关开───────────────► CQ（发 CQ）
IDLE ──解码有定向候选──────────► QSO（应答）      ← 「一定应答」最高优先
IDLE ──解码有 CQ 候选 && 允许──► QSO
IDLE ──解码无候选──────────────► CQ
CQ   ──解码有回应者────────────► QSO
CQ   ──(每周期)────────────────► CQ              ← 重发 CQ，永不放弃主叫
QSO  ──对手推进───────────────► QSO(序号 +1)     ← 收到对方 n → 我方 n+1
QSO  ──对手沉默 && 有别的定向──► QSO(换台)
QSO  ──收到对方 RR73(4)────────► FINISHING(我方序号 5：回 73，待发)
QSO  ──收到对方 73(5)──────────► IDLE(完成，无待发)
QSO  ──兜底判据(无回应/转呼别人)─► IDLE(gaveUp，无待发)
FINISHING ──收尾报文发出──────► IDLE / CQ         ← 队列空则回 CQ
任意 ──监管超时────────────────► IDLE(总开关关)
```

**核心收益**：`pendingAutoFinish` 这条跨层补丁消失 —— 「收尾报文还没发」就是显式阶段 `FINISHING`，
`onTransmitted(收尾)` 之后才离开它。VM 不再需要「什么时候通知第 2 层」的时序约定。

### 2.2 内部序号也是显式的（`QsoStep`）

「我下一条要发的报文序号」不再是 `order: Int`，而是 `enum class QsoStep`
（`NONE` / `GRID` / `REPORT` / `ROGER` / `RR73` / `FINAL_73` / `CQ`）。报文渲染、对外状态同步、
角色推断三处 `when` 现在都收敛到同一个枚举上——新增一步不会再漏改一处。唯一转移规则仍是
`QsoStep.replyTo(对方序号) = of(对方序号 + 1)`；对外的 `QsoProgress.order` 由它派生，数值不变。

---

## 3. 不变式（改造中始终成立）

1. 报文**文本**是发射区红/绿点匹配的唯一依据（`TxCompose.slots` 必须与状态机 `txText` 逐字一致）。
2. 报告值**只取首次测得**，重发不刷新；序号 3 的 `R<报告>` 复用序号 2 的值。
3. 落库**幂等**：进入序号 4/5 与「真正发出」各记一次、去重，一段只出一条。
4. 无回应计数在**除 CQ 主叫外各阶段**生效，阈值取字面值，空批也计。
5. 「一定应答」：发给我的定向报文一律优先，不受开关 / 筛选 / 已通联影响（`73` 例外）。
6. 完成后**保留发射周期**，不得跳时隙。

---

## 4. 验证

- 回归安全网：`QsoEngineTest`（681 行）原样通过；`AutoProgramTest` 的调度器断言语义等价
  （仅把 `AutoScheduler().onDecoded` 改为 `QsoEngine().onIdleBatch`）。
- 新增 `QsoPhaseTest`：显式钉住会话阶段转移（空闲 / 主叫 / 跑 QSO / 收尾待发 / 收尾完成）。
- `./gradlew :app:testDebugUnitTest` 全绿（478 个用例）。

---

## 5. 改造清单（4 个阶段，均已落地）

| 阶段 | 内容 | 说明 |
| --- | --- | --- |
| 1 | `QsoEngine` 内部 `order:Int` → 显式 `QsoStep` 枚举 | 新增一步不再漏改 `when` |
| 2 | `AutoScheduler` 并入 `QsoEngine`，新增显式阶段 `QsoPhase` | 三层合一 |
| 3 | `SessionViewModel` 按 `phase` 分支，`pendingAutoFinish` 移除 | 跨层补丁消失 |
| 4 | 删除旧门面（`AutoScheduler`）、补 `QsoPhaseTest`、文档同步 | 收尾 |

剩余可做（收益有限）：把 `QsoPhase` / `QsoStep` 抽到独立子包，用更强约束（如密封接口 + 转移表）进一步固化。
