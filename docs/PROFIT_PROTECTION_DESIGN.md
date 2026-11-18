# PROFIT_PROTECTION_DESIGN

> Phase 2 设计。输入：`docs/CURRENT_EXIT_ARCHITECTURE.md`（Phase 1 审计）。
> **本文仍是设计文档，未修改任何业务代码。**
> 日期：2026-09-17

---

## 0. 设计要解决的问题

Phase 1 确认：现有退出是**交易所托管的静态 TP/SL**，SL 在持仓期间永不移动，也没有任何一处记录"这笔交易最高到过多少"。

因此下面这条路径在当前系统里是**必然**发生的：

```
Entry 1.00 → 1.90（MFE = +18R）→ 回落 → 0.95（触发初始 SL）
结果：-1R，且系统内没有任何证据表明它曾经 +18R
```

本设计要做的不是"加一个 trailing stop"，而是补一层**缺失的 Profit Protection Layer**：
1. 观测并记录 MFE/MAE（以本笔交易的 R 为单位）；
2. 按 MFE 分阶段地把**保护止损**单向推向盈利方向（不允许变差）；
3. 记录 Profit Giveback，让"曾经赚多少 / 实际拿走多少"成为可观测指标；
4. 上述状态必须**可持久化、可恢复、幂等**，且在宕机/超时/重复事件下不会把已锁利润释放掉。

---

## 1. 核心设计原则（先讲为什么）

| # | 原则 | 为什么（基于代码事实） |
|---|---|---|
| P1 | **不新建平仓通道** | 现有 `AutoTradeLifecycleService#runOnce` 已是事实上的 Single Exit Authority。盈利保护引擎**只移动止损，不发起平仓**，从根上避免多模块同时 Close。 |
| P2 | **不覆盖 `initialStopLoss`** | `CanonicalExecutablePlan.stopLoss()` 是编译期不可变快照，被 `reconcileCanonicalProtection` 用作 desired 值。新增 `currentProtectiveStop` 必须是**独立字段**。 |
| P3 | **单调性由数学保证 + 守卫兜底** | 见 §4.3：`stop = f(mfePeak)`，`mfePeak` 单调不减 ⇒ `stop` 单调不降。再叠一层显式 `MonotonicStopGuard` 断言。 |
| P4 | **交易所是止损的事实源** | 恢复时以交易所读回的 `slTriggerPx` 为准，本地状态只做"意图 + 观测"。 |
| P5 | **不确定结果不重试，先读回** | 沿用 `OkxTradeAdapter#recoverUnknownSubmitStatus` 的哲学：超时后按 `algoClOrdId` 查询，不重发。 |
| P6 | **复用已有原语** | `ExposureReductionGuard`、`CanonicalPlanCompiler`、`OkxInstrumentRules`、`CounterfactualExitService` 全部保留。 |
| P7 | **最小改动** | 不重写交易系统，不引入新抽象层，不引入 AI。 |

---

## 2. 状态模型

### 2.1 新增独立维度，不污染 `OrderStatus`

Phase 1 §1.3 确认 `OrderStatus` 已存在"幽灵状态"（`ACTIVE` / `SIDEWAYS_TIMEOUT` / `MAX_HOLD_TIMEOUT` 有枚举无写入点）。
→ **绝不**往 `OrderStatus` 里塞 `PROFIT_LOCKED` / `TRAILING`。

新建枚举 `PositionExitState`（包 `com.example.quant.agent.exit`）：

```java
public enum PositionExitState {
    NEW,                  // 0  刚建仓，尚未初始化保护事实
    INITIAL_PROTECTION,   // 1  只有初始 SL（当前系统的唯一状态）
    BREAK_EVEN_ARMED,     // 2  保护价已 ≥ effective break-even
    PROFIT_LOCKED,        // 3  保护价 > break-even，已锁定部分利润
    TRAILING,             // 4  跟踪止盈中
    EXITING,              // 5  已发起退出（终态前）
    CLOSED                // 6  终态
}
```

**单向演进**：`ordinal` 严格递增（`canTransition` 校验）。
**两个例外（terminal override）**：任意状态 → `EXITING` / `CLOSED` 始终允许。

### 2.2 状态图

```text
                     ┌──────────────────── 任意状态 ────────────────────┐
                     │  emergency / safety close / max-hold / thesis    │
                     └────────────────────────┬────────────────────────┘
                                              ▼
  NEW ──► INITIAL_PROTECTION ──► BREAK_EVEN_ARMED ──► PROFIT_LOCKED ──► TRAILING ──► EXITING ──► CLOSED
          (mfeR < 0.75)          (mfeR ≥ 0.75)        (mfeR ≥ 1.5)      (mfeR ≥ 3.0)
              │                       │                    │                 │
              └───────────────────────┴────────────────────┴─────────────────┘
                                  只前进，不回退（hysteresis = 单向性本身）
```

**关键：hysteresis 的实现方式不是"回撤阈值"，而是"状态不可回退"。**
价格回撤时状态**保持不变**（不会从 `TRAILING` 退回 `PROFIT_LOCKED`），因此不存在"来回切状态"的问题。这直接满足测试用例 3。

去抖则由两条独立机制保证（见 §6）：
- 状态迁移看 `mfePeak`（单调）→ 不会抖动；
- 改单看"最小改善幅度 + 最小间隔"→ 不会刷单。

### 2.3 为什么状态名不照抄提示词

提示词给的参考是 `POSITION_OPEN → INITIAL_PROTECTION → PROFITABLE → BREAK_EVEN_PROTECTED → PROFIT_LOCKED → TRAILING → EXITING → CLOSED`。

我做了两处调整，理由是代码事实：

1. **去掉 `POSITION_OPEN`**：现有 `OrderStatus` 已有 `SUBMITTED`/`ENTRY_FILLED` 表达入场，再建一个 `POSITION_OPEN` 会造成两个状态维度语义重叠。用 `NEW` 表示"退出状态尚未初始化"，生命周期更短、更明确。
2. **去掉 `PROFITABLE`**："盈利"是连续量不是阶段，且系统已有 `unrealizedPnl`。盈利程度由 `mfeR` 连续表达，不需要离散状态。
3. **`BREAK_EVEN_ARMED` 而非 `BREAK_EVEN_PROTECTED`**："armed" 强调这是一个**已激活的意图**，而保护价是否真的 ≥ break-even 还要经过 `MonotonicStopGuard` 与"不能贴着市价"的约束，用 "protected" 会暗示已达成。

---

## 3. MFE / MAE 模型

### 3.1 价格源（关键决策）

Phase 1 §3.2 指出 `PositionSummary` 没有 `markPx`。
**但对 OKX 官方文档的核实（2026-09-17）表明：`GET /api/v5/account/positions` 本身就返回 `markPx`、`last`、`uplRatio`、`bePx`，只是 `PositionSnapshotService` 没有映射。**

