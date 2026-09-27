# FT8CN 的 QSO 逻辑剖析

> 对象：`D:\Desktop\ft8cn_wty\FT8CN`（BG7YOZ/N0BOY 的 FT8CN，Android / Java 版）
> 目的：搞清 FT8CN 到底用的是什么 QSO 模型，为 Ft8Vox 按 [`NEW_QSO_.md`](./NEW_QSO_.md)（对标 JTDX）重构提供**真实实现的对照基准**。
> 说明：本文所有结论均给出源码位置（`文件:行号`），行号以本仓库当前副本为准。

---

## 0. 结论速览（TL;DR）

1. **FT8CN 不是 JTDX 那种"多档 AutoSeq（1/2/3/4+）"模型**，而是**单一自动程序 + 单一开关**：
   - 全部自动决策集中在一个方法：`FT8TransmitSignal.parseMessageToFunction()`（`ft8transmit/FT8TransmitSignal.java:808`）。
   - 只有一个分层开关：`autoCallFollow`（自动呼叫关注的呼号）与 `autoFollowCQ`（自动关注所有 CQ）。
   - 没有"仅主叫 / 仅应答 / 半自动 / 全自动"的档位概念，也没有 Log QSO 确认卡。
2. **六步指令序列** `functionOrder = 1..6`，应答方从 1 开始、主叫方固定 6，逐级 +1 收敛（`FT8TransmitSignal.java:251`）。
3. **角色（CQ 方 / 应答方）不是显式状态机**，而由两个变量隐式表达：`functionOrder` 是否为 6、以及 `toCallsign.callsign` 是否为 `"CQ"`。
4. **"有人呼叫我一定应答"是硬实现**：`checkCQMeOrFollowCQMessage()` 的第二个循环，只要报文 `to` 是我、且不是 73，就立刻回包（`FT8TransmitSignal.java:697`）。
5. **无回应限制 / 发射监管**是它仅有的两个"安全阀"；并针对 RR73 卡死写了**三重兜底**（`FT8TransmitSignal.java:832`）。
6. **完成即落库、落库后不关发射**：收到 73/RR73 时 `doComplete()` 直接写日志，且 `activated` 保持为真（只有"发射监管超时"才自动关）。

---

## 1. 文件与数据地图

| 文件 | 职责 |
| --- | --- |
| `ft8transmit/FT8TransmitSignal.java` | **核心**。发射时序、六步报文生成、自动程序 `parseMessageToFunction`、完成判定、日志落库触发 |
| `ft8transmit/TransmitCallsign.java` | 当前目标：呼号 / 频率 / 时隙 / 我测到的 SNR |
| `ft8transmit/QslRecordList.java` | 一次运行内的通联记录表（按呼号索引、去重、`saved` 标记） |
| `ft8transmit/GenerateFT8.java` / `FunctionOfTransmit.java` | 把指令序号编码成 FT8 报文 |
| `GeneralVariables.java` | 全局参数与判据函数：`checkFun1..5`、`checkFunOrder`、无回应限制、发射监管、关注列表、QSL 呼号表 |
| `Ft8Message.java` | 报文模型：`getSequence()`、`checkIsCQ()`、`getFromCallTransmitCallsign()` / `getToCallTransmitCallsign()` |
| `timer/UtcTimer.java` | 时隙心跳与 `sequential` 计算、NTP 校时 |
| `MainViewModel.java` | 解码回调 → 驱动 `parseMessageToFunction`；构建"呼叫列表" `findIncludedCallsigns` |
| `ui/MyCallingFragment.java`、`ui/CallingListFragment.java` | 人工入口：CQ、呼叫、应答、73、右键菜单 |

---

## 2. 六步指令序列（functionOrder 1..6）

来源：`FT8TransmitSignal.getFunctionCommand(int order)`（`FT8TransmitSignal.java:251`）。

| order | 报文 | 语义 | 报告来源 |
| :---: | --- | --- | --- |
| **1** | `<对方> <我> <4位网格>` | 应答 CQ / 首次呼叫对方 | 发送前 `resetTargetReport()` |
| **2** | `<对方> <我> <±dd>` | 发给对方的信号报告 | `toCallsign.snr`（我测到的对方 SNR）|
| **3** | `<对方> <我> R±dd` | 回 R 报告 | `toCallsign.snr` |
| **4** | `<对方> <我> RR73` | 收尾 | — |
| **5** | `<对方> <我> 73` | 收尾 | — |
| **6** | `CQ <我> <网格>` | 主叫 | `resetTargetReport()` |

