# 按 FT8CN QSO 逻辑改造 Ft8Vox —— 实施方案

> 依据：
> - FT8CN 实现剖析 → [`FT8CN-QSO.md`](./FT8CN-QSO.md)
> - 三轮共 21 项口径确认（下表）
> - 现有代码：`qso/AutoProgram.kt`、`qso/QsoEngine.kt`、`qso/TxCompose.kt`、`qso/Message.kt`、`qso/DecodeFilter.kt`、`qso/DecodeHighlight.kt`、`ui/SessionViewModel.kt`、`ui/AutoProgramDialog.kt`、`ui/TxDrawer.kt`、`ui/DecodeList.kt`、`data/settings/SettingsRepository.kt`
>
> 目标：**把 Ft8Vox 的 QSO 子系统换成 FT8CN 的「六步指令序列 + 单档自动程序 + 两个安全阀」模型，结合现有 UI 落地。**

---

## 0. 决策基线（21 项，已确认）

### 第一轮（7 项）

| # | 项目 | 决定 |
| :--: | --- | --- |
| 1 | 六步序列与抽屉 | **照 FT8CN 重排**：1=网格 / 2=报告 / 3=R报告 / 4=RR73 / 5=73 / 6=CQ |
| 2 | 自动化档位 | **改成 FT8CN 单档**：自动程序常开 + 两个开关；删除 AutoSeq 档位 |
| 3 | 完成收尾 | **照 FT8CN 落库**：收到 73/RR73 立即落库、不弹确认、不关 TX；保留 90s 去重（后被第 15 项取代） |
| 4 | 手动接管 | **取消 paused**：人工操作只改目标/指令，不暂停自动程序 |
| 5 | S&P 与已通联台 | **完全照 FT8CN**：有目标不换台 / 已通联 CQ 台不理 / 定向一律应答 |
| 6 | 安全阀 | **照 FT8CN 替换**：发射监管 + 无回应限制 + RR73 三重兜底；`retryLimit` 体系退役 |
| 7 | 设置页字段 | **按 FT8CN 四项重做** |

### 第二轮（7 项）

| # | 项目 | 决定 |
| :--: | --- | --- |
| 8 | 复合呼号匹配 | **照 FT8CN 宽松匹配**（`contains` 短呼号；目标带 `/` 用 `contains`） |
| 9 | CQ 修饰符 | **支持并用于选台** |
| 10 | 深度解码 / 弱信号 | **两条都照做**（见 §1.6 的落地说明） |
| 11 | 报告值来源 | **每次重测最新**（⚠ 有意偏离 FT8CN 的「固定首次」，见 §1.7） |
| 12 | 无回应与换台 | **照 FT8CN**：按解码批次计数；超限优先换台、无台才回 CQ |
| 13 | 监管超时动作 | **关发送总开关**（`txEnabled=false`） |
| 14 | UI 与筛选 | **筛选只影响显示、不干预选台；解码列表补菜单；不加手动 73** |

### 第三轮（8 项）

| # | 项目 | 决定 |
| :--: | --- | --- |
| 15 | R 报告值 | **复用 Tx2 已发出的值** |
| 16 | 落库报告取值 | **用会话内最后一次**（不回查历史） |
| 17 | 已通联标记 | **完成即写 WorkedIndex**（本波段，立即生效） |
| 18 | 落库去重 | **会话内一条 + saved 标记**（取代 90s 时间窗） |
| 19 | 设置项取值默认 | **完全照 FT8CN**：监管 0/5…/95 分钟默认 10；无回应 0–30 默认 0（忽略）；两开关默认开 |
| 20 | 日志字段 | **补齐 FT8CN 字段 + 保留现有备注** |
| 21 | 旧键与文案 | **旧键静默保留（便于回滚）；文案沿用 Ft8Vox 现有中文风格**；最后一轮选择「够了，出方案」 |

> **补充（追加决定）**：**取消防误发确认框（`AutoEnableConfirmDialog`）** —— 发送总开关直接生效，全程不弹任何确认框。

---

## 1. 目标模型

### 1.1 六步指令序列（唯一序列定义）

取代现有 `QsoEngine.Step`（NONE/CQ/GRID/REPORT/ROGER/RR73/SEVENTY3 的**乱序**枚举）与 `TxCompose.TxMessageKind`（CQ/REPLY/EXCHANGE/RR73/SEVENTY_THREE/CUSTOM 的**语义**枚举），统一为 FT8CN 的序号语义：

| order | 常量 | 报文 | 语义 | 报告来源 |
| :--: | --- | --- | --- | --- |
| 1 | `GRID` | `<对方> <我> <4位网格>` | 应答 CQ / 首次呼叫 | —（进入前 `resetReport()`）|
| 2 | `REPORT` | `<对方> <我> <±dd>` | 发信号报告 | **每次重测最新**（§1.7）|
| 3 | `ROGER` | `<对方> <我> R±dd` | 回 R | **复用 Tx2 已发出的值** |
| 4 | `RR73` | `<对方> <我> RR73` | 收尾 | — |
| 5 | `SEVENTY3` | `<对方> <我> 73` | 收尾 | — |
| 6 | `CQ` | `CQ [修饰符] <我> <网格>` | 主叫 | —（进入前 `resetReport()`）|

- `GRID`/`CQ` 进入前复位收发报告（对应 FT8CN `resetTargetReport()`）。
- `generateFun` 语义：order 6 只渲染一条；其余渲染 1..6（供抽屉展示「后续报文」）。

### 1.2 角色隐式化

**删除** `QsoRole` 的「脚本式」用法，改为 FT8CN 的隐式表达：

