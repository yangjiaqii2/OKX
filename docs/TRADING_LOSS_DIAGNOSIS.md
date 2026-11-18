# 模拟交易亏损诊断（第一轮，仅诊断）

诊断开始：2026-09-06；报告完成：2026-09-07，Asia/Shanghai。金额单位：USDT。本轮只新增本报告，未修改交易逻辑、参数、配置、数据库或运行开关，未发送订单、撤单、平仓请求。

## 1. 结论与证据强度

**现有可核实的自动交易确实亏损，但样本只有 3 笔，且存在实际退出被程序阻断、平仓漏入账、行情环境不一致等问题。这批结果不能证明策略长期没有正期望，也不能用于直接调整止损、止盈或指标。**

本次续查的运行库中，最近成交仍为 **2026-08-13 11:08:36**。2026-09-05 有 346 条 `SKIPPED / auto_trade_runtime_switch_off`，没有新增成交。因此，下文分析的是能找到完整事实链的 **8 月 12—13 日故障案例**，不是擅自把它当作“昨天”的交易。若用户所指的是其他部署、账户或交易时段，本报告不能代替那个样本。

### Top 5

|优先级|原因/问题|置信度|本次证据与因果边界|
|---|---|---|---|
|P0|全局 readiness 门禁同时阻断已有仓位的超时平仓和保护单补交|高：实际发生；中：可避免亏损金额|XAU 第二笔 468 次、BTC 935 次超时平仓失败，均为“交易引擎尚未完成启动对账；OKX写请求未发送”。首次失败后分别又持仓 243.40、515.67 分钟。`OkxRestOrderGateway#closePosition/requireTradingEnabled` 与 `AutoTradeLifecycleService#submitMaxHoldClose` 完整解释阻断。不能断言每次 readiness 变坏均由 WS 断线造成，历史底层状态日志不足。|
|P0|止损子订单未进入本地成交/归属链，收益和每日风控漏算|高|XAU 止损真实平仓 717 张、14 笔 fill，毛亏 23.2472、平仓手续费 1.571664；本地缺这些 fills。`reconcileMissingAlgo` 只更新 algo 状态，没有跟进普通子订单 fills；`naturalCloseRecord` 可在收益未知时标 CLOSED。不是亏损的制造原因，而是掩盖亏损、污染风控的程序错误。|
|P1|分析使用无 Demo 标记的公开行情，执行在 Demo，不能视为同一价格路径|高：代码及价格差异；中：损益影响|`OkxRestClient#publicGet` 不调用 `applyTradingMode`，私有请求调用。XAU 第二笔普通行情 MFE 1.074R，Demo 行情仅 0.828R；普通行情最高 4450.5，Demo 4442.7，实际 TP1=4448.2。误用前者会得出错误的“TP 漏触发”结论。|
|P1|短时退出与以 R 为单位的较远目标存在时间尺度冲突，且故障使实际持有期失控|高：当前行为；低至中：策略负期望|当前生命周期配置亏损 30 分钟可平仓、最大 60 分钟；横盘收紧 TP 在 3 小时以后。三笔 Demo MFE 均不足 1R，计划 RR=1.8，实际挂单却按 1R/2R/3R 分批。尚不能判定应放宽时间、收紧止盈还是改变入场，必须先恢复正确执行再验证。|
|P2|手续费显著放大弱毛收益，同时实际入场是 AI 主选的 NEUTRAL 候选，尚无 Alpha 证据|高：账单/选币事实；低：长期策略结论|3 笔毛收益合计 -27.875680，手续费 -8.538439，资金费 +0.475246，净 -35.938873。三笔均 `AI_PRIMARY`、规则 `NEUTRAL`。手续费占净亏损 23.758%，但不是“毛利润为正被手续费吃成负数”。|

不要把表中五项的金额相加：执行、行情、Entry/Exit 是相互作用的反事实因素；只有毛收益、手续费、资金费是可严格相加的会计分解。

## 2. 数据范围、运行版本与取证方式

1. 先读 `docs/AI_REPO_MAP.md`，仅用于导航；以下结论重新打开源码核实。工作区原有大量修改，本轮未恢复或覆盖。
2. 当前容器 `okx-app-1`、`okx-mysql-1` 均在运行；只读检查 `TRADING_MODE=DEMO`。没有启动另一套带定时交易任务的应用。
3. 容器 `/app/app.jar` 与续查时已有 `/tmp/okx-loss-running.jar` SHA-256 一致：`6cb4f2a5656511236ed7937d4a9e02562bf0175023a7b759c562abe777c374c5`。六个关键类（生命周期、行情 K 线、REST 网关、账本汇总、平仓恢复、自动执行）的 jar 字节与 `target/classes` 一致；另用 `javap -c -p` 核实网关 readiness 分支。**这不是对全部当前源码与历史每次部署一致性的证明。**
4. MySQL 查询使用 `SET SESSION TRANSACTION READ ONLY; START TRANSACTION READ ONLY; ... COMMIT;`。实际表名通过运行库查询确认，尤其为 `pending_order_state`，不能照导航猜作 `pending_order`。
5. 续查复核：`trade_fill` 29 行，时间 8 月 12 日 13:39:30 至 8 月 13 日 11:08:36；`account_bill` 45 行（43 TRADE、2 FUNDING）；`trade_order` 18 行；已提交 pending 4 笔。`auto_trade_record` 中 `ENTRY_SUBMITTED=3`、`REJECTED=12`、`WAITING_ENTRY=96`，其余大量 SKIPPED，**扫描次数不等于交易次数**。
6. `agent_scan_run`、`agent_contract_candidate_score` 的精确 `COUNT(*)` 均为 0。数据库没有可用的完整候选历史/K 线历史；选中计划 JSON 有部分当时指标与理由。
7. 取回/复核 OKX Demo GET：`account/positions-history`、`account/positions`、`trade/order-algo`；历史持仓的 4 笔本系统期间交易与 bills 一致，当前持仓为空。历史列表另有 2025 年 JUP，不属于本次系统交易，剔除。XAU 平仓 `fills-history` 本轮刷新网络失败，保留上轮同日成功取证结果，并以本轮成功的历史持仓、SL 子订单号交叉验证。未将失败查询解释为无成交。
8. 历史容器日志查询（8 月 12—13 日）无输出；不能恢复当时 WS 断线/对账失败的底层原因。数据库 `trade_event` 是本报告的主要持久化事件证据。

取证文件在 `/tmp/okx-loss-evidence/`：`pending.json`、`orders.json`、`fills.json`、`bills.json`、`records.json`、`plans.json`、`decisions.json`、`events.json`、`position-history.json`、`xau-sl.json`、`xau-tp1.json`、`xau-close-history.json`、`positions-now.json`、`XAU-USDT-SWAP[-demo]-1m.json`、`BTC-USDT-SWAP[-demo]-1m.json`。临时文件会随环境清理；本报告嵌入关键统计、标识和 SQL，避免结论仅依赖临时文件。原始 pending 有确认字段，不应把整份原始导出公开提交。

