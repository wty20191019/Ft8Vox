# FT8CN 的 QSO 逻辑剖析（实现基准）

> 对象：`D:\Desktop\ft8cn_wty\FT8CN`（BG7YOZ / N0BOY 的 FT8CN，Android / Java）
> 副本：HEAD `785f643`（`F_FT8CN` 分支）。本文所有 `文件:行号` 均以该副本逐行复核为准。
> 用途：FT8CN 的 QSO 模型是 Ft8Vox **已采纳的实现基准**（改造口径见 [`FT8CN_QSO_PLAN.md`](./FT8CN_QSO_PLAN.md)）。本文记录它**实际怎么跑**，是编码与真机回归的对照源；不是设计稿、不是愿望清单。

---

## 0. 结论速览（TL;DR）

一句话：**FT8CN 的 QSO ＝「六步指令序列（order 1..6）＋ 单一常开自动程序 ＋ 两个安全阀」，角色隐式、应答强制、完成即落库。**

1. **不是 JTDX 那种「多档 AutoSeq」**。全部自动决策集中在一个方法：`FT8TransmitSignal.parseMessageToFunction()`（`ft8transmit/FT8TransmitSignal.java:808`）。
   只有一个分层开关对：`autoFollowCQ`（是否收纳 CQ 台，`GeneralVariables.java:205`）与 `autoCallFollow`（是否自动去呼叫，`:206`）。
   没有「仅主叫 / 仅应答 / 半自动 / 全自动」档位，也没有 Log QSO 确认卡。
2. **六步指令序列** `functionOrder = 1..6`（`getFunctionCommand()`，`FT8TransmitSignal.java:251`）。应答方从 1 起步，主叫方恒为 6，逐级 `+1` 收敛。
3. **角色是隐式的**，由两个变量表达：`functionOrder` 是否 `== 6`、以及 `toCallsign.callsign` 是否 `"CQ"`（`TransmitCallsign.java:44`）。没有独立的「CQ 方 / 应答方」状态对象。
4. **「有人呼叫我一定应答」是硬实现**：`checkCQMeOrFollowCQMessage()` 的第 2 个循环，只要报文 `to` 解析出我方呼号、且不是 `73`，立即按对方报文序号 `+1` 回包（`FT8TransmitSignal.java:697`）。
5. **安全阀只有两个**：发射监管（`launchSupervision`，超时 `setActivated(false)`）与无回应限制（`noReplyLimit`）；另针对 RR73 卡死写了**三重兜底**（`:834`、`:838`、`:840`）。
6. **完成即落库、落库不关发射**：收到 73/RR73 时 `doComplete()` 直接写库（`:475`），`activated` 保持为真——**只有发射监管超时才会自动关发射**。

---

## 1. 文件与数据地图

| 文件 | 职责 |
| --- | --- |
| `ft8transmit/FT8TransmitSignal.java` | **核心**。发射时序、六步报文生成、自动程序 `parseMessageToFunction`、完成判定、日志落库触发 |
| `ft8transmit/TransmitCallsign.java` | 当前目标：呼号 / 频率 / 时隙 / 我测到的 SNR；`haveTargetCallsign()` 判空 |
| `ft8transmit/QslRecordList.java` | 一次运行内的通联记录表（按对方呼号索引、去重、`saved` 标记、`deleteIfSaved`） |
| `ft8transmit/GenerateFT8.java` | 按呼号类型算 `i3`、生成 FT8 音频 |
| `ft8transmit/FunctionOfTransmit.java` | 一条「指令序号 + 报文 + 是否当前」的封装，供 UI 列表展示 |
| `GeneralVariables.java` | 全局参数与判据函数：`checkFun1..5`、`checkFunOrder`、`noReplyLimit`、`launchSupervision`、关注列表、已通联呼号表 |
| `Ft8Message.java` | 报文模型：`getSequence()`、`checkIsCQ()`、`getFromCallTransmitCallsign()` / `getToCallTransmitCallsign()` |
| `timer/UtcTimer.java` | 时隙心跳与 `sequential` 计算、NTP 校时 |
| `MainViewModel.java` | 解码回调 → 驱动 `parseMessageToFunction`；构建呼叫列表 `findIncludedCallsigns`；落库回调 `doAfterTransmit` |
| `database/DatabaseOpr.java` | 写 `QSLTable`、按波段装载「已通联呼号表」 |
| `log/QSLRecord.java` | 日志记录模型（起止时间、收/发报告、波段、基频、`saved`） |
| `ui/MyCallingFragment.java`、`ui/CallingListFragment.java` | 人工入口：CQ、呼叫、回复、查日志、左滑呼叫/删除、长按菜单 |

### 1.1 `FT8TransmitSignal` 方法清单（行号＝副本行号）

