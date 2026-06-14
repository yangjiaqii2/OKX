# Risk-First Trading UI Design

## Goal

Optimize the existing React Admin frontend into a risk-first OKX contract trading cockpit, and add a simple login boundary so saved OKX API credentials are available after restart. The work covers the full trading loop: login, Dashboard, OKX contract opportunity pool, pending order review, account/risk monitoring, and OKX account binding.

The UI must feel like an operational trading terminal, not a marketing dashboard. It should help a contract trader answer four questions quickly:

- Is the OKX account usable right now?
- Is risk acceptable for live trading?
- What positions, opportunities, and pending actions need attention?
- Which actions are safe to run immediately, and which require explicit review?
- Is the current browser session authenticated before showing live-trading controls?

## Current Context

The project already has:

- `frontend/src/pages/Dashboard.tsx`
- `frontend/src/pages/ContractCandidateList.tsx`
- `frontend/src/pages/PendingOrderList.tsx`
- `frontend/src/pages/AccountRiskPage.tsx`
- `frontend/src/pages/AccountBindingPage.tsx`
- shared `MetricCard`, `StatusChip`, formatters, React Admin layout, MUI dark theme, and `quantApi`.

The current UI is functional but still reads as a backend admin app. Dashboard is more explanatory than operational, the contract and order pages are table-first, high-risk live trading context is not visible consistently across the trading loop, and OKX API credentials are held in process memory only.

## Chosen Approach

Use a risk-first information architecture.

Dashboard gets a persistent risk command area as the dominant first-read element. Contract opportunities, pending orders, and account/risk pages inherit the same risk context through a compact top strip or explanatory panel. This makes every live-trading action visibly dependent on account state, risk level, suggested leverage, pending actions, and latest refresh state.

Rejected alternatives:

- Overview-first cockpit: clearer for broad monitoring, but live order safety is less prominent.
- Execution-first pipeline: efficient for manual review volume, but it compresses account and risk context too aggressively for a live contract system.

## Page Design

### Dashboard

Dashboard becomes the main cockpit.

It uses a two-zone layout on desktop:

- Left: `RiskCommandPanel`, a fixed-width risk command area.
- Right: account metrics, positions/PnL snapshot, pending action summary, and top contract opportunities.

The risk command area displays:

- account mode
- risk pass/block status
- risk level
- suggested leverage
- max single-trade loss
- pending order count
- risk rejection reason if present
- OKX error or unbound warning when applicable
- latest risk check time

The right side displays:

- account equity
- available balance
- daily PnL only when provided by the API
- contract opportunity count
- pending order count
- position snapshot from `positions()`
- empty or unavailable states when data is missing

### OKX Contract Opportunity Pool

Keep the React Admin list and table, but add a `TradingStatusStrip` above the table so the user sees account and risk state before creating plans or pending orders.

Rows should emphasize:

- contract symbol
- latest price
- 24h change
- 24h volume
- funding rate
- trend
- top candidate reasons
- actions split by risk level

Actions:

- Generate plan: low-risk, one click.
- Create pending order: medium-risk, visually stronger than scan but weaker than live confirmation. The button should make clear that it creates a pending order, not an executed order.

### Pending Order Review

Pending orders become a live review desk.

The page should include a fixed warning that confirmation submits to the OKX live order endpoint when live trading is enabled. The table remains dense, but row actions should open a structured review dialog instead of a long text string.

`PendingOrderReviewDialog` displays:

- contract
- open/close action
- side and position side
- order type if present
- price
- size
- leverage
- stop loss
- take profit
- risk warning or risk status if present
- explicit OKX live submission notice

Confirming a live order remains a high-risk action and requires a confirmation dialog. Cancelling a pending order also requires confirmation, but the copy should be shorter and less severe than live submission.

### Account And Risk

Account/risk becomes the explanation page for trading availability.

It should answer:

- why live trading is available or unavailable
- what account state OKX returned
- which risk rules passed or blocked
- what positions are currently visible

The existing position list should become a denser `PositionSnapshotTable`. If the positions endpoint fails, the page should show a stable error alert and not imply the account is flat.

### Login And OKX Binding

Add a simple application login before the React Admin shell is shown.

The first implementation pass uses a single local application user managed by the backend. The login boundary is intended to prevent casual access to live-trading controls on the local network; it is not a multi-tenant user-management system.

Login behavior:

- unauthenticated users see only the login page
- authenticated users see the React Admin trading console
- logout clears the browser session token
- API calls include the session token
- backend endpoints reject unauthenticated requests except login and health-style public endpoints

OKX API binding behavior:

- binding saves API Key, Secret, and Passphrase to the database
- status survives backend restart
- frontend copy no longer says credentials are process-memory only
- Secret and Passphrase are never returned to the browser
- API Key is returned only in masked form
- unbind deletes or disables the stored credentials
- existing environment variables remain a fallback only when no database credential exists

Credential storage:

