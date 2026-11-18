# OKX 量化交易系统代码库说明

### 2026-11-18 - 明确历史评估指标与回填顺序

- **变更**：固定 UTC 日期分层，优先回填四小时重点结果。限制历史请求收到 HTTP 429 后的重试并记录原因。评估报告使用最大逆向波动 MAE 并单列峰谷回撤。
- **影响文件**：候选结果仓储、回填服务、公开行情客户端、回填测试、历史评估手册。
- **验证**：用多会话时区测试、优先级和 429 回归测试验证。五条固定种子复算与 `19df36b` 副本一致。

### 2026-11-18 - 候选质量评估分层回填

- **变更**：近期优先，历史区分规则、分数或趋势信号入选的候选与按信号、日期抽取的对照样本。V44 保存快照止损、止盈价以及回填的方向最大回撤和首次触及结果。历史行情单实例请求间隔为 150 毫秒。
- **影响文件**：历史回填仓储和服务、候选快照录入和实体、行情服务、V44 迁移及回填测试。
- **影响**：仅用于候选质量观测。旧快照缺止损、止盈时保留已验证的收益、浮盈和回撤，首次触及不可判定。现有已完成结果保持原样。
- **验证**：Java 17 离线全量 1,066 个测试通过。隔离 H2 副本通过公开行情回填 260 条四小时样本，五条独立复算一致。源库同批记录保持 PENDING。

### 2026-11-18 - 测试环境关闭汇率外网刷新

- **变更**：在 `application-test.yml` 关闭汇率刷新。应用上下文测试检查该属性确实关闭。迁移测试继续使用非 Web 的 H2 测试上下文及配置中的后备汇率。
- **影响文件**：`src/test/resources/application-test.yml`、`QuantAssistantApplicationContextTest`。
- **验证**：Java 17 执行 `mvn -o -q clean test`，1,065 个测试全部通过。迁移和上下文测试使用 H2，唯一的 ECB 成功日志来自固定报价测试。前端 `npm run build --offline` 通过，并报告现有包体积警告。

### 2026-11-18 - 恢复候选历史结果回填

- **诊断**：本地有 240,371 条 `PENDING` 从未尝试。回填任务按最早成熟时间逐批查询，每批使用最近 300 根 15 分钟 K 线，超出行情窗口的旧记录持续重试，并挤占新记录名额。固定 5 分钟的 100 条批次在满扫描量时也可能低于新增速度。
- **变更**：以成熟时间 48 小时分开查询。近期继续取最近 300 根，历史按成熟时间取锚定的历史页。同一合约和锚点共用请求。两类队列优先处理未尝试的记录，历史默认最多 20 条/轮；回填固定延迟默认 2 分钟。完整性校验仍要求连续的已收盘窗口。
- **影响文件**：历史结果仓储及回填服务、`OkxMarketService`、`application.yml` 中的回填时间配置、回填和行情解析测试及历史评估手册。此路径仅写候选未来观测结果。
- **验证**：只读 SELECT 核验本地积压分布。Java 17 下 25 个定向单元测试全部通过，覆盖旧窗口回填、近期配额、24 小时窗口的到期边界和缺口重试。此前执行的 4 个迁移测试在非 Web 的 H2 测试上下文中通过。实际历史行情可达性和上线吞吐待验证。

### 2026-11-18 - 调整自动交易候选评分与成交额门槛

- **成交额评分**：24 小时 USDT 成交额按低于 200 万、200 万至不足 500 万、500 万至不足 1,000 万、1,000 万至不足 5,000 万、5,000 万至不足 1.5 亿、1.5 亿及以上分档，分别为 20、40、50、65、88、96 分。信号分析和扫描器共用此分档。扫描器仍按成交额、价差和深度 40%、30%、30% 计算流动性，移除按成交额对流动性原始分设置的 45 和 70 上限。
- **综合评分与准入**：流动性、趋势、量能、波动性、持仓量与资金费率、大盘、新闻的权重为 25、25、20、10、10、5、5。计划准入要求总分 65、趋势 15、量能 15、流动性 10，且新闻原始安全分至少 70。盘口和最终排名分继续根据实际数据检查。
- **运行影响**：`quant.agent.market.min-volume-24h-usdt` 默认 200 万，供 V1 扫描预筛、交易计划风控和下单确认共用。V2 选币权重、盘口价差和深度阈值沿用现有配置。
- **影响文件**：`ContractSignalAnalyzer`、`OkxContractScanner`、`ContractScoreBreakdown`、`AgentProperties`、`application.yml` 及相关回归测试。
- **验证**：Java 17 执行 `mvn -q test`，1062 个测试全部通过。成交额边界、评分权重、准入子分和确认风控参数通过模拟行情与交易网关验证。

### 2026-10-11 - 补齐单向仓位风控与手动平仓匹配

- **变更摘要**：补齐同向敞口和手动平仓路径遗漏的 `net` 方向匹配，阻断同一仓位通过方向别名重复平仓。
- **影响文件**：`CorrelationExposureGuard`、`AutoTradeService`、`PositionCloseService`、`ClosePositionRecoveryService` 及对应回归测试，共用已有 `PositionSideMatcher`。
- **运行影响**：自动入场入口先校验持仓数量/方向，无效快照进入 `position_snapshot_unavailable` 门禁；同向敞口按 net 有符号数量识别多空，零仓位不占用额度。手动平仓按实际仓位方向关联本地自动订单，记录保留订单逻辑方向，发送使用实际交易所模式，并拦截 long/short/net 别名的重复未决平仓。恢复清理按保护单平仓买卖方向解释 net 记录，仍限定用户、合约与 pendingOrderId，避免遗漏本订单保护单或清理另一订单的保护单。
- **验证**：已有安全修复定向 146 个测试通过。新增 14 个场景先复现旧实现的 10 个预期断言失败（无测试错误），第一轮相关 224 个测试通过；独立复查后补充平仓到保护清理的调用验证及 2 个自动入场无效数量场景，先复现 6 个预期断言失败，修复后相关 126 个测试通过，复查无剩余严重问题。最终 `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn -q test` 为 **1052 run / 0 fail / 0 error / 0 skip**（174 个测试类），`git diff --check` 通过。网关为模拟对象，不发送真实交易请求。
- **边界**：保留原有工作区修改，不部署、不重启、不更改交易开关或数据库数据。

### 2026-10-09 - 修复交易主链路安全缺陷

- **变更摘要**：统一单向仓位匹配、按订单归属隔离保护单，保留未知提交和已受理事实，严格确认撤单结果，优先识别全局阻断。
- **影响文件**：`PositionSideMatcher`、`AutoTradeLifecycleService`、`ClosePositionRecoveryService`、`OrderConfirmService`、`OkxTradeAdapter`、`PendingOrderService`、`AutoTradeRecoveryTask`、`OkxRestClient`、`OkxRestOrderGateway`、`OkxApiRejectedException`、`OkxCancelAcknowledgement`、`FailureReasonClassifier`，以及 `TradingSafetyRegressionTest`、`PositionSideMatcherTest`、`FailureReasonClassifierTest`、`ProfitProtectionLifecycleTest` 和相关模拟测试。
- **仓位匹配**：`net` 按有符号数量识别多空，双向仓位按 `posSide`；无效数量/方向抛出待核实异常，禁止当作仓位已消失。生命周期孤儿识别及平仓恢复共用匹配规则；最大持仓超时平仓使用实际交易所持仓方向，net 模式省略 posSide，已补多空完整生命周期回归。
- **保护单隔离**：本订单附加止损/确定生命周期 ID，或本用户、本订单、同合约同方向的本地保护单记录，才可作为有效保护或清理目标；新分段止盈 ID 同时包含订单身份与数量/分段身份，重启后可验证归属，旧 ID 按本地记录识别。
- **提交状态**：发送前异常及明确 OKX 拒绝可释放预算；OKX 50004 超时及 50001/50013/50026 服务/系统异常也保守视为未知结果；其他发送异常先查订单，查不到或查询失败保留 UNKNOWN 与预占预算；订单 ACK 不被本地计划/订单记录保存失败覆盖；重启 SUBMITTING 进入 UNKNOWN 对账；历史 REJECTED 但保留交易所订单号的记录保留 ACK，进入 `LEGACY_ACK_ACCOUNTING_RECONCILIATION_REQUIRED` 待对账状态，阻断新入场；生命周期、自动恢复和普通 UNKNOWN 处理均保留记账待核对状态，不会自动绕过预算/每日风险核对。此前已经释放的预算和风险占用不能仅凭订单号自动假定已恢复。
- **错误分类**：全局阻断错误优先于候选计划错误；删除泛化 ai/plan 子串匹配，保留明确候选预算不足（含附带说明）的递补行为及原有 continueOnPlanRiskFail 配置。
- **撤单结果**：请求包含 instId 与算法身份；要求顶层 code=0、非空 data 和所有逐项 sCode=0；无网关/缺身份/非零或缺失结果保持 CANCEL_FAILED 并继续恢复流程。
- **验证**：先新增风险复现回归测试，Java 17 定向 169 个测试已通过，全量 1027 个测试已通过；独立复查发现的 net 强平报文问题已用两个完整生命周期测试先复现；新增 50004 顶层/逐项/REST 封装响应及历史 ACK 记账阻断测试先复现风险。截至 2026-10-11，这些修复及后续衔接回归已随最终全量 **1052 run / 0 fail / 0 error / 0 skip** 验证通过。所有网关为模拟对象，不调用真实交易接口。
- **边界**：保留已有工作区修改；未部署、未重启、未提交 Git，未更改交易开关。

### 2026-09-24 - 修复自动交易三仓预算分配与边界舍入

- **变更摘要**：百分比自动交易预算改为三个目标仓位等分，总预算利用目标由 90% 调整为 100%；已通过准入的候选不再因评分系数把仓位从三等分继续缩小。
- **影响文件**：`src/main/java/com/example/quant/config/AgentProperties.java`、`src/main/java/com/example/quant/agent/budget/AutoTradeBudgetService.java`、`src/main/java/com/example/quant/agent/execution/AutoTradeService.java`、`src/main/java/com/example/quant/tradeplan/ContractTradePlanBuilder.java`、`src/main/java/com/example/quant/order/OrderConfirmService.java`、`src/main/java/com/example/quant/order/PendingOrder.java`、`src/main/resources/application.yml` 及对应测试。
- **影响**：默认三个预算槽位为 `33.333334% / 33.333333% / 33.333333%`，合计 100%；自动计划和最终风险确认使用同一单仓保证金上限。订单保证金按四位小数向下截断，避免预算值处于上限时因四舍五入产生 `Margin exceeds configured single-trade cap` 误拒绝。`single-trade-risk-percent=1`、止损、杠杆、实时盘口、市场状态动态仓位数及交易就绪门禁保持不变，风险倒推金额低于三等分时仍按更小金额执行。
- **兼容性**：新增语义明确的 `max-single-margin-percent`；旧 `max-single-loss-percent` Java 绑定保留为兼容别名，但不再作为默认配置名称。
- **验证**：回归测试先在旧实现上得到 8 个预期失败，覆盖 45/30/25 旧权重、5% 自动保证金上限和上限金额向上舍入；修改后相关 106 个测试通过。完整 `mvn -DskipTests=false test` 为 **977 run / 0 fail / 0 error / 0 skip**，`git diff --check` 通过。未部署、未重启、未执行真实交易。

### 2026-09-23 - 自动交易最小盘口深度调整为 20000 USDT

- **变更摘要**：将订单簿较小一侧的固定最小深度阈值从 `100000 USDT` 调整为 `20000 USDT`，增加中等深度合约的候选机会。
- **影响文件**：`src/main/resources/application.yml`、`src/main/java/com/example/quant/config/AgentProperties.java`、`src/test/java/com/example/quant/config/AgentPropertiesProviderTest.java`、`docs/contract-agent-loop-design.md`、`docs/CODEBASE_DOC.md`。
- **影响**：YAML 配置和 Java 缺省值统一为 `20000 USDT`；扫描流动性评分、`OrderBookLiquidityService.tradable` 和下单前实时流动性门禁统一使用新阈值；`max-spread-bps=8`、`max-depth-usage-rate=0.20` 与滑点保护保持不变。低于 `20000 USDT` 的盘口仍拒绝自动交易。
- **验证**：默认值回归测试先确认旧 Java 回退值仍为 `100000` 并按预期失败；修改后 `AgentPropertiesProviderTest`、`LiquidityFilterTest`、`OrderBookLiquidityServiceTest`、`OrderConfirmServiceTest` 定向测试通过；完整 `mvn -q test` 为 **976 run / 0 fail / 0 error / 0 skip**，`git diff --check` 通过。未部署、未重启、未执行真实交易。

### 2026-09-23 - 修复 OKX 历史 OI 数组解析与扫描质量日志

- **变更摘要**：历史 OI Provider 按 OKX 官方数组格式 `[ts, oi, oiCcy, oiUsd]` 解析，并使用 `oiCcy` 计算 1h/4h/24h 变化率；扫描完成日志改为读取实际存在的 `open_interest_history` 状态键。
- **影响文件**：`OkxOpenInterestHistoryProvider`、`OkxContractScanner`、`OkxOpenInterestHistoryProviderTest`、`OkxContractScannerV2Test`、`docs/CODEBASE_DOC.md`。
- **影响**：V2 候选可恢复 OI 变化与衍生品维度证据；缺失或坏行仍保持 null/UNAVAILABLE，不伪装成零；日志不再固定显示 `oiDataQuality=null`。
- **验证**：新增回归测试先确认修复前官方数组响应为 `UNAVAILABLE`、日志质量为 `null`；修复后 OI/扫描定向测试通过，完整 `mvn -q test` 为 **975 run / 0 fail / 0 error / 0 skip**，`git diff --check` 通过。未部署、未重启、未执行真实交易。

### 2026-09-22 - 自动交易复盘：开仓方向简化为「多/空」

- **变更摘要**：复盘列表「开仓方向」只显示「多」或「空」，不再显示「开多 / 多仓」等组合文案。
- **影响文件**：`frontend/src/formatters.ts`、`docs/CODEBASE_DOC.md`。
- **验证**：文案映射调整，未改交易逻辑。

### 2026-09-22 - 自动交易复盘：阶段列中文化

- **变更摘要**：复盘列表「阶段」列将 `SCAN`/`SUBMIT`/`ENTRY` 等 stage 码显示为中文。
- **影响文件**：`frontend/src/formatters.ts`（`formatStage`）、`frontend/src/pages/AutoTradeRecordList.tsx`、`docs/CODEBASE_DOC.md`。
- **验证**：`cd frontend && npx tsc --noEmit` 通过。未部署、未重启、未提交 Git。

### 2026-09-22 - 自动交易复盘：开仓方向回填与枚举中文化

- **变更摘要**：跳过/未成单复盘记录在无订单动作时，用 AI 方向或候选趋势回填 `action`/`posSide`；前端将信号、AI 决策来源、入场模式及 `auto_trade_disabled_by_config` 等 reason code 统一显示为中文。
- **后端**：`AutoTradeRecordService.resolveOpenDirection`：已有结果方向优先；否则 `BULLISH→OPEN_LONG/long`、`BEARISH→OPEN_SHORT/short`。
- **前端**：`formatOpenDirection` 对历史空方向行回退 `aiDirection`/`trendDirection`；`formatSignalType`/`formatDecisionSource`/`formatEntryMode`/`formatAutoTradeReason` 覆盖 `NO_TRADE`、`NO_ELIGIBLE_CANDIDATE`、`auto_trade_disabled_by_config` 等。
- **影响文件**：`AutoTradeRecordService`、`AutoTradeRecordServiceTest`、`frontend/src/formatters.ts`、`frontend/src/pages/AutoTradeRecordList.tsx`、`docs/CODEBASE_DOC.md`。
- **验证**：`mvn -o -q -Dtest=AutoTradeRecordServiceTest test` 通过；`cd frontend && npx tsc --noEmit` 通过。未部署、未重启、未提交 Git。

### 2026-09-22 - 成交额不可用拒绝入选 + 待成交订单跨轮占用敞口

- **变更摘要**：成交额无法换算时返回 null 并在扫描阶段剔除，不再当真实零成交额参与选币/评分/覆盖排序；自动交易轮初把本地活跃入场单与 OKX 非 reduce-only 当前单按方向并入敞口列表，覆盖跨轮「已提交未成交」场景。
- **成交额**：`quoteTurnoverUsdt` 不可算 → `null`；`buildTickerSnapshot` / V1 ticker 循环跳过该合约；V1 预筛对 null volume 显式过滤。
- **敞口**：`TradeOrderRecordService.activeEntryExposures()`；`OkxCurrentOrderSyncService.SyncResult.activeEntryExposures`；`AutoTradeService.mergePendingExposures` 在轮初合并，同轮提交仍追加 `committedExposurePosition`。
- **影响文件**：`OkxContractScanner`、`TradeOrderRecordService`、`OkxCurrentOrderSyncService`、`AutoTradeService` 及对应测试；`docs/CODEBASE_DOC.md`。
- **验证**：见本轮最终回复中的定向与全量 Maven 结果。未部署、未重启、未提交 Git。

