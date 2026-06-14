# Quant Assistant MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a runnable Spring Boot backend MVP for the dual-market quant assistant.

**Architecture:** Create a standalone Maven project with Spring Boot web/config/JPA/Flyway dependencies and focused service packages. Keep external providers behind adapters, and make OKX live order execution disabled by default.

**Tech Stack:** Java 17, Spring Boot 3, Maven, JUnit 5, H2 for tests, Flyway SQL, MySQL-compatible schema.

---

### Task 1: Project Skeleton

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/example/quant/QuantAssistantApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/resources/application-test.yml`

- [ ] Write Maven project metadata and Spring Boot dependencies.
- [ ] Add the Spring Boot application entrypoint.
- [ ] Add safe default configuration with OKX live trading disabled.
- [ ] Run `mvn test` and expect compilation to start.

### Task 2: Core Models and Services

**Files:**
- Create packages under `src/main/java/com/example/quant`.
- Add market enums, result wrappers, scoring models, leverage models, position models, risk models, trade plan models, order models, news models.

- [ ] Write failing tests for score, leverage, position, risk, pending order, order confirmation, and news sentiment.
- [ ] Implement minimal services to satisfy the tests.
- [ ] Keep A-share pending order creation rejected in `PendingOrderService`.
- [ ] Keep OKX confirmation flow dependent on `RiskService` and live-trading flag.

### Task 3: API, SQL, and Documentation

**Files:**
- Create controllers under `src/main/java/com/example/quant/controller`.
- Create SQL migration `src/main/resources/db/migration/V1__init_quant_schema.sql`.
- Create `README.md`.

- [ ] Add REST endpoints from the requirements with sample or in-memory MVP behavior.
- [ ] Add all required quant tables in SQL.
- [ ] Document configuration, startup, API examples, and live trading switch.
- [ ] Run full `mvn test` before final response.