- `step == CQ` ⇒ 主叫方；`step ∈ {GRID, REPORT, ROGER, RR73, SEVENTY3}` ⇒ 应答/在跑。
- `theirCall` 为当前目标；`theirCall == null || theirCall == "CQ"` ⇒ 无目标。
- 保留 `QsoRole` 仅作 UI 文案（可选）。

### 1.3 收敛阶梯（推进规则）

收到**发给我的、来自当前目标**的报文 `newOrder = checkFunOrder(msg)`（CQ→6，否则按 `5→4→3→2→1` 依次判定）：

```text
newOrder == 1 || 2  → 先 resetReport()，再 step = newOrder + 1
newOrder ∈ {3,4}    → step = newOrder + 1
newOrder == 5       → 完成
```

即：收网格→发报告(2)，收报告→发R(3)，收R→发RR73(4)，收RR73→发73(5)，收73→完成。

> 与现有引擎的差异：现引擎在 `ROGER` 收到**纯报告**时会直接 RR73 收尾（打破死循环）。FT8CN 无此分支。**保留该分支作为收敛兜底**（硬要求「两台机器不得跑死」，见 §1.8）。

### 1.4 完成判定（5 路 OR，照 FT8CN）

```text
newOrder == 5                                             // ① 收到 73
|| (step == SEVENTY3 && newOrder == -1)                   // ② 我发了 73、对方无回应
|| (step == RR73 && noReplyCount > noReplyLimit*2 && noReplyLimit > 0)  // ③ 卡 RR73、超阈值×2
|| (step == RR73 && targetCallingOthers)                  // ④ 卡 RR73、对方已转呼别人
|| (step == RR73 && noReplyCount > 20 && noReplyLimit == 0) // ⑤ 忽略无回应时防 RR73 死锁
```

命中 → `DONE` 并立即落库（§4）。

> **实施修正（期 1）**：③④⑤ 在本引擎**结构上不可达** —— 引擎在收到 `R` / 对撞纯报告 /
> `respondToRoger` 时立即进入 `RR73` 并 `finish()`，`RR73` 从不滞留，因此无需这三条兜底
> （加进去只是死代码）。真正防跑死的是「收敛阶梯 + 逐条自听过滤」。详见 §10.5。

### 1.5 重试 / 无回应（取代 retryLimit 体系）

- `noReplyCount` 悬挂在 **QsoEngine**（对应 FT8CN `FT8TransmitSignal` 内的计数）：
  - **每收到一个「没有对方回复」的解码批次 +1**（不是按空时隙）；
  - 收到任意有效回复 → 清零；
  - 重新生成序列（`generateFun`/新目标/回 CQ）→ 清零。
- 超过 `noReplyLimit`（且 `>0`）→ 目标作废，**交给第 2 层换台**（§3.3）。
- `noReplyLimit == 0`（默认）→ 永不因无回应放弃，仅由 §1.4 的 ③④⑤ 兜底。
- **删除** `retryLimit`、`giveUpAfterRetry`、`QsoProgress.retries/maxRetries`、`QsoState.FAILED` 的「重试耗尽」语义（`FAILED` 仅保留给异常/中止）。

### 1.6 深度解码 / 弱信号（照做，但需预留字段）

现状：`DecodeResult` 只有一路解码，**没有** `deep` / `weak` 概念（已 grep 确认全项目无 `deep`/`isWeak`）。FT8CN 的 `isDeep` 与 `isWeakSignal` 其实来自**同一路「弱信号二次解码」**。

落地方式（不改现有行为）：
1. `DecodeResult` 增加 `val deep: Boolean = false`（native JNI 构造签名不变，Kotlin 侧默认值）。
2. `AutoScheduler.onDecoded` 与 `QsoEngine.onDecoded` 增加 `if (m.deep) continue`（or 过滤），`noReplyCount` 不计 `deep` 批次。
3. 当前 native 单遍解码 ⇒ `deep` 恒 `false` ⇒ **行为等价现状**，钩子留给后续 native 二次解码。

### 1.7 报告值（有意偏离 FT8CN）

| 环节 | FT8CN | 本方案（用户决定） |
| --- | --- | --- |
| Tx2 发出的报告 | 固定首次测得的 SNR | **每次重测最新**（每次重发 Tx2 都用最新 SNR） |
| Tx3 的 R 报告 | 与 Tx2 相同（同一变量） | **复用 Tx2 实际发出的值（快照）** |
| 落库的收/发报告 | 落库前从历史报文回查 | **用会话内最后一次已发出/已收到的值** |

实现：
- 引擎维护 `lastSentReport`（Tx2 实际发出的快照）与 `lastHeardSnr`（对方最新 SNR）。
- 处于 `REPORT` 步时，每个解码批次刷新 `reportSent = reportFromSnr(lastHeardSnr)`。
- 推进到 `ROGER` 时 **冻结**：`reportSent = lastSentReport`。
- `onTransmitted()` 里把当前 `reportSent` 快照进 `lastSentReport`。

### 1.8 防跑死（保留的必要兜底）

| 兜底 | 来源 | 说明 |
| --- | --- | --- |
| 逐条自听过滤 | 现有（优于 FT8CN 的首条整批丢弃） | 只丢掉「落在我发射那个时隙」的解码，**保留同批中对方的回复** |
| 收纯报告但已在 ROGER → 直接 RR73 | 现有引擎 | 打破「双发报告互不回」死循环 |
| RR73 三重兜底 ③④⑤ | FT8CN | 同上 |
| 无时窗闸门 | 现有（**有意偏离** FT8CN 的 2s 闸门） | 保证晚到 / 大批量解码也能触发应答 |
| `protectedStop` | 现有 | 发射监管触发后不再产出动作 |

### 1.9 有意偏离 FT8CN 的清单（写入 `ROADMAP.md` 阶段 9）