要点：

- **order 2 与 3 用的是同一个值** `toCallsign.snr`（即"我最初测到对方的 SNR"），发送前不重测（`FT8TransmitSignal.java:260`、`:266`）。
- `generateFun()`（`:293`）一次性把 1..6 全部生成进 `functionList`；**唯独 order 6 只生成一条**（`break`，`:298`）。
- `generateFun()` 里会把 `noReplyCount = 0`（`:295`）——这是"重置无回应计数"的唯一入口之一。
- 判据函数（`GeneralVariables.java:376`、`:391`、`:399`、`:407`、`:420`、`:438`、`:443`）：

  | 函数 | 命中条件 | 对应 order |
  | --- | --- | :---: |
  | `checkFun5` | `extraInfo == "73"` | 5 |
  | `checkFun4` | `extraInfo ∈ {"RR73","RRR"}` | 4 |
  | `checkFun3` | `R` 开头且后段可解析为整数（`R-10`） | 3 |
  | `checkFun2` | 可解析为整数且 `!= 73`（`-10`） | 2 |
  | `checkFun1` | 匹配 `[A-Z][A-Z][0-9][0-9]` 且不是 `RR73`，**或空串** | 1 |
  | `checkIsCQ` | `callsignTo` 的首段是 `CQ`/`DE`/`QRZ` | 6 |

  `checkFunOrder(msg)`：先判 CQ→6，否则 `checkFunOrderByExtraInfo()` 按 **5→4→3→2→1** 的顺序返回，都没命中返回 `-1`（`GeneralVariables.java:376`）。
  > 注意判据是"从高到低"依次命中，所以 `RRR` 会被当 order 4（收尾），`73` 会被当 order 5 而不是 order 2。

---

## 3. 时隙与时序（sequential）

### 3.1 时隙计算

- `UtcTimer.sequential(utc) = (((utc/1000)/15) % 2)`（FT8；FT4 用 7.5 s）（`timer/UtcTimer.java:253`）。
- `Ft8Message.getSequence()` 用 `((utcTime+750)/1000/15)%2`，**加了 750 ms 半槽补偿**（`Ft8Message.java:324`）——即"这份报文属于哪个时隙"。
- 心跳：`secTimer` 每 100 ms 检查 `((utc-time_sec)/100)%600 % sec == 0`，命中后触发 `doOnSecTimer` 并 `sleep(1000)` 防重复（`UtcTimer.java:166`）。
- `time_sec` 就是 `transmitDelay`（默认 500 ms，`GeneralVariables.java:179`），注释明确写"这个时间也是给上一个周期的解码时间"（`ConfigFragment` 更改时同步 `setTimer_sec`，`:159`）。

### 3.2 发射时隙 = 目标出现时隙的相反槽

`setTransmit()` 里写死：

```java
sequential = (toCallsign.sequential + 1) % 2;   // FT8TransmitSignal.java:231
```

其中 `toCallsign.sequential` 是"目标报文自身的时隙"。因此：

- **应答一个 CQ**：目标报文在槽 X → 我在 `1-X` 发射（正确）。
- **"呼叫 to 方"路径**：`Ft8Message.getToCallTransmitCallsign()` 已经先翻过一次（`Ft8Message.java:524`），`setTransmit` 再翻一次，净效果是**回到原始槽**。这是源码里一处容易被误读的"双重取反"，接入时需实测确认。

### 3.3 每秒 tick 的发射闸门

`doOnSecTimer`（`FT8TransmitSignal.java:126`）：

```java
if (isLaunchSupervisionTimeout()) { setActivated(false); return; }   // 监管超时先关发射
if (getNowSequential() == sequential && activated) doTransmit();     // 到我的槽且开着才发
```

`transmitNow()`（人工立即发射，`:151`）：要求 `getNowSequential() == sequential` 且距槽边界 `< 2500 ms`，否则不发。

---

## 4. 决策入口：`parseMessageToFunction(msgList)`

来源：`FT8TransmitSignal.java:808`。调用点：`MainViewModel.java:324`，外层还有三重闸门（`:318`）：

