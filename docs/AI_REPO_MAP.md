# OKX Quant Assistant 仓库认知图

> 扫描日期：2026-09-06（Asia/Shanghai）  
> 扫描范围：仓库源码、构建文件、配置、迁移脚本、测试、前端源码、部署脚本。  
> 目的：给后续高级工程模型使用的源码事实索引。本文只记录能由源码/配置确认的行为；“风险”表示应优先复核的高影响区域，不等于已经证明存在生产缺陷。

## 0. 读取规则与仓库边界

- Git 根目录是当前目录 `/home/yang/code/myapp/OKX`；仓库外的父目录不是本项目 Git 根。
- 本次只新增本文件；未修改业务代码、配置、测试或数据库迁移。
- 扫描时工作区本来就有大量已修改/未跟踪文件，包括 `src/main/java`、`frontend`、`Dockerfile`、`pom.xml`、`README.md`、`docs/*`、`scripts/*` 和测试。不能把这些已有变化归因于本次文档生成。
- 本文使用 `路径 :: 类#方法` 作为证据索引。若一个结论是“未发现”，对应范围会明确写出。
- 源码验证边界：本次为静态扫描，没有启动交易服务，没有调用 OKX，没有发送订单，没有修改数据库状态，也没有把测试通过当成运行时/交易所 E2E 证明。

## 1. 一页结论

这是一个 Java/Spring Boot 单体后端 + React/Vite 管理终端，面向 OKX USDT 永续合约自动/手动交易。后端负责市场扫描、候选评分、计划与风控、订单确认、OKX REST/Private WebSocket、成交/持仓/账本对账、生命周期恢复和通知；前端通过 `/api/quant/*` 调用后端。

核心运行闭环：

```text
ApplicationReady
  ├─ ContractMarketScanTask.runOnStartup/runOnce
  │    └─ ContractAnalysisService -> OkxContractScanner -> ContractTradePlanBuilder
  │         -> AutoTradeService.evaluateAndExecute
  ├─ TradingStartupRecovery.recover
  │    └─ AutoTradeRecoveryTask.runOnce -> REST orders/positions/bills 对账
  └─ OkxWebSocketService.maintain
       └─ orders event -> OkxOrderEventService.apply

策略/人工计划
  -> PendingOrderService
  -> OrderConfirmService.confirm/confirmAuto
  -> ContractRiskService + DailyRiskService
  -> OkxTradeAdapter.placeOrder
  -> OkxRestOrderGateway -> OKX REST
  -> Private WS / REST sync / timeout recovery
  -> TradeFillLedgerService
  -> AutoTradeLifecycleService / PositionCloseService
  -> PositionReconciliationService + AccountBillLedgerService
  -> LedgerPnlService / AutoTradeProfitService
```

交易写入默认受 `TradingMode.DISABLED`、`TradingReadinessService`、紧急停止和账户绑定共同保护；配置和网关证据见 `src/main/resources/application.yml :: quant.trading/okx`、`src/main/java/com/example/quant/okxtrade/OkxRestOrderGateway.java :: requireTradingEnabled`、`src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java :: requireTradingEnabled`。

## 2. 技术栈、启动、构建、测试、部署

### 2.1 技术栈

| 层 | 技术与事实证据 |
|---|---|
| 后端语言/框架 | Java 17；Spring Boot 3.2.5；Spring MVC、Bean Validation、Spring Data JPA；证据：`pom.xml` 的 `java.version`、`spring-boot-starter-parent`、`spring-boot-starter-web/validation/data-jpa`。 |
| 数据库 | MySQL Connector/J 运行时；H2 测试/本地；Flyway + JPA；证据：`pom.xml`、`src/main/resources/application.yml :: datasource/jpa/flyway`、`src/main/resources/application-test.yml`、`src/main/resources/application-local.yml`。 |
| 外部交易 | Java `HttpClient` 调 OKX REST；Java WebSocket/`CompletableFuture` 调 OKX Private WebSocket；证据：`src/main/java/com/example/quant/crypto/OkxRestClient.java :: publicGet/privateGet/privatePost`、`src/main/java/com/example/quant/crypto/OkxWebSocketService.java :: maintain/connectPrivate`。 |
| AI/通知 | OpenAI-compatible JSON HTTP 客户端；Telegram HTTP sender + DB outbox；证据：`src/main/java/com/example/quant/ai/AiAnalysisService.java :: completeJson/sendWithRetry`、`src/main/java/com/example/quant/notification/TelegramSender.java :: send`。 |
| 前端 | React 18.3.1、React Admin 5.15.1、MUI 6.4、React Router 7.18、TypeScript 5.7、Vite 6；证据：`frontend/package.json`。 |
| 前端 API | `fetch` 封装、React Admin data provider、登录 token/session；证据：`frontend/src/api/http.ts`、`frontend/src/api/quantApi.ts`、`frontend/src/api/quantDataProvider.ts`、`frontend/src/api/authProvider.ts`。 |
| 部署 | Docker 多阶段构建：Node 20 构建前端、Maven Temurin 17 构建 JAR、Temurin 17 JRE 运行；Compose 运行 MySQL 8.4 + app；证据：`Dockerfile`、`docker-compose.yml`、`scripts/build-image.sh`、`scripts/test-deployment.sh`。 |
| 未发现的基础设施 | 扫描 `src`、`pom.xml`、Compose、Dockerfile 和配置，未发现 Redis/Redisson/RLock 或分布式锁实现；未发现 Kafka/RabbitMQ/JMS/RocketMQ/Pulsar 依赖或 listener。不要把 OKX WebSocket 当作 MQ。 |

### 2.2 入口与启动

| 场景 | 命令/入口 | 事实 |
|---|---|---|
| Spring Boot 主入口 | `src/main/java/com/example/quant/QuantAssistantApplication.java :: main` | `@SpringBootApplication`、`@EnableScheduling`、`@EnableConfigurationProperties`；注册股票、合约、OKX、Agent、AI、交易、认证、通知等配置。 |
| Maven 本地 | `mvn spring-boot:run` | `README.md` 的 Linux/macOS 启动说明；默认后端端口来自 `SERVER_PORT`/Spring 配置。 |
| 打包后运行 | `./start.sh` | 执行 `mvn -DskipTests package` 后运行 `target/quant-assistant-0.0.1-SNAPSHOT.jar`；`start.sh :: shell command`。 |
| Windows 辅助 | `start-dev.cmd`、`start-dev.ps1` | 仓库脚本负责本地开发启动；具体 Maven/npm 命令见脚本。 |
| 前端开发 | `cd frontend && npm install && npm run dev` | `frontend/package.json :: scripts.dev` 使用 `vite --host 0.0.0.0`；`frontend/vite.config.ts` 端口 5173，`/api` 代理到 `VITE_BACKEND_URL`，默认 `127.0.0.1:8080`。 |
| Docker 启动 | `docker compose up -d` | `docker-compose.yml` 要求 `OKX_APP_IMAGE`、MySQL 密码、`TRADING_MODE` 等环境变量；MySQL 健康后启动 app。 |
| 后端容器入口 | `java -jar /app/app.jar --spring.web.resources.static-locations=file:/app/static/` | `Dockerfile :: ENTRYPOINT`；前端静态产物复制到 `/app/static`。 |
| 启动恢复 | `ApplicationReadyEvent` | `src/main/java/com/example/quant/task/TradingStartupRecovery.java :: recover`、`src/main/java/com/example/quant/task/ContractMarketScanTask.java :: runOnStartup`、`src/main/java/com/example/quant/system/FxRateService.java :: onApplicationReady`。启动恢复先设为 RECOVERING，未完成 REST/WS/仓位/账本安全条件不会放行交易写入。 |