→ **决策：给 `PositionSummary` 增加 `markPrice` / `lastPrice` 两个字段，从已有的那次 REST 响应里多读两个 key。零额外 API 调用、零额外限频。**

- **主价格用 `markPx`**：与 OKX 计算 `upl` 的价格一致，抗插针，且 SL 触发默认也是 `last` 而 `markPx` 更稳健。
- 配置 `price-source: MARK | LAST`，默认 `MARK`。

> 已知局限（诚实说明）：lifecycle 是 30 秒轮询，两次轮询之间的瞬时极值会被漏掉。因此 MFE 是**观测到的 MFE**，不是真实 MFE。
> V1 接受这个近似（用户场景是"数小时级别的 +18R"，30 秒采样足够）。
> V1.1 可选：用 `OkxMarketService#candles` 的 5m/15m `high`/`low` 补齐采样间隙——`CounterfactualExitService` 已经在用 candle high/low 做同样的事，接入成本低。配置项预留 `mfe-sample-source`。

### 3.2 定义（LONG / SHORT 严格对称）

```
risk        = |entryPrice - initialStopLoss|          // 本笔交易的 1R，永不改变
sign        = LONG ? +1 : -1

// 极值（随持仓时间单调扩张）
highestSinceEntry = max(highestSinceEntry, price)      // LONG 用
lowestSinceEntry  = min(lowestSinceEntry, price)       // SHORT 用

// 有利/不利偏移
mfePrice = LONG ? highestSinceEntry : lowestSinceEntry
maePrice = LONG ? lowestSinceEntry  : highestSinceEntry

mfe      = (mfePrice - entryPrice) * sign              // ≥ 0
mae      = (entryPrice - maePrice) * sign              // ≥ 0

mfePct   = mfe / entryPrice * 100
maePct   = mae / entryPrice * 100

mfeR     = mfe / risk                                  // ≥ 0
maeR     = mae / risk                                  // ≥ 0

mfeAt / maeAt = 达到该极值的时间戳
```

**8 个字段全部持久化**（用户要求）：`mfePrice / maePrice / mfePct / maePct / mfeR / maeR / mfeAt / maeAt`，外加 `highestSinceEntry / lowestSinceEntry` 两个原始极值。

### 3.3 `initialRisk` 的来源与不变性

- `entryPrice`：优先用**入场 fill 的 VWAP**（`TradeFillRepository.findByPendingOrderId` 已支持，且 `holdingStart()` 已经在用它），回退 `position.avgPrice`，再回退 `order.price()`。
- `initialStopLoss`：只来自 `canonicalPlan.stopLoss().intendedPrice()`（**或** `normalizedPrice()`，与现有 `stopLossPayload` 保持一致）。
- 两者在 `position_exit_state` 里**写入后不可变**（只有创建/重建时写，之后只读）。

> 这直接满足用户第十二条：把 SL 从 0.95 移到 1.20 **不会**改变 `initialRisk`，R 不会失真。

### 3.4 Profit Giveback

```
realizedR   = (exitPrice - entryPrice) * sign / risk     // 来自真实 fill，不是 trigger price
givebackR   = mfeR > 0 ? max(0, mfeR - realizedR) : 0
givebackRatio = mfeR > 0 ? givebackR / mfeR : null
```

边界处理：
- `mfeR <= 0`（从未盈利）→ `givebackR = 0`，`givebackRatio = null`（不产生除零，也不产生"负回吐"的误导数值）。
- `realizedR > mfeR`（真实成交优于观测极值，例如观测漏采 + 跳空高开成交）→ `givebackR = 0`。
- `realizedR` 一律来自 `TradeFill` + `AccountBill`（fee/funding），**绝不用 `slTriggerPx` 代替**。Phase 1 §6.1 已确认现有 PnL 链路满足这一点，本次只是把已实现 PnL 换算成 R。

---

## 4. Profit Protection 模型

### 4.1 输入 / 输出

```java
public record ExitDecision(
    ExitAction action,          // HOLD | TIGHTEN_STOP | NO_OP
    String reason,              // 人类可读 + 机器编码
    BigDecimal targetProtectiveStop,
    PositionExitState fromState,
    PositionExitState toState,
    Map<String, String> evidence   // mfeR / currentR / oldStop / newStop / markPx ...
) {}

public enum ExitAction { HOLD, TIGHTEN_STOP, NO_OP }
```

**★ 注意 `ExitAction` 里没有 `CLOSE`。** 这是 P1 原则的编码化：盈利保护引擎永远不发起平仓。
（用户第十七条：不要让多个模块同时发起 Close。）

输入：`ExitContext(entry, initialStop, initialRisk, side, markPrice, mfePeak, mfeR, maeR, holdingMinutes, currentState, appliedStop, tickSize, config)`。

### 4.2 保护价公式（R-based，方案 A）

记 `mfeR` = 峰值有利偏移（R）。

```
stage 0 : mfeR <  breakEvenActivationR
          stop = initialStopLoss                       // 不干预

stage 1 : breakEvenActivationR ≤ mfeR < profitLockActivationR
          stop = effectiveBreakevenPrice               // 覆盖手续费后不亏

stage 2 : mfeR ≥ profitLockActivationR
          stop = entryPrice + sign * (mfeR - givebackR) * risk
          其中 givebackR = min(maxGivebackR, mfeR * givebackRatioPct / 100)
```

**baseline 参数**（见 §9，均为待校准值）：
`breakEvenActivationR = 0.75`、`profitLockActivationR = 1.5`、`givebackRatioPct = 35`、`maxGivebackR = 3.0`。

验算（LONG，entry=1.00，initialSL=0.95 ⇒ 1R=0.05）：

| mfeR | 峰值价 | givebackR | 保护价 | 锁定 |
|---|---|---|---|---|
| 0.5 | 1.025 | — | 0.95（初始） | 0 |
| 0.75 | 1.0375 | — | ≈1.0017（break-even） | ≈0 |
| 1.5 | 1.075 | 0.525 | 1.0488 | +0.975R |
| 2.0 | 1.10 | 0.70 | 1.065 | +1.30R |
| 3.0 | 1.15 | 1.05 | 1.0975 | +1.95R |
| 6.0 | 1.30 | 2.10 | 1.195 | +3.90R |
| 18.0 | 1.90 | 3.00（封顶） | 1.75 | **+15.0R** |

对照用户给的例子（1.00 → 1.90 → 0.95）：新系统会在 1.75 附近离场，拿走 **+15R**，而不是 -1R。

### 4.3 单调性：为什么这个公式天然满足不变量

`stop_R(mfeR) = mfeR − min(maxG, mfeR·g)`