1. 不引入 FT8CN 的「距周期起点 >2s 不决策」闸门（漏应答根因）。
2. 保留「报文驱动收敛兜底」（§1.8 第 2 条）。
3. 报告值改为「每次重测最新 / R 复用 Tx2」（§1.7）。
4. `deep`/`weak` 双通道仅预留字段，当前行为等价现状。
5. 无手动「关注呼号名单」UI：FT8CN 的 `followCallsigns` 表（手动关注、持久、Web 后台删除，名单里的台不受 `autoFollowCQ` 限制）**不做**；本机只有 `autoFollowCq` / `autoCallFollow` 两个开关，二者**串联**（都开才自动呼叫 CQ 台），属「无名单子集」。
6. **无任何发射确认框**：删除 `AutoEnableConfirmDialog`，总开关直接生效（FT8CN 的 `activated` 也是直接生效）。

---

## 2. 六步序列与现有 UI 的映射

### 2.1 发射抽屉「消息类型」（`ui/TxDrawer.kt` §2 的两行大按钮）

现状：`TxMessageKind` = CQ / 回复 / 交换 / RR73 / 73 / 自定义。
改为六步 + 自定义：

| 按钮 | 报文模板 | 说明 |
| --- | --- | --- |
| `1 网格` | `{call} {mycall} {mygrid}` | 应答/呼叫 |
| `2 报告` | `{call} {mycall} {report}` | |
| `3 R报告` | `{call} {mycall} R{report}` | |
| `4 RR73` | `{call} {mycall} RR73` | |
| `5 73` | `{call} {mycall} 73` | |
| `6 CQ` | `CQ {mycall} {mygrid}`（或修饰符变体） | 主叫 |
| 自定义 | 自由文本 | 保留 |

- `DEFAULT_MACROS` 重排为上述 6 条 + CQ 修饰符变体 + 自定义（保持 4×2=8 格）。
- `TxCompose.kindOf()` 改为按六步映射（`isCq→6`、`grid→1`、`report→2`、`isRoger→3`、`isRr73→4`、`is73→5`）。

### 2.2 顶栏「TX：n」与底栏

- 顶栏第三行 `displayTxText` 逻辑不变；`n` 现在表示**当前 step 的 order（1..6）**（现有为自定义序号）。
- 底栏 `QSO n` 进度文案改用六步：如 `QSO 3/5 等待 R`。

### 2.3 解码列表（`ui/DecodeList.kt`）

- **筛选不动选台**：`DecodeFilter` 仍只作用于 `DecodeList` 展示；`AutoProgramSelector` 不再对 CQ 台套用 `DecodeFilter.matches`（改为只尊重 `ignoredCalls`）。
- **补菜单**（长按/右键）：`呼叫此台` / `回复` / `查看其日志`（不加手动 73）。
  - 复用现有 `answer(call, grid, df)` 与 `LogScreen` 的呼号查询入口。

### 2.4 设置页「自动程序」（`ui/AutoProgramDialog.kt` + `ui/SettingsScreen.kt` §6）

替换为 FT8CN 四项：

| 控件 | 取值 | 默认 | 对应 |
| --- | --- | --- | --- |
| 发射监管 | 不监管 / 5 / 15 / … / 95 分钟 | 10 | FT8CN `launchSupervision` |
| 无回应次数 | 0–30（0=忽略） | 0 | FT8CN `noReplyLimit` |
| 自动关注 CQ | 开关 | 开 | FT8CN `autoFollowCQ`（把 CQ 台纳入候选，非「写入关注名单」） |
| 自动呼叫关注的呼号 | 开关 | 开 | FT8CN `autoCallFollow`（是否自动呼叫；总闸） |

> FT8CN 还有一份「关注呼号名单」（`followCallsigns`），名单里的台不受 `autoFollowCQ` 限制；**本机不做**，
> 故两个开关是串联关系（都开＝自动呼叫未通联 CQ 台；任一关＝不自动呼叫 CQ）。

- **删除**：工作模式单选、解码时机、允许重复通联、排序依据、重发机制、保护限制（无有效 QSO / 发射总时长）。
- **删除** `AutoMode` 概念：自动程序常开，**开关就是「发送总开关」`txEnabled`**。
- **删除 `AutoEnableConfirmDialog`**：发送总开关（`txEnabled`，默认关、不持久化）是**唯一闸门**，开关**直接生效、不弹任何确认框**；`MainShell` / `SettingsScreen` 里的 `requestAutoMode` / `confirmAutoMode` 一并移除。

---

## 3. 第 2 层调度（`qso/AutoProgram.kt` 重写）

### 3.1 设置模型

```kotlin
data class AutoProgramSettings(
    /** 发射监管：0=不监管，5/15/…/95 分钟（FT8CN launchSupervision）。 */
    val supervisionMinutes: Int = 10,
    /** 无回应次数上限：0=忽略（FT8CN noReplyLimit）。 */
    val noReplyLimit: Int = 0,
    /** 自动关注 CQ（把 CQ 台纳入候选，FT8CN autoFollowCQ）。 */
    val autoFollowCq: Boolean = true,
    /** 自动呼叫关注的呼号（是否真的去呼叫候选里的 CQ 台，FT8CN autoCallFollow）。 */
    val autoCallFollow: Boolean = true,
)
```

- 删除：`AutoMode`、`AutoSort`、`DecodeTiming`、`MIXED_CQ_NO_REPLY_LIMIT`、`allowRepeat`、`sortBy`、`reportPriority`、`giveUpAfterRetry`、`retryLimit`、`stopAfterNoQso`、`noQsoMinutes`、`stopAfterTxTotal`、`txTotalMinutes`、`allowsWorked`。
- 已通联过滤**硬编码**（不再有开关）。