- store credentials in a dedicated database table
- avoid plaintext storage in application objects beyond request handling and OKX signing
- encode or encrypt the stored values through a small service boundary so the storage mechanism can be upgraded later
- default development implementation may use reversible Base64 encoding if no encryption key is configured, but the code should isolate that decision in one component and document that production should use an encryption key

## Shared Components

### RiskCommandPanel

Dashboard-only large panel for risk state and risk limits.

Inputs come from `riskStatus()`, `accountSummary()`, and pending order count.

### TradingStatusStrip

Compact list-page strip for:

- account mode
- risk level
- risk pass/block
- suggested leverage
- pending order count
- last refresh timestamp when available

Used on contract opportunity pool and pending order review pages.

### TradeMetricCard

Enhance or wrap the existing `MetricCard` for trading-focused metric display:

- monospace value
- compact label
- helper text
- clear neutral/warning/error/success accent
- stable height to avoid layout shift

### PendingOrderReviewDialog

Structured replacement for the current long confirmation string.

### PositionSnapshotTable

Compact account/risk page table for positions.

## Interaction Rules

Low-risk actions:

- Refresh
- Scan
- Generate trade plan

These execute with one click and report success/failure through toast notifications.

Medium-risk actions:

- Create pending order

These remain one click for now, but the button label and surrounding context must make it clear the order is not live yet.

High-risk actions:

- Confirm live order
- Cancel pending order
- Emergency stop
- Resume trading

These require a confirmation dialog. The live order dialog must be structured for trade review and must include the OKX live submission warning.

Exceptional states:

- OKX unbound
- OKX API error
- risk blocked
- position fetch failure

These must appear as persistent page alerts or risk panels, not only toast notifications.

## Data And Degradation

This UI pass uses existing APIs only:

- `riskStatus()`
- `accountSummary()`
- `positions()`
- `contractCandidates()`
- `pendingOrders()`

The login and credential persistence pass adds small authentication and binding APIs:

- `POST /api/quant/auth/login`
- `POST /api/quant/auth/logout`
- `GET /api/quant/auth/session`
- database-backed OKX binding through existing `/api/quant/account/bind`, `/binding-status`, and `/unbind`

No backend changes are required for the risk-first UI layout itself, but backend changes are required for login and persistent OKX credential storage.

Missing fields degrade safely:

- daily PnL displays `-` or an unavailable state if the API does not provide it
- margin usage displays `-` or unavailable if absent
- risk/reward ratio displays only when data is available
- positions area displays empty, unavailable, or error states distinctly
- no mock financial numbers are shown in the real UI

Formatting uses existing formatters:

- `formatUSDT`
- `formatPrice`
- `formatNumber`
- `formatPercent`
- `formatStatus`
- `formatSide`
- `formatAction`

Color usage remains restrained:

- green/red only for PnL, directional movement, and pass/fail status
- warning amber for pending review and medium risk
- neutral dark surfaces for ordinary data

## Responsive Behavior

Desktop:

- Dashboard uses the full risk-first two-zone layout.
- Tables remain dense and scannable.
- Action controls stay visible without wrapping into unclear clusters.

Narrow screens:

- Risk command panel stacks above account metrics.
- List pages keep the status strip above the table.
- Critical actions remain reachable.
- Text must not overlap or truncate into unreadable labels.

## Implementation Scope

Expected frontend files:

- `frontend/src/pages/Dashboard.tsx`
- `frontend/src/pages/ContractCandidateList.tsx`
- `frontend/src/pages/PendingOrderList.tsx`
- `frontend/src/pages/AccountRiskPage.tsx`
- `frontend/src/pages/AccountBindingPage.tsx`
- `frontend/src/pages/LoginPage.tsx`
- `frontend/src/api/authProvider.ts`
- `frontend/src/api/quantApi.ts`
- `frontend/src/api/http.ts`
- `frontend/src/components/MetricCard.tsx` or a new `TradeMetricCard.tsx`
- new shared UI components as needed under `frontend/src/components/`
- `frontend/src/formatters.ts` only if a small formatter gap appears

Expected backend files:

- `src/main/java/com/example/quant/auth/*`
- `src/main/java/com/example/quant/account/OkxAccountBindingService.java`
- `src/main/java/com/example/quant/account/*OkxCredential*`
- `src/main/java/com/example/quant/controller/AccountController.java`
- `src/main/java/com/example/quant/controller/AuthController.java`
- `src/main/resources/db/migration/V2__auth_and_okx_credentials.sql`
- focused backend tests under `src/test/java/com/example/quant/auth/` and `src/test/java/com/example/quant/account/`

## Verification

Run:

```bash
mvn test

cd frontend
npm run build
```

Then verify the UI locally:

- unauthenticated browser sees login instead of trading console
- login allows access to Dashboard
- OKX credentials remain bound after service restart or new service instance reading the same repository
- Dashboard desktop layout is risk-first and not blank.
- Contract opportunity pool shows risk context and row actions clearly.
- Pending order confirmation dialog is structured and readable.
- Account/risk page distinguishes no positions from position fetch failure.
- Narrow viewport does not overlap text, buttons, chips, or table summaries.
