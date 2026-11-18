# CURRENT_EXIT_ARCHITECTURE

> Phase 1 审计产出。**本文只做审计，未修改任何业务代码、配置或迁移。**
> 审计日期：2026-09-17（Asia/Shanghai）
> 证据格式：`路径 :: 类#方法`
> 结论全部来自源码/配置/迁移/测试的静态事实，未启动交易、未调用 OKX、未发单。

---

## 0. 一页结论

当前系统的**退出完全由交易所托管**（OKX `conditional` algo order），本地只负责"建单 + 对账 + 恢复"。

**保护单在持仓生命周期内是静态的**：

- SL：从 `CanonicalExecutablePlan.stopLoss()` 编译一次，此后**永不向盈利方向移动**。
- TP：TP1/TP2/TP3 三条固定触发价的 reduce-only 条件单，价格同样**永不改变**。
- 唯一会改动的退出行为是 `SIDEWAYS_TIMEOUT`——横盘超时后把三条 TP **替换**成一条"小盈利 TP"（`avgPx * (1 + 0.3%)`），这是一个**收紧止盈**动作，不是盈利保护动作，且触发条件是"几乎不赚不亏"，与"巨大浮盈回吐"完全无关。

因此，**用户描述的场景在现有系统里是必然发生的，不是偶发 bug**：

```
Entry 1.00 → 1.90 → 0.95
SL 仍在 0.95（从未移动）
最终以 ~-1R 了结，而 MFE 曾达到 +18R
```

系统当前**没有任何一处**记录过"这笔交易最高到过 1.90"。没有 MFE、没有 MAE、没有 break-even、没有 profit lock、没有 trailing、没有 `move_order_stop`、没有 Profit Giveback 统计，退出原因也无法区分止损/止盈/保护退出。

---

## 1. 当前真实状态机（不是理想状态机）

### 1.1 主状态机：`OrderStatus`

来源：`src/main/java/com/example/quant/order/OrderStatus.java`

```java
CREATED, PENDING_CONFIRM, BUDGET_RESERVED, CONFIRMING, CONFIRMED,
SUBMITTING, SUBMITTED, UNKNOWN_SUBMIT_STATUS,
ENTRY_FILLED, ENTRY_TIMEOUT_CANCELLED,
PROTECTION_PENDING, PROTECTION_SUBMITTED,
ACTIVE, ACTIVE_WITH_TP_WARNING,
SIDEWAYS_TIMEOUT, SIDEWAYS_TIMEOUT_TP_ADJUSTED,
MAX_HOLD_TIMEOUT,
CLOSE_SUBMITTED,
PROTECTION_FAILED, PROTECTION_REPLAN_REQUIRED, PROTECTION_RULES_STALE,
EXECUTED, CANCELLED, EXPIRED, REJECTED, FAILED,
CLOSED, CLOSED_RECONCILING, EMERGENCY_ATTENTION_REQUIRED
```

**关键事实：这个 enum 是"订单状态"，不是"退出生命周期状态机"。**

- `ACTIVE` / `ACTIVE_WITH_TP_WARNING` / `SIDEWAYS_TIMEOUT` 在 `AutoTradeLifecycleService` 中**从未被写入**（只在 `protectionStatus()` 与 `legacyActiveLifecycleCount()` 中被读取/兼容）。
- 实际持仓中的订单稳定停留在 `PROTECTION_SUBMITTED`。
- 没有 `PROFITABLE` / `BREAK_EVEN` / `TRAILING` / `PROFIT_LOCKED` 之类的任何盈利阶段状态。
- 盈利相关信息**没有状态位**，只在 `AutoTradeLifecycleSnapshot` 里以 `unrealizedPnl` / `pnlPct` 作为**展示型数值**存在。

### 1.2 真实运行态状态图（从代码推导）

```text
                      扫描/人工建计划
                            │
                    PENDING_CONFIRM
                            │ 预算预留
                    BUDGET_RESERVED
                            │
                   CONFIRMING → CONFIRMED
                            │ 风控通过
                      SUBMITTING
                            │ OKX ACK（不等于成交）
                       SUBMITTED ─────────────┐
                            │                 │ 超时无成交
                  完全成交/持仓已存在            │
                            │                 ▼
                            │         ENTRY_TIMEOUT_CANCELLED
                            │         （释放预算）
                            ▼
                   PROTECTION_SUBMITTED  ◄─── 保护补交/恢复的唯一稳态
                            │
        ┌───────────────────┼────────────────────┬──────────────────┐
        │                   │                    │                  │
   横盘超时              持仓时长 ≥            亏损时长 ≥      保护单缺失/
   |pnl%| ≤ 1%         maxHoldMinutes(60)   lossClose(30min)  canonical 不可用
        │                   │                    │                  │
        ▼                   ▼                    ▼                  ▼
SIDEWAYS_TIMEOUT_     CLOSE_SUBMITTED      CLOSE_SUBMITTED   PROTECTION_FAILED
TP_ADJUSTED                 │                    │            PROTECTION_REPLAN_REQUIRED
（TP 换成 0.3% 小盈利单）      │                    │            PROTECTION_RULES_STALE
        │                   └────────┬───────────┘            EMERGENCY_ATTENTION_REQUIRED
        └────────────────────────────┤                                  │
                                     ▼                        安全平仓 / 人工介入
                              CLOSED / CLOSED_RECONCILING
```

写入点证据：
- `AutoTradeLifecycleService#runOnce`（第 174-449 行）
- `AutoTradeRecoveryTask#runOnceAsOwner`（第 185-243 行）
- `ClosePositionRecoveryService#runOnce`
- `PendingOrder#transition / transitionFromAny`

### 1.3 必须注意的"名义状态 vs 实际状态"