### 2026-09-22 - P1：成交额单位、持仓失败门控、同轮敞口累计、指定标的拒绝

- **变更摘要**：修复四项会直接影响选币或自动开仓安全的 P1 缺陷。
- **成交额**：`OkxContractScanner.quoteTurnoverUsdt` 将 `volCcy24h`（基础币数量）× `last` 换算 USDT 名义成交额；`vol24h`（张数）回退必须乘 `ctVal`，缺面值时不得伪造成交额。
- **持仓查询**：`AutoTradeService` 区分「查询成功且空仓」与「查询失败」；失败时 `SKIPPED/position_snapshot_unavailable`，禁止新增订单。
- **同轮敞口**：提交成功后把承诺仓位加入本轮 `positions`/`openOrPendingInstIds`，敞口与仓位质量按累计状态检查，不再只靠轮初快照。
- **指定标的**：`ContractTradePlanBuilder.buildPlan(String)` 找不到请求 `instId` 时抛错；自动推荐走独立入口 `buildTopCandidatePlan` / `TradePlanService.createTopCandidateContractPlan`。
- **影响文件**：`OkxContractScanner`、`AutoTradeService`、`ContractTradePlanBuilder`、`TradePlanService` 及对应测试夹具/用例；`docs/CODEBASE_DOC.md`。
- **验证**：见本轮最终回复中的定向与全量 Maven 结果。未部署、未重启、未提交 Git。

### 2026-09-22 - 相对强弱：共同终点新鲜度门控与 windowEnd

- **变更摘要**：共同时间窗口增加相对扫描时间的滞后上限与三方最新收盘偏差上限；超出范围标为 UNAVAILABLE，不得用过旧窗口继续评分；snapshot 记录实际评分终点 `windowEnd`。
- **阈值**：共同终点相对 `asOf` 最大滞后 3 小时（覆盖未收盘小时与整点切换短暂缺棒）；任一序列最新收盘相对共同终点最大超前 1 小时。候选已有 ≥25 根 1H 时仍可复用，但复用路径同样受新鲜度约束。
- **影响文件**：`RelativeStrengthProvider`、`RelativeStrengthSnapshot`、`OkxRelativeStrengthProvider`、`RelativeStrengthWindowTest`、`RelativeStrengthProviderTest`。
- **验证**：见本轮最终回复中的定向与全量 Maven 结果。未部署、未重启、未提交 Git。

### 2026-09-22 - 选币评分：相对强弱时间窗、OI 名义价值与资金流有界映射

- **变更摘要**：修复相对强弱 1H K 线数量不足与错位窗口；为资金流引入明确的 `openInterestUsd` 名义价值字段；OI 请求失败不再默认成零；资金流成交量/OI/深度子项分别有界映射后再相加。
- **相对强弱**：`OkxRelativeStrengthProvider` 请求 limit 从 24 提升为 48；`RelativeStrengthProvider` 使用三方共同结束整点，并要求 `end` 与 `end-Nh` 精确存在，不得用更早价格冒充目标窗口；`ProviderResult.dataQuality` 与 snapshot 内部质量一致。
- **OI 单位**：`ContractCandidate.openInterest` 仅表示币数量（oiCcy）；新增 `openInterestUsd` 供资金流使用。解析优先 `oiUsd`，USDT-SWAP 在缺失时可 `oiCcy×lastPrice`；禁止把合约张数 `oi` 当作美元。失败/空响应/缺字段两侧均为 null。
- **资金流**：`capitalFlow` 依赖 `openInterestUsd`；各子项 `boundedLinear` 后相加再 clamp；巨额成交量不能单独推到满分。
- **影响文件**：`RelativeStrengthProvider`、`OkxRelativeStrengthProvider`、`OkxContractScanner.OpenInterestReading`、`ContractCandidate`、`V2SelectionScoreCalculator` 及对应测试。
- **验证**：见本轮最终回复中的定向与全量 Maven 结果。未部署、未重启、未提交 Git。

### 2026-09-21 - 交易机会导向选币补验收：等待持续评估与资格分组

- **变更摘要**：补验收上一轮选币 Minimal Fix。等待入场意图改为由扫描层固定深度分析名额和交付名额，每轮刷新行情后再评估触发/失效；交付排序拆成立即可交易 / 计划合格待触发 / 仅观察，并给活跃等待意图单独一档。轮换覆盖从「保证所有币被覆盖」收窄为「静态名单上的下标轮换」。
- **等待评估**：`WaitingEntryIntentService.evaluate()` 对交付列表中找不到的合约仍然 `continue`（不触发、不按行情失效，只有壁钟到期会终止）。扫描器通过 `ActiveEntryIntentInstIds` 读取未终结意图，在 `DeepAnalysisTargetAllocator` 和 `rankForDelivery` 内从既有 `deepAnalysisLimit` / `topCandidateLimit` 扣名额 pin 这些合约。掉出硬过滤名单的等待合约不会被强行拉回。
- **资格分组**：`rankForDelivery` 四档为 pin → 立即可交易（`aiHardDenyReasons` 空且 `autoDenyReasons` 空）→ 计划合格但动作未就绪（仅 `candidate_action_not_auto_trade_allowed*`）→ 仅观察。`AutoTradeService.autoTradeReady()` 用新闻硬拒绝 + `autoGate` 排递补顺序；`planDenyReasons` 在规则回退路径仍是警告，不当作已知必拒。
- **轮换覆盖边界**：`DeepAnalysisTargetAllocator` 保存的是尾部下标游标，不是每币最后分析时间。`ceil(tailSize / rotationQuota)` 只在名单与排序稳定、扫描完成并 commit、进程未重启时成立。测试锁定：重启归零、排序躲避窗口、尾部变长都会让原上界失效。
- **方向评分未改公式**：做空退出 ATH 维度后覆盖率上限 90%，`rankingScore` 仍受 coverage confidence 折扣；「不适用」与「获取失败」本轮仍走同一缺失路径。相对强弱维度中间值 `clamp` 到 0–100 后再加权。
- **影响文件**：`DeepAnalysisTargetAllocator`、`OkxContractScanner`、`WaitingEntryIntentService`、`AutoTradeService`、`ActiveEntryIntentInstIds` 及对应测试。
- **验证**：见本轮最终回复中的定向测试与全量 Maven 结果。未部署、未重启、未提交 Git。

### 2026-09-21 - 提高新闻数据缺失时的保守评分

- **变更摘要**：没有匹配到该币新闻、公告接口失败或空返回时，新闻风险分默认从 20 调整为 70。
- **运行语义**：仍保持 `UNKNOWN` 和 `WAIT`，不会把缺失数据当成已确认安全；可通过 `QUANT_AGENT_NEWS_RISK_MISSING_DATA_SCORE` 覆盖。
- **验证**：新增默认值回归测试，定向测试先按预期复现 20 分失败，再验证 70 分通过。

### 2026-09-21 - 接入 OKX 官方下架公告源

- **变更摘要**：将新闻接口从固定空实现切换为 OKX 官方 `announcements-delistings` 公告源，按币种匹配下架公告并转换为现有 `NewsItem`，供合约新闻风险分析使用。
- **风险语义**：匹配到官方下架公告时标记为负面、高重要性和 `DELISTING` 风险关键词；公告接口失败或无数据不伪装为低风险，由现有分析链路保持 UNKNOWN/等待。该适配器不决定杠杆、止盈止损、仓位或数量。
- **配置**：`QUANT_NEWS_PROVIDER=OKX` 默认启用；设置为 `NOOP` 才使用旧的空新闻源。Docker Compose 已透传该环境变量。
- **影响文件**：`OkxAnnouncementNewsSourceAdapter`、`NoopNewsSourceAdapter`、`NewsProperties`、`application.yml`、`docker-compose.yml`、`.env.example`、`README.md` 及对应测试。
- **验证**：OKX 公告适配器定向测试通过；`mvn -q test` 为 **840 run / 0 fail / 0 error / 0 skip**，`git diff --check` 通过。未重建或重启当前 Docker 容器。

### 2026-09-21 - 自动交易原因中文化

- **变更摘要**：自动交易记录和生命周期页面将 `no_candidate_passed_auto_gate`、持仓质量分数、候选动作、不可交易信号等内部英文 reason code 转换为中文说明，便于直接识别跳过或拦截原因。
- **显示语义**：后端继续保留英文原始 reason code、日志和历史数据；前端通过统一格式化函数展示中文，动态分数、市场状态、动作和信号值也会一并翻译。未知原因码保留原文，避免丢失审计信息。
- **影响文件**：`frontend/src/formatters.ts`、`frontend/src/pages/AutoTradeRecordList.tsx`、`frontend/src/pages/AutoTradeLifecycleList.tsx`。
- **验证**：`npm run build`（包含 TypeScript 检查和生产构建）通过，`git diff --check` 通过；未改变交易策略、风控阈值、订单接口或后端 reason code。

### 2026-09-21 - Phase 4A 固定 TP 生命周期基础

- **变更摘要**：在不启用 Runner、不改变 TP 比例和 ProfitProtection 参数的前提下，新增持久化 `exit_leg_ledger`，按稳定 `exitLegId` 保存 TP1/TP2/TP3 的 planned/filled/remaining/status 与当前 algo 身份。
- **TP 语义**：reconciliation 使用 canonical exit legs、TradeFill ledger、TradeOrder 订单事实和当前 active algo 保护单；已成交 tranche 不会按剩余仓位重新按比例挂回。相同 `tradeId` 重放只累计一次；未成交 entry 生命周期仍可调整 planned quantity，任一 TP 成交后整笔退出计划冻结。
- **保护状态**：`position_exit_state` 新增 `observed_stage`、`confirmed_protection_stage` 和 `stop_amend_state`。价格达到保护阶段只写 observed；amend 发送后必须通过 exchange read-back 才推进 confirmed。timeout、拒绝或 read-back mismatch 保留 `RECONCILIATION_REQUIRED`，不假设成功。
- **平仓恢复**：仓位消失时将剩余 TP ledger 标记 `OBSOLETE`，并尝试撤销剩余 TP/SL；仓位为零的路径不再创建新的保护单。canonical stop replacement 优先尝试 gateway 的 amend-algos；不可确认时保留 place-new-first，并要求保护 payload 为 reduceOnly，避免 cancel-first 的无止损窗口。
- **数据库**：新增 Flyway `V43__exit_leg_ledger_and_protection_stages.sql`，创建 `exit_leg_ledger` 及保护阶段字段；没有改变 Runner、风险预算、杠杆、TP 比例或 0.75R/1.5R/3R 参数。
- **影响文件**：`ExitLegLedgerEntity`、`ExitLegLedgerRepository`、`ExitLegLedgerService`、`AutoTradeLifecycleService`、`PositionExitStateEntity`、`PositionExitService`、V43 migration 及对应测试。
- **验证**：新增 ledger、partial fill、重复 fill、仓位归零、保护 read-back 与 response-lost 测试；`mvn -q test` 为 **838 run / 0 fail / 0 error / 0 skip**。未连接生产交易环境，未执行真实下单或容器部署。

### 2026-09-21 - 横盘小止盈状态仍执行最大持仓平仓

- **变更摘要**：修复订单进入 `SIDEWAYS_TIMEOUT_TP_ADJUSTED` 后提前 `continue`，导致达到最大持仓时间仍不执行强制平仓的问题。
- **运行语义**：最大持仓时间检查现在优先于横盘小止盈保护；未达到最大持仓时间时继续维护小盈利止盈，达到后提交 `MAX_HOLD_TIMEOUT_CLOSE`，仓位完全关闭后才释放扫描名额。
- **影响文件**：`AutoTradeLifecycleService`、`AutoTradeLifecycleServiceTest`。
- **验证**：新增回归测试先按预期复现 `maxHoldCloseSubmitted=0`，修复后生命周期定向测试通过；全量 Maven 为 **822 run / 0 fail / 0 error / 0 skip**；Docker 构建成功，`okx-app-1` 已按新镜像重建并为 `healthy`，`okx-mysql-1` 未重启且保持 `healthy`，启动日志确认 schema 42 已是最新。

### 2026-09-21 - 满仓暂停新开仓选币扫描

- **变更摘要**：当交易所实际持仓、当前订单或本地活动入场订单达到 `max-open-positions` 时，跳过会触发 CoinGecko/AI 的新开仓候选扫描；容量恢复后自动继续扫描。
- **影响文件**：`PositionCapacityScanGate`、`ContractMarketScanTask`、`PositionCapacityScanGateTest`、`ContractMarketScanTaskTest`。
- **运行语义**：容量判断复用 OKX 持仓快照、当前订单同步和本地活动入场记录；交易所状态不可用时返回 `POSITION_CAPACITY_UNKNOWN` 并放行扫描，不把未知状态当作满仓。30 秒恢复任务和持仓止盈止损生命周期不受影响。
- **日志语义**：跳过时记录 `POSITION_CAPACITY_FULL`、`openOrPending` 和 `maxOpenPositions`，平仓后下一轮容量检查恢复候选扫描。
- **验证**：`mvn -q -DskipTests=false -Dtest=PositionCapacityScanGateTest,ContractMarketScanTaskTest test` 通过；全量 Maven 为 **821 run / 0 fail / 0 error / 0 skip**，`git diff --check` 通过；`docker build -t okx-quant:local .` 成功，`okx-app-1` 已按新镜像重建并为 `healthy`，`okx-mysql-1` 保持 `healthy` 且未重启；启动日志显示 Flyway schema 42 已是最新。

### 2026-09-21 - TypeSafe Jev typed AI provider 接入

- **变更摘要**：新增可选的 TypeSafe Jev provider；配置 `AI_PROVIDER=TYPESAFE` 后，候选选择使用一次 System One 请求返回的 Choice/Score/Noul 结构化结果，默认 OpenAI-compatible/Kimi 链路保持不变。
- **影响文件**：`AiProperties`、`AiAnalysisService`、`AiContractSelectionService`、`AiAnalysisServiceTypeSafeTest`、`AiContractSelectionServiceTest`、`application.yml`、`docker-compose.yml`、`.env.example`、`README.md`。
- **请求与配置**：Jev 请求地址默认为 `https://api.typesafe.ai/v1/systemone`，模型默认为 `jev-latest`；密钥通过根目录 `.env` 的 `TYPESAFE_API_KEY` 注入，不写入源码或镜像。可配置 `TYPESAFE_BASE_URL`、`TYPESAFE_MODEL`。
- **选择语义**：Jev 同时回答 `decision`、`selectedInstId`、`direction`、`entryMode`、`quality` 和 `risk_ok`；代码取 Choice/Score 置信度最低值作为选择置信度，风险概率低于 0.65 或质量分低于 2 时强制 WAIT；结果仍必须通过原有候选池、方向、入场模式和硬风控校验。
- **故障边界**：Jev 请求失败、响应缺字段、候选越界或类型/范围不合法时进入既有规则保底，不直接产生交易订单；没有配置 `TYPESAFE_API_KEY` 时不会调用 Jev。
- **验证**：TypeSafe 定向测试、AI 选择定向测试、全量 Maven、前端 `tsc --noEmit`、前端生产构建、`git diff --check` 和 `docker compose config --quiet` 均通过；前端构建仅保留既有的大 chunk 警告。容器重建与重启结果以本轮运行态检查为准。

### 2026-09-19 - OI 落库、AI 可观测性与评分门槛修复

- **变更摘要**：修复 `snapshot_open_interest` 数值溢出；为 AI 选择与 Chat Completions 请求补充可验证的开始、响应、完成和失败日志；将纯评分交易门槛从 80/84/88 放宽为无持仓 65、1 个持仓 70、2 个持仓 75。
- **影响文件**：`V40__widen_open_interest_snapshot.sql`、`AgentContractCandidateScoreEntity`、`AiAnalysisService`、`AiContractSelectionService`、`AgentProperties`、`ContractSignalAnalyzer`、`ContractSignal`、`ContractCandidate`、`application.yml`、`docker-compose.yml`、`.env.example`、`README.md` 及对应测试。
- **OI 数据库影响**：`agent_contract_candidate_score.snapshot_open_interest` 从 `DECIMAL(18,8)` 扩大为 `DECIMAL(30,12)`；历史已存在的 V38/V39 不修改，部署时由 Flyway V40 增量升级。
- **AI 可观测性**：日志包含 `requestId`、endpoint、model、payload 字符数、HTTP 状态、响应字符数、耗时和失败原因；不记录 API key 或完整 prompt/response。候选池为空、AI 禁用、AI fallback 也分别记录原因。
- **评分门槛语义**：只放宽 score / finalRank 的软门槛；高风险市场、新闻风险、流动性、价差、信号类型、盈亏比、止损距离、账户和订单执行风控保持不变。高波动市场的最低质量门槛仍由 `PositionQualityPolicy` 提高到 88。
- **可配置环境变量**：支持 `QUANT_AGENT_AUTO_TRADE_MIN_SCORE`、`QUANT_AGENT_MIN_TOTAL_SCORE`、`QUANT_AGENT_MIN_FINAL_RANK_SCORE` 和按持仓数配置的 position-quality 门槛。
- **验证**：先运行回归测试确认 OI migration、AI logging、72 分候选均失败；随后定向测试 100/100 通过；全量 `mvn -DskipTests=false test` 为 **805 run / 0 fail / 0 error / 0 skip**；`git diff --check` 与 `docker compose config --quiet` 均通过。