```java
if (!isTransmitting() && !isDeep
        && (timeSec + pttDelay + transmitDelay <= 2000)) {   // 距周期起点 >2s 就不再决策
    parseMessageToFunction(messages);
}
```

方法内部按**严格顺序**逐级判定：

### 第 0 级：前置过滤
1. `myCallsign.length() < 3` → 返回（呼号未配置）。
2. `msgList` 为空 → 返回。
3. **`msgList.get(0).getSequence() == sequential` → 返回**（`:814`）——这批报文属于"我发射的那个时隙"，不可能含对方对我的应答，直接丢弃。

### 第 1 级：找出对方给我的回复序号
`checkFunctionOrdFromMessages(messages)`（`:604`）：

- 从后往前扫，跳过 `getSequence() == sequential` 的报文（同槽）；
- 命中条件：`to 是我` **且** `from == 当前目标呼号`（`checkCallsignIsCallTo`，支持带 `/` 的复合呼号，`:564`）；
- 命中时顺手记录：
  - `sendReport = 该报文的 snr`（**我测到的对方 SNR**，将来作为我发给对方的报告，`:627`）；
  - `receiveTargetReport / receivedReport = 对方给我的信号报告`（`checkFun2/3` 命中时，`:618`）；
- 返回 `checkFunOrder(该报文)`（1..6），找不到返回 `-1`。

`newOrder != -1` 时，**清零无回应计数** `noReplyCount = 0`（`:821`）。

### 第 2 级：更新/落库通联记录
`updateQSlRecordList(newOrder, toCallsign)`（详见 §9）。**order 为 4/5 时即写日志**。

### 第 3 级：完成判定（5 路 OR，任一命中即"收尾回 CQ"）
（`:832`）

```text
newOrder == 5                                                  // ① 对方发来 73
|| (functionOrder == 5 && newOrder == -1)                      // ② 我发了 73，对方无回应
|| (functionOrder == 4 && noReplyCount > noReplyLimit*2
    && noReplyLimit > 0)                                       // ③ 卡在 RR73，超阈值×2
|| (functionOrder == 4 && checkTargetCallMe(messages) > 1)     // ④ 卡在 RR73，对方已转呼别人
|| (functionOrder == 4 && noReplyCount > 20 && noReplyLimit==0)// ⑤ 忽略无回应时，防 RR73 死锁
```

命中后：

```java
resetToCQ();                          // 目标改为 "CQ"，functionOrder = 6
checkCQMeOrFollowCQMessage(messages); // 同一批报文里看有没有人呼叫我/我关注的 CQ
setCurrentFunctionOrder(functionOrder);
return;
```

> `checkTargetCallMe()`（`:578`）：有目标呼叫我 → `0`；没有任何目标的消息 → `1`；目标在呼别人 → `>1`。
> ⑤ 的注释写"大于 10 次"，实际代码是 `> 20`（`:840`）。

### 第 4 级：收到回复但未完成 → 推进一格
（`:855`）

```java
if (newOrder == 1 || newOrder == 2) { resetTargetReport(); generateFun(); }
functionOrder = newOrder + 1;
// 更新 UI、设置当前指令
return;
```

即收敛阶梯：

```text
收到 网格(1)  → 发 报告(2)
收到 报告(2)  → 发 R报告(3)
收到 R报告(3) → 发 RR73(4)
收到 RR73(4)  → 发 73(5)
收到 73(5)    → 走第 3 级"完成判定"
```

### 第 5 级：没有回复 → 看有没有人呼叫我
`checkCQMeOrFollowCQMessage(messages)` 返回 true 就结束（详见 §5）。

### 第 6 级：我在 CQ
`functionOrder == 6` 时再调一次 `checkCQMeOrFollowCQMessage()` 后返回（**CQ 状态下永远不放弃主叫，也不会自增无回应计数**，`:879`）。

### 第 7 级：无回应累计与"换台"
（`:885`）

```java
if (!messages.get(0).isWeakSignal) noReplyCount++;     // 弱信号(深度解码)不计数
if (noReplyCount > noReplyLimit && noReplyLimit > 0) {
    if (!getNewTargetCallsign(messages)) {             // 到关注列表里找新的 CQ 台
        functionOrder = 6; toCallsign.callsign = "CQ"; // 找不到就自己 CQ
    }
    generateFun(); // 同时把 noReplyCount 清零
}
```

