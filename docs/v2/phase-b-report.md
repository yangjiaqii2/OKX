# Phase B 交付报告 — Universe Hard Filter Pipeline V2

> 交付时间：2026-09-17  
> 适用范围：`quant-assistant` OKX 合约扫描与 AI 选币子系统  
> Phase B 关键字：**Universe Hard Filter Pipeline V2**  
> 设计基线：selection V2 子模块、Provider 抽象层、AI eligible pool

---

## 0. TL;DR

Phase B 的目标是把候选宇宙从"先按 volume 排序取 Top N → 再过滤"翻转为
"先收集 broad universe → 全部进入 Universe Hard Filter 流水线 → 通过的再进入
deep analysis"。这一架构选择确保了：

- 低成交额但**有基本面质量**的 SLEEPER 标的不会被成交量截断
- 业务阈值（MarketCap ≥ 20M、Circulation ≥ 50%、Volume/MCap ≥ 20%）成为**可
  观测的硬资格**，不依赖成交额排名
- AI 选币的 eligible pool 与 V2 扫描路径**共享同一道 Universe Hard Filter**
- 全部原有行为（trade execution / 止损 / PositionQualityPolicy / V32 迁移）
  不变；516 个原测试零回退

**测试结果**：`mvn -o test` → **638 / 638 通过**，0 failure / 0 error / 0 skip。

---

## 1. 改动清单

### 1.1 新增组件

| 类型 | 路径 | 职责 |
| --- | --- | --- |
| util | `crypto/util/CandleAggregator.java` | 把 `OkxContractScanner.aggregate20m` 抽出来作 `public static` 工具方法 |
| filter | `crypto/filter/MarketCapFilter.java`（重写） | 单道过滤器：硬资格 market cap ≥ 20M；DataQuality 短路 |
| filter | `crypto/filter/CirculationFilter.java`（重写） | 双路径：V2 ratio 优先 / 快照绝对值回退 |
| filter | `crypto/filter/VolumeMcapFilter.java`（重写） | 双路径：V2 ratio 优先 / 快照绝对值回退；DataQuality 在 ratio 计算前短路 |
| filter | `crypto/filter/PriceRangeFilter.java` | 24h 振幅硬上限 |
| filter | `crypto/filter/LiquidityFilter.java` | 盘口深度硬下限 + spread 上限 |
| filter | `crypto/filter/UniverseHardFilter.java` | 5 个 filter 链式按 cheapest-first 短路 |
| dto | `crypto/filter/FilterContext.java` | 候选级 V2 字段：circulationRatio / volumeMcapRatio / marketCapUsd |
| dto | `crypto/filter/FilterResult.java` | 单道过滤结果 |
| dto | `crypto/filter/FilterOutcome.java` | 整链过滤结果（funnel / passed / rejected） |
| dto | `crypto/filter/FilterFunnel.java` | `stages: Map<String, Long>` 漏斗观测 |
| dto | `crypto/filter/UniverseFilter.java` | 单道过滤器接口 |
| dto | `crypto/filter/SingleOutcome.java` | 单输入批量过滤产出 |
| provider | `crypto/provider/MarketCapProvider.java` | 抽象接口 |
| provider | `crypto/provider/ProviderResult.java` | 业务结果 + DataQuality 联合封装 |
| provider | `crypto/provider/DataQuality.java` | 5 个枚举（FRESH / STALE / TIMEOUT / UNAVAILABLE / UNKNOWN） |
| provider | `crypto/provider/ProviderCache.java` | TTL 缓存（freshTtl / staleTtl 双窗口） |
| provider | `crypto/provider/NullMarketCapProvider.java` | 兜底：永远 UNAVAILABLE |
| provider | `crypto/provider/dto/MarketCapSnapshot.java` | mcap / fdv / 供应量 / 24h 量 |
| agent | `agent/scan/ScanDiagnostics.java` | universe / passed / rejected / funnel / providerStatus / deepAnalysisTargetCount |
| selection | `agent/selection/AiContractSelectionService.java`（修改） | eligible pool 走 `passesUniverseHardFilter` |

### 1.2 重构

- `crypto/OkxContractScanner.java`：删除内部 `aggregate20m`，改为 `CandleAggregator.aggregate20m(...)`
- `crypto/OkxContractScanner.java`：增加 V2 路径 `scanV2(tickers) → Universe Hard Filter → deep analysis`
- `crypto/OkxContractScanner.java`：`toFilterContext` 使用 `FilterContext.withV2Ratios(...)` 传
  候选级 V2 ratio，让 V2 ratio 优先于 snapshot 绝对值
- `agent/selection/AiContractSelectionService.java`：`passesUniverseHardFilter` 使用
  `FilterContext.withV2Ratios(...)`，**保证 AI 池子走 V2 ratio 优先路径**
- `config/AgentProperties.java` + `application.yml`：业务阈值下放到配置
  - `quant.selection.universe.min-market-cap-usd = 20000000`
  - `quant.selection.universe.min-circulation-ratio = 0.50`
  - `quant.selection.universe.min-volume-mcap-ratio = 0.20`
  - `quant.selection.universe.top-candidate-limit = 30`
  - `quant.market.max-24h-range-pct = 40`
  - `quant.market.max-spread-bps = 50`
  - `quant.market.min-depth-usdt = 100000`

