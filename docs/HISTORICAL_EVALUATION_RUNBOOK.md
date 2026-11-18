# Historical Evaluation Burn-in Runbook

## 目的与边界

本 runbook 用于 Wave 5/5.1/5.2 的 Historical Candidate Snapshot、Future Outcome 和 Offline Evaluation burn-in 检查。它只读取历史证据并报告数据健康状态，不修改 snapshot/outcome，不重算历史分数，也不触发交易、风险或保护逻辑。

当前生产数据状态如果没有经过生产数据库只读查询确认，必须报告为 `NO_REAL_SAMPLE_YET` / `NOT_VERIFIED_AGAINST_PRODUCTION_DB`，不得用测试 fixture 代替。

## 真实运行链路

```text
ContractMarketScanTask.runOnce()
  -> ContractAnalysisService.refreshCandidates()
  -> OkxContractScanner.scan() / scanV2()
  -> ContractCandidateScoreRecorder.recordLiveScan()
  -> agent_scan_run
  -> agent_contract_candidate_score
  -> candidate_future_outcome (PENDING, 60/240/1440 minutes)

HistoricalCandidateOutcomeReconciliationService.reconcileScheduled()
  -> CandidateFutureOutcomeRepository.findRecentDue() / findHistoricalDue()
  -> OKX public 15m candles (recent window or timestamp-anchored history)
  -> confirmed 15m window calculation
  -> candidate_future_outcome update

HistoricalCandidateEvaluationService.evaluate(scoringVersion, minimumSampleSize)
  -> COMPLETE outcomes only
  -> rank / coverage / dimension bucket metrics
```

`ContractAnalysisService.scanLiveCandidates()` 在 snapshot 写入失败时记录 warning 并返回原 scanner 结果；历史记录是 observational side path。 `HistoricalCandidateEvaluationService` 不返回生产 scanner、AutoTrade、TradeGate、risk 或 protection 链路。

## 当前配置与调度

当前仓库没有 `application-prod.yml`；生产运行时使用 `application.yml` 和环境变量覆盖。关键默认值如下：

| 项目 | 当前值 | 来源 |
| --- | ---: | --- |
| contract scan | 60 秒 fixed rate，另有 ApplicationReady 首次运行 | `quant.contract.scan-interval-seconds` |
| outcome reconcile | 120000 ms fixed delay（2 分钟） | `quant.agent.evaluation.outcome-reconciliation-interval-ms` |
| reconcile batch | 100 条，最低 2 条 | `quant.agent.evaluation.outcome-reconciliation-batch-size` |
| retry delay | 300000 ms（5 分钟） | `quant.agent.evaluation.outcome-reconciliation-retry-delay-ms` |
| stuck threshold | 21600000 ms（6 小时） | `quant.agent.evaluation.outcome-reconciliation-stuck-threshold-ms` |
| scheduling | Spring `@EnableScheduling` | `QuantAssistantApplication` |

Historical recording 没有单独的 `historicalEvaluation.enabled` 开关。当前 contract scan 启用时，recorder 由 `ContractAnalysisService` 尝试执行；reconciliation service 也没有 profile/property 条件。 `quant.agent.enabled` 不应被当作 Historical Evaluation 的开关。需要关闭时应在部署配置层明确停用 contract scan 或后续补充专用开关，不要手工改历史数据。

reconciliation 使用单 JVM `ReentrantLock` 包住一次 `reconcile()`。仓库没有发现自定义 `TaskScheduler`、Spring scheduler pool 配置或 distributed lock。单 JVM burn-in 可接受；如果生产部署超过一个应用 JVM，当前锁不能跨实例保护，必须先停止 burn-in 并单独设计分布式协调。

## Flyway 检查

Wave 5.2 源码中的最大 migration 是 `V39`。 `V38` 创建 snapshot 扩展列和 `candidate_future_outcome`，`V39` 增加 retry/observation/window 字段、horizon check constraint 和 retry index。不要编辑旧 migration，也不要修改 `flyway_schema_history`。

在具有生产只读权限的 MySQL session 中执行：

```sql
SET SESSION time_zone = '+00:00';

SELECT installed_rank, version, description, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 20;

SELECT installed_rank, version, description, success, installed_on
FROM flyway_schema_history
WHERE version IN ('38', '39')
ORDER BY installed_rank;
```

源码/H2 migration tests 只能证明资源和顺序在测试环境可应用，不能代替上述生产数据库检查。

## 只读健康 SQL

以下 SQL 以真实 V38/V39 schema 为准。执行前使用 UTC session；`agent_scan_run.created_at`、snapshot `created_at`、outcome `maturity_at`/`updated_at` 都按 UTC `Instant` 语义解释。所有查询均为 SELECT（`SET SESSION` 只设置当前查询 session 时区）。

### 1. 最近 scan cohort

```sql
SELECT id, status, started_at, finished_at, candidate_count, created_at
FROM agent_scan_run
ORDER BY created_at DESC
LIMIT 20;
```