### 2026-09-19 - 扫描 / 评分 / 自动交易链路最终验收

- **变更摘要**：全链路 deadline-aware 扫描、`LiveScanSnapshot` 原子发布、report 缺失 fail-closed、自动交易仅接受 `COMPLETED`、V2 canonical score 不回退 V1、`deepAnalysisCompleted` 语义修正、OI 数据质量分层。
- **影响文件（核心）**：
  - 扫描：`OkxContractScanner`、`ScanContext`、`ScanRunReport`、`ScanRunStatus`、`ScanDiagnostics`
  - 发布 / 分析：`ContractAnalysisService`、`LiveScanSnapshot`、`ContractScanResult`
  - 任务：`ContractMarketScanTask`
  - 环境 / 网络：`MarketEnvironmentService`、`OkxRestClient`、`OkxOpenInterestHistoryProvider`、`OpenInterestHistoryProvider`
  - 评分 / 信号：`V2SelectionScoreCalculator`、`ContractSignalAnalyzer`、`ContractCandidateScoreRecorder`
  - 测试：`OkxContractScannerScanControlTest`、`OkxContractScannerV2Test`、`ContractAnalysisServiceTest`、`ContractMarketScanTaskTest`、`OkxOpenInterestHistoryProviderTest`、`V2SelectionScoreCalculatorTest`、`ContractSignalAnalyzerTest`、`OpenInterestServiceTest`
- **deadline 语义（`scan-round-timeout-seconds`）**：约束 market_environment → fetch_tickers → collect_market_cap → universe_hard_filter → deep_analysis → OI/Funding/RS/ATH enrichment → V2 score → ranking 全阶段。每阶段开始前检查 `ctx.isExpired()`；BTC/ETH K 线、ticker、market-cap batch、OI history 均使用 `ctx.requestTimeout()`；market-cap batch 通过 `Future.get(waitBudget)` 有界等待；deadline 后不再 seed cache、不再发布 `COMPLETED`、返回 `PARTIAL_TIMEOUT`/`FAILED` 且候选列表为空。
- **partial / failed / unknown**：
  - `PARTIAL_TIMEOUT` / `FAILED` / `UNKNOWN` / report 缺失：不覆盖 `LiveScanSnapshot`、不覆盖 cached candidates / V2 scores、不记录 SUCCESS、不进入自动交易。
  - `report == null` → `UNKNOWN`，日志含 `scanId`、`status=UNKNOWN`、`reason=SCAN_REPORT_MISSING`。
  - `ContractMarketScanTask` 采用白名单：仅 `scanStatus == COMPLETED` 才调用 `autoTradeService.evaluateAndExecute(...)`。
- **V2 canonical score**：`ContractAnalysisService.analyze(...)` 只读 `LiveScanSnapshot` 中同 scanId 的 V2 score；缺失时抛 `IllegalStateException("V2 canonical score 不可用: <instId>")`，不回退 `candidate.score()`、不用 0 分伪造、不生成 STRONG/GOOD 可交易推荐。
- **原子发布**：`AtomicReference<LiveScanSnapshot>` 一次性发布 candidates + v2Scores + scanId + status + startedAt + finishedAt；`candidates()`、`cachedV2Scores()`、`analyze()` 均从同一快照读取。
- **`deepAnalysisCompleted`**：仅在 K 线基础分析 + RS + ATH + OI + Funding 等已启用 enrichment 全部非 TIMEOUT/UNAVAILABLE 后递增；provider 超时/缺失时候选仍可发布（若 scan 整体 COMPLETED），但该合约不计入 completed 计数。
- **OI 数据不可用行为**：
  - V1（`ContractSignalAnalyzer`）：`previousOI <= 0` 时 `oiChange=null`，保留风险提示「OI变化不可用，不能作为增仓确认」。
  - V2（`OkxOpenInterestHistoryProvider`）：支持 `fetch(instId, Duration timeout)`；原因码包括 `OI_FIRST_OBSERVATION`、`OI_EMPTY_RESPONSE`、`OI_INSUFFICIENT_HISTORY`、`OI_BASELINE_ZERO`、`OI_TIMEOUT`、`OI_PROVIDER_UNAVAILABLE`；null 不转 `BigDecimal.ZERO`；OI 缺失时 V2 `DERIVATIVES` 维度 unavailable，`coverageRatio`/`availableWeight` 下降，不给增仓确认加分，不生成 LEADING 方向。
- **扫描完成日志字段**：`scanId`、`status`、`currentStage`、`durationMs`、`universeCount`、`hardFilterPassed`、`deepTargetLimit`、`deepAnalysisStarted`、`deepAnalysisCompleted`、`skippedByDeadline`、`providerTimeoutCount`、`oiDataQuality`、`finalRankCount`；OI 不可用按 instId 打 debug，不重复刷 WARN。
- **验证（2026-09-19 实测）**：
  - 聚焦测试：`ContractMarketScanTaskTest, OkxContractScannerV2Test, ContractAnalysisServiceTest, OkxContractScannerScanControlTest, ContractSignalAnalyzerTest, OpenInterestServiceTest, OkxOpenInterestHistoryProviderTest, V2SelectionScoreCalculatorTest` → **77 run / 0 fail / 0 error / 0 skip**
  - 全量测试：`mvn -DskipTests=false test` → **793 run / 0 fail / 0 error / 0 skip**
  - `git diff --check` 通过

### 2026-09-18 - Wave 4.2 V2 Selection Score calibration

- 变更摘要：保留七维权重与 available-weight normalization，新增独立的 `normalizedScore`、`coverageRatio` 与 `rankingScore`，使低覆盖候选不会仅凭单一高分维度进入排序顶部。
- 数学语义：`normalizedScore = Σ(score_i × weight_i) / availableWeight`；`coverageRatio = availableWeight / 100`；`rankingScore = normalizedScore × confidence(coverageRatio)`。confidence 的 evidence bands 由权重推导：medium band 为 CapitalFlow+Derivatives+News=50，high band 为五个最大维度=85；分段 soft penalty 为 `0 coverage -> 0`，`0<coverage<50% -> 0.50 + 0.70×coverage`，`50%~85% -> 0.85 + 0.13×(coverage-0.50)/0.35`，`85%~100% -> 0.98 + 0.02×(coverage-0.85)/0.15`。全部不可用时两个 score 都为 null。
- Derivatives：`LEADING` 只对正 OI 增长增加幅度分；`FOLLOWING_WEAK`、`CAPITULATION`、`WEAK_DOWN` 的不利 OI 幅度不再使用 `abs()` 奖励，而是按方向作惩罚；`OIDirection.classify()` 未修改。
- MarketStructure：BULLISH/BEARISH 仅表示方向明确度，不引入 LONG bias；真实高低点趋势结构加结构质量分，WATCH/NEUTRAL 与 RANGE 保持较低分，高风险/阻断风险扣分。
- Breakout 与防御：`PARTIAL`/`STALE` 使用依赖 raw breakout score 的质量 confidence，不再固定减 15；`UNAVAILABLE/TIMEOUT` 不入分。负 spread 使 VolumeQuality 不可用，spike、volume/mcap、spread 的线性项在配置区间内封顶。
- 排序：`scanV2` 与 `compareForRanking` 先按 `rankingScore`，再按 `normalizedScore`、`availableWeight`、原 `finalRankScore`、`score`、`volume24h`、`instId` 做 deterministic tie-break；`finalScore()` 保留为 normalized score 的兼容别名。

### 2026-09-17 - Wave 4 V2 Selection Score

- 变更摘要：新增独立、纯函数式的 V2 Selection Score，使用已有 Candidate/Snapshot 数据按七个可缺失维度归一化评分，并让 `scanV2` 在 enrichment 完成后按 V2 score 稳定排序。
- 影响文件：`crypto/selection/V2ScoreDimension`、`V2SelectionScoreResult`、`V2SelectionScoreCalculator`、`OkxContractScanner` 及 V2 score 测试。
- 评分维度与权重：CapitalFlow 25、Derivatives 20、RelativeStrength 15、MarketStructure 15、VolumeQuality 10、Breakout 10、News 5，总权重 100。CapitalFlow 使用成交额/OI/盘口深度；Derivatives 复用 `OIDirection`、OI 24h 变化、`FundingTrend` 与 extreme；RelativeStrength 复用 1h/4h/24h RS；MarketStructure 使用趋势、K 线结构与市场风险；VolumeQuality 使用 volume spike、Volume/MCap、spread；Breakout 使用 ATH 分类且 `PARTIAL` 降分；News 使用已有新闻风险评分。
- 缺失处理：维度不可用时不计入分子或分母；`availableWeight = Σ可用维度权重`，`finalScore = Σ(score_i × weight_i) / availableWeight`，全部不可用时返回 null score，不伪装成 0、NaN 或 Infinity。`STALE/PARTIAL` 保留已有数据语义，PARTIAL Breakout 做可信度折减。
- V1 隔离：未修改 `ContractSignalAnalyzer`、V1 score、55 ceiling、交易执行、Trade Gate、仓位、杠杆、风险或保护逻辑；V2 calculator 不请求 HTTP、不写数据库、不进入 AutoTradeService。
- 排序：`scanV2` 在 OI/Funding、RelativeStrength、ATH enrichment 后计算 V2 score，排序为 V2 score DESC，再按原 `finalRankScore`、`score`、`volume24h`、`instId` 做 deterministic tie-break；不覆盖 Candidate 的 `finalRankScore`。
- 验证：V2 score/scanner 定向测试 15/15 通过；完整测试与 verify 结果以本轮最终验证为准。

### 2026-09-17 - Wave 3-B ATH / Breakout enrichment

- 变更摘要：新增基于 OKX 1D K 线历史 high 的 ATH / Breakout Provider，支持历史分页、共享按合约缓存、截断元数据，并在 `scanV2` 中通过 `ContractCandidate.withAthBreakout` 做非评分 enrichment。
- 影响文件：`AthBreakoutProvider`、`OkxAthBreakoutProvider`、`AthBreakoutSnapshot`、`ContractCandidate`、`OkxContractScanner`、`AgentProperties`、`DataQuality`、`MarketCapFilter`、`application.yml` 及 ATH/Breakout 测试。
- 影响：currentPrice 使用 broad ticker 的 `ticker.last`；ATH 使用有效 1D candle high 的最大值；距离区间由配置驱动（-2/-10/-40）；历史遍历达到 `max-pages` 且仍可能存在更早数据时标记 `PARTIAL` 与 `lookbackTruncated=true`。ATH 缺失不参与 UniverseHardFilter，不改变 score、finalRankScore、交易门禁或执行逻辑。
- 验证：ATH/Breakout Provider 15 个、Candidate 1 个及 scanner 回归 4 个定向测试通过；完整 `mvn -o test` 为 722/722，`mvn -o verify` 成功，`git diff --check` 通过。

### 2026-09-17 - Wave 3-A RelativeStrength enrichment

- 变更摘要：新增基于 OKX 1H K 线的 RelativeStrength Provider，按 1H/4H/24H 计算候选相对 BTC/ETH 强者基准的原始差值，并通过 `ContractCandidate.withRelativeStrength` 在 `scanV2` 中做非评分 enrichment。
- 影响文件：`RelativeStrengthProvider`、`OkxRelativeStrengthProvider`、`RelativeStrengthSnapshot`、`ContractCandidate`、`OkxContractScanner`、`AgentProperties`、`application.yml` 及 RelativeStrength 测试。
- 影响：BTC/ETH 基准使用共享 `ProviderCache`（fresh 60 秒、stale 600 秒）；当前候选 `score`、`finalRankScore`、UniverseHardFilter、交易门禁和执行链路均不依赖 RelativeStrength。数据不足、非法价格或 Provider 不可用时保留 null 与 `DataQuality.UNAVAILABLE`，不将缺失转换为 0，也不淘汰候选。
- 验证：RelativeStrength/Candidate 定向测试 10/10 通过；V2 scanner 回归 9/9 通过；完整 `mvn -o test` 与 `mvn -o verify` 结果以本轮最终验证为准。

### 2026-09-09 - F5 funding completeness 显式产品语义

- 变更摘要：`AutoTradeProfitSummary` 新增 `fundingCompleteness`。严格 lifecycle 的 OKX fills 已能确定 gross/fee、但同合约 funding bill 无法唯一归属订单时，API 保留已知 gross/fee，返回 `fundingCompleteness=UNKNOWN`、`funding=null`、`realized/net=null`，并保留 `AUTO_SCOPED_LEDGER_FUNDING_UNATTRIBUTED`，不再降格成泛化的“尚未接入流水”。
- 影响文件：`AutoTradeProfitService`、`AutoTradeProfitSummary`、`AutoTradeProfitServiceTest`、Demo V33 E2E 最终报告。
- 影响：只修正只读收益 API 的完整性表达，不分摊 funding、不写 ledger、不改变交易 lifecycle、策略参数或订单路径；可唯一归属时返回 `fundingCompleteness=COMPLETE`。
- 验证：新测试先因 API 缺少 `fundingCompleteness` 编译失败，最小实现后通过；完整 Maven 与真实 Demo API 读回结果见本轮最终报告。

### 2026-09-09 - Demo V33 暂停任务恢复与最终验收

- 恢复点：源码与真实 Demo DB 均为 V33；上一轮已经完成 migration、极小 XAU entry/fill/protection/manual close，但仓库 12:40 的 QA lifecycle 绑定修复未进入 12:31 构建的运行镜像，留下一个 `CLOSED_RECONCILING`。V27 迁移前备份证据仍为 UNKNOWN。
- 本轮完成：部署遗漏修复、增加孤立 manual close 的严格唯一事实恢复、收口该 QA lifecycle、停止重复 recovery 事件、补齐 F5 explicit N/A、复核 Recorder/Shadow/Counterfactual、UNKNOWN/Algo/readiness 故障注入与全量回归。
- 当前数据库：MySQL 8.4、Flyway V33 success；所有 PendingOrder 为 CLOSED/REJECTED，所有 close record 为 CLOSED，unresolved pending/close、非零持仓、当前普通/Algo订单均为 0。
- 当前部署：`okx-quant:demo-v33-e2e-final-20260909`，image ID `sha256:2906188154257a73f8a1d1b5dcdddcf1cb05ec0def3e43f16cb627d4327263b6`；仓库与容器 JAR SHA-256 均为 `e04de923829eecf226119134cc5f70ddaeaefef37a066c4e6c6c16ca4277e267`。
- 当前结论：`READY_FOR_DEMO_AUTO=YES`，但 AutoTrade 保持 OFF；`READY_FOR_LIVE=NO`。未完成/证据限制为：V27 pre-migration backup 无证据、无本轮新 Algo child fill、无真实 exchange timeout UNKNOWN 注入、Public WS 未实现、Shadow/OOS 尚不能证明策略正期望。
- 详细证据：`docs/GPT56_SOL_DEMO_V33_E2E_2026-09-09.md`。

### 2026-09-09 - Demo QA 手动平仓生命周期恢复收口

- 恢复点：真实 Demo 数据库与运行镜像都已在 V33，但 2026-09-08 的极小 XAU QA 生命周期因当时镜像中的 `PositionCloseService` 仍要求 budget reservation，手动平仓事实没有绑定 PendingOrder，留下一个持续重试的 `CLOSED_RECONCILING`。
- 变更摘要：`ClosePositionRecoveryService.reconcileNaturalClose` 可收养唯一且完整的孤立手动平仓事实；只接受同 owner、同合约、同方向、与 PendingOrder 持久化 executable contract quantity 完全一致、时间相差不超过 10 分钟且已有 order/fill/PnL 完整事实的候选。候选不唯一或任一条件不满足时继续保持 `CLOSED_RECONCILING`。
- 唯一约束：恢复只复制交易所 close order、数量、成交价、realized PnL、fee/funding 完整性，不复制原记录的 `closeClOrdId`，因此不制造 V33 `(user_name, close_cl_ord_id)` 冲突；原手动 close 记录保持不变。
- 影响文件：`src/main/java/com/example/quant/account/ClosePositionRecoveryService.java`、`src/test/java/com/example/quant/account/ClosePositionRecoveryServiceTest.java`、本说明和 Demo V33 E2E 最终报告。
- 验证：正向用例先失败（预期 closed=1、实际 0），实现后通过；另有双候选歧义 fail-closed 用例。`ClosePositionRecoveryServiceTest` 13/13；close 修复阶段全量 418/418，加入 F5 API 测试后最终全量 419/419。真实 Demo 部署、启动恢复与运行态读回结果记录在 `docs/GPT56_SOL_DEMO_V33_E2E_2026-09-09.md`。

### 2026-09-07 - Close Intent 数据库级幂等