### 3.2 `onDecoded`（最高优先级顺序）

```text
1. 监管超时？ → AutoAction.Stop("发射监管超时")        // 由 VM 关 txEnabled
2. protectedStop？ → None
3. 候选收集（见 3.3）
4. 定向候选非空？ → HandleDirected(rank 最优)          // 一律应答，最高优先，不受任何开关/筛选/已通联影响
5. 无进行中 QSO 时：
     autoCallFollow && 有未通联 CQ 候选？ → AnswerCq(最优)   // 候选已按 autoFollowCq 把关
     否则 → SendCq
```

- **删除** `paused` / `phase` / `cqPending` / `noReplyStreak`。
- `AutoAction` 保留 `SendCq` / `AnswerCq` / `HandleDirected` / `Listen` / `Stop` / `None`。

### 3.3 选台（`AutoProgramSelector.collect` / `rank`）

- `collect`：
  - 逐条解析；跳过自己；跳过 `ignoredCalls`；
  - **定向报文（to=我）**：一律入选（CALL / REPORT / ROGER；73/RR73 不作为新 QSO 起点）；**不受显示筛选、不受已通联、不受开关影响**；
  - **CQ 台**：仅当 `autoFollowCq == true` 才入选（对应 FT8CN「自动关注 CQ」＝推送到可呼叫集合；**不是**写入关注名单）；**已通联的 CQ 台一律跳过**（硬编码，对应 FT8CN `checkQSLCallsign` 过滤）；不再套用 `DecodeFilter.matches`。
  - 记录 `deep`（§1.6）与 `cqModifier`。
- `rank`（无 `sortBy`）：排序键降序 = `(CQ 修饰符优先级, 解码顺序)`：
  1. `DX`（最高）；
  2. 与「我所在大洲/区域」匹配的 `NA/EU/AS/OC/SA/AF`；
  3. 其他修饰符（`TEST` 等）；
  4. 无修饰符（最低）。
  - 定向报文始终优先于 CQ。

### 3.4 完成/失败后（`onQsoFinished`）

```text
成功 → SendCq（FT8CN resetToCQ）
无回应超限（giveUpTarget）：
    autoCallFollow && 有未通联 CQ 候选 → AnswerCq(最优)   // 换台；候选已按 autoFollowCq 把关
    否则 → SendCq
```

### 3.5 发射监管

- `checkProtection` 改为：`supervisionMinutes > 0 && now - enableSince >= supervisionMinutes*60_000` → `Stop`。
- `Stop` 由 VM 执行 **`setTxEnabled(false)`**（FT8CN `setActivated(false)`），**不再**把模式切回 MANUAL（无 AutoMode）。
- 每次人工操作（呼叫/应答/发 CQ）→ `resetSupervision()`（对应 FT8CN `resetLaunchSupervision`）。

---

## 4. 落库与日志（`ui/SessionViewModel.kt` + `data/log/*`）

### 4.1 完成即落库

- 引擎产生 `QsoLogEntry`（一次性）→ 立即 `qsoRepo.add(entity)`。
- **不弹确认卡、不关 TX**。
- 保留「完成时最后一条 RR73/73 要发完」的收口逻辑（`pendingAutoFinish`）。

### 4.2 去重：会话内一条 + saved 标记

- 新增 `private val sessionSavedCalls = HashSet<String>()`。
- 落库前：`if (!sessionSavedCalls.add(call)) return`（已落过就跳过）。
- **删除** `QSO_LOG_DEDUP_MS` 与 `lastLoggedMs`。
- 会话结束（`stop()` / 切换波段）清空该集合。

### 4.3 报告取值

- 直接用引擎的 `lastSentReport` / `lastReceivedReport`（即 `QsoLogEntry.reportSent/reportReceived`）。
- 不回查历史报文。

### 4.4 WorkedIndex 实时更新

- `WorkedIndex` 增加 `fun plus(call: String?, grid: String?): WorkedIndex`（产出含新呼号/网格的新索引）。
- 落库成功后：`_worked.update { it.plus(entry.theirCall, entry.theirGrid) }` → 后续 S&P 立即不选该台。

### 4.5 日志字段（`data/log/QsoEntity.kt`）

FT8CN `QSLRecord` 字段对照：

| FT8CN | QsoEntity | 动作 |
| --- | --- | --- |
| 起止时间（messageStart/EndTime） | `utcMs`（单一） | **新增** `startUtcMs` / `endUtcMs`（`utcMs` 保留为 ADIF 规范时间） |
| 我方呼号 / 网格 | `myCall` / `myGrid` | 已有 |
| 对方呼号 / 网格 | `theirCall` / `theirGrid` | 已有 |
| 发出报告 / 收到报告 | `reportSent` / `reportReceived` | 已有 |
| 模式 | `mode` | 已有 |
| 波段 | `band` | 已有 |
| 基频 | `freqHz` | 已有 |
| —（Ft8Vox 特有） | `comment` | **保留** `Distance: xxx km, Qso by Ft8Vox` |

- Room 版本号 +1，写 `Migration`（新增两列，允许 NULL）。
- `QsoEngine` 记录 `qsoStartMs`（第一份报文发射/接收时刻）与 `qsoEndMs`（完成时刻）。

---

## 5. 逐文件改动清单