> `getNewTargetCallsign()`（`:911`）：在关注列表里挑「同波段 + 是 CQ + 不是当前目标 + 之前没通联成功过」的台，命中则切到 order 1。

---

## 5. "有人呼叫我一定应答"——`checkCQMeOrFollowCQMessage`

来源：`FT8TransmitSignal.java:673`。三个循环，**优先级从高到低**：

### 循环 1：优先回"我的当前目标"
扫描 `to 是我`、`from == 当前目标`、且不是 73 的报文 → `setTransmit(order = checkFunOrder(msg)+1)` 并返回 true（`:678`）。
> 目的（源码注释）：多个台同时呼叫我时，**避免回复对象漂移**，先锁定当前目标。

### 循环 2：任何人呼叫我，一律应答 ★
扫描 `to 是我`、且不是 73 的报文 → 立刻 `setTransmit(checkFunOrder(msg)+1)`（`:697`）。
> **这就是 FT8CN 的"一定应答"**：不看是否已通联、不看显示筛选、不看是否有目标——只要 `to` 解析出我方呼号，就回。
> 例外仅两个：`isExcludeMessage()` 过滤（同槽 / 异波段 / 排除字头）与 73。

### 循环 3：S&P 自动应答关注的 CQ（仅当"当前无目标"）
- `!autoCallFollow` → 直接返回 false（`:714`）。
- `toCallsign == null` 或 `haveTargetCallsign()`（已有目标）→ 返回 false（`:718`、`:722`）——**有目标就不换**。
- 遍历关注列表 `transmitMessages`，挑：`是 CQ` +（`autoCallFollow && autoFollowCQ` 或 我关注）+ 之前没通联成功 + 不是我自己 → `setTransmit(..., 1, ...)`（`:728`）。

### 报文过滤 `isExcludeMessage`（`:661`）

```java
msg.getSequence() == sequential      // 与我发射同槽
|| msg.band != GeneralVariables.band // 异波段
|| checkIsExcludeCallsign(from)      // 排除字头
```

---

## 6. 呼叫列表与关注呼号

`MainViewModel.findIncludedCallsigns()`（`MainViewModel.java:520`）：

```java
if (isActivated() && sequential != getNowSequential()) return;   // 发射中且不在我的槽，不刷新
若 满足以下任一：
    to/from 含我呼号
    || from 在关注列表
    || to 在关注列表
    || (autoFollowCQ && 该报文是 CQ)
并且 不是排除字头
→ 加入 transmitMessages，并标记 isQSL_Callsign = checkQSLCallsign(from)
```

- `autoFollowCQ`（默认 true，`GeneralVariables.java:205`）：自动收纳所有 CQ 进列表。
- `autoCallFollow`（默认 true，`:206`）：自动去呼叫列表里的 CQ。
- `callsignInFollow()`：是否在关注列表（`:327`）。
- `checkQSLCallsign()`：是否在本波段已通联成功（`QSL_Callsign_list`，`:273`）。

---

## 7. 人工接管入口

| 动作 | 入口 | 行为 |
| --- | --- | --- |
| 呼叫某台（`to` 方） | 呼叫列表/发射界面 菜单 case 1（`CallingListFragment.java:313`、`MyCallingFragment.java:71`） | `addFollowCallsign` → `setActivated(true)` → `setTransmit(getToCallTransmitCallsign(), 1)` → `transmitNow()` → `resetLaunchSupervision()` |
| 回复（order = -1） | 菜单 case 4 | `setTransmit(..., -1, extraInfo)`，由 `checkFunOrderByExtraInfo(extraInfo)+1` 推断下一步（`:214`） |
| 发起 CQ | 「CQ」按钮 → `restTransmitting()`（`:982`） | 重设为 order 6，`setTransmit(..., 6, "")` |
| 关发射 | `setActivated(false)` | 同时 `setTransmitting(false)`（`:945`），并通知 UI |
| 复位监管 | `resetLaunchSupervision()` | 重置 `launchSupervisionStart`（`GeneralVariables.java:352`） |

> 说明：**人工操作不会暂停自动程序**。FT8CN 没有"手动接管（paused）"概念，人工设定只是把发射目标/指令改掉，自动程序下一批解码照旧运行。

---