- 当 `mfeR·g ≤ maxG`：`stop_R = mfeR(1−g)`，随 `mfeR` 递增；
- 当 `mfeR·g > maxG`：`stop_R = mfeR − maxG`，随 `mfeR` 递增；
- 分界点连续。

而 **`mfePeak` 按定义单调不减**。所以 `stop` 单调不降（LONG）/ 单调不升（SHORT）——**不变量由公式结构保证**，不依赖运行时判断顺序。

在此之上再加**显式守卫**作为 defense-in-depth（也是单测对象）：

```java
// MonotonicStopGuard
boolean improves(BigDecimal candidate, BigDecimal current, TradePlanType action) {
    if (current == null || current.signum() <= 0) return true;           // 无当前值 → 接受
    return action == OPEN_SHORT
        ? candidate.compareTo(current) <= 0     // SHORT：只能更低
        : candidate.compareTo(current) >= 0;    // LONG：只能更高
}
// 违反 → 拒绝 + 记 PROFIT_PROTECTION_INVARIANT_VIOLATION 事件（而不是静默接受）
```

**守卫被违反时的处理：拒绝 + 告警，绝不静默接受。** 这样"旧事件把止损降回去"会变成可观测事件而不是隐性资金损失（对应测试用例 10）。

### 4.4 三条安全夹逼（防止生成"立即触发"的止损）

1. **不能贴着市价**：
   `LONG: stop ≤ markPx × (1 − stopBufferFromMarkPct/100)`
   `SHORT: stop ≥ markPx × (1 + stopBufferFromMarkPct/100)`
   baseline `stopBufferFromMarkPct = 0.10`。
   *否则价格一波动就立刻被自己的保护单打掉，等于变相强制平仓。*
2. **不能低于初始 SL**（LONG）：`stop ≥ initialStopLoss`。
3. **tick 归一化**：用 `CanonicalPlanCompiler#normalizeStopPrice`（STOP 角色对 LONG 用 `FLOOR`、SHORT 用 `CEILING`，即**只会让止损更宽**）。
   *注意：这与"只向盈利方向移动"方向相反，所以归一化后必须再过一遍单调守卫；若归一化导致变差，则**放弃本次改动**（等下一次峰值更高时再移）。*

### 4.5 为什么选方案 A（R-based），而不是 B / C / D

| 方案 | 可行性（基于代码） | 结论 |
|---|---|---|
| **A. R-based** | `entry` / `initialStopLoss` / `markPx` 全部可得；`ExitLeg.targetR` 字段已存在于 canonical plan | ✅ **V1 采纳** |
| B. ATR / 波动率 | `ContractSignalAnalyzer#atr(safe,14)` 与 `atrPct` **已实现**，但是 `private static` 且只在扫描期计算，未持久化到 trade plan | ⚠️ V1.1。需要先把 ATR 写进 plan/state |
| C. 结构（swing low/high） | `StructureSnapshot.recentSwingLow/recentSwingHigh` **已实现**（`ContractSignalAnalyzer#structure`），同样是 private record、未持久化 | ⚠️ V1.1。初始 SL 本身已经是 `swingLow − 0.2×ATR`，结构上是有延续性的 |
| D. Hybrid | 依赖 B/C | 待 B/C 落地后再评估 |

**决定性理由**：`agent/evaluation/CounterfactualExitService` 的离线策略里已经存在 `TRAIL_1R`（`best` 峰值 − 1R）和 `BE_075`（0.75R 激活 break-even）。
**选方案 A 可以让 live 实现与已有离线评估语义对齐**，从而 `counterfactual_exit` 表（V32）里的历史数据可以直接用于校准本次的 baseline 参数。选 B/C 会让已有离线评估失去参照价值。

这不是"为了简单"，而是**让新代码接入已有的校准闭环**。

### 4.6 Effective Break-even

```
LONG : effectiveBreakevenPrice = entryPrice × (1 + (roundTripFeePct + estimatedSlippagePct)/100) + tickSize
SHORT: effectiveBreakevenPrice = entryPrice × (1 − (roundTripFeePct + estimatedSlippagePct)/100) − tickSize
```

- baseline `roundTripFeePct = 0.12`（开+平 ≈ 0.06%×2，USDT 永续 taker 量级）
- baseline `estimatedSlippagePct = 0.05`
- 再叠一个 `tickSize` 缓冲，避免"刚好等于成本价"被计为盈利。

**资金费不进入公式**（它由持仓时长决定，是运行时量不是开平成本），但在 giveback 统计里通过 `AccountBill` 的 FUNDING 体现。

> 另有 OKX 自带的 `bePx`（breakeven price，positions 接口返回，含手续费）。V1 **不使用**，因为：(a) 它不含资金费也不含滑点；(b) 引入它会产生第二个事实源。V1.1 可考虑 `max(计算值, bePx)` 作为保守修正。
> 满足用户第十三条"先审查已有数据是否足够"——结论是：够用，且不过度优化。

### 4.7 关于 TP1 / TP2 / TP3

**决策：V1 完全不动 TP ladder。**

理由（代码事实）：
1. TP ladder 来自**不可变的 canonical 快照**，是 `CanonicalPlanCompiler` 的核心不变量（"Prices, target-R and fractions are never recomputed"）。动它就破坏了 V34 的设计契约。
2. 移动止损与静态 TP **可以安全共存**：两者都是 `reduceOnly`，`ExposureReductionGuard` 保证单笔 ≤ 仓位。收紧 SL 不会增加总减仓量。
3. 用户担心的"TP3 必须全部平仓"在当前系统里本来就不是必须——`tp3-ratio=0.30`，三条 TP 合计 100%，所以最后一条确实会清空剩余仓位。**但取消它需要数据支撑（趋势是否健康），V1 不做。**

**保留配置开关** `trailing-cancel-remaining-tp: false`，V1.1 在拿到 demo 数据后决定是否启用"进入 TRAILING 后撤销剩余 TP leg 让利润奔跑"。

> ⚠️ Phase 1 §7.2 已确认的既有问题：`TradeIntent#fromPlan/fromPendingOrder` 把 TP1/TP2 硬编码为 1R/2R，而 TP3 用 plan 目标价——**语义漂移确实存在**。
> **本设计不在本次修复它。** 理由：改 TP 语义会改变 canonical plan 的编译结果，影响所有历史快照对比与 `CanonicalPlanShadow`，属于独立的策略变更，应与"盈利保护"解耦。已在 Remaining Risks 登记。

---

## 5. 持久化

### 5.1 新表：`position_exit_state`（迁移 **V36**）

当前最大迁移编号 V35，故用 V36。