| 方法 | 行 | 职责 |
| --- | :--: | --- |
| 构造器 | `:98` | 绑 PTT / 落库回调；建 `UtcTimer`（心跳里做监管检查与发射闸门） |
| `transmitNow()` | `:151` | 人工「立即发射」：仅当在（取反后的）本槽且距槽边界 `< 2500 ms` 才发 |
| `doTransmit()` | `:172` | 发射动作：`activated` 判定 → WSPR-2 频点保护 → 投 `DoTransmitRunnable` |
| `setTransmit(...)` | `:199` | 设定目标/指令/网格；算 `sequential = (目标槽+1)%2`；`generateFun()` |
| `getFunctionCommand(order)` | `:251` | 六步报文生成（见 §2） |
| `generateFun()` | `:293` | 生成 1..6 指令列表；`noReplyCount = 0`；order 6 只生成一条 |
| `doComplete()` | `:475` | 回查历史报文修正报告 → 构造 `QSLRecord` → 回调落库 → 写已通联呼号 |
| `setCurrentFunctionOrder(order)` | `:542` | 设当前指令；order 1 复位报告；order 4/5 触发落库检查 |
| `checkCallsignIsCallTo(from,to)` | `:564` | 复合呼号宽松匹配 |
| `checkTargetCallMe(messages)` | `:578` | 0=目标呼叫我 / 1=无目标消息 / >1=目标在呼别人 |
| `checkFunctionOrdFromMessages(messages)` | `:604` | 找「对方→我」的回复序号，取 `sendReport` / `receivedReport` |
| `getReportFromExtraInfo(extraInfo)` | `:643` | 从 `R-10` / `-10` 抠出整数，失败 `-100` |
| `isExcludeMessage(msg)` | `:661` | 同槽 / 异波段 / 排除字头 |
| `checkCQMeOrFollowCQMessage(messages)` | `:673` | **三循环**：目标优先 → 任何人呼我必答 → S&P 关注 CQ |
| `updateQSlRecordList(order,toCall)` | `:753` | 按 order 更新记录；order 4/5 且未保存 → `doComplete()` |
| `parseMessageToFunction(msgList)` | `:808` | **自动程序总入口**（见 §5） |
| `getNewTargetCallsign(messages)` | `:911` | 在**当前解码批次**里挑新的 CQ 台换台 |
| `setActivated(b)` | `:945` | 总开关；关时同时 `setTransmitting(false)` |
| `setTransmitting(b)` | `:957` | 发射状态 + 音频暂停/PTT 回调 |
| `restTransmitting()` | `:982` | 人工发 CQ：重设为 order 6 |
| `resetTargetReport()` | `:996` | 收/发报告复位成 `-100` |
| `resetToCQ()` | `:1005` | 回到 order 6（目标是 CQ），不改时隙（`toCallsign` 为 null 时才重算时隙） |
| `DoTransmitRunnable.run()` | `:1047` | 记起始时间 → 取报文 → PTT → `sleep(pttDelay)` → 播音频 |

---

## 2. 六步指令序列（functionOrder 1..6）

来源：`getFunctionCommand(int order)`（`FT8TransmitSignal.java:251`）。

| order | 报文 | 语义 | 报告来源 |
| :---: | --- | --- | --- |
| **1** | `<对方> <我> <4位网格>` | 应答 CQ / 首次呼叫对方 | 进入前 `resetTargetReport()`（`:255`） |
| **2** | `<对方> <我> <±dd>` | 发信号报告 | `toCallsign.snr`（`:260`） |
| **3** | `<对方> <我> R±dd` | 回 R 报告 | `toCallsign.snr`（`:266`） |
| **4** | `<对方> <我> RR73` | 收尾 | — |
| **5** | `<对方> <我> 73` | 收尾 | — |
| **6** | `CQ [修饰符] <我> <网格>` | 主叫 | 进入前 `resetTargetReport()`（`:279`）；修饰符取 `toModifier`（`:282`） |

要点：

- **order 2 与 3 用的是同一个值** `toCallsign.snr`——即「把该台设为/更新为目标那一刻」那条解码的 SNR。**发送前不重测**（`:260`、`:266`）。这就是 Ft8Vox 方案里所说 FT8CN「固定首次」的含义（Ft8Vox 有意改为「每次重测最新」，见 §12）。
- `generateFun()`（`:293`）把 `1..6` 全部生成进 `functionList`，**唯独 order 6 只生成一条**（`:298`-`:300` 的 `break`）。
- `generateFun()` 里 `GeneralVariables.noReplyCount = 0`（`:295`）——这是「无回应计数」的主要清零入口。
- 进入 order 2 / 3 时会顺手把 `sentTargetReport = toCallsign.snr`（`:260`、`:266`），供落库取值。

### 2.1 报文序号判据（`GeneralVariables.java`）

