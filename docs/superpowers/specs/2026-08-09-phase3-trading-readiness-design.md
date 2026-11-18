# Phase 3 Trading Readiness 设计

## 目标与边界

本阶段把系统推进到可安全接入 OKX Demo 的工程状态，不承诺 Exactly Once，也不自动切换 LIVE。交易所 REST 继续是订单、成交、仓位和账单的最终权威；Private WebSocket 只提供低延迟事件，连接恢复后必须先完成 REST reconciliation 才能标记 READY。

## 方案选择

采用“REST 权威状态机 + WS 实时加速 + 数据库幂等/租约”。没有采用 WS 主导架构，因为 OKX orders 频道没有订阅初始快照，且重复、迟到事件必须由 REST 修正；没有只增加 Demo Header，因为它不能解决重复决策、终态、部分成交和重启恢复。

## 核心模型

- `TradingMode`：仅允许 `DISABLED/DEMO/LIVE`。DISABLED 阻断所有写请求；DEMO 为所有私有 REST 请求添加 `x-simulated-trading: 1` 并使用 Demo Private WS URL；LIVE 不添加模拟头。
- `strategy_decision`：唯一键为 `owner + strategy_id + inst_id + timeframe + candle_ts`。首次领取写入 `PROCESSING`；任何重复、并发或重启扫描不能再次执行。崩溃遗留 PROCESSING 转为 `RECONCILING`，不自动重放决策。
- `trade_order`：保存 requested/filled/remaining size、average fill price、OKX update timestamp 和本地 reconciliation 状态。终态只由 Order Details/History/Fills 或不倒退的 WS 事件确认。
- `trade_fill`：按 `owner + inst_id + trade_id` 唯一去重，保存真实成交价格、数量、费用、成交 PnL 和关联订单。
- `account_bill`：按 `owner + bill_id` 唯一去重，分别保存 pnl、fee、funding fee 与 balance change。
- `trading_instance_lease`：数据库单行租约保护单交易实例；租约丢失立即把交易引擎置为 BLOCKED。

## 启动与恢复

应用启动时交易引擎依次进入 `INITIALIZING → RECOVERING → READY/DEGRADED/BLOCKED`。恢复顺序为：取得实例租约、确认运行模式/凭据、REST 拉取活动订单、逐笔对账未知和活动本地订单、同步 fills/bills、拉取仓位并识别 EXTERNAL/UNMANAGED、恢复过期决策、启动 Private WS。任何 UNKNOWN 订单、租约失败、凭据失败或仓位对账失败都会阻止新开仓。

## WS 生命周期

状态为 `DISCONNECTED/CONNECTING/AUTHENTICATING/SUBSCRIBING/READY/DEGRADED/RECONNECTING/FAILED`。使用 Java 17 `HttpClient.WebSocket`，订阅 `orders(SWAP)/positions(SWAP)/account`；指数退避带 jitter，定时 ping 和 stale 检测。重复 fill 由 tradeId 唯一约束吸收，订单事件按 `uTime` 和终态优先级更新，旧 LIVE 不能覆盖 FILLED/CANCELED。

## 安全语义

系统提供持久化决策幂等、稳定 clOrdId、本地意图和交易所对账，目标是在超时、崩溃和重复事件后可恢复；不声称 Exactly Once。不确定时保持 RECONCILING/BLOCKED，宁可错过交易，不产生新的风险敞口。

