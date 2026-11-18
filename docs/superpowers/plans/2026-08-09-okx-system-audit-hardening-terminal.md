# OKX 系统审计、交易加固与终端升级实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变策略收益参数的前提下，修复当前实盘链路中有代码证据的资金安全、数据一致性和凭据问题，并把现有 React Admin 界面升级为可信的专业交易终端。

**Architecture:** 保留 Spring Boot、React Admin、MUI 和现有 REST 权威数据流。交易状态仍以 OKX REST 查询为权威源，本地表负责意图、审计和恢复；未实现的 WebSocket 明确显示为未接入，不制造在线状态。所有核心修改采用失败测试、最小实现、回归验证的顺序。

**Tech Stack:** Java 17、Spring Boot 3.2、JPA、MySQL/H2、JUnit 5、React 18、TypeScript、MUI 6、Vite 6。

## Global Constraints

- 不修改策略阈值以提高回测收益。
- 不对交易类 POST 做无条件重试。
- 不向浏览器或日志返回 OKX Secret、Passphrase。
- 不用 mock 数据替代 OKX 数据，不写死余额、价格、仓位、盈亏或连接状态。
- 使用现有依赖和 Java 标准库，不新增运行时依赖。
- UI 使用 Industrial 方向：近黑背景、1px 边界、等宽字体、tabular numbers、弱圆角、无渐变和装饰阴影。

---

### Task 1: 恢复可信测试基线

**Files:**
- Modify: `src/test/java/com/example/quant/auth/AuthInterceptorTest.java`
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Consumes: `AuthUserContext.clear()`
- Produces: 不跨 JUnit 用例泄漏当前用户的测试基线

- [x] 在 `AuthInterceptorTest` 增加用例后清理，并先复现整套测试的用户串漏失败。
- [x] 运行 `mvn test`，确认预算和收益测试不再读取上一用例的 `admin` 上下文。

### Task 2: 移除明文配置并加密 OKX 凭据

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/example/quant/account/AesGcmOkxCredentialCodec.java`
- Modify: `src/main/java/com/example/quant/account/Base64OkxCredentialCodec.java`
- Modify: `README.md`
- Test: `src/test/java/com/example/quant/account/AesGcmOkxCredentialCodecTest.java`

**Interfaces:**
- Consumes: `QUANT_CREDENTIAL_MASTER_KEY`
- Produces: `OkxCredentialCodec.encode/decode` 的 `v1:` AES-GCM 密文；旧 Base64 只读兼容

- [x] 写测试覆盖随机 IV、往返解密、错误密钥失败和旧值兼容，并确认新格式测试先失败。
- [x] 用 JCA `AES/GCM/NoPadding` 完成最小实现；没有主密钥时禁止新绑定，不打印密钥。
- [x] 把数据库密码和默认管理员密码改为环境变量必填/安全启动提示，并记录 Git 历史中的旧凭据需要轮换。
- [x] 运行凭据测试和配置上下文测试。

### Task 3: 只允许已收盘 K 线进入策略

**Files:**
- Modify: `src/main/java/com/example/quant/crypto/OkxMarketService.java`
- Test: `src/test/java/com/example/quant/crypto/OkxMarketServiceTest.java`

**Interfaces:**
- Consumes: OKX candles 数组第 9 项 `confirm`
- Produces: 按时间升序且仅含 `confirm=1` 的 `ContractCandle`

- [x] 写包含一根未收盘 K 线的失败测试。
- [x] 过滤未确认和字段不完整的数据，运行测试确认策略输入不再含当前形成中的 K 线。

### Task 4: 下单精度和交易规则失败关闭

**Files:**
- Modify: `src/main/java/com/example/quant/okxtrade/OkxInstrumentRules.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxInstrumentRulesService.java`
- Modify: `src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java`
- Test: `src/test/java/com/example/quant/okxtrade/OkxTradeAdapterTest.java`

**Interfaces:**
- Consumes: OKX `ctVal`、`ctValCcy`、`lotSz`、`minSz`、`tickSz`
- Produces: 只向下规整且不超过风险预算的合约数量；无法取得真实规则时禁止下单

- [x] 写 `0.4` 不得扩大为 `1` 以及规则接口失败不得回退默认规则的失败测试。
- [x] 将数量改为向下取整；低于最小下单量时拒绝，不再自动放大。
- [x] 生产规则服务失败时抛出明确异常，运行适配器测试。

### Task 5: 硬风控始终进入自动交易链路

**Files:**
- Modify: `src/main/java/com/example/quant/order/OrderConfirmService.java`
- Modify: `frontend/src/pages/SystemControlPage.tsx`
- Test: `src/test/java/com/example/quant/order/OrderConfirmServiceTest.java`

**Interfaces:**
- Consumes: `RiskService.check`、实时订单簿和账户快照
- Produces: 放宽候选门槛也不能绕过紧急停止、杠杆、保证金、止损、流动性和动态杠杆上限

- [x] 写放宽模式仍被硬风控拒绝、自动交易不得抬高杠杆的失败测试。
- [x] 删除规则风控旁路和最低杠杆放大行为，保留候选评分层面的放宽。
- [x] 更新危险操作文案并运行订单确认测试。

### Task 6: 真实运行状态与 Industrial 交易终端 UI

**Files:**
- Modify: `src/main/java/com/example/quant/crypto/OkxWebSocketService.java`
- Modify: `src/main/java/com/example/quant/controller/SystemControlController.java`
- Modify: `frontend/src/api/quantApi.ts`
- Modify: `frontend/src/theme.ts`
- Modify: `frontend/src/layout.tsx`
- Modify: `frontend/src/components/TradingStatusStrip.tsx`
- Modify: `frontend/src/components/PageShell.tsx`
- Modify: `frontend/src/pages/Dashboard.tsx`

**Interfaces:**
- Consumes: 账户 REST 模式、WebSocket 实际实现状态、系统控制状态
- Produces: LIVE/DEMO、REST、Public WS、Private WS、策略引擎、交易引擎的非伪造状态显示

- [x] 为运行状态响应写控制器测试，确认未接入 WebSocket 返回 `NOT_IMPLEMENTED` 而非 Connected。
- [x] 添加运行状态接口并接入全局状态带。
- [x] 统一 Industrial token、密集表格、数字层级、危险色和桌面布局，不添加假数据。
- [x] 运行 `npm run build`，启动前后端并在 1440、1280、1024 宽度检查主页面、控制台和网络请求。

### Task 7: 全量验证与二次审计

**Files:**
- Modify: `docs/CODEBASE_DOC.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: 本计划全部交付物
- Produces: 可复查的中文架构、风险边界、配置和验证记录

- [x] 运行 `mvn test`、`npm run build`、`git diff --check` 和敏感信息扫描。
- [x] 检查未使用代码、类型错误、虚假状态、Demo/Live 标识、订单重试和前端资源清理。
- [x] 记录仍未实现的真实 WebSocket 私有流、fills/bills 实现盈亏和完整回测引擎，不用占位实现掩盖缺失。
