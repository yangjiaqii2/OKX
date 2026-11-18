# Wave 5 Historical Candidate Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Persist immutable V2 candidate evidence per scan cohort, reconcile durable 1h/4h/24h market outcomes, and provide read-only rank/dimension/coverage evaluation without changing production selection or trading.

**Architecture:** Extend the existing `ContractCandidateScoreRecorder` and `agent_contract_candidate_score` table with explicit Wave 5 snapshot fields. Use the existing persisted scan-run ID as the durable cohort identifier. Store future observations in a separate `candidate_future_outcome` table with a unique `(snapshot_id, horizon_minutes)` key. Reconciliation reads confirmed 15m candles once per instrument and offline evaluation reads only the two historical tables.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring Data JPA, Flyway, H2/MySQL-compatible tests, existing `OkxMarketService` candle adapter, JUnit 5/AssertJ/Mockito.

## Global Constraints

- Do not modify V2 score formulas, confidence, weights, V1 score, 55 ceiling, TradeGate, execution, risk, protection, SL/TP, leverage, position size, providers, or external APIs.
- Do not modify `ContractSignalAnalyzer.java`, `AutoTradeService.java`, `PositionQualityPolicy.java`, `ProtectionOrderService.java`, `agent/trade/*`, or `crypto/risk/*`.
- Snapshot stores T0 facts and scoring version; outcome is separate mutable evidence.
- Missing values remain `NULL`; missing future candles never become zero return.
- Historical persistence failure is observational and must not change the candidate list or fail the scan task.
- No automatic weight optimization, production feedback, PnL/order simulation, or real trading call.

---

### Task 1: Extend the existing scan recorder into an immutable V2 snapshot cohort

**Files:**
- Modify: `src/main/java/com/example/quant/agent/score/AgentContractCandidateScoreEntity.java`
- Modify: `src/main/java/com/example/quant/agent/score/AgentContractCandidateScoreRepository.java`
- Modify: `src/main/java/com/example/quant/agent/score/ContractCandidateScoreRecorder.java`
- Modify: `src/main/java/com/example/quant/crypto/ContractAnalysisService.java`
- Modify: `src/main/java/com/example/quant/crypto/OkxContractScanner.java`
- Test: `src/test/java/com/example/quant/agent/score/ContractCandidateScoreRecorderTest.java`
- Test: `src/test/java/com/example/quant/crypto/ContractAnalysisServiceTest.java`

**Interfaces:**
- `OkxContractScanner#isV2Mode()` exposes only whether the already-selected scanner path is V2; it does not alter scanning.
- `ContractCandidateScoreRecorder#recordLiveScan(List<ContractCandidate>, Instant, Instant, boolean)` records rank order and V2 facts when the scanner is V2; the existing three-argument method remains compatible for old tests/callers.
- `AgentContractCandidateScoreEntity` stores `scanRunId` as the durable cohort ID, `snapshotRank`, `referencePrice`, `scoringVersion`, normalized/ranking/coverage fields, nullable dimension scores, quality labels, and explicit raw feature fields.

- [ ] Write tests that capture `saveAll` rows and assert one shared scan-run ID, ranks 1..N, scan-time reference price, `V2_WAVE_4_2`, nullable missing dimensions, and stored V2 scores.
- [ ] Write a test that changes/replaces the source candidate after recording and proves persisted snapshot values do not follow the later object.
- [ ] Write a test that recorder/research persistence failure is logged and does not throw to its caller.
- [ ] Run the focused tests and observe the expected RED failure for missing fields/methods.
- [ ] Add the nullable JPA fields/getters/setters and explicit mapping from `ContractCandidate`, provider snapshots, `V2SelectionScoreCalculator`, `OIDirection.classify`, and existing signal/direction fields.
- [ ] Add the V2-mode accessor and call the four-argument recorder only as an observational side path after `scanner.scan()` returns; preserve the existing V1 recorder path.
- [ ] Run the focused tests and verify GREEN.

### Task 2: Add V38 outcome storage and durable pending rows

**Files:**
- Create: `src/main/java/com/example/quant/agent/evaluation/CandidateFutureOutcomeEntity.java`
- Create: `src/main/java/com/example/quant/agent/evaluation/CandidateFutureOutcomeRepository.java`
- Modify: `src/main/java/com/example/quant/agent/score/ContractCandidateScoreRecorder.java`
- Create: `src/main/resources/db/migration/V38__historical_candidate_outcomes.sql`
- Test: `src/test/java/com/example/quant/agent/score/ContractCandidateScoreRecorderTest.java`

**Interfaces:**
- `CandidateFutureOutcomeEntity` has `snapshotId`, `horizonMinutes` (60/240/1440), `maturityAt`, status, future timestamp/price, raw/directional return, raw max-upside/max-downside, directional MFE/MAE, error reason, and update timestamps.
- `CandidateFutureOutcomeRepository` supports `findByStatusInAndMaturityAtOrBefore`, `findBySnapshotIdAndHorizonMinutes`, and `findBySnapshotIdIn`.
- Initial snapshot persistence creates exactly three `PENDING` rows per snapshot. The database unique key is `(snapshot_id, horizon_minutes)`.

- [ ] Add tests for three pending horizons, exact maturity timestamps, no zero defaults, and repository-level idempotency behavior.
- [ ] Run tests RED before adding the migration/entity.
- [ ] Add V38 using the actual current maximum V37; keep all new snapshot columns nullable and add indexes for cohort/rank/version, outcome maturity/status, and the unique snapshot-horizon key.
- [ ] Persist pending outcome rows from the existing recorder after snapshot rows are saved; never serialize the whole Java object as the primary schema.
- [ ] Run H2 migration-focused tests GREEN.