### 2.3 构建与测试

- 后端构建：`mvn -DskipTests clean package`；Docker 后端阶段明确执行该命令，证据：`Dockerfile :: backend-build`。`pom.xml` 中 Maven Surefire 存在，不能仅根据 Docker 的 `-DskipTests` 判断测试状态。
- 后端测试：`mvn test`；测试配置使用 H2 MySQL mode，证据：`src/test/resources/application-test.yml`。测试目录覆盖 account、agent、auth、crypto、okxtrade、order、position、risk、task、tradeplan 等。
- 前端构建：`cd frontend && npm run build`，脚本是 `tsc && vite build`；证据：`frontend/package.json :: scripts.build`。构建成功同时证明 TypeScript 编译和 Vite 打包，但不等于真实浏览器交易验收。
- 前端没有独立 `lint` script；`frontend/package.json` 的 scripts 只有开发、构建、预览等项目命令。不要报告“lint 已通过”，除非另行运行并记录工具。
- 静态校验：本次文档完成后只执行 `git diff --check -- docs/AI_REPO_MAP.md` 和 Git 状态检查；没有启动服务、没有运行真实交易链。

## 3. 仓库目录与模块职责

### 3.1 顶层结构

```text
src/main/java/com/example/quant/     Spring Boot 后端
src/main/resources/                  application*.yml、Flyway V1-V27
src/test/java/com/example/quant/     后端单元/集成测试
frontend/src/                        React Admin/MUI 管理终端
akshare-service/                     Python 股票数据服务（分析辅助，不是 OKX 订单通道）
scripts/                             镜像构建、部署测试等脚本
docs/                                既有审计、设计、计划文档；本文件为 AI 仓库地图
```

### 3.2 后端模块图

| 模块/路径 | 负责什么 | 核心入口、Service、Repository/DAO、消费者/任务 |
|---|---|---|
| `config` | 配置绑定、交易模式和 Agent/风控/重试参数 | `AgentProperties`、`TradingProperties`、`TradingMode`、`AuthProperties`、`OkxProperties`；配置来源 `src/main/resources/application.yml`。 |
| `auth` | 登录会话、用户、用户维度 OKX 凭据加密/绑定、当前 owner | `AuthUserService`、`AuthSessionService`、`AuthUserContext`、`OkxAccountBindingService`、`AesGcmOkxCredentialCodec`；`AuthUserJpaRepository`/`JpaAuthUserRepository`、`JpaOkxCredentialRepository`；HTTP 入口 `AuthController`、`AccountController`。 |
| `controller` | REST 边界和 SPA fallback，不承载交易核心决策 | `ContractAnalysisController`、`PendingOrderController`、`TradeConfirmController`、`OkxOrderController`、`AccountController`、`RiskController`、`SystemControlController`、`AutoTradeRecordController`、`NotificationController`、`DemoTradeVerificationController`；前端 fallback `SpaController.frontend`。 |
| `common`/`entity` | 共享类型、实体/Schema marker、通用工具 | `src/main/java/com/example/quant/common`、`entity`；具体持久化行为在各业务包 Repository/Entity。 |
| `crypto` | OKX 公共行情、合约扫描、信号、OI、REST、Private WS | `OkxRestClient`、`OkxContractScanner.scan`、`ContractAnalysisService.refreshCandidates/analyze`、`OkxWebSocketService.maintain`、`OkxOrderEventService.apply`；`OpenInterestSnapshotRepository`。 |
| `market` | 公共市场环境/市场数据抽象 | `MarketEnvironmentService`、`MarketDataService` 等；被 `OkxContractScanner`、风险/计划构建调用。 |
| `news` | 新闻刷新、新闻风险分析 | `ContractNewsAnalysisService`、`NewsRefreshTask.runOnce`；合约 Gate 读取新闻阻断结果。 |
| `score` | 股票/合约评分纯计算 | `ContractScoreCalculator.calculate`、`StockScoreCalculator`、输入 record；由 scanner/analysis 使用。 |
| `stock` | 股票候选/分析 DTO 与服务；当前不是核心 OKX 订单链 | `StockAnalysisService`、`StockMarketScanTask.runOnce`；股票 HTTP 入口 `StockAnalysisController`。 |
| `agent/market` | 订单簿流动性、滑点、市场辅助指标 | `OrderBookLiquidityService`；被 scanner、计划和最终确认调用。 |
| `agent/scan`/`agent/score`/`agent/audit` | Agent 扫描记录、候选分数和审计 | `AgentScanRunRepository`、`AgentContractCandidateScoreRepository`、`AgentAuditLogRepository`、`ContractCandidateScoreRecorder.recordLiveScan`。 |
| `agent/selection`/`ai` | AI 主选/等待入场、AI JSON 审查和审计 | `AiContractSelectionService`、`AiAnalysisService.completeJson`；selection 结果供 `AutoTradeService`、`ContractTradePlanBuilder` 使用；AI 不是最终风控裁决。 |
| `agent/gate` | 自动交易候选硬门禁 | `ContractTradeGate.planDenyReasons`、`allowCandidate` 等；检查信号分、新闻、spread、risk level 等。 |
| `agent/plan`/`tradeplan` | 可执行交易计划、AI/规则 fallback、持久化计划 | `ContractTradePlanBuilder.buildPlan/buildSelectedPlan/buildRuleFallbackPlan`、`TradePlanService.create*`、`TradePlanRecordService`；`TradePlanRepository`、`TradeTakeProfitPlanRepository`。 |
| `agent/budget` | Agent 账户/单笔预算分配、预留、使用、释放 | `AutoTradeBudgetService.allocate/reserveBudget/markUsed/release`；`BudgetReservationRepository`；是本地预算状态，不是交易所资金冻结。 |
| `agent/entry` | AI 等待入场意图和条件触发 | `WaitingEntryIntentService.createOrGet/evaluate/complete`；`WaitingEntryIntentRepository`；由 `AutoTradeService` 周期评估。 |
| `agent/execution` | 自动交易编排、自动交易记录、订单持久化、收益展示 | `AutoTradeService.evaluateAndExecute`、`TradeOrderRecordService`、`AutoTradeRecordService`、`AutoTradeProfitService`、`TradeOrderEntity`；`TradeOrderRepository`、`AutoTradeRecordRepository`。 |
| `agent/lifecycle` | 入场成交后保护单、超时撤入场、持仓生命周期、止盈/止损/最大持仓时间 | `AutoTradeLifecycleService.runOnce`、`submitProtection`、`cancelEntryOrder`；事实对象 `PositionLifecycleFact`、`ClosePositionFact`。 |
| `order` | 待确认订单聚合、状态变迁、手动/自动确认 | `PendingOrder`、`PendingOrderService.createPendingOrder/createAutoPendingOrder`、`OrderConfirmService.confirm/confirmAuto`、`OrderStatus`；`PendingOrderRepository`。 |
| `risk` | 合约订单硬风控、每日损失、每日入场 claim | `ContractRiskService.check`、`DailyRiskService.claimEntry/recordReconciledOutcome`；`DailyRiskStateRepository` 有悲观锁，`DailyRiskEntryClaimRepository` 防重复 claim。 |
| `position`/`leverage` | 仓位数量和杠杆计算 | `PositionSizingService.calculate`、`LeverageDecisionService.decide`；计划阶段生成数量，OKX instrument rules 阶段再转合约张数。 |
| `account` | OKX 账户余额/持仓、平仓意图与恢复、仓位归属对账 | `AccountSnapshotService.summary/verifyOkx`、`PositionSnapshotService.positions`、`PositionCloseService.closePosition`、`PositionReconciliationService.reconcile`、`ClosePositionRecoveryService.runOnce`；`ClosePositionRecordRepository`、`ExchangePositionRepository`、`JpaOkxCredentialRepository`。 |
| `okxtrade` | OKX 交易写网关、合约规则、当前订单同步、WS 订单映射 | `OkxTradeAdapter.placeOrder/closePosition/recoverUnknownSubmitStatus`、`OkxRestOrderGateway`、`OkxInstrumentRulesService.rules`、`OkxCurrentOrderSyncService.syncOnce`、`OkxOrderEventService.apply`；`TradeOrderRepository` 为本地订单存储。 |
| `ledger` | fill、账户 bills/funding、同步状态、P&L 汇总 | `TradeFillLedgerService.record`、`AccountBillLedgerService.syncRecent`、`LedgerPnlService.summary`；`TradeFillRepository`、`AccountBillRepository`、`AccountLedgerSyncStateRepository`。 |
| `strategydecision` | 按 owner/strategy/instrument/timeframe/candle 的持久化决策领取和恢复 | `StrategyDecisionService.claim/recoverStaleProcessing/complete`；`StrategyDecisionRepository`；V17 唯一键是跨重启幂等主线。 |
| `system` | 系统开关、FX、运行 readiness、REST 首次恢复 | `SystemControlService`、`TradingReadinessService`、`OkxRuntimeReconciliationService`、`FxRateService`；`SystemControlRepository`。 |
| `task` | 启动事件和定时编排 | `ContractMarketScanTask`、`AutoTradeRecoveryTask`、`TradingStartupRecovery`、`StockMarketScanTask`、`NewsRefreshTask`、`AccountSnapshotTask`。 |
| `notification` | 交易事实事件、Telegram outbox、重试/租约派发 | `TradeEventService`、`NotificationOutboxService`、`TelegramNotificationDispatcher.dispatch`、`TelegramSender.send`；`TradeEventRepository`、`NotificationOutboxRepository`。 |
| `report` | 报告/展示型服务，不是下单权威 | `src/main/java/com/example/quant/report`；相关数据来自 agent/account/ledger 服务。 |
| `demo` | Demo 交易验证入口/结果 | `DemoTradeVerificationController`、`src/main/java/com/example/quant/demo`；仍受交易模式与 readiness 网关保护。 |