| 函数 | 行 | 命中条件 |
| --- | :--: | --- |
| `checkFun5` | `:443` | `extraInfo == "73"` |
| `checkFun4` | `:438` | `extraInfo ∈ {"RR73","RRR"}` |
| `checkFun3` | `:420` | 首字符 `R`、次字符非 `R`，且余下可 `parseInt`（如 `R-10`） |
| `checkFun2` | `:407` | 至少 2 位、可 `parseInt` 且 `!= 73`（如 `-10`） |
| `checkFun1` | `:399` | 匹配 `[A-Z][A-Z][0-9][0-9]` 且不是 `RR73`，**或空串** |
| `checkIsCQ` | `Ft8Message.java:461` | `callsignTo` 的首段是 `CQ` / `DE` / `QRZ` |
| `checkFunOrder` | `:391` | 先 `checkIsCQ`→6，否则 `checkFunOrderByExtraInfo` |
| `checkFunOrderByExtraInfo` | `:376` | 按 **5→4→3→2→1** 顺序判定，全不中返回 `-1` |

> 判据是「从高到低依次命中」，所以 `RRR` 归 order 4（收尾），`73` 归 order 5 而不会落进 order 2。

---

## 3. 时隙与时序（sequential）

### 3.1 时隙计算

- `UtcTimer.sequential(utc) = (((utc/1000)/15) % 2)`（FT8；FT4 用 7.5 s）（`timer/UtcTimer.java:253`）。
- `Ft8Message.getSequence() = ((utcTime+750)/1000/15) % 2`，**加了 750 ms 半槽补偿**（`Ft8Message.java:324`）——回答「这份报文属于哪个时隙」。
- 心跳：`secTimer` 每 100 ms 检查 `((utc-time_sec)/100)%600 % sec == 0`，命中后触发 `doOnSecTimer` 并 `sleep(1000)` 防重复（`UtcTimer.java:166`）。
- `time_sec` 即 `transmitDelay`（默认 500 ms，`GeneralVariables.java:179`），注释写明「这个时间也是给上一个周期的解码时间」。

### 3.2 发射时隙 ＝ 目标出现时隙的相反槽

`setTransmit()` 里写死：

```java
sequential = (toCallsign.sequential + 1) % 2;   // FT8TransmitSignal.java:231
```

其中 `toCallsign.sequential` 是「目标报文自身的时隙」。因此：

- **应答一个 CQ**：目标报文在槽 X → 我在 `1-X` 发射（正确）。
- **「呼叫 to 方」路径**：`Ft8Message.getToCallTransmitCallsign()`（`Ft8Message.java:524`）已经先翻过一次，`setTransmit` 再翻一次，净效果是**回到原始槽**。这是源码中一处易被误读的「双重取反」，接入时以实机为准。

### 3.3 每秒 tick 的发射闸门

`doOnSecTimer`（`FT8TransmitSignal.java:126`）：

```java
if (GeneralVariables.isLaunchSupervisionTimeout()) { setActivated(false); return; }  // 监管超时先关发射
if (UtcTimer.getNowSequential() == sequential && activated) {
    if (myCallsign.length() < 3) { /* 呼号不合法，拒绝发射 */ return; }
    doTransmit();                                                                   // 到我的槽且开着才发
}
```

`transmitNow()`（人工立即发射，`:151`）额外要求「在（取反后的）本槽」且「距槽边界 `< 2500 ms`」（`:163`），否则不发。

---

## 4. 发射驱动链

```text
tick（每 100ms 检查，命中秒边界）
  → doOnSecTimer：监管超时？ / 到我的槽且 activated？
  → doTransmit：activated？WSPR-2 频点？→ 投 DoTransmitRunnable（:1047）
  → DoTransmitRunnable：
        functionOrder ∈ {1,2} 时记 messageStartTime（:1058）
        messageStartTime == 0 时补为当前（:1061）
        msg = getFunctionCommand(functionOrder)（自由文本模式则另取）
        onBeforeTransmit(msg, order)   ← 打开 PTT
        isTransmitting = true
        sleep(GeneralVariables.pttDelay)   // 默认 100 ms，给电台响应时间（:1096）
        playFT8Signal(msg)
  → afterPlayAudio（:462）：onAfterTransmit（关 PTT）、isTransmitting=false、释放 AudioTrack
```

- **起始时间语义**：`messageStartTime` 在「首次发出 order 1 或 2」时落定（`:1058`），就是日志的 `QSO_DATE`/`TIME_ON`。
- **发射时长**：注释说明「考虑网络模式，发射时长是 13 秒」，即 FT8 报文未额外截断。

---

## 5. 决策入口：`parseMessageToFunction(msgList)`

来源：`FT8TransmitSignal.java:808`。调用点：`MainViewModel.java:324`，外层还有三重闸门（`:318`）：

```java
if (!ft8TransmitSignal.isTransmitting()
        && !isDeep                                                   // 深度解码不驱动自动程序
        && (ft8SignalListener.timeSec + pttDelay + transmitDelay <= 2000)) {  // 距周期起点 >2s 不再决策
    ft8TransmitSignal.parseMessageToFunction(messages);
}
```

方法内部按**严格顺序**逐级判定（完整伪代码见 §5.1）：

