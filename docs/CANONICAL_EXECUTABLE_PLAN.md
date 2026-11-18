# Canonical Executable Plan

## 目的

从策略意图到 OKX 委托只保留一个可执行事实源，避免 TradePlan、Lifecycle 和 Adapter 各自重新解释 TP/SL/数量。

## 数据流

`TradeIntent`（策略经济意图） → `CanonicalPlanCompiler`（确定性 OKX 约束编译） → `CanonicalExecutablePlan`（不可变快照） → `PendingOrder` / `TradePlanRecord` → Lifecycle / Adapter。

Strategy 不感知 `lotSz`、`minSz` 或 `tickSz`。Compiler 使用编译时的 instrument snapshot，统一处理 `ctVal`、数量步进、最小数量和价格 tick。Entry、SL 及全部 TP legs 在入场提交前一次性验证。

## 拒绝语义

TP ladder 任一 leg 无法满足最小数量、余量无法表达、风险超过预算或价格几何无效时，结果为 `REJECTED`，不得静默合并、放大、丢弃或回退到旧 TP 生成器。Rejected result 同样写入 PendingOrder/TradePlan 的快照用于审计，但永远不可执行。后续可以设计显式 `REPLAN_REQUIRED`，本版本不自动重算策略。

## 消费者所有权

Lifecycle 只读取已批准快照的 SL/TP 价格；部分成交通过 `ProtectionAllocation` 只按已批准 fractions 缩放当前仓位数量，不重新计算 target-R。增量成交会对账并替换数量，不会保留旧的较小保护覆盖。Adapter 只序列化 canonical quantity/price 并做 payload 合法性断言，不从 `PendingOrder.takeProfitPrice` 生成 ladder。Lifecycle 当前直接通过 `OkxOrderGateway` 提交保护 algo，Adapter 不再拥有第二套保护生成器。

Entry attached SL 只是初始交付通道；成交后必须从交易所活动 algo 中确认 SL。缺失时使用同一 canonical SL 的稳定 client id 做 standalone recovery，确认新保护后才撤销旧保护。TP ladder 无法按当前仓位表达时保留 SL，并将状态标记为 `PROTECTION_REPLAN_REQUIRED`，不会静默合并或放大数量。若入场后交易所规则变化，先按当前规则重新表达已批准的 SL/TP 语义；若 canonical SL 无法在风险预算内表达，则创建稳定、可恢复的 `CANONICAL_SL_UNEXPRESSIBLE_AFTER_ENTRY` 终止安全减仓意图，不把仓位留在普通 ACTIVE 状态。SIDEWAYS 只作为显式的、持久化的 profit-side replacement intent，先确认新 TP active，再清理旧 TP，SL 不参与替换。

## 持久化与重启

V34 在 `pending_order_state` 与 `trade_plan` 增加 `canonical_plan_json`。快照 JSON 包含 `schemaVersion=canonical-plan-schema-v1`、`compilerVersion` 与编译时 `compiledAt`，以及 strategy version、TP leg intended/executable price 与 quantity、风险值及 tick/lot/min/ctVal 规则。PendingOrder 生成的同一份 JSON 会复制到 TradePlanRecord，避免两个持久化目标产生不同时间戳。Market Entry 的价格角色为 `REFERENCE`，真实成交价只能来自 fill VWAP。重启后优先读取已持久化快照，不因最新交易所规则悄悄重编译；入场前规则变化拒绝下单。入场后规则变化先做安全重编译；无法表达 canonical SL 时进入终止安全减仓。V34 启动检查会阻断没有 canonical 快照的活动旧生命周期、未清理的本地生命周期，以及交易所 orphan position/owned algo；已关闭历史记录允许 `NULL`，未知 schema 仅对 active fail closed，历史读取保留 UNKNOWN。

## Shadow Compile

`CanonicalPlanShadow` 只读比较旧订单事实与编译结果，输出 `PRICE_ROUNDING`、`QTY_ROUNDING`、`TP_SEMANTIC_DRIFT`、`TP_UNEXPRESSIBLE`、`RISK_MISMATCH` 等差异；不创建、撤销或修改任何 OKX 委托，也不影响预算。

## 版本

当前 compiler version：`canonical-plan-v1`。它独立于 strategy version；策略经济参数未因本次改造改变。

旧的 `mergeSmallTpOrders` 与 `finalTpUseRemainingSize` 配置已标记 deprecated；canonical compiler 不读取它们，也不允许通过配置静默改变 TP 语义。