### 3.3 Frontend 模块

| 路径 | 责任 | 证据 |
|---|---|---|
| `frontend/src/App.tsx`/`layout.tsx` | React Admin、路由、资源、布局 | `App.tsx :: App` 注册 resources 和自定义 routes；`layout.tsx :: AppLayout`。 |
| `frontend/src/api` | HTTP、认证、React Admin provider、量化 API | `http.ts :: apiFetch`、`authProvider.ts`、`quantDataProvider.ts`、`quantApi.ts`。 |
| `frontend/src/components` | 交易状态条、运行态、风险控制、待确认/平仓弹窗、持仓表 | `RuntimeStatusBar` 读取 `/api/quant/system/runtime-status`；`RiskCommandPanel`、`PendingOrderReviewDialog`、`ClosePositionDialog`。 |
| `frontend/src/pages` | Dashboard、候选、待确认订单、当前 OKX 委托、仓位、风险/账号绑定、自动交易生命周期/记录、系统控制 | 页面调用 `quantApi`；前端展示不能替代后端最终风控和 OKX 终态。 |

### 3.4 Python 辅助服务

`akshare-service/server.py` 提供股票/行情辅助 HTTP；`akshare-service/requirements.txt` 是 Python 依赖。核心 OKX 永续交易链不依赖它：OKX 路径从 `OkxRestClient`、`OkxRestOrderGateway`、`OkxWebSocketService` 进入。

## 4. 核心入口、HTTP API、任务和消费者

### 4.1 HTTP 边界

| API | Controller#method | 调用下游 |
|---|---|---|
| `GET /api/quant/contract/candidates`、`POST /scan`、`POST /trade-plan` | `ContractAnalysisController#candidates/scan/tradePlan` | `ContractAnalysisService`、`ContractTradePlanBuilder`。 |
| `POST /api/quant/contract/pending-order` | `PendingOrderController#create` | `TradePlanService` -> `PendingOrderService.createPendingOrder`。 |
| `GET /api/quant/orders/pending`、`POST /cancel` | `PendingOrderController#pending/cancel` | 当前用户 scope 的 `PendingOrderService`；取消路径不会直接等价于交易所成交终态。 |
| `POST /api/quant/orders/confirm` | `TradeConfirmController#confirm` | `OrderConfirmService.confirm` -> `OkxTradeAdapter.placeOrder`。 |
| `GET /api/quant/okx/orders/current`、`/algo` | `OkxOrderController#current/algo` | `OkxCurrentOrderService`。 |
| `GET /api/quant/account/summary/positions` | `AccountController#summary/positions` | `AccountSnapshotService`、`PositionSnapshotService`。 |
| `POST /api/quant/account/positions/close` | `AccountController#closePosition` | `PositionCloseService.closePosition` -> `OkxTradeAdapter.closePosition`。 |
| `GET /api/quant/risk/status` | `RiskController#status` | 运行态/风险展示；实际订单硬风控在 `OrderConfirmService` 的 `ContractRiskService.check`。 |
| `GET /api/quant/system/runtime-status` | `SystemControlController#runtimeStatus` | `TradingReadinessService`、WS、未知订单、unmanaged position、账本同步状态。 |
| `POST /api/quant/system/emergency-stop/resume` | `SystemControlController#emergencyStop/resume` | `SystemControlService`；网关写操作再次检查交易模式/readiness。 |
| `GET /api/quant/auto-trade/records`、`/profit/summary`、`/lifecycle` | `AutoTradeRecordController#records/profit/lifecycle` | `AutoTradeRecordService`、`AutoTradeProfitService`、生命周期数据。 |
| `POST /api/quant/demo/verification/order` | `DemoTradeVerificationController#order` | Demo 验证服务/OKX adapter；不能绕过 `TradingMode`。 |

### 4.2 调度/事件/外部消息消费者

| 类型 | 类#方法 | 触发与职责 |
|---|---|---|
| 启动扫描 | `ContractMarketScanTask#runOnStartup -> runOnce` | `ApplicationReadyEvent`；候选扫描、决策 claim、自动交易评估、决策完成。 |
| 合约扫描周期 | `ContractMarketScanTask#runHourly -> runOnce` | `@Scheduled`，默认配置在 `quant.contract.scan-interval-seconds`；JVM `AtomicBoolean` 防同实例重入。 |
| 自动恢复周期 | `AutoTradeRecoveryTask#runScheduled -> runOnce` | `@Scheduled` 默认约 30 秒；同步当前订单、过期/释放、SUBMITTING/UNKNOWN 恢复、生命周期、平仓恢复。 |
| 启动恢复 | `TradingStartupRecovery#recover` | `ApplicationReadyEvent`；恢复后执行 runtime reconciliation，条件不满足时 BLOCKED/DEGRADED。 |
| 账号重新绑定恢复 | `TradingStartupRecovery#recoverAfterBinding` | `OkxAccountBoundEvent`；绑定新 owner 后复用恢复流程。 |
| Private WS maintain | `OkxWebSocketService#maintain` | `@Scheduled(fixedDelay=1000)`；登录、订阅 orders/positions/account、heartbeat、退避重连、重连后 REST 对账。 |
| OKX WS 订单消费者 | `OkxOrderEventService#apply` | 解析 Private WS `orders` 事件，按 owner + ordId/clOrdId 找本地订单，防终态回退，落 fill。不是 MQ consumer。 |
| FX refresh | `FxRateService#onApplicationReady/refresh` | 启动和 cron `0 5 0 * * *` 刷新外汇。 |
| Telegram dispatch | `TelegramNotificationDispatcher#dispatch` | `@Scheduled(fixedDelay=10000)`；抢租约、发送、成功/失败/退避。 |
| 当前未形成有效周期的任务 | `StockMarketScanTask#runOnce`、`NewsRefreshTask#runOnce`、`AccountSnapshotTask#runOnce` | 源码为 MVP/stub 级入口；未发现对应 `@Scheduled` 周期驱动。 |