| 名义 | 实际 |
|---|---|
| `ACTIVE_WITH_TP_WARNING` | 枚举存在，代码中**无写入点** |
| `SIDEWAYS_TIMEOUT` | 枚举存在，`tightenTakeProfit` 成功后直接写 `SIDEWAYS_TIMEOUT_TP_ADJUSTED`，**从不写 `SIDEWAYS_TIMEOUT`** |
| `MAX_HOLD_TIMEOUT` | 枚举存在，`submitMaxHoldClose` 直接 `markCloseSubmitted`，**从不写 `MAX_HOLD_TIMEOUT`** |
| `PROTECTION_PENDING` | 只在 `protectionStatus()` 做展示兜底 |

**结论：现有状态机有历史遗留的"幽灵状态"。新增盈利保护状态时不能复用它们，必须新建独立的退出状态维度。**

---

## 2. TP / SL 数据流

### 2.1 完整链路

```text
ContractTradePlanBuilder（策略：entry / SL / TP 目标价 / 仓位）
        │  TradePlan
        ▼
TradeIntent.fromPlan / fromPendingOrder        ← ★ TP1/TP2 在这里被硬编码成 1R/2R
        │  （策略经济意图，无 tick/lot 概念）
        ▼
CanonicalPlanCompiler.compile(intent, rules)   ← 唯一做 tickSz / lotSz / minSz / ctVal 归一化的地方
        │  CanonicalExecutablePlan（不可变快照，含 ExitLeg{semanticId, targetR, price, fraction, qty}）
        ▼
CanonicalPlanJsonCodec → pending_order_state.canonical_plan_json（V34）
        │
        ▼
OkxTradeAdapter#orderPayload
    └─ attachAlgoOrds = [ attachedStopLoss ]   ← 入场时随单附一个 SL
        │
        ▼
AutoTradeLifecycleService#submitProtection（成交后）
    ├─ stopLossPayload()   → placeAlgoOrder(slTriggerPx, slOrdPx=-1, reduceOnly=true)
    └─ reconcileProfitProtection() → 逐条 placeAlgoOrder(tpTriggerPx, tpOrdPx=-1, reduceOnly=true)
```

### 2.2 TP1/TP2/TP3 价格怎么算的（回答问题 2、3、4）

来源：`src/main/java/com/example/quant/tradeplan/TradeIntent.java :: fromPlan / fromPendingOrder`（第 54-96 行）

```java
BigDecimal risk = plan.entryPrice().subtract(plan.stopLossPrice()).abs();   // 1R
BigDecimal sign = SHORT ? -1 : 1;

tp1 = entry + risk * 1 * sign;      // ← 硬编码 1R
tp2 = entry + risk * 2 * sign;      // ← 硬编码 2R
tp3 = plan.takeProfitPrice();       // ← 用 TradePlan 自己的目标价
finalR = (tp3 - entry) * sign / risk;
```

数量分配来自配置（`application.yml :: quant.agent.take-profit`）：

| 配置 | 默认值 |
|---|---|
| `tp1-ratio` | 0.30 |
| `tp2-ratio` | 0.40 |
| `tp3-ratio` | 0.30 |

**★ 语义漂移确实存在，且已被代码确认：**

| 层级 | TP1 | TP2 | TP3 |
|---|---|---|---|
| TradePlan 语义 | （无） | （无） | 策略目标价 `takeProfitPrice` |
| `TradeIntent` 语义 | `1R` | `2R` | `finalR（由 TP3 目标价反算）` |
| Lifecycle 是否重算 | ❌ 不重算 | ❌ 不重算 | ❌ 不重算 |

即：**TP1/TP2 是 Lifecycle 层（其实是 `TradeIntent` 工厂方法）强加的 1R/2R 语义，TradePlan 只贡献了 TP3。**
`CanonicalPlanCompiler` 只做 tick/lot 归一化，**从不重算 target-R**（`scaleExitLegsInternal` 注释明确写了 "Prices, target-R and fractions are never recomputed here"）。

→ 用户提示词中"不要写死 TP1=1R TP2=2R"的要求，正好命中了当前代码里一个已经存在的硬编码。这是本次必须处理的既有问题，但**处理方式应是在 `TradeIntent` 层把 TP 语义配置化/策略化，而不是在 Lifecycle 里再造一套 R 计算**。

### 2.3 初始 SL 从哪里来（回答问题 5）

1. `ContractTradePlanBuilder#buildRuleFallbackPlan`（第 289-296 行）：
   `stopLoss = positive(candidate.stopLossPrice(), fallbackStop(entry, direction))`
2. `buildSelectedPlan`（第 322-328 行）有**最小止损距离保护**：
   `minimumDistance = entry * 0.006`（0.6%），`riskDistance = max(candidateDistance, minimumDistance)`
3. `CanonicalPlanCompiler#compile`：`normalizePrice(stop, rules, shortSide, PriceRole.STOP)`
   —— STOP 方向归一化用 `shortSide ? CEILING : FLOOR`（**让止损更宽，不允许 tick 把止损收紧**）。
4. 编译期校验：`validStopGeometry` + `EXECUTABLE_RISK_EXCEEDS_RISK_BUDGET`。

### 2.4 SL 在持仓期间能否修改（回答问题 6）

**不能。**

- `stopLossPayload()`（第 1240-1262 行）永远用 `plan.stopLoss().intendedPrice()`。
- `reconcileCanonicalProtection()`（第 1293-1313 行）确实会"替换 SL"，但触发条件只有两个：
  1. `stop.size() != size`（数量不匹配，例如 TP1 已成交缩小了仓位）
  2. `stop.triggerPrice() != desiredStop`，而 `desiredStop` **恒等于 canonical 初始 SL**

  即：**它只会把 SL 改回初始值，永远不可能移到盈利区。**
- `AgentProperties.Lifecycle.moveStopToBreakeven`（默认 `false`）**是死配置**——全仓库搜索确认只有声明、getter、setter 和 yml，无任何读取点。

### 2.5 Exit Leg 的 R 语义现状

`CanonicalExecutablePlan.ExitLeg` 已经带 `targetR` 字段（第 79 行），`TradeIntent.ExitLeg` 也带 `targetR`。
→ **"R"这个概念在编译层已经存在且被持久化，只是没有任何一个运行时模块消费它。**
这是本次实现 MFE/MAE（以 R 为单位）的天然落点。

