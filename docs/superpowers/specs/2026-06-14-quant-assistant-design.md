# Quant Assistant MVP Design

## Goal

Build a Java 17 Spring Boot 3 backend for a dual-market trading analysis assistant. The MVP provides A-share analysis only and OKX USDT perpetual contract analysis with semi-automatic user-confirmed order execution.

## Scope

The project is a new standalone Maven application under `D:\code\View`. There is no existing business code to preserve. The MVP focuses on safe server-side boundaries, testable risk logic, REST APIs, database schema, and adapter abstractions. External market data, news, AI, and OKX live trading are represented by adapters and safe defaults.

## Safety Rules

A-share flows never create pending orders and never call order execution. OKX orders must pass contract risk checks, create a `PendingOrder`, and require user confirmation before execution. `trading.okx-live-enabled=false` disables live exchange order placement even after confirmation. Secrets are read from environment-backed Spring configuration only.

## Architecture

The application uses focused packages under `com.example.quant`: `stock`, `crypto`, `news`, `score`, `ai`, `leverage`, `position`, `risk`, `tradeplan`, `order`, `okxtrade`, `controller`, and `config`. Services are plain Spring beans with small DTO-style records/classes to keep first-phase behavior testable.

## MVP Deliverables

- Maven Spring Boot application with Java 17 target.
- Flyway SQL migration for the required quant tables.
- REST APIs for A-share analysis, OKX contract analysis/trade plan/pending orders, risk status, and account placeholders.
- Mock adapter implementations for news and market-facing boundaries.
- Unit tests for score, leverage, position sizing, risk, pending order, order confirmation, and sentiment.
- README with startup, configuration, and API examples.
