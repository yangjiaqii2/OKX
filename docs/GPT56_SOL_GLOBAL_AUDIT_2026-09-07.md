# GPT-5.6 Sol 全局审计结果

> 审计基准：2026-09-07 当前工作区真实源码、V1-V33 Flyway migration、408 项 Maven 测试及生产调用链静态复核。工作区在接手时已有大量未提交修改；本报告不把既有改动视为已部署事实，也不把 mock 测试视为 OKX 运行时证明。

## 1. 总体架构

真实开仓主链为：

`ContractMarketScanTask.runOnce`
→ `ContractAnalysisService.refreshCandidates`
→ `OkxContractScanner.scan`
→ `ContractCandidate`
→ `StrategyDecisionService.claim`（按 owner/strategy/inst/timeframe/candle 幂等）
→ `AutoTradeService.evaluateAndExecuteInternal`
→ `ContractTradeGate.aiHardDenyReasons / planDenyReasons / autoDenyReasons`
→ `AiContractSelectionService.select`
→ `ContractTradePlanBuilder.buildSelectedPlan/buildPlan`
→ `TradePlanRecordService.record`
→ `PendingOrderService.createAutoPendingOrder`
→ `OrderConfirmService.confirm`
→ `OkxInstrumentRulesProvider.rulesFor + normalizeEntrySize`
→ `DailyRiskService.claimEntry`
→ `OkxTradeAdapter.placeOrder`
→ `OkxRestOrderGateway.placeOrder`
→ `OkxRestClient.privatePost`
→ OKX `/api/v5/trade/order`。

回流主链为：

OKX private WS / REST
→ `OkxCurrentOrderService`
→ `OkxCurrentOrderSyncService.syncOnce`
→ `TradeOrderEntity`
→ Algo `ordIdList`
→ child `TradeOrderEntity(parentAlgoId)`
→ `TradeFillLedgerService.record`
→ `trade_fill`
→ `AlgoFillReconciliation`
→ `ClosePositionRecoveryService`
→ PendingOrder lifecycle / ClosePositionRecord / budget release / DailyRisk outcome / TradeReview
→ `LedgerPnlService`
→ `AutoTradeProfitService`。

所有真实 OKX 私有 POST 最终集中在 `OkxRestOrderGateway`。全文搜索未发现其他生产类直接调用 `privatePost/privatePostJson`。

## 2. F1-F8 逐项真实状态

| ID | 此前状态 | Sol复核状态 | 发现问题 | 是否修复 |
|---|---|---|---|---|
| F1 | COMPLETE | **YES（源码边界）** | READY 时 reduce-exposure 原先直接绕过 `ExposureReductionGuard` | 已修；READY/非 READY 的 close/reduce/SL/TP 都经过 guard，非 READY 的 OPEN/ADD/SET_LEVERAGE 继续 fail-closed |
| F2 | COMPLETE | **YES（源码/空库 migration/测试）** | 缺失 fee 或 fillPnl 被解析成 0，数量齐全就可能伪报 COMPLETE | 已修；缺字段保持 null，`AlgoFillReconciliation` 保持 PARTIAL；DB 以 `(user_name, inst_id, trade_id)` 去重 |
| F3 | COMPLETE | **YES（源码语义）** | `MarketDataContext` 曾把不存在的 Demo 公共行情标成 DEMO | 已修；公共 candle/ticker/book/funding/OI/instrument 统一 `PUBLIC_LIVE`，DEMO 配置 fail-closed，执行为 DEMO/LIVE |
| F4 | COMPLETE | **YES（应用 V33 后）** | max-hold UNKNOWN 后 scheduler 会创建第二个 intent，且手动/自动 close 可并发认领同一 PendingOrder | 已修；未决 intent 交给 recovery；V33 同时唯一约束 owner+stable clOrdId 与 owner+PendingOrder，冲突实例不发送；终态失败只允许一个实例原子重新认领 |
| F5 | COMPLETE | **NO** | executedCount 仍读 `status=EXECUTED`；owner+合约账本会混入同合约 Manual/QA funding | 已修计数和 fill scope；无法按订单归属的 funding 现在返回 N/A，因此完整自动净收益尚未成立 |
| F6 | COMPLETE | **YES** | `AutoTradeRecordService` 在候选理由为空时仍回退 decision 顶层理由 | 已修；只持久化实际 `RankedCandidate` metadata；TradePlan 接收的是 `selectionFor(candidate)` |
| F7 | COMPLETE | **YES（应用 V30 后）** | executable quantity/stop risk 原为 transient；重启会按新 instrument metadata 重算 | 已修；V30 持久化两项事实，entity 完整恢复；CONFIRMING/CONFIRMED 崩溃状态安全回退并重新完整确认 |
| F8 | COMPLETE | **YES** | 未发现业务日残留 UTC/`LocalDate.now()` 路径 | DailyRisk、Ledger、AutoTradeProfit 均使用 `TradingDayResolver` 的 `Asia/Shanghai` |