---

## 3. Lifecycle 数据流

### 3.1 驱动方式（关键约束）

`AutoTradeLifecycleService#runOnce` 由 `AutoTradeRecoveryTask` 每 **30 秒**调用一次
（`application.yml :: quant.agent.recovery.interval-seconds: 30`）。

**★ 结论：当前没有 tick 级/行情事件驱动的退出。**
- 不存在行情 tick 回调进入 lifecycle 的路径。
- `OkxWebSocketService` 只订阅 private `orders` / `positions` / `account`（`AI_REPO_MAP.md` 第 172 行），公共行情 WS **未实现**（`OkxWebSocketService#publicStatus` 明确为未实现）。
- `AutoTradeLifecycleService` 唯一的行情输入是 `PositionSnapshotService.positions()`（REST `/api/v5/account/positions`，30 秒一次）。

### 3.2 PositionSummary 里有什么（关键约束）

来源：`src/main/java/com/example/quant/account/dto/PositionSummary.java`

```java
instId, posSide, size, avgPrice, unrealizedPnl, margin, initialMargin,
marginMode, leverage, liquidationPrice, notionalUsd, marginRatio, mode,
ownership, reconciliationReason
```

**★ 没有 `markPx`，没有 `lastPx`，没有 `uplRatio`（只有绝对 `upl` 和 `notionalUsd`）。**

当前代码确实间接算出了收益率（`AutoTradeLifecycleService#pnlPct`，第 1621-1628 行）：

```java
pnlPct = unrealizedPnl * 100 / |notionalUsd|
```

但这**不能反推出价格**（`notionalUsd` 是 OKX 自己算的美元名义值，不是 `size * ctVal * mark` 的简单可分离量）。
→ **要算 MFE/MAE，必须新增价格源。** 现有可用且已封装好的来源有两个：

| 来源 | 代码 | 粒度 | 备注 |
|---|---|---|---|
| `OkxMarketService#candles(instId, bar, limit)` | `crypto/OkxMarketService.java:28` | OHLC K 线（**含 high/low，天然可算 MFE/MAE**） | 已有 `ContractCandle` DTO；`CounterfactualExitService` 已在用它 |
| `OkxRestClient#publicGet("/api/v5/market/tickers?instType=SWAP")` | `crypto/OkxContractScanner.java:62` | 最新价 | 需要按 instId 过滤 |

### 3.3 runOnce 决策顺序（回答问题 17：Exit Priority）

`AutoTradeLifecycleService#runOnce`（第 197-446 行）对**每个 order 顺序判断，命中即 `continue`**：

```text
1.  EMERGENCY_ATTENTION_REQUIRED + isProtectionSafetyEmergency
      └─ 查交易所 SL：无效 → triggerSafetyClose（CANONICAL_PLAN_UNAVAILABLE_AFTER_ENTRY）
2.  SUBMITTED 分支：查成交 → 建保护 / 超时撤单 / 部分成交保护
3.  canonical plan 不可用 + 有持仓 + managedProtectionStatus
      └─ reconcileCanonicalProtection（可能 → 安全平仓）
4.  保护缺失恢复（ENTRY_TIMEOUT_CANCELLED / PROTECTION_FAILED / REPLAN / RULES_STALE）
5.  SIDEWAYS_TIMEOUT_TP_ADJUSTED → tightenTakeProfit 对账
6.  计算 age = now - holdingStart(order)   ← 用第一笔入场 fill 时间
7.  sidewaysDue  = age ≥ effectiveSidewaysMinutes(180) && |pnlPct| ≤ 1
8.  forceCloseDue = age ≥ maxHoldMinutes(60)
                 || (age ≥ lossCloseMinutes(30) && upl < 0)
9.  常规对账 reconcileCanonicalProtection
10. if (sidewaysDue)     → tightenTakeProfit
11. if (forceCloseDue)   → submitMaxHoldClose
```

**★ 这已经是事实上的 "Single Exit Authority"：`runOnce` 是唯一入口，靠 `if/continue` 串行优先级。**
风险在于：**它是一个 1804 行的巨型方法，优先级是隐式编码的（靠代码顺序），没有显式 Priority 结构**；且 `tightenTakeProfit` 内部还会**直接下单**（第 751-753、805-811、827-829 行），绕过了统一的 branch。

### 3.4 实际配置（application.yml :: quant.agent.lifecycle）

| 配置 | 实际值 | 说明 |
|---|---|---|
| `entry-timeout-minutes` | 15 | |
| `sideways-position-hours` | 3 | |
| `sideways-pnl-range-pct` | 1 | 触发横盘的 |pnl%| 上界 |
| `sideways-exit-profit-pct` | 0.3 | 横盘替换后的止盈目标（相对 avgPx 的 %） |
| `loss-close-minutes` | 30 | 亏损且持有 ≥30min → 平仓 |
| `max-hold-minutes` | **60** | **不是 5 天** |
| `max-hold-hours` | 1 | setter 会覆盖 maxHoldMinutes，最终仍是 60 |
| `max-hold-action` | CLOSE_POSITION | |
| `sideways-action` | TIGHTEN_TAKE_PROFIT | |
| `move-stop-to-breakeven` | false | **死配置，无消费点** |

> 关于"5 天"：当前实际最大持仓是 **60 分钟**。`monitor.max-hold-minutes: 120` 是另一处配置且同样未见消费点。
> 用户要求的"maxHoldingTime 作为 safety boundary 而非目标"在当前代码里**已经是事实**（就是 60 分钟的硬边界）。

---

## 4. OKX 执行层能力

### 4.1 已实现（回答问题 9、14）

来源：`src/main/java/com/example/quant/okxtrade/OkxRestOrderGateway.java`

