# Account Binding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a local runtime OKX account binding page and API without writing API credentials to disk.

**Architecture:** Backend stores OKX credentials in an in-memory service and exposes status, bind, and unbind endpoints. Frontend adds a React Admin page that submits credentials, displays only masked key status, and keeps live trading disabled by existing configuration.

**Tech Stack:** Spring Boot 3, JUnit 5, React Admin, MUI, Vite.

---

### Task 1: Backend Binding API

**Files:**
- Create: `src/main/java/com/example/quant/account/OkxAccountBindingService.java`
- Create: `src/main/java/com/example/quant/account/dto/OkxAccountBindRequest.java`
- Create: `src/main/java/com/example/quant/account/dto/OkxAccountBindingStatus.java`
- Modify: `src/main/java/com/example/quant/controller/AccountController.java`
- Test: `src/test/java/com/example/quant/account/OkxAccountBindingServiceTest.java`

- [ ] Write a failing test that binding stores credentials in memory, masks the API key, rejects blank fields, and clears state on unbind.
- [ ] Implement request/status DTOs and service.
- [ ] Add account controller endpoints: `/binding-status`, `/bind`, `/unbind`.
- [ ] Run `mvn "-Dmaven.repo.local=D:\code\View\.m2\repository" test`.

### Task 2: React Admin Binding Page

**Files:**
- Modify: `frontend/src/api/quantApi.ts`
- Modify: `frontend/src/layout.tsx`
- Modify: `frontend/src/App.tsx`
- Create: `frontend/src/pages/AccountBindingPage.tsx`

- [ ] Add API methods for status, bind, and unbind.
- [ ] Add `账号绑定` route and menu item.
- [ ] Implement a form that never echoes secret/passphrase after submit.
- [ ] Display bound/unbound state and masked API key.
- [ ] Run `npm run build`.

### Task 3: Documentation

**Files:**
- Modify: `README.md`

- [ ] Document runtime binding behavior and note that credentials are memory-only.
- [ ] Mention that environment variable binding still works for startup configuration.