| 文件 | 动作 | 说明 |
| --- | --- | --- |
| `qso/Message.kt` | 改 | `addressedTo()` 改宽松匹配；新增 `CallMatch`（`shortCall` / `isCallingMe` / `isCallingTarget`）；`ParsedMessage` 补 `deep` 透传（可选） |
| `qso/QsoEngine.kt` | **重写** | 六步 `QsoStep`；收敛阶梯；完成 5 路 OR；`noReplyCount`；报告快照（§1.7）；删除 retries/giveUp/FAILED-重试 |
| `qso/AutoProgram.kt` | **重写** | `AutoProgramSettings` 四项；删除 AutoMode/AutoSort/DecodeTiming；`AutoScheduler` 单档；`collect/rank` 新规则；监管 |
| `qso/TxCompose.kt` | 改 | `TxMessageKind` 改六步；`DEFAULT_MACROS` 重排；`kindOf` 映射 |
| `qso/DecodeHighlight.kt` | 改 | `WorkedIndex.plus(call, grid)` |
| `qso/DecodeFilter.kt` | 不改逻辑 | 仅从选台链路移除调用（展示仍需） |
| `ui/SessionViewModel.kt` | 改 | 删 paused/manualQso/`manualIntervention`/`resumeAutoAfterManual`；落库去重改会话集合；WorkedIndex 实时更新；监管 Stop→`setTxEnabled(false)`；`resetSupervision`；日志起止时间；筛选不参与选台 |
| `ui/AutoProgramDialog.kt` | 改 | 面板四项；**删除 `AutoEnableConfirmDialog`**；`autoProgramSummary` 四项 |
| `ui/MainShell.kt` | 改 | **删除** `confirmAutoMode` / `requestAutoMode` 与确认框弹窗 |
| `ui/SettingsScreen.kt` | 改 | §6 自动程序面板文案；**删除** `confirmAutoMode` / `requestAutoMode` |
| `ui/TxDrawer.kt` | 改 | 消息类型六步按钮；收起条摘要 |
| `ui/DecodeList.kt` | 改 | 补「呼叫 / 回复 / 查看日志」菜单 |
| `ui/AppChrome.kt` | 改 | 顶栏 `TX：n` 序号语义 / 底栏进度文案（如需） |
| `data/settings/SettingsRepository.kt` | 改 | 新增 4 键 + 读；旧键常量保留不读不写；`AppSettings.auto` 类型更新 |
| `data/log/QsoEntity.kt` | 改 | 增 `startUtcMs` / `endUtcMs` |
| `data/log/AppDatabase.kt` | 改 | 版本 +1 + Migration |
| `data/log/QsoRepository.kt` | 改 | 映射新字段 |
| `data/adif/AdifMapper.kt` | 查 | 起止时间映射（ADIF `QSO_DATE`/`TIME_ON`/`TIME_OFF`）|
| `docs/*` | 改 | 见 §8 |

---

## 6. 单测计划

| 文件 | 用例 |
| --- | --- |
| `qso/MessageParserTest` | 宽松呼号匹配：`BG7ZJW/P` ↔ `BG7ZJW`；`XXBG7ZJW` 的边界；目标带 `/` 的 `contains` |
| `qso/QsoEngineTest`（重写） | 六步推进 1→2→3→4→5→完成；收 73 完成；发完 73 无回应完成；RR73 三重兜底 ③④⑤；收纯报告（ROGER 中）直接 RR73；报告每次重测 + R 复用；noReplyCount 批次累加 / 回复清零 |
| `qso/AutoProgramTest`（重写） | 单档：定向一律应答（不受开关/筛选/已通联）；已通联 CQ 台不选；`autoFollowCq=false` 无 CQ 候选；两开关默认开；CQ 修饰符优先级；监管超时 Stop；超限换台 / 无台回 CQ |
| `qso/DecodeHighlightTest` | `WorkedIndex.plus` 增量更新 |
| `qso/TxComposeTest` | 六步模板渲染；`kindOf` 六步映射 |
| `ui/TxDisplayTextTest` / `TxParityAutoTest` | 序号语义变更后回归 |
| 新增 `qso/CallMatchTest` | 短呼号提取与匹配 |
| `data/log/QsoCommentTest` | 备注格式不变 |

- 目标：全绿，且 README 的单测计数同步。
- 构建命令：`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain`（基线 292 例 / 31 suite）。

---

## 7. 分期实施

| 期 | 内容 | 产出 |
| :--: | --- | --- |
| **期 1** | `Message.kt` 宽松匹配 + `CallMatch`；`QsoEngine` 六步重写；`TxCompose` 六步 | 引擎单测全绿 |
| **期 2** | `AutoProgram.kt` 单档 + 两开关 + 选台 + 安全阀；`DecodeHighlight.plus` | 调度单测全绿 |
| **期 3** | `SessionViewModel` 去 paused、落库会话去重、WorkedIndex 实时、监管关总开关；`QsoEntity`/Room 迁移 | 构建通过 |
| **期 4** | `AutoProgramDialog` 四项、`TxDrawer` 六步、`DecodeList` 菜单、**删除 `AutoEnableConfirmDialog` 与总开关确认** | BUILD SUCCESSFUL |
| **期 5** | `README`/`ROADMAP`/`REGRESSION`/`How2use`/`FT8CN-QSO.md` 更新；真机回归 | 文档 + 实测 |

---

## 8. 真机回归项（写入 `REGRESSION.md` 新增 T 组）

| 编号 | 项目 |
| --- | --- |
| T1 | 一端 CQ、另一端定向呼叫 → 双方都应答、不跑死 |
| T2 | 已通联呼号的**定向**呼叫 → 仍应答（不看已通联） |
| T3 | 已通联呼号的 **CQ** → 不自动应答、不自动呼叫 |
| T4 | 无回应超限 → 换台（列表有未通联 CQ 时）或回 CQ |
| T5 | RR73 卡死 → 三重兜底收尾（改无回应限制验证） |
| T6 | 发射监管到点 → 发送总开关自动关闭 |
| T7 | 收到 73/RR73 → 立即落库、不弹确认卡、TX 不关 |
| T8 | 同一呼号一段 QSO → 只落一条日志（会话去重） |
| T9 | 落库后 → WorkedIndex 立即生效（该台 CQ 不再被自动应答） |
| T10 | CQ 修饰符（CQ DX 等）→ 主叫文案与选台优先级 |
| T11 | 显示筛选切到「CQ/73/已通联」→ 定向呼叫我方仍被应答 |
| T12 | 解码列表菜单：呼叫 / 回复 / 查看日志 |