```sql
CREATE TABLE position_exit_state (
  id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_name               VARCHAR(128) NOT NULL,
  pending_order_id        VARCHAR(36)  NOT NULL,
  trade_plan_id           VARCHAR(36),
  inst_id                 VARCHAR(64)  NOT NULL,
  pos_side                VARCHAR(16),

  -- 不可变事实（写入后只读）
  entry_price             DECIMAL(30,12) NOT NULL,
  initial_stop_loss       DECIMAL(30,12) NOT NULL,
  initial_risk            DECIMAL(30,12) NOT NULL,
  action                  VARCHAR(32)  NOT NULL,   -- OPEN_LONG / OPEN_SHORT

  -- 极值 / MFE / MAE
  highest_since_entry     DECIMAL(30,12),
  lowest_since_entry      DECIMAL(30,12),
  mfe_price               DECIMAL(30,12),
  mae_price               DECIMAL(30,12),
  mfe_pct                 DECIMAL(18,8),
  mae_pct                 DECIMAL(18,8),
  mfe_r                   DECIMAL(18,8),
  mae_r                   DECIMAL(18,8),
  mfe_at                  TIMESTAMP,
  mae_at                  TIMESTAMP,

  -- 退出状态
  exit_state              VARCHAR(32)  NOT NULL,
  exit_state_since        TIMESTAMP,
  state_version           BIGINT       NOT NULL DEFAULT 0,

  -- 保护止损（applied = 交易所已确认；desired = 本轮意图）
  applied_protective_stop DECIMAL(30,12),
  desired_protective_stop DECIMAL(30,12),
  stop_algo_cl_ord_id     VARCHAR(32),
  stop_algo_id            VARCHAR(128),
  stop_version            INT          NOT NULL DEFAULT 0,
  stop_applied_at         TIMESTAMP,
  pending_since           TIMESTAMP,
  pending_req_id          VARCHAR(32),

  -- 最近观测
  last_mark_price         DECIMAL(30,12),
  last_price_at           TIMESTAMP,

  -- 了结
  exit_reason             VARCHAR(32),
  realized_r              DECIMAL(18,8),
  mfe_r_final             DECIMAL(18,8),
  giveback_r              DECIMAL(18,8),
  giveback_ratio          DECIMAL(18,8),
  closed_at               TIMESTAMP,

  created_at              TIMESTAMP NOT NULL,
  updated_at              TIMESTAMP NOT NULL,
  UNIQUE KEY uk_exit_state_owner_order (user_name, pending_order_id),
  KEY idx_exit_state_inst (user_name, inst_id, exit_state)
);
```

设计说明：
- 唯一键 `(user_name, pending_order_id)` 与全库 owner 隔离约定一致（V15/V16）。
- `state_version` 用于乐观并发（compare-and-set），见 §6.4。
- `stop_algo_id` 必须存：Phase 1 §5.3 指出 `ActiveAlgoProtection` 目前**没有保留 `algoId`**，而 `POST /api/v5/trade/cancel-algos` 的签名要求 `algoId`（python-okx 里 `set_cancel_algos(algoId, instId)`，`algoId` 为必填）。存下来才能让撤销更可靠。

### 5.2 为什么需要新表而不是扩 `pending_order_state`

- `pending_order_state` 已经有 30+ 列和多个迁移（V12/V15/V30/V34），继续加会加剧耦合。
- 退出状态有自己的并发语义（CAS 版本号、pending 意图），与 pending order 的状态机写入点是分离的。
- 独立表让"退出状态"成为一个可独立测试、可独立恢复的聚合。

---

## 6. 幂等与并发

### 6.1 问题的真实形态（纠正一个前提）

用户担心"连续 50 次 tick 都判定要把 SL 提到 1.30，产生 50 个请求"。
**但 Phase 1 §3.1 确认：当前没有 tick 驱动。** lifecycle 由 `AutoTradeRecoveryTask` 每 30 秒调用一次。

真实风险是：
- 30 秒一轮 × 长时间持仓 = 也会产生大量重复判定；
- 恢复任务与启动恢复可能重叠；
- **多副本部署时**（Phase 1 R11，无分布式锁）两个实例并发跑 lifecycle。

因此下面四层防护仍然必要，且第 4 层是针对多副本的。

### 6.2 第 1 层：desired vs applied（消除重复判定）

```
每轮：
  desired = engine.evaluate(ctx).targetProtectiveStop
  if (!MonotonicStopGuard.improves(desired, applied))               → NO_OP
  if (|desired - applied| / applied < minStopImprovementBps/10000)  → NO_OP   // 最小有意义变化
  if (now - stopAppliedAt < minStopAmendIntervalSeconds)            → NO_OP   // 最小间隔
  if (amendCountInLastHour >= maxProtectiveStopAmendsPerHour)       → NO_OP   // 限流
  else → 执行改动
```

baseline：`minStopImprovementBps = 5`（0.05%）、`minStopAmendIntervalSeconds = 60`、`maxProtectiveStopAmendsPerHour = 20`。

> 这三层节流保证：一个稳定上涨的仓位，每小时最多改 20 次止损，且每次至少要改善 0.05%。这远低于 OKX 的 20 次/2s 限频。

### 6.3 第 2 层：改单前先读回（解决超时不确定，测试用例 8）

```
amend 流程：
  1. GET /api/v5/trade/order-algo?algoClOrdId=<stopAlgoClOrdId>
     - 读到 slTriggerPx == desired  → 直接把 applied = desired，不发请求，返回
     - 读到 slTriggerPx 比 desired 更好 → applied = 交易所值（交易所是事实源），返回
     - 读不到 / 报错 → 转 §5 的 REPAIR 分支，不盲目 amending
  2. POST /api/v5/trade/amend-algos  { instId, algoClOrdId, newSlTriggerPx, reqId }
  3. 再次读回确认；只有确认成功才写 applied = desired
  4. 任一步超时/异常 → 保持 desired + pending_since，下一轮从第 1 步重来
```

**关键：第 1 步的"读回确认"让"超时"变成"可收敛"而不是"重复下单"。** 即使上一次 amend 实际成功但我们没收到响应，下一轮读回会发现 `slTriggerPx` 已经是目标值 → 直接收敛。

### 6.4 第 3 层：稳定幂等键

| 用途 | 键 | 生成 |
|---|---|---|
| algo 下单 | `algoClOrdId` | **沿用现有 `lifecycleAlgoClOrdId` 的 `lc<h>SL` 格式**（向后兼容 `endsWith("sl")` 的 role 推断，见 §8.3） |
| algo 改单 | `reqId` | `"ps" + sha256(pendingOrderId + ":" + targetStop.toPlainString())[0..20]` |

`reqId` 是 OKX 明确的"用户自定义修改事件ID（1-32 位）"——**同一目标价的重复 amend 使用同一 reqId**，提供交易所侧的可追溯性。

### 6.5 第 4 层：CAS 版本号（多副本）

```sql
UPDATE position_exit_state
   SET applied_protective_stop = ?, stop_version = stop_version + 1,
       state_version = state_version + 1, updated_at = ?
 WHERE id = ? AND stop_version = ?
```
影响行数 ≠ 1 → 说明并发更新发生 → 放弃本轮（下一轮会基于新值重新计算）。