- 变更摘要：V33 为 `(user_name, close_cl_ord_id)` 与 `(user_name, pending_order_id)` 增加唯一约束；max-hold scheduler 在唯一键竞争失败时读取胜出的 intent 并转交 recovery，不再发送第二次 close。`CLOSE_FAILED_BEFORE_SEND/CLOSE_REJECTED` 只能由数据库条件更新成功的一个实例重新认领。
- 影响文件：`ClosePositionRecordRepository`、`AutoTradeLifecycleService`、`V33__close_intent_idempotency.sql`、`ClosePositionRecordRepositoryTest`、`AutoTradeLifecycleServiceTest`。
- 影响：覆盖双 scheduler stable-ID 竞争，以及手动/自动平仓对同一自动 PendingOrder 的竞争；不可重试冲突保持人工关注。
- 数据库验证：运行中 Demo MySQL 只读检查为 V27，两类唯一键重复分组均为 0，未执行升级；一次性 MySQL 8.4 空库 V1-V33 迁移与 Spring context 启动通过。
- 回归验证：`mvn -DskipTests=false test` 共 408 项通过；`mvn -DskipTests package` 与 `git diff --check` 通过。

### 2026-09-07 - 独立复核首批事实修复

- 变更摘要：将 F7 的最终合约数量和可执行止损金额加入 PendingOrder 持久化；缺少 fee 或 fill PnL 的 Algo child fills 保持 PARTIAL；不可用的 Demo 公共行情 provenance 改为 fail-closed；自动交易记录只读取实际 RankedCandidate 的理由，不再回退到 selection 顶层理由。
- 影响文件：`PendingOrder`、`PendingOrderEntity`、`V30__pending_order_executable_risk.sql`、`AlgoFillReconciliation`、`OkxCurrentOrderService`、`MarketDataContext`、`AutoTradeRecordService` 及对应回归测试。
- 影响：重启后 Adapter 复用确认阶段的 contract quantity，不因 instrument metadata 变化重算下单数量；账本缺字段不再伪装 COMPLETE；signal venue 保持 `PUBLIC_LIVE`；候选 metadata 保持单一事实源。
- 验证：四个回归测试均已先确认 RED；生产修复后继续由同组测试验证，完整结果见本轮最终审计报告。

### 2026-09-07 - 确认阶段与持仓年龄重启恢复

- 变更摘要：恢复任务将崩溃遗留的 `CONFIRMING` 或尚未进入 `SUBMITTING` 的 `CONFIRMED` 安全回退到原 `BUDGET_RESERVED`；持仓年龄只接受属于非 reduce-only entry order 的成交时间，缺少 entry fill 时回退到已持久化的 `submittedAt`，不再误用 close fill。
- 影响文件：`AutoTradeRecoveryTask`、`AutoTradeLifecycleService` 及恢复/生命周期回归测试。
- 影响：确认阶段没有发生交易所提交，因此回退不会制造重复订单；第一笔极小 partial entry fill 仍按现有产品语义启动持仓计时。
- 验证：两个缺陷测试先确认 RED；修复后的定向与全量验证见本轮最终审计报告。

- F1 补充：`OkxRestOrderGateway` 对所有 reduce-exposure 写请求执行 `ExposureReductionGuard`，包括 readiness=READY；不再只在非 READY 时验证 owner、mode、managed position、size、reduceOnly 与稳定 ID。

### 2026-09-07 - Max-hold UNKNOWN 平仓单一恢复责任

- 变更摘要：生命周期调度在提交 max-hold close 前查询同一 PendingOrder 的未决 Close Intent；`CLOSE_SUBMITTING`、`CLOSE_SUBMIT_UNKNOWN`、`CLOSE_SUBMITTED` 存在时将订单交给 Close Recovery，不再创建第二个 intent 或再次提交。
- 影响文件：`AutoTradeLifecycleService`、`AutoTradeLifecycleServiceTest`。
- 影响：UNKNOWN 后稳定 `closeClOrdId` 和首个持久化 intent 保持单一；当前数据库索引仍不替代多进程调度锁，部署约束与残余风险记入全局审计。
- 验证：重复调度缺陷测试先确认 RED；修复后的定向与全量验证见本轮最终审计报告。

### 2026-09-07 - 自动交易收益按 lifecycle 严格归属

- 变更摘要：`executedOrderCount` 改由真实 entry fill lifecycle 计数；AutoTradeProfit 将自动 PendingOrder ID 集合传给 LedgerPnl，只汇总这些 lifecycle 的 fills。无法按订单归属的同合约 funding bill 保持未知，禁止混入 Manual/QA 收益。
- 影响文件：`AutoTradeProfitService`、`LedgerPnlService`、`AccountBillEntity`、`AutoTradeProfitServiceTest`。
- 影响：严格排除非 Scheduler lifecycle；资金费归属不完整时 `net` 为 N/A，并输出 `AUTO_SCOPED_LEDGER_FUNDING_UNATTRIBUTED`。
- 验证：测试先因缺少 scoped ledger API 确认 RED；修复后的定向与全量验证见本轮最终审计报告。

### 2026-09-07 - 同一扫描四策略 Shadow Selection

- 变更摘要：每次 Contract scan 用同一候选快照记录 `CURRENT_AI_OVERRIDE`、`RULE_FIRST_AI_RANK`、`RULE_ONLY`、`LIMITED_AI_OVERRIDE` 四种选择结果，并持久化 scan/policy/coverage/venue；V31 以 `(user_name, scan_id, policy)` 保证幂等。
- 影响文件：`ShadowSelectionService`、`ShadowSelectionEntity`、`ShadowSelectionRepository`、`ContractMarketScanTask`、V31 migration 及测试。
- 影响：Shadow service 只依赖候选、AI selection 和自身 repository；没有 PendingOrder、budget、DailyRisk、leverage 或 OKX order gateway 依赖。Shadow 失败被记录后不会中断真实执行。
- 一致性：实际执行和 Shadow 共用 `ContractTradeGate.aiHardDenyReasons`，避免 hard eligibility 双实现漂移。
- 验证：缺少基础设施的编译失败先确认 RED；定向、Flyway 与全量验证见本轮最终审计报告。

- 补充：`RULE_FIRST_AI_RANK` 下，历史 WaitingEntry 触发后会重新执行 `ContractTradeGate.planDenyReasons`；已失去规则 eligibility 的 intent 转为 `FAILED_SAFE`，不再成为绕过 Rule-first 的第二入口。

### 2026-09-07 - 七策略 Counterfactual Exit

- 变更摘要：对真实 entry fills 独立评估 `BASELINE`、`S1_V2`、`TIME_60/120/240`、`BE_075`、`TRAIL_1R`，V32 按自动 PendingOrder 与 policy 幂等更新。
- 影响文件：`CounterfactualExitService`、`CounterfactualExitTask`、entity/repository、V32 migration 及测试。
- 影响：输入 entry 价格、数量和时间来自真实非 reduce-only fills；后续路径仅用 `PUBLIC_LIVE` 5m candles，执行 venue 固定标记 `PUBLIC_COUNTERFACTUAL`。该任务不注入 Ledger、DailyRisk、PositionLifecycle 或订单网关。手续费无法从公共行情反事实推导，因此 fee/net 保持 N/A。
- Persistency：评估任务直接遍历持久化 `pending_order_state`，包括进程重启后不再加载到内存的 CLOSED 行；BASELINE target 使用该订单持久化的实际 `takeProfitPrice`，避免退回固定 3R。
- 数据窗口：若 300 根 5m 公共 candles 已无法覆盖 entry 后首个 10 分钟，结果记为 `DATA_GAP / INSUFFICIENT_PUBLIC_CANDLE_WINDOW`，不从截断路径推导虚假 exit。
- 验证：七策略、venue 与未知费用语义由定向测试覆盖；完整结果见本轮最终审计报告。

- Sideways 审计补充：生命周期快照同时暴露 `configuredSidewaysAfter` 与 `effectiveSidewaysAfter`。默认 180 分钟配置在 max-hold=60 分钟时实际为 59 分钟；该行为属于明确可见的 effective policy mutation，不再只存在于内部静默计算。

### 2026-09-07 - Strategy Evaluation 聚合

- 变更摘要：新增按 selection policy 与 exit policy 聚合的只读 API `/api/agent/strategy-evaluation`，输出 scanCount、selectionCount、coverage、tradeCount、gross、fee、net、expectancy、expectancyR、PF、drawdown、avgHold、dataQuality。
- 影响文件：`StrategyEvaluationService`、`StrategyEvaluationController` 及测试。
- 影响：无数据和无法推导的字段返回 JSON null（N/A），不以 0 伪装；当前公共行情反事实缺实际费用，fee/net/expectancy/PF/drawdown 保持 N/A。
- 验证：无数据语义先确认 RED；定向与全量验证见本轮最终审计报告。

### 2026-09-07 - S1 生命周期时间语义与保护目标统一

- 变更摘要：Entry 超时继续以 `submittedAt` 计算，已成交持仓的生命周期年龄改为本地 entry fill 的最早 `fillTime`；sideways 调整在 max-hold 判断前执行；TP3 保护目标改为读取订单计划 `takeProfitPrice` 的实际 R 倍数，避免执行层固定 3R 与计划目标不一致。
- 影响文件：`AutoTradeLifecycleService`、`TradeFillRepository`、`OkxTradeAdapter` 及生命周期回归测试。
- 影响：未修改 entry、SL、TP 比例、风险比例、仓位、杠杆或成交数量规则；仅修正等待成交时间与实际持仓时间混用，以及计划/执行目标的事实来源。
- 验证：生命周期与 Adapter 定向测试通过；完整策略收益实验仍受当前仅 3 笔完整交易样本限制，不能证明正期望。

### 2026-09-07 - S2 Rule-first AI candidate policy

- 变更摘要：新增 `agent.ai-selection.policy`（默认 `RULE_FIRST_AI_RANK`）。该模式先用现有 `ContractTradeGate.planDenyReasons` 构造规则可交易池，再让 AI 只负责候选排序和方向；原有环境硬门禁仍在，AI 不能绕过。可通过配置显式切回 `CURRENT_AI_OVERRIDE` 以进行对照实验。
- 影响文件：`AgentProperties`、`AutoTradeService`、`application.yml`。
- 影响：没有改 AI prompt、指标参数、候选评分、风险比例、仓位或杠杆；这是低自由度的候选职责划分，NEUTRAL/不满足规则门禁的候选默认不进入 AI 主选池。
- 验证：`AutoTradeServiceTest` 通过；只有 3 笔完整交易，S1/S2 控制变量结果均标记样本不足，不能据此宣称正期望。

### 2026-09-07 - F3 市场数据与执行环境 provenance

- 变更摘要：确认 OKX 公共 Market Data REST（candles、ticker、books、funding、OI、instruments）均通过 `publicGet`，当前不附带模拟盘 header；账户、订单、持仓和 fills 仍通过私有接口按 `TradingMode` 使用 Demo/Live。新增 `MarketDataVenue`/`MarketDataContext`，在 TradePlan JSON 中记录 signal/execution 及各类数据 venue。
- 安全边界：`MARKET_DATA_MODE=DEMO` 而公共行情 API 不具备独立模拟数据时 fail-closed，禁止静默回退到 Public；默认产品语义为 `PUBLIC_LIVE signal + DEMO/LIVE execution`。
- 影响文件：`MarketDataVenue`、`MarketDataContext`、`OkxRestClient`、`TradePlanRecordService` 及 F3 venue/XAU fixture 测试。
- 验证：F3 定向 17 个测试通过；全量 Maven、package、Flyway v29 和 `git diff --check` 待最终执行。

### 2026-09-07 - F6/F7 生产链路接入与最终数量归一化

- 变更摘要：自动交易候选切换测试覆盖 HYPE 观察失败后 BTC 提交；下单确认阶段通过 `OkxInstrumentRulesProvider` 预先归一化最终 base quantity，Adapter 使用同一归一化结果，PendingOrder 记录 executable stop risk。
- 影响文件：`OrderConfirmService`、`PendingOrder`、`OkxInstrumentRules`、`TradePlan` 及 F6/F7 测试。
- 影响：未改变仓位算法、风险比例、杠杆、lot 规则或最终 contract 数量；风险检查前后使用同一规则归一化事实。
- 验证：定向 39 个测试、全量 Maven、package、Flyway v29、`git diff --check` 通过。

### 2026-09-07 - F6/F7 第三阶段验收 fixture

- 变更摘要：补充 HYPE→BTC 候选失败切换的自动交易集成夹具，验证实际 BTC 计划使用候选级 reason/risk；补充 XAU/BTC 合约规则归一化后的 executable risk 测试及显式 lot 向下取整测试。
- 影响文件：`AutoTradeServiceTest`、`TradePlanExecutableRiskTest`、`OkxInstrumentRules`、`TradePlan`、`PositionSizingServiceTest`、`TradingDayResolverTest`。
- 验证：候选 A 观察计划被跳过后候选 B BTC 提交；XAU/BTC 归一化风险与 raw risk 区分；全量测试与打包待本轮最终执行。

### 2026-09-07 - F6/F7/F8 事实语义与业务交易日统一

- 变更摘要：`RankedCandidate` 持有候选级 reason/risk，候选切换与自动交易记录按实际候选绑定，缺失理由保持空值；仓位结果拆分 `riskBudgetAmount` 与按最终数量计算的 `estimatedStopLossAmount`，计划 JSON 增加明确字段并保留旧 `maxLossAmount` 作为预算兼容字段；新增 `TradingDayResolver` 统一 Asia/Shanghai 业务日，DailyRisk、Ledger 和 AutoTradeProfit 共用同一日期解析。
- 影响文件：`ContractSelectionDecision`、`AiContractSelectionService`、`AutoTradeService`、`AutoTradeRecordService`、`PositionSizingResult`、`PositionSizingService`、`TradePlan`、`TradePlanRecordService`、`TradingDayResolver`、`DailyRiskService`、`LedgerPnlService`、`AutoTradeProfitService` 及对应测试。
- 影响：未改变 AI prompt、策略阈值、止盈止损、风险比例、杠杆或计算出的下单数量；旧 `maxLossAmount` API 保留并明确表示 risk budget，估算止损以最终 size 重算。
- 验证：F6/F7/F8 定向测试 17 个通过；既有 DailyRisk UTC rollover 测试已按业务日边界更新。全量回归与 package 待本轮结束后执行。

## 系统定位

本项目是 OKX USDT 永续合约实盘控制台。后端使用 Spring Boot 负责行情扫描、策略信号、独立风控、待确认订单、OKX 下单、订单/仓位恢复和审计；前端使用 React Admin 与 MUI 展示真实账户、持仓、订单和系统控制状态。

### 2026-09-07 - F4 Close Intent 恢复与 F5 自动交易事实统计

- 变更摘要：平仓意图现在先持久化再发送，区分发送前失败与发送后未知，并按稳定 `closeClOrdId` 查询当前/历史订单恢复；自动交易统计改为按 `SCHEDULER` lifecycle 的真实 entry fills 计数，排除 QA/manual，并暴露 submitted、entry-filled、closed、PnL-complete 分层计数。
- 影响文件：`PositionCloseService`、`AutoTradeLifecycleService`、`ClosePositionRecoveryService`、`OkxCurrentOrderService`、`OkxRestOrderGateway`、`OkxOrderGateway`、`ClosePositionRecordEntity`、`AutoTradeProfitService`、`AutoTradeProfitSummary`、Flyway `V29__close_intent_submit_audit.sql` 及相关测试。
- 影响：close intent 使用 `CLOSE_SUBMITTING/CLOSE_SUBMITTED/CLOSE_SUBMIT_UNKNOWN/CLOSE_FAILED_BEFORE_SEND`；恢复不会因一次 query miss 生成新 ID；统计不再把 `EXECUTED` workflow 状态当成交事实。
- 验证：F4/F5 定向测试先 RED 后 GREEN；Flyway v29、定向回归 50 个测试、全量 380 个测试、`mvn -DskipTests package` 和 `git diff --check` 通过。

### 2026-09-07 - F1/F2 风险降低通道与 Algo 子订单账本对账

- 变更摘要：交易写网关引入 `TradingOperationType`/`TradingOperationContext`，非 READY 状态继续阻断开仓、加仓、杠杆和撤单；只有经过 owner、DEMO/LIVE、instId、posSide、真实托管仓位、方向、数量、reduceOnly 与幂等键验证的平仓/减仓/保护操作才可发送。Algo 终态同步现在解析 `algoId` 与 `ordIdList`，分别查询普通 child order 和 child fills，经统一 fill ledger 入账，并以 `pnlCompleteness` 区分 UNKNOWN/PARTIAL/COMPLETE。
- 生命周期边界：position gone 仅进入 `CLOSED_RECONCILING`/`POSITION_GONE_PNL_INCOMPLETE`，成交数量、签名 fee、fill PnL 未闭环前不标记 CLOSED、不写 DailyRisk；重复同步仍由 `owner + instId + tradeId` 唯一键去重。
- 影响文件：`OkxRestOrderGateway`、`OkxOrderGateway`、`ExposureReductionGuard`、`TradingOperationType`、`TradingOperationContext`、`OkxCurrentOrderService`、`OkxCurrentOrderSyncService`、`TradeOrderEntity`、`ClosePositionRecordEntity`、`TradeFillLedgerService`、`ClosePositionRecoveryService`、`PendingOrder`、Flyway `V28__algo_child_fill_completeness.sql` 及对应测试。
- 验证：F1 网关/验证器、Algo child ID、XAU 717 contracts/14 fills 顺序不变性、signed fee 与重复 ledger replay 定向测试通过；后续执行完整相关测试与编译。未修改 Entry、SL、TP、RR、持仓/杠杆/手续费参数或策略选择逻辑。