因此，**F1-F8 不能全部保持 COMPLETE**。F5 必须降级为 PARTIAL；F1/F2/F3/F4/F6/F7/F8 可以在“当前源码 + V33 migration + 自动测试”范围保持 COMPLETE，但尚无本轮真实 OKX 运行时证明。

## 3. S1 当前真实状态

- Entry timeout 使用 `submittedAt`，没有改成进程当前时间。
- Holding age 现在只读取属于非 reduce-only entry order 的最早 fill；entry fill 暂缺时回退到持久化 `submittedAt`，close fill 不会重置年龄。
- 第一笔极小 partial fill 会立即启动整个 lifecycle 计时。这是当前明确产品语义，未在本轮改变。
- first fill 可由 `trade_fill` 跨重启恢复；测试仍是 repository mock，没有真实进程杀死/重启 E2E。
- Sideways 配置 180 分钟、max-hold 60 分钟时，实际是 **59 分钟**。这是 effective policy mutation。生命周期快照现同时暴露 `configuredSidewaysAfter=180m` 与 `effectiveSidewaysAfter=59m`。
- TP 执行端按实际 fill avgPx、instrument lot/tick 和剩余数量构造三腿，TP3 读取计划 takeProfit 的实际 R；long/short 公式和 remainder 在定向测试中通过。
- `trade_take_profit_plan` 仍表示计划时理论价格，而 `trade_order` 表示实际 tick/quantity payload；二者不是逐字段完全相同。实际 payload 可审计，但“TradePlan audit 与 Algo payload 完全一致”尚未达到。
- 最佳 Exit Policy 没有被证明，S1 仍为 **PARTIAL**。

## 4. S2 当前真实状态

- 默认 policy 是 `RULE_FIRST_AI_RANK`。
- Hard gate：instrument、价格、turnover、funding、market risk、critical/high news、极端 spread。实际执行和 Shadow 已共用 `ContractTradeGate.aiHardDenyReasons`。
- Rule-first eligibility：`ContractTradeGate.planDenyReasons`，包含 factor score、总分、RR、SL%、signal、news、spread、market regime。
- Soft/auto execution gate：action、tradable signal、extreme spread，并叠加 position quality、correlation exposure、pre-confirm refresh、budget、DailyRisk 和 confirm risk。
- 历史 WaitingEntry trigger 原先是第二入口；现会在执行前重新过当前 `planDenyReasons`，失去 eligibility 时 `FAILED_SAFE`。
- `signalPool=NEUTRAL` 会被 `signal_type_not_tradable_NEUTRAL` 排除，因此 Rule-first coverage 可能接近 0。这是策略覆盖风险，本轮没有放宽规则。
- 长期优于基线没有被证明，S2 仍为 **PARTIAL**。

## 5. 新发现 P0/P1/P2

### P0