| 能力 | 方法 | OKX 端点 |
|---|---|---|
| 下单 | `placeOrder` | `POST /api/v5/trade/order` |
| 查单 | `queryOrder` | `GET /api/v5/trade/order` |
| 历史单 | `queryOrderHistory` | `GET /api/v5/trade/orders-history-archive` |
| 当前普通单 | `currentOrders` | `GET /api/v5/trade/orders-pending` |
| **当前策略单** | `currentAlgoOrders` | `GET /api/v5/trade/orders-algo-pending` |
| **查策略单** | `queryAlgoOrder` | `GET /api/v5/trade/order-algo` |
| **下策略单** | `placeAlgoOrder` | `POST /api/v5/trade/order-algo` |
| 撤普通单 | `cancelOrder` | `POST /api/v5/trade/cancel-order` |
| **撤策略单** | `cancelAlgoOrder` | `POST /api/v5/trade/cancel-algos` |
| 一键平仓 | `closePosition` | `POST /api/v5/trade/close-position` |
| 设置杠杆 | `setLeverage` | `POST /api/v5/account/set-leverage` |
| 成交明细 | `fills` | `GET /api/v5/trade/fills` + `fills-history` 兜底 |

### 4.2 未实现（回答问题 13）

**★ `move_order_stop` / `callbackRatio` / `callbackSpread` / `activePx` —— 全仓库搜索结果为 0。**

全仓库搜索 `move_order_stop|callbackRatio|callbackSpread|activePx|attachAlgoOrds|amendAlgo|amend`：
- `attachAlgoOrds` —— 存在（`OkxTradeAdapter#orderPayload:346`，入场附 SL）
- `amend` —— **0 处实现**，唯一出现是注释：
  `AutoTradeLifecycleService.java:778` —— `// The exchange adapter has no amend-algo primitive.`

**★ 这是本次最关键的执行层约束：没有 amend，改止损只能"place new + cancel old"。**

### 4.3 生成的保护单长什么样

`AutoTradeLifecycleService#algoPayload`（第 1450-1463 行）+ `stopLossPayload`（第 1240-1262 行）：

```json
{
  "instId": "...", "tdMode": "cross", "side": "<平仓方向>",
  "posSide": "...",                    // 仅 LONG_SHORT 模式
  "ordType": "conditional",
  "reduceOnly": "true",
  "sz": "<contracts>",
  "algoClOrdId": "lc<sha256_12><TYPE>",
  "slTriggerPx": "<stop>", "slOrdPx": "-1"      // SL
  "tpTriggerPx": "<target>", "tpOrdPx": "-1"    // TP
}
```

- `slOrdPx = -1` / `tpOrdPx = -1` → **触发后市价成交**。
- `ordType` 固定 `conditional`（**不是** `move_order_stop`）。

### 4.4 幂等 Client ID 生成（回答问题 13、16、19）

两个生成器（`AutoTradeLifecycleService` 第 1638-1655 行）：

```java
lifecycleAlgoClOrdId(order, suffix) =
    "lc" + sha256(order.id())[0..12] + lifecycleTypeCode(suffix)

profitAlgoClientId(order, role, qty, identitySeed) =
    "lc" + sha256(order.id() + ":" + seed)[0..12] + lifecycleTypeCode(role)
    // seed = identitySeed != null ? identitySeed : role + ":" + qty

lifecycleTypeCode:  …REPLACE…SL→SLR | …SL/STOP→SL | SIDEWAYS→SWT |
                    TP1→TP1 | TP2→TP2 | TP3→TP3 | else→LC
```

**★ 严重缺陷：SL 的 client ID 与价格无关。**

| 场景 | suffix | 生成的 ID |
|---|---|---|
| 成交后建 SL | `FULL_FILL_SL` | `lc<h>SL` |
| 对账补建 SL | `RECONCILE_SL` | `lc<h>SL` ← **与上面同一个** |
| 替换 SL | `REPLACE_SL` | `lc<h>SLR` |
| 横盘替换 SL | `SIDEWAYS_REPLACE_SL` | `lc<h>SLR` ← **与上面同一个** |

后果：
1. 首次建 SL 用 `lc<h>SL`；一旦需要"替换"，新单用 `lc<h>SLR`，撤掉 `lc<h>SL`。
2. **如果还需要再动一次止损，会再次尝试 `lc<h>SLR`，而它此刻还活着** → OKX 对重复 `algoClOrdId` 的处理是报错或去重，**无法表达"同一个保护单的新价格"**。
3. `profitAlgoClientId` 把 `quantity` 编进 seed，所以 TP 换数量会换 ID；但**换价格不会换 ID**。

→ **必须设计"价格/版本感知 + 幂等"的 client ID 方案，否则"移动止损"根本无法可靠表达。** 这是本次执行层改造的第一优先级。

### 4.5 写前守卫：`ExposureReductionGuard`

来源：`src/main/java/com/example/quant/okxtrade/ExposureReductionGuard.java`

所有降风险写操作（`PLACE_STOP_LOSS` / `PLACE_TAKE_PROFIT` / `REDUCE_POSITION` / `CANCEL_PROTECTION` / `CLOSE_POSITION`）在 `OkxRestOrderGateway#requireTradingEnabled` 里被强制校验：

1. `reduceOnly=true`（CLOSE_POSITION 除外）
2. **idempotencyKey 必须存在**（SL/TP 用 `algoClOrdId`）
3. 仓位必须存在且 `ownership == MANAGED`
4. `requestedSize ≤ positionSize` → **`REDUCTION_SIZE_EXCEEDS_POSITION` 直接拒绝**
5. 方向必须匹配（`long→sell` / `short→buy`）
6. `tradingMode` 与 `position.mode()` 一致
7. `tdMode/mgnMode` 一致

**★ 这是已有的"防超额减仓"硬保护，本次必须继续走这条路径，不能绕过。**
注意第 4 条：**所有 TP/SL 单的合计数量可能超过仓位**（因为守卫视单笔），重复减仓风险靠"总数量 ≤ position"在**单笔**上限制，但**多笔并存**时仍可能累计超额（见 §7 风险点）。