### 第 0 级：前置过滤
1. `myCallsign.length() < 3` → 返回（呼号未配置，`:809`）。
2. `msgList` 为空 → 返回（`:812`）。
3. **`msgList.get(0).getSequence() == sequential` → 返回**（`:814`）——这批报文属于「我发射的那个时隙」，直接丢弃（**注意：是拿首条判定，不是逐条**）。

### 第 1 级：找对方给我的回复序号
`checkFunctionOrdFromMessages(messages)`（`:604`）：从后往前扫，跳过 `getSequence() == sequential` 的报文；命中条件 `to 是我` 且 `from == 当前目标`（`checkCallsignIsCallTo`，支持目标带 `/`）。命中时顺手记录：

- `sendReport = msg.snr`（我测到的对方 SNR，`:627`）；
- `receivedReport / receiveTargetReport = 对方给我的报告`（`checkFun2/3` 命中时，`:618`-`:626`）；
- 返回 `checkFunOrder(该报文)`（1..6），找不到 `-1`。

`newOrder != -1` 时**清零无回应计数**（`:821`）。

### 第 2 级：更新/落库通联记录
`updateQSlRecordList(newOrder, toCallsign)`（`:753`）。**order 为 4/5 时即写日志**（`:790`）。

### 第 3 级：完成判定（5 路 OR，任一命中即「收尾回 CQ」）

```text
newOrder == 5                                                   // ① 对方发来 73
|| (functionOrder == 5 && newOrder == -1)                       // ② 我发了 73、对方无回应
|| (functionOrder == 4 && noReplyCount > noReplyLimit*2
    && noReplyLimit > 0)                                        // ③ 卡 RR73、超阈值×2
|| (functionOrder == 4 && checkTargetCallMe(messages) > 1)      // ④ 卡 RR73、对方已转呼别人
|| (functionOrder == 4 && noReplyCount > 20 && noReplyLimit==0) // ⑤ 忽略无回应时防 RR73 死锁
```

命中后：

```java
resetToCQ();                            // 目标改为 "CQ"，functionOrder = 6
checkCQMeOrFollowCQMessage(messages);   // 同一批里看有没有人呼叫我 / 我关注的 CQ
setCurrentFunctionOrder(functionOrder);
return;
```

> `checkTargetCallMe()`（`:578`）：有目标呼叫我 → `0`；没有任何目标的消息 → `1`；目标在呼别人 → `>1`。
> ⑤ 的注释写「大于 10 次」，实际代码是 `> 20`（`:840`）。

### 第 4 级：收到回复但未完成 → 推进一格

```java
if (newOrder == 1 || newOrder == 2) { resetTargetReport(); generateFun(); }  // :857-860
functionOrder = newOrder + 1;                                               // :862
setCurrentFunctionOrder(functionOrder);
return;
```

收敛阶梯：

```text
收到 网格(1)  → 发 报告(2)
收到 报告(2)  → 发 R报告(3)
收到 R报告(3) → 发 RR73(4)
收到 RR73(4)  → 发 73(5)
收到 73(5)    → 走第 3 级「完成判定」
```

### 第 5 级：没有回复 → 看有没有人呼叫我
`checkCQMeOrFollowCQMessage(messages)` 返回 true 就结束（`:872`）。

### 第 6 级：我在 CQ
`functionOrder == 6` 时再调一次 `checkCQMeOrFollowCQMessage()` 后返回（`:879`）——**CQ 状态下永远不放弃主叫，也不自增无回应计数**。

### 第 7 级：无回应累计与「换台」

```java
if (!messages.get(0).isWeakSignal) noReplyCount++;          // 弱信号(深度解码)不计数（:886）
if (noReplyCount > noReplyLimit && noReplyLimit > 0) {       // :890
    if (!getNewTargetCallsign(messages)) {                   // 在【当前解码批次】里找新的 CQ 台（:911）
        functionOrder = 6; toCallsign.callsign = "CQ";        // 找不到就自己 CQ
    }
    generateFun();                                           // 同时把 noReplyCount 清零
    setCurrentFunctionOrder(functionOrder);
}
```

> `getNewTargetCallsign()`（`:911`）遍历的是**本批解码**（不是关注列表），条件为「同波段 + 是 CQ + 不是当前目标 + 之前没通联成功过」。

### 5.1 `parseMessageToFunction` 伪代码（按源码顺序）

