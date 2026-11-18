# Phase 4 Demo E2E 与 Live Readiness 实施计划

**目标：** 在不启用 LIVE 的前提下，补齐可离线验证的关键恢复能力；只有存在 Demo 凭据时才执行真实最小订单生命周期。

**安全边界：** REST 是最终权威；WS 只加速事件。任何认证、订阅、对账、仓位归属或账本同步不确定，交易引擎保持 `BLOCKED/DEGRADED`。交易写请求不自动重试。

### Task 1: Demo 前置与 Algo 终态

- [x] 无泄露检查 Demo 凭据可用性；结果为 `NOT AVAILABLE`，真实 Demo E2E 阻塞。
- [x] 用专用 Algo details 接口恢复保护单终态。
- [x] 定向测试普通/Algo 对账。

### Task 2: Private WS 最小生命周期

- [x] 先写登录、订阅确认、重复/乱序订单事件、断线退避测试。
- [x] 使用 JDK `HttpClient.WebSocket` 连接 Demo/LIVE 对应 private URL。
- [x] 认证并确认 orders/positions/account 三个订阅，REST 对账成功后才 READY。
- [x] stale/断线进入 DEGRADED，指数退避重连、重新认证订阅并 REST 对账。

### Task 3: 仓位归属与运行时恢复

- [x] 持久化交易所仓位快照和 `MANAGED/UNMANAGED` 归属。
- [x] 外部或不匹配仓位进入 UNMANAGED 并阻断冲突自动交易。
- [x] 启动、Private WS 周期/事件和账号绑定后统一执行订单/仓位对账。

### Task 4: Bills、Funding 与 PnL 可靠性

- [x] 按 owner + billId 保存 OKX 原始账单类型、金额和关联 ID。
- [x] 交易费用只取 fills，funding 只取明确账单类型，避免重复扣费。
- [x] 未完成同步的净收益返回 unavailable，不显示 0。

### Task 5: 验证与判断

- [x] 完成 timeout/429/500/畸形 JSON、WS 断线/认证失败/重复乱序事件、DB 异常测试。
- [x] 后端 clean package、前端 TypeScript/Vite、npm audit、secret scan、diff check。
- [x] 启动前后端并做 Playwright Console/Network/主要状态检查。
- [x] 无 Demo 凭据：`Demo E2E BLOCKED: NO DEMO CREDENTIALS`；结论保持 `READY FOR DEMO`。