---

## 9. 风险与回滚

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| **默认两开关都开 ⇒ 行为偏 S&P** | 照 FT8CN 默认（`autoFollowCQ`/`autoCallFollow` 均开）时，程序会**优先应答听到的未通联 CQ**，而不是一直主叫 | 已在 §3.2/3.3 明确；真机 T1/T4 验证观感；如不接受，只需关掉「自动呼叫关注的呼号」 |
| 宽松 `contains` 匹配误判 | `to` 含我方短呼号即算呼叫我，理论上可能误匹配 | 落库保存报文原文；T2 验证 |
| 移除 AutoMode ⇒ 总开关=自动开关 | 开总开关即启动自动发射，且**无确认框**，误触即发 | 总开关默认关且不持久化（每次启动需手动开）；顶栏 `TX：n` 常显状态；真机注意误触 |
| Room 迁移 | 新增两列需版本号 +1 | 写 `Migration`，允许 NULL |
| 报告「每次重测」与 R 复用不一致 | 若对方在两轮间消失，Tx2 的值可能滞后 | `lastSentReport` 快照保证 Tx3 与 Tx2 一致 |
| 旧键残留 | 旧 `AutoMode` 枚举值（CALLER/MIXED）反序列化失败 | 读配置容错（未知值 → 默认）；旧键常量保留不删 |

---

## 10. 与现有实现的差异速查

| 维度 | 现状 | 改造后 |
| --- | --- | --- |
| 序列 | 语义枚举（CQ/REPLY/EXCHANGE/RR73/73） | **六步 order 1..6** |
| 角色 | 显式 `QsoRole`（CALLER/RESPONDER） | 隐式（step/theirCall） |
| 档位 | `AutoMode` MANUAL/CALLER/MIXED | **无档位**，总开关即开关 |
| 手动接管 | `paused` + `manualQso` | **取消** |
| 重试 | `retryLimit` + `giveUpAfterRetry` | **`noReplyLimit` + RR73 三重兜底** |
| 保护限制 | 无有效 QSO / 发射总时长 | **发射监管（FT8CN）** |
| 选台排序 | `AutoSort`（解码/SNR/距离） | **解码顺序 + CQ 修饰符优先级** |
| 显示筛选 | 作用于 CQ 选台 | **只作用于显示** |
| 已通联过滤 | `allowRepeat` 开关 | **硬编码**（CQ 不理；定向必答） |
| 落库去重 | `QSO_LOG_DEDUP_MS` 90s 时间窗 | **会话内一条 + saved** |
| 完成后 | 通知第 2 层继续 | **resetToCQ + 换台/CQ** |
| CQ 修饰符 | 仅构造 | **构造 + 选台优先级** |
| WorkedIndex | 启动/手动重建 | **落库后实时增量** |

---

## 10.5 实施进度

### 期 1（已完成）

**范围**：`Message.kt` 宽松匹配 + `QsoEngine.kt` 六步模型 + `TxCompose.kt` 六步。

| 改动 | 说明 |
| --- | --- |
| `Message.kt` | 新增 `CallMatch`（`shortCall` / `isCallingMe` / `isFrom`）；`addressedTo()` 改为宽松匹配 |
| `QsoEngine.kt` | `Step` 加 `order`（1..6）；`QsoProgress` 新增 `order` / `noReplyCount`；报告快照 `lastSentReport`；「每次重测最新」刷新 + R 复用 Tx2 值；无回应按解码批次计数 |
| `TxCompose.kt` | `TxMessageKind` 改为六步（GRID/REPORT/ROGER/RR73/SEVENTY_THREE/CQ/CUSTOM，带 `order` ）；`kindOf` 六步映射 |
| `TxDrawer.kt` | 消息类型按钮改「1 网格 / 2 报告 / 3 R报告 / 4 RR73 / 5 73 / 6 CQ」两行 |
| 单测 | 新增 `CallMatchTest` 3 例；`QsoEngineTest` 新增 3 例 + 更新 1 例；`TxComposeTest` 更新 3 例 |

**验证**：`:app:testDebugUnitTest` **298 例 / 32 suite 全绿**；`:app:assembleDebug` **BUILD SUCCESSFUL**。

**期 1 的两处工程修正（与初版方案不同）**：

1. **RR73 三重兜底 ③④⑤ 不再需要**：本引擎在收到 `R` / 对撞纯报告 / `respondToRoger` 时**立即**进入 `RR73` 并 `finish()`，`RR73` 从来不是滞留态（`syncState` 把 RR73 映射为 DONE）。因此 FT8CN 那三条「卡在 RR73」的兜底在本引擎**结构上不可达**，加进去只是死代码。防跑死由「收敛阶梯 + 逐条自听过滤」承担。
2. **`isFrom` 改为双向对称**（FT8CN 的超集）：FT8CN 只在**目标**带 `/` 时用 `contains`；本实现改为「任一方带 `/` 即用 `contains`」，避免「对方先以裸呼号出现、后续改用 `/P`」时被判成陌生人而漏应答。
3. **`noReplyLimit` 参数已预留**（`QsoEngine.configure(...)`），但引擎自身的放弃仍用 `retries`（与设置项 `retryLimit` 相连）；期 2 会把它切到 `noReplyCount` 并迁到第 2 层「换台」逻辑。

