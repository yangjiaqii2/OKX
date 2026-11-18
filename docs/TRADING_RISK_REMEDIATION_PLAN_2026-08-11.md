# 交易安全整改方案（2026-08-11）

## 结论与边界

当前 303 个后端测试通过只能证明已有断言成立，不能消除资金风险。系统可继续用于受监督的 OKX Demo 验证，但在以下 Critical 和 High 项关闭并完成真实 Demo 生命周期前，不允许 LIVE。

本方案遵循最小安全改动：优先在共同执行入口、数据库约束和权威对账点修复，不重写策略框架，不用 UI 状态代替后端保护。

## Critical

### C1. 账户读取失败回退虚拟权益

- 证据：`OrderConfirmService.accountSnapshot()` 失败后，最终确认可能使用 `ContractRiskRequest.safeDefaults()` 的 `10000` 权益继续计算。
- 风险：账户余额未知时仍可放行手动或自动订单，单笔损失比例不再以真实权益为基准。
- 整改：最终订单确认共同入口要求交易所权益和可用余额均来自本次有效账户快照；查询失败、零值或时间过期直接拒绝新开仓。只允许撤单、reduce-only 平仓和风险降低动作使用降级路径。
- 验收：账户 timeout、500、畸形响应、缺字段、零权益均不会调用写网关；查询恢复后重新确认才可交易。

### C2. 仓位归属确认前可能提交保护单

- 证据：启动恢复先运行订单生命周期，再运行仓位 reconciliation；生命周期仅按 `instId + posSide` 匹配交易所仓位。
- 风险：本地遗留 SUBMITTED 订单可能错误绑定用户在 OKX App 建立的同方向仓位，并自动提交 SL/TP。
- 整改：启动和运行时顺序固定为“订单对账 -> fills 对账 -> 仓位归属对账 -> 生命周期动作”；保护单提交必须具有 `owner -> decision -> clOrdId -> ordId -> fill -> position` 的可验证链路。无法证明归属时持久化为 UNMANAGED 并阻止冲突自动化。
- 验收：同 symbol/side 同时存在本地遗留订单和外部仓位时，不提交保护单；只有 ordId 对应 fills 可解释的实际成交量允许创建保护。

### C3. 全局凭据与系统控制缺少授权边界

- 证据：用户未绑定账号时，REST Client 可回退全局环境凭据；系统级紧停、恢复和自动交易 owner 切换只要求登录。
- 风险：多用户部署中普通用户可能读取全局账户，替换自动交易 owner 或操作系统级交易状态。
- 整改：生产私有 REST 禁止从用户绑定回退到全局凭据；全局环境凭据仅允许显式单管理员模式。系统控制写接口要求 ADMIN，审计记录操作者、旧值、新值和时间。
- 验收：普通用户不能访问其他账户或系统控制写接口；未绑定用户返回 `OKX_ACCOUNT_UNBOUND`，不会访问全局私有 API。

## High

### H1. Private WebSocket 会话代际竞态

- 根因：旧 socket 的延迟 `onClose/onError` 可以无条件清空当前全局连接；自动交易 owner 变化后未强制重连。
- 整改：为每次连接分配 generation/sessionId，回调只操作自身代际；owner 改变立即断开、重新认证、重新订阅并完成 REST reconciliation 后才 READY。
- 验收：旧连接迟到关闭不影响新 READY 会话；owner 切换期间引擎 BLOCKED，所有事件只写入对应 owner。

### H2. 每日风险统计口径不完整

- 根因：交易次数按 UTC、PnL 按 Asia/Shanghai；账单只拉一页却标记同步；连续亏损依赖从未稳定回填的关闭字段；外部账户损失未进入账户级熔断。
- 整改：统一使用配置化交易日时区，默认 `Asia/Shanghai`；账单按游标分页到当日起点并保存同步水位；从 fills/bills 生成不可重复的已实现结果；账户级日损优先使用 OKX 权威权益基线与账单，数据不完整时阻止新开仓。
- 验收：跨午夜、超过 100 条账单、部分成交、多笔费用、手工交易亏损、重启后连亏冷却均有集成测试。

### H3. Trading Engine READY 未覆盖全部风险状态

- 根因：运行对账没有统一纳入 `PROTECTION_FAILED`、本地 UNKNOWN submit、账本未完成和未管理冲突仓位。
- 整改：单一 readiness 聚合器读取订单、保护单、仓位、账本、WS、凭据和 instrument rules 状态；只有全部 hard prerequisite 就绪才允许新开仓。
- 验收：任一保护失败、未知订单、UNMANAGED 冲突或账本不可判定均显示具体 BLOCKED 原因，并在底层写网关前再次校验。

## Medium

### M1. 行情新鲜度使用本地接收时间

- 整改：保留 OKX ticker、candle 和 order book 的交易所时间戳；新鲜度使用交易所时间并结合本地单调时钟检测请求耗时。缺失或未来时间戳直接降级。

### M2. OI 变化窗口不稳定

- 整改：持久化固定窗口观测并要求最小/最大时间差；重启后的跨小时旧值返回 UNKNOWN，不参与加分。

### M3. 订单数据库唯一性不足

- 整改：清理既有重复数据后，增加 `owner + clOrdId` 唯一约束和非空 `ordId` 的 owner 范围唯一约束；WS 与 REST 插入使用同一 upsert/冲突读取路径。

### M4. 多实例与 REST 调用协调

- 整改：短期在部署文档和启动状态明确单交易实例；下一步用数据库 lease 保证只有 leader 可 READY。公共读、私有读和交易写分组限流；只对读请求做有界退避，交易写 timeout 必须先按 clOrdId 对账。

## 实施顺序

1. C1、C3：先关闭最直接的未授权与虚拟资金风险。
2. C2、H3：重排恢复链路并统一 readiness，确保未知状态不能产生新风险。
3. H1：修复 WS 代际和 owner 切换，再执行断线/乱序故障注入。
4. H2：统一交易日和账本游标，恢复日损、连亏、费用和 funding 的真实约束。
5. M1~M4：完善数据时间、OI 窗口、数据库唯一约束、单实例 lease 和读请求协调。
6. 使用 OKX Demo 完成正常成交、部分成交后撤单、平仓、重启、WS 断线、外部仓位和 ACK 丢失演练。

## Live 准入门槛

- 上述 Critical/High 全部关闭，核心 failure injection 和完整构建通过。
- 真实 Demo E2E 至少完成正常订单、撤单、平仓、部分成交、重启、WS 断线和外部仓位识别。
- 交易日账本可证明费用、funding 与 realized PnL 未重复计算，UNKNOWN 不显示为 0。
- 单交易实例得到技术约束，管理员权限和 owner 隔离经过端到端验证。
- 只能评估为 `READY FOR CONTROLLED LIVE TEST`；仍不得自动修改 `TRADING_MODE=LIVE`。
