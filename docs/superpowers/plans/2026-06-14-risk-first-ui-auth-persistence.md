# Risk-First UI Auth Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a simple login boundary, persist OKX API credentials in the database, and optimize the trading UI into the approved risk-first cockpit.

**Architecture:** Use lightweight application-level auth with bearer session tokens and a Spring MVC interceptor, avoiding a large security dependency for this local trading console. Store OKX credentials through a repository-backed service and a small codec boundary, then update React Admin with an auth provider, login page, shared risk context components, and structured live-order review.

**Tech Stack:** Java 17, Spring Boot 3 MVC/JPA/Flyway, JUnit 5, React 18, React Admin 5, MUI 6, TypeScript, Vite.

---

### Task 1: Backend Auth And Credential Tests

**Files:**
- Create: `src/test/java/com/example/quant/auth/AuthSessionServiceTest.java`
- Modify: `src/test/java/com/example/quant/account/OkxAccountBindingServiceTest.java`

- [ ] Add tests proving valid login creates a session token, invalid login is rejected, and expired tokens are not accepted.
- [ ] Add tests proving OKX binding saves credentials through a store, status returns a masked key after a new service instance, credentials decode correctly, and unbind clears the store.
- [ ] Run `mvn -Dtest=AuthSessionServiceTest,OkxAccountBindingServiceTest test` and verify the tests fail because the auth classes and persistent store do not exist yet.

### Task 2: Backend Auth Implementation

**Files:**
- Create: `src/main/java/com/example/quant/config/AuthProperties.java`
- Modify: `src/main/java/com/example/quant/QuantAssistantApplication.java`
- Create: `src/main/java/com/example/quant/auth/AuthLoginRequest.java`
- Create: `src/main/java/com/example/quant/auth/AuthSessionResponse.java`
- Create: `src/main/java/com/example/quant/auth/AuthSessionService.java`
- Create: `src/main/java/com/example/quant/auth/AuthInterceptor.java`
- Create: `src/main/java/com/example/quant/controller/AuthController.java`
- Modify: `src/main/java/com/example/quant/config/WebConfig.java`
- Modify: `src/main/resources/application.yml`

- [ ] Implement configurable single-user auth properties: username, password, session TTL.
- [ ] Implement session creation, validation, logout, and expiry cleanup.
- [ ] Add `/api/quant/auth/login`, `/logout`, and `/session`.
- [ ] Register a MVC interceptor that allows auth endpoints and OPTIONS, then requires `Authorization: Bearer <token>` for other `/api/quant/**` endpoints.
- [ ] Run `mvn -Dtest=AuthSessionServiceTest test` and verify it passes.

### Task 3: Persistent OKX Credential Store

**Files:**
- Create: `src/main/java/com/example/quant/account/OkxCredentialCodec.java`
- Create: `src/main/java/com/example/quant/account/Base64OkxCredentialCodec.java`
- Create: `src/main/java/com/example/quant/account/StoredOkxCredential.java`
- Create: `src/main/java/com/example/quant/account/OkxCredentialStore.java`
- Create: `src/main/java/com/example/quant/account/JpaOkxCredentialEntity.java`
- Create: `src/main/java/com/example/quant/account/JpaOkxCredentialRepository.java`
- Create: `src/main/java/com/example/quant/account/JpaOkxCredentialStore.java`
- Modify: `src/main/java/com/example/quant/account/OkxAccountBindingService.java`
- Create: `src/main/resources/db/migration/V2__auth_and_okx_credentials.sql`
- Modify: `src/main/resources/application.yml`
- Modify: `README.md`

- [ ] Implement the codec and store boundary.
- [ ] Add a JPA-backed credential table and repository.
- [ ] Change `OkxAccountBindingService` to save, load, and unbind through the store, keeping OKX env vars as fallback in `OkxRestClient`.
- [ ] Update docs to say OKX credentials persist in DB and are never returned except masked API key.
- [ ] Run `mvn -Dtest=OkxAccountBindingServiceTest test` and verify it passes.

### Task 4: Frontend Auth Boundary

**Files:**
- Create: `frontend/src/api/auth.ts`
- Create: `frontend/src/api/authProvider.ts`
- Modify: `frontend/src/api/http.ts`
- Create: `frontend/src/pages/LoginPage.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/pages/AccountBindingPage.tsx`

- [ ] Add local token storage and bearer-token fetch headers.
- [ ] Add a React Admin auth provider.
- [ ] Add a login page and enable `requireAuth`.
- [ ] Update account binding copy to explain database persistence.
- [ ] Run `cd frontend && npm run build` after frontend implementation.

### Task 5: Risk-First Trading UI

**Files:**
- Create: `frontend/src/components/TradeMetricCard.tsx`
- Create: `frontend/src/components/RiskCommandPanel.tsx`
- Create: `frontend/src/components/TradingStatusStrip.tsx`
- Create: `frontend/src/components/PendingOrderReviewDialog.tsx`
- Create: `frontend/src/components/PositionSnapshotTable.tsx`
- Modify: `frontend/src/pages/Dashboard.tsx`
- Modify: `frontend/src/pages/ContractCandidateList.tsx`
- Modify: `frontend/src/pages/PendingOrderList.tsx`
- Modify: `frontend/src/pages/AccountRiskPage.tsx`
- Modify: `frontend/src/formatters.ts` only if required.

- [ ] Replace the dashboard with the risk command layout.
- [ ] Add a compact trading status strip to OKX opportunity and pending order pages.
- [ ] Replace long pending-order confirm strings with a structured review dialog.
- [ ] Convert account positions into a dense snapshot table with distinct empty/error states.
- [ ] Run `cd frontend && npm run build` and fix TypeScript or layout-related build failures.

### Task 6: Final Verification

**Files:**
- Verify all touched files.

- [ ] Run `mvn test`.
- [ ] Run `cd frontend && npm run build`.
- [ ] Start the backend and frontend only if verification needs browser inspection.
- [ ] Confirm the final response reports completed work, tests run, and any remaining risk.