### 4.6 提交超时的处理（回答问题 16）

`OkxTradeAdapter#recoverTimedOutSubmit` / `recoverUnknownSubmitStatus`：
- 超时后**按 `clOrdId` 查询一次**，能查到就恢复 ordId/state。
- 查不到 → 保持 `UNKNOWN_SUBMIT_STATUS`，**不盲目重提、不自动释放预算**，等恢复任务/人工。

**★ 但 `AutoTradeLifecycleService#placeProtection`（保护单）没有等价的 timeout-recovery 分支。**
它用的是 `placeAlgoOrder` + `requireActiveProtection`（place 后再查一次交易所）：

```java
placeProtection(...)                       // 下单
requireActiveProtection(instId, id, role)  // 读回交易所确认，否则抛 <ROLE>_NOT_RECONCILED
```

`requireActiveProtection` 是**读回确认**，这在一定程度上覆盖了"ACK 不等于生效"。但：
- place 抛超时异常时，**本地不知道交易所是否已建单**，而 `recordProtectionSubmitted` 只在成功返回后才写 `trade_order`。
- 下一轮会用**同一个 `algoClOrdId`** 重试 → 若上一轮其实成功了，OKX 会因重复 ID 报错，随后 `requireActiveProtection` 又会读到已存在的单 → 状态最终收敛，但会留下 `PROTECTION_FAILED` 事件噪音。
- 这个"重试即收敛"的性质**依赖于 client ID 稳定**，一旦我们为了"移动止损"把 ID 变成价格相关，就必须重新设计不确定结果的处理（见 Phase 2）。

---

## 5. Recovery 流程

### 5.1 启动恢复

来源：`src/main/java/com/example/quant/task/TradingStartupRecovery.java` / `AutoTradeRecoveryTask`

```text
ApplicationReadyEvent
  └─ TradingStartupRecovery#recover
       └─ AutoTradeRecoveryTask#runOnce
            ├─ syncCurrentOkxOrders()      ← REST 对账普通单
            ├─ 逐 order 状态迁移（CONFIRMING/CONFIRMED → BUDGET_RESERVED 等）
            ├─ UNKNOWN_SUBMIT_STATUS → recoverUnknownSubmit（按 clOrdId 查）
            ├─ AutoTradeLifecycleService#runOnce   ← 保护对账
            └─ ClosePositionRecoveryService#runOnce ← 平仓终态
  └─ OkxRuntimeReconciliationService#reconcile（含 bills 同步、orphan 检测）
```

`OkxWebSocketService#maintain`（`@Scheduled(fixedDelay=1000)`）负责 private WS 登录/订阅/重连，重连后触发 REST 对账。

### 5.2 保护对账（回答问题 15）

`reconcileCanonicalProtection`（第 1264-1331 行）：

```text
1. requireCanonicalPlan(order)          ← canonical plan 缺失？
     缺失 → 查交易所 algo：
        有有效 SL → EMERGENCY_ATTENTION_REQUIRED(CANONICAL_PLAN_UNAVAILABLE_WITH_VALID_SL)
        无有效 SL → triggerSafetyClose(CANONICAL_PLAN_UNAVAILABLE_AFTER_ENTRY)
                  失败 → EMERGENCY_ATTENTION_REQUIRED(CANONICAL_PLAN_MISSING_ACTIVE_POSITION)
2. size = floorToLot(position.size)
3. desiredStop = canonical initial SL（再次强调：永远是初始值）
4. 交易所无 SL → place；size 或 trigger 不符 → place(new) + cancel(old)
5. TP ladder → allocateProtection → reconcileProfitProtection（增量 place / 减量 cancel）
```

**★ 恢复是"以交易所为准 + canonical 快照重建"，不是"本地状态恢复"。**
这带来一个重要性质：**当前的恢复不需要本地持久化保护状态，因为保护状态完全等于 canonical 快照 + 交易所 algo 集合。**

**★ 但这也意味着：一旦我们引入"动态保护止损"，这个等式就失效了——**
`currentProtectiveStop` 既不在 canonical 快照里（快照是不可变的、编译期产物），也无法从交易所可靠读出（要靠 `algoClOrdId` 反推 role，见下），**必须新建本地 durable state**。

### 5.3 交易所 algo → 本地 role 的还原方式（脆弱点）

`activeAlgoProtections`（第 1192-1238 行）**靠 client ID 后缀猜 role**：

```java
if (hasText(slTriggerPx) || clientId.endsWith("sl"))        role = "STOP_LOSS";
else if (clientId.endsWith("tp1"))                          role = "TP1";
else if (clientId.endsWith("tp2"))                          role = "TP2";
else if (clientId.endsWith("tp3"))                          role = "TP3";
else if (clientId.endsWith("swt"))                          role = "SIDEWAYS_TP";
else                                                        role = "TAKE_PROFIT";
```

- `sz` 和 `triggerPrice` 从 algo 单读出，但**没有 `algoId` 被保留**（`ActiveAlgoProtection` 只有 clientOrderId/role/size/triggerPrice）。
- 撤单只能按 `algoClOrdId`（`cancelAlgo`，第 1386-1390 行）。

→ 新增的动态保护单必须**遵守这套后缀约定**，否则 `hasValidStop()` 认不出它，`reconcileCanonicalProtection` 会把它当"无 SL"从而触发安全平仓。这是本次最容易踩的坑（对应测试用例 9）。

### 5.4 平仓终态恢复

`ClosePositionRecoveryService`：
- 显式 `CLOSE_SUBMITTED` 记录 → 按 order detail / fills / position 消失判断终态。
- `POSITION_GONE`：持仓接口成功返回且持仓不存在 → 建关闭记录 → `CLOSED` 或 `CLOSED_RECONCILING`（账本不完整时）。

---

## 6. PnL / Ledger / Fill

### 6.1 成交如何进入账本（回答问题 20）