### 2026-08-12 - 终态 fills 补偿对账、每日汇率与震荡容量

- 变更摘要：Private WS 终态事件的单次 `fillSz` 小于累计 `accFillSz` 时，订单先进入 `RECONCILING`；REST 订单同步会对最近 24 小时、本地账本尚未确认完整的 `FILLED / PARTIALLY_FILLED_CANCELED` 普通订单按 `ordId` 补齐全部 fills，防止真实系统仓位被误判为 `UNMANAGED / EXCHANGE_LOCAL_SIZE_MISMATCH`。震荡行情动态仓位上限从 1 调整为 2，趋势行情仍为 3，高波动仍为 1。
- 汇率：人民币换算在应用就绪后及每天北京时间 00:05 读取 ECB 欧元日参考汇率，并以 `EUR/CNY ÷ EUR/USD` 得出 `USD/CNY`；ECB 官方域名不可达时使用无密钥的 Frankfurter ECB 镜像。接口全天读取线程安全快照。后续刷新全部失败时保留上次成功值并标记 `*_STALE`，从未成功时才使用 7.0 配置兜底并标记 `FALLBACK`。汇率不进入交易、PnL、仓位或风险计算。
- 影响文件：`OkxOrderEventService`、`OkxCurrentOrderSyncService`、`EcbFxRateClient`、`FxRateService`、`SystemFxRateProperties`、`AgentProperties`、`application.yml`、`docker-compose.yml`、`.env.example` 及对应测试。
- 验证：订单事件、终态 fills、仓位对账、ECB/Frankfurter 解析、汇率快照和震荡第二仓均完成 RED→GREEN 测试；`mvn clean package` 全量 360 个测试通过，前端 Vite 构建和 `git diff --check` 通过。Demo 容器已重建且 healthy，运行扫描确认 `CHOPPY dynamicMaxPositions=2`；XAU 入场订单已回补 7 条 fills，合计 717 contracts，当前交易所无该仓位时本地正确标记 `CLOSED`。启动实测取得 `USD/CNY=6.74317887`，来源 `ECB`。

### 2026-08-12 - 自动入场从极度保守调整为适度积极

- 变更摘要：AI 主选提示不再要求全部软指标和周期完美一致；趋势明确、流动性良好且不过热时优先考虑 `MARKET / LIMIT`，等待仅用于过热、结构未确认、价格明显偏离或风险收益不足。等待意图从 15 分钟延长至 30 分钟，`READY` 触发允许参考价上下对称 0.20% 容差；经过两根新收盘 5m K 线后，同类型 `WAIT_PULLBACK / WAIT_RETEST` 也可在价格容差内触发，`NOT_READY` 仍禁止触发。
- 影响文件：`AiContractSelectionService`、`WaitingEntryIntentService`、`AutoTradeService` 及三个对应测试类；设计与实施边界记录在 `docs/superpowers/specs/2026-08-12-balanced-entry-activity-design.md` 和 `docs/superpowers/plans/2026-08-12-balanced-entry-activity.md`。
- 运行影响：新等待意图拥有更多收盘 K 线确认机会，轻微价格偏差不再造成无意义错过；触发后仍完整经过 TradingMode、readiness、Kill Switch、账户、仓位、每日风险、实时流动性、精度与订单幂等链路。一轮多个候选均未成交时，首个确认层硬拒绝原因优先展示，不再被后续普通观察原因覆盖。
- 验证：等待意图 7 个、AI 选择器 8 个、自动交易服务 42 个定向测试通过；`mvn clean package -DskipTests=false` 全量 353 个测试通过，前端 TypeScript/Vite 构建、Docker 镜像构建和 `git diff --check` 通过。Demo 应用容器已使用新镜像重建并保持 healthy，运行模式确认仍为 `TRADING_MODE=DEMO`，Flyway schema v27 无需新增迁移。

### 2026-08-12 - 多合约入场去重、1–10 倍杠杆与 Demo 状态显示

- 变更摘要：自动交易不再因任意合约存在活跃入场委托而停止整轮扫描；本地活跃入场、OKX 活跃委托和真实持仓按 `instId` 去重后统一占用仓位容量，同一合约仍禁止重复入场，`SUBMITTING / UNKNOWN_SUBMIT_STATUS` 继续全局阻断。规则杠杆扩展为 1–10 倍，建议 0 回退为 1 倍，硬风险只能降杠杆，系统最终上限固定为 10。
- 影响文件：`TradeOrderRecordService`、`AutoTradeService`、`ContractSignalAnalyzer`、`LeverageDecisionService`、`AgentProperties`、`application.yml`、`.env.example`、`docker-compose.yml`、`Dashboard.tsx` 及对应测试。
- 运行影响：不同合约可在动态仓位上限、预算和组合风控内继续入场；容量拒绝信息会同时给出 `dynamicMax`、`configuredMax` 和 `marketRegime`。绝对上限默认 3，`CHOPPY` 默认动态上限为 2，`HIGH_VOLATILITY` 仍为 1，可通过 `.env` 显式调整。Dashboard 持仓区不再因“存在持仓”而写死 `OKX_REAL`，始终显示账户真实 Demo/Live 模式。已有持仓杠杆不会自动修改。
- 验证：`AutoTradeServiceTest` 41 个测试和杠杆/计划相关 30 个定向测试通过；`mvn clean package -DskipTests=false` 全量 349 个测试通过，前端 TypeScript/Vite 构建和 Docker 镜像构建通过，`git diff --check` 通过。Demo 容器重启后确认 `TRADING_MODE=DEMO`、杠杆上限 10；运行扫描显示 `held=0, okxCurrentOrders=0, localActiveEntries=0`，未再出现跨合约 `local_active_entry_order` 或保护单重复占用容量。

### 2026-08-12 - AI 等待入场持久化与限价撤单终态确认

- 变更摘要：AI `WAIT_PULLBACK / WAIT_RETEST` 不再作为一次性跳过丢弃，而是持久化为最多 15 分钟的等待入场意图；新收盘 5m K 线满足回踩/回测、方向与风险条件后，使用原始稳定 `clOrdId` 重新进入既有计划、账户、仓位、流动性、精度、风险和订单确认链路。自动分配阶段同时应用最终确认使用的账户权益单笔保证金上限，避免生成必然被下游风控拒绝的金额。
- 影响文件：`AiContractSelectionService`、`WaitingEntryIntent*`、`AutoTradeService`、`ContractMarketScanTask`、`StrategyDecisionStatus`、`AutoTradeLifecycleService`、Flyway `V27__waiting_entry_intent.sql`、自动交易记录页及对应测试。
- 安全边界：等待意图不预占预算、不直接发单；到期、趋势反转、HIGH 市场/新闻风险或穿越失效价会终止。触发后崩溃可从 `TRIGGERED / VALIDATING` 恢复且不增加触发次数。限价单超时后不追价、不自动改单；只有 OKX 撤单 ACK 成功并通过订单详情确认 `canceled/filled` 终态后才释放预算或按真实成交量保护，`51400`、拒绝 ACK、未知终态都保留订单与预算继续对账。
- 验证：AI 协议、等待持久化/重启恢复、触发复用原 `clOrdId`、账户权益保证金封顶、K 线决策状态和撤单不确定性聚焦测试通过；`mvn clean package -DskipTests=false` 全量 346 个测试通过，前端 TypeScript/Vite 构建、npm 高危依赖审计和 `git diff --check` 通过。

### 2026-08-12 - Telegram 交易事实卡片与通知降噪

- 变更摘要：Telegram 改用 HTML 原生卡片；受保护开仓从真实 fills、有效保护单、合约规则、OKX 仓位和账户快照组装，平仓结算从 close/fills 组装并在消息首屏加粗突出盈利或亏损。删除 ordId、clOrdId 等内部字段。
- 影响文件：`src/main/java/com/example/quant/notification/TelegramMessageRenderer.java`、`TelegramSender.java`、`NotificationOutboxService.java`、`src/test/java/com/example/quant/notification/TelegramNotificationTest.java`、`docs/TELEGRAM_NOTIFICATION_GUIDE.md`。
- 运行影响：常规提交和瞬时成交过程不再推送；只推送受保护开仓、未成交撤单、UNKNOWN、保护异常、止盈调整、平仓处理中、最终结算和恢复异常。合约数量展示币数量与 contracts；资金费未对账时净盈亏保持“同步中”，不显示虚假 `0`。Telegram 原生消息不支持 CSS 字号/颜色，因此使用加粗结果和绿/红/黄/警告图标表达优先级。
- 验证：`mvn -Dtest=TelegramNotificationTest test -DskipTests=false` 通过 8 个测试，覆盖事件降噪、HTML 请求、字段转义、真实开仓/平仓数据、实际 TP1 角色、未知账户/资金费、盈利/亏损突出和其他生命周期告警；`mvn clean package -DskipTests=false` 全量通过 325 个测试并成功打包。

### 2026-08-12 - 真实 Demo Private WS 与最小交易验证入口

- 变更摘要：修复 OKX Private WS 订阅请求 ID 含连字符导致的 `60033 Parameter id error`，保留交易所协议错误详情，并确保 JDK WebSocket 先申请接收额度再发送登录；账号绑定恢复会持久化选择当前交易 owner。新增只允许 `DEMO` 的固定 5 USDT、1 倍杠杆交易基础设施验证入口，并在共享 PendingOrder 层拒绝 `WATCH/NO_ENTRY` 计划。
- 影响文件：`OkxPrivateWebSocketProtocol`、`OkxWebSocketService`、`SystemControlService`、`TradingStartupRecovery`、`PendingOrderService`、`DemoTradeVerificationService`、`DemoTradeVerificationController` 及对应测试。
- 运行影响：Private WS 只有认证、orders/positions/account 三频道订阅和 REST 对账完成后才 READY；Demo 验证入口为 `POST /api/quant/demo/verification/order?instId=ETH-USDT-SWAP`，LIVE/DISABLED、已有持仓或普通未成交单都会拒绝。该入口只验证订单基础设施，不代表策略信号，不改变自动策略阈值。
- 验证：真实 OKX Demo 私有连接达到 `AUTHENTICATED_SUBSCRIBED_RECONCILED`。专用 Demo intent 经完整确认链提交 ETH-USDT-SWAP 0.02 张市价单，真实 ACK、成交、仓位、附带止损、后续保护单、系统平仓、仓位归零、fills、bills 和 Telegram 通知均已验证；交易期间真实发生 Private WS `1006` 断线，引擎降级并阻断写请求，随后重连、重新认证/订阅和 REST 对账后恢复 READY。自动交易以 STRICT、50 USDT 总预算运行，重启后继续真实调用 AI，但当前行情决策为 `AI_WAIT`，因此没有伪造一笔“自动策略成交”。平仓恢复新增按 `closeClOrdId` 查询真实订单/fills 并回补 `ordId/avgPx/fillPnl/fee`，本次历史 Demo 平仓已真实回补；funding 未精确归属时，已确认亏损仍保守计入连续亏损/冷却，未确认的正收益不能重置连亏状态。完整后端 322 个测试、前端 TypeScript/Vite 构建及 `npm audit --audit-level=high` 通过。

### 2026-08-11 - AI 主选落地、持久化审计与启动读取解阻塞

- 变更摘要：自动交易在硬规则候选池内使用 AI 主选合约、方向和入场模式；合法 AI `WAIT` 结束本轮，技术失败进入不再二次调用 AI 的确定性规则保底；自动交易记录持久化选择来源、置信度、方向、入场模式和脱敏理由。
- 影响文件：`AiContractSelectionService`、`AutoTradeService`、`ContractTradePlanBuilder`、`TradePlanService`、`AutoTradeRecordEntity/Service/View`、`V26__auto_trade_ai_selection_audit.sql` 及对应测试。
- 运行影响：AI 不能决定止损、止盈、杠杆或仓位，也不能绕过 TradingMode、readiness、订单幂等和硬风控。历史审计字段保持空值，不做推测回填。
- 启动修复：`ContractAnalysisService` 将慢速全市场刷新与候选缓存读取解耦；启动扫描进行中时 HTTP 返回当前缓存（首次为空），不再持锁约 45 秒导致前端代理 502，扫描完成后后续轮询读取真实候选。
- 凭据打包修复：Maven 资源阶段显式排除 `src/main/resources/.env*`，部署检查同时验证构建产物；本地配置仍可读取，但不会进入 JAR 或 Docker 镜像。
- Telegram 故障修复：发送器在 Telegram 返回畸形 JSON 时保留真实 HTTP 状态，并仅对 429/5xx/网络超时继续重试；401、400 等确定性失败不再被误判为可重试。
- 验证：`mvn clean package -DskipTests=false` 通过 316 个测试并校验 Flyway V26；前端 TypeScript/Vite 构建通过，`npm audit --audit-level=moderate` 为 0 漏洞，部署契约检查通过。以 `TRADING_MODE=DISABLED` 实际启动前后端后，390/430/1024/1440 四档宽度、总览/机会/订单/持仓 16 个组合均无横向溢出，Console、失败请求和服务端错误均为 0；启动扫描期间候选接口不再返回 502。

### 2026-08-11 - AI 主选币、移动终端与 Telegram 设计

- 变更摘要：确认 AI 负责候选合约、方向和入场方式，确定性规则负责数据资格、计划计算、最终硬风控和 AI 技术故障时的自动保底；同时确定移动端核心交易导航和基于 `trade_event` 的 Telegram 单向通知边界。
- 影响文件：`docs/superpowers/specs/2026-08-11-ai-primary-mobile-telegram-design.md`、`docs/superpowers/plans/2026-08-11-ai-primary-contract-selection.md`、`docs/superpowers/plans/2026-08-11-telegram-notifications.md`、`docs/superpowers/plans/2026-08-11-mobile-trading-terminal.md`、`docs/CODEBASE_DOC.md`。
- 影响：本次仅固化设计，不改变当前运行行为；合法 AI `WAIT` 不回退，非法/超时 AI 进入规则自动保底，新闻缺失降置信度但不单独阻断；手机端保留监控、订单、平仓和 Kill Switch，Telegram 不接受交易命令。
- 验证：已按用户确认的 AI 权限、移动端范围和 Telegram 通知范围完成规格与三份实施计划的覆盖、占位符、类型命名和 Markdown diff 自检；未运行构建和测试，因为本次没有修改应用代码。当前脏工作区未创建 Git 提交。

### 2026-08-11 - Docker 一键部署与交易风险整改计划

- 变更摘要：将应用镜像改为 Node 20 前端构建、Maven/Java 17 后端构建和 Java 17 JRE 运行的多阶段镜像；Compose 收敛为单应用加 MySQL，增加内部网络、健康依赖、持久卷、非 root 运行、部署校验和一键拉取启动脚本。
- 影响文件：`Dockerfile`、`docker-compose.yml`、`.dockerignore`、`.env.example`、`deploy.sh`、`scripts/build-image.sh`、`scripts/test-deployment.sh`、`README.md`、`docs/TRADING_RISK_REMEDIATION_PLAN_2026-08-11.md` 及部署设计/计划文档。
- 影响：目标服务器不再要求 JDK、Maven、Node 或宿主机 MySQL；只需 Docker、Compose、部署文件和 `.env`。MySQL 不开放宿主机端口，Flyway 自动迁移；LIVE 需要独立人工确认值，脚本不会自动开启交易。
- 验证：`mvn clean package -DskipTests=false` 通过 303 个测试；前端 TypeScript/Vite 构建通过，`npm audit --audit-level=moderate` 为 0 漏洞；部署契约测试完成 RED→GREEN，Compose `DISABLED` 配置通过且未确认 LIVE 被拒绝；真实构建 `okx-quant:verification` 镜像后，隔离 Compose 栈中应用与 MySQL 均为 healthy，首次启动自动执行 23 个 Flyway migration，删除容器并保留数据卷后再次启动识别 schema v23 且无需重复迁移，HTTP 首页返回 200。测试栈及其隔离数据卷已清理，未执行镜像仓库 push。
- 风险整改：新增分级整改方案，覆盖虚拟权益回退、外部仓位误绑定、全局凭据授权、WS 会话竞态、每日风险口径、readiness、数据新鲜度、订单唯一约束、多实例和 REST 协调；本次部署工作不宣称这些交易问题已修复。

### 2026-08-10 - NO_RISK 收敛为不可下单的 Shadow 观察