已有 `/tmp/okx_loss_metrics.py` 的结果只作为辅助，已重新用交易所历史持仓和 bills/fills核对：它原先根据 bills 反推的 XAU 第二笔退出价 **4384**，现在有历史持仓 `closeAvgPx=4384` 的独立支持；ETH 也不能简单沿用脚本标签算入自动样本。

OKX 请求环境和接口字段另参照 [OKX 官方 API 文档](https://www.okx.com/docs-v5)：Demo 请求使用 `x-simulated-trading: 1`。撮合/触发的实际事实优先采用本账户 API 返回，未假设 Demo 必须与公开实盘逐笔价格相同。

## 3. 完整交易调用链（重新核实源码）

下表文件路径均相对于 `src/main/java/com/example/quant/`；类名即 `.java` 文件名。输入和输出包括返回对象、持久化事实与外部请求，不以方法名称推断行为。

|环节|文件 / 方法|输入 → 输出|上游 → 下游|
|---|---|---|---|
|调度入口|`task/ContractMarketScanTask.java#runOnce/claimDecisions`|开关、owner → 本轮 candidates、稳定决策 clOrdId|启动/定时 `runHourly` → `ContractAnalysisService.refreshCandidates`、`StrategyDecisionService.claim`、`AutoTradeService.evaluateAndExecute`|
|市场数据|`crypto/OkxRestClient.java#publicGet/privateGet`；`crypto/OkxMarketService.java#candles`|instId/bar/limit → ticker/book/OI/funding JSON；只收 `confirm=1` 的 OHLCV，按时间正序|Scanner/MarketEnvironment → OKX 公共 GET；指标消费 candles|
|候选池初筛|`crypto/OkxContractScanner.java#scan`|SWAP tickers → USDT 永续、成交额/24h 振幅过滤、成交额前 30|Analysis → `analyzeSnapshotSafely/analyzeSnapshot`，最后按资格/排名/分数/量排序并截取配置数量|
|多周期数据|同文件 `#safeKlines/aggregate20m`|5m、1H、4H、1D → 由四根连续且对齐的已收盘 5m 合成 20m|analyzeSnapshot → MarketService、SignalAnalyzer|
|盘口与大盘|`agent/market/OrderBookLiquidityService.java#snapshot`；`agent/market/MarketEnvironmentService.java#current/trend`|盘口；BTC/ETH 已收盘 K 线 → spread/depth/slippage 估计；趋势/风险|Scanner、最终确认 → public GET；候选评分与门禁|
|指标/结构|`crypto/ContractSignalAnalyzer.java#analyze/indicators/structure`|ticker 与多周期 candles → EMA20/60、MACD、RSI14、ATR14、ADX、结构、量能|Scanner → trendDirection、signalType、scoreBreakdown|
|趋势与多空|同文件 `#trendDirection/directionFor/signalType`|指标、结构、日内变化 → UP/DOWN/NEUTRAL、方向与信号枚举|analyze → planPrices、ContractSignal；**最终方向还可被 AI 排名决定覆盖**|
|评分/过滤|同文件 `#scoreBreakdown`；Scanner `#adjustedScoreBreakdown/finalRankScore`；`agent/gate/ContractTradeGate.java#planDenyReasons`|指标、news、盘口、大盘 → score、final rank、deny reasons|Scanner → Candidate；自动执行分别选择 rule gate 或 AI hard gate|
|候选持久化|`crypto/ContractAnalysisService.java#scanLiveCandidates`；`agent/score/ContractCandidateScoreRecorder.java#recordLiveScan`|本轮 candidates → 内存缓存；尝试 scan/score 表写入|refreshCandidates → Scanner、Recorder；本库对应两表为零，不能假定历史可回放|
|最终选币/方向|`agent/selection/AiContractSelectionService.java#select/parse/ranked`；`agent/execution/AutoTradeService.java#aiOrderedCandidates/selectionFor`|eligible pool → AI 排名、方向、入场模式、置信度；失败 RULE_FALLBACK|evaluateAndExecuteInternal → AI JSON；逐候选尝试生成计划，拒绝后可换下一个|
|等待入场|`agent/entry/WaitingEntryIntentService.java#createOrGet/evaluate/triggered`|WAIT 决策、下一根 closed candle、价格容差 → 持久化 intent/TriggeredIntent|AutoTradeService → 下轮 evaluate → executableSelection → 计划；本次三笔直接入场，不是 WAIT 样本|
|计划价格与方向几何|`tradeplan/ContractTradePlanBuilder.java#buildSelectedPlan/selectedDraft/buildPlan`；Analyzer `#planPrices`|candidate+AI direction → entry、SL、TP、RR、orderType|TradePlanService.createSelectedContractPlan → sizing、leverage、risk、record；AI 主选不接受任意 AI 价格，而按规则重建|
|止损距离|Analyzer `#planPrices`；Builder `#selectedDraft`|ATR/局部结构/entry → 价格距离；AI 路径距离至少 entry×0.006，再按实际方向重建|计划 → sizing/risk/PendingOrder；不是全币种固定百分比止损|
|杠杆|`leverage/LeverageDecisionService.java#decide`；Builder `#analyzedLeverage`|volatility、funding、SL 距离、风险、置信度 → 上限内杠杆|Builder → PositionSizing；最终确认还可按动态风险降低杠杆|
|仓位|`position/PositionSizingService.java#calculate`|equity、available、损失率、保证金率、SL 距离、杠杆 → base quantity/margin/maxLoss|Builder → 计划；先按风险反推名义金额，再受保证金上限与可用余额裁剪|
|预算/相关暴露|`agent/budget/AutoTradeBudgetService.java#allocate/reserveBudget`；`agent/execution/CorrelationExposureGuard.java#evaluate`|本轮余额、slot、评分、risk cap、已有仓位 → margin/reservation、放行/拒绝|AutoTradeService → PendingOrder；暴露是同方向非主流计数，不是实测相关矩阵|
|入场复查|`agent/execution/PreConfirmRefreshService.java#check`；`order/OrderConfirmService.java#currentLiquidity/hardLiquidityDenyReason`|candidate/plan；新盘口 → 放行/拒绝|AutoTradeService → confirmAuto；前者只读传入旧 candidate，后者确实请求新盘口并校验价差/价格偏离|
|待确认聚合|`order/PendingOrderService.java#createAutoPendingOrder/buildPendingOrder`；`order/PendingOrder.java#applyMarginAmount`|plan、margin、reservation、stable clOrdId → pending_order_state；base size=margin×leverage/price|AutoTradeService → confirmAuto；margin 四位 HALF_UP、size 八位 DOWN|
|最终风控|`order/OrderConfirmService.java#confirmAuto/confirm`；`risk/ContractRiskService.java#check`；`risk/DailyRiskService.java#claimEntry`|pending、新账户/盘口、风险设置、账本 → 拒绝或 CONFIRMING→SUBMITTING|AutoTradeService → Risk、daily claim、OkxTradeAdapter；不通过不发送|
|订单创建与精度|`okxtrade/OkxTradeAdapter.java#placeOrder/orderPayload/attachedStopLoss`；`okxtrade/OkxInstrumentRules.java#normalizeEntrySize`|base quantity、合约面值/lot/tick → 合约张数、tick 价格、附加 SL|confirm → setLeverage、OkxRestOrderGateway.placeOrder；小于最小量拒绝，不自动放大|
|提交与模拟撮合|`okxtrade/OkxRestOrderGateway.java#placeOrder` → `crypto/OkxRestClient.java#privatePostJson`|Demo 订单请求 → OKX ACK，再由交易所产生 fills|Adapter → OKX Demo；**本地没有 K 线撮合引擎，ACK 不是成交**|
|未知提交|Adapter `#recoverTimedOutSubmit/recoverUnknownSubmitStatus`|超时+原 clOrdId → 查询恢复或 UNKNOWN|placeOrder/RecoveryTask → queryOrder；不以新 ID 重发入场|
|成交与订单同步|`okxtrade/OkxOrderEventService.java#apply/find/recordFill`；`okxtrade/OkxCurrentOrderSyncService.java#syncOnce/reconcileMissing/reconcileMissingAlgo`|WS orders/REST details/fills → trade_order、trade_fill|WS/AutoTradeRecoveryTask → Ledger；普通单可补 fills，algo 分支缺子单跟进|
|持仓事实/归属|`account/PositionSnapshotService.java#positions`；`account/PositionReconciliationService.java#reconcile`|OKX positions+本地 signed fills → managed/unmanaged/closed|Recovery/runtime/AutoTrade → 持仓门禁、生命周期；不使用本地猜测价格撮合仓位|
|成交后保护|`agent/lifecycle/AutoTradeLifecycleService.java#runOnce/submitProtection/protectionPayloads/takeProfitLegs`|实际成交 VWAP/张数、SL、比例 → 全量 SL、TP1/2/3 reduceOnly algo|RecoveryTask → Gateway.placeAlgoOrder；已有 attached SL 按 client ID 去重|
|TP/SL 触发|上述保护单的 `slTriggerPx/tpTriggerPx`，`slOrdPx/tpOrdPx=-1`|交易所触发价格事件 → algo 子普通订单和 fills|OKX Demo → WS/REST；无本地“同根 K 线先 TP 还是先 SL”分支|
|时间退出/横盘退出|Lifecycle `#shouldForceClose/shouldAdjustSideways/submitMaxHoldClose/tightenTakeProfit`|submittedAt 年龄、持仓浮盈亏 → close 请求或替换 TP|runOnce → Gateway；forceClose 优先于 sideways；年龄从提交时间算起|
|平仓与恢复|`account/PositionCloseService.java#closePosition`；`account/ClosePositionRecoveryService.java#runOnce/reconcileCloseExecution/closeOrdersWithoutPositions`|手动/超时 close intent 或仓位消失 → CLOSED、预算释放、review/event/outcome|生命周期/Controller → OKX/fills；自然关闭分支存在收益未知却关闭的问题|
|手续费/资金费|`ledger/TradeFillLedgerService.java#record`；`ledger/AccountBillLedgerService.java#syncRecent`|fill fee、fillPnl；bills type/subtype → trade_fill/account_bill|WS/REST/close recovery/runtime reconcile → LedgerPnl；bill 当前只取 limit=100 一页|
|最终收益展示/风险反馈|`ledger/LedgerPnlService.java#summary`；`agent/execution/AutoTradeProfitService.java#summary`；DailyRisk `#recordReconciledOutcome`|traceable fills+funding → gross+fees+funding；自动记录/持仓 → 页面指标|Controller/每日风控 ← 账本；`EXECUTED` 过滤与实际 `ENTRY_SUBMITTED` 不一致|

重要源码锚点：`OkxRestClient:77`、`OkxMarketService:26`、`OkxContractScanner:58`、`ContractSignalAnalyzer:405`、`AutoTradeService:300/883`、`ContractTradePlanBuilder:317`、`PositionSizingService:13`、`OrderConfirmService:117`、`OkxTradeAdapter:233`、`OkxRestOrderGateway:150`、`AutoTradeLifecycleService:142/490/616/743`、`OkxCurrentOrderSyncService:197/221`、`ClosePositionRecoveryService:229`、`LedgerPnlService:37`、`AutoTradeProfitService:87`。行号对应本轮工作区，后续修改后以类/方法定位。

## 4. 先核实“亏损”及统计口径

### 4.1 一笔交易定义

一笔交易 = 一个已确认的入场生命周期至完全平仓；分批 fill 不另计交易，拒单/扫描/等待不计。自动样本用 `auto_trade_record.status=ENTRY_SUBMITTED` 的 pending ID 与 entry ordId 联结，再由成交/账单/历史持仓确认闭环，不能只按 symbol 或 posId 去重（XAU 历史不同轮次可复用 posId）。

ETH 为 `qa...`、5U 保证金验证单，无自动入场记录，平仓 `source=MANUAL`，单独列示。自动样本三笔没有持仓重叠，资金费可用 instId+持有窗口配对；更一般的重叠持仓不能这样直接分摊。

计费恒等式：`net = gross + signedFee + signedFunding`。费用字段为负表示支出，资金费本样本为正表示收入，不能再对 fee 取反相加。

### 4.2 自动交易统计（3 笔闭合交易）

|指标|结果|
|---|---|
|总交易数 / 盈利 / 亏损 / 持平|3 / 0 / 3 / 0|
|胜率|0%（n=3，不能外推长期胜率）|
|总盈利（净正收益之和）|0|
|总亏损（净负收益绝对值）|35.938873|
|净利润|-35.938873|
|不计交易手续费的价格毛收益|-27.875680|
|不计交易手续费、但计真实资金费|-27.400434|
|平均盈利|N/A：没有盈利交易，不应伪造为 0 来估算长期 payoff|
|平均亏损|11.979624|
|最大单笔盈利|N/A；最大单笔净收益仍是 -3.810891|
|最大单笔亏损|26.243580|
|平均盈亏比（平均盈利/平均亏损）|N/A：盈利侧为空|
|Profit Factor|0（正净收益总和 / 负净收益绝对值）|
|样本 Expectancy|-11.979624 USDT/笔；均值 -0.483596R/笔|
|最大连续盈利 / 连续亏损|0 / 3|
|按平仓净值离散采样的最大回撤|35.938873 USDT|
|按 Demo 1m close 估值、加入已发生入场费用/资金费的采样最大回撤|52.779998 USDT；852 个估值/边界点。不是 tick 级或账户级精确最大回撤|
|最大回撤百分比|N/A：缺完整起始账户权益和逐时权益序列；60,000U 是配置预算，不应冒充净值基数|
|平均持仓时间|282.811111 分钟（4 小时 42 分 48.7 秒）|
|手续费总额（支出）|8.538439|
|手续费 / 正毛利润|N/A：正毛利润为 0，比例不可定义|
|手续费 / 价格毛亏损绝对值|30.630426%（只是成本强度，不称作手续费占毛利润）|
|手续费 / 最终净亏损|23.758228%|
|做多：次数 / 胜率 / 净收益|2 / 0% / -30.054471|
|做空：次数 / 胜率 / 净收益|1 / 0% / -5.884402|

连败序列为 XAU1 → XAU2 → BTC。含 ETH 验证单的四笔合计净收益 **-35.943054**，不能混用四笔的计数和三笔的收益。

### 4.3 展示为什么不能作为完整账本

运行库按 `LedgerPnlService` 当前 traceable 口径复算：毛收益 **-4.628480**、fee **-6.968665870**、funding **+0.475246102**，net **-11.121899768**。同期间四笔实际 net=-35.943053978，差 **24.821154210**。

差异可以精确分解：

- XAU2 止损子单漏毛亏 **23.247200** + 漏退出费 **1.571664** = **24.818864**。
- ETH 手动退出虽然在 trade_fill，但无 pending/plan 归属，被 traceable 过滤：毛亏 **0.000400** + 退出费 **0.001890210** = **0.002290210**。
- 两项合计 **24.821154210**。不重复把 bills 交易费加到 fills 费上。

所以页面可能**少显示亏损**，而非制造了本不存在的亏损。`RECENT_WINDOW_SYNCED` 只证明 bills 最近一页请求成功，不证明 fills 完整；仍将质量标为 `EXCHANGE_RECENT_LEDGER` 存在误导。

`AutoTradeProfitService#summary` 还只接纳 status=`EXECUTED`；本库实际是 3 条 `ENTRY_SUBMITTED`，无 EXECUTED。依此推导自动笔数/提交保证金为 0，autoSymbols 为空时浮动持仓汇总也会漏掉。这是代码与数据直接支持的统计缺陷；本轮未冒充已访问登录页面验证其最终渲染。

## 5. 逐笔还原

时间均为北京时间；数据库秒精度时间与交易所毫秒时间可能相差不到一秒。信号时间分为“候选快照时间”和“指标最后 K 线时间”，不能互相替代。计划保存了候选快照时间，但缺完整原始输入 K 线/AI响应时刻。

|字段|XAU1|XAU2|BTC|
|---|---|---|---|
|pending ID|57dd8968-d3b4-4804-a434-c3a071c64746|ce4e94e5-b095-499e-9c2d-e353c21bb40f|af8ec3bb-29df-48d5-99ca-5efd4007a284|
|方向|long|long|short|
|entry ordId|3826043089745514496|3826304856392372224|3827059560495882240|
|close ordId|3826117303559163904|3826856731638407168|3828161924552368128|
|候选快照/信号可见时间|08-12 17:35:28.610|08-12 19:45:27.895|08-13 02:00:19.996|
|选币/计划落库|08-12 17:36:09|08-12 19:46:10|08-13 02:01:02|
|提交时间|08-12 17:36:10|08-12 19:46:11|08-13 02:01:03|
|首次入场 fill|08-12 17:46:18|08-12 19:46:11|08-13 02:01:03|
|最终平仓|08-12 18:13:01|08-13 00:20:21|08-13 11:08:36|
|计划价 → 实际入场 VWAP|4415.4 → 4415.4|4415.6 → 4416.422873|63449.9 → 63460.520240|
|成交类型|LIMIT|MARKET|LIMIT（不等于保证 maker）|
|数量（张 / base）|717 / 0.717|717 / 0.717|4.99 / 0.0499|
|杠杆 / 模式|1x / cross|1x / cross|1x / cross|
|计划保证金|3169.6902|3169.5306|3167.9958|
|计划 SL → 交易所 SL|4384.544286 → 4384.5|4384.684286 → 4384.7|64401.6485 → 64401.6|
|计划 TP|4470.940286|4471.248286|61736.7527|
|实际 TP1 / TP2 / TP3|4446.3 / 4477.1 / 4508.0|4448.2 / 4479.9 / 4511.6|62519.4 / 61578.3 / 60637.1|
|实际退出 VWAP|4413.174616|4384.000000|63521.299399|
|退出原因|MAX_HOLD_TIMEOUT（共用标签，无法只凭标签区分 lossClose/maxHold）|交易所 SL；`actualSide=sl`、`state=effective`、子订单号吻合|MAX_HOLD_TIMEOUT；此前平仓一直被阻断|
|毛收益|-1.595600|-23.247200|-3.032880|
|手续费（带符号）|-2.21529146|-3.15495160|-3.16819640|
|资金费|0|+0.15857172|+0.316674382|
|净收益|-3.81089146|-26.24357988|-5.884402018|
|持仓分钟|26.716667|274.166667|547.550000|
|最终结果|亏损|亏损，占自动净亏损 73.0228%|亏损|

三个入场方向与计划的 SL/TP 几何均正确。XAU1 提交至成交延迟 **10 分 08 秒**，生命周期从 `submittedAt` 起算，故实际只持有约 26.7 分钟也可超过 30 分钟亏损退出阈值。这是计时语义问题，不等价于止损太紧。

候选快照至入场约为 649.4 秒、43.1 秒、43.0 秒。计划价相对实际成交的有方向偏差（正=不利）分别 **0、+1.86356、-1.67380 bps**。这包含行情移动、等待、盘口、撮合因素，不能全部称为纯滑点。缺提交时 Demo bid/ask，无法分离纯滑点与 spread。

### 5.1 选币理由和当时市场状态

证据：`auto_trade_record` id **980、1034、1237**；三个 `trade_plan.plan_json` 的 `reasonList/riskList/aiPayload`。

- XAU1：AI 置信度 0.72，认为 20m/4h 向上、负 funding、OI +7.99%、量能 1.7191 倍；实际快照 **20m UP / 1h NEUTRAL / 4h UP**、RSI 63.6953、ATR%=0.1874，规则 NEUTRAL。AI 提到短周期与 4h，不应改写成三周期共振。
- XAU2：置信度 0.78，主张高周期上行、不过热、负 funding；快照 **20m NEUTRAL / 1h UP / 4h UP**、RSI 58.2061、量能 1.1235、ATR%=0.1717，OI +0.37%，规则 NEUTRAL。
- BTC：实际做空、置信度 0.55；快照 **20m NEUTRAL / 1h DOWN / 4h DOWN**、RSI 25.9312、量能仅 0.1835、ATR%=0.2827。**选择理由却写 HYPE 三周期上涨、RSI65.41、OI+3.17%。** `AutoTradeService#selectionFor` 切换到排名中的候选方向/模式/置信度，却保留顶层 reasonList/riskList，导致 BTC 复盘引用 HYPE 理由。这是审计绑定错误，不足以证明实际 BTC 方向也取错。

三笔 `market_risk_level=LOW`，但计划 news 均 **UNKNOWN**。LOW 是 BTC/ETH 波动风险分类，不是“已确认趋势行情”或“新闻安全”；不可从该字段推断所有市场环境。

ETH 验证单：08-12 13:39:30→13:43:23，long，0.02 张=0.002 ETH，1x，entry1890.41→exit1890.21，SL1872.0801、计划TP1928.8098，实际小仓位 TP3=1945.4；毛 -0.0004、费 -0.00378062、净 -0.00418062，持仓3.8833分钟。没有自动选币理由和该时段行情，MFE/MAE不编造。

### 5.2 MFE / MAE

以实际 entry VWAP 为基准、base 数量为乘数、未扣费；`R=abs(entryVWAP-planSL)×baseQty`。持仓首尾分钟包含交易前/平仓后数据，不能全部当作持仓内路径。

Demo 分钟行情每币 1365 根，连续无缺口、confirm 全为 1，覆盖 08-12 16:30 至 08-13 15:15（右端不含）。用完全位于持仓内的分钟给下界，用与持仓相交分钟给上界；再将已确认 entry/exit fill 纳入已观测价格范围。不能确定分钟内先高后低，但本批 TP 未命中，不存在因此选择盈利路径的问题。

|指标|XAU1|XAU2|BTC|
|---|---|---|---|
|持仓内完整分钟最高 / 最低|4425.5 / 4413.2|4442.7 / 4395.4|63694.7 / 63100|
|含边界分钟最高 / 最低|4425.5 / 4413.1|4442.7 / 4382.5|63694.7 / 63100|
|MFE 金额|7.241700|18.840700|17.989960|
|MFE / R|0.327330|0.827924|0.383072|
|MAE：完整分钟下界|1.577400|15.073400|11.685570|
|加入已观测平仓价后的 MAE 下界|至少1.595600|至少23.247200|11.685570|
|MAE：边界分钟上界|1.649100|24.322700|11.685570|
|MAE / R（修正下界至上界）|至少0.0721 至0.0745|至少1.0216 至1.0688|0.248829|

MAE 下界用退出 VWAP仍是保守值，逐 fill 中更差的价格可以进一步提高下界；精确 tick 极值仍未知。MFE 是价格曾到过的毛估值，不代表全量仓位能以极值成交。

达到过 0.5R 但最终亏损：**1/3（XAU2）**。达到过 1R、1.5R、2R、3R 但最终亏损：**均 0/3**。不能据此说大量交易“达到 TP1 却没执行”。

实盘公开行情对照：XAU1 MFE=0.1685R、XAU2=1.0737R、BTC=0.1833R，和 Demo 明显不同。特别是 XAU2：公开 high4450.5 可跨 TP1=4448.2，但 Demo high4442.7 未跨；交易所 TP1 `actualSz=0,state=canceled` 与 Demo 路径一致。

唯一确认的 SL 交易 XAU2：止损后 1 小时 Demo high=4459.3，回到入场价上方，因此该现象为 **1/1**。样本只有一笔且此前时间平仓异常，不能解释成“止损统一放宽就有效”。XAU1/BTC 不是 SL，不能计入“止损后反弹率”。

## 6. 亏损归因：策略问题与程序问题分开

### A. 选币与量能/波动

|组别|次数 / 胜率|净收益|解释限制|
|---|---|---|---|
|XAU|2 / 0%|-30.054471|占比大来自两笔且含一次大亏，不证明 XAU 不适合|
|BTC|1 / 0%|-5.884402|无法评价长期做空能力|
|ATR20m <0.2%|2 / 0%|-30.054471|XAU 两笔，和币种完全混杂|
|ATR20m 0.2%至0.3%|1 / 0%|-5.884402|BTC 一笔|
|其余波动率区间|0 / N/A|N/A|不能填零收益表示表现良好|
|24h成交额 1亿至5亿|2 / 0%|-30.054471|XAU约4.053亿、3.873亿|
|24h成交额 ≥10亿|1 / 0%|-5.884402|BTC约43.463亿|
|其余成交量区间|0 / N/A|N/A|缺样本|

所选并非低成交额小币；快照盘口 XAU 双边深度百万 USDT 级、BTC 最小一侧约49万，计划名义金额约3168U。但这些是无 Demo 标记盘口，不能作为 Demo 可成交深度的证明。

AI 主选路径明确允许越过规则 soft gate/PositionQuality 拒绝；三笔计划都记 `autoTradeEligible=false`、`signalPool=NEUTRAL`。这在源码中是显式模式，不宜擅自定为“漏写 if”；是否允许该策略语义需后续确认。当前没有随机候选/Top Volume/Top Volatility 同期基准，不能声称选币产生或没有 Alpha。

### B. 方向

long两笔、short一笔均亏。XAU高周期UP与做多一致，BTC高周期DOWN与做空一致，无证据证明“上涨频繁做空/下跌频繁做多”。BTC短期RSI25.93且量能0.1835，是追跌/动量疲弱假设的依据；仅一笔不足确认。HYPE理由落在BTC上是审计 bug，须先纠正理由绑定，再评估方向模型。

### C. Entry

XAU1明显存在限价等成交延迟；后两笔约43秒候选至执行。`PreConfirmRefreshService` 不重新获取5m趋势；最终确认会刷新盘口和价格偏差，故不能说“完全无实时复查”。需要以后存下 signalGeneratedAt/marketDataAt/submittedAt/firstFillAt 和两环境 bid/ask 才能拆分延迟与纯滑点。

### D. Stop Loss

本批实际 SL 距离约0.699%、0.719%、1.483%，分别约为快照ATR%的3.73、4.19、5.25倍；不是显然“比一个ATR还紧”。这些是不同环境数据的尺度对照，不能替代 Demo ATR 校准。

XAU2止损 VWAP4384 比交易所触发4384.7低0.7，退出超触发价格成本约 **0.5019U**（0.7×0.717），约 **1.60bps**；无法单独分解触发延迟、spread与滑点。实际毛亏比计划SL风险多 **0.490633U**（包括tick取整差异），净亏超过1R还叠加了费用。

### E. Take Profit / 持仓管理

实际保护三段为1R/2R/3R，默认30%/40%/30%，名义完整目标加权2R；不是计划里显示的单目标1.8R。XAU实际分配215/286/216张，最后剩余张数承接取整；BTC需按lot同样看实际挂单。没有看到随浮盈持续抬SL的可执行主链；目前有分批止盈、横盘时间后替换小盈利TP，但非持续保本/追踪SL。

当前 `application.yml` 生命周期 `loss-close-minutes=30,max-hold-minutes=60,sideways-position-hours=3`。在正常到60分钟强制退出的情况下，3小时横盘逻辑通常没有机会；forceClose又在sideways之前判断。**参数之间的时间冲突已确认，正确参数取值仍未验证。**

### F. 仓位/杠杆/风险收益

|交易|计划止损 Risk（按实际入场）|计划 TP Reward（按实际入场）|实际几何 RR|净实现 R|
|---|---|---|---|---|
|XAU1|22.123547|39.822385|1.800000|-0.172255|
|XAU2|22.756567|39.309821|1.727406|-1.153231|
|BTC|46.962300|86.016000|1.831597|-0.125301|

Risk 未含费用和资金费，便于固定 R 基准；费用后的完整风险预算应另算。全为1x，无爆仓、无杠杆重复乘损益证据。XAU两笔和BTC保证金接近是保证金上限裁剪后的结果，不能只凭金额接近断言仓位公式没考虑止损。

但 `PositionSizingService#calculate` 裁剪 margin 后仍返回原先 `maxLoss=equity×lossRate`；三个计划 JSON 写约 **633.94/633.91/633.60U**，而实际 pending 风险约 **22.15/22.19/47.52U**。这是 maxLoss 字段语义/实现错误，当前最终风控重新算了止损损失，未证明它把本次实际下单量放大。需区分“风险预算上限”和“当前数量预计止损金额”。

组合方面，本批没有并行重叠持仓，不能归因于相关性叠加。源码只有同向非主流计数/仓位容量，不是历史相关系数风控。连续亏损后有每日限额、连亏与冷却控制，未发现按连败自动递减仓位的公式；不能由此直接认定必须递减仓位。每日风控使用 UTC 日期，而 Ledger 当日PnL使用北京时间，是另一个日界线不一致问题，尚未量化为本次亏损来源。

整体净R分布：`(-∞,-1]` 1笔，`(-1,0)` 2笔，其余0。样本均值为负；无盈利样本，无法可靠估计真实胜率、平均盈利或理论期望。对于理想单目标1.8R、固定损失1R且无费用的二项模型，盈亏平衡胜率35.714%；这里实际分批+时间退出+执行故障明显不符合该简化模型，不能据此证明策略应有正期望。

### G. 手续费/资金费/频率

真实自动价格毛收益 -27.875680，交易费 -8.538439，资金费 +0.475246，得到 -35.938873。因此价格路径本已亏损，费用再放大；资金费反而抵消部分损失。

XAU1入场fee=-0.63316836，约maker 2bps，退出fee=-1.58212310，约taker 5bps；XAU2/BTC往返约taker各5bps。这里费率由实际金额反算，不是凭 LIMIT/MARKET 标签假定。策略盘口估计slippage不含全部交易费，更不等于真实成本。仅3笔不能称作高频亏损；1,403次失败平仓均未发送，不应当成1,403笔收费交易。

### H. 市场环境与执行

系统确有 regime：`MarketEnvironmentService` 计算BTC/ETH短周期趋势/风险，`PositionQualityPolicy#marketRegime`映射BULLISH/BEARISH/CHOPPY/HIGH_VOLATILITY/RISK_OFF。但未保存本批完整BTC/ETH状态，且AI路径可越过质量过滤，不能把LOW都归为CHOPPY或LOW_VOLATILITY。

可报告的替代分层：三笔marketRisk=LOW共-35.938873；其余风险类无样本。候选20m UP的一笔-3.810891、NEUTRAL两笔合计-32.127982；高周期UP两笔-30.054471、DOWN一笔-5.884402。**这些是指标标签分层，不是独立识别的市场regime收益。** 宽/窄震荡、高/低波动的跨期盈利能力未知。

### 会计损失与因果损失分开

可严格复算：价格毛亏27.875680 + 费用8.538439 − 资金费收入0.475246 = 净亏35.938873。

可证明的执行异常：XAU2/BTC到期平仓未发送。可估计而非精确归因：在首次失败前最后一根完整Demo分钟close处退出，保留实际入场费、估5bps退出费，不计反事实资金费：

|交易|首次失败时间|当时已完成分钟close|该价格退出估算净值|实际净值|两者差额|
|---|---|---|---|---|---|
|XAU2|08-12 20:16:57|4410.7|-7.267824|-26.243580|实际更差18.975756|
|BTC|08-13 02:32:56|63452.9|-2.786240|-5.884402|实际更差3.098162|

这是**退出执行故障的敏感性示例**，不是“修bug必然挽回22.073918U”。分钟价格可比实际时刻早最多59秒，缺当时可成交盘口，尤其BTC分钟估值和触发时浮盈亏可不同。不能把该差额再次与手续费分解加总。

## 7. 程序 Bug / Look Ahead Bias 专项检查

|检查项|结论及范围|
|---|---|
|未收盘K线/未来最终OHLC用于即时信号|`OkxMarketService#candles`过滤confirm=1，20m要求四根连续已收盘5m；指标消费当前列表。当前实时链未发现所述未来K线泄漏。不能代替对未来新增回测引擎的验证。|
|K线时间戳语义|合成20m保留开盘时间、最后5m timestamp作为决策键；开盘时间不是信号可用时间。未来回放必须等bar结束后方可消费。原始历史输入未全量保存，不能逐笔重放当时所有指标。|
|信号close等于成交close|实时entry用ticker/候选价，实际成交来自OKX；XAU1等待608秒，后两笔实际VWAP也不同，未见本地按信号close伪造成交。|
|同一根K线同时触发TP/SL|本地无撮合引擎，不存在本地先TP/先SL分支。应使用交易所algo子单；离线实验若只有OHLC必须给保守上下界，不选有利顺序。|
|SL/TP多空方向|本样本正确；Builder检查geometry、Adapter强制SL方向、Lifecycle按posSide算目标。未发现本次反向设置。|
|重复开仓/旧信号|扫描锁、symbol锁、持仓/委托检查、数据库决策唯一键、稳定clOrdId；样本无重复/重叠入场。当前单实例结果不能证明所有并发/超时竞态安全。|
|重复平仓/未知提交|本批大量失败均为本地门禁抛错、未发请求，不是重复成交。另 `submitMaxHoldClose` 在成功响应后才持久化close意图，异常只标attention，缺手动close相同的提交前意图/unknown恢复约束，是潜在风险，未证实此次发生未知提交重复平仓。|
|algo子单/状态同步|已证实漏入账；algoId不能代替ordId查普通fills。自然positionGone关闭不等于PnL已核实。|
|PnL多空/fee符号|用实际合约base数量及方向重算与交易所历史一致；fee负数相加正确。本次主要问题是缺失及过滤，不是符号反转。|
|杠杆重复计算|没有本批证据；真实qty已含杠杆名义暴露，PnL不再乘杠杆。样本均1x，不能验证高杠杆场景。|
|quantity/precision|base→contract→lot向下取整路径存在；本批717张与4.99张吻合。margin HALF_UP靠近硬cap有拒单风险，12条拒单均为Margin cap，尚缺每次最新账户快照，不能全部归因于舍入。|
|百分比单位|ATR百分数再除100存volatility，spread bps÷100为百分数，配置percentRate÷100，主要链未见本批100倍误用。风险配置命名和maxLoss返回语义仍需修复。|
|模拟数据一致性|已证实分析公开行情与Demo执行路径不同，不能按公开high/low判Demo漏触发；并非Look Ahead Bias。|
|审计理由绑定|已证实BTC记录沿用HYPE理由，不能用其解释BTC因果。|
|历史完整性|bills只一页100，fill仅能找到本地订单才落账；本样本bills45行未触及一页上限，但不能推广为全历史完整。|

## 8. 控制变量实验（离线，不改参数）

### A / D：真实Entry固定，5/15/30/60/240分钟退出

以下使用**Demo**历史分钟，在期限前最后一根已完成1m close估价，早于期限0至59秒。quantity保持实际量，手续费后=毛值+实际entry费−假定taker退出5bps；不含资金费、不模拟盘口可成交量。结果为反事实观察，不是回测验证、不保证该价可成交。

|期限|XAU1毛 / 费后|XAU2毛 / 费后|BTC毛 / 费后|
|---|---|---|---|
|5min|+3.298200 / +1.080462|-0.876800 / -4.042937|+0.295420 / -2.871112|
|15min|+1.434000 / -0.782806|-0.805100 / -3.971273|+1.547910 / -1.617996|
|30min|+1.147200 / -1.069463|-4.103300 / -7.267824|+0.534940 / -2.631472|
|1h|+0.143400 / -2.072761|+5.719600 / +2.550165|+3.878240 / +0.713499|
|4h|+2.222700 / +0.005499|+1.991200 / -1.176371|-0.957070 / -4.124228|

观察：局部预测方向时有正确，但不少正毛值小于往返成本；没有某个观察期限在三笔上一致正净收益。不能从此表挑一个最赚钱期限上线；这些交易本身就被故障污染且样本极小。

### B：固定Entry，对照Exit

已做三类对照：实际退出（含故障）、固定期限退出、首次失败平仓时刻的已完成分钟估值。也核对计划单TP与实际1R/2R/3R：本批Demo都未达TP1，因此在实际持仓窗口内，仅将TP从1.8R改为分批目标不会产生已证实的漏止盈收益。

未穷举新止损/止盈阈值或“择优”参数；没有精确tick路径，不能给声称可交易的最优Exit。MFE极值退出不属于合法可预测策略，不用它证明新增浮盈保护一定改善期望。

### C：当前选币 vs 随机候选 / Top Volume / Top Volatility

**无法有效执行**：完整同期候选池/排名/被拒候选数据未持久化（两张scan/score表0行），也缺候选各币当时Demo盘口与历史特征。用今天候选替代会产生幸存者偏差，用当前AI重跑旧行情还可能引入模型知识时间泄漏。已记录的少量拒单理由不能构成完整对照池。

### E：不同市场状态

已提供可观察的指标标签分层；正式regime对照缺样本/原始输入。不能虚构“趋势赚、震荡亏”的结果。此实验必须和C一起补齐更长窗口后做。

### 更长周期、OOS、Walk Forward

查询全库成交并未找到更长自动实成交样本。`src/main/java`、`scripts`、`pom.xml` 中检索backtest/walk-forward/replay未找到可执行完整历史回测链，仅有 `ContractTradeGate` 的backtest门禁提示。不能把“接口存在/配置存在”当已有回测能力。

后续验证方案：先冻结修正后的执行版本、行情环境、特征与费用口径，保存所有候选及拒单；按时间顺序划训练/验证/完全未触碰测试窗，覆盖上涨、下跌、震荡，跨币种留出。只在训练窗估计规则，在验证窗决定是否接受，OOS只评一次；Walk Forward每步只使用当时之前的数据与模型。需要处理AI历史知识污染、退市/新上市和消息发布时间。不能给固定天数或3笔样本贴“具有稳定正期望”的标签。

## 9. 修改方案与优先级（全部尚未实施）

|ID / 优先级|当前行为、证据|建议修改 / 涉及文件|潜在影响 / 验证方式|
|---|---|---|---|
|F1 P0|readiness非READY时close/保护一并拒绝；1,403次落库失败|按操作风险区分新增暴露与减少已核实仓位暴露；减仓通路须核实Demo/Live、owner、现存数量、reduceOnly/posSide和幂等，不能简单删除门禁。涉及OkxRestOrderGateway、TradingReadinessService、AutoTradeLifecycleService、ClosePositionRecoveryService、OkxRuntimeReconciliationService|影响写入权限和断线风险；测试WS降级但REST可用、REST失效、账本不全、错误owner、已有SL、未知close，证明绝不反向开仓、绝不扩大仓位，同时能对正确仓位收险。|
|F2 P0|SL的ordIdList子单不跟进，POSITION_GONE直接Closed，账本漏24.818864|持久化algo→entry pending→子ordId；拉齐fills/fee后结算；空仓与收益已对账分开；funding按生命周期归属；引入账本数量/PnL completeness。涉及OkxCurrentOrderService/SyncService、OkxOrderEventService、TradeFillLedgerService、ClosePositionRecoveryService、LedgerPnlService、DailyRiskService|影响汇总与风控；使用本次717张14fill固定fixture，期望毛-23.2472/退出费-1.571664，重放重复/乱序不多算；缺fill时保持PnL未知，不标完整。历史补账另需明确迁移方案及写入授权。|
|F3 P1|publicGet无Demo标记；XAU high/TP判定冲突|明确“实盘信号驱动Demo执行”还是“全Demo”产品语义；分别记录signal venue与execution venue，按执行环境算MFE/MAE。若要统一，修改OkxRestClient及market/book/instrument读取契约|影响全部指标、选币和撮合差异；先对同inst/time双环境记录，不把改数据源当策略参数优化；测试header、历史对齐及Demo失败不静默切换实盘。|
|F4 P1|超时close成功后才保存意图，异常缺UNKNOWN对账；当前错误文案隐藏真实readiness原因|先保存稳定close意图，超时按原ID查询；日志保存state/reason与请求是否已发。涉及AutoTradeLifecycleService、PositionCloseService、ClosePositionRecoveryService、TradeEvent|防意图重复/错误未知；测试远端已收、本地超时、进程中断恢复。此潜在bug未被本次门禁失败证明发生。|
|F5 P1|AutoTradeProfit仅筛EXECUTED，实际是ENTRY_SUBMITTED；manual/auto归属混杂|按已成交entry/生命周期事实计数，明确自动与验证单scope，不用扫描状态代替成交。涉及AutoTradeProfitService、AutoTradeRecordService、LedgerPnlService、相关前端DTO|改变页面数字；fixture应显示自动3笔、净-35.938873，验证单单列；有仓位时浮动收益不漏，未知不归零。|
|F6 P1|selectionFor沿用顶层HYPE理由给BTC|每个ranked candidate拥有自身reason/risk/decision时间；fallback保存原选择与实际选择，缺理由标未知。涉及ContractSelectionDecision、AiContractSelectionService、AutoTradeService、TradePlanRecordService|审计契约改变；测试第一候选拒绝、第二币不同方向，reason与instId必须一致。不要仅替换文字掩盖缺失依据。|
|F7 P1|margin裁剪后maxLoss仍原预算；margin上限附近12次拒单|将riskBudget与estimatedStopLoss字段分开，裁剪/lot归整后重算；金额统一向安全方向规整，并保留提交前账户cap。涉及PositionSizingService、PendingOrder、AutoTradeBudgetService、OrderConfirmService、ContractTradePlanBuilder|改变展示/分配，应先以原样本证明数量不会被放大；测试cap上下一个最小单位、余额变化及不同SL。12次拒单不直接认定均是舍入bug。|
|F8 P1|DailyRisk UTC日、Ledger北京时间日不一致；自然close未知结果不计连败|统一明确业务交易日，收益结果与entry claim做幂等追溯；涉及DailyRiskService、LedgerPnlService、ClosePositionRecoveryService|影响日限额、连败/冷却；覆盖北京时间00点/08点跨日、funding、迟到成交补记。没有证据将本批亏损归因于该日界线。|
|S1 P2|30/60分钟退出、3小时横盘、计划TP和实际分批目标不一致|先统一配置语义和计划/执行契约，审议年龄从firstFill还是submit计算；对原Entry比较固定退出/现有退出，不预先决定改成哪个数。涉及AgentProperties、application.yml、Lifecycle、Builder|这是策略与产品语义变更，须另行确认；验证低波动/趋势样本、手续费后OOS，不依据昨天拟合。|
|S2 P2|AI可交易NEUTRAL、绕过soft gate；n=3无正期望证明|保存候选级选择事实，建立同池随机/Volume/Volatility和规则基线；再判断AI主选资格与环境适配。涉及selection、score recorder、PositionQualityPolicy、研究工具|可能显著改变交易覆盖与频率；先恢复数据再比较，禁止直接增加过滤条件。|
|O1 P3|缺完整历史候选/指标/行情/运行state序列，临时取证不可长期依赖|增加带版本、venue、时间、费用完整性的审计导出，分离策略观测与执行事实；涉及Recorder、TradeEvent、研究数据存储|存储/API成本增加；验证脱敏、关联完整、只读回放及复算结果。必须先查清现有Recorder为何两表为0，不能默认仅需打开某个开关。|

完成顺序建议：F1/F2（保护真实风险与账本）→ F4/F5/F6/F7/F8（执行/统计契约）→ F3数据环境决策及可比性验证 → S1/S2独立研究 → O1的长期完善。诊断数据的最低保存要求应和前面修复同步落实。

## 10. 复核依据、可复现公式与未完成项

关键只读SQL（执行时加只读事务，owner实际为admin）：

```sql
SELECT COUNT(*) n, MIN(fill_time), MAX(fill_time) FROM trade_fill;
SELECT category, COUNT(*), SUM(pnl), SUM(fee)
FROM account_bill GROUP BY category;
SELECT status, COUNT(*), MIN(created_at), MAX(created_at)
FROM auto_trade_record GROUP BY status;
SELECT COUNT(*) FROM agent_contract_candidate_score;
SELECT COUNT(*) FROM agent_scan_run;
SELECT pending_order_id, reason_code, COUNT(*), MIN(created_at), MAX(created_at)
FROM trade_event WHERE reason_code='MAX_HOLD_TIMEOUT_CLOSE_FAILED'
GROUP BY pending_order_id, reason_code;
SELECT SUM(fill_pnl), SUM(fee) FROM trade_fill
WHERE user_name='admin'
  AND ((trade_plan_id IS NOT NULL AND trade_plan_id<>'')
    OR (pending_order_id IS NOT NULL AND pending_order_id<>''));
SELECT * FROM trade_fill WHERE ord_id='3826856731638407168';
SELECT id, inst_id, source, status, pending_order_id, realized_pnl, fee
FROM close_position_record;
```

逐笔复算：

```text
entryVWAP = sum(entryFillPx * entryContracts) / sum(entryContracts)
baseQty = contracts * ctVal               # 本批 XAU .001、BTC .01、ETH .1
sign = +1(long), -1(short)
gross = sign * (exitVWAP-entryVWAP) * baseQty
net = gross + sum(signedFillFees) + assignedFunding
risk = abs(entryVWAP-plannedSL) * baseQty
realizedR = net / risk
MFE = max(0, sign*(favorablePrice-entryVWAP)*baseQty)
MAE = max(0, -sign*(adversePrice-entryVWAP)*baseQty)
PF = sum(positive net) / abs(sum(negative net))
Expectancy(sample) = sum(net) / closedTradeCount
drawdown = max(previousPeakCumulativePnl-currentCumulativePnl)
```

交易所历史持仓、bills、fills用Decimal复核，差异容许低于1e-8U；数据库12位小数截断可产生约1e-12差异。不能将预算收益率称作账户收益率；不能用最大浮盈当可实现退出收益。

**仍不能确认的事实**：用户所指其他“昨天”时段的数据；当时每次readiness降级的最底层触发原因；提交时Demo逐笔盘口/纯滑点；完整信号原始K线和AI响应时刻；精确tick级MFE/MAE/最大回撤；被拒候选基准收益；不同市场regime长期收益；修复执行后的策略真实期望。这些不确定性不能用补写结论填满。

本轮验证为只读数据库/交易所查询、源码及关键运行字节码核对、Decimal统计、分钟序列完整性检查与离线反事实比较。未运行整套业务测试或重启交易进程；未改业务代码，因此没有声称完成修复、通过交易E2E或证明正期望。

**第一轮诊断结束。所有修改方案均未实施，等待确认具体修复范围。**