> 注意：这只保护**本地状态**的并发写；交易所侧的并发 amend 由第 2 层的读回 + monotonic guard 共同保证最终收敛（即使两个实例都发了 amend，目标值都是 `f(mfePeak)`，单调守卫保证不会变差）。

### 6.6 与现有 JVM 锁的关系

保留 `AutoTradeService` 的 `AtomicBoolean running` 等现有 JVM 内锁。本设计**不引入** Redis/分布式锁（架构边界未变），但通过 CAS 让多副本至少不会写坏状态。多副本部署仍是既有风险（Phase 1 R11），不因本次而改变。

---

## 7. OKX 执行策略

### 7.1 三个方案的评估

| 方案 | 描述 | 评估 |
|---|---|---|
| A | 完全本地计算，用普通 `conditional` SL | 可行，但改价只能 place-new + cancel-old ⇒ **新旧 SL 并存窗口** ⇒ 重复减仓风险（Phase 1 R6） |
| B | 进入 trailing 后用 OKX `move_order_stop` | ❌ **不采纳**，见下 |
| **C'** | **本地计算 + `amend-algos` 原位改价** | ✅ **采纳** |

### 7.2 为什么不把保护交给 OKX `move_order_stop`

已核实（OKX 官方文档 + python-okx `algotrade.py`，2026-09-17）：

- `move_order_stop` 用 `callbackRatio`（回调比例，%）/ `callbackSpread`（价距）/ `activePx`（激活价）表达。
  → **只能表达"从最高点回撤 X%"，无法表达"锁定 X R 利润"**。R 是本笔交易的风险单位，百分比不是。
- `POST /api/v5/trade/amend-algos` 明确写着 **"仅支持止盈止损单，不包含计划委托、冰山委托、时间加权、移动止盈止损等订单"**。
  → **一旦用了 `move_order_stop`，我们就失去了本地干预能力**，退化为"只能撤不能改"。
- 未完成移动止损单上限 **50** 笔（`General Info` 明确列出），而 TP/SL 单是 100/合约。
- 取消它需要 `POST /api/v5/trade/cancel-advance-algos`（**另一个端点**，不是现在的 `cancel-algos`），又要新增一套代码与恢复路径。

**结论：把"保护多少利润"的策略判断留在本地（可测、可恢复、可用 R 表达），把"价格落在哪"的执行交给 OKX 原位改单。这正好是用户第九条要求的分层。**

### 7.3 采纳的执行原语

**新增 `POST /api/v5/trade/amend-algos`**（注意是复数 `amend-algos`，不是 `amend-algo-order`）：

```json
{
  "instId": "BTC-USDT-SWAP",
  "algoClOrdId": "lc<hash12>SL",
  "newSlTriggerPx": "1.7500",
  "reqId": "ps<hash20>"
}
```

已核实的响应/语义要点：
- 限速 20 次/2s，规则 `UserID + Instrument ID`；
- `cxlOnFail`：修改失败时是否自动撤单，**默认 false**。→ **我们保持默认 false**，因为自动撤单会让仓位失去保护，违反"失败要保守"原则；
- `newSlTriggerPx` 传 0 = 删除止损 → **我们的代码必须对 `newSlTriggerPx` 做非空/正数校验**，防止 `BigDecimal.ZERO` 被序列化成删除指令；
- 只适用于交割和永续 ⇒ 与本项目 SWAP 场景匹配。

**降级路径**：若 `amend` 返回明确不支持/报错（例如 demo 环境限制），回退到现有 `place-new + cancel-old`（`stopLossPayload` + `cancelAlgo`）。降级时保留"先建后撤"顺序，且**新单用不同 `algoClOrdId` 后缀**（`SLR`）以避开 ID 冲突——这正是现有代码已经在做的事。

### 7.4 网关改动

`OkxOrderGateway` 新增：
```java
JsonNode amendAlgoOrder(Map<String, String> payload, TradingOperationContext context);
```
`OkxRestOrderGateway` 实现 → `POST /api/v5/trade/amend-algos`。

`TradingOperationType` 新增 `AMEND_PROTECTION`，并让它 `reducesExposure() == true`，从而自动走 `ExposureReductionGuard`。

`ExposureReductionGuard` 需要为 `AMEND_PROTECTION` 单独分支（现有逻辑对非 CLOSE 操作强制要求 `reduceOnly` + `sz`）：
- 要求：`algoId` 或 `algoClOrdId` 至少一个（幂等键）
- 要求：仓位存在、`ownership == MANAGED`、mode 匹配（沿用现有检查）
- **不要求** `sz` 与 `reduceOnly`（amend 不改数量；且 OKX 的 amend 请求体里本来就没有这两个字段）

### 7.5 仍然使用的 OKX 对象（回答"实际用了哪些单"）

| 对象 | ordType | 何时用 | 变化 |
|---|---|---|---|
| 入场单 | `limit` / `market` | 开仓 | 不变；仍带 `attachAlgoOrds` 附随 SL |
| 附随 SL | `conditional`（attach） | 入场时 | 不变 |
| 独立 SL | `conditional`，`reduceOnly=true`，`slOrdPx=-1` | 成交后建保护 | 不变（ID 仍为 `lc<h>SL`） |
| **SL 改价** | — | 盈利保护 | **新增 `amend-algos`** |
| TP1/TP2/TP3 | `conditional`，`reduceOnly=true`，`tpOrdPx=-1` | 成交后 | **不变** |
| SIDEWAYS_TP | `conditional` | 横盘超时 | **不变** |
| 平仓 | `close-position` | max-hold / safety | **不变** |
| `move_order_stop` | — | — | **不使用** |

### 7.6 Role 推断的向后兼容（关键坑）

Phase 1 §5.3 / R3：`activeAlgoProtections()` 靠 `clientId.endsWith("sl")` 判定 `STOP_LOSS`，且 `hasValidStop()` 认不出就触发安全平仓。

**因此：动态保护单必须继续复用同一个 `algoClOrdId`（`lc<h>SL`），绝不新建不同后缀的"trailing 单"。**
- amend 是在**同一个 algo order 上改价**，`algoClOrdId` 天然不变 → 完美兼容。
- 这比"新建一条 trailing 单"安全得多，也顺带解决了 R3。

---

## 8. Recovery 策略

### 8.1 恢复时机

- 复用现有 `AutoTradeLifecycleService#runOnce` 的入口（30 秒一轮），**不新增定时任务**。
- 恢复逻辑作为 lifecycle 的一个前置阶段：`reconcileExitState(order, position)`，位置在现有的 `reconcileCanonicalProtection` **之前**。
- 这样启动恢复（`TradingStartupRecovery` → `AutoTradeRecoveryTask` → `lifecycleService.runOnce`）自动受益，无需改动启动流程。