```text
if myCallsign.len < 3        return
if msgList.empty            return
if msgList[0].seq == seq    return

newOrder = checkFunctionOrdFromMessages(msgList)
if newOrder != -1           noReplyCount = 0
updateQSlRecordList(newOrder, toCallsign)

if (newOrder == 5)
   or (functionOrder == 5 and newOrder == -1)
   or (functionOrder == 4 and noReplyLimit > 0 and noReplyCount > noReplyLimit*2)
   or (functionOrder == 4 and checkTargetCallMe(msgList) > 1)
   or (functionOrder == 4 and noReplyLimit == 0 and noReplyCount > 20):
        resetToCQ(); checkCQMeOrFollowCQMessage(msgList)
        setCurrentFunctionOrder(functionOrder); return

if newOrder != -1:                          # 收到回复、未完成
        if newOrder == 1 or 2: resetTargetReport(); generateFun()
        functionOrder = newOrder + 1
        setCurrentFunctionOrder(functionOrder); return

if checkCQMeOrFollowCQMessage(msgList): return

if functionOrder == 6:
        checkCQMeOrFollowCQMessage(msgList); return

if not msgList[0].isWeakSignal: noReplyCount++      # 无回应计数
if noReplyLimit > 0 and noReplyCount > noReplyLimit:
        if not getNewTargetCallsign(msgList):
                functionOrder = 6; toCallsign.callsign = "CQ"
        generateFun(); setCurrentFunctionOrder(functionOrder)
```

---

## 6. 「有人呼叫我一定应答」——`checkCQMeOrFollowCQMessage`

来源：`FT8TransmitSignal.java:673`。三个循环，**优先级从高到低**：

### 循环 1：优先回「我的当前目标」（`:678`）
扫描 `to 是我`、`from == 当前目标`、且不是 73 的报文 → `setTransmit(order = checkFunOrder(msg)+1, extraInfo)` 并返回 true。
> 源码注释：多个台同时呼叫我时，先锁定当前目标，**避免回复对象漂移**。

### 循环 2：任何人呼叫我，一律应答 ★（`:697`）
扫描 `to 是我`、且不是 73 的报文 → 立刻 `setTransmit(checkFunOrder(msg)+1)`（`:704`-`:708`）。
> **这就是 FT8CN 的「一定应答」**：不看是否已通联、不看显示筛选、不看是否已有目标——只要 `to` 解析出我方呼号就回。
> 唯一例外是 `isExcludeMessage()` 过滤（同槽 / 异波段 / 排除字头）与 `73`。

### 循环 3：S&P 自动应答关注的 CQ（仅当「当前无目标」）
- `!autoCallFollow` → 返回 false（`:714`）。
- `toCallsign == null` → 返回 false（`:718`）。
- `toCallsign.haveTargetCallsign()`（已有目标）→ 返回 false（`:722`）——**有目标就不换**。
- 遍历关注列表 `transmitMessages`，挑：`是 CQ` ＋（`autoCallFollow && autoFollowCQ` 或 我关注）＋ 之前没通联成功 ＋ 不是我自己 → `resetTargetReport()` + `setTransmit(..., 1, ...)`（`:728`-`:746`）。

### 报文过滤 `isExcludeMessage`（`:661`）

```java
msg.getSequence() == sequential      // 与我发射同槽
|| msg.band != GeneralVariables.band // 异波段
|| checkIsExcludeCallsign(from)      // 排除字头
```

---

## 7. 呼叫列表与关注呼号

`MainViewModel.findIncludedCallsigns()`（`MainViewModel.java:520`）：

```java
if (ft8TransmitSignal.isActivated() && sequential != getNowSequential()) return;  // 发射中且不在我的槽，不刷新
若 满足以下任一：
    to/from 含我呼号
    || from 在关注列表
    || to 在关注列表
    || (autoFollowCQ && 该报文是 CQ)
并且 不是排除字头
→ 加入 transmitMessages，并标记 isQSL_Callsign = checkQSLCallsign(from)
```

- `autoFollowCQ`（默认 true，`GeneralVariables.java:205`）：**自动关注 CQ**——把解码到的 CQ 推送到
  「呼叫」列表；**不写入**关注呼号表（帮助文件 `auto_follow_help.txt` 明确说明）。
- `autoCallFollow`（默认 true，`:206`）：**自动呼叫关注的呼号**——是否自动去呼叫 CQ。它是总闸
  （`:714` 关掉即直接返回）；开启时，`autoFollowCQ` 也开 ⇒ **任何**未通联 CQ 台都可呼叫，
  `autoFollowCQ` 关 ⇒ **只**呼叫关注名单里的 CQ 台（`FT8TransmitSignal.java:733-737`）。
- `callsignInFollow()`（`:327`）：是否在**关注呼号名单**（`followCallsigns` 表，`DatabaseOpr.java:142`）。
  名单是**用户手动**加的（`CallingListFragment` / `MyCallingFragment` / 地图 `GridTrackerMainActivity`），
  **永久保存**，且**只能在局域网 Web 后台删除**（`LogHttpServer.java:444`）或清缓存时清空；
  名单里的台不受 `autoFollowCQ` 限制，一定进列表、并在 `autoCallFollow` 开时被自动呼叫。
- `checkQSLCallsign()`（`:273`）：是否在**本波段**已通联（`QSL_Callsign_list`）；`checkQSLCallsign_OtherBand()`（`:283`）为其它波段。

---

## 8. 人工入口

