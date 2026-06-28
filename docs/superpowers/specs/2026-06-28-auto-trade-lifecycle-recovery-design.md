# 自动交易扫描与生命周期恢复设计

## 目标

本次只处理三个问题：

1. 候选扫描成功后，不因扫描总耗时超过阈值而丢弃本轮自动交易。
2. 已进入持仓阶段的自动订单在 OKX 持仓消失后，自动关闭本地生命周期并释放预算。
3. 清理已经失效的本地活动订单和没有对应持仓的 OKX 保护单。

不在本次接入 OKX fills/bills，也不改造真实收益统计。

## 慢扫描处理

`ContractMarketScanTask` 在候选刷新成功后始终调用 `AutoTradeService`。当总耗时超过 `scan-round-timeout-seconds` 时，仅记录 `SCAN_SLOW` 告警并在任务结果中保留耗时，不再提前返回 `SCAN_TIMEOUT`。

候选刷新抛出异常、返回过程失败或自动交易执行自身失败时，继续使用现有失败处理。扫描锁保持不变，仍禁止同一时间重复扫描。

## 持仓消失判定

只有 OKX 持仓接口成功返回后，才允许根据“持仓不存在”推进关闭。持仓查询异常时不修改订单、预算或保护单。

只处理已经明确进入持仓阶段的本地自动订单：

- `ENTRY_FILLED`
- `EXECUTED`
- `PROTECTION_SUBMITTED`
- `ACTIVE`
- `ACTIVE_WITH_TP_WARNING`
- `SIDEWAYS_TIMEOUT`
- `SIDEWAYS_TIMEOUT_TP_ADJUSTED`
- `MAX_HOLD_TIMEOUT`
- `PROTECTION_FAILED`
- `EMERGENCY_ATTENTION_REQUIRED`

`PENDING_CONFIRM`、`BUDGET_RESERVED`、`SUBMITTING`、`SUBMITTED` 和 `UNKNOWN_SUBMIT_STATUS` 不通过持仓消失直接关闭，避免把未成交或状态未确认的入场委托误判为已平仓。

## 生命周期关闭

`ClosePositionRecoveryService` 继续负责生命周期终态。每轮恢复在处理显式 `CLOSE_SUBMITTED` 记录后，再检查符合条件的持仓阶段订单：

1. 按 `instId + posSide` 匹配当前 OKX 持仓。
2. 持仓仍存在时不处理。
3. 持仓不存在时，创建或复用一条来源为 `POSITION_GONE` 的关闭记录。
4. 将本地订单标记为 `CLOSED`。
5. 释放关联的 `RESERVED` 或 `USED` 预算。
6. 失效关联的活动入场记录并清理保护单。
7. 记录关闭和预算释放事件。

重复执行必须幂等：已经 `CLOSED` 的订单、已经 `RELEASED` 的预算和已经失效的保护单不能重复产生副作用或重复关闭记录。

## 无效订单与孤儿保护单

扩展现有 OKX 当前委托同步：除入场记录外，也把“OKX 已不存在”的本地活动保护记录标记为 `INACTIVE`。同步必须分别使用普通委托和策略委托的 OKX 订单号、客户端订单号判断，不能只按合约判断。

持仓消失后，恢复服务清理该合约所有本地仍标记为活动的 reduce-only 保护记录，包括没有 `pendingOrderId`、但由 OKX 当前委托同步写入的记录。处理规则：

- 有 `algoId` 时按 `algoId` 撤销；否则按 `algoClOrdId` 撤销。
- OKX 撤销成功后标记为 `INVALID/CANCELLED`。
- 其他撤销失败标记为 `PROTECTION_CANCEL_FAILED`，保留现有重试机制。
- 没有可用订单标识的本地记录直接标记 `INVALID`，因为无法形成有效的 OKX 活动保护单。

不撤销仍有同方向持仓的保护单，不撤销普通入场委托，不按时间猜测订单失效。

## 数据与兼容性

复用现有 `close_position_record`、`pending_order_state`、`trade_order` 和预算预留表，不增加数据库字段或迁移。

现有控制器响应和前端数据结构保持不变。新增的来源值仅使用 `POSITION_GONE`，满足现有数据库字段长度限制，页面可按普通字符串展示。

## 测试

- 扫描耗时超过阈值但候选刷新成功时，自动交易仍被调用。
- 扫描刷新异常时，自动交易不被调用。
- 持仓阶段订单对应持仓消失时，订单关闭且 `USED` 预算释放。
- `SUBMITTED` 未成交订单没有持仓时不被误关闭。
- 持仓查询异常时不关闭订单、不释放预算、不撤保护单。
- 关联保护单和无 `pendingOrderId` 的同合约孤儿保护单被撤销并失效。
- 仍有持仓的保护单不被清理。
- 撤销失败进入 `PROTECTION_CANCEL_FAILED`，后续可重试。
- 重复执行不重复创建关闭记录，不重复释放预算。

## 风险边界

本次使用“OKX 持仓接口成功且持仓不存在”作为自然退出事实，不推断具体由止盈、止损还是手工操作触发。真实成交价、手续费、资金费和已实现盈亏仍需后续接入 fills/bills 才能确定。