- 变更摘要：数据库兼容枚举 `NO_RISK` 不再放宽自动交易，而是在自动扫描开始处走独立 Shadow 分支；达到最低观察分且新闻可用的候选只生成并持久化 `WATCH / NO_ENTRY` 计划，不读取交易容量、不预占预算、不创建 PendingOrder、不调用订单确认。
- 最终保护：`OrderConfirmService` 在状态转换和交易所调用前再次拒绝 Shadow 自动订单，防止历史待确认单或旁路调用产生 OKX 写请求；系统控制页同步显示“Shadow 观察/运行中”，不再显示“放宽策略”。
- 观察证据：计划审计 JSON 保存 `strategyVersion`、`signalPool`、`autoTradeEligible`、`dataTimestamp`、确定性 Entry/SL/TP 与 `analysisSource`；Shadow 计划不会写入可交易标志或 TP 子表。尚未实现 MFE/MAE、先触达 `+1R/-1R` 和结果校准。
- AI 稳定性：AI 已退出可执行资金参数，复核请求 temperature 从 `1` 降为 `0.1`，减少相同输入的解释漂移；AI 不可用仍只影响解释，不改变确定性参数。
- 验证：`ContractTradePlanBuilderTest`、`AutoTradeServiceTest`、`OrderConfirmServiceTest`、`TradePlanRecordServiceTest` 通过，覆盖无 PendingOrder、无确认调用、最终 Guard、不可交易记录和审计字段。最终 `mvn clean package -DskipTests=false` 通过 303 个测试和 23 个 Flyway migration；temperature 修改后再次全量测试并重新打包通过；前端 TypeScript/Vite 构建与 `npm audit --audit-level=moderate` 通过（0 漏洞），`git diff --check` 通过。

### 2026-08-10 - 每日硬风控进入最终订单确认链路

- 变更摘要：新增按 owner + UTC 日持久化并加锁的每日风险状态，以及按 owner + entryKey 唯一的入场领取记录；手动和自动风险增加订单都在最终 OKX 写请求前统一领取，避免控制器或策略入口绕过。
- 生效门槛：近期账本必须为 `EXCHANGE_RECENT_LEDGER` 且今日净收益已知；超过每日交易次数、日损阈值、连续亏损或冷却期时阻止新开仓。明确拒单会释放领取，提交超时保留领取等待对账；撤单、保护、reduce-only 和平仓不走入场领取。
- 边界：每日次数和基于账本的日损已可执行；连续亏损/冷却只在单笔 managed close 的 realized PnL、fee、funding 均有真实归因时更新。当前关闭记录经常缺少完整三项，所以系统选择不猜测，不能宣称连续亏损链路已完整投产。
- 影响文件：`DailyRiskService`、两张风险实体/仓库、`OrderConfirmService`、`ClosePositionRecoveryService`、Flyway V23 及对应测试。

### 2026-08-10 - 可交易性优先扫描、自然 20m 与持久化 OI 基线

- 变更摘要：SWAP 24h USDT 成交额使用 `volCcy24h * last`；先按交易资格/成交额过滤，再做方向中性的候选排序，不再先截取涨幅榜 Top 30。20m 只聚合 UTC 自然边界内完整连续的 0/5/10/15 四根 5m 已收盘 K 线。
- OI 语义：新增按 instrument 持久化的 OI 快照（Flyway V22）；首次或无可比较历史时 `openInterestChange = null` 并按中性分处理，不能伪装为 0% 增仓确认。反转信号保留分析输出，但不具备自动交易资格。
- 验证：ticker 金额、先过滤后排名、缺失/乱序 20m、首次 OI UNKNOWN、跨 service 重建读取旧基线等 3 个聚焦测试类通过。新币上市天数、标准 Wilder RSI/ADX 和真实 Public WS freshness 仍未完成。

### 2026-08-10 - 单一保护单责任与成交后对账

- 变更摘要：入场委托不再同时附带 TP/SL 两套退出；只附带按交易计划规整的紧急 SL，并使用稳定 `attachAlgoClOrdId`。完全成交后，生命周期必须先从 OKX active algo 列表确认该 SL，才提交按实际成交量拆分的 TP1/TP2/TP3，不再提交第二张 SL。
- 失败语义：附带 SL 尚未可见时不猜测成功、不补重复 SL，订单进入 `PROTECTION_FAILED` 并由后续生命周期恢复；恢复提交使用按订单和角色稳定的 `algoClOrdId`，会跳过交易所已存在的同角色保护单。
- 验证：`OkxTradeAdapterTest` 与 `AutoTradeLifecycleServiceTest` 共 30 个测试通过，覆盖计划 SL、无随附 TP、完全成交三档 TP、未对账 SL 失败关闭、部分成交按实际数量保护和 NET 模式。

### 2026-08-10 - 风险仓位成为最终订单上限

- 变更摘要：最终自动订单保证金改为 `min(质量调整槽位, 计划风险仓位, 剩余预算, 单仓上限, 可用余额)`；账户权益/余额不可用时禁止新开仓，不再使用固定回退资金生成可执行计划。
- 风控边界：自动确认不再把单仓比例放宽到 100%、资金费率放宽三倍或把最低成交量/信号分清零；计划风控或仓位计算失败直接降级为 `WATCH`。`ContractRiskService` 使用保证金、杠杆和计划止损距离校验真实最大亏损上限。
- 验证：预算、自动执行、订单确认、合约风控、计划构建和待确认订单 6 个聚焦测试类共 85 个测试通过；计划阶段使用 REST 候选时间、确认阶段使用最新订单簿时间计算真实 delay，过期数据失败关闭。Public WS 仍未实现，不能显示为已连接。

### 2026-08-10 - AI 退出可执行计划与价格几何失败关闭

- 变更摘要：交易方向、Entry、SL、TP 和杠杆改由确定性候选/规则生成；AI 只允许补充解释或降级为 `WATCH`，反向建议和价格/杠杆字段不再覆盖订单计划；非法 LONG/SHORT 价格几何不再伪装为 `1.5R`。
- 影响文件：`ContractTradePlanBuilder`、`PositionSizingService` 及对应测试、`docs/superpowers/plans/2026-08-10-trading-analysis-hardening.md`。
- 影响：AI 模型、temperature 或提示词变化不会改变可执行资金参数；多头止损在入场上方、空头止损在入场下方等非法结构直接观察/拒绝。
- 验证：新增测试先分别得到 `OPEN_SHORT`、`OPEN_LONG` 和错误接受的失败结果；修改后 `mvn -q -DskipTests=false -Dtest=ContractTradePlanBuilderTest,PositionSizingServiceTest test` 通过，共 10 个测试。

### 2026-08-10 - 交易分析方案二次收敛

- 变更摘要：将交易优化方案收敛为“可交易性资格、策略内候选池、下单前执行检查”三段流程；AI 仅保留解释、新闻摘要和降级权限，趋势延续、回调延续和各自退出模型分开验证。
- 影响文件：`docs/TRADING_ANALYSIS_OPTIMIZATION_STRATEGY_2026-08-10.md`、`docs/CODEBASE_DOC.md`。
- 影响：补充新币/数据新鲜度/按计划数量评估盘口、价格与 OI 状态、市场宽度、R/组合 heat、Shadow 结果快照和评分校准；每日硬风控提前到 Critical 修复阶段。只修改实施文档，不改变运行代码或交易参数。
- 验证：检查文档标题结构、阶段编号、重复条目、Markdown diff 和 `git diff --check`；未运行应用测试和构建，因为本次没有修改应用代码。

### 2026-08-10 - 交易分析与自动交易整体优化策略

- 变更摘要：基于当前真实交易链路和 `go_binance_futures@20152433ed00c03f567c1aac0164588eaca68391` 源码对照，形成选币、AI 计划、风险仓位、保护单、每日风控、回测和 Telegram/Mattermost 通知的分阶段实施方案。
- 影响文件：`docs/TRADING_ANALYSIS_OPTIMIZATION_STRATEGY_2026-08-10.md`、`docs/CODEBASE_DOC.md`。
- 影响：本次只补充审计和实施文档，不改变运行配置、交易算法、数据库结构或 OKX 写请求；当前状态仍为 `READY FOR DEMO`、`NOT READY FOR LIVE`。
- 验证：核对参考仓库 README、选币预过滤、策略表达式、模拟交易、WS、订单恢复和通知源码；执行 Markdown 链接/格式检查与 `git diff --check`，未运行构建和测试，因为没有修改应用代码。

### 2026-08-09 - Phase 4 最终验证

- 后端 clean package 通过：283 个测试，Flyway V1-V21；前端 TypeScript/Vite 构建和 `npm audit --omit=dev` 通过。
- DISABLED 运行态和浏览器实际导航验证通过：REST `UNBOUND`、Private WS `DISCONNECTED`、Trading Engine `DISABLED`，1440/1280/1024 无横向溢出且 Console/Network 无错误。
- 增加交易写 429/500/连接重置/畸形 JSON 单次失败测试，确认不会自动重发；DISABLED 状态原因统一为 `TRADING_MODE_DISABLED`。
- 无 OKX Demo 凭据，真实订单、撤单、成交、平仓、重启、外部仓位和 Private WS E2E 均未执行；当前结论为 `READY FOR DEMO`。

### 2026-08-09 - Phase 4 Bills、PnL 可靠性与运行态可观测性

- 变更摘要：按 `owner + billId` 保存 OKX 近期账单原始类型；净收益只汇总可追溯系统 fills、对应管理合约的 funding bills，避免把外部成交或 trade bill fee 重复计入；持仓快照失败时 PnL 保持未知。
- 影响：状态栏暴露 Private WS 原因、交易引擎原因、未决订单、未托管仓位和账本同步状态；仓位表显示 `MANAGED/UNMANAGED` 归属；DEMO 账户与订单确认不再标成实盘。
- 边界：账本当前只同步最近 100 条归档账单，不是完整历史；同一 instrument 内系统仓位与外部仓位的 funding 仍不能精确拆分，因此存在 `UNMANAGED` 时引擎阻断。
- 验证：账本状态落库失败、外部 fills/funding 排除、持仓 REST 失败不归零、DEMO 标识等定向测试通过；真实 Demo E2E 未执行。

### 2026-08-09 - Phase 4 Private WS、仓位归属与绑定后恢复

- 变更摘要：实现 JDK Private WebSocket 最小生命周期，按模式隔离 Demo/LIVE URL，登录后订阅 `orders/positions/account`，三频道确认且 REST 订单/仓位对账成功才 READY；新增交易所仓位持久化归属，不能由本地策略/待确认订单 fill 链精确匹配的仓位标记 `UNMANAGED`；账号绑定成功后立即触发 owner 级恢复。
- 影响文件：`OkxWebSocketService`、`OkxPrivateWebSocketProtocol`、`OkxOrderEventService`、`PositionReconciliationService`、`OkxRuntimeReconciliationService`、`TradingStartupRecovery`、`OkxAccountBindingService`、Flyway V20 及对应测试。
- 影响：重复/迟到 WS 订单事件不能让终态倒退，未知 exchange 订单不会自动绑定当前用户；断线/stale/认证或对账失败使引擎 `DEGRADED/BLOCKED`。Private WS 是事件源，REST 仍为恢复权威。
- 验证：协议登录/订阅/失败降级、DEMO URL、断线非热循环、订单重复乱序、仓位 managed/unmanaged、绑定后恢复等定向测试通过；因无 Demo 凭据，真实 Private WS 与 Demo E2E 尚未执行。

### 2026-08-09 - Phase 4 Algo 订单终态对账

- 变更摘要：保护性 Algo 订单从 pending 列表消失后，改用 OKX 专用 `GET /api/v5/trade/order-algo` 按 `algoId/algoClOrdId` 查询终态，并映射 `effective/canceled/order_failed/partially_*`，不再永久停留在占位错误。
- 影响文件：`src/main/java/com/example/quant/okxtrade/OkxOrderGateway.java`、`OkxRestOrderGateway.java`、`OkxCurrentOrderService.java`、`OkxCurrentOrderSyncService.java` 及同步测试。
- 影响：普通订单与 Algo 订单继续使用各自的权威查询接口；未知或部分失败仍保持 `RECONCILING`，不会被猜成已成交。
- 验证：`mvn -q -Dtest=OkxCurrentOrderSyncServiceTest,OkxRestClientTest -DskipTests=false test` 通过，共 12 个测试。

### 2026-08-09 - Phase 3 订单对账、Fill Ledger 与启动门禁

- 变更摘要：普通订单从 pending 消失后按 `ordId/clOrdId` 查询详情；保存 requested/filled/remaining/avgPx，并表达部分成交后撤单和 RECONCILING。真实 fills 按 `owner + instId + tradeId` 唯一键持久化。
- 影响文件：`OkxCurrentOrderSyncService`、`TradeOrderEntity`、`TradeFillLedgerService`、`TradingReadinessService`、`TradingStartupRecovery`、V18/V19 迁移及测试。
- 影响：不再把 pending 列表缺失推断为成交；启动对账完成前最终 OKX 写网关 fail-closed。Algo 终态详情、bills/funding、Private WS 与外部仓位完整归属对账尚未完成。
- 验证：订单部分成交后撤单、fill 去重、启动 BLOCKED/READY 和并发 K 线领取均有定向测试；Phase 3 收口基线为 249 个后端测试全部通过。

### 2026-08-09 - Phase 3 REST 首次扫描协调与禁用守卫

- 变更摘要：`quant.contract.enabled=false` 同时阻断启动/定时扫描和首次候选查询；缓存为空时的并发候选请求共享一次全市场扫描。
- 原因：浏览器演练发现 Dashboard 并发请求在禁用配置下仍触发多次 OKX public REST 全市场扫描，核心数据区被慢请求阻塞。
- 影响：禁用模式不再产生行情请求风暴；单 JVM 内首次填充和定时刷新串行，未引入影响交易写请求的全局限流器。
- 验证：禁用入口与两个并发首次请求均有测试；真实 DISABLED 运行态下 Dashboard 不再触发 instrument rules 扫描。

### 2026-08-09 - Phase 3 明确 DISABLED/DEMO/LIVE

- 变更摘要：使用单一 `TradingMode` 取代运行时 `okx-live-enabled` 布尔判断；Demo 私有 REST 请求由客户端统一添加模拟交易 Header。
- 影响文件：`TradingMode`、`TradingProperties`、`OkxRestClient`、最终交易网关/适配器、运行状态接口、配置、README 和测试。
- 影响：DISABLED 阻断写请求，DEMO 与 LIVE 均可走同一交易链路但协议级隔离；LIVE 不发送 Demo 标记。
- 验证：Mock HTTP Server 已验证 DEMO 私有请求携带 `x-simulated-trading: 1`，LIVE 不携带；真实 Demo E2E 未执行。

### 2026-08-09 - Phase 3 持久化策略决策领取

- 变更摘要：新增 `strategy_decision` 持久化模型，以 `owner + strategyId + instId + timeframe + candleTimestamp` 数据库唯一键领取决策；领取时同时生成稳定 `clOrdId`。
- 影响文件：`src/main/java/com/example/quant/strategydecision/*`、`src/main/resources/db/migration/V17__strategy_decision_idempotency.sql`、对应测试。
- 影响：重复调用或应用重启不能重新领取同一已收盘 K 线；超时遗留的 `PROCESSING` 只转为 `RECONCILING`，不会自动重放交易决策。
- 验证：测试先因缺少实现失败；首次实现又暴露多构造器 Spring 注入失败，已明确标注生产构造器，定向和完整验证结果将在本阶段收口时更新。

### 2026-08-09 - Phase 3 最终验证基线

- 后端：`mvn clean package -DskipTests=false` 通过，249 个测试全部通过并生成可执行 JAR，Flyway V1-V19 完整迁移。
- 前端：`npm run build` 通过（包含 `tsc`），入口共享包为 834.43 KB；仓库未配置独立 lint 脚本。`npm audit --audit-level=moderate` 报告 0 个已知漏洞。
- 运行态：使用 DISABLED、未绑定 OKX 私有账号和禁用合约扫描的本地配置实际启动；状态接口真实返回 REST UNBOUND、两个 WS NOT_IMPLEMENTED 和交易引擎 DISABLED。1440/1280/1024 状态条及 1024 五个主要页面无横向溢出，Console、失败请求和 HTTP 错误列表为空。
- 安全：当前受管配置没有明文生产凭据，但 Git 历史仍命中疑似真实 AI API Key 和非占位数据库口令，必须在外部轮换；`.akshare-venv-linux` 仍有 11,225 个已跟踪文件，ignore 只阻止新增文件。

### 2026-08-09 - 未绑定账号恢复同步失败关闭

- 变更摘要：订单 Gateway 暴露只读凭据可用性；账号未绑定时不查询私有接口、不失效本地订单，并返回 `OKX_ACCOUNT_UNBOUND` 让恢复保持未就绪。
- 边界：定时恢复会重复记录这一失败关闭结果并输出 WARN；这是可观测性噪声，不会发送交易所请求或修改本地活动订单。
- 影响文件：`src/main/java/com/example/quant/okxtrade/OkxOrderGateway.java`、`OkxRestOrderGateway.java`、`OkxCurrentOrderService.java`、`OkxCurrentOrderSyncService.java` 及同步测试。
- 验证：新增测试证明未绑定时不会访问 repository 或 private orders；同步、恢复和底层 Gateway 相关测试通过。

### 2026-08-09 - 真实运行态页面收口