### 1.3 新增迁移

`db/migration/V37__selection_v2_universe.sql` — 给 `agent_contract_candidate_score`
表追加 5 个 V2 列：

```sql
ALTER TABLE agent_contract_candidate_score ADD COLUMN market_cap_usd       DECIMAL(20, 6) NULL;
ALTER TABLE agent_contract_candidate_score ADD COLUMN fdv_usd              DECIMAL(20, 6) NULL;
ALTER TABLE agent_contract_candidate_score ADD COLUMN circulation_ratio    DECIMAL(10, 6) NULL;
ALTER TABLE agent_contract_candidate_score ADD COLUMN volume_mcap_ratio    DECIMAL(10, 6) NULL;
ALTER TABLE agent_contract_candidate_score ADD COLUMN market_quality_note VARCHAR(120)   NULL;
```

**V32 迁移文件未动**，与其他 V1-V36 迁移一同跑通。V37 之后 `flyway_schema_history` 版本
号为 v37。

---

## 2. 测试矩阵

### 2.1 新增单测（本次提交）

| 测试 | 范围 | 关键断言 |
| --- | --- | --- |
| `MarketCapFilterTest` | 5 个边界：FRESH 达标 / FRESH 未达 / STALE / TIMEOUT / UNAVAILABLE | 硬资格 + DataQuality 短路 |
| `CirculationFilterTest` | 6 个边界：V2 ratio 优先 / snapshot 绝对值回退 / JUST below 50% / UNKNOWN / STALE / UNAVAILABLE | 双路径都覆盖 |
| `VolumeMcapFilterTest` | 5 个边界：V2 ratio 优先 / snapshot 回退 / JUST below 20% / TIMEOUT / STALE | DataQuality 短路在 ratio 计算前 |
| `PriceRangeFilterTest` | 4 个边界：24h range 0% / 20% / 39% / 40.1% | 上限不卡低活跃 |
| `LiquidityFilterTest` | 4 个边界：null depth PASS / 大 spread REJECT / 小 depth REJECT / big depth PASS | 兜底保守 |
| `UniverseHardFilterTest` | 6 个：PASS / REJECT / 多级短路 / 漏斗 / 提供完整 FilterContext | SingleOutcome + FilterFunnel |
| `NullMarketCapProviderTest` | 4 个：UNAVAILABLE / 错误消息 / ProviderCache 包装 / 多次取一致 | `errorMessage = "configured"` |
| `ProviderCacheTest` | 4 个：freshTtl 命中 / 过期 stale / 过期 refresh / null fetch | 双窗口 |
| `AiContractSelectionServiceV2PoolTest` | 13 个：5 拒绝原因各 1 + dedup + 截断 + V2 优先 + UNKNOWN ratio 兜底 | AI 池子与 V2 扫描共享 |
| `OkxContractScannerV2Test` | 4 个：**架构级 top30 截断反转** + V2 全过 + NullProvider + ProviderCache 复用 | 见 § 2.3 |
| `V37MigrationTest` | 4 个：文件结构 / ALTER 数量 / 顺序严格递增 / Flyway runtime 验证列 | 见 § 2.4 |

### 2.2 修改单测

- `UniverseHardFilterTest`：适配 V2 ratio 路径 + `r.result()` API + BigDecimal 精度
  trap（`4900000/10000000` 而不是 `4999999/10000000`）
- `NullMarketCapProviderTest`：新增 `errorMessage="configured"` 两条断言
- `ProviderCacheTest`：把 `r.reason()` 修正为 `r.errorMessage()`（API 实际方法名）

### 2.3 架构级测试 — `OkxContractScannerV2Test#nonTop30WithQualityCanEnterV2`

**测试目的**：锁住"tickers → broad universe → Universe Hard Filter → deep analysis"
这一架构选择，防止回退到"先 volume 排序后取 top30"的旧设计。

**场景**：

```text
31 个 universe 输入：
  - 30 个 "OLD0..OLD29" ticker：volume 5B（成交额最高）但 market cap = 10M
    （低于 20M 硬资格）→ 应在 MarketCapFilter 被拒
  - 1 个 "SLEEPER" ticker：volume 仅 5M（远低于 OLD*，按成交额排不进 Top 30）
    但 market cap = 20M + circulation = 60% + vol/mcap = 25% → 应通过全部硬资格
    并进入 deep analysis
```

**断言**：

- `diag.universeTickers() == 31`（包含 30 个 OLD* + 1 个 SLEEPER）
- `diag.passedCount() == 1`（仅 SLEEPER）
- `diag.rejectedCount() == 30`（30 个 OLD* 被 MarketCapFilter 拒）
- `diag.deepAnalysisTargetCount() == 1`（V2 deep analysis 目标数等于 passed）
- `diag.providerStatus().get("market_cap") == FRESH`（数据新鲜）
- `scan()` 输出包含 `"SLEEPER-USDT-SWAP"`（即使它不是 volume Top 30）
- `scan()` 输出不包含 `"OLD0-USDT-SWAP"` / `"OLD29-USDT-SWAP"`（被 V2 拒）