### 2. 最近 snapshot 与版本

```sql
SELECT scan_run_id,
       MAX(created_at) AS latest_snapshot_at,
       COUNT(*) AS persisted_snapshot_count,
       MAX(snapshot_rank) AS max_rank,
       GROUP_CONCAT(DISTINCT scoring_version ORDER BY scoring_version) AS scoring_versions
FROM agent_contract_candidate_score
GROUP BY scan_run_id
ORDER BY latest_snapshot_at DESC
LIMIT 50;
```

当前第一批 V2 burn-in 应单独筛选 `scoring_version = 'V2_WAVE_4_2'`，不要把 `V2_WAVE_4` 混入同一份报告：

```sql
SELECT scoring_version, COUNT(*) AS snapshot_count
FROM agent_contract_candidate_score
GROUP BY scoring_version
ORDER BY scoring_version;
```

### 3. cohort 完整性

`agent_scan_run.candidate_count` 是预期 snapshot 数；实际持久化数量由 score 表按 `scan_run_id` 统计。只有 `SUCCESS` 且两者相等的 cohort 才能进入 offline evaluation；Evaluation service 也会执行相同的排除语义。

```sql
SELECT r.id AS scan_run_id,
       r.status,
       r.created_at,
       r.candidate_count AS expected_snapshot_count,
       COUNT(s.id) AS persisted_snapshot_count,
       CASE
         WHEN r.status = 'SUCCESS'
          AND r.candidate_count = COUNT(s.id) THEN 'COMPLETE'
         ELSE 'INCOMPLETE'
       END AS cohort_state
FROM agent_scan_run r
LEFT JOIN agent_contract_candidate_score s ON s.scan_run_id = r.id
GROUP BY r.id, r.status, r.created_at, r.candidate_count
ORDER BY r.created_at DESC
LIMIT 100;
```

### 4. outcome 状态与 horizon

```sql
SELECT horizon_minutes, status, COUNT(*) AS outcome_count
FROM candidate_future_outcome
GROUP BY horizon_minutes, status
ORDER BY horizon_minutes, status;
```

`60`、`240`、`1440` 分别表示 `1h`、`4h`、`24h`。不要用字符串 `1h`/`60m` 另造 horizon；数据库和 domain 只接受这三个整数。

最近完成时间和当前队列：

```sql
SELECT MAX(created_at) AS latest_snapshot_at
FROM agent_contract_candidate_score;

SELECT MAX(updated_at) AS latest_completed_outcome_at
FROM candidate_future_outcome
WHERE status = 'COMPLETE';

SELECT horizon_minutes, status, COUNT(*) AS updated_recent_count
FROM candidate_future_outcome
WHERE updated_at >= UTC_TIMESTAMP() - INTERVAL 1 DAY
GROUP BY horizon_minutes, status
ORDER BY horizon_minutes, status;
```

### 5. stuck outcomes

默认 stuck threshold 是 maturity 后 6 小时。 `error_reason` 是实际字段名，不是 `last_error`。

```sql
SELECT id, snapshot_id, horizon_minutes, status,
       maturity_at, retry_count, last_attempt_at,
       next_retry_at, error_reason, updated_at
FROM candidate_future_outcome
WHERE maturity_at < UTC_TIMESTAMP() - INTERVAL 6 HOUR
  AND status NOT IN ('COMPLETE', 'UNAVAILABLE')
ORDER BY maturity_at
LIMIT 200;

SELECT COUNT(*) AS stuck_count
FROM candidate_future_outcome
WHERE maturity_at < UTC_TIMESTAMP() - INTERVAL 6 HOUR
  AND status NOT IN ('COMPLETE', 'UNAVAILABLE');
```

### 6. retry 状态

```sql
SELECT status,
       retry_count,
       COUNT(*) AS outcome_count,
       MIN(last_attempt_at) AS first_attempt_at,
       MAX(next_retry_at) AS latest_next_retry_at
FROM candidate_future_outcome
GROUP BY status, retry_count
ORDER BY status, retry_count;
```

### 7. 最近 cohort/snapshot 快照量

```sql
SELECT COUNT(*) AS successful_cohort_count,
       COALESCE(SUM(candidate_count), 0) AS expected_snapshot_count
FROM agent_scan_run
WHERE status = 'SUCCESS'
  AND created_at >= UTC_TIMESTAMP() - INTERVAL 1 DAY;

SELECT COUNT(*) AS persisted_snapshot_count
FROM agent_contract_candidate_score
WHERE created_at >= UTC_TIMESTAMP() - INTERVAL 1 DAY;
```

如果需要按某个 burn-in 日历日检查，使用明确的 UTC 起止参数替代 `UTC_TIMESTAMP() - INTERVAL 1 DAY`，避免把本地日历日误当 UTC 日历日。

## 时间与 candle 窗口规则