### 8.2 决策矩阵：Local vs Position vs Orders

```
输入：local = position_exit_state 行（可能为 null）
      pos   = OKX 持仓（可能为 null）
      algos = GET /api/v5/trade/orders-algo-pending (ordType=conditional)
```

| local | pos | 交易所 SL | 决策 | 说明 |
|---|---|---|---|---|
| — | null | — | **SKIP** | 无仓位，无事可做 |
| — | 有 | 有 | **CREATE + ADOPT** | 从 canonical plan + position.avgPx 建行；`applied = 交易所 slTriggerPx`（**采纳交易所值**）；MFE 从 entry 起算；state 按 `f(mfeR)` 反推 |
| — | 有 | 无 | **CREATE + REPAIR** | 建行（state=`INITIAL_PROTECTION`），走现有 `submitProtection` 建 SL；**不 safety close** |
| 有 | null | — | **CLOSE** | 结算 giveback，`exit_state=CLOSED`；退出原因尝试从 algo history 的 `actualSide` 推断 |
| 有 | 有 | 有，且 **优于** local.applied | **ADOPT** | `applied = 交易所值`（交易所是事实源） |
| 有 | 有 | 有，且**劣于** local.applied | **AMEND** | 按 local.desired 改回；单调守卫兜底 |
| 有 | 有 | 无 | **REPAIR** | 重新建 SL（用 `lc<h>SL`，与首次一致） |
| 有 | 有 | 查询失败 | **KEEP + 告警** | **绝不 safety close**，记事件，下轮再来 |

**★ 测试用例 9（"OKX 已有有效 protective SL，本地 canonical 丢失 → 不应立即 Safety Close"）由表中第 2 行与最后一行共同覆盖：**
- canonical 丢失但交易所有有效 SL → `ADOPT`，不触发 `hasValidStop==false` 的安全平仓路径；
- 查询失败 → `KEEP + 告警`，不 close。

这与现有 `reconcileCanonicalProtection` 的保守倾向一致，我们不改变它，只是**在它之前**补上本地状态恢复，使其不必走到"canonical 不可用"那条路。

### 8.3 MFE 的恢复

- `highestSinceEntry / lowestSinceEntry / mfeR` 从 DB 恢复，**不重置**。
- 恢复后第一轮立刻用当前 `markPx` 更新一次极值。
- 若 local 行为 null（程序首次升级到本版本），MFE 从当前价起算（保守：低估历史 MFE），并在 `updated_at` 旁标注 `state_version=0` 以便识别"非完整历史"的样本。
  → 分析 giveback 统计时应过滤这类样本，避免污染校准数据。

### 8.4 幂等

所有恢复分支写库都通过 `(user_name, pending_order_id)` 唯一键 + CAS `state_version`。重复执行不产生重复行、不重复改单（因为改单前先读回，见 §6.3）。

---

## 9. 配置

新增 `AgentProperties.ProfitProtection`（`quant.agent.profit-protection.*`）。

| 配置项 | baseline | 作用 | 是否需校准 |
|---|---|---|---|
| `enabled` | `true` | 总开关（可一键回退到旧行为） | — |
| `price-source` | `MARK` | `MARK` / `LAST` | 低 |
| `break-even-activation-r` | `0.75` | 激活 break-even 的 mfeR | **高** |
| `profit-lock-activation-r` | `1.5` | 激活利润锁定的 mfeR | **高** |
| `giveback-ratio-pct` | `35` | 允许的回吐占 MFE 的比例 | **高** |
| `max-giveback-r` | `3.0` | 回吐上限（R），防止大仓位回吐过大 | **高** |
| `round-trip-fee-pct` | `0.12` | 开平手续费（用于 effective break-even） | 中 |
| `estimated-slippage-pct` | `0.05` | 预估滑点 | 中 |
| `stop-buffer-from-mark-pct` | `0.10` | 止损与市价的最小距离 | 中 |
| `min-stop-improvement-bps` | `5` | 最小有意义改善（去抖） | 低 |
| `min-stop-amend-interval-seconds` | `60` | 两次改单最小间隔 | 低 |
| `max-protective-stop-amends-per-hour` | `20` | 限流 | 低 |
| `trailing-cancel-remaining-tp` | `false` | TRAILING 时是否撤销剩余 TP | **高**（V1.1） |
| `mfe-sample-source` | `POSITION_MARK` | `POSITION_MARK` / `CANDLE` | 中（V1.1） |

**只有 16 项，全部影响策略行为或安全边界，没有无意义配置。**

> ⚠️ **baseline 不等于最优。** 这些数字的来源只有一处：与 `CounterfactualExitService` 的 `BE_075` / `TRAIL_1R` 对齐，保证语义连续。
> 它们**没有**经过本项目历史数据的回测。校准路径见 §13。

`move-stop-to-breakeven`（Phase 1 发现的死配置）**删除或标注废弃**——它的职责由 `break-even-activation-r` 正式承接。为避免破坏既有 yml 兼容性，V1 只加 `@Deprecated` 注释，不删字段。

---

## 10. Exit Priority（显式化）

Phase 1 §3.3 指出当前优先级是**靠 `if/continue` 代码顺序隐式表达**的。

**决策：V1 不重构这个结构**（用户第二十二条：不要无意义重构）。改为：

1. 新增一个纯函数 `ExitPriority`，把现有顺序**显式声明**为常量表，并在 lifecycle 里加注释指向它——**只做文档化，不改行为**；

```java
// 与 AutoTradeLifecycleService#runOnce 的 if/continue 顺序一一对应
public enum ExitPriority {
    EMERGENCY_SAFETY_CLOSE,   // 1. canonical 不可用且无有效 SL
    ENTRY_TIMEOUT_CANCEL,     // 2. 入场超时撤单
    PROTECTION_REPAIR,        // 3. 保护缺失恢复
    HARD_TIME_STOP,           // 4. max-hold / loss-close
    SIDEWAYS_TP_ADJUST,       // 5. 横盘收紧
    PROFIT_PROTECTION,        // 6. 盈利保护（本次新增，只改止损）
    MONITOR                   // 7. 常规对账
}
```

2. **本次新增的 `PROFIT_PROTECTION` 排在第 6 位，且它的 `ExitAction` 里没有 `CLOSE`** ⇒ 结构上不可能与其他平仓分支冲突。

> 这满足用户第十七条：Single Exit Authority 继续由 `runOnce` 持有；盈利保护**不新增平仓发起方**。
> `ExitPriority` 枚举是只读的、纯声明式的，不引入调度框架。

---

## 11. 可观测性

**只在状态变化 / 保护价变化 / 守卫拒绝 / 异常时打 INFO。** 正常 `HOLD` 走 DEBUG。

统一日志格式（一次一行，KV）：