**通过条件**：`mvn -o test -Dtest=OkxContractScannerV2Test` 必须 4/4 通过。

**修复的 Phase B 设计 bug**：在写这个测试时发现，**最初 V2 扫描路径有严重设计
bug**——V2 把 FilterContext 走 `provider.snapshot.circulatingSupply` 路径，但
NullMarketCapProvider 不提供供应量字段，导致 CirculationFilter 永远返回
`MISSING`，**V2 路径永远 0 passed，AI eligible pool 永远空**。这一 bug 被本测
试发现并修复：

- 给 FilterContext 加候选级 V2 ratio 字段（`circulationRatio` / `volumeMcapRatio`）
- CirculationFilter / VolumeMcapFilter 改为 **ratio 优先路径**
- AiContractSelectionService / OkxContractScanner 全部统一用 `withV2Ratios(...)`

修复后 V2 路径在 null provider 下退化为 `UNAVAILABLE`（不是 `MISSING`），不
会"假阳性全过"也不会"假阴性全拒"，与设计意图一致。

### 2.4 运行时验证 — `V37MigrationTest`

**结构性**：

- `V37__selection_v2_universe.sql` 存在
- 包含 5 个 V2 字段（`market_cap_usd` / `fdv_usd` / `circulation_ratio` /
  `volume_mcap_ratio` / `market_quality_note`）
- 恰好 5 条 `ALTER TABLE` 语句（H2 MySQL 模式不支持单语句多列 ADD）
- 作用对象为 `agent_contract_candidate_score`
- V32（counterfactual_exit）未触碰
- 所有 migration 版本号严格递增

**运行时**（Spring Boot + Flyway + H2 内存）：

- Flyway 成功应用 36 条迁移到 v37
- `INFORMATION_SCHEMA.COLUMNS` 查询到 5 个 V2 列（小写形式，因为 H2
  `DATABASE_TO_LOWER=TRUE` + 标识符小写化）

### 2.5 TokenSymbolMapper — 不补

`TokenSymbolMapperTest` 已覆盖（5 个测试）：

- 1000x meme multiplier（`1000PEPE` / `1000BONK` / `1000FLOKI`）
- 标准 swap（`BTC-USDT-SWAP`）
- spot 也工作（`BTC-USDT`）
- null / empty / 期货合约（`BTC-USD-SWAP-260328`）抛 `SymbolMappingException`
- `isValid` regex 边界

**没有真实 gap**——按用户原话"TokenSymbolMapper 真实场景 (only if real gaps)"，
判断当前覆盖已经完整，**不补**。

---

## 3. 数据流（V2 路径）

```text
OkxRestClient.publicGet("/api/v5/market/tickers")
            ↓
      List<ContractTicker>  (broad universe, 全部 USDT-SWAP)
            ↓
   ── 体积截断已删除 ──
            ↓
  MarketCapProvider.fetch(base)  (per-ticker, ProviderCache 命中复用)
            ↓
   ScanDiagnostics.universeTickers = N
            ↓
  UniverseHardFilter.apply(tickers, context)
            ↓
   ScanDiagnostics.passedCount / rejectedCount / funnel
            ↓
  deep analysis (per passed ticker)
   - OrderBookLiquidityService
   - ContractNewsAnalysisService
   - ContractSignalAnalyzer
            ↓
  List<ContractCandidate>  (ranked, topCandidateLimit 上限)
```

**关键不变式**：

1. `tickers.size() == diag.universeTickers()`：universe 永远等于输入，不被截断
2. `passed + rejected == universeTickers`：没有候选"消失"
3. `deepAnalysisTargetCount == passedCount`：deep 不会无界

---

## 4. 兼容性 / 不变项

| 不变项 | 状态 |
| --- | --- |
| 516 个原测试 | 0 回退（最终 638/638 通过） |
| V32 migration（counterfactual_exit） | 未触碰 |
| Trade execution / 止损 / PositionQualityPolicy | 未触碰 |
| `finalRankScore ≤ 55` 拒绝上限 | 保持原状（**未恢复**） |
| AI 路径可绕过 Universe Hard Filter | 不可（`AiContractSelectionService.passesUniverseHardFilter` 是硬闸） |
| 业务阈值硬编码 | 已下放到 `application.yml` |

---

## 5. 后续工作（不在 Phase B 范围）

- 实盘接入 `MarketCapProvider`：当前默认 `NullMarketCapProvider`，生产需接
  CoinGecko / CoinMarketCap / OKX on-chain 等
- V38 迁移：把 `agent_loop_run` 关联 V2 漏斗指标（passed / rejected / providerStatus）
- Provider 注入 Solon 容器并配 `@ConditionalOnProperty`
- Sleeper 标的的"二次进入监控"——Phase B 是一次性 deep analysis，C 阶段要加
  持续追踪（V38+ 一起做）