## 5. 交易系统核心链路（源码调用关系）

下表按真正会触发 OKX 写入的路径描述；页面只创建计划/待确认订单，`OrderConfirmService` 和 `OkxRestOrderGateway` 是最终写入门禁。

| 步骤 | 源码路径、类、方法 | 调用关系与状态事实 |
|---|---|---|
| 1. 用户/策略触发 | `src/main/java/com/example/quant/task/ContractMarketScanTask.java :: runOnce`；`src/main/java/com/example/quant/controller/ContractAnalysisController.java :: tradePlan` | 策略入口：`ContractAnalysisService.refreshCandidates` -> `StrategyDecisionService.claim` -> `AutoTradeService.evaluateAndExecute`。人工入口：Controller -> `TradePlanService.createContractPlan` 或 `PendingOrderService`。claim 绑定 owner、strategy、instId、timeframe、收盘 candle。 |
| 2. 市场/策略候选 | `src/main/java/com/example/quant/crypto/OkxContractScanner.java :: scan/analyzeSnapshot`；`ContractAnalysisService :: refreshCandidates/analyze` | `OkxRestClient.publicGet` 拉 tickers、funding、OI、order book、K 线；过滤 SWAP、成交额、range、spread，使用 `BigDecimal` 计算并产生 `ContractCandidate`。缺数据/异常时 `analyzeSnapshotSafely` 丢弃候选，不将不完整数据直接变成可交易候选。 |
| 3. AI/规则选择 | `src/main/java/com/example/quant/agent/selection/AiContractSelectionService.java :: select`；`src/main/java/com/example/quant/agent/gate/ContractTradeGate.java :: planDenyReasons` | AI 仅用于候选主选/计划意见；Gate 对 score、signal、news、spread、risk 等硬门禁。AI 结果被审计，最终可执行性仍由计划和风控决定。 |
| 4. 计划创建 | `src/main/java/com/example/quant/tradeplan/ContractTradePlanBuilder.java :: buildPlan/buildSelectedPlan/buildRuleFallbackPlan`；`TradePlanService :: create*` | 检查模式、entry mode、账户 equity/available、杠杆、仓位计算；生成 entry/stop/take-profit、margin、max loss、TTL；再调用 `RiskService.check`，失败降为 WATCH/NO_ENTRY 并记录 `TradePlanRecordService`。 |
| 5. 参数数量/杠杆计算 | `src/main/java/com/example/quant/leverage/LeverageDecisionService.java :: decide`；`src/main/java/com/example/quant/position/PositionSizingService.java :: calculate` | 依据波动、资金费、止损距离、账户权益、available、最大单笔 margin/loss 计算杠杆和 base quantity；数量在此处按 8 位 HALF_UP，尚未是 OKX 合约张数。 |
| 6. 参数校验与待确认 | `src/main/java/com/example/quant/order/PendingOrderService.java :: createPendingOrder/createAutoPendingOrder/buildPendingOrder`；`PendingOrder :: applyMarginAmount` | 只接受 OKX_SWAP 且不是 WATCH/NO_ENTRY；设置 side/posSide/tdMode、计划价格/止损/止盈、margin；`applyMarginAmount` 计算 margin scale4 HALF_UP、size scale8 DOWN、maxLoss scale4 HALF_UP；持久化 `PendingOrderEntity`，自动路径还记录 budget reservation/clientOrderId。 |
| 7. 预算预留 | `src/main/java/com/example/quant/agent/budget/AutoTradeBudgetService.java :: allocate/reserveBudget` | 本地预算以内存 map + `BudgetReservationRepository` 记录 RESERVED；金额 scale8；成功成交 `markUsed`，拒单/取消/超时 `release`。源码没有把该预留映射为 OKX 账户资金冻结。 |
| 8. 最终风控 | `src/main/java/com/example/quant/order/OrderConfirmService.java :: confirm/confirmAuto`；`src/main/java/com/example/quant/risk/ContractRiskService.java :: check`；`DailyRiskService :: claimEntry` | 检查订单可确认、过期、auto stop/emergency、最新 liquidity、账户、杠杆/margin、stop/RR/funding/volume/news/signal；可按配置降杠杆重试风控；`DailyRiskService.claimEntry` 以 owner+entryKey 做每日损失/入场限制，并在 DB 行锁保护下 claim。 |
| 9. 订单创建/本地提交意图 | `OrderConfirmService :: confirm/confirmAuto`；`PendingOrder :: transition` | `PENDING_CONFIRM/BUDGET_RESERVED -> CONFIRMING -> CONFIRMED -> SUBMITTING`；本地 `TradeOrderEntity` 记录由 `TradeOrderRecordService` 维护；提交前必须拥有稳定 clOrdId 和当前用户凭据。 |
| 10. 订单发送 | `src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java :: placeOrder` -> `OkxRestOrderGateway :: setLeverage/placeOrder` -> `OkxRestClient :: privatePost` | 加载 OKX instrument rules、position mode，设置杠杆；`orderPayload` 将 base quantity 转合约张数、lot floor、price tick；随入场订单附一个稳定 attached SL；网关再次检查写入模式/readiness。OKX ACK 只表示 submitted，不表示 filled。 |
| 11. 超时/未知提交 | `OkxTradeAdapter :: recoverTimedOutSubmit/recoverUnknownSubmitStatus`；`AutoTradeRecoveryTask :: recoverUnknownSubmit` | 提交超时先按 instId+clOrdId 查询一次；可找到则恢复 ordId/state；找不到保持 `UNKNOWN_SUBMIT_STATUS`，不盲目重提、不自动释放预算，等待恢复任务/人工处理。这是防重复订单的关键分支。 |
| 12. 成交回报 | `src/main/java/com/example/quant/okxtrade/OkxOrderEventService.java :: apply`；`OkxCurrentOrderSyncService :: syncOnce` | Private WS orders 事件优先快速更新；REST sync 作为启动/断线/终态补偿。按 `uTime`、filled size、防终态回退；终态订单拉 fills；WS 事件按 tradeId 写 `TradeFillLedgerService.record`。部分 fill 且 fills 不完整时进入 `RECONCILING`，不是直接宣布完成。 |
| 13. 订单状态变化 | `src/main/java/com/example/quant/order/OrderStatus.java`；`PendingOrder :: transition/transitionFromAny`；`OkxCurrentOrderSyncService :: mapState` | 本地状态覆盖 `PENDING_CONFIRM`、`CONFIRMING`、`SUBMITTING`、`SUBMITTED`、`UNKNOWN_SUBMIT_STATUS`、`ENTRY_FILLED`、`PROTECTION_*`、`CLOSE_SUBMITTED`、`CLOSED`、`FAILED/REJECTED/CANCELLED` 等；OKX state 映射和时间/filled 保护分散在同步服务和 WS consumer。 |
| 14. 持仓变化/归属 | `src/main/java/com/example/quant/account/PositionSnapshotService.java :: positions`；`PositionReconciliationService :: reconcile` | 读取 OKX `/account/positions`，按 owner + instId + posSide 与本地可追溯订单/fill 计算 signed exposure；精确比较本地/交易所净仓，写 `exchange_position_state` MANAGED/UNMANAGED。外部无法证明归属的仓位不会自动接管。 |
| 15. 保护单、部分/完全成交 | `src/main/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleService.java :: runOnce/submitProtection` | 完全成交后用实际 fill/position size 建 SL/TP1/TP2/TP3，检查已存在的 attached SL/algo client ID 防重复；部分成交只保护实际成交量，余量由撤单/终态对账处理；保护失败进入 `PROTECTION_FAILED` 并要求恢复/人工关注。 |
| 16. 入场撤单/超时 | `AutoTradeLifecycleService :: cancelEntryOrder`；`AutoTradeRecoveryTask :: expirePendingOrders/releaseReservedStatuses` | 对未成交/超时入场发送 cancel，再 query 确认；无 fill 变 `ENTRY_TIMEOUT_CANCELLED` 并释放预算；有部分 fill 不能按无成交处理，进入实际仓位/保护流程。 |
| 17. 平仓/撤仓 | `src/main/java/com/example/quant/account/PositionCloseService.java :: closePosition`；`OkxTradeAdapter :: closePosition` | 先读当前持仓，拒绝已有未决 close；创建稳定的 close intent/`CLOSE_SUBMITTING`，调用 OKX close-position，成功 `CLOSE_SUBMITTED`，异常 `CLOSE_UNKNOWN` 类状态；`ClosePositionRecoveryService` 通过订单详情/fills/position disappearance 做终态恢复。 |
| 18. 完全关闭与释放 | `ClosePositionRecoveryService :: runOnce/reconcileCloseExecution` | 只有平仓事实可对账时才标 CLOSED；随后取消残余保护/入场 algo，关闭本地订单、释放预算，写 review、daily risk outcome、trade event。查询失败保留未知/错误，不把失败当成功。 |
| 19. 手续费/资金费 | `src/main/java/com/example/quant/ledger/TradeFillLedgerService.java :: record`；`AccountBillLedgerService :: syncRecent` | fill 记录交易费；bills archive type/subtype 映射 TRADE/FUNDING，唯一键 owner+billId；同步状态不是全历史，只是 recent window。资金费来自账单，不从 `fundingRate` 估算已实现资金费。 |
| 20. 盈亏/持久化 | `src/main/java/com/example/quant/ledger/LedgerPnlService.java :: summary`；`AutoTradeProfitService :: summary` | gross realized PnL = traceable fills 的 fillPnl 汇总；net = gross + trading fees + funding bills；账本未达到 `RECENT_WINDOW_SYNCED` 时保持数据质量/未知，不应当作零收益。持久化落点为 `trade_order`、`trade_fill`、`account_bill`、`account_ledger_sync_state`、`auto_trade_record`、`trade_review`、`trade_event`。 |