| 动作 | 入口 | 行为 |
| --- | --- | --- |
| 呼叫某台（`to` 方） | 呼叫列表/发射界面 菜单 case 1（`CallingListFragment.java:313`、`MyCallingFragment.java:103`） | `setActivated(true)` → `setTransmit(getToCallTransmitCallsign(), 1, extraInfo)` → `transmitNow()` |
| 呼叫发起者（`from` 方） | 菜单 case 3 / 左滑；`doCallNow()`（`MyCallingFragment.java:71`、`CallingListFragment.java:313`） | `addFollowCallsign` → `setActivated(true)` → `setTransmit(getFromCallTransmitCallsign(), 1)` → `transmitNow()` → `resetLaunchSupervision()` |
| 回复（order = -1） | 菜单 case 4（`MyCallingFragment.java:125`） | `setTransmit(..., -1, extraInfo)`，由 `checkFunOrderByExtraInfo(extraInfo)+1` 推断下一步（`:216`；若推出 6 则改回 1，`:217`） |
| 查看呼号 QRZ | 菜单 case 5/6 | 跳 QRZ 页 |
| 查该台日志 | 菜单 case 7/8 | 跳日志页并预填呼号 |
| 发起 CQ | 「CQ」按钮 → `restTransmitting()`（`:982`） | 重设为 order 6 |
| 关发射 | `setActivated(false)`（`:945`） | 同时 `setTransmitting(false)` 并通知 UI |
| 复位监管 | `resetLaunchSupervision()`（`GeneralVariables.java:352`） | 重置 `launchSupervisionStart` |

> **人工操作不会暂停自动程序**。FT8CN 没有「手动接管（paused）」概念：人工设定只是把发射目标/指令改掉，下一批解码照旧跑自动程序。

---

## 9. 安全阀与防卡死

| 机制 | 位置 | 说明 |
| --- | --- | --- |
| **发射监管** `launchSupervision` | `GeneralVariables.java:150`、`:192`、`:365`；`FT8TransmitSignal.java:128` | 默认 **10 分钟**（`DEFAULT_LAUNCH_SUPERVISION = 10*60*1000`）；档位 `0(忽略) / 5 / 15 / 25 / … / 95` 分钟（`LaunchSupervisionSpinnerAdapter.java:28`）。超时 → `setActivated(false)` |
| **监管计时基准** | `GeneralVariables.java:352` | 自 `resetLaunchSupervision()` 起的**绝对计时**；人工操作（呼叫/回复/发 CQ）会复位 |
| **无回应限制** `noReplyLimit` | `GeneralVariables.java:194` | `0..30`，`0`＝忽略；超出（且 `>0`）后退回 CQ 或换台 |
| **无回应计数** `noReplyCount` | `GeneralVariables.java:196` | `generateFun()` / 收到回复时清零；**弱信号（深度解码）不累加** |
| **RR73 三重兜底** | `FT8TransmitSignal.java:834`、`:838`、`:840` | 阈值×2 / 对方已转呼别人 / 忽略时 `>20` |
| **同槽过滤** | `:814`、`:661`、`:607`、`:582` | 多处丢弃「我发射那个时隙」的报文 |
| **排除字头** | `GeneralVariables.java` 的 `excludedCallsigns` | 前缀黑名单 |
| **WSPR-2 频点保护** | `FT8TransmitSignal.java:177` | 落在 WSPR-2 频点拒绝发射并自动关发射 |
| **深度解码不触发自动** | `MainViewModel.java:319` | `isDeep` 的解码不驱动自动程序（避免重复/延迟决策） |

---

## 10. 通联记录与日志落库

数据结构：`QslRecordList`（`QslRecordList.java`），以「对方呼号」为唯一键：

- `getRecordByCallsign`（`:19`）／ `addQSLRecord`（`:47`，已存在则 `oldRecord.update(record)`）；
- `record.saved`（`QSLRecord.java:49`）防重复入库；
- `deleteIfSaved()` 在 order 1/2/3 更新时**移除已保存的旧记录**（`FT8TransmitSignal.java:779`、`:786`）。

`updateQSlRecordList(order, toCall)`（`:753`）：

| order | 动作 |
| :---: | --- |
| 1 | 更新 `toMaidenGrid`、`sendReport`；`deleteIfSaved` |
| 2 / 3 | 更新 `sendReport`、`receivedReport`；`deleteIfSaved` |
| **4 / 5** | 若 `!record.saved` → **`doComplete()`** 并置 `saved = true` |

`doComplete()`（`:475`）落库前会**回查历史报文**修正报告：

- 遍历 `transmitMessages` 找「对方→我」的报告 → `receiveTargetReport`（`:489`）；
- 遍历找「我→对方」的报告 → `sentTargetReport`（`:501`）；
- 入库报告优先级：`sentTargetReport != -100 ? sentTargetReport : sendReport`（收方同理）（`:523`、`:524`）；
- 通过 `onTransmitSuccess.doAfterTransmit(QSLRecord)` 回调写库（`MainViewModel.java:461`），并 `addQSLCallsign` 把呼号加入本波段已通联表（`:530`）。