## 8. 安全阀与防卡死

| 机制 | 位置 | 说明 |
| --- | --- | --- |
| **发射监管** `launchSupervision` | `GeneralVariables.java:192`、`FT8TransmitSignal.java:128` | 默认 10 分钟；可选 0（不监管）/ 5/15/…/95 分钟；超时自动 `setActivated(false)` |
| **无回应限制** `noReplyLimit` | `GeneralVariables.java:194` | 0..30，0=忽略；超出后退回 CQ 或换台 |
| **无回应计数** `noReplyCount` | 同上 `:196` | `generateFun()` 时清零；收到回复清零；弱信号不累加 |
| **RR73 三重兜底** | `FT8TransmitSignal.java:834`、`:838`、`:840` | 阈值×2 / 对方已转呼别人 / 忽略时 >20 次 |
| **同槽过滤** | `:814`、`:661`、`:607` | 三层都在丢弃"我发射那个时隙"的报文 |
| **排除字头** | `GeneralVariables.java:94` | 前缀黑名单 |
| **WSPR2 频点保护** | `FT8TransmitSignal.java:177` | 落在 WSPR-2 频点拒绝发射并自动关发射 |
| **深度解码不触发自动** | `MainViewModel.java:319` | `isDeep` 的解码不驱动自动程序（避免重复/延迟决策） |

---

## 9. 通联记录与日志落库

数据结构：`QslRecordList`（`QslRecordList.java`），以"对方呼号"为唯一键：

- `getRecordByCallsign` / `addQSLRecord`（已存在则 `oldRecord.update(record)`）；
- `record.saved` 标记防止重复入库；
- `deleteIfSaved()` 在 order 1/2/3 更新时**移除已保存的旧记录**（`:779`、`:786`）。

`updateQSlRecordList(order, toCall)`（`FT8TransmitSignal.java:753`）：

| order | 动作 |
| :---: | --- |
| 1 | 更新 `toMaidenGrid`、`sendReport` |
| 2 / 3 | 更新 `sendReport`、`receivedReport` |
| **4 / 5** | 若 `!record.saved` → **`doComplete()`** 并置 `saved = true` |

`doComplete()`（`:475`）会**回查历史报文**修正报告：
- 遍历 `transmitMessages` 找"对方→我"的报告 → `receiveTargetReport`；
- 遍历找"我→对方"的报告 → `sentTargetReport`；
- 最终入库的报告优先级：`sentTargetReport != -100 ? sentTargetReport : sendReport`（收方同理）（`:523`、`:524`）；
- 通过 `onTransmitSuccess.doAfterTransmit(QSLRecord)` 回调写数据库。

> **关键差异**：FT8CN 在收到 RR73/73 的**同一批解码里就直接落库**，不弹确认卡，也不关发射总开关。

---

## 10. FT8CN 已知的"跑死/不应答"风险点（诚实记录）

1. **外层 2 秒闸门会漏报文**：`MainViewModel.java:318` 要求 `timeSec + pttDelay + transmitDelay <= 2000`。若解码耗时超过 2 s，定向报文（含呼叫我方的）**整批不进入自动程序** → 表现为"有人叫我却不回"。
2. **深度解码被排除**：`isDeep` 的报文不进自动程序（`:319`），只为 UI 展示。
3. **`msgList.get(0).getSequence() == sequential` 整体丢弃**：若一批解码的首条属于其它时隙、而同一批里夹着我的目标回复，会被整批误伤（首条判定，不是逐条判定）。
4. **`checkFunctionOrdFromMessages` 只认"from == 当前目标"**：如果对方换了呼号形态（加 `/`），`checkCallsignIsCallTo` 只会用 `contains` 做宽松匹配（`:564`），极端情况仍可能认不出。
5. **无"手动接管期间也应答"的概念**：人工设定 target 后，自动程序仍会因上述闸门漏报文。
6. **`restTransmitting()` 用 `getNowSequential()` 作 `TransmitCallsign.sequential`，再被 `setTransmit` 取反**，实际发射槽需实机验证（与 §3.2 的"双重取反"同类疑点）。

---

## 11. 与 JTDX（`NEW_QSO_.md`）的对照