`scanTime` 使用 snapshot 的 `created_at`（UTC `Instant`），`maturityAt = scanTime + horizon`。 `OkxMarketService.candles(instId, "15m", 300)` 只保留 OKX response 中 `confirm = 1` 的 candle，并按 timestamp 转成 chronological `Instant`。

当前 canonical full-bar rule：

```text
firstOpen = scanTime 之后的第一个 15m 边界
lastOpen  = maturityAt - 15m 之前（含）的最后一个 15m 边界
included  = firstOpen..lastOpen 的每一根已确认 candle
```

每根 candle 必须满足：`open >= scanTime` 且 `open + 15m <= maturityAt`。必须存在完整且连续的预期 bar 集合，才允许 `PARTIAL -> COMPLETE`；只有一根连续 candle 不足以证明 horizon 完整。

例如 `scanTime = 10:07`、`1h maturityAt = 11:07`：使用 `10:15`、`10:30`、`10:45`，排除包含 scan 前行情的 `10:00`，也排除到 `11:15` 才结束的 `11:00`。因此这是可重复的“完整 15m bar 子窗口”，不是用未完成 candle 或估算值制造精确 11:07 价格。

`futurePrice` 是 included window 最后一根已确认 candle 的 close；`futureTimestamp` 是该 candle 的 open timestamp；`observedAt` 是该 bar 的结束时间。 `maxUpside`/`maxDownside` 扫描同一 included window 的全部 high/low。中间缺 bar 或预期 bar 数不足保持 `PARTIAL`，不会写 `0` return。

`COMPLETE` outcome 在正常 reconciliation 中不可覆盖。 `PENDING`、`PARTIAL`、`FAILED` 会按 `next_retry_at` 重试；`UNAVAILABLE` 不进入 due 队列，也不进入 COMPLETE evaluation 样本。

## Burn-in 验收

每次验收只使用 `scoring_version = 'V2_WAVE_4_2'` 的记录，并保留查询时间和 SQL 输出：

1. 一个成功 scan 后，`agent_scan_run` 出现新 cohort，snapshot 数等于 `candidate_count`，rank 与该次 scanner 最终顺序一致。
2. 每个 V2 snapshot 有且只有 `60/240/1440` 三条唯一 outcome，初始为 `PENDING`。
3. 队列无积压时，成熟且具备完整已收盘窗口的 outcome 在下一轮回填后进入 `COMPLETE`。暂时缺数据时记录 `PARTIAL` 或 `FAILED` 及重试信息。
4. 短暂 market API 失败后，`FAILED` 在 `next_retry_at` 到达后重新尝试；数据恢复后允许 `FAILED -> COMPLETE`。不得手工更新状态或价格。
5. 正常运行的 stuck count 应接近 0；出现 stuck 时先查 `retry_count`、`last_attempt_at`、`next_retry_at`、`error_reason` 和 OKX 数据，不自动删除或修复。
6. 不完整 cohort 不进入 offline evaluation；不存在的 rank bucket 不按零收益计数。

## 运行状态标签

这些标签只用于离线数据健康报告，不影响 scanner 或生产决策：

```text
NO_DATA                       没有成功 cohort 或没有 outcome 记录
LOW_SAMPLE                    有记录，但完整 cohort / 24h COMPLETE 样本不足
EARLY_SAMPLE                  已有多个 cohort 和至少一批完整 24h outcome，仍只用于观察
USABLE_FOR_INITIAL_ANALYSIS  由报告调用方按扫描频率设定最低 cohort、候选和 24h 样本门槛后标记
```

当前环境声明为 `NO_REAL_SAMPLE_YET`，除非使用本 runbook 对生产数据库进行了只读验证。即便达到最后一个标签，也只表示数据集适合开始初步分析，不代表策略、因子或排名已经有效。

## 近期与历史回填

- 最近 48 小时内成熟的记录使用最近 300 根 15 分钟 K 线，约覆盖 75 小时。较早成熟的记录使用公开历史行情接口，以成熟时间向上对齐小时作为 `after`，取该点以前最多 100 根 K 线。100 根最多覆盖 25 小时，同一合约和查询锚点共用一页。
- 默认每轮历史积压最多 20 条，近期最多 100 条。有积压时近期至少保留 80 个名额。两类队列各自优先处理从未尝试的记录，之后轮转重试已尝试的记录。实际吞吐受 fixed delay、市场接口响应时间和共享调度器负载影响。
- 结果仍要求到期前完整、连续且已收盘的窗口。一页有缺口时保留 `PARTIAL`，行情请求失败时保留 `FAILED`，两者按原重试间隔再次入队。市场历史不可用或标的已下架时，积压可能无法全部回填。
- 回填量级应通过重新上线后的只读统计验证。默认历史配额在每轮处理耗时趋近于零时约为 600 条/小时，约 24 万条历史积压对应约 17 天的一次尝试，另需计入实际耗时和重试。

