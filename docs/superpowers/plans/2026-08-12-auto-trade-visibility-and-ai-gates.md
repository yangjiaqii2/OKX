# 自动交易可用性与 AI 软硬规则修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 DEMO 自动交易的配置、运行状态、AI 决策输入和审计页面保持一致且可验证。

**Architecture:** 保留现有双 Guard、AI 选择器和审计模型，只在共享状态接口、AI allowlist Prompt、分页排序与前端列上修正语义；JDBC 会话统一为 UTC。根目录 `.env` 继续作为 Docker 配置源。

**Tech Stack:** Java 17、Spring Boot 3.2、Spring Data JPA、MySQL 8.4、React、TypeScript、MUI、Docker Compose。

## Global Constraints

- 仅使用 `TRADING_MODE=DEMO` 验证，绝不自动切换 LIVE。
- AI 不得绕过资金安全硬 Guard；合法 WAIT 仍不交易。
- 不修改策略阈值、指标算法或收益参数。
- 当前工作区已有大量未提交成果，不创建提交、不覆盖无关改动。

---

### Task 1: 可信自动交易运行状态

**Files:**
- Modify: `src/test/java/com/example/quant/controller/SystemControlControllerTest.java`
- Modify: `src/main/java/com/example/quant/controller/SystemControlController.java`

**Interfaces:**
- Consumes: `SystemControlService.status()`、`AgentProperties.autoTrade().enabled()`。
- Produces: `RuntimeStatus.strategyEngine()`、`tradingEngine()`、`tradingReason()`。

- [ ] 写失败测试：持久化运行开关开启但配置 Guard 关闭时，期望 `AUTO_DISABLED_BY_CONFIG / MANUAL_CONFIRM / AUTO_TRADE_DISABLED_BY_CONFIG`。
- [ ] 运行 `mvn -DskipTests=false -Dtest=SystemControlControllerTest test`，确认旧实现错误返回 `AUTO_RUNNING / AUTO_ENABLED`。
- [ ] 给控制器注入 `AgentProperties`，实现上述最小状态组合。
- [ ] 重跑聚焦测试并确认通过。

### Task 2: AI 软硬规则语义

**Files:**
- Modify: `src/test/java/com/example/quant/agent/selection/AiContractSelectionServiceTest.java`
- Modify: `src/main/java/com/example/quant/agent/selection/AiContractSelectionService.java`

**Interfaces:**
- Consumes: 已通过 `aiHardGate` 的 `ContractCandidate`。
- Produces: 只包含 `softRuleSignal`、`softRuleScore`、`softRuleReasons`、`softRuleDiagnostics` 的候选 JSON。

- [ ] 写失败测试并捕获 Prompt，断言明确出现“软规则不是硬否决”，且不再发送 `riskTags` 字段。
- [ ] 运行 `mvn -DskipTests=false -Dtest=AiContractSelectionServiceTest test`，确认失败。
- [ ] 重命名候选字段并收紧 System Prompt；不改变 JSON 输出协议和 WAIT 语义。
- [ ] 重跑选择器与自动交易测试。

### Task 3: 审计顺序和前端决策字段

**Files:**
- Modify: `src/test/java/com/example/quant/agent/execution/AutoTradeRecordServiceTest.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeRecordService.java`
- Modify: `frontend/src/pages/AutoTradeRecordList.tsx`

**Interfaces:**
- Produces: `GET /auto-trade-records` 按 `id DESC`，前端同时展示 `candidateScore` 与 `finalRankScore`、`signalType`、AI 审计字段。

- [ ] 写失败测试捕获 `Pageable`，断言排序字段为 `id DESC`。
- [ ] 运行聚焦测试确认旧实现按 `createdAt` 失败。
- [ ] 最小修改后端排序和表格列，不新增依赖。
- [ ] 运行后端测试、`npm run tsc` 和 `npm run build`。

### Task 4: UTC 与 DEMO 配置恢复

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `.env`（Git 忽略，迁移已有 AI 配置，不输出 Secret）
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- JDBC: `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`。
- Runtime: `TRADING_MODE=DEMO`、`QUANT_AGENT_AUTO_TRADE_ENABLED=true`、AI 配置存在。

- [ ] 将 MySQL 连接会话统一为 UTC。
- [ ] 安全合并 `src/main/resources/.env` 中已有 AI 配置到根 `.env`，不打印密钥；确认 Demo 和自动交易配置。
- [ ] 更新中文文档变更日志。
- [ ] 完整执行后端测试、前端构建、`git diff --check`。
- [ ] 重建并重启 DEMO 容器，验证 runtime-status、AI 配置存在、新记录时间与扫描日志。