1. READY reduce-exposure 绕过 managed-position guard：已修。
2. UNKNOWN max-hold close 被 scheduler 再次提交：已修为 recovery 单一责任，稳定 `closeClOrdId` 不变。

当前审计过的源码与 V33 约束中，**没有遗留一个已知必然或高概率生成真实错误订单的 P0**。此结论不覆盖尚未部署的旧进程和真实 OKX 行为。

### P1

1. F7 可执行 quantity/risk 不持久化：V30 已修。
2. Algo fill 缺失 PnL/fee 伪 COMPLETE：已修。
3. Demo 公共行情伪 provenance：已修。
4. `CONFIRMING`、`CONFIRMED` 崩溃后卡死：已修。
5. close fill 误作 first entry fill：已修。
6. 自动收益混入 Manual/QA：fills 已严格按 Scheduler PendingOrder scope；funding 无法唯一归属时返回 N/A。
7. close intent 原先缺数据库原子性：V33 增加 `(user_name, close_cl_ord_id)` 与 `(user_name, pending_order_id)` 唯一约束；scheduler 捕获冲突后进入 recovery，终态失败通过条件更新单实例认领后才可重试。H2 与 MySQL 8.4 integration 均已验证。

### P2

1. Sideways 59/180 effective mutation 已显式暴露，策略本身未改变。
2. 计划 TP 表与实际 Algo payload 是计划/执行两个事实层，尚无单表逐腿关联。
3. `OrderStatus.SIDEWAYS_TIMEOUT`、`MAX_HOLD_TIMEOUT`、`PROTECTION_PENDING` 等存在 legacy/过渡状态，其中部分生产代码没有直接进入点；保留用于旧数据兼容，建议后续单独 migration 后删除。
4. 旧的 NO_RISK “Shadow plan” 与新 Shadow Selection 同名但语义不同，容易误读；未做无关重构。
5. Flyway 缺少 V24；当前可正常迁移，但未来不能再直接补 V24，否则会引入 out-of-order 管理问题。
6. Shadow 与真实执行分别调用 AI，随机模型响应可能不同；比较时以 Shadow 四策略内部共用的一次 AI 响应为准。这是实验噪声/可维护性问题，不影响真实订单安全。

## 6. Migration / DB 风险

- V28：`trade_order.parent_algo_id`、trade/close `pnl_completeness` 默认 UNKNOWN。旧数据不会自动变成 COMPLETE，安全。
- V29：close submit audit 字段均可空；旧数据可进入 recovery，但缺 `closeClOrdId` 的历史行只能依靠 position/fill 事实，无法做稳定 ID 查询。
- V30：PendingOrder 新增 nullable executable quantity/stop risk。旧 SUBMITTING 行不重提，只按稳定 `clOrdId` 恢复；旧 BUDGET_RESERVED 行重新确认并写入新事实。
- V31：Shadow Selection 新表，唯一键 `(user_name, scan_id, policy)`。
- V32：Counterfactual Exit 新表，唯一键 `(user_name, pending_order_id, policy)`。
- V33：Close Intent 唯一键 `(user_name, close_cl_ord_id)` 与 `(user_name, pending_order_id)`，数据库吸收跨实例 stable-ID、手动/自动同 lifecycle 竞争；旧重复数据会让 migration 失败关闭。
- H2 空库与一次性 MySQL 8.4 空库均从 V1 成功迁到 V33，Hibernate context 成功启动。
- 运行中 Demo MySQL 经只读检查仍在 **V27**；两类 V33 冲突分组当前均为 0。V28-V33 未写入该库，本轮没有部署或迁移现有 Demo DB。

## 7. Restart / Recovery 风险