- 变更摘要：当前委托页先读取账号绑定状态，未绑定时不再调用两个必然失败的私有 API，并明确显示不可查询；1024 顶栏压缩运行状态标签，系统控制操作区允许安全换行。
- 原因：真实浏览器验收发现未绑定页面产生 502/Console 错误，且窄桌面末端引擎状态被截断、系统控制存在 13px 横向溢出。
- 影响文件：`frontend/src/pages/CurrentOkxOrderList.tsx`、`frontend/src/components/RuntimeStatusBar.tsx`、`frontend/src/layout.tsx`、`frontend/src/pages/SystemControlPage.tsx`。
- 验证：1440/1280/1024 Dashboard 及 1024 的合约、待确认、当前委托、风控和系统控制页面均无页面横向溢出；Console、请求失败和 HTTP 错误列表为空。

### 2026-08-09 - 修复 instrument rules 服务启动注入

- 变更摘要：为生产使用的单参数构造器明确标注 Spring 注入，保留包内 TTL 构造器只供缓存测试。
- 原因：第二轮规则缓存增加测试构造器后，Spring 面对多个构造器无法选择，完整应用上下文启动失败；单元测试直接 `new` 没有暴露该回归。
- 影响文件：`src/main/java/com/example/quant/okxtrade/OkxInstrumentRulesService.java`。
- 验证：上下文测试从 Bean 创建失败恢复为通过，Flyway V1-V16 完整迁移并完成真实 public REST 启动扫描。

### 2026-08-09 - 页面级按需加载

- 变更摘要：保留登录页和 Dashboard 首屏同步加载，其余资源页和系统页改为 React 原生 `lazy` 按路由加载。
- 原因：主 bundle 的主要体积来自 React Admin/MUI 基础运行时，但所有业务页面原来也被首屏一次加载；页面拆分是无需新增依赖的低风险优化。
- 影响文件：`frontend/src/App.tsx`。
- 验证：生产构建通过，入口 JS 从约 922.80 KB 降至 834.22 KB；共享框架包仍使入口超过 Vite 500 KB 提示，不以手工 vendor 分包掩盖总下载量。

### 2026-08-09 - 禁用用户立即撤销既有会话

- 变更摘要：会话有效性校验除 TTL 外，增加数据库用户仍存在且已启用的检查；失效会话会从内存会话表删除。
- 原因：原实现只在登录时检查 `enabled`，管理员禁用用户后，该用户已有 token 在过期前仍能访问包括交易在内的认证接口。
- 影响文件：`src/main/java/com/example/quant/auth/AuthSessionService.java`、`src/test/java/com/example/quant/auth/AuthSessionServiceTest.java`。
- 验证：回归测试先证明禁用后的 token 仍有效，修复后认证、拦截器和用户服务相关测试通过。

## 核心权威关系

- OKX REST 账户和持仓接口是当前账户资产、仓位的权威数据源。
- OKX REST 当前委托和策略委托接口是活动订单的权威数据源。
- 本地数据库保存交易意图、客户端订单号、状态机、预算预留、事件和恢复记录，不替代交易所事实。
- Private WebSocket 提供 orders/positions/account 实时事件，但只有认证、三个订阅确认和 REST 对账完成才是 READY；Public WebSocket 尚未实现。

## 交易主链路

`OKX REST 行情/K线/订单簿 → ContractSignalAnalyzer → ContractTradeGate/PreConfirmRefresh → ContractRiskService → PendingOrder → OkxTradeAdapter → OKX REST → AutoTradeRecoveryTask/AutoTradeLifecycleService → OKX 订单与仓位同步 → 本地审计和收益估算`

## 变更记录

### 2026-08-09 - 第二轮审计补齐待处理订单用户所有权

- 变更摘要：`pending_order_state` 新增 `user_name` 与索引；创建、恢复、列表、详情、取消和确认均按当前认证用户隔离，后台恢复继续显式切换到持久化的自动交易 owner。
- 原因：OKX 凭据和预算已按用户隔离，但待处理订单原来是全局 Map/全表恢复；知道 UUID 的其他登录用户可查看或操作不属于自己的订单，后台也可能用错误账号恢复。
- 影响：历史记录迁移为 `local-admin`；跨用户访问统一表现为订单不存在。新增所有权测试先证明原实现泄漏，修复后相关 66 个订单、确认、自动交易和恢复测试通过。
- 相关文件：`PendingOrderService`、`PendingOrderEntity`、`V15__pending_order_user_scope.sql`、对应测试。

### 2026-08-09 - 第二轮审计隔离交易订单同步与保护单恢复

- 变更摘要：`trade_order` 新增 `user_name`；订单记录、OKX 当前委托同步、保护单查找/撤销、入场失效和生命周期快照均只处理当前 owner 的记录。
- 原因：OKX 私有查询使用当前用户凭据，但本地 `trade_order` 原来全局共享；一个账号的空委托快照可能把另一个账号的订单标成失效，甚至把同 symbol 的保护单当作自己的进行撤销。
- 影响：历史交易订单迁移为 `local-admin`；新增测试证明 Bob 的同步不会失效 Alice 的订单。相关同步、生命周期和关仓恢复 32 个测试通过。
- 相关文件：`TradeOrderEntity`、`TradeOrderRecordService`、`OkxCurrentOrderSyncService`、`AutoTradeLifecycleService`、`ClosePositionRecoveryService`、`V16__trade_order_user_scope.sql`。

### 2026-08-09 - 第二轮审计统一订单簿与下单合约单位

- 变更摘要：订单簿深度计算删除独立 `ContractSpec` 缓存和“1 张=1 币”失败兜底，直接复用已严格校验和按 symbol 刷新的 `OkxInstrumentRulesProvider`。
- 原因：合约规格请求失败时默认 `ctVal=1` 可能把 OKX SWAP 的可用深度放大，导致流动性门控放行过大的订单；下单与风控使用两套单位模型也会产生漂移。
- 影响：`ctVal/ctValCcy` 不可用时订单簿快照标记不可交易；行情扫描和确认链路均失败关闭。相关 21 个订单簿、扫描和确认测试通过。
- 相关文件：`OrderBookLiquidityService`、`OrderBookLiquidityServiceTest`、`OkxContractScannerTest`。

### 2026-08-09 - 第二轮审计停止接管外部仓位并关闭新闻未知放行

- 变更摘要：生命周期恢复不再为“OKX 有持仓但本地无待处理订单”的外部仓位自动挂 2% 止损和分批止盈；新闻源无返回或异常时统一降级为 `WAIT`，严格与放宽候选模式都禁止自动提交。
- 原因：本地没有订单归属就无法证明仓位由本系统创建，自动挂单会擅自改变用户在 OKX App 的手工仓位；新闻数据未知也不能作为 `allowTrade=true` 的硬风险输入。
- 影响：外部仓位仍由 OKX REST 权威快照展示，但系统只管理自身持久化订单关联的生命周期；数据源恢复后候选可在后续扫描自然恢复，不需要复杂重试框架。
- 相关文件：`AutoTradeLifecycleService`、`ContractNewsRiskAnalysis`、`ContractSignalAnalyzer`、`OkxContractScanner`、`ContractTradePlanBuilder`、`AutoTradeService` 及对应测试；相关 57 个测试通过。

### 2026-08-09 - 第二轮审计区分未知已实现收益与零收益

- 变更摘要：关闭记录没有 `realizedPnl/fee/fundingFee` 对账值时，收益汇总返回 `REALIZED_PNL_UNAVAILABLE` 和空已实现值；Dashboard 显示 `-` 与警告口径，不再显示绿色 `0`。
- 原因：当前生产链路只按 OKX 持仓消失确认关闭，尚未拉取 fills/bill；三个空字段不能代表真实净收益为零。
- 影响：OKX 当前持仓的 `upl` 仍作为交易所未实现 PnL 展示；已实现、手续费、资金费和净 PnL 只有在字段实际写入后才参与汇总。
- 相关文件：`AutoTradeProfitService`、`AutoTradeProfitServiceTest`、`Dashboard.tsx`。

### 2026-08-09 - 第二轮审计关闭资金费率零值兜底

- 变更摘要：候选扫描获取不到 OKX 资金费率或字段缺失时，不再将未知值当作 `0` 继续评分，而是跳过该 symbol；单个 symbol 失败不终止整轮扫描。
- 原因：`0` 是看似安全但未经交易所确认的值，会绕过异常资金费率硬门槛并制造可交易候选。
- 影响：网络或字段异常时宁可缺少候选也不使用假资金费率；K 线不足原本已返回 `NO_TRADE`，行为保持不变。
- 相关文件：`OkxContractScanner`、`OkxContractScannerTest`。

### 2026-08-09 - 第二轮审计收紧自动交易停止竞态

- 变更摘要：`confirmAuto` 在进入确认和最终 OKX 提交前各检查一次持久化运行开关；用户停止自动交易或开启紧急停止后，尚未发出的自动订单会被拒绝并释放预留预算。
- 原因：扫描轮次只在开始时检查开关，用户在计划生成或风控检查期间停止策略，原轮次仍可能继续提交。
- 影响：停止操作与已进入网络发送的请求无法实现跨进程原子取消，但提交前窗口已缩小；已经发出的未知请求仍按 `clOrdId` 对账，不能当作失败重发。
- 相关文件：`OrderConfirmService`、`OrderConfirmServiceTest`。

### 2026-08-09 - 第二轮审计移除虚假全局风控通过状态

- 变更摘要：`/risk/status` 不再用固定账户、行情延迟和 `websocketConnected=true` 的 `safeDefaults` 计算“通过”；交易禁用或紧急停止时返回真实阻断，实盘开启但没有具体订单时返回 `NOT_EVALUATED / ORDER_CONTEXT_REQUIRED`。
- 原因：订单风控依赖具体保证金、杠杆、入场价、止损、流动性等上下文，不能用样例参数代表整个系统已通过风控；Public/Private WS 当前也确实未实现。
- 影响：Dashboard 和账户页会显示“待订单预检”，只有实际确认订单时的同步 REST 订单簿、账户与计划参数检查才决定订单是否放行。
- 相关文件：`RiskController`、`RiskControllerTest`、`formatters.ts`、`RiskCommandPanel.tsx`、`AccountRiskPage.tsx`。

### 2026-08-09 - 第二轮审计限制页面轮询重叠

- 变更摘要：合约页五秒聚合刷新和 K 线弹窗轮询均增加单飞保护；K 线 REST 刷新从每秒一次调整为每五秒一次。
- 原因：响应超过轮询周期时原实现会叠加请求；一个 K 线弹窗每分钟最多触发约 60 次完整 candles 请求，缺少 Public WS 时没有必要以 1 秒重复拉取已收盘 K 线。
- 影响：页面仍保持近实时人工观察能力，同时降低浏览器多标签页对 OKX public/private REST 的放大；交易提交接口不参与自动重试。
- 相关文件：`ContractCandidateList.tsx`。

### 2026-08-09 - 第二轮审计持久化平仓未知状态

- 变更摘要：手动平仓改为先保存 `CLOSE_SUBMITTING + closeClOrdId` 再调用 OKX；异常响应保存为 `CLOSE_SUBMIT_UNKNOWN`，恢复任务按真实持仓继续对账，同一仓位存在未决平仓时拒绝重复提交。
- 原因：原实现收到 OKX ACK 后才写平仓记录；请求超时或进程中断会丢失客户端订单号和恢复线索，并允许用户再次提交平仓。
- 影响：HTTP 超时不再等同于平仓失败；Exchange 持仓仍是最终确认依据。未知记录在持仓未消失时保持未决，不自动推断失败。
- 相关文件：`PositionCloseService`、`ClosePositionRecoveryService`、`PositionCloseServiceTest`、`formatters.ts`、`StatusChip.tsx`。

### 2026-08-09 - 第二轮审计封闭提交崩溃窗口

- 变更摘要：手动与自动待确认订单在提交前都持久化稳定 `clOrdId`；恢复任务将重启遗留的 `SUBMITTING` 转入查询对账，无法确认不存在的订单不再自动释放预算；自动交易在存在 `SUBMITTING/UNKNOWN_SUBMIT_STATUS` 时停止新开仓。
- 影响文件：`src/main/java/com/example/quant/order/PendingOrder.java`、`src/main/java/com/example/quant/order/PendingOrderService.java`、`src/main/java/com/example/quant/task/AutoTradeRecoveryTask.java`、`src/main/java/com/example/quant/agent/execution/AutoTradeService.java` 及对应测试。
- 影响：进程在 OKX 请求前后崩溃时仍以同一客户端订单号查询；系统选择 at-most-once 偏好的失败关闭，不把“暂未找到”自动当成“从未提交”。未知状态会锁住后续自动开仓，需查询恢复或人工核对。
- 验证：新增测试分别在原实现上证明手动订单缺少 `clOrdId`、`SUBMITTING` 不恢复、未知订单释放预算以及下一轮重复开仓；修复后相关 50 个测试通过。

### 2026-08-09 - 第二轮审计校验并刷新合约元数据

- 变更摘要：规则服务验证响应 `instId/instType/ctVal/ctValCcy/lotSz/minSz/tickSz`，按 symbol 缓存一小时后重新加载，刷新失败时本次调用失败关闭且后续调用可再次尝试。
- 影响文件：`src/main/java/com/example/quant/okxtrade/OkxInstrumentRulesService.java`、`src/test/java/com/example/quant/okxtrade/OkxInstrumentRulesServiceTest.java`。
- 影响：缺字段、错误 instrument 或错误类型不再被 record 默认值掩盖；缓存不会永久陈旧，不同 symbol 仍使用独立键。
- 验证：缺字段和错误类型测试先在原实现上失败；TTL 测试证明过期后重新请求。`mvn -DskipTests=false -Dtest=OkxInstrumentRulesServiceTest test` 通过，共 4 个测试。

### 2026-08-09 - 第二轮审计移除 Base64 凭据旁路

- 变更摘要：删除账号绑定服务的包内 Base64 构造路径和独立 Base64 codec，服务测试改为实际使用 AES-256-GCM 并断言存储值为 `v1:` 密文。
- 影响文件：`src/main/java/com/example/quant/account/OkxAccountBindingService.java`、删除 `src/main/java/com/example/quant/account/Base64OkxCredentialCodec.java`、`src/test/java/com/example/quant/account/OkxAccountBindingServiceTest.java`。
- 影响：生产和测试的账号绑定路径统一使用注入的加密 codec；旧 Base64 数据仍由 `AesGcmOkxCredentialCodec.decode` 兼容读取并迁移，不再提供新的 Base64 写入入口。
- 验证：新增存储密文断言先在旧构造器上失败；`mvn -DskipTests=false -Dtest=AesGcmOkxCredentialCodecTest,OkxAccountBindingServiceTest,OkxRestClientTest test` 通过，共 11 个测试。

### 2026-08-09 - 第二轮审计关闭保护单元数据兜底

- 变更摘要：生命周期补挂止损止盈时，合约规则缺失或加载失败不再回退虚构精度，而是标记保护失败并要求恢复重试或人工处理。
- 影响文件：`src/main/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleService.java`、`src/test/java/com/example/quant/agent/lifecycle/AutoTradeLifecycleServiceTest.java`。
- 影响：已成交仓位不会使用错误的 `lotSz/minSz/tickSz` 提交保护单；代价是元数据不可用期间保护单失败关闭，必须依赖后续恢复或人工处置。
- 验证：新增测试先在原实现上观察到保护单被错误提交；修改后 `mvn -DskipTests=false -Dtest=AutoTradeLifecycleServiceTest test` 通过，共 17 个测试。

### 2026-08-09 - 第二轮审计修正市价单确认语义

- 变更摘要：OKX 下单接口返回订单号后，市价单与限价单均保持 `SUBMITTED`，只有后续订单查询确认 `filled` 才视为成交。
- 影响文件：`src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java`、`src/test/java/com/example/quant/okxtrade/OkxTradeAdapterTest.java`。
- 影响：下单 ACK 不再提前消耗预算或产生“已成交”事件；生命周期服务继续通过 OKX 订单/仓位查询确认完全成交或部分成交。
- 验证：新增断言先在原实现上以 `filled=true` 失败；修改后 `mvn -DskipTests=false -Dtest=OkxTradeAdapterTest test` 通过，共 12 个测试。

### 2026-08-09 - 第二轮审计将交易禁用保护下沉到 REST 写网关

- 变更摘要：所有 OKX 资金变更请求在 `OkxRestOrderGateway` 最终出口统一检查实盘开关，未显式启用时失败关闭。
- 影响文件：`src/main/java/com/example/quant/okxtrade/OkxRestOrderGateway.java`、`src/test/java/com/example/quant/okxtrade/OkxRestOrderGatewayTest.java`、`src/test/java/com/example/quant/crypto/OkxRestClientTest.java`。
- 影响：策略入场、手动入场、设置杠杆、保护单、撤单、撤销策略单和一键平仓均不能通过直接调用 Gateway 绕过交易禁用；查询接口不受影响。
- 验证：先验证新增测试在原实现上失败，再下沉 Guard；`mvn -DskipTests=false -Dtest=OkxRestOrderGatewayTest,OkxRestClientTest test` 通过，共 4 个测试。

