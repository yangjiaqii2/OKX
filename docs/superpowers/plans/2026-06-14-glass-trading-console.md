# Glass Trading Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modernize the OKX quantitative trading frontend with a custom dark glass trading-console UI while preserving existing workflows.

**Architecture:** Keep React Admin and MUI. Add focused reusable styling primitives, update the global theme, then refactor the most visible pages and components around those primitives.

**Tech Stack:** React 18, Vite, TypeScript, React Admin 5, MUI 6.

---

### Task 1: Shared Glass Primitives

**Files:**
- Create: `frontend/src/components/glass.ts`
- Create: `frontend/src/components/PageShell.tsx`

- [ ] Add shared glass `sx` helpers for panels, cards, and compact stat surfaces.
- [ ] Add `PageShell` and `PageHeader` wrappers for consistent page spacing and headers.
- [ ] Keep helpers dependency-free and compatible with MUI `sx`.

### Task 2: Theme And Layout

**Files:**
- Modify: `frontend/src/theme.ts`
- Modify: `frontend/src/layout.tsx`

- [ ] Replace flat dark component overrides with translucent glass-like surfaces.
- [ ] Style React Admin drawer, menu, app bar, cards, tables, dialogs, buttons, inputs, and alerts.
- [ ] Add global body background layers through `MuiCssBaseline`.
- [ ] Keep route labels and icons unchanged.

### Task 3: Dashboard And Status Components

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx`
- Modify: `frontend/src/components/TradeMetricCard.tsx`
- Modify: `frontend/src/components/RiskCommandPanel.tsx`
- Modify: `frontend/src/components/TradingStatusStrip.tsx`

- [ ] Introduce a cockpit-style dashboard header with compact status chips.
- [ ] Convert metric/risk/status cards to glass panels.
- [ ] Preserve all existing API calls and conditional alerts.

### Task 4: Contract And Utility Pages

**Files:**
- Modify: `frontend/src/pages/ContractCandidateList.tsx`
- Modify: `frontend/src/pages/LoginPage.tsx`
- Modify: `frontend/src/pages/AccountRiskPage.tsx`
- Modify: `frontend/src/pages/AccountBindingPage.tsx`
- Modify: `frontend/src/pages/SystemControlPage.tsx`

- [ ] Apply `PageShell` and `PageHeader` to non-list custom pages.
- [ ] Upgrade contract candidate cards with better hierarchy and glass material.
- [ ] Modernize login and system action surfaces without changing behavior.

### Task 5: Awesome Skill

**Files:**
- Create skill under the Codex skills directory if permissions allow.

- [ ] Create an `awesome-curator` skill that tells Codex to use the local `D:\code\OKX\awesome` checkout as a catalog.
- [ ] Include concise search instructions and trigger examples.
- [ ] Validate the skill structure manually if the validation script is unavailable.

### Task 6: Verification

**Commands:**
- `npm run build`
- If local npm bin shims are missing, run `node node_modules/typescript/bin/tsc && node node_modules/vite/bin/vite.js build`

- [ ] Confirm TypeScript compiles.
- [ ] Confirm Vite production build completes.
- [ ] Inspect `git diff --stat` and summarize touched files.
