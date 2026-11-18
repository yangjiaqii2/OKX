# OKX 交易分析与自动交易整体优化策略

> 日期：2026-08-10
> 当前系统基线：`READY FOR DEMO`，`NOT READY FOR LIVE`
> 参考仓库：[`sorry510/go_binance_futures`](https://github.com/sorry510/go_binance_futures)，审查基线 `master@20152433ed00c03f567c1aac0164588eaca68391`
> 文档性质：工程实施方案，不表示下述问题已经修复，也不构成投资建议。

## 1. 总体结论

参考项目有三类能力值得当前 OKX 系统吸收：

1. **可解释的选币预过滤**：候选、排除原因、数据缺口、数据时间和“不追涨”风险一起输出，而不是只给一个分数。
2. **策略配置与影子运行**：指标和开平仓条件可配置，同一套条件可以先在不触发交易所写操作的环境中持续观察。
3. **按交易事件分类的外部通知**：开仓、平仓、策略、资金费率和异常分别通知，并允许按模块启停。

参考项目的下列实现不能照搬：

- 它按每个币种固定 USDT 金额和杠杆计算数量，不是按止损距离约束最大亏损。
- 它可以在模拟交易连续盈利后自动打开真实交易，这是不可接受的实盘切换方式。
- Private WS 使用递归重连和常驻 keepalive goroutine，没有完整连接状态、退避上限、资源清理和重连后权威对账。
- 启动同步会删除本地活动订单和全部仓位后重建，订单兜底还会根据“没有仓位”删除开仓记录，不能满足 UNKNOWN、部分成交和可审计恢复要求。
- 自定义策略、预过滤和收益计算使用大量 `float64` 与固定阈值，不能直接成为资金安全边界。
- 通知直接启动 goroutine 发送，没有持久化、幂等、超时、重试状态和投递审计，且参考代码存在记录 token 的风险。
- 自定义平仓兜底中的 [`nowProfit < 3 || nowProfit > -3`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/feature/strategy/line/line_custom.go#L162-L170) 对所有正常数字恒为真，后续反向 K 线判断实际不可达，说明其策略参数和实现不能作为本项目正确性依据。

因此，当前系统不应移植参考项目的交易主链路；应只吸收它的产品表达方式，并继续以本项目已经建立的 `TradingMode`、稳定 `clOrdId`、订单对账、Private WS、持仓归属和 Ledger 为安全底座。

第二次策略复核进一步确认：当前文档的安全问题判断成立，但实施范围需要收敛。最优先的不是继续增加指标或调整评分，而是把交易分析明确拆成三个不可混用的阶段：

1. **可交易性资格**：判断合约、数据和盘口是否足以安全交易，失败时硬拒绝。
2. **策略内机会排序**：只在已合格的合约中，按趋势延续、回调延续等独立策略分别建立多空候选池。
3. **下单前执行检查**：使用计划数量重新检查盘口冲击、风险预算、组合敞口和数据新鲜度。

一个综合分数不能同时承担这三种职责。硬资格不能被高策略分抵消，策略排名也不能替代下单时的实时执行检查。

审查快照中未发现 `LICENSE`/`COPYING` 文件，因此以下内容只借鉴设计思路；未经作者明确授权，不复制其源码到本项目。

## 2. 目标交易链路

```text
OKX Ticker / Closed Candle / Order Book / OI / Funding
    -> 数据单位和新鲜度校验
    -> 可交易性硬过滤
    -> 按策略拆分的多空候选池
    -> 确定性信号与市场环境
    -> 确定性 Entry / SL / TP 计划
    -> AI 解释、新闻摘要或降级为 WATCH
    -> 方向、价格几何与净盈亏比硬校验
    -> 按计划数量进行实时执行检查
    -> 按最大可承受亏损计算仓位
    -> 预算、组合敞口与每日风险硬上限
    -> OKX 规则规整后的最终风险复核
    -> 持久化 Intent / Decision / clOrdId
    -> OKX 下单
    -> Private WS 实时事件 + REST 最终对账
    -> 单一保护单体系
    -> Fills / Bills / Funding Ledger
    -> Trade Event
    -> Telegram / Mattermost 通知 Outbox
```

核心原则：AI 不拥有交易方向、价格、杠杆和仓位的最终决定权；评分、预算槽位和 AI 都只能缩小、降级或拒绝交易，不能突破按真实止损计算出的最大风险仓位。

## 3. 参考项目中值得借鉴的能力

### 3.1 可解释预过滤结果

参考项目的 [`scanner/prefilter.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/scanner/prefilter.go#L28-L57) 同时返回：

- `Candidates`
- `Excluded` 与排除原因
- `DataMissing`
- `GeneratedAt`、数据来源和筛选参数
- 每个候选的 `Reasons`、`Risks`、`Missing`、`NotChase`

它还显式检查成交额、行情陈旧、极端涨幅、长上影、冲高回落和价格偏离 24 小时中枢，并用测试验证排除原因和缺失字段。这个“为什么入选、为什么排除、缺什么数据”的输出方式值得直接借鉴。

但其评分区间偏向上涨的山寨币，并主动排除 BTC/ETH，不能用于本项目的多空双向交易。当前系统应借鉴数据结构和可解释性，不复制 `-5% ~ 35%`、`60%`、中枢偏离等具体阈值。

### 3.2 策略模板和可配置表达式

参考项目把 MA、EMA、RSI、KC、BOLL、ATR 组织成命名指标，并允许用表达式配置 `long / short / close_long / close_short`。实现可见 [`technology/define.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/technology/define.go) 和 [`line_custom.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/feature/strategy/line/line_custom.go#L21-L61)。

值得借鉴：

- 策略参数和交易执行分离。
- 指标有稳定名称，策略表达式只引用已声明输入。
- 开仓和平仓条件分别配置。
- 策略模板可复用到不同 symbol。

当前系统若后续增加可配置策略，只应先用于 Demo/Shadow，并必须具备：版本号、输入数据时间、只读表达式环境、编译校验、复杂度限制、仅使用已收盘 K 线、持久化 candle decision 幂等和发布审批。它不能绕过确定性风控，也不能直接执行任意代码。

### 3.3 同策略影子运行

参考项目的 [`feature_test_strategy.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/feature/feature_test_strategy.go#L25-L145) 会扫描与真实策略相同的条件，把模拟开平仓结果持久化。这种“先持续影子观察，再决定是否进入 Demo”的方法值得借鉴。

但它不是真实 OKX Demo，不包含真实撮合、部分成交、手续费、滑点和资金费；更不能复制其 [`autoTestToTrade`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/feature/feature_test_strategy.go#L415-L435) 自动打开真实交易的行为。

本项目不需要增加第四种 `TradingMode`。更小且安全的做法是增加独立的 `strategy.shadow-enabled`：只产生 Decision、Plan 和评估记录，不创建 PendingOrder，不调用任何 OKX 写网关。

### 3.4 事件分类通知

参考项目用 `Pusher` 抽象开仓、平仓、策略测试、资金费率和价格异动通知，并允许按 module 覆盖配置，见 [`notify/notify_interface.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/notify/notify_interface.go#L109-L128) 与 [`notify/common.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/notify/common.go#L28-L55)。

值得借鉴的是“事件分类”和“按模块启停”，不是其直接异步 HTTP 实现。本项目已经有持久化 `trade_event`，应从这些事实事件生成通知，不应在下单业务代码中逐处调用 Telegram/Mattermost SDK。

### 3.5 WS 降低 REST 压力与无数据告警

参考项目将公共 ticker 先缓存在内存、批量刷库，并跟踪最后接收时间；超过阈值会通知，见 [`feature/api/binance/index.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/feature/api/binance/index.go#L667-L784)。Private user stream 也与周期 REST 查询组合使用。

这个方向与本项目现有设计一致：WS 是实时事件源，REST 是恢复与最终权威。当前 OKX Private WS 生命周期、重连后 REST reconciliation 和状态机已经比参考实现完整，不需要重写；只需继续完成真实 Demo E2E。

### 3.6 每币种策略覆盖与限价单超时

参考项目支持每个 symbol 覆盖策略、杠杆、保证金模式和止盈止损，并会取消超过等待时间的限价委托。这两点可以转化为：

- `SymbolPolicy` 只允许收紧全局规则，例如禁用、降低杠杆、提高最低流动性、指定策略版本。
- 入场限价单配置 TTL，超时后先查询成交量，再撤销剩余部分；已部分成交数量必须进入保护链路。

不允许 symbol 配置提高全局最大杠杆、扩大单笔风险或关闭硬风控。

## 4. 当前问题与整体修复方案

### 4.1 Critical：自动仓位没有受单笔最大亏损约束

#### 当前事实

- [`PositionSizingService`](../src/main/java/com/example/quant/position/PositionSizingService.java) 按 `权益 × 单笔风险率 ÷ 止损距离` 计算计划仓位。
- [`AutoTradeService`](../src/main/java/com/example/quant/agent/execution/AutoTradeService.java) 自动执行时传给预算器的 `riskBasedMaxMarginUsdt` 实际是总预算/账户可用余额，而不是 `plan.suggestedMargin()`。
- [`AutoTradeBudgetService`](../src/main/java/com/example/quant/agent/budget/AutoTradeBudgetService.java) 最终取槽位预算、所谓风险上限、剩余预算和单仓预算的最小值；由于输入错误，45%/30%/25% 槽位成为主要仓位来源。
- 预算器虽然计算了 `scoreFactor` 和 `qualityAdjusted`，最终 `finalMargin` 仍取原始 `slotBudget`，评分除了为零时也没有真正缩小仓位。
- [`OrderConfirmService`](../src/main/java/com/example/quant/order/OrderConfirmService.java) 对自动订单把单笔保证金率设为 100%，且最低成交额和信号分为 0。
- `ContractRiskService` 计算了允许最大亏损，但没有把“本次订单按止损估算的真实亏损”与该值比较。

#### 修复方案

1. 自动预算分配必须传入 `plan.suggestedMargin()` 作为风险上限：

   ```text
   finalMargin = min(
     plan.suggestedMargin,
     slotBudget,
     remainingBudget,
     accountAvailable,
     maxSinglePositionBudget
   )
   ```

2. 预算槽位只负责资金分配，不能反向放大风险仓位；评分只允许把仓位乘以 `0..1`。
3. `OrderConfirmService` 不再为自动路径设置 100% 保证金上限，不再把最低信号分和最低成交额置零。Hard Safety 在所有模式下相同。
4. 在 OKX `ctVal / ctValCcy / lotSz / tickSz` 规整数量和价格以后进行最后一次风险复核。复核使用 `OkxInstrumentRules` 的真实合约换算，不假设“一张等于一个币”。
5. 最终风险至少包含：止损价格损失、入场/退出手续费缓冲和预估滑点。资金费只在预计跨越结算时单列计入，不与交易手续费混淆。
6. 若最小合法 `minSz` 已超过本次最大风险，拒绝交易，绝不向上放大。
7. 手动确认、自动交易和任何未来机器人下单入口都必须调用同一个最终风险校验。

#### 验收标准

- 止损更远时最终下单数量单调下降。
- 槽位预算大于风险仓位时不能放大仓位。
- 规整后的最坏损失不高于 `equity × singleTradeRiskPercent`。
- 账户权益或 instrument rules 不可用时禁止新开仓。
- minSz 超风险上限时返回明确拒绝原因。

### 4.2 Critical：AI 仍进入可执行交易参数生成

#### 当前事实

- AI 输出的 `OPEN_LONG / OPEN_SHORT` 没有与候选方向绑定。
- 止损距离和收益距离大量使用绝对值，错误方向仍可能得到正数。
- `safeRiskReward` 在风险或收益方向无效时返回最低合格值 `1.5`，会把非法几何伪装为合格计划。
- AI 不可用或返回 WAIT 时，本地“高可用”规则仍可能继续生成计划。

#### 修复方案

最安全且更容易验证的边界是：**方向、Entry、SL、TP、杠杆和仓位全部由确定性代码生成，AI 只负责解释、新闻摘要、风险提示或将候选降级为 `WATCH`。** AI 不能将 `WATCH` 升级为交易，不能翻转方向，也不能扩大任何风险参数。

在 `ContractTradePlanBuilder` 内保留最小的确定性校验步骤，不建立通用规则框架：

```text
候选 BULLISH -> 只允许 WATCH 或 OPEN_LONG
候选 BEARISH -> 只允许 WATCH 或 OPEN_SHORT
候选 NEUTRAL -> 只允许 WATCH

LONG  -> stopLoss < entry < takeProfit
SHORT -> takeProfit < entry < stopLoss
```

- 任一价格为空、非正数、方向错误、止损距离为零或净盈亏比不足，计划直接变为 `WATCH/INVALID`。
- `safeRiskReward` 遇到非法几何必须返回无效结果或抛出受控校验异常，不能返回最低合格值。
- AI 输出不得覆盖确定性计划的方向、Entry、SL、TP、杠杆和仓位。若保留旧 DTO 字段，只作为解释内容审计，不进入订单对象。
- AI 失败时继续使用已经通过几何和风控校验的确定性计划；若确定性规则不能证明方向与价格有效，则 `WATCH`。
- 已将 temperature 从 `1` 调整为 `0.1`；计划审计同时记录策略版本和输入数据时间。模型与提示词版本仍需在后续配置版本化时补齐。

#### 验收标准

- 多头候选 + AI `OPEN_SHORT` 被拒绝。
- 多头止损高于入场、空头止损低于入场均被拒绝。
- 非法几何不能得到 `1.5R`。
- AI 超时、畸形 JSON、提示词变化和模型切换不会改变确定性计划的资金参数。
- AI 只能把计划降级为 `WATCH`，不能把被硬门槛拒绝的候选升级为订单。

### 4.3 Critical：重复保护单和止损口径不一致

#### 当前事实

- 入场委托使用 `attachAlgoOrds` 附带全仓 SL 和全仓 TP。
- 附带 SL 使用固定 `3% ÷ 杠杆`，不是计划止损。
- 完全成交后生命周期再次提交全仓 SL 和三档 TP，形成两套保护逻辑。
- 保护失败配置为 `CLOSE_POSITION`，当前主要行为仍是标记失败并等待恢复。

#### 推荐的单一保护体系

1. 入场订单只附带一个**紧急全仓止损**，价格必须等于经过 tick 规整后的计划止损；删除附带全仓 TP。
2. 主订单完全成交后，先从 OKX 查询并持久化这张 attached SL 的 `algoId`，生命周期不得再提交第二张 SL。
3. 生命周期只按真实 filled size 提交 TP 分档。TP 数量合计不得超过当前持仓；每次部分成交、人工减仓或 TP 成交后都以交易所仓位重新对账。
4. 部分成交且剩余委托超时：先确认累计成交量，再撤剩余入场单，然后对已成交部分提交一张 SL 和可合法拆分的 TP。
5. 所有保护单带 `protectionGroupId + generation`。调整保护时先识别旧 generation，再执行取消/替换和 REST 对账，禁止并存未知代保护单。
6. 若无法确认保护状态，进入 `RECONCILING/BLOCKED`；若确认仓位存在且没有有效 SL，则真正执行配置的保护失败动作。若选择紧急平仓，必须使用稳定 `clOrdId`、reduce-only/正确 close 语义和超时对账。

#### 验收标准

- 一个 managed position 任一时刻最多有一张有效全仓止损。
- 附带止损、UI 计划止损和仓位风险计算使用同一价格。
- TP 总量不超过交易所当前仓位。
- 重复 fill/WS 事件不会重复创建保护单。
- 保护创建超时不会无脑重发；按 `algoClOrdId/algoId` 对账。

### 4.4 High：OKX 合约成交额单位错误

#### 修复方案

- 对 SWAP ticker，`volCcy24h` 是基础币数量，USDT 近似成交额应为 `volCcy24h × last`。
- 若使用 `vol24h` 合约张数回退，必须按 instrument 的 `ctVal`、`ctValCcy` 和价格换算，不能简单乘价格。
- 在 DTO 中显式区分：`baseVolume24h`、`contractVolume24h`、`quoteTurnover24h`、`turnoverSource`，UI 只把最后一项标成 USDT。
- 缺少单位或规则时停止使用成交额做交易门槛，并把该候选标记为数据缺失。

#### 验收标准

- BTC、ETH 和低价币用相同 USDT 口径比较。
- fixture 覆盖高价币、低价币、不同 `ctValCcy` 和字段缺失。
- UI 不再把基础币数量显示为“USDT 成交额”。

### 4.5 High：候选池偏向涨幅榜

#### 修复方案

借鉴参考项目的 `Excluded/Reasons/Risks/Missing/NotChase`，但不要继续建设一个承担所有职责的综合分。候选选择改成以下三段：

#### 第一段：可交易性资格

这是方向无关的硬门槛，任一关键字段未知时失败关闭：

- 仅支持系统已明确实现的 USDT SWAP，instrument 必须为可交易状态。
- 使用 OKX `listTime` 设置可配置的最短上市观察期；新币、历史 K 线不足和 OI 基线不足不得自动交易。
- instrument rules、ticker、K 线、OI、funding 和订单簿必须在允许的新鲜度内。
- 使用正确的 USDT turnover，并检查 spread、目标数量可成交深度和预计冲击成本。
- mark/index/last 偏离、basis、funding 或短时振幅异常时拒绝或降级，不用高趋势分抵消执行风险。

#### 第二段：策略内候选池

- 不在过滤前按 24 小时涨幅截断 Top 30。
- 初期只建立 `TREND_CONTINUATION_LONG/SHORT` 与 `PULLBACK_LONG/SHORT` 四个镜像池。
- 趋势延续和回调延续分别排名，不共用一套权重；反转/均值回归先保留在 Shadow，未建立独立止损、退出和验证前不进入自动交易。
- 排名可使用 turnover、spread、depth、趋势、波动和 OI 等横截面 percentile 或 robust z-score；绝对安全阈值仍放在第一段。
- BTC/ETH 保留为市场环境输入；是否允许其成为交易候选由配置显式决定。

#### 第三段：下单前执行检查

- 使用**最终计划数量**重新查询 spread、depth 和预计滑点，不能复用扫描阶段的小额样例结果。
- 重新检查数据时间、当前持仓、未成交订单、同向敞口、组合风险和剩余每日风险。
- 执行检查只允许拒绝或缩小订单，不得提高候选分或扩大数量。

输出必须包含完整排除原因、数据缺口、数据时间、所属策略池和 `NotChase/NotShort` 标记，不把“缺数据”算成低分或安全。初期不增加机器学习排名；先修正数据和分层边界，再用 Shadow/Demo 数据校准阈值。

### 4.6 High：OI 变化不是历史变化

#### 修复方案

- 按 `instId + observedAt` 持久化 OI 快照，至少保留 5m、20m、1h 可比较窗口。
- 使用对应时间点的真实历史值计算：

  ```text
  oiChangePct = (currentOI - previousOI) / previousOI
  ```

- 找不到历史点时返回 `UNKNOWN`，OI 因子不加分；不能用 0 假装“没有下降”。
- OI 单位、来源和采样时间进入审计记录。
- 价格/OI 使用同一时间窗口，并按组合状态解释：

  | 价格 | OI | 解释候选 |
  |---|---|---|
  | 上涨 | 上升 | 新多头或趋势增仓 |
  | 上涨 | 下降 | 空头回补，不等于新多趋势确认 |
  | 下跌 | 上升 | 新空头或趋势增仓 |
  | 下跌 | 下降 | 多头去杠杆/清算，不等于新空趋势确认 |

- 价格/OI 组合只作为策略上下文与确认因子，不独立触发开仓。

### 4.7 High：每日风险配置没有进入交易链路

#### 修复方案

实现一个持久化的 owner 级每日风险快照，数据来自 fills、bills、持仓和 entry decision：

- `dailyTradeCount`：按唯一入场 decision/order 计数，不按 fill 条数计数。
- `consecutiveLosses`：按完整关闭的 managed trade 计数，不按单个 TP leg 计数。
- `dailyRealizedNetPnl`：仅在账本同步可靠时使用。
- `dailyEquityDrawdown`：以 UTC 日初权益高水位和当前 OKX 权益计算，作为更保守的硬门槛。
- `cooldownUntil`：连续亏损或单笔止损后持久化，重启不丢失。

硬风控被触发后：禁止新开仓，但始终允许撤单、reduce-only、保护单和人工平仓。

当前 `max-daily-loss-percent: 30` 对资金安全过于宽松。先把配置真正接入链路，并同时用风险单位 `R` 表达：`1R` 为单笔计划最大损失，每日停止阈值和组合 heat 使用若干 `R` 配置。百分比只作为最终资金上限，不在文档中把 2%~3% 固化为普适参数；具体值必须由单笔风险、最大并发仓位和 Demo 结果共同校准，且不能由 AI 或机器人修改。

### 4.8 High：“严格模式”仍有假状态和放宽路径

#### 修复方案

- 删除自动路径对最大资金费率的 3 倍放宽，以及最低信号分、最低成交额清零。
- 账户查询失败不得使用用户预算代替真实可用余额。
- 风控请求中的市场数据连接和延迟必须来自真实 Runtime Status；在 Public WS 未实现时，使用 REST 响应时间和 ticker/candle 时间戳计算 freshness，不能使用 `safeDefaults()` 的固定安全值。
- `NO_RISK` 只能作为候选观察或 Shadow 模式名称，不能发送 OKX 写请求。
- Hard Safety 不因 manual/admin/AI/机器人入口而变化。

### 4.9 High：没有可证明的回测和样本外验证

#### 最小可用实现

先做事件驱动的单策略验证器，不建立大型回测平台：

- 从现在开始持久化每次入选和排除快照，包括策略版本、原始特征、数据时间、排除原因、计划价格和后续结果；否则无法判断规则是否真正改善选币。
- 只使用已收盘 K 线，信号在本根收盘后产生，最早按下一根可成交价格执行。
- 使用真实 tick/lot/minSz 规则。
- 计入 maker/taker fee、方向性滑点、资金费和止损跳空。
- 支持部分成交或至少采用保守的全额 taker 成交假设。
- 结果包括净收益、最大回撤、胜率、profit factor、Sharpe、交易次数、MFE、MAE、成交率、实际/估计滑点，以及按策略、symbol、市场状态和评分分位的表现。
- 对每个策略分数区间统计“扣除成本后先达到 `+1R` 还是 `-1R`”的概率和期望值；分数未完成这种校准前只表示相对排序，不宣称是成功概率。
- 使用训练段、验证段、样本外段和 walk-forward，禁止用同一数据调参后再报告收益。

参考项目 README 已明确其模拟策略“无回测功能”，因此它的固定止盈止损参数不能作为本项目收益依据。

### 4.10 Medium：K 线与指标口径

- 20m K 线按 `floor(timestamp / 20min)` 对齐自然边界，只保留四根完整且连续的 5m K 线；缺一根则该 20m bar 无效。
- RSI 改为 Wilder 平滑，或将当前实现明确改名为 simple RSI，避免误导。
- ADX 实现标准 `+DM/-DM/TR -> +DI/-DI -> DX -> Wilder ADX`；若暂不实现，删除 ADX 名称和相关门槛。
- 为指标加入公开固定样本的 golden test，防止实现与 UI 名称不一致。

### 4.11 Medium：止盈止损与持仓生命周期

#### 止损

- 止损首先表达“交易假设失效点”，可来自结构低/高点和 ATR 缓冲；杠杆不能决定止损距离。
- 仓位由止损距离反推，不因希望仓位更大而收紧止损。
- 止损必须在下单前可执行、方向正确、距强平价有安全缓冲，并经过 tick 规整。

#### 止盈

- 1R/2R/3R 可以作为基线，不应在没有回测证据时视为最优参数。
- 目标应同时检查前方结构位、盘口深度、净盈亏比和最小合法拆单量。
- 计算净 R 时扣除预计双边手续费、滑点以及可能发生的 funding。
- 当前 “3 小时横盘调止盈” 与更早的最大持仓/亏损强平条件必须调整为可达顺序；若 `maxHoldMinutes <= sidewaysPositionHours × 60`，应禁用横盘分支或拒绝配置。

退出模型必须按策略分开：

- 趋势延续：结构/ATR 失效止损，第一目标结合前方结构与净 R，剩余仓位使用结构或 ATR 跟踪；不默认过早移动到保本。
- 回调延续：止损放在回调结构失效点之外，目标优先前高/前低或下一结构区域；前方障碍导致净盈亏比不足时直接跳过。
- 均值回归：若以后启用，应以均值/中轨为主要目标并使用更短 time stop，不能复用趋势策略的长期 trailing。

持仓时间统一用策略 timeframe 的 bar 数表达；墙上时间只作为故障兜底，避免“60 分钟强平”与“3 小时横盘处理”互相矛盾。

#### 组合风险

当前同向山寨数量只能作为第一层简化上限。近期先增加组合总名义敞口、同向敞口和单 symbol 上限；只有在积累足够同步收益序列后，再增加滚动相关系数/Beta，不提前建设复杂组合模型。

最终数量应满足：

```text
riskSize = maxLossBudget / perContractLossToStopIncludingCosts

finalSize = min(
  riskSize,
  remainingDailyRiskSize,
  portfolioHeatSize,
  directionalExposureSize,
  liquiditySize,
  availableMarginSize
)
```

经 `ctVal/lotSz/minSz/tickSz` 规整后必须重新计算最坏损失；规整后的 `minSz` 超限时拒绝，不得向上补量。

### 4.12 Medium：市场环境与因子数量需要收敛

市场环境不能只依赖 BTC/ETH 单一方向，建议增加全市场宽度：上涨合约比例、位于中期均线上方比例、收益中位数、横截面离散度和上涨/下跌成交额比例，最终只输出少量可执行状态：

- `TREND_UP`
- `TREND_DOWN`
- `RANGE`
- `HIGH_VOL_STRESS`

市场状态可以禁用不适合的策略，而不只是加减综合分。例如 `HIGH_VOL_STRESS` 可禁止新开追价单。

初期因子限制为趋势、波动、成交参与度、真实 OI 变化、funding/basis 拥挤度、盘口执行质量和市场宽度。避免同时加入大量高度相关指标；每个新增因子必须能通过候选快照和后续结果做消融比较，证明其提供了独立价值。

## 5. Telegram / Mattermost 通知方案

### 5.1 边界

第一阶段机器人只负责通知，不接受买入、卖出、平仓或切换 LIVE 命令。通知系统不是交易控制面：

- 通知失败不能回滚或阻塞交易状态更新。
- 通知成功不代表交易成功，消息必须显示 Exchange 状态。
- Telegram/Mattermost token、webhook 和 chat/channel id 只能存在服务端环境变量或加密配置中，不能进入前端、日志或 `trade_event`。

### 5.2 最小架构

复用当前 `trade_event` 作为事实源，只新增最小持久化 Outbox：

```text
TradeEvent committed
    -> NotificationOutbox(eventId, channel, status, attempts, nextAttemptAt)
    -> Dispatcher
    -> TelegramSender / MattermostSender
    -> SENT 或 FAILED
```

关键约束：

- 唯一键：`trade_event_id + channel`，防止重复 WS/REST 对账造成重复提醒。
- 发送状态：`PENDING / SENDING / SENT / RETRY / FAILED`。
- Query 失败可以指数退避和 jitter；通知 HTTP 可按 429 `Retry-After` 重试。交易写请求的“不重试”规则不受影响。
- 连接超时和请求超时必须明确；达到最大尝试次数后停止并在 Dashboard 暴露通知失败。
- 消息由 allowlist DTO 生成，不直接序列化 OKX 原始响应或异常对象。
- 单实例阶段可用数据库 `FOR UPDATE SKIP LOCKED` 或原子状态更新领取任务，不引入 Kafka/Redis。

### 5.3 通知优先级

#### P0：立即通知

- `ENTRY_SUBMIT_UNKNOWN`
- `PROTECTION_FAILED`
- `RECOVERY_FAILED`
- Trading Engine `BLOCKED`
- Private WS 长时间 `DEGRADED/FAILED`
- UNMANAGED position
- Kill Switch 开启
- 账本或仓位对账失败导致禁止交易

#### P1：交易生命周期通知

- 入场 ACK：`SUBMITTED/LIVE`
- 部分成交：filled / remaining / avgPx
- 完全成交和持仓建立
- 撤单确认
- SL/TP 创建、触发和取消
- 平仓提交与平仓确认
- Realized PnL、fee、funding、net PnL（仅账本可靠时）

#### P2：聚合通知

- 每日风险摘要
- 当日交易次数、连续亏损、冷却剩余时间
- 候选/信号摘要

`AUTO_CANDIDATE_SKIPPED` 不应逐条推送，否则扫描会形成通知风暴；只进入定时摘要或页面日志。

### 5.4 消息必须包含

- `TradingMode`，LIVE 必须使用高危前缀。
- `instId`、方向、订单角色、状态和时间。
- `decisionId / clOrdId / ordId / tradeId` 中可用的关联 ID。
- requested / filled / remaining size、avgPx。
- 计划 SL/TP 与当前保护状态。
- 失败原因和是否已 BLOCKED。
- PnL 的来源标签：`EXCHANGE / LEDGER / ESTIMATED / UNAVAILABLE`。

### 5.5 渠道配置

建议环境变量：

```dotenv
NOTIFICATION_ENABLED=false
NOTIFICATION_CHANNEL=TELEGRAM      # TELEGRAM 或 MATTERMOST

TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=

MATTERMOST_WEBHOOK_URL=
MATTERMOST_CHANNEL=
```

默认必须关闭。增加“发送测试消息”只能验证渠道连通性，不能改变交易模式。

如果未来需要机器人命令，应作为独立阶段：先只读 `/status`、`/positions`、`/orders`；任何资金变化命令都需要用户白名单、签名/会话认证、重放保护、二次确认、审计和最终 Trading Guard。当前需求只是提醒，因此不实现入站交易命令。

## 6. 分阶段实施顺序

### Phase A：关闭当前 Critical 风险

1. 将 AI 移出方向、Entry、SL、TP、杠杆和仓位生成，只保留解释、新闻摘要和降级权限；保留确定性几何硬校验。
2. 自动仓位使用 `plan.suggestedMargin` 作为不可突破上限，并做 OKX 规整后最终亏损复核。
3. 统一保护单：计划 SL + 单套分批 TP，消除重复。
4. 保护失败动作与配置一致。
5. 自动路径取消成交额、信号分、资金费率和市场 freshness 放宽。
6. 同步落实每日交易次数、权益回撤、连续亏损、冷却和组合 heat；这些属于资金安全，不能推迟到选币优化之后。

完成门槛：相关单元/集成测试通过，并在真实 OKX Demo 证明开仓、部分成交、保护、撤单、平仓和重启恢复；未完成前禁止自动 LIVE。

当前实现状态：确定性计划、风险仓位上限、单一保护单、每日入场硬门槛和 REST 候选/订单簿时间戳 freshness 已接入；Public WS、逐单净收益完整归因和真实 Demo 生命周期仍未完成，因此本门槛尚未达成。

### Phase B：修正选币数据和多空候选池

1. 修正 quote turnover 单位并补 fixture。
2. 建立“可交易性资格 -> 策略内候选池 -> 下单前执行检查”三段边界。
3. 初期只启用趋势延续和回调延续的多空镜像池；反转策略留在 Shadow。
4. 输出排除原因、数据缺口、数据时间、策略池和 NotChase/NotShort 标记。
5. 持久化 OI 历史，UNKNOWN 不加分。
6. 修正 20m 对齐、RSI、ADX 命名或算法。

完成门槛：固定 ticker/candle/OI/orderbook 数据集产生稳定、可解释且多空平衡的结果；BTC/ETH 和低价币的成交额口径一致，计划数量变化会正确影响执行资格。

当前实现状态：quote turnover、先资格后排名、自然 20m、持久化 OI UNKNOWN 语义和反转信号禁止自动交易已完成；新币上市观察期、独立多空策略池、标准 RSI/ADX 与真实 freshness 尚未完成。

### Phase C：尽早建立 Shadow 数据闭环

1. Shadow 运行不新增交易模式，不创建 PendingOrder，不调用 OKX 写网关。
2. 从第一天开始保存入选/排除快照、策略版本、特征值、计划参数、MFE、MAE 和先触达 `+1R/-1R` 的结果。
3. 按策略池、市场状态和评分分位统计，不把综合分解释为胜率。

完成门槛：同一输入和策略版本结果可复现；缺数据与硬拒绝可区分；每项排名因子都能做消融比较。

当前实现状态：兼容枚举 `NO_RISK` 已改为 Shadow，只保存不可执行计划及策略版本、信号池、资格、数据时间和 AI 解释来源，并由最终确认层禁止 OKX 写请求。MFE/MAE、`+1R/-1R` 后验结果、结果更新任务和消融统计尚未实现，不能把当前记录称为完整数据闭环。

### Phase D：验证体系

1. 最小事件回测器，复用 Phase C 的策略版本与特征定义，计入费用、滑点和 funding。
2. 分别验证趋势延续和回调延续，不把不同策略收益混在一个总数中。
3. walk-forward 和样本外验证。
4. 只有验证后才讨论评分、杠杆、TP 倍数和阈值校准。

完成门槛：回测与 Demo 使用相同的策略版本和参数哈希；结果可复现，且没有未来函数。

### Phase E：Telegram/Mattermost

1. Trade Event -> Notification Outbox。
2. Telegram 与 Mattermost 两个发送适配器。
3. P0/P1/P2 路由、去重、退避和失败可观测性。
4. Demo 环境真实通知演练。

完成门槛：交易提交不依赖通知服务；重复事件只通知一次；token/webhook 不进日志；渠道故障可恢复且不会产生通知风暴。

P0 风险通知可以在 Phase A 完成事实事件后提前并行实施，不必等待全部策略验证；P1/P2 和双渠道管理仍按本阶段完成。

### Phase F：Controlled Live Test 前收口

- 完成真实 Demo 多次生命周期演练，包括断线、重启、部分成交、外部仓位和 UNKNOWN。
- 处理所有 Critical/High 未解决项。
- 使用极小资金、单 symbol、单策略、低杠杆、严格每日亏损和人工值守。
- LIVE 只能由人工显式配置和启动，Shadow/Demo 成绩不能自动切换 LIVE。

## 7. 核心测试清单

### 仓位与方向

- 槽位预算大于风险仓位时不会放大。
- 不同止损距离下的仓位单调性。
- OKX minSz、lotSz、ctVal 和 tickSz 规整后风险不超限。
- AI 反向内容和错误 SL/TP 几何被拒绝。
- 更换 AI 模型、temperature 或提示词不会改变确定性计划资金参数。

### 保护单

- full fill 只有一张有效 SL。
- partial fill -> cancel remainder -> 按实际成交量保护。
- 重复/乱序 fill 不重复创建保护。
- attached SL 被查询并绑定，不再二次提交。
- 保护超时进入对账，不盲重试。

### 选币

- `volCcy24h × last` 的 quote turnover fixture。
- 可交易性硬拒绝不能被高策略分抵消。
- 趋势/回调多空池都能进入候选，排序不依赖先截涨幅榜。
- 陈旧数据、缺 OI 历史和缺 instrument rules 显式排除/降级。
- 同一计划在不同盘口深度下产生正确的可执行/拒绝结果。
- 20m 自然边界和缺 bar 测试。

### 分析与验证

- 价格/OI 四种组合映射正确，窗口不一致时返回 UNKNOWN。
- 市场状态能禁用不适合的策略，而不是只修改综合分。
- Shadow 不创建 PendingOrder，不触发任何 OKX 写请求。
- 同一策略版本和输入快照结果可复现。
- 评分分位统计包含成本后 `+1R/-1R`、MFE、MAE 和滑点，不把未知样本计为零。

### 每日风控

- 第 N+1 笔交易被阻止。
- 达到 daily drawdown 后只能减仓/撤单/保护。
- 连续亏损跨重启仍处于 cooldown。
- 账本 UNKNOWN 不被当作 0 放行。

### 通知

- 同一 `tradeEventId + channel` 只发送一次。
- Telegram/Mattermost 429、500、timeout、畸形响应。
- P0 失败进入 RETRY/FAILED，交易线程不阻塞。
- 消息不含 API Secret、Passphrase、Master Key、Webhook 或 Bot Token。

## 8. 不实施的内容

- 不复制参考项目的固定 USDT 仓位和止盈止损参数。
- 不允许模拟收益自动打开 LIVE。
- 不用排除 symbol 白名单代替现有 UNMANAGED position 归属模型。
- 不因增加通知引入 Kafka、微服务或双向机器人交易命令。
- 不在修正数据单位和执行安全前调分数追求更高收益。
- 不在趋势延续和回调延续形成独立数据证据前自动启用反转/均值回归。
- 不让 AI 生成或覆盖可执行方向、价格、杠杆和仓位。
- 不把 Shadow、Mock 或测试通过写成真实 Demo E2E 成功。

## 9. 完成后的状态判断

完成 Phase A~C 且核心集成测试通过，只能维持或重新确认 `READY FOR DEMO`。

只有真实 OKX Demo 完整验证以下链路后，才可评估 `READY FOR CONTROLLED LIVE TEST`：

- 风险仓位是最终上限。
- AI 无法改变确定性方向和硬风控。
- 无重复保护单。
- 成交额、OI 和 K 线口径正确。
- 每日风险限制真实生效。
- Private WS 断线恢复和 REST 对账通过。
- 部分成交、UNKNOWN、重启和外部仓位恢复正确。
- fills/bills/funding/net PnL 可追溯。
- Telegram/Mattermost 故障不影响交易安全。
- 无 Critical/High 未解决交易风险。

在当前代码状态下，结论仍是：**READY FOR DEMO，NOT READY FOR LIVE**。

## 10. 参考证据

- 参考项目总体功能与模拟策略边界：[`README.md`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/README.md)
- 可解释预过滤：[`scanner/prefilter.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/scanner/prefilter.go)
- 预过滤测试：[`scanner/prefilter_test.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/scanner/prefilter_test.go)
- 自定义指标与策略：[`STRATEGY.CN.md`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/STRATEGY.CN.md)
- 自动交易与固定金额仓位：[`feature/feature.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/feature/feature.go)
- 模拟策略和自动切实盘风险：[`feature/feature_test_strategy.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/feature/feature_test_strategy.go)
- User Data WS：[`feature/api/binance/index.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/feature/api/binance/index.go)
- 启动 REST 同步：[`feature/feature_userdata.go`](https://github.com/sorry510/go_binance_futures/blob/20152433ed00c03f567c1aac0164588eaca68391/feature/feature_userdata.go)
- 通知接口与模块配置：[`notify/`](https://github.com/sorry510/go_binance_futures/tree/20152433ed00c03f567c1aac0164588eaca68391/notify)
- OKX API 字段和附带 TP/SL 语义：[`OKX API v5`](https://www.okx.com/docs-v5/)
- OKX instrument 状态、规则与更新建议：[`OKX API 使用说明`](https://www.okx.com/docs-v5/trick_en/)
- 趋势研究边界（传统期货、较长周期，不能直接证明本项目短周期加密策略）：[`Time Series Momentum`](https://w4.stern.nyu.edu/facdir/lpederse/papers/TimeSeriesMomentum.pdf)
- 波动率管理的研究依据与样本外边界：[`Volatility Managed Portfolios`](https://www.nber.org/papers/w22208)、[`On the performance of volatility-managed portfolios`](https://www.sciencedirect.com/science/article/abs/pii/S0304405X2030132X)