### 期 2（本轮已完成，含期 3 的 VM 部分 + 期 4 的 UI 部分）

> 期 2 原设计只动 `AutoProgram.kt`，但新设置模型（`AutoProgramSettings` 四项）会级联
> `SettingsRepository` / `SessionViewModel` / `AutoProgramDialog` / `MainShell` / `SettingsScreen` /
> `TxDrawer` / `AppChrome`，无法只改调度层就让构建通过；因此本轮把**期 2 + 期 3（VM 逻辑）
> + 期 4（设置面板与删确认框）**一起做完，只剩 **Room 日志字段**与**解码列表菜单**未做。

| 文件 | 改动 |
| --- | --- |
| `qso/AutoProgram.kt`（重写） | 删 `AutoMode`/`AutoSort`/`DecodeTiming`/`MIXED_CQ_NO_REPLY_LIMIT`；`AutoProgramSettings(supervisionMinutes=10, noReplyLimit=0, autoFollowCq=true, autoCallFollow=true)` + `SUPERVISION_MINUTES`/`NO_REPLY_LIMIT_RANGE`；`AutoTarget` 加 `cqModifier`；`collect` 定向一律入选（不受筛选/已通联/开关）、CQ 台按 `autoFollowCq` 且硬编码跳过已通联、去 `DecodeFilter.matches` 与距离排序；`rank` 按 `DX > 我所在区域 > 其它修饰符 > 无`（稳定保序）+ `areaOfGrid` 粗判大洲；`AutoScheduler` 去 `phase`/`paused`/`txAccumMs`/`pause`/`resume`，监管超时→`Stop`，新增 `onTargetGaveUp`（超限换台/回 CQ）、`enable`/`disable`/`resetSupervision`/`markProtectedStop` |
| `qso/QsoEngine.kt` | 退役 `retryLimit` 体系：删 `maxRetries`/`giveUp`/`FAILED(重试耗尽)`/`retries`/`awaitingReplySinceTx` 与放弃分支；`configure(myCall, myGrid)`；`noReplyCount` 保留（第 2 层据此换台）；深度解码 `m.deep` 不推进、纯 deep 批次不计无回应 |
| `engine/DecodeResult.kt` | 类体新增 `deep: Boolean = false`（钩子；**不动 JNI 构造签名**，当前恒 false） |
| `qso/DecodeHighlight.kt` | `WorkedIndex.plus(call, grid)` 增量合并，供落库后实时生效 |
| `data/settings/SettingsRepository.kt` | 四个新键 `auto_supervision_minutes` / `auto_no_reply_limit` / `auto_follow_cq` / `auto_call_follow`；旧键常量保留、**不再读写**（便于回滚） |
| `ui/SessionViewModel.kt` | 删 `setAutoMode`/`phaseLabel`/`manualQso`/`manualIntervention`/`resumeAutoAfterManual`/`stopAutoByProtection`/`lastLoggedMs`/`QSO_LOG_DEDUP_MS`；自动程序闸门改由 `txEnabled` 驱动；`setTxEnabled` 开→`scheduler.enable`、关→`stopTransmit`；`AutoAction.Stop`→`stopAutoBySupervision`（**关总开关**）；每接收批次检查发射监管；`maybeGiveUpTarget` 在 `qsoEngine.onDecoded` 后按 `noReplyLimit` 换台/回 CQ；落库改**会话内去重 + `_worked.plus` 实时**（`stop()`/换波段清空）；`qsoEngine.configure(myCall, myGrid)` |
| `ui/AutoProgramDialog.kt`（重写） | 四项面板（发射监管档位 / 无回应次数 / 两个开关）；**删除 `AutoEnableConfirmDialog`**；`autoProgramSummary` 改四项摘要 |
| `ui/MainShell.kt` / `ui/SettingsScreen.kt` | 删 `confirmAutoMode` / `requestAutoMode` / 确认弹窗调用；面板调用去掉 `onSetMode` |
| `ui/TxDrawer.kt` / `ui/AppChrome.kt` | 去掉 `mode.shortLabel`/`mode.enabled`，改按 `txEnabled` 显示「运行中/待命」；抽屉文案改新口径 |

**验证**：`:app:testDebugUnitTest` **294 例 / 32 suite 全绿**；`:app:assembleDebug` **BUILD SUCCESSFUL**。

**期 2 与初版方案的差异**：

1. **`QsoProgress.retries`/`maxRetries` 一并删除**（原方案列在「删除」清单）；`QsoState.FAILED` 枚举值**保留**但不再由重试产生（仅兼容 UI 引用，异常/中止走 `stop()`→IDLE）。
2. **`QsoEngine.configure` 去掉 `noReplyLimit` 参数**：上限判断完全在第 2 层（`AutoScheduler` + VM `maybeGiveUpTarget`），引擎只提供 `noReplyCount`。
3. **发射监管检查点**除了 `onDecoded`/`onQsoFinished`/`onTargetGaveUp`，还在 VM 每个接收批次统一检查一次（含进行中的 QSO 与仅自听批次），避免「对静默伙伴无限重试」时监管永不触发。
4. **`DecodeResult.deep` 放类体**（不动主构造器），保证 native JNI 构造签名 `(Ljava/lang/String;IFIIJ)V` 不变。

### 期 3（数据层）已完成

