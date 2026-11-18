# AI 等待入场与未成交委托生命周期设计

日期：2026-08-12

## 目标

解决 AI 持续返回 `WAIT_PULLBACK / WAIT_RETEST` 时系统只记录跳过、没有后续触发的问题；同时处理限价单因价格持续远离而长期不成交的问题。系统可以错过机会，但不能为了成交无限追价或复用过期风控参数。

## 决策语义

- `decision=WAIT` 只能搭配 `WAIT_PULLBACK / WAIT_RETEST`，生成本地等待入场意图，不向 OKX 提交订单。
- `decision=TRADE` 只能搭配 `MARKET / LIMIT`。`MARKET` 立即进入现有最终确认链路；`LIMIT` 按有效价格提交 OKX 限价单。
- 非法组合解析失败并走现有规则保底，不再把 `TRADE + WAIT_*` 伪装成已选中但最终跳过。
- 评分、趋势分、量能分继续作为 AI 参考和审计字段，不因为单项软分再次否决 AI；资金安全硬规则保持不变。

## 等待入场意图

复用现有 `strategy_decision` 作为决策权威，新增与决策一对一的等待意图记录。记录至少包含：所有者、策略、合约、方向、等待类型、参考价、失效价、决策 K 线、创建时间、到期时间、状态、触发次数和原因。

状态为：

```text
WAITING -> TRIGGERED -> VALIDATING -> SUBMITTED
WAITING -> EXPIRED | INVALIDATED | CANCELED
TRIGGERED | VALIDATING -> FAILED_SAFE
```

同一 `owner + strategy + instId` 同时只允许一个活动等待意图。等待意图不预占交易预算、不占用持仓名额，也不阻止 AI 选择其他合约。

有效期取“创建后 15 分钟”和“3 根已收盘 5m K 线”中较早者。到期后释放本地意图；后续新 K 线可以重新分析，但不能重放旧决策。

## 触发与重新确认

- 多头回踩：价格回到参考价或其下方，方向仍为多，且新的已收盘 5m K 线恢复 `READY`。
- 空头回测：价格回到参考价或其上方，方向仍为空，且新的已收盘 5m K 线恢复 `READY`。
- 方向反转、市场风险升为 HIGH、新闻风险升为 HIGH/CRITICAL、价格穿越失效价时，意图直接 `INVALIDATED`。
- 触发后必须重新获取实时订单簿、Instrument Rules、账户余额和仓位，重新计算入场、止损、止盈、杠杆、仓位与最大亏损；原决策只保留方向与关联关系。
- 触发提交复用现有 TradingMode、Kill Switch、readiness、日损、相关敞口、流动性、精度、稳定 `clOrdId` 和 UNKNOWN reconciliation 保护。

## 未成交 LIMIT 管理

- 当前安全版本最长保留 15 分钟，随后请求撤单，自动调整/重挂次数固定为 `0`。
- 只有撤单接口返回成功 ACK，且订单详情再次确认 `canceled` 或 `filled` 终态后，才释放预算或按真实成交量进入保护链路。
- `51400` 只表示“可能已成交、已撤销或不存在”，不能当作撤单成功；拒绝 ACK、查询不到终态或仍为 `live` 时保留订单与预算，等待 REST reconciliation。
- 当前不追价、不发送替代订单。未来若要支持最多一次调整，必须先增加独立替换订单、全新 `clOrdId`、父子链路和逐单 reconciliation，不能复用原订单身份。

## 调度与恢复

等待意图与限价单检查复用现有市场扫描和订单 reconciliation，不新增高频轮询器。每轮先恢复 UNKNOWN/活动订单，再处理到期和触发，最后才允许产生新决策。

应用重启后加载 `WAITING / TRIGGERED / VALIDATING` 意图和活动 LIMIT：先用 REST 对账，再决定恢复等待、进入 `RECONCILING` 或终止。恢复完成前不得提交替代订单。

## 可观测性

自动交易记录和 UI 分开显示：

- `AI_WAITING_ENTRY`：AI 等待价格条件。
- `AI_WAIT_EXPIRED`：等待超时，没有成交。
- `AI_WAIT_INVALIDATED`：方向或风险条件失效。
- `HARD_RISK_BLOCKED`：确定性硬风控拒绝。
- `ENTRY_TIMEOUT_CANCELLED`：限价委托终态已确认撤销。
- `ENTRY_CANCEL_UNCONFIRMED`：撤单 ACK 或终态不确定，保留订单与预算。

Telegram 只在创建等待意图、触发、失效、订单提交、撤单/重挂失败时通知，避免每轮扫描重复推送同一等待状态。

## 验收测试

- AI 输出 `TRADE + WAIT_PULLBACK` 被拒绝为非法协议；`WAIT + WAIT_PULLBACK` 正确持久化。
- 同一决策、重复扫描和并发扫描只创建一个等待意图。
- 重启后等待意图仍可恢复，旧决策不会重复提交。
- 回踩/回测条件未满足时不下单；满足后只触发一次并重新执行完整硬风控。
- 到期、方向反转、新闻风险升级和失效价穿越会安全终止。
- LIMIT 到期只撤单、不调整、不追价。
- 原限价单取消未知时禁止替代提交。
- WAIT 不占预算和持仓槽位，其他合约仍可进入交易链路。
- 现有 MARKET、订单幂等、UNKNOWN reconciliation、Demo 标记和 Kill Switch 测试继续通过。

## 非目标

- 不修改策略指标、评分参数或收益算法。
- 不保证每个高评分合约都成交。
- 不把 WAIT 自动转换成市价追单。
- 不引入新的消息队列、分布式调度或独立微服务。