1. `OkxOrderEventService#apply`（private WS `orders` 事件）按 `tradeId` 写 `TradeFillLedgerService#record`。
2. `OkxCurrentOrderSyncService#syncOnce`（REST 兜底）终态订单拉 fills。
3. `TradeFillEntity`：`fillPrice / fillSize / fee / feeCurrency / fillPnl / fillTime`，唯一键 `owner + instId + tradeId`（V19）。
4. `AccountBillLedgerService#syncRecent`：`/api/v5/account/bills-archive`，type 8 subtype 173/174 → FUNDING，type 2 → TRADE；唯一键 `owner + billId`（V21）。
5. `LedgerPnlService#summary`：`gross = Σ traceable fillPnl`，`net = gross + fees + funding`；**仅当 `AccountLedgerSyncState.status == RECENT_WINDOW_SYNCED` 才提供可用汇总**。

**★ 这是个好消息：PnL 已经严格来自真实 fill + 真实 bill，不存在用 trigger price 算 PnL 的问题。**
（用户提示词第十条的担忧在当前代码里**已经满足**。）

`ClosePositionRecordEntity` 也有 `realizedPnl` / `fee` / `fundingFee` / `pnlCompleteness`（`UNKNOWN`/`PARTIAL`/`COMPLETE`）。

### 6.2 退出原因（回答问题 21、22）

`close_position_record.source` 的全部取值（全仓库搜索 `setSource(`）：

| source | 写入点 |
|---|---|
| `MANUAL` | `PositionCloseService#closePosition` |
| `SAFETY` | `PositionCloseService#closePositionForSafety` |
| `MAX_HOLD_TIMEOUT` | `AutoTradeLifecycleService#createMaxHoldCloseIntent` |
| `POSITION_GONE` | `ClosePositionRecoveryService`（持仓消失） |

**★ 无法区分：正常止损 / 盈利保护退出 / Trailing Stop / TP / Time Stop / Thesis Invalid / Break-even。**

- `MAX_HOLD_TIMEOUT` 是唯一的"自动退出原因"，**且它是"为什么发起平仓"，不是"仓位实际如何结束"**。
- 绝大多数仓位是以 `POSITION_GONE` 结束的（被交易所 algo 打掉后持仓消失），**退出原因永久丢失**。
- `TradeEventType`（`agent/event/TradeEventType.java`）也只有 `SIDEWAYS_TP_REPLACED` / `MAX_HOLD_CLOSE_SUBMITTED` / `MANUAL_CLOSE_SUBMITTED` / `CLOSE_CONFIRMED`，无 TP/SL/trailing 区分。

→ **这是本次必须新增的维度**（用户提示词第十一条）。且**不能**覆盖 `initialStopLoss`（第十二条）。

---

## 7. 已有能力 vs 缺失能力

### 7.1 已有（不要重复造轮子）

| 能力 | 位置 | 备注 |
|---|---|---|
| ✅ 交易所托管 TP/SL（conditional algo） | `OkxRestOrderGateway#placeAlgoOrder` | |
| ✅ 不可变 canonical 计划快照 | `CanonicalExecutablePlan` + V34 | 已含 `targetR`、tick/lot/min/ctVal |
| ✅ tick/lot/minSz 归一化 | `CanonicalPlanCompiler` / `OkxInstrumentRules` | `floorToLot` / `normalizePrice` |
| ✅ 部分成交按实际量保护 | `submitProtection` + `ProtectionAllocation` | |
| ✅ 小仓位 TP 不可表达时 → `TP_REPLAN_REQUIRED`，**不静默合并/放大** | `CanonicalPlanCompiler#allocateProtection` | 符合用户第十四条 |
| ✅ 提交超时不重提，按 clOrdId 查询 | `OkxTradeAdapter#recoverUnknownSubmitStatus` | |
| ✅ 下单后读回交易所确认 | `requireActiveProtection` | |
| ✅ 降风险写前守卫（reduceOnly / MANAGED / size ≤ position / 方向 / 模式） | `ExposureReductionGuard` | |
| ✅ 单一生命周期入口 + 隐式优先级 | `AutoTradeLifecycleService#runOnce` | 需显式化，不需新建 |
| ✅ 平仓意图持久化 + 重试 + 幂等 | `ClosePositionRecordEntity` + V33/V35 | |
| ✅ PnL 严格来自 fill + bill | `LedgerPnlService` | |
| ✅ **离线反事实退出评估**（已含 BE_075 / TRAIL_1R / TIME_60/120/240） | `agent/evaluation/CounterfactualExitService` | ★ 见下 |
| ✅ 历史 K 线 OHLC 获取 | `OkxMarketService#candles` | MFE/MAE 数据源已具备 |

**★ 重大发现：`CounterfactualExitService` 已经实现了离线版的"盈利保护策略评估"。**

`src/main/java/com/example/quant/agent/evaluation/CounterfactualExitService.java`：

```java
POLICIES = ["BASELINE", "S1_V2", "TIME_60", "TIME_120", "TIME_240", "BE_075", "TRAIL_1R"]

// TRAIL_1R
if (favorable >= riskDistance * 1) {
    best = LONG ? max(best, candle.high()) : min(best, candle.low());   // ← 这就是 MFE price
    stop = best - riskDistance * sign;                                   // ← 1R trailing
}
// BE_075
if (favorable >= riskDistance * 0.75) armedBe = true;
if (armedBe) stop = entryPrice;

pnlR = gross / (riskDistance * quantity);
```

它已经有：`riskDistance`（= 1R）、`best`（= MFE price）、`favorable`（= MFE 幅度）、`pnlR`、多种持仓周期。
**唯一缺的是 live 实现，以及 MFE/MAE 的持久化与 giveback 统计。**

→ **本次的 baseline 参数（0.75R 激活 BE、1R trailing）不应我拍脑袋定，而应与 `CounterfactualExitService` 的 POLICIES 对齐，并用它做校准。** 这正好满足用户第十八条"参数需未来校准"和第十五条"不要假装知道最优参数"。

### 7.2 缺失（本次要补）