```
exit_decision pendingOrderId={} instId={} side={} entry={} initialSL={} risk={} markPx={} \
  currentR={} mfeR={} maeR={} oldStop={} newStop={} exitState={}->{ } action={} reason={} stopVersion={}
```

另有三类必须单独告警（WARN）：
- `PROFIT_PROTECTION_INVARIANT_VIOLATION`（单调守卫被违反 → 说明有 bug 或并发问题）
- `PROFIT_PROTECTION_AMEND_UNCERTAIN`（改单结果未知 → 下轮读回）
- `PROFIT_PROTECTION_REPAIR_REQUIRED`（交易所保护缺失 → 触发重建）

事件落 `trade_event`（复用 `TradeEventService#record`），`TradeEventType` 新增：
`PROFIT_PROTECTION_ARMED`、`PROTECTIVE_STOP_TIGHTENED`、`PROFIT_GIVEBACK_RECORDED`。
**不新增** `TradeEventType` 的平仓类取值（平仓事件沿用现有）。

---

## 12. 测试计划

测试文件：`src/test/java/com/example/quant/agent/exit/`

| # | 用例 | 断言要点 |
|---|---|---|
| 1 | 涨到保护条件后回落 | price 1.00→1.10→回落到 1.05；`exitState` 从 `INITIAL_PROTECTION` 前进到 `PROFIT_LOCKED`；`appliedStop > initialSL` |
| 2 | 单边持续上涨 | 每轮只升不降；断言 `appliedStop` 序列单调不减（**属性测试**：10 个随机递增价格） |
| 3 | 高位震荡 | 价格在峰值附近来回；断言 `exitState` **不回退**、`appliedStop` 不下降、amend 请求次数为 0（被去抖拦截） |
| 4 | 高 MFE 后暴跌 | 1.00→1.90→0.95；断言 `mfeR=18`、最终 `appliedStop` 已到 ~1.75（**不会停在 0.95**）、`givebackR` 被记录且合理 |
| 5 | Break-even | 断言 `effectiveBreakevenPrice > entryPrice`（含费用）；刚过 break-even 时 `realizedR >= 0`，不被错误统计为盈利 |
| 6 | Partial TP | TP1 成交后仓位缩小；断言剩余 TP/SL 数量 = 当前仓位、`TP_PROTECTION_QUANTITY_EXCEEDS_POSITION` 不触发 |
| 7 | 重启恢复 | 内存状态清空后从 DB 重建；断言 `entryPrice/initialSL/mfeR/appliedStop/exitState` 全部一致，且**不发新单**（读回后判定已 applied） |
| 8 | 改单超时 | amend 抛超时；断言下一轮先读回、不重复发 amend、`appliedStop` 最终收敛、请求次数 ≤ 2 |
| 9 | 本地 canonical 丢失 + 交易所有效 SL | 断言**不触发** `triggerSafetyClose`，走 ADOPT 分支，`appliedStop = 交易所值` |
| 10 | 旧事件/旧价格到达 | 传入过期低价；断言 `MonotonicStopGuard` 拒绝、记 `INVARIANT_VIOLATION`、`appliedStop` 不变 |
| 11 | LONG / SHORT 对称 | 同一组场景参数化跑两边；断言 `stop` 方向、不等式符号、break-even 计算全部镜像 |
| 12 | 极小仓位 | 仓位 = `minSz` 单张；断言不创建非法 TP 分腿、不产生 `MIN_SIZE_UNEXPRESSIBLE` 异常、保护仍成立 |

**另有纯函数单测（不依赖 Spring）**：
- `ProfitProtectionEngineTest`：公式表驱动（对应 §4.2 验算表）
- `MonotonicStopGuardTest`：LONG/SHORT 边界、`current==null`、相等值
- `ExitStateMachineTest`：所有非法回退被拒、terminal override 生效
- `GivebackCalculatorTest`：`mfeR<=0`、`realizedR>mfeR`、`mfeR=0` 除零

**回归要求**：现有 `AutoTradeLifecycleServiceTest`、`CanonicalPlanCompilerTest`、`OkxTradeAdapterTest`、`AutoTradeRecoveryTaskTest`、`ClosePositionRecoveryServiceTest` 必须全部保持通过（不修改其断言）。

---

## 13. 参数校准路径（回答"未来怎么校准"）

`agent/evaluation/CounterfactualExitService`（V32 `counterfactual_exit` 表）**已经**在离线评估 7 种策略：
`BASELINE / S1_V2 / TIME_60 / TIME_120 / TIME_240 / BE_075 / TRAIL_1R`。

它有 `riskDistance`、`best`（MFE 峰值）、`pnlR`、`holdMinutes`，**只差 MFE/MAE/giveback 的持久化**。

**建议路径**：
1. 本次上线后，`position_exit_state` 开始积累 `mfeR / realizedR / givebackR`。
2. 把本次的保护公式**加进 `CounterfactualExitService.POLICIES`**（例如 `PP_V1`），用历史 K 线重放，与 `BE_075` / `TRAIL_1R` 同表对比 `pnlR`。
3. 用 `counterfactual_exit` 的数据做网格搜索 `break-even-activation-r × profit-lock-activation-r × giveback-ratio-pct × max-giveback-r`。
4. Walk-forward 验证后再改 baseline，并把 `POLICIES` 增到 `PP_V2`。

这样 baseline 参数**从第一天起就处在校准闭环里**，而不是拍脑袋。

---

## 14. 权衡（Trade-offs）

| 决策 | 选择 | 放弃的 | 理由 |
|---|---|---|---|
| 保护策略 | R-based（A） | ATR / 结构 | 与已有离线评估对齐；B/C 的指标未持久化 |
| 执行原语 | `amend-algos` | `move_order_stop` / place+cancel | 无并存窗口；保留本地干预能力；能表达 R 语义 |
| 是否动 TP | 不动 | 动态 TP / TRAILING 撤 TP | 保护 canonical 不变量；减少风险；留配置开关 |
| 是否发起平仓 | 不发起 | 主动 giveback 平仓 | 避免多模块 Close；SL 收紧已能兜住 |
| 价格源 | positions 的 `markPx` | 新增 ticker 订阅 / candle 补全 | 零额外 API 成本；接受采样精度损失 |
| 状态存储 | 新表 | 扩 `pending_order_state` | 独立并发语义；可独立测试恢复 |
| 优先级 | 文档化，不重构 | 抽出调度框架 | 用户明确禁止无意义重构 |
| MFE 精度 | 观测 MFE | 真实 MFE | 30s 采样对小时级行情足够；V1.1 可选 candle 补全 |

---

## 15. 计划改动的文件（Phase 3 用）