> **关键**：FT8CN 在收到 RR73/73 的**同一批解码里就直接落库**，不弹确认卡，也不关发射总开关。
> 「已通联」是**按波段**装载的（`DatabaseOpr.java:1904`：`select distinct call from QSLTable where band=?`），换波段即换表。

---

## 11. FT8CN 已知的「跑死 / 不应答」风险点（诚实记录）

1. **外层 2 秒闸门会漏报文**：`MainViewModel.java:318` 要求 `timeSec + pttDelay + transmitDelay <= 2000`。解码耗时超过 2 s 时，定向往我的报文**整批不进入自动程序** → 表现为「有人叫我却不回」。
2. **深度解码被排除**：`isDeep` 的报文不进自动程序（`:319`），只为 UI 展示。
3. **`msgList.get(0).getSequence() == sequential` 整体丢弃**：按**首条**判定，若同批夹着对我的回复会被整批误伤。
4. **`checkFunctionOrdFromMessages` 只认「from == 当前目标」**：对方换呼号形态（加 `/`）时，`checkCallsignIsCallTo` 仅在目标带 `/` 时用 `contains`（`:564`），极端情况仍可能认不出。
5. **`getNewTargetCallsign` 只看本批解码**：若该批没有可用的 CQ 台，就直接回 CQ（而不是在关注列表里找），换台命中率受限。
6. **`restTransmitting()` / `resetToCQ()` 的时隙取反疑点**：`getNowSequential()` 作 `TransmitCallsign.sequential` 后再被 `setTransmit` 取反，实际发射槽与 §3.2 的「双重取反」同源，需以实机为准。

---

## 12. FT8CN → Ft8Vox 当前实现对照（期 1–4）

> Ft8Vox 已按 FT8CN 的 QSO 逻辑改造完毕（分期与逐文件清单见 [`FT8CN_QSO_PLAN.md`](./FT8CN_QSO_PLAN.md) §7/§10.5）。
> 「状态」列：**照搬**＝与 FT8CN 同口径；**超集/加固**＝在 FT8CN 基础上加强；**有意偏离**＝用户明确选择不同做法。

| 维度 | FT8CN（本文） | Ft8Vox 已采纳口径 | 状态 |
| --- | --- | --- | --- |
| 六步序列 | 1 网格 / 2 报告 / 3 R / 4 RR73 / 5 73 / 6 CQ | 同（`QsoEngine.Step.order`、`TxDrawer` 六格、`TxCompose` 六种） | 照搬 |
| 自动化档位 | 无档位；两个布尔开关 | 单档常开 + 四项设置（监管 / 无回应 / 两开关）；删 `AutoMode` | 照搬 |
| 角色 | 隐式：`order==6` 即主叫 | 同（`step`/`target`） | 照搬 |
| 定向一律应答 | 循环 2，几乎无例外 | 定向分支优先级最高，**逐条**判 `addressedTo(myCall)` | 超集/加固 |
| 目标优先（防漂移） | 循环 1 | 保留 | 照搬 |
| 有目标不换台 | 循环 3 前置 `haveTargetCallsign()` | 保留（仅当目标**本批有回应**时才不换台） | 照搬 |
| 忙时目标沉默 | 循环 2：目标无回应 → 转而应答其他呼叫我方者 | **本轮补齐**：`QsoProgress.advanced` + `AutoScheduler.directedTakeover` | 照搬 |
| 已通联 CQ 台不自动应答 | `checkQSLCallsign` 过滤 | 硬编码跳过本波段已通联；**定向报文仍一律应答** | 照搬 |
| 换台计数 | 按解码批次 `noReplyCount` | 同；弱信号批次不计 | 照搬 |
| 换台动作 | 超限 → 本批找 CQ → 否则回 CQ | 同（`maybeGiveUpTarget` → `onTargetGaveUp`） | 照搬 |
| 监控超时动作 | `setActivated(false)` | 关**发送总开关**（`txEnabled=false`） | 照搬 |
| Tx2 报告值 | 固定首次测得的 SNR | **每次重测最新** | 有意偏离 |
| Tx3 的 R 报告 | 与 Tx2 同一变量 | **复用 Tx2 已发出的快照** | 有意偏离 |
| 落库报告取值 | 落库前回查历史报文 | **用会话内最后一次值** | 有意偏离 |
| 完成收尾 | 收到 73/RR73 立即落库、不弹框、不关 TX | 同 | 照搬 |
| 落库去重 | `QslRecordList` + `saved` 标记 | 会话内一条 + `saved` 标记 | 照搬 |
| 已通联标记 | `addQSLCallsign`（本波段表） | 完成即写 `WorkedIndex`（本波段，立即生效） | 照搬 |
| 日志字段 | 起止时间 / 波段 / 基频 / 收发报告 | 补齐 + 保留 `Distance: … km, Qso by Ft8Vox` 备注 | 照搬 |
| 2 秒时窗闸门 | 有（漏应答根因） | **无**（逐条判定，晚到/大批量也能触发） | 有意偏离（避隐患） |
| 首条整批丢弃 | 有 | **逐条**自听过滤（仅丢本槽那一条） | 有意偏离（避隐患） |
| 深度解码 / 弱信号 | `isDeep` 不驱动自动、弱信号不计无回应 | 预留 `DecodeResult.deep` 钩子（当前恒 false），同规则 | 照搬（占位） |
| 手动接管 paused | 无此概念，人工不暂停自动 | 取消 `paused`；人工只改目标/指令 | 照搬 |
| 发射确认框 | 无 | 删 `AutoEnableConfirmDialog`；`txEnabled` 即唯一闸门 | 照搬 |
| 复合呼号匹配 | 目标带 `/` 时 `contains`（单向） | **双向**（任一方带 `/` 即 `contains`） | 超集/加固 |
| 选台排序 | 无 DX 分级 | `AutoProgramSelector.rank`：DX > 我所在区域 > 其它修饰符 > 无（稳定保序） | 超集（自定） |
| 关注呼号名单 | `followCallsigns` 表：手动（呼叫列表/地图）关注、持久保存、App 内不能删；名单里的台不受 `autoFollowCQ` 限制 | `AppSettings.followCalls` + `autoFollowOrder`：手动长按「关注 / 取消关注」，**或**由「自动收录 CQ 台」自动加入；筛选条最右 **⭐** 打开「关注呼号列表」查看（左滑呼叫 / 右滑取消关注）；名单里的台不受 `autoAddCqToFollow` 限制 | **有意偏离**（本机多一条自动收录来源 + App 内删除入口） |
| 两个 CQ 开关 | `autoFollowCQ`＝把 CQ 推送到呼叫列表（**不写名单**）；`autoCallFollow`＝是否自动呼叫（总闸），配合关注名单 | `autoAddCqToFollow`＝**写入关注名单** + 把关 CQ 候选（关注名单是例外）、`autoCallFollow`＝把关是否呼叫 | **有意偏离**（见 `NEW_QSO_` §十三） |
| RR73 三重兜底 | ③④⑤ | 引擎结构上 RR73 不滞留，兜底不可达；以「收敛兜底 + 逐条过滤」代替 | 有意偏离（等价防死） |