| # | 缺失 | 证据 |
|---|---|---|
| 1 | 持仓期间最高价/最低价 | 搜索 `highest\|maxPrice\|peak` → 0 处相关（只有无关的 `maxPriceDeviationPct`） |
| 2 | MFE / MAE | 0 处 |
| 3 | 历史最大浮盈 | 0 处 |
| 4 | Break-even Stop | `moveStopToBreakeven` 是死配置 |
| 5 | Profit Lock | 0 处 |
| 6 | Trailing Stop | 0 处（仅离线反事实有 `TRAIL_1R`） |
| 7 | OKX `move_order_stop` | 0 处（连 `amend` 都没有） |
| 8 | Profit Giveback 统计 | 0 处 |
| 9 | `initialStopLoss` 与 `currentProtectiveStop` 分离 | 只有一个 `stopLossPrice`，无动态保护价概念 |
| 10 | 退出原因区分 TP/SL/Trailing/保护/TimeStop | 只有 4 种 source |
| 11 | 单调保护止损不变量 | SL 目前根本不动，无从谈起"单调" |
| 12 | 动态保护状态的本地持久化 | 不需要（因为无动态），所以**没有表/字段可用** |
| 13 | 退出优先级显式结构 | 靠 `if/continue` 顺序隐式表达 |

### 7.3 回答问题 7-12 汇总

| 问题 | 答案 |
|---|---|
| 7. 是否记录最高价/最低价 | **否** |
| 8. 是否存在 MFE/MAE | **否**（仅 `CounterfactualExitService` 在内存里临时算 `best`，不持久化） |
| 9. 是否知道历史最大浮盈 | **否** |
| 10. 是否存在 Break-even Stop | **否**（配置存在但无消费点） |
| 11. 是否存在 Profit Lock | **否** |
| 12. 是否存在真正的 Trailing Stop | **否** |

---

## 8. 风险点

### P0 — 直接阻碍"移动止损"的结构性缺陷

| ID | 风险 | 证据 | 影响 |
|---|---|---|---|
| **R1** | **没有 amend-algo 原语**，改价只能 place-new + cancel-old | `AutoTradeLifecycleService.java:778` 注释明确承认 | 每次移动止损都产生"新旧并存窗口" |
| **R2** | **SL client ID 与价格无关**（`lc<h>SL` / `lc<h>SLR`），第二次移动会撞 ID | `lifecycleAlgoClOrdId` / `lifecycleTypeCode` 第 1638-1677 行 | 移动止损无法稳定表达；必须重设计 ID |
| **R3** | **role 靠 client ID 后缀反推**（`endsWith("sl")`），动态单若不合约定会被判为"无 SL" → **触发安全平仓** | `activeAlgoProtections` 第 1217-1230 行；`hasValidStop` 第 1376 行 | 错误的保护单命名 = 误平仓 |
| **R4** | **无行情价格源接入 lifecycle**，`PositionSummary` 无 markPx | `PositionSummary.java` | MFE/MAE 无从计算，必须新增数据源 |
| **R5** | **无本地动态状态持久化表** | V1-V35 无任何 exit-state 表 | 重启后 `currentProtectiveStop` / MFE 丢失 |

### P1 — 并发 / 幂等 / 恢复

| ID | 风险 | 证据 | 影响 |
|---|---|---|---|
| **R6** | place-new + cancel-old 期间**两条 SL 并存**，极端行情可能重复触发减仓 | `submitProtection` 第 1127-1131 行先 place 后 cancel | `ExposureReductionGuard` 只校验**单笔** ≤ position，不校验**合计** |
| **R7** | 30 秒调度周期 + 无去抖：若每轮都判定"应移动止损"，会反复下单 | `AutoTradeRecoveryTask` 30s | 需要 desired vs applied 状态 + 最小变化阈值 |
| **R8** | 保护单 place 超时无 `recoverUnknownSubmitStatus` 等价物 | `placeProtection` 无 timeout 分支 | 不确定结果需专门设计 |
| **R9** | 状态机存在"幽灵状态"（`ACTIVE` / `SIDEWAYS_TIMEOUT` / `MAX_HOLD_TIMEOUT` 无写入点） | §1.3 | 复用会造成语义污染 |
| **R10** | `AutoTradeLifecycleService` 1804 行、7 个构造器重载、优先级靠代码顺序 | 全文件 | 新增盈利保护分支会显著加剧复杂度 |
| **R11** | 无分布式锁（无 Redis），多副本部署时 lifecycle 会并发跑 | `AI_REPO_MAP.md:59, 347` | 需 DB 约束/单 owner 边界 |

### P2 — 语义

| ID | 风险 | 证据 |
|---|---|---|
| **R12** | TP1/TP2 硬编码 1R/2R，与 TradePlan 的 TP3 语义不一致 | `TradeIntent#fromPlan` 第 60-72 行 |
| **R13** | `SIDEWAYS_TP` 语义是"几乎不赚不亏时换成 0.3% 小止盈"，**与盈利保护正交**，但共用同一套 TP 替换机制 | `tightenTakeProfit` 第 733-852 行 |
| **R14** | 退出原因丢失（绝大多数以 `POSITION_GONE` 结束） | §6.2 |
| **R15** | `CounterfactualExitService` 的 `TRAIL_1R`/`BE_075` 与 live 实现若不一致，离线评估失去校准价值 | `CounterfactualExitService.java:16-17` |

---

## 9. 代码位置索引

### 核心退出链路