## 6. 状态机与异常分支

### 6.1 入场订单

```text
CREATED/PENDING_CONFIRM
  -> BUDGET_RESERVED                  自动预算预留
  -> CONFIRMING                       最终确认开始
  -> CONFIRMED -> SUBMITTING          已通过风控，准备写 OKX
  -> SUBMITTED                        OKX 已 ACK，未必成交
       ├─> ENTRY_FILLED               完全成交
       ├─> PROTECTION_PENDING/SUBMITTED/ACTIVE
       ├─> ENTRY_TIMEOUT_CANCELLED    超时撤单且无成交
       ├─> UNKNOWN_SUBMIT_STATUS      网络/提交结果不可知
       └─> RECONCILING                fills/终态信息不完整

部分成交 -> 实际成交量保护 + 余单撤销/对账；
完全成交 -> 实际 fill/position 建保护；
保护失败 -> PROTECTION_FAILED -> 恢复任务/人工关注；
平仓 -> CLOSE_SUBMITTED -> CLOSED 或 EMERGENCY_ATTENTION_REQUIRED。
```

证据：`src/main/java/com/example/quant/order/OrderStatus.java`；状态写入：`PendingOrder#transition`、`PendingOrder#transitionFromAny`、`OkxOrderEventService#apply`、`OkxCurrentOrderSyncService#syncOnce`、`AutoTradeLifecycleService#runOnce`、`ClosePositionRecoveryService#runOnce`。

### 6.2 等待入场意图

`WaitingEntryIntentService.createOrGet` 以 `decisionClientOrderId` 和 active key 幂等；`evaluate` 在 WAITING/TRIGGERED/VALIDATING 中检查过期、市场/新闻/方向/价格失效，在下一根收盘 K 线且容差满足时进入 TRIGGERED；`complete` 进入 SUBMITTED/RECONCILING/EXPIRED/INVALIDATED/CANCELED/FAILED_SAFE。实体和唯一约束：`src/main/java/com/example/quant/agent/entry/WaitingEntryIntentEntity.java`、`WaitingEntryIntentRepository.java`、`src/main/resources/db/migration/V27__waiting_entry_intent.sql`。

## 7. 精度、数量、金额和合约规则

| 区域 | 源码事实 |
|---|---|
| Java 类型 | 交易价格、数量、费用、P&L、权益等核心字段使用 `BigDecimal`；证据：`PendingOrder`、`PositionSizingRequest/Result`、`TradeFillEntity`、`AccountBillEntity`、`TradeOrderEntity`。 |
| Scanner 计算 | `OkxContractScanner#scan` 对 24h change、turnover、range/spread 使用 BigDecimal；部分中间结果 scale4/HALF_UP。 |
| 计划仓位 | `PositionSizingService#calculate`：stop/reward/risk-reward scale8/HALF_UP；size scale8/HALF_UP；最小 base quantity 检查 `0.0001`。 |
| 待确认订单 | `PendingOrder#applyMarginAmount`：margin scale4/HALF_UP；`amount * leverage / price` size scale8/DOWN；maxLoss scale4/HALF_UP。 |
| 预算 | `AutoTradeBudgetService#allocate`：金额 scale8/HALF_UP，最终 margin scale8/DOWN，利用率 scale8/HALF_UP。 |
| OKX 合约张数 | `OkxTradeAdapter#orderPayload` 调 `OkxInstrumentRules`；`OkxInstrumentRules#contractSizeFromBaseQuantity` scale16/DOWN，`normalizeEntrySize/floorToLot` 按 lotSz 向下，`normalizePrice` 按 tickSz HALF_UP。 |
| 风控 | `ContractRiskService#check`：stopDistance scale8/HALF_UP，maxLoss scale8/HALF_UP；比较 max margin、RR、funding、volume、最大止损距离和动态杠杆 cap。 |
| 持久化精度 | 交易核心迁移多数为 `DECIMAL(30,12)`；老的行情表在 `V1__init_quant_schema.sql` 有 `DECIMAL(32,10)`/`DECIMAL(16,8)` 等；订单、fill、账单字段分别见 V5、V18、V19、V21。 |
| 关键审计点 | 计划/风控按 base quantity 计算，真正发送前在 `OkxTradeAdapter#orderPayload` 转 contract size；任何修改不能把这两种单位混用。手续费/资金费使用交易所返回值，不能用费率估算替代账本。 |