| 维度 | FT8CN 实际实现 | `NEW_QSO_.md` 对标 JTDX |
| --- | --- | --- |
| 自动化档位 | 无档位。自动程序常开，仅 `autoCallFollow`/`autoFollowCQ` 两个布尔 | 多档：AutoSeq 1 / 2 / 3 / 4+ |
| 角色模型 | 隐式：`functionOrder==6` 即主叫；无独立"CQ 方/应答方"状态 | 显式区分 Tx1a(CQ) / Tx1b(网格应答) |
| 指令编号 | 1=网格、2=报告、3=R、4=RR73、5=73、6=CQ | Tx1a=CQ、Tx1b=网格、Tx2=报告、Tx3=R、Tx4=RR73、Tx5=73 |
| 选台策略 | 关注列表 + `autoFollowCQ`；**无 DX 分级**，无 LoTW / 新实体优先 | DX 分级主键（新 DXCC→CQ→ITU→网格→前缀…） |
| "一定应答" | **有**：任何 `to==我` 且非 73 立即回（循环 2） | 需在 AutoSeq 中保证 |
| 已通联台 | CQ 台**不**自动应答（`checkQSLCallsign` 过滤，`:736`）；**定向报文一律应答** | 由"允许重复通联"决定 |
| 无回应处理 | `noReplyLimit` 0..30；超出退 CQ / 换台 | 有独立 AutoSeq 重试与放弃逻辑 |
| 完成收尾 | 收到 73/RR73 **直接落库、不关发射** | 弹 Log QSO 确认卡 +（文档要求）关发送总开关 |
| 手动接管 | 无 paused 概念，人工设 target 不暂停自动程序 | 手动接管 = 临时接管第 1 层 |
| 安全阀 | 发射监管（超时关发射）+ 无回应限制 | 半自动安全阀（多档） |
| 深度解码 | 不驱动自动程序 | — |

---

## 12. 对 Ft8Vox 的启示（可直接借鉴 / 需规避）

**可直接借鉴**

1. **§5 循环 2 的"定向报文一律立即应答"**，且**优先级高于 paused/显示筛选**——与 Ft8Vox 已有方向一致（`AutoScheduler.onDecoded` 定向分支提到 `paused` 之前）。FT8CN 把它做成**不含任何例外**（除同槽/异波段/黑名单/73），值得作为验收基准。
2. **§5 循环 1 的"当前目标优先"**：多台同时呼叫我方时先回当前目标，避免"回复对象漂移"。
3. **§5 循环 3 的"有目标就不换"**：S&P 只在无目标时启动，避免抢当前通联。
4. **RR73 三重兜底**思路（阈值×2 / 对方已转呼别人 / 忽略时 >20 次）可作为 Ft8Vox"两台都不跑死"的补充判据。
5. **报告回查**：`doComplete()` 落库前回查历史报文修正报告值，比"用最后一刻的缓存值"更稳。
6. **弱信号（深度解码）不计无回应**：避免因为一次深度解码把重试计数打乱。

**需规避 / 不能照抄**

1. **§10.1 的 2 秒闸门**与 **§10.3 的首条整批丢弃**，正是"不应答"的典型根因。Ft8Vox 的 `collect` 已是**逐条**判断 `addressedTo(myCall)`，应坚持逐条。
2. **无 paused / 无半自动确认**不符合 Ft8Vox 已定的交互（手动接管、`AutoEnableConfirmDialog`），不应引入 FT8CN 的"人工不暂停自动"模型。
3. **完成即落库不确认**与 Ft8Vox 的 Log QSO 流程不同，仅借鉴"何时算完成"，不借鉴"如何收尾 UI"。
4. **无 DX 分级选台**：FT8CN 的选台过于朴素（关注列表 + CQ），`NEW_QSO_.md` 的 DX 分级应照 JTDX 实现，而不是照 FT8CN。

---

## 附录：一句话总结

> FT8CN 的 QSO 是 **"六步指令序列 + 单一自动程序 + 两个安全阀"** 的半自动模型；
> 它的杀手锏是"**任何 `to==我`（非 73）的报文一律立即应答**"（`FT8TransmitSignal.java:697`），
> 它的最大隐患是"**自动程序被 2 秒时窗、深度解码、首条整批判定三道闸门挡在门外**"（`MainViewModel.java:318`）。
> Ft8Vox 应保留前者的"一定应答"语义，同时用逐条判定 + 不设时窗闸门来消除后者的漏应答。
