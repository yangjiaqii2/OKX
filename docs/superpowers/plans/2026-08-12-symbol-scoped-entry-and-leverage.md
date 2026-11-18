# Symbol-scoped Entry and Leverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 允许不同合约在安全容量内并行等待入场，并把新订单杠杆范围扩展为 1–10 倍。

**Architecture:** 用合约集合替代账户级活跃入场布尔锁，将本地与 OKX 活跃入场合约合并进容量计算。保留未知提交全局阻断。杠杆沿用现有确定性决策链，只扩展信号建议和配置上限。

**Tech Stack:** Java 25、Spring Boot 3.2、JUnit 5、AssertJ、Maven。

## Global Constraints

- 不改变当前持仓杠杆。
- 不削弱 `SUBMITTING`、`UNKNOWN_SUBMIT_STATUS` 和订单 reconciliation 的全局保护。
- 不绕过预算、仓位、相关性、交易模式和 Kill Switch。

---

### Task 1: 合约级入场去重与容量

**Files:**
- Modify: `src/main/java/com/example/quant/agent/execution/TradeOrderRecordService.java`
- Modify: `src/main/java/com/example/quant/agent/execution/AutoTradeService.java`
- Test: `src/test/java/com/example/quant/agent/execution/AutoTradeServiceTest.java`

- [x] 增加失败测试：不同合约活跃入场不阻止新候选。
- [x] 增加失败测试：相同合约仍跳过，活跃合约计入容量。
- [x] 运行目标测试确认按旧账户级锁失败。
- [x] 用当前用户活跃入场合约集合替代全局布尔锁，并合并容量集合。
- [x] 运行 `mvn -Dtest=AutoTradeServiceTest test`。

### Task 2: 1–10 倍杠杆

**Files:**
- Modify: `src/main/java/com/example/quant/crypto/ContractSignalAnalyzer.java`
- Modify: `src/main/java/com/example/quant/leverage/LeverageDecisionService.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/example/quant/crypto/ContractSignalAnalyzerTest.java`
- Test: `src/test/java/com/example/quant/leverage/LeverageDecisionServiceTest.java`

- [x] 增加失败测试：高质量信号可建议大于 5 且不超过 10。
- [x] 增加失败测试：配置允许 10 倍且高风险仍降为 1 倍。
- [x] 运行目标测试确认旧上限失败。
- [x] 扩展既有评分档位和配置上限，保留风险降杠杆。
- [x] 运行两个目标测试类。

### Task 3: 文档与全量验证

**Files:**
- Modify: `docs/CODEBASE_DOC.md`

- [x] 更新中文交易并发、容量和杠杆语义及变更日志。
- [x] 运行 `mvn clean package -DskipTests=false`。
- [x] 运行 `git diff --check` 并复核相关 diff。
- [x] 重建并重启 Demo 容器，确认运行状态；不切换 LIVE。