## 8. 并发、事务、幂等、重试与补偿

### 8.1 JVM 内并发控制

| 机制 | 位置与方法 | 边界 |
|---|---|---|
| 首次扫描锁 | `ContractAnalysisService` 的 `ReentrantLock refreshLock`、`refreshCandidates` | 防同一 JVM 首次扫描重复；不是跨实例锁。 |
| 扫描重入 | `ContractMarketScanTask` 的 `AtomicBoolean scanRunning`、`runOnce` | 防同一 JVM 定时任务重入。 |
| 自动交易重入 | `AutoTradeService` 的 `AtomicBoolean running`、`evaluateAndExecute` | 防同一 JVM 自动交易并发；symbol 去重另有 `ConcurrentHashMap<String,Instant> inFlightUntilByInstId`。 |
| Pending 内存索引 | `PendingOrderService` 的 `ConcurrentHashMap`、`restorePendingOrders` | 用于当前 JVM 快速查询；DB 负责恢复，但不是跨实例状态锁。 |
| 预算 | `AutoTradeBudgetService` 的同步方法/`synchronized` | 只保护单 JVM 内的预算 map；DB reservation 没有源码可见的分布式锁。 |
| 时间 offset | `OkxRestClient` 的 `synchronized` 时间同步 | 只保护时间偏移更新。 |
| instrument cache | `OkxInstrumentRulesService` 的 `ConcurrentHashMap` | 缓存 1h 左右规则；不负责订单幂等。 |
| readiness | `TradingReadinessService` 的 `synchronized update` + volatile state | 保护运行门禁状态；不是任务互斥。 |

### 8.2 数据库事务/行锁/唯一键

| 机制 | 证据与作用 |
|---|---|
| Spring 事务 | `@Transactional` 分布在 `DailyRiskService`、`OkxCurrentOrderSyncService#syncOnce`、`OkxOrderEventService#apply`、`PositionReconciliationService#reconcile`、`ClosePositionRecoveryService#runOnce`、`TradeFillLedgerService#record`、`AccountBillLedgerService#syncRecent`、`StrategyDecisionService#recoverStaleProcessing/complete` 等。外部 OKX HTTP 调用与本地事务边界需分别审查，不能假设远端回滚。 |
| 每日风险行锁 | `DailyRiskStateRepository` 的 `@Lock(PESSIMISTIC_WRITE)` 查询；`DailyRiskService#claimEntry/recordReconciledOutcome` 修改每日状态。 |
| 通知行锁 | `NotificationOutboxRepository` 的 due/lease 查询使用 `PESSIMISTIC_WRITE`；`NotificationOutboxService#claimDue/markResult`。 |
| Strategy 幂等 | `StrategyDecisionService#claim` 先读，再 `saveAndFlush`，捕获 `DataIntegrityViolationException` race；V17 对 owner/strategy/inst/timeframe/candle 和稳定 client order id 建唯一性。 |
| Fill 幂等 | `TradeFillLedgerService#record` 按 owner+instId+tradeId 查询/保存，重复唯一键捕获；V19 建唯一键。 |
| Bill 幂等 | `AccountBillLedgerService#syncRecent` 按 owner+billId；V21 建唯一键。 |
| Waiting intent 幂等 | `WaitingEntryIntentService#createOrGet` 捕获唯一约束 race；V27 对 decision client id/active key 建唯一性。 |
| 订单 clOrdId | V5/V16 的 `trade_order` 对 clOrdId/owner scope 有唯一约束；稳定决策 clOrdId 由 `StrategyDecisionService#clientOrderId` SHA-256 生成；OKX 提交未知时按它查询而不是生成新订单。 |

### 8.3 重试与补偿

- OKX REST client：`OkxRestClient` 的 `privateGet/privatePost` 没有通用自动重试循环；`OkxTradeAdapter#recoverTimedOutSubmit` 在提交超时后按 clOrdId 查询一次，避免盲目重复下单。
- 配置虽有 `AgentProperties.Retry` 的 `okxSubmitRetry`、`okxQueryRetry`、`retryBackoffMillis`，不能单凭配置名断言所有调用都使用了这些次数；真正订单提交实现以 `OkxTradeAdapter` 代码为准。
- 保护单撤销：`ClosePositionRecoveryService#retryFailedProtectionCancels` 按错误消息中的 `cancelRetry` 计数并有上限；`AgentProperties.Protection#protectionSubmitRetry` 是配置来源。
- Private WS：`OkxWebSocketService#scheduleReconnect` 使用指数退避 + jitter，stale heartbeat 后重连；重连后由 `OkxRuntimeReconciliationService#reconcile` REST 对账。
- AI：`AiAnalysisService#sendWithRetry` 依据 AI 配置执行请求重试；AI 失败在计划层由 `ContractTradePlanBuilder#aiReviewOrFallback` 退回确定性计划，但仍需最终风险检查。
- Telegram：`NotificationOutboxService#markResult` 对 429/5xx 等 retryable 结果按 `maxAttempts` 和退避时间更新 outbox；超过上限进入 failed。
- MQ 重复消费：未发现 MQ 依赖/listener；因此不存在 Kafka/Rabbit consumer 的重复消费逻辑。OKX WS 重复事件由 `OkxOrderEventService#apply` 的 ordId/clOrdId、uTime、终态回退保护和 fill tradeId 幂等承担。

## 9. 资金、持仓、费用、P&L 的真实边界

### 9.1 资金冻结/解冻

源码明确存在的是本地自动交易预算预留：`AutoTradeBudgetService#reserveBudget` 写 `BudgetReservation` RESERVED，`markUsed` 变 USED，`release` 变 RELEASED；持久化见 `BudgetReservationRepository` 和 V13。源码未发现本地账户资金冻结/解冻 API，也未发现 OKX `account` transfer/withdraw/frozen balance 写入；实际保证金和交易所可用余额由 `AccountSnapshotService#summary` 读取、OKX 下单由交易所管理。不要把本地 reservation 当作交易所资金锁。

### 9.2 持仓冻结/解冻

`PositionSnapshotService#positions` 只读 OKX positions；`PositionReconciliationService#reconcile` 写本地归属状态；`PositionCloseService#closePosition` 发送 close-position。源码未发现独立本地“持仓冻结/解冻”状态或 API；持仓安全依赖交易所 reduceOnly/close、保护单、平仓恢复和 unmanaged gate。

### 9.3 手续费与盈亏

- `TradeFillEntity` 保存 `fillPrice/fillSize/fee/feeCurrency/fillPnl/fillTime`，数据库字段见 `V19__trade_fill_ledger.sql`。
- `AccountBillLedgerService#syncRecent` 读取 `/api/v5/account/bills-archive`，type 8 subtype 173/174 记 FUNDING，type 2 记 TRADE；唯一键防重复。
- `LedgerPnlService#summary` 只在 `AccountLedgerSyncStateEntity.status == RECENT_WINDOW_SYNCED` 时提供可用汇总；owner、managed instId、traceable fill 是过滤条件；`net = gross + fees + funding`。
- `AutoTradeProfitService#summary` 优先 ledger；缺失时使用可识别的 close record 估算，并保持未知收益为未知，不把未知强行变成零；展示入口是 `AutoTradeRecordController#profitSummary`。
- 当前 bills 同步是 runtime reconciliation 调用链的一部分（`OkxRuntimeReconciliationService#reconcile`），未发现 `AccountBillLedgerService#syncRecent` 自身的 `@Scheduled` 注解；如果 runtime reconcile 没运行，账本状态会阻止 readiness/收益可信度。

