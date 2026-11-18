# 移动交易终端实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留桌面高密度终端的同时，让手机完成状态监控、机会查看、订单确认/撤销、持仓平仓和 Kill Switch。

**Architecture:** 继续使用 React Admin、MUI 和现有真实 API；重做全局 Industrial Design Token、桌面侧栏/手机底栏双导航和固定安全状态栏；关键表格在 `md` 以下切换为紧凑可展开数据行，不复制或伪造业务数据。

**Tech Stack:** React 18、TypeScript 5.7、MUI 6、React Admin 5、Vite 6、Playwright 浏览器验证。

## Global Constraints

- 不更换框架，不新增 UI 组件库，不制造模拟账户、价格、PnL 或 Connected 状态。
- 390/430px 不允许页面级横向溢出；复杂表格只能在自身容器内滚动。
- `LIVE`、Blocked、Unknown Order、Unmanaged Position 和 AI fallback 必须真实可见。
- 撤单和平仓操作放在展开区，触摸目标不小于 44px；Kill Switch 保留高危确认。
- 当前脏工作区未经用户授权不创建 Git 提交。

---

### Task 1: Industrial Token 与响应式 Shell

**Files:**
- Modify: `frontend/src/theme.ts`
- Modify: `frontend/src/components/glass.ts`
- Modify: `frontend/src/components/PageShell.tsx`
- Test: `frontend/src/theme.ts` 由 TypeScript/Vite 编译验证

**Interfaces:**
- Produces: 全站黑色/暖黑、1px 边框、无渐变/玻璃/阴影、等宽数字、统一间距与移动底部安全区。

- [ ] **Step 1: 记录现有关键 token 并修改主题**

将背景收敛为 `#000000/#0B0C0A/#111311`，正文和显示统一使用 `"IBM Plex Mono", "JetBrains Mono", ui-monospace, monospace`，成功/错误/警告固定为 `#00E676/#FF3B30/#FFB800`。按钮最小高度 44px（桌面 small 可为 36px），Panel 只使用 1px border 和 0..4px 弱圆角。

- [ ] **Step 2: 删除 glass 视觉语义但保留调用接口**

`glassCard/compactGlass/interactiveGlass` 保留导出名称以减少扩散修改，但值改为平面背景和边框，不再包含 backdrop blur、透明渐变或阴影。

- [ ] **Step 3: 增加 safe-area 页面间距**

`PageShell` 在 `xs` 使用紧凑 12px padding，并预留 `calc(72px + env(safe-area-inset-bottom))` 底部空间；`md` 以上取消移动底栏预留。

- [ ] **Step 4: 编译检查**

Run: `cd frontend && npm run build`

Expected: TypeScript 和 Vite 构建通过。

### Task 2: 桌面侧栏、手机底栏和可展开安全状态

**Files:**
- Modify: `frontend/src/layout.tsx`
- Create: `frontend/src/components/MobileBottomNavigation.tsx`
- Modify: `frontend/src/components/RuntimeStatusBar.tsx`
- Modify: `frontend/src/App.tsx`
- Create: `frontend/src/pages/PositionsPage.tsx`

**Interfaces:**
- Produces: 手机 `总览/机会/订单/持仓/更多`；`/positions` 路由；顶部状态栏可展开真实阻断原因。

- [ ] **Step 1: 实现手机底部导航**

使用 MUI `BottomNavigation`、`BottomNavigationAction` 和 React Router `useNavigate/useLocation`。底栏只在 `xs/sm` 显示，固定到底部并应用 `padding-bottom: env(safe-area-inset-bottom)`；“更多”打开 Drawer，列出非核心页面。

- [ ] **Step 2: 新增持仓页并复用现有组件**

`PositionsPage` 调用 `quantApi.positions()`，复用 `PositionSnapshotTable` 和 `ClosePositionDialog`，不复制平仓请求逻辑；页面显示接口错误、Unmanaged ownership 和刷新时间。

- [ ] **Step 3: 收敛移动安全状态栏**

手机首行只固定显示 TradingMode、Trading Engine、Private WS 和异常总数；点击展开 REST、Public WS、Ledger、Unknown、Unmanaged 和 `tradingReason`。socket open 仍不得显示 READY。

- [ ] **Step 4: 编译检查**

Run: `cd frontend && npm run build`