### Task 3: Implement clock-driven outcome reconciliation with one candle fetch per instrument

**Files:**
- Create: `src/main/java/com/example/quant/agent/evaluation/HistoricalCandidateOutcomeReconciliationService.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/example/quant/agent/evaluation/HistoricalCandidateOutcomeReconciliationServiceTest.java`

**Interfaces:**
- `reconcile(Instant now)` returns a diagnostics record with pending/completed/failed counts for each horizon.
- A scheduled wrapper invokes `reconcile(clock.instant())`; no per-candidate timers are created.
- For each instrument, one `OkxMarketService#candles(instId, "15m", 300)` call supplies all three horizons. The canonical future price is the latest confirmed candle close whose timestamp is at or before the horizon target and after T0; a missing/too-old candle remains non-complete.
- `rawReturn = (futurePrice - referencePrice) / referencePrice` as a decimal ratio.
- `maxUpside = (maxHigh - P0) / P0`; `maxDownside = (minLow - P0) / P0`.
- For LONG, directional return is raw return, MFE is `max(maxUpside, 0)`, and MAE is `max(-maxDownside, 0)`; for SHORT, directional return is negated, MFE is `max(-maxDownside, 0)`, and MAE is `max(maxUpside, 0)`. Unknown direction leaves directional fields NULL while retaining raw fields.
- API failure uses `FAILED` with an error reason; missing candles use retryable `PARTIAL`; `COMPLETE` rows are never recomputed.

- [ ] Write Clock-based tests for 30m, 1h+5m, 4h, and 24h maturity boundaries.
- [ ] Write tests for decimal returns, LONG/SHORT raw-vs-directional return, MFE/MAE, missing candles, provider failure, and confirmed-candle selection.
- [ ] Write a test proving repeated `reconcile()` creates no duplicate outcome and a fresh service instance discovers persisted pending rows.
- [ ] Write a test proving one instrument fetch serves all mature horizons.
- [ ] Run the focused tests RED.
- [ ] Implement grouping by `instId`, safe timestamp sorting, one candle fetch, horizon derivation, retryable states, and scheduled diagnostics.
- [ ] Run focused reconciliation tests GREEN.

### Task 4: Implement read-only offline ranking evaluation

**Files:**
- Create: `src/main/java/com/example/quant/agent/evaluation/HistoricalCandidateEvaluationService.java`
- Create: `src/test/java/com/example/quant/agent/evaluation/HistoricalCandidateEvaluationServiceTest.java`

**Interfaces:**
- `evaluate(String scoringVersion, int minimumSampleSize)` returns an immutable report grouped by horizon.
- The report contains candidate/complete/missing counts, rank buckets `1-5`, `6-10`, `11-20`, `21+`, coverage buckets `low`, `medium`, `high`, and per-dimension score buckets `[0,20)`, `[20,40)`, `[40,60)`, `[60,80)`, `[80,100]`.
- Bucket metrics contain sample count, insufficient-sample flag, average/median raw return, average/median directional return, hit rate (`directionalReturn > 0`), average ranking score, average MFE, and average MAE. A NULL dimension is excluded from that dimension’s buckets; NULL directional return is excluded from hit-rate denominator.
- Coverage buckets reuse Wave 4.2 semantics: low `< 0.50`, medium `0.50.. < 0.85`, high `>= 0.85`.
- No production score or scanner method is called by the evaluation service.

- [ ] Write tests for rank boundaries 1/5/6/10/11/20/21, dimension boundaries 0/20/40/60/80/100/NULL, coverage groups, median, hit-rate denominator, and low-sample `insufficientSample=true`.
- [ ] Write a scoring-version isolation test for `V2_WAVE_4` versus `V2_WAVE_4_2`.
- [ ] Run focused evaluation tests RED.
- [ ] Implement repository reads, horizon grouping, bucket classification, null-safe statistics, and minimum-sample reporting without any recommendation label.
- [ ] Run focused evaluation tests GREEN.

### Task 5: Document and prove production isolation

**Files:**
- Modify: `docs/CODEBASE_DOC.md`
- Modify: `src/test/java/com/example/quant/crypto/ContractAnalysisServiceTest.java`
- Modify: `src/test/java/com/example/quant/task/ContractMarketScanTaskTest.java` only if the existing seam is required for isolation coverage

**Interfaces:**
- Documentation records the real `scan() -> recorder -> snapshot -> reconciliation -> evaluation` chain, `scanRunId` cohort semantics, V38 schema, formulas, statuses, retry/restart behavior, and one-way isolation.
- Isolation tests prove recorder failure does not alter the scanner result or invoke execution dependencies.

- [ ] Add a regression test for unchanged returned candidates when observational persistence fails.
- [ ] Add the architecture and schema notes without changing score formulas or trade paths.
- [ ] Run Markdown/diff checks.

### Task 6: Full verification and scope audit

**Files:**
- No additional production files.

- [ ] Run `mvn -o test` with `-DskipTests=false` only if the project default requires it, and record the complete count.
- [ ] Run `mvn -o verify` and inspect the final result.
- [ ] Run `git diff --check`.
- [ ] Run `git status --short` and verify the Wave 5 file list while preserving all pre-existing dirty changes.
- [ ] Confirm no redline file, V2 score implementation, trading/risk/protection path, provider, HTTP client, or frontend page was modified by Wave 5.