- `CONFIRMING/CONFIRMED`：交易所调用尚未开始，恢复到 BUDGET_RESERVED 后重新完整确认。
- `SUBMITTING`：一律转 UNKNOWN，按 stable entry `clOrdId` 查询，禁止自动释放 budget。
- Close UNKNOWN：current order → history → fills → position snapshot；scheduler 不再重提同一未决 intent。
- POSITION_GONE 只证明仓位消失；缺完整 exit quantity/price/PnL/fee 时保持 `CLOSED_RECONCILING/PARTIAL`，不等于 PNL_COMPLETE。
- Counterfactual task 直接读取持久化 `pending_order_state` 与 fills，CLOSED 行在重启后仍可评估；公共 5m 窗口覆盖不到 entry 时标为 DATA_GAP，不截断推算。
- 尚缺：现有 Demo 数据库实际升级 + 杀进程 + 重启 + OKX Demo 的端到端恢复测试。Close Intent 的数据库唯一约束与冲突分支已在 H2 integration/单元测试验证，尚未做双 JVM 实压。

## 8. 测试质量

| 类型 | 当前覆盖 | 真实缺口 |
|---|---|---|
| UNIT | gate、size、TP、selection、ledger、evaluation 等多数测试 | 大量 gateway/repository 为 mock，无法证明 OKX/DB 事务行为 |
| INTEGRATION | Spring context + H2 Flyway V1-V33；一次性 MySQL 8.4 空库 V1-V33；Close Intent 两个唯一键实际冲突；`StrategyDecisionServiceTest` 使用 H2 唯一约束并发 | 没有现有 Demo V27→V33 upgrade integration；多数名为 service 的测试仍是 unit mock |
| RECOVERY | UNKNOWN entry、CONFIRMING/CONFIRMED、close recovery、POSITION_GONE 测试 | 没有真实 JVM crash/restart；没有双实例 scheduler |
| FIXTURE | OKX REST/WS JSON、venue fixture、instrument rules | fixture 可能与 OKX 新字段/状态漂移 |
| E2E | 无 | 没有真实 OKX Demo 下单→Algo child→fills→close→ledger 全链 E2E |

最终全量计数为 408 tests。测试证明回归契约，不证明 LIVE 或当前已部署 Demo 行为。

## 9. 死代码 / 双实现

- 公共/私有数据来源已收敛：public market 只走 `publicGet`，私有交易只由 `OkxRestOrderGateway` POST。
- AI hard eligibility 已从实际/Shadow 两份实现收敛到 `ContractTradeGate.aiHardDenyReasons`。
- AutoTradeProfit 不再保留旧 `status=EXECUTED` 统计路径。
- 顶层 `selection.reasonList/riskList` 仍是 AI decision envelope 的合法字段，但后续 TradePlan/AutoTradeRecord 使用候选级 `selectionFor`/RankedCandidate；未发现再次读取顶层候选理由的 service。
- 无 stable ID 的旧 `OkxTradeAdapter.closePosition` overload 仍存在；经真实 gateway 会被 guard fail-closed。它主要服务 mock/兼容调用，建议后续弃用。
- legacy OrderStatus 暂不删除，以免旧库反序列化失败。

# S1/S2 Shadow/OOS 验证基础设施结果

## Shadow Selection

- `ContractMarketScanTask` 在同一 scan/candidate snapshot 记录四策略：`CURRENT_AI_OVERRIDE`、`RULE_FIRST_AI_RANK`、`RULE_ONLY`、`LIMITED_AI_OVERRIDE`。
- 四策略共用一次 Shadow AI ranking；policy 只改变 eligibility/排序职责。
- Shadow service 的依赖只有 candidate、AI selector、AgentProperties 和自身 repository；不能创建 PendingOrder、claim/reserve budget、claim DailyRisk、set leverage、占用 slot 或 place order。
- Shadow persistence 失败只记录 warning，不中断真实执行。

## Counterfactual Exit

- 对真实非 reduce-only entry fills 建立七条独立路径：`BASELINE`、`S1_V2`、`TIME_60`、`TIME_120`、`TIME_240`、`BE_075`、`TRAIL_1R`。
- BASELINE 使用 PendingOrder 持久化的实际 takeProfitPrice；其余路径只改变反事实 exit 判定。
- 同一根 5m candle 同时碰到 stop/target 时采用保守 stop，并标记 `AMBIGUOUS_INTRABAR`。
- 公共 candle 无法覆盖 entry 起点时为 `DATA_GAP`。