Expected: 新路由、导航和状态栏通过 TypeScript。

### Task 3: Dashboard 与 AI 候选移动层级

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx`
- Modify: `frontend/src/pages/ContractCandidateList.tsx`
- Modify: `frontend/src/api/quantApi.ts`
- Modify: `frontend/src/components/TradeMetricCard.tsx`

**Interfaces:**
- Consumes: `GET /contract/ai-selection`、现有 account/risk/positions/orders/profit/runtime API。
- Produces: 手机首屏安全状态和 AI 主选结果；机会页展示全部候选而不是只显示当前持仓。

- [ ] **Step 1: 修正机会页数据范围**

候选列表使用 `contracts` 排序和过滤；当前持仓独立由 `/positions` 页面呈现。保留 held 状态标识，但不得再通过 `heldContracts` 把未持仓候选全部过滤掉。

- [ ] **Step 2: 按移动优先级重排 Dashboard**

`xs` 顺序为交易模式/阻断、权益与余额、今日/未实现 PnL、持仓、订单异常、AI 选择、WS/Ledger；`lg` 使用网格恢复高密度布局。未知 PnL 保持 `Unavailable/Syncing`，不能回退 0。

- [ ] **Step 3: 展示 AI 来源和排名**

增加 `quantApi.aiContractSelection()`；候选卡显示 AI rank、direction、entryMode、confidence 和 source。尚未产生 AI 扫描时显示“AI 尚未完成本轮选择”，不伪造排名。

- [ ] **Step 4: 编译检查**

Run: `cd frontend && npm run build`

Expected: TypeScript/Vite 通过，无未使用导入。

### Task 4: 订单与持仓的移动展开行

**Files:**
- Modify: `frontend/src/components/PositionSnapshotTable.tsx`
- Modify: `frontend/src/pages/PendingOrderList.tsx`
- Modify: `frontend/src/pages/CurrentOkxOrderList.tsx`
- Modify: `frontend/src/components/PendingOrderReviewDialog.tsx`

**Interfaces:**
- Produces: `md` 以上专业表格；`xs/sm` 紧凑摘要和展开详情，危险操作仅位于展开区。

- [ ] **Step 1: 为持仓增加移动摘要**

摘要固定显示 Symbol、Side、Size、UPnL、Ownership；展开显示 Entry、Mark、Margin、Leverage、Liq.Price、TP/SL、更新时间和平仓按钮。UNMANAGED 使用错误色并禁止策略接管暗示。

- [ ] **Step 2: 为订单增加移动摘要**

摘要显示 Symbol、Side、Status、filled/requested；展开显示 ordId/clOrdId、价格、remaining、avgPx、时间、撤单操作。UNKNOWN/RECONCILING 置顶显示原因。

- [ ] **Step 3: 校验危险 Dialog**

平仓确认必须显示 TradingMode、Symbol、Side、Size 和 reduce-only；LIVE 使用 error Alert。Dialog action 在 390px 下不溢出，确认按钮与取消按钮至少 44px 高。

- [ ] **Step 4: 编译检查**

Run: `cd frontend && npm run build`

### Task 5: 浏览器、依赖和文档验收

**Files:**
- Modify: `README.md`
- Modify: `docs/CODEBASE_DOC.md`

**Interfaces:**
- Produces: 手机/桌面实际验收记录。

- [ ] **Step 1: 启动 DISABLED 环境**

启动后端和前端，确认运行态使用真实 API；没有账号时允许显示 UNBOUND，但不得用 mock 填充页面。

- [ ] **Step 2: 浏览器检查六档宽度**

用 Playwright 检查 390、430、768、1024、1280、1440：Dashboard、机会、待确认订单、当前委托、持仓、系统控制。断言 `document.documentElement.scrollWidth <= innerWidth`，同时记录 Console error、失败请求、Dialog 和 Drawer 遮挡。

- [ ] **Step 3: 完整前端验证**

Run: `cd frontend && npm run build`

Run: `cd frontend && npm audit --audit-level=moderate`

Expected: 构建通过，0 个 moderate 及以上已知漏洞。

- [ ] **Step 4: 同步文档和最终检查**

README 记录移动导航和功能边界；`docs/CODEBASE_DOC.md` 记录主题、路由、AI 状态、修改文件和浏览器结果。

Run: `git diff --check`

Expected: 无空白错误。