| 文件 | 改动 |
| --- | --- |
| `data/log/QsoEntity.kt` | 新增 `startUtcMs`（通联起始时间＝ADIF `QSO_DATE`/`TIME_ON`，`@ColumnInfo(defaultValue = "0")`）；`utcMs` 语义明确为**完成时间**（＝`QSO_DATE_OFF`/`TIME_OFF`）。波段 `band` 与基频 `freqHz` 原本已有 |
| `data/log/AppDatabase.kt` | `version = 2` + `MIGRATION_1_2`（`ALTER TABLE qso ADD COLUMN startUtcMs INTEGER NOT NULL DEFAULT 0`），`addMigrations(...)`；旧记录 `startUtcMs = 0` ⇒ 导出时回退为完成时间，**既有日志导出内容不变** |
| `qso/QsoEngine.kt` | 记录本段 QSO **起始时间**（`start*` 新增可选 `utcMs` 参数 → `QsoLogEntry.startUtcMs`；取不到时回退为完成时间）；`respondToRoger` 起止相同 |
| `data/adif/AdifRecord.kt` / `AdifMapper.kt` | 读写 `QSO_DATE_OFF`/`TIME_OFF`：导入 `TIME_ON`→`startUtcMs`、`TIME_OFF`（缺省回退 `TIME_ON`）→`utcMs`；导出仅在 `utcMs > startUtcMs` 时写 OFF，避免老记录往返变化 |
| `data/log/QsoMerge.kt` | 合并时回填 `startUtcMs`（已有则保留） |
| `ui/SessionViewModel.kt` | 落库带 `startUtcMs`；`start*` 调用传入 `AudioEngine.utcNowMs()` |
| `ui/LogScreen.kt` | 手动新增 / 编辑保留 `startUtcMs` |

### 期 4（UI）已完成

| 文件 | 改动 |
| --- | --- |
| `qso/AutoProgram.kt` | 新增 `AutoProgramSelector.toTarget(msg, myCall)`：单条解码 → 目标分类（CQ / 定向网格 / 定向报告 / 定向 R；自听与 `73` 类返回 null），**不受**两开关 / 已通联 / 显示筛选影响 |
| `ui/SessionViewModel.kt` | 新增 `replyTo(row)`：把一行解码按报文类型交给第 1 层（CQ→应答；网格→报告；报告→R 报告；R→RR73 收尾），人工操作只复位发射监管 |
| `ui/DecodeList.kt` | 长按菜单补齐 **呼叫 / 回复 / 查看日志**（＋复制消息 / 忽略；删除无用的「加宏（U3）」占位）；**不加手动 73**。左滑与菜单「呼叫」统一走 `onCall` |
| `ui/OperateScreen.kt` | 接线三项菜单；`onOpenLog` 改为携带呼号 |
| `ui/MainShell.kt` / `ui/LogScreen.kt` | 「查看日志」跳日志页并**预填搜索呼号**（`focusCall` + `focusSeq` 触发） |

**验证**：`:app:testDebugUnitTest` **303 例 / 32 suite 全绿**；`:app:assembleDebug` **BUILD SUCCESSFUL**。

### 期 5（文档）

`README`（计数已同步 310）/ `How2use`（§11 单档、§12 解码菜单、§13 日志起止时间）/ `FT8CN_QSO_PLAN` §10.5 已同步；
`REGRESSION.md` 已加 **T 组**（期 2 新口径真机回归清单）与 **U 组**（解码菜单 / 日志起止时间），
并把 E 组标注为过期、A 组与「模拟器可预演」段落改到新口径。剩余：`new_ui.md`、`NEW-UI-PLAN.md`、
`UI-DESIGN.md` 等**历史设计稿**中的旧档位 / 确认框描述（不影响使用，可择机统一标注）。

### 期 5 补记（2026-09-27）：`NEW_QSO_.md` 改版 + 补齐「循环 2」

- `docs/NEW_QSO_.md` 从「对标 JTDX」整体改写为 **v4.0「对标 FT8CN」**：删除 AutoSeq 档位、
  DX 分级选台、LoTW 呼号集、Auto RX filter、QSO history、半自动 Log 确认、看门狗等 FT8CN 没有的机制。
  `README` / `ROADMAP` / `REGRESSION` 中的相关引用同步更新。
- **补齐真差距**（FT8CN `checkCQMeOrFollowCQMessage` 循环 2）：有进行中 QSO 且**当前目标本批沉默**时，
  也要应答「其他呼叫我方」的定向台。落地：
  - `QsoEngine` / `QsoProgress` 新增 `advanced`（本次 `onDecoded` 是否推进）；
  - `AutoScheduler.directedTakeover(...)` 挑「非当前目标」的定向候选；
  - `SessionViewModel.pollOnce` 在 `!advanced` 且仍 active 时先换台、未命中才走无回应判定。
- 新增单测 6 例（`AutoProgramTest` 4 + `QsoEngineTest` 2），全库 **310 例 / 32 suite**。
- **语义修正（不加关注名单、不加 UI）**：核对 FT8CN 源码后发现此前口径有误——`autoFollowCQ`
  实为「把解码到的 CQ **推送到呼叫列表**」（不写入关注名单），`autoCallFollow` 才是「是否自动呼叫 CQ」；
  FT8CN 另有一份 `followCallsigns` 关注名单（本机不做）。据此：`nextAction` 改为只由
  `autoCallFollow` 把关（`collect` 已按 `autoFollowCq` 过滤，**行为等价**、职责清晰），
  `AutoProgramDialog` 文案、`FT8CN-QSO.md` §7/§12、`NEW_QSO_.md`、`How2use.md` §11 全部更正。

---

## 11. 一句话总结

> 把 Ft8Vox 的 QSO 从「语义状态机 + 多档自动程序 + 重试限制」换成
> **FT8CN 的「六步 order 序列 + 单档自动程序（总开关即开关）+ 发射监管/无回应限制/RR73 三重兜底」**，
> 保留 Ft8Vox 已有的**逐条自听过滤、无时窗闸门、定向一律应答、收敛兜底**作为防跑死底线，
> 并在 UI 上把抽屉消息类型、设置页四项、解码列表菜单对齐 FT8CN。