## 10. 持久化表/Repository 索引

| 领域 | Entity/表 | Repository/关键操作 |
|---|---|---|
| 认证/凭据 | `AuthUserEntity`/`quant_auth_user`、`OkxCredentialEntity`/`quant_okx_credential` | `AuthUserJpaRepository`、`JpaAuthUserRepository`、`JpaOkxCredentialRepository`；`AuthUserService`、`JpaOkxCredentialStore`。 |
| 计划 | `TradePlanEntity`/`trade_plan`、`TradeTakeProfitPlanEntity` | `TradePlanRepository`、`TradeTakeProfitPlanRepository`；`TradePlanRecordService#record*`。 |
| 订单 | `PendingOrderEntity`/`pending_order`、`TradeOrderEntity`/`trade_order` | `PendingOrderRepository`、`TradeOrderRepository`；状态/ordId/clOrdId/filled size/average fill price。 |
| 自动交易 | `AutoTradeRecordEntity`/`auto_trade_record`、`TradeEventEntity`/`trade_event`、`TradeReviewEntity`/`trade_review` | `AutoTradeRecordRepository`、`TradeEventRepository`、`TradeReviewRepository`；记录决策、运行事实、复盘。 |
| 预算 | `BudgetReservationEntity`/`budget_reservation` | `BudgetReservationRepository`；RESERVED/USED/RELEASED。 |
| 策略决策 | `StrategyDecisionEntity`/`strategy_decision` | `StrategyDecisionRepository`；V17 唯一策略 K 线 claim。 |
| 等待入场 | `WaitingEntryIntentEntity`/`waiting_entry_intent` | `WaitingEntryIntentRepository`；V27 unique decision client/active key。 |
| 成交 | `TradeFillEntity`/`trade_fill` | `TradeFillRepository`；owner+inst+tradeId 唯一。 |
| 账户账单 | `AccountBillEntity`/`account_bill`、`AccountLedgerSyncStateEntity` | `AccountBillRepository`、`AccountLedgerSyncStateRepository`；bill unique 和 recent sync status。 |
| 仓位 | `ExchangePositionEntity`/`exchange_position_state` | `ExchangePositionRepository`；owner+inst+posSide，MANAGED/UNMANAGED/CLOSED。 |
| 风控 | `DailyRiskStateEntity`、`DailyRiskEntryClaimEntity` | `DailyRiskStateRepository`、`DailyRiskEntryClaimRepository`；每日 loss 和入场 claim。 |
| 平仓 | `ClosePositionRecordEntity`/`close_position_record` | `ClosePositionRecordRepository`；CLOSE_SUBMITTING/UNKNOWN/SUBMITTED/CLOSED 等。 |
| 通知 | `NotificationOutboxEntity`/`notification_outbox` | `NotificationOutboxRepository`；event+channel unique、lease、attempt。 |
| 行情辅助 | `AgentScanRunEntity`、`AgentContractCandidateScoreEntity`、`OpenInterestSnapshotEntity` | 对应 scan/score/OI repositories；不是订单权威。 |

Flyway 迁移完整范围是 `src/main/resources/db/migration/V1__init_quant_schema.sql` 至 `V27__waiting_entry_intent.sql`，当前存在 V1-V23、V25-V27；未看到 V24 文件。关键演进：V5 订单、V12 pending 状态、V13 budget、V14 生命周期、V15/V16 owner 隔离、V17 决策幂等、V18 对账字段、V19 fill、V20 仓位归属、V21 bills、V23 daily risk、V25 outbox、V26 AI 审计、V27 waiting intent。

## 11. 关键外部边界与权威关系

| 数据/动作 | 权威来源 | 本地代码 |
|---|---|---|
| OKX 订单写入 | OKX REST response + 后续 order state/fills | `OkxRestOrderGateway`、`OkxTradeAdapter`。ACK 不是成交。 |
| 订单最终状态 | REST detail/current orders + fills；WS 是快速事件通道 | `OkxCurrentOrderSyncService`、`OkxOrderEventService`。 |
| 成交数量/费/PnL | OKX fills | `TradeFillLedgerService`。 |
| funding | OKX account bills | `AccountBillLedgerService`，不使用 fundingRate 代替实际 funding。 |
| 账户权益/可用余额 | OKX account balance | `AccountSnapshotService`。 |
| 交易所净持仓 | OKX positions | `PositionSnapshotService`；归属/阻断在 `PositionReconciliationService`。 |
| 本地交易归属 | owner + strategy decision + order + fill 链 | `StrategyDecisionService`、`TradeOrderRecordService`、`TradeFillLedgerService`。 |
| 交易是否可写 | `TradingMode` + `TradingReadinessService` + emergency state | `OkxRestOrderGateway#requireTradingEnabled`、`OkxTradeAdapter#requireTradingEnabled`。 |
| AI 方向/价格 | 非权威建议 | `ContractTradePlanBuilder` 只接受在确定性几何/硬风控范围内的结果；最终由风险/adapter 决定可执行性。 |

## 12. 高风险区域分级

分级按“错误可能造成资金、持仓、订单或交易状态错误”的影响，不是漏洞确认。后续模型应先读这些路径，再改代码；每项都附源码证据。

### P0：资金/持仓/订单错误的高影响边界

| 区域 | 证据路径 | 为什么是 P0 关注区 |
|---|---|---|
| 最终订单写入与 ACK/超时 | `src/main/java/com/example/quant/order/OrderConfirmService.java :: confirm/confirmAuto` -> `src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java :: placeOrder` -> `OkxRestOrderGateway :: placeOrder` | 这是唯一进入交易所订单写接口的核心路径；网络超时可能“交易所已下单、本地未知”，任何新增重试、释放预算或状态变更都可能重复下单/重复释放。必须沿 clOrdId、ordId、UNKNOWN、REST detail 验证。 |
| 本地预算与跨实例竞态 | `src/main/java/com/example/quant/agent/budget/AutoTradeBudgetService.java :: reserveBudget/markUsed/release`；`AutoTradeService :: evaluateAndExecute` | 预算同步和自动交易锁主要是 JVM 内 `synchronized/AtomicBoolean/ConcurrentHashMap`；源码未发现 Redis/分布式锁。若未来多副本部署，多个实例可能同时 reserve/submit，必须依赖 DB 约束或单 owner 部署边界。 |
| 保护单与平仓 | `src/main/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleService.java :: submitProtection/cancelEntryOrder/runOnce`；`src/main/java/com/example/quant/account/PositionCloseService.java :: closePosition`；`ClosePositionRecoveryService :: reconcileCloseExecution` | 保护单缺失/重复、部分成交误判、close submit unknown 或错误标记 CLOSED 会直接影响仓位风险。任何修改必须同时验证 attached SL、algo order、actual fill size、position disappearance、fills 和残余保护取消。 |
| 仓位归属与 unmanaged gate | `src/main/java/com/example/quant/account/PositionReconciliationService.java :: reconcile`；`PositionSnapshotService :: positions` | 本地可追溯 fills 与交易所净仓精确对比；归属判断错误可能接管外部仓位或错误阻止系统。当前实现倾向保守进入 UNMANAGED，需验证 owner/posSide/contract-size 单位一致。 |
| fill/账单作为资金事实 | `src/main/java/com/example/quant/ledger/TradeFillLedgerService.java :: record`；`AccountBillLedgerService :: syncRecent`；`LedgerPnlService :: summary` | P&L、费用、资金费、每日风险都依赖这些数据；重复、漏记或把未同步窗口当完整历史会导致风控和展示错误。必须保留 UNKNOWN/RECONCILING，不可用零值掩盖。 |