**新增**
- `agent/exit/PositionExitState.java`（枚举）
- `agent/exit/ExitDecision.java`（record）
- `agent/exit/ProfitProtectionEngine.java`（纯函数，无 Spring 依赖）
- `agent/exit/MonotonicStopGuard.java`
- `agent/exit/GivebackCalculator.java`
- `agent/exit/PositionExitStateEntity.java` + `PositionExitStateRepository.java`
- `db/migration/V36__position_exit_state.sql`
- 测试：`agent/exit/` 下 6 个测试类

**修改（最小化）**
- `config/AgentProperties.java`：新增 `ProfitProtection` 内部类
- `resources/application.yml`：新增 `profit-protection` 配置块（全部 baseline）
- `account/dto/PositionSummary.java`：新增 `markPrice` / `lastPrice`
- `account/PositionSnapshotService.java`：映射 `markPx` / `last`
- `okxtrade/OkxOrderGateway.java` + `OkxRestOrderGateway.java`：新增 `amendAlgoOrder`
- `okxtrade/TradingOperationType.java`：新增 `AMEND_PROTECTION`
- `okxtrade/ExposureReductionGuard.java`：为 `AMEND_PROTECTION` 加分分支
- `agent/lifecycle/AutoTradeLifecycleService.java`：插入 `reconcileExitState` + `applyProfitProtection` 两个阶段
- `agent/event/TradeEventType.java`：新增 3 个事件类型
- `agent/evaluation/CounterfactualExitService.java`：`POLICIES` 增加 `PP_V1`（校准闭环）

**不改动**
- `TradeIntent`（TP 1R/2R 硬编码问题登记为独立风险，不在本次修）
- `CanonicalPlanCompiler` / `CanonicalExecutablePlan`（不可变快照契约不变）
- `ContractRiskService` / `DailyRiskService` / `AutoTradeBudgetService`
- `OrderStatus`（不新增状态）
- 前端（V1 不改 UI；lifecycle 快照可后续加字段）

---

## 16. 实施结果（Phase 3）

按本设计完整实施。`mvn -o test`：**516 个测试全部通过，零回归**（其中 59 个为本次新增）。
`V36 - position exit state` 迁移在 H2 上验证通过，Spring 上下文正常加载。

实测行为（LONG，entry 1.00，initialSL 0.95 ⇒ 1R = 0.05）：

| 峰值 | mfeR | 保护价 | 锁定 |
|---|---|---|---|
| 1.0375 | 0.75 | 1.01（effective break-even） | ≈0 |
| 1.10 | 2.0 | 1.06 | +1.2R |
| 1.15 | 3.0 | 1.09 | +1.8R |
| 1.30 | 6.0 | 1.19 | +3.8R |
| 1.90 | 18.0 | 1.75 | **+15R** |

用户的示例路径 `1.00 → 1.90 → 0.95` 现在在 **1.75 离场（+15R）**，而不是回到 0.95 的 -1R。

## 17. Remaining Risks

### TP_SEMANTIC_DRIFT_RISK（既有问题，本次不修改）

**事实**：`TradeIntent#fromPlan` / `#fromPendingOrder` 把 TP1 硬编码为 `entry + 1R`、TP2 为 `entry + 2R`，
而 TP3 才使用 `TradePlan.takeProfitPrice()`。也就是说 TradePlan 只贡献了三个腿里的一个，
另外两个腿的 R 语义是 `TradeIntent` 工厂方法强加的。

**为什么不修**：`CanonicalExecutablePlan` 是编译期不可变快照，被 `CanonicalPlanShadow` 用于
`TP_SEMANTIC_DRIFT` 检测，也被 V34 持久化。改变 TP 语义会改变所有新计划的编译结果，
使历史快照对比失效，属于独立的策略变更，必须与"盈利保护"解耦。

**本次已做的隔离**：盈利保护引擎完全不读 TP 价格，只移动 SL；TP ladder 一行未改。
`trailing-cancel-remaining-tp` 已做成配置项（默认 false），撤 TP 逻辑**未实现**，仅预留。

**建议处理**：单独一次变更，把 TP1/TP2 的 R 倍数配置化或改由策略层显式提供，
并同步更新 `CanonicalPlanShadow` 的 drift 基线。

### 其他遗留风险

| ID | 风险 | 说明 |
|---|---|---|
| RR1 | **MFE 是采样值** | lifecycle 30 秒轮询，两次轮询之间的瞬时极值会被漏掉。MFE 是"观测到的 MFE"。V1.1 可用 `OkxMarketService#candles` 的 high/low 补齐（配置项 `mfe-sample-source` 已预留）。 |
| RR2 | **realizedR 是毛值** | 平仓时从真实 exit fill 的 VWAP 反算，**不含手续费与资金费**。净 realizedR 应改用 `LedgerPnlService` 的 net PnL，需接入 `ClosePositionRecord` 的 realizedPnl。 |
| RR3 | **退出原因仍未区分 TP/SL** | 持仓被交易所打掉后仍以 `POSITION_GONE` 结束。OKX `GET /api/v5/trade/order-algo` 返回 `actualSide`（`tp`/`sl`）可以区分，需查 algo 历史，本次未做。 |
| RR4 | **amend-algos 未在 demo 环境实测** | 端点与参数已按 OKX 官方文档与 python-okx 实现（`newSlTriggerPx` / `reqId` / `cxlOnFail`），但尚未在 demo trading 上跑通。上线前必须用 `TRADING_MODE=DEMO` 验证一次。 |
| RR5 | **无降级开关** | 若 demo 验证发现 amend 不可用，需回落到 place-new + cancel-old。该路径未实现（现有代码只有 place+cancel，但会因 client ID 撞车而受限）。 |
| RR6 | **多副本并发** | 无分布式锁。本地状态靠 JPA `@Version` 乐观锁保护，交易所侧靠"读回 + 单调守卫"最终收敛，但两个实例可能在同一轮都发 amend（目标值相同，幂等无害）。 |
| RR7 | **`cancel-algos` 需要 algoId** | OKX 该端点的 `algoId` 为必填，而现有 `cancelAlgo` 只传 `algoClOrdId`。本次未改（避免超出范围），但已在 `position_exit_state.stop_algo_id` 存下 algoId 备用。 |
| RR8 | **baseline 参数未经校准** | 全部 13 个参数均为 baseline，未用本项目历史数据回测。校准路径见 §13。 |

## 18. 变更记录

### 2026-09-17
- 新增 `docs/PROFIT_PROTECTION_DESIGN.md`（Phase 2 设计）。
- 未修改任何业务代码、配置、迁移或测试。
- 已核实 OKX 官方能力：`POST /api/v5/trade/amend-algos`（仅支持止盈止损单，含 `newSlTriggerPx` / `reqId` / `cxlOnFail`）、`GET /api/v5/trade/order-algo`（含 `actualSide: tp|sl`）、positions 接口含 `markPx` / `last` / `bePx` / `uplRatio`。