## Evaluation

- 新增只读 API：`GET /api/agent/strategy-evaluation?selectionPolicy=...&exitPolicy=...`。
- 输出 scanCount、selectionCount、coverage、tradeCount、gross、fee、net、expectancy、expectancyR、profitFactor、drawdown、avgHold、dataQuality。
- 无数据和不可计算字段返回 null（N/A），不会输出伪造 0。
- 公共行情不能推导真实成交 fee/slippage，因此 counterfactual fee/net/expectancy/PF/drawdown 当前保持 N/A；gross、R、hold 可以积累。

## Venue

- Actual execution：DEMO 或 LIVE。
- Actual signal：PUBLIC_LIVE。
- Counterfactual signal：PUBLIC_LIVE；execution：PUBLIC_COUNTERFACTUAL。
- 不再把 public candle MFE/MAE 称为 Demo execution MFE/MAE，也不据此声称 Demo TP 应触发。

## Persistency

- V31：Shadow scan/policy 唯一持久化。
- V32：actual PendingOrder/policy 唯一持久化并周期更新。
- V33：同 owner 的 stable Close Intent 与自动 PendingOrder 各自只能有一条 lifecycle 记录。
- Counterfactual entry facts 来自 DB order/fill，计划 SL/TP 来自持久化 PendingOrder。

## Tests

- 新增并通过：Shadow 四策略/venue/无执行依赖，Counterfactual 七策略/未知费用，Evaluation N/A，F1 READY guard，F2 缺字段 PARTIAL，F4 UNKNOWN 不重提/唯一键冲突/失败 intent 原子重认领，F5 lifecycle 计数与 scope，F6 单一 metadata，F7 restart facts，S1 entry-only first fill，S2 WaitingEntry re-gate。
- 全量 `mvn -DskipTests=false test`：408/408 通过，H2 Flyway 到 V33；一次性 MySQL 8.4 空库 V1-V33 context test 通过。
- `mvn -DskipTests package`：BUILD SUCCESS；`git diff --check`：通过；本轮新增文件尾随空白扫描：通过。

## 必须明确回答

1. **F1-F8 是否真的都可以保持 COMPLETE？NO。** F5 为 PARTIAL；F1/F2/F3/F4/F6/F7/F8 在当前源码且应用 V33 的验证范围为 YES。
2. **当前系统是否存在已知可能导致真实错误订单的 P0？NO（限修复后仓库源码 + V33）。** 当前运行中的 Demo 部署未更新且 DB 仍为 V27，不能沿用这个 NO；其运行时 P0 状态是 UNKNOWN，所以已判定不可继续自动运行。
3. **当前系统是否适合继续 Demo 自动运行积累样本？NO，当前不能直接继续。** 阻断项：运行中 Demo DB 仍是 V27，先在停交易窗口应用 V28-V33；应用前再次 SELECT 查重并备份；随后在真实 Demo 做 readiness guard、entry UNKNOWN、close UNKNOWN、Algo child/fill/ledger smoke。完成后可继续 Demo 样本积累。
4. **是否适合 LIVE？NO。**
   - execution safety：NO；运行中 Demo DB 尚未应用 V28-V33，且缺真实 Demo 全链 E2E。
   - recovery：NO；缺真实 crash/restart 与 UNKNOWN 恢复演练。
   - data completeness：NO；自动 funding 无法按 lifecycle 唯一归属，counterfactual fee/net 为 N/A。
   - strategy evidence：NO；Shadow/OOS 样本尚未积累，S1/S2 都未证明优于基线。
5. **是否已经证明策略正期望？NO。** 当前代码和测试只建立安全、事实与评估基础，不能证明收益期望。