### P1：交易状态错误/恢复错误

| 区域 | 证据路径 | 风险 |
|---|---|---|
| 状态机分散 | `PendingOrder.java :: transition/transitionFromAny`、`OkxOrderEventService.java :: apply`、`OkxCurrentOrderSyncService.java :: syncOnce`、`AutoTradeLifecycleService.java :: runOnce` | 状态转换逻辑分布在聚合、WS、REST sync、生命周期多个类；新增状态或直接 setter 容易造成终态回退、保护遗漏或预算未释放。 |
| Private WS 与 REST 竞态 | `OkxWebSocketService.java :: maintain`、`OkxOrderEventService :: apply`、`OkxCurrentOrderSyncService :: syncOnce` | WS 是异步事件，REST 是恢复权威；uTime/fill 数量/终态保护必须保持一致。重连、重复事件、事件乱序是重点。 |
| 平仓未知态恢复 | `PositionCloseService :: closePosition`、`ClosePositionRecoveryService :: runOnce` | close ACK 不等于成交；查询失败必须留 UNKNOWN/attention。修改 query fallback 可能把仓位仍在场误标 CLOSED。 |
| 每日风险 claim | `DailyRiskService :: claimEntry/recordReconciledOutcome`、`DailyRiskStateRepository` | 行锁只保护风险行；entryKey、owner、净 PnL 与账本状态必须保持一致，否则可能重复放行或错误阻断。 |
| 策略决策恢复 | `StrategyDecisionService :: claim/recoverStaleProcessing/complete`、V17 | 5 分钟 lease 过期变 RECONCILING，不自动盲目重放；改变此策略可能造成同一根 K 线重复委托。 |
| 合约单位转换 | `PositionSizingService :: calculate`、`OkxInstrumentRules :: contractSizeFromBaseQuantity/normalizeEntrySize`、`OkxTradeAdapter :: orderPayload` | base quantity、quote quantity、contract size、lot size 混用会导致实际下单量或最大亏损计算错误。 |
| 多副本运行 | `ContractMarketScanTask :: runOnce`、`AutoTradeService :: evaluateAndExecute`、`PendingOrderService` | 同实例锁不能替代部署级单 owner；当前没有分布式任务锁。 |

### P2：普通业务/可观测性问题

| 区域 | 证据路径 | 风险 |
|---|---|---|
| 最近窗口而非完整历史 | `AccountBillLedgerService :: syncRecent`、`LedgerPnlService :: summary` | UI/报表若把 recent window 当账户全历史，会产生范围误读。 |
| 任务 stub | `StockMarketScanTask#runOnce`、`NewsRefreshTask#runOnce`、`AccountSnapshotTask#runOnce` | 代码存在入口但不等于已有完整周期能力。 |
| 前端验证不足 | `frontend/package.json`、`frontend/src/pages/*` | `npm run build` 不覆盖真实浏览器交互、网络断线、权限和订单确认流程。 |
| 公共 WebSocket | `OkxWebSocketService :: publicStatus` | 明确为未实现/非核心；不要误认为公共行情 WS 已启用。 |
| 展示/估算收益 | `AutoTradeProfitService :: summary` | ledger 不完整时返回 data quality/估算；调用方不可把估算当交易所结算。 |

### P3：非核心/维护性

| 区域 | 证据路径 | 风险 |
|---|---|---|
| 未使用的旧执行抽象 | `src/main/java/com/example/quant/order/OrderExecutionService.java :: execute` | 代码是 stub/拒绝提示，不是当前 OKX 写入主链；后续模型不要误接到它。 |
| 公开 API/页面文案 | `frontend/src/pages/*`、`src/main/java/com/example/quant/controller/*` | 影响用户体验或文案一致性，但不直接决定交易所订单事实。 |
| 旧/并存 DTO 和历史文档 | `docs/CODEBASE_DOC.md`、`src/main/java/com/example/quant/*/dto` | 文档/DTO 名称不能替代当前方法调用；必须回到表中列出的当前实现。 |

## 13. 必须保持的安全/业务不变量

1. 写 OKX 前必须同时满足交易模式允许、readiness READY、账户 owner/凭据有效；证据：`OkxRestOrderGateway#requireTradingEnabled`、`OkxTradeAdapter#requireTradingEnabled`、`TradingReadinessService`。
2. OKX 提交返回只代表 submitted；filled 必须由 order state/fills/position 对账确认；证据：`OkxTradeAdapter#placeOrder`、`OkxCurrentOrderSyncService#syncOnce`、`OkxOrderEventService#apply`。
3. 提交超时不能直接重提；先用同一 clOrdId 查询并保持 UNKNOWN；证据：`OkxTradeAdapter#recoverTimedOutSubmit`、`AutoTradeRecoveryTask#recoverUnknownSubmit`。
4. 计划/风控 quantity 与最终 OKX contract size 必须分清；证据：`PositionSizingService#calculate`、`OkxInstrumentRules#contractSizeFromBaseQuantity`、`OkxTradeAdapter#orderPayload`。
5. 部分成交必须按实际成交量保护，不得按计划总量直接建保护或按零成交释放；证据：`AutoTradeLifecycleService#runOnce/submitProtection`、`OkxCurrentOrderSyncService#syncOnce`。
6. 保护单缺失、平仓未知、unmanaged position、fills 不完整都必须进入恢复/阻断路径，而不是默认为成功/零；证据：`ClosePositionRecoveryService`、`PositionReconciliationService`、`LedgerPnlService`。
7. Fill、bill、strategy decision、waiting intent、notification outbox 的唯一约束是跨重启幂等的一部分，不能只依靠 JVM map；证据：V17、V19、V21、V25、V27 和对应 Repository/Service。
8. 当前系统没有 Redis 分布式锁、MQ consumer 或本地资金/持仓冻结实现；若部署模型或资金语义改变，必须先扩展架构边界，而不是把现有 JVM reservation 当成分布式金融账本。

## 14. 后续高级模型的验证优先级

1. 先读取 `OkxTradeAdapter`、`OkxRestOrderGateway`、`OrderConfirmService`、`AutoTradeLifecycleService`、`ClosePositionRecoveryService`，建立“提交/确认/保护/关闭”的写入边界。
2. 再读取 `OkxOrderEventService`、`OkxCurrentOrderSyncService`、`TradeFillLedgerService`、`PositionReconciliationService`，确认异步事实如何回写本地。
3. 再读取迁移 V5、V12-V23、V25-V27 与 Repository 唯一键，确认跨重启幂等和所有权隔离。
4. 最后读取 `AutoTradeService`、`ContractTradePlanBuilder`、`ContractRiskService`、`DailyRiskService`，确认策略、数量、预算和风险上限没有绕过最终确认。
5. 运行验证应分层报告：后端单测、前端 TypeScript/Vite 构建、应用启动、Docker/数据库、OKX Demo REST/Private WS、真实 fills/bills/平仓；任何一层通过都不能替代下一层。

## 15. 变更记录

### 2026-09-06

- 新增 `docs/AI_REPO_MAP.md`。
- 内容来自仓库源码/配置/迁移/测试/前端静态扫描；没有修改业务代码，没有发送交易请求。
- 本文件生成前已知工作区不是干净状态；后续比较变更时只把本文件的新增视为本次文档动作。