| 关注点 | 文件 | 关键方法 |
|---|---|---|
| 生命周期主循环 | `agent/lifecycle/AutoTradeLifecycleService.java` | `runOnce`(174)、`submitProtection`(1106)、`reconcileCanonicalProtection`(1264)、`tightenTakeProfit`(733)、`submitMaxHoldClose`(958)、`reconcileProfitProtection`(859) |
| 保护单 payload | 同上 | `algoPayload`(1450)、`stopLossPayload`(1240) |
| Client ID | 同上 | `lifecycleAlgoClOrdId`(1638)、`profitAlgoClientId`(1644)、`lifecycleTypeCode`(1657) |
| 交易所 algo 读取/role 推断 | 同上 | `activeAlgoProtections`(1192)、`hasValidStop`(1376)、`requireActiveProtection`(1163) |
| 状态枚举 | `order/OrderStatus.java` | 全文件 |
| 计划编译 | `tradeplan/CanonicalPlanCompiler.java` | `compile`(19)、`allocateProtection`(151)、`scaleExitLegsInternal`(113) |
| 计划快照 | `tradeplan/CanonicalExecutablePlan.java` | `ExitLeg`(72)（`targetR` 已存在） |
| 策略意图 / **TP 1R 2R 硬编码** | `tradeplan/TradeIntent.java` | `fromPlan`(54)、`fromPendingOrder`(76) |
| 计划生成 / 初始 SL | `tradeplan/ContractTradePlanBuilder.java` | `buildSelectedPlan`(322)、`buildRuleFallbackPlan`(289) |
| 入场 + 附随 SL | `okxtrade/OkxTradeAdapter.java` | `orderPayload`(310)、`attachedStopLoss`(350) |
| OKX 写网关 | `okxtrade/OkxRestOrderGateway.java` | `placeAlgoOrder`(158)、`cancelAlgoOrder`(180)、`currentAlgoOrders`(131)、`closePosition`(188) |
| 写前守卫 | `okxtrade/ExposureReductionGuard.java` | `verify`(23) |
| 精度规则 | `okxtrade/OkxInstrumentRules.java` | `floorToLot`、`normalizePrice`、`contractSizeFromBaseQuantity` |
| 持仓快照 | `account/PositionSnapshotService.java` | `positions` |
| 归属对账 | `account/PositionReconciliationService.java` | `reconcile` |
| 手动/安全平仓 | `account/PositionCloseService.java` | `closePosition`、`closePositionForSafety` |
| 平仓终态恢复 | `account/ClosePositionRecoveryService.java` | `runOnce`、`reconcileCloseExecution` |
| Fill 账本 | `ledger/TradeFillLedgerService.java` | `record` |
| Bill 账本 | `ledger/AccountBillLedgerService.java` | `syncRecent` |
| PnL | `ledger/LedgerPnlService.java` | `summary` |
| 恢复编排 | `task/AutoTradeRecoveryTask.java` | `runOnceAsOwner`(185) |
| 启动恢复 | `task/TradingStartupRecovery.java` | `recover` |
| 配置 | `config/AgentProperties.java` | `Lifecycle`(1549)、`TakeProfit`(1376)、`Protection`(1319) |
| **离线反事实评估** | `agent/evaluation/CounterfactualExitService.java` | `evaluate`(49)、`POLICIES`(16) |
| K 线数据源 | `crypto/OkxMarketService.java` | `candles`(28) |

### 持久化

| 表 | 迁移 | 用途 |
|---|---|---|
| `pending_order_state` | V12 + V15 + V30 + **V34** | `canonical_plan_json`（V34） |
| `trade_plan` | V4 + **V34** | `canonical_plan_json`（V34） |
| `trade_order` | V5 + V16 + V18 | 保护单记录（`order_role` / `cl_ord_id` / `okx_ord_id` / `parent_algo_id`） |
| `trade_fill` | V19 | 成交（唯一键 owner+inst+tradeId） |
| `account_bill` | V21 | 手续费/资金费 |
| `close_position_record` | V29 + **V35** | 平仓意图、重试、退出 source |
| `exchange_position_state` | V20 | MANAGED / UNMANAGED |
| `counterfactual_exit` | V32 | 离线策略评估 |

**当前迁移最大编号：V35**（`V35__safety_close_retry_state.sql`）。新增迁移应为 **V36+**。

### 测试

现有相关测试：
- `agent/lifecycle/AutoTradeLifecycleServiceTest.java` ★ 主要落点
- `tradeplan/CanonicalPlanCompilerTest.java`、`CanonicalPlanShadowTest.java`
- `okxtrade/OkxTradeAdapterTest.java`、`OkxRestOrderGatewayTest.java`、`ExposureReductionGuardTest.java`
- `account/ClosePositionRecoveryServiceTest.java`
- `agent/evaluation/CounterfactualExitServiceTest.java`
- `task/AutoTradeRecoveryTaskTest.java`

---

## 10. Phase 1 结论：本次改造必须解决的 5 件事

1. **新增价格源 + MFE/MAE 持久化**：`PositionSummary` 无 markPx，需接入 `OkxMarketService#candles`（含 high/low，天然支持 MFE/MAE）或 ticker，并新建表持久化 `highest/lowest/mfeR/maeR/state`。
2. **重设计保护单 Client ID**：现有 ID 与价格无关且只有 `SL`/`SLR` 两个槽位，无法表达"移动止损"。需要"角色 + 版本/价格 + 幂等"的新方案，且必须**向后兼容 `endsWith("sl")` 的 role 推断**。
3. **place-new + cancel-old 的原子化 + 去抖**：因为没有 amend，每次移动都要跨过"新旧并存"窗口；需要 desired/applied/pending 三态 + 最小变化阈值 + 单调性不变量。
4. **新增独立退出状态维度**：不能污染 `OrderStatus`（已有幽灵状态），需要独立的 `ExitLifecycleState`（可持久化、可恢复、幂等、单向、带 hysteresis）。
5. **参数与离线评估对齐**：baseline 参数应与 `CounterfactualExitService` 的 `BE_075` / `TRAIL_1R` 语义对齐，并明确标注为 baseline，后续用 `counterfactual_exit` 表数据校准。

---

## 11. 变更记录

### 2026-09-17
- 新增 `docs/CURRENT_EXIT_ARCHITECTURE.md`（Phase 1 审计）。
- 未修改任何业务代码、配置、迁移或测试。
- 工作区在本次审计前已存在大量未提交改动（`git status` 初始快照），这些改动不是本次审计产生的。