**FT8CN 有意没有、Ft8Vox 也不引入的东西**：Hound/Fox、逐条精确 DXCC 分级、多档 AutoSeq、完成确认卡。

---

## 13. 附录 A：常量与默认值

| 常量 | 值 | 位置 |
| --- | --- | --- |
| `functionOrder` 初值 | `6`（开机即 CQ） | `FT8TransmitSignal.java:45` |
| `activated` 初值 | `false` | `:47` |
| `sentTargetReport` / `receiveTargetReport` 空值 | `-100` | `:63`、`:67` |
| `baseFrequency` | `1000` Hz | `GeneralVariables.java:166` |
| `transmitDelay`（=心跳 `time_sec`） | `500` ms | `:179` |
| `pttDelay` | `100` ms | `:180` |
| `synFrequency`（同频发射） | `false` | `:178` |
| `band` | `14074000` Hz | `:183` |
| `launchSupervision` | `10` min（`DEFAULT_LAUNCH_SUPERVISION`） | `:150`、`:192` |
| 监管档位 | `忽略 / 5 / 15 / 25 / … / 95` min | `LaunchSupervisionSpinnerAdapter.java:28` |
| `noReplyLimit` | `0`（忽略） | `:194` |
| `noReplyCount` | `0` | `:196` |
| `autoFollowCQ` | `true` | `:205` |
| `autoCallFollow` | `true` | `:206` |
| 无回应换台阈值 | `> noReplyLimit`；RR73 兜底 `> noReplyLimit*2` / `> 20` | `FT8TransmitSignal.java:835`、`:840`、`:890` |
| 人工立即发射窗口 | 距槽边界 `< 2500` ms | `:163` |
| 决策时窗闸门 | `timeSec + pttDelay + transmitDelay <= 2000` ms | `MainViewModel.java:323` |
| 时隙 | FT8 15 s / FT4 7.5 s | `UtcTimer.java:253`、`Ft8Message.java:324` |

---

## 14. 附录 B：一句话总结

> FT8CN 的 QSO 是 **「六步指令序列 + 单一常开自动程序 + 两个安全阀」** 的半自动模型；
> 它的杀手锏是「**任何 `to==我`（非 73）的报文一律立即应答**」（`FT8TransmitSignal.java:697`），
> 它的最大隐患是「**自动程序被 2 秒时窗、深度解码、首条整批判定三道闸门挡在门外**」（`MainViewModel.java:318`）。
> Ft8Vox 保留前者的「一定应答」语义，用逐条判定 + 不设时窗闸门消除后者的漏应答，其余按 §12 逐项对齐。