### 2026-08-09 - 默认禁用实盘与可信运行状态

- 变更摘要：最终下单/平仓适配器强制检查实盘开关，仓库默认改为交易禁用；新增 REST、Public WS、Private WS、策略引擎和交易引擎运行状态接口与全局状态带。
- 影响文件：`src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java`、`src/main/java/com/example/quant/controller/SystemControlController.java`、`src/main/java/com/example/quant/crypto/OkxWebSocketService.java`、`frontend/src/components/RuntimeStatusBar.tsx`、`frontend/src/layout.tsx`、配置和测试文件。
- 影响：`okx-live-enabled=false` 不再只是展示字段，而会阻断最终交易请求；尚未接入的 WebSocket 明确显示 `NOT_IMPLEMENTED`，不会伪造 Connected 或 Demo。
- 验证：控制器 6 个定向测试通过；浏览器在 1440、1280、1024 三档验证状态真实性、无横向溢出、console/network 无错误。

### 2026-08-09 - Industrial 交易终端与依赖安全升级

- 变更摘要：首页第一信息层改为权益、可用余额、今日/累计/未实现 PnL 和持仓；统一近黑背景、1px 边界、弱圆角、无渐变阴影和 tabular numbers；升级兼容依赖安全补丁。
- 影响文件：`frontend/src/pages/Dashboard.tsx`、`frontend/src/theme.ts`、`frontend/src/components/glass.ts`、`frontend/src/layout.tsx`、`frontend/package.json`、`frontend/package-lock.json`。
- 影响：1440/1280 使用六列核心指标，1024 使用三列；无 token 时不再发送无效 logout 请求。
- 验证：`npm run build` 通过；完整 `npm audit` 为 0 漏洞；三档 Playwright 验收通过。

### 2026-08-09 - 自动交易硬风控不可绕过（历史，已被 2026-08-10 Shadow 语义取代）

- 变更摘要：放宽模式只放宽候选评分门槛，不再绕过执行前规则风控；自动交易不再把 AI 计划杠杆抬高到用户下限，订单超出实时盘口允许深度时直接拒绝。
- 影响文件：`src/main/java/com/example/quant/order/OrderConfirmService.java`、`src/test/java/com/example/quant/order/OrderConfirmServiceTest.java`
- 影响：当时的放宽模式仍可下单；当前 `NO_RISK` 已改为不创建订单的 Shadow，仅 `STRICT` 自动订单进入这些执行硬风控。
- 验证：`OrderConfirmServiceTest` 14 个测试通过；相关精度、规则和订单测试合计 26 个通过。

### 2026-08-09 - 下单数量向下规整并在规则缺失时关闭交易

- 变更摘要：合约数量统一按 `lotSz` 向下规整；规整后低于 `minSz` 时拒绝下单；无法从 OKX 获取 `ctVal/lotSz/minSz/tickSz` 时不再用默认值继续交易。
- 影响文件：`src/main/java/com/example/quant/okxtrade/OkxInstrumentRules.java`、`src/main/java/com/example/quant/okxtrade/OkxInstrumentRulesService.java`、`src/main/java/com/example/quant/okxtrade/OkxTradeAdapter.java`、对应测试文件。
- 影响：下单数量不会因四舍五入或最小量兜底而超过预算和风险计算值；规则接口异常时交易失败关闭。
- 验证：`OkxTradeAdapterTest` 与 `OkxInstrumentRulesServiceTest` 13 个测试通过；整套后端 219 个测试通过。

### 2026-08-09 - 策略只使用已收盘 K 线

- 变更摘要：解析 OKX K 线响应时校验 `confirm=1`，未收盘或字段不完整的 K 线不再进入指标和信号计算。
- 影响文件：`src/main/java/com/example/quant/crypto/OkxMarketService.java`、`src/test/java/com/example/quant/crypto/OkxMarketServiceTest.java`
- 影响：5m、1H、4H、1D 以及由 5m 聚合的 20m 策略输入均排除当前形成中的 K 线；这只解决未收盘数据变化问题，不等于同一根已收盘 K 线具备持久化幂等。
- 验证：`OkxMarketServiceTest` 通过；扫描器旧夹具补齐 `confirm=1` 后 3 个扫描测试通过。

### 2026-08-09 - 清理明文配置并加密 OKX 凭据

- 变更摘要：移除仓库中的数据库明文密码和默认管理员密码，新增基于 Java 标准库的 AES-256-GCM OKX 凭据加密，并在读取旧 Base64 数据后自动迁移。
- 影响文件：`src/main/java/com/example/quant/account/AesGcmOkxCredentialCodec.java`、`src/main/java/com/example/quant/account/OkxAccountBindingService.java`、`src/main/java/com/example/quant/config/AuthProperties.java`、`src/main/resources/application.yml`、`src/main/resources/application.properties`、`docker-compose.yml`、`README.md`
- 影响：首次管理员初始化要求 `QUANT_AUTH_PASSWORD`；绑定或读取 OKX 凭据要求 `QUANT_CREDENTIAL_MASTER_KEY` 为 Base64 编码的 32 字节密钥；Git 历史还确认出现过非占位数据库密码和疑似真实 AI API Key，部署方必须轮换对应凭据。
- 验证：AES-GCM、账号绑定、认证和应用上下文 13 个定向测试通过；整套后端 219 个测试通过。

### 2026-08-09 - 隔离认证测试上下文

- 变更摘要：认证拦截器测试在每个用例结束后清理线程用户上下文，避免整套测试因上一用例遗留的 `admin` 身份而串扰。
- 影响文件：`src/test/java/com/example/quant/auth/AuthInterceptorTest.java`、`docs/CODEBASE_DOC.md`
- 影响：仅影响测试隔离；生产拦截器仍在请求完成时清理上下文。
- 验证：`mvn -Dtest=AuthInterceptorTest,AutoTradeBudgetServiceTest,AutoTradeProfitServiceTest test` 通过，共 18 个测试。
# 2026-08-11 AI 主选、Telegram 与移动终端

- `AiContractSelectionService` 在硬规则准入池内调用 OpenAI-compatible JSON API，区分 `AI_PRIMARY`、`AI_WAIT`、`RULE_FALLBACK`；自动交易以 AI 排名为主，规则重新计算方向一致的止损/止盈、杠杆、风险仓位并执行硬风控。
- 新闻 `UNKNOWN` 降低可信度但不再单独硬阻断；`HIGH/CRITICAL` 仍禁止交易。
- `notification_outbox` 对关键 `trade_event` 做数据库级去重和重启恢复；Telegram 网络发送与交易事务隔离，失败不改变订单状态。
- 前端增加 `/positions`、移动底部导航和移动持仓展开行；机会页展示完整候选池，不再错误地只展示已有持仓。
- Telegram 配置与验收见 `docs/TELEGRAM_NOTIFICATION_GUIDE.md`；移动/AI/通知设计与实施边界见 `docs/superpowers/specs/2026-08-11-ai-primary-mobile-telegram-design.md`。

# 2026-08-12 自动交易可见性与 AI 软规则修正

- 自动交易记录改按自增 `id` 倒序，避免历史时区错误数据遮住当前扫描；JDBC 会话统一使用 UTC，历史错误时间不做破坏性重写。
- 自动交易表同时展示原始分、最终排序分、规则信号、AI 来源/置信度/入场模式和 AI 选择原因，避免把原始分误认为最终准入分。
- AI 提示中的规则方向、规则评分和诊断标签明确为辅助证据，不再作为 AI 的预设否决；AI 仍可合法返回 `WAIT`，硬风控、流动性、仓位和 Risk Engine 不放宽。
- `/system/runtime-status` 同时校验页面运行时开关和 `QUANT_AGENT_AUTO_TRADE_ENABLED`；配置层关闭时返回 `AUTO_DISABLED_BY_CONFIG`，不再显示虚假的 `AUTO_RUNNING/AUTO_ENABLED`。
- AI 与通知密钥只保存在根目录 `.env`，资源目录 `.env` 已移除，Compose 与打包流程不会读取或携带该文件。
- `NO_RISK` 明确显示为 `SHADOW_RUNNING / SHADOW_ONLY`，不再显示可执行自动交易；只有 `STRICT` 会进入 AI 主选和订单执行链路。
- AI 已选择交易但计划等待回踩/复测时，记录改为 `no_order_submitted` 并关联 AI 首选合约；不再误报为规则准入失败。规则评分只参与 AI 输入和候选排序，AI 主选之后仍保留账户、新闻、流动性、仓位及风险硬校验。
- AI 主选 `MARKET` 计划不再把顺序扫描的处理耗时误当作行情陈旧；自动市价单改在最终提交前使用实时订单簿校验新鲜度、价差、深度及计划价偏差，偏差超过 `quant.agent.pre-confirm-refresh.max-price-deviation-pct`（默认 0.25%）时失败关闭。规则兜底计划仍保留原有候选时间校验。
- AI 主选已通过自动交易硬准入后，规则总分不再在计划风控和最终确认阶段重复硬拒绝；低规则分仍会提升风险等级并将杠杆上限降至 1 倍。手动订单和规则兜底路径继续执行规则分门槛，其他资金、止损、亏损、新闻、Funding、成交量与实时盘口硬限制不变。
- 稳定 `clOrdId` 在历史 JDBC 时区语义变化后可能命中旧决策但自然键时间不一致；决策认领现在先按稳定 `client_order_id` 去重，并校验 owner/strategy/instrument/timeframe，避免启动扫描因唯一键冲突整轮失败。
- 验证：相关回归测试通过；`mvn clean package -DskipTests=false` 共 334 个测试通过；前端 `npm run build`（含 TypeScript）通过。

### 2026-09-13 - Canonical Executable Plan 与交易所约束编译

- 变更摘要：新增不可变 `TradeIntent`、确定性 `CanonicalPlanCompiler`、`CanonicalExecutablePlan` 及只读 Shadow 比较；入场前统一编译 Entry/SL/全部 TP legs，无法满足 `lotSz/minSz` 时失败关闭，禁止静默合并或放大。
- 影响文件：`src/main/java/com/example/quant/tradeplan/TradeIntent.java`、`CanonicalExecutablePlan.java`、`CanonicalPlanCompiler.java`、`CanonicalPlanShadow.java`、`src/main/java/com/example/quant/order/OrderConfirmService.java`、`PendingOrder*.java`、`AutoTradeLifecycleService.java`、`OkxTradeAdapter.java`、`src/main/resources/db/migration/V34__canonical_executable_plan_snapshot.sql`。
- 影响：Spring 生产链路在 OKX 入场前绑定 canonical snapshot；接受和拒绝结果都保留 intent/compiled 审计快照；Lifecycle/Adapter 消费同一 TP/SL 语义，历史 1.8R target 不会被改写成 3R；旧快照缺失时生产保护路径失败关闭。
- 验证：`CanonicalPlanCompilerTest` 覆盖 long/short、1.8R、最小数量拒绝、lot/tick 舍入、余量、部分成交数量缩放及 JSON 重启快照；`CanonicalPlanShadowTest`、`OkxTradeAdapterTest`、`AutoTradeLifecycleServiceTest` 定向回归通过；`mvn -DskipTests=false test` 共 428 个测试通过，Spring/Flyway H2 上迁移至 v34；`mvn -DskipTests package` 与 `git diff --check` 通过。

### 2026-09-18 - Wave 5 Historical Candidate Snapshot 与离线 Outcome Evaluation

- V2 生产扫描结果继续由 `OkxContractScanner.scanV2()` 决定；`ContractAnalysisService` 仅将已产生的候选列表通过 observational side path 交给 `ContractCandidateScoreRecorder`。同一次扫描使用 `AgentScanRunEntity.id` 作为 durable evaluation cohort，V2 snapshot 的 `scoringVersion` 当前为 `V2_WAVE_4_2`。
- V2 snapshot 扩展现有 `agent_contract_candidate_score`，保存 scan/rank/instId/reference price、`normalizedScore`、`rankingScore`、available weight、coverage ratio、七个维度分数、已存在的 OI/Funding/RS/ATH/市场质量原始 feature 和 DataQuality。缺失维度保持 SQL `NULL`；snapshot 保存后不由未来行情或当前 calculator 重算覆盖。
- Flyway V38 新增 `candidate_future_outcome`。每个 snapshot 建立 1h、4h、24h 三个 horizon；`(snapshot_id, horizon_minutes)` 唯一约束保证幂等，Outcome 与 immutable snapshot 分离。Outcome 保存 future price、raw return、directional return、raw max upside/downside、directional MFE/MAE、状态和错误原因。
- Outcome reconciliation 由定时任务从数据库扫描 `PENDING/PARTIAL/FAILED` 且已成熟的记录；以现有 `OkxMarketService` 的已确认 15m candles 一次按 instId 取数，并从同一批 candles 派生 horizon price 和 MFE/MAE。缺 candle/API 错误不写零收益，而是保留 `PARTIAL`/`FAILED` 及原因，后续可重试；重启不依赖内存 timer。
- `HistoricalCandidateEvaluationService` 只读 snapshot/outcome，按 scoring version 隔离，提供 1~5、6~10、11~20、21+ rank bucket，LOW/MEDIUM/HIGH coverage bucket，以及七个 dimension 的 0~20、20~40、40~60、60~80、80~100 bucket。统计包含样本数、样本不足标记、raw/directional 平均与中位收益、hit rate、平均 ranking score、MFE、MAE；NULL 维度不进入该维度统计。离线结果没有返回生产 scanner、交易、风险或保护链路。
- Snapshot 在 T0 只使用 scanner 当时已有的 candidate/V2 结果；未来 candles 只由 Outcome reconciliation 读取。该设计保持 production selection 与 offline evaluation 单向隔离，也不改变 Wave 4.2 评分公式、权重或 confidence。

### 2026-09-18 - Wave 5.1 Historical Evaluation 数据完整性加固

- 时间语义统一使用 UTC `Instant`。snapshot 的 T0 是 scanner 完成时的 `createdAt`；`maturityAt = T0 + horizon`。OKX 15m candle 的 timestamp 是开盘时间，因此只有 `open >= T0` 且 `open + 15m <= maturityAt` 的已确认完整 candle 才能进入窗口。
- 例如 T0 为 `10:07`、1h maturity 为 `11:07` 时，使用 `10:15`、`10:30`、`10:45` 三根完整 candle；排除包含 T0 前行情的 `10:00` 和超出 maturity 的 `11:00`。`futurePrice` 是最后一根 eligible candle 的 close，`futureTimestamp` 是其 open timestamp，`observedAt` 是该 bar 的结束时间。
- MFE/MAE 在同一完整窗口内扫描所有 eligible candle 的 high/low；内部缺 bar 保持 `PARTIAL` 并按 retry metadata 重试。`COMPLETE` outcome 在正常 reconciliation 中不可覆盖。`PENDING/PARTIAL/FAILED` 由 DB 查询恢复，批量、同 instrument 复用 candle 请求，并有 retry backoff；`UNAVAILABLE` 不作为可评估样本。
- V39 不修改 V38，新增 `retry_count`、`last_attempt_at`、`next_retry_at`、`observed_at`、`window_start`、`window_end` 和 retry 索引；数据库与 domain 均限制 horizon 为 60/240/1440 分钟。reconciliation 提供 pending/partial/failed/complete/stuck 及 oldest pending age diagnostics。
- Evaluation 只接受 `COMPLETE` outcome，并按 `scoringVersion` 与完整 `AgentScanRunEntity` cohort 隔离；`expected candidateCount` 与持久化 snapshot 数不一致的 cohort 不参与统计。raw、directional、excursion 使用独立有效样本分母，NULL direction 不计 directional miss。

### 2026-09-18 - Wave 5.2 Historical Evaluation Burn-in 就绪性审计

- 变更摘要：审计并加固 `HistoricalCandidateOutcomeReconciliationService` 的 15m canonical full-bar 窗口；只有完整、连续、已确认的预期 bar 集合才允许 outcome 从 `PARTIAL` 进入 `COMPLETE`，防止单根 candle 被错误当作完整 1h/4h/24h horizon。新增只读 burn-in runbook，覆盖 Flyway V38/V39、cohort 完整性、horizon 状态、retry、stuck、版本隔离和 75 小时 candle 覆盖风险。
- 影响文件：`src/main/java/com/example/quant/agent/evaluation/HistoricalCandidateOutcomeReconciliationService.java`、`src/test/java/com/example/quant/agent/evaluation/HistoricalCandidateOutcomeReconciliationServiceTest.java`、`docs/HISTORICAL_EVALUATION_RUNBOOK.md`。
- 影响：不改变 V2 score、权重、coverage、scanner 排名、AutoTrade、Risk、Protection 或交易执行；缺 bar 继续保留为可重试 `PARTIAL`，不会写零收益或伪造 future price。
- 验证：新增“单根 bar 不得完成 horizon”回归测试；focused reconciliation tests 通过；完整 test/verify、diff check 结果以本轮 Completion Report 为准。生产 `flyway_schema_history` 和真实 outcome 数据仍需按 runbook 只读核验。
