# OKX 量化交易系统审计（2026-08-09）

## 架构结论

- 行情、K 线和订单簿当前来自 OKX REST；账户、订单和仓位以 OKX REST 为恢复与最终确认权威，Private WS 提供实时事件。
- 订单和仓位最终权威源是 OKX；本地数据库只保存交易意图、`clOrdId`、状态机、预算预留、恢复和审计记录。
- 真实链路为：`Market REST → Signal → Pre-confirm → Risk → PendingOrder → OkxTradeAdapter → OKX REST → Recovery/Lifecycle → OKX Order/Position REST → Local Audit/PnL Estimate`。
- Private WS 已实现 orders/positions/account 登录、订阅确认、心跳、stale 检测、退避重连和重连后 REST 对账；Public WS 仍显示 `NOT_IMPLEMENTED`。真实 Demo 已验证认证订阅、订单事件和一次 `1006` 断线后的降级、重连、重新认证订阅及 REST 对账恢复。
- 已建立 `DISABLED/DEMO/LIVE` 单一运行模式；DEMO 私有 REST 请求统一携带 OKX 模拟交易标记。2026-08-12 已完成最小真实 Demo 委托、成交、保护、系统平仓、fills/bills 与通知 E2E；没有自动切换 LIVE。

## Critical

| 状态 | 分类 | 证据与处理 |
|---|---|---|
| 已修复当前文件，需外部轮换 | Security | 全部 15 个历史提交的定向扫描确认曾出现非占位数据库密码和疑似真实的 AI 服务 API Key。当前配置已清除明文并改成环境变量；部署方仍必须轮换对应历史凭据。 |
| 第二轮补全 | Trading Logic | 第一轮只保护 `OkxTradeAdapter`，生命周期服务仍可直接调用 Gateway 提交保护单、撤单和平仓。第二轮已将 Guard 下沉到 `OkxRestOrderGateway` 的全部写接口；查询接口保持可用。 |

## High

| 状态 | 分类 | 证据与处理 |
|---|---|---|
| 第二轮补强 | Security | AES-256-GCM 本身使用随机 IV 且失败关闭，但第一轮仍保留测试可用的 Base64 写入构造器。第二轮已删除旁路，并让绑定服务测试验证实际存储密文；旧 Base64 仅保留只读迁移兼容。 |
| 已修复 | Strategy | K 线解析原来忽略 OKX `confirm`，会把未收盘 K 线送入信号计算。现在只接受 `confirm=1`。 |
| 已修复 | Precision | 合约数量原来按最近值规整，低于 `minSz` 还会向上扩大，可能超过预算。现在只向下规整，低于最小量直接拒绝。 |
| 第二轮补全 | OKX Integration | 第一轮只关闭入场下单的虚构规则兜底，生命周期补挂保护单仍会回退默认规则。第二轮已关闭该旁路；规则不可用时保护失败并保留人工关注状态。 |
| 第二轮修复 | OKX Integration | 规则缓存原来永久有效，且缺字段会被构造器默认值掩盖。现在严格校验 SWAP instrument 及关键字段，并按 symbol 每小时刷新；刷新失败不会使用过期规则执行本次写操作。 |
| 第二轮回归修复 | Backend / Startup | 规则服务新增测试构造器后未标注生产注入构造器，完整 Spring Context 无法启动；现已明确注入入口，上下文与 V1-V16 迁移验证通过。 |
| 已修复 | Risk Control | 放宽模式原来绕过 `RiskService`，并会把策略 2x 杠杆抬到用户配置的更高下限。现在所有订单都走硬风控，只允许动态风控向下降杠杆。 |
| 已修复 | Liquidity | 自动订单原来不按实时盘口深度限制保证金。现在不可交易、价差过大或名义金额超过允许深度占用率时拒绝。 |
| 第二轮修复 | Order State | 第一轮漏查：市价单下单 ACK 被直接标记为 `filled`。现在所有 ACK 先保持 `SUBMITTED`，实际成交由后续 OKX 查询确认。 |
| 第二轮修复 | Idempotency / Recovery | 第一轮漏查：重启遗留 `SUBMITTING` 不会恢复，手动订单也未预先持久化 `clOrdId`；查询暂未找到后还会释放预算。现在稳定客户端订单号在提交前持久化，`SUBMITTING` 进入对账，未知状态保留预算并阻止新自动开仓。 |
| 第二轮修复 | Close / Recovery | 手动平仓原来先请求 OKX、后写本地记录，超时会丢失 `closeClOrdId` 且允许重复提交。现在先保存 `CLOSE_SUBMITTING`，异常转为 `CLOSE_SUBMIT_UNKNOWN`，按 OKX 持仓恢复，并阻止同仓位未决平仓重复发送。 |
| 第二轮修复 | Security / Data Scope | 凭据和预算已按用户隔离，但 `pending_order_state` 原来没有 owner，所有登录用户共享恢复 Map。现在订单持久化 `user_name`，API 与后台恢复均按 owner 隔离；历史行归属 `local-admin`。 |
| 第二轮修复 | Data Consistency / Data Scope | `trade_order` 原来全局共享，当前账号同步可能失效或撤销另一个账号的订单/保护单。现在记录、同步、生命周期与关仓清理均按 owner 过滤；历史行归属 `local-admin`。 |
| 第二轮修复 | Recovery / Observability | 未绑定 owner 时不会调用 OKX 私有查询，也不会把“无法查询”误作空订单而失效本地活动记录；同步返回 `OKX_ACCOUNT_UNBOUND`，恢复保持未就绪。当前定时恢复仍会记录该失败关闭状态，存在重复 WARN 噪声。 |
| 第二轮修复 | Security / Authentication | 用户禁用原来只阻止新登录，既有 token 在 TTL 内仍可访问交易接口。会话共同校验入口现在每次确认用户仍启用，禁用后立即失效。 |
| 第二轮优化 | REST / Frontend | 合约页原有 5 秒聚合轮询和 1 秒 K 线轮询都可能在慢响应时重叠；现在同类请求单飞，K 线降至 5 秒。当前调用规模暂不引入复杂全局限流器。 |
| 第二轮修复 | Risk / UX | `/risk/status` 原先用固定样例参数并写死 `websocketConnected=true`，会制造“风控通过”。现在运行态只报告交易禁用、紧急停止或“待订单预检”；真实放行仍在订单确认链路按实际参数计算。 |
| 第二轮修复 | Concurrency | 自动交易只在扫描开始检查运行开关，停止策略期间仍可能走到提交。现在自动确认入口和最终提交前都会复查开关；已进入 HTTP 发送的请求仍只能依靠稳定 `clOrdId` 对账。 |
| 第二轮修复 | Market Data | 资金费率请求失败原来静默回退为 `0`，可能绕过异常资金费率门槛。现在该 symbol 失败关闭并从本轮候选移除，其他 symbol 可继续扫描。 |
| 第二轮修复 | Precision / Liquidity | 订单簿深度原来单独获取 `ctVal`，失败时回退为“1 张=1 币”，可能严重放大合约深度。现在与下单共用严格的 SWAP instrument rules；元数据未知时流动性不可交易。 |
| 第二轮修复 | PnL / UX | 平仓记录字段未对账时原来把 `null` 当作真实 `0` 展示。现在明确返回 `REALIZED_PNL_UNAVAILABLE`，已实现 PnL 显示 `-`；OKX `upl` 仍单独作为交易所未实现 PnL。 |
| 第二轮修复 | Position Ownership | 恢复任务原来会给本地没有订单归属的 OKX 仓位自动挂一套 2% 止损和分批止盈，可能接管用户在 OKX App 的手工仓位。现在外部仓位只展示、不自动修改；仅本系统持久化订单关联的仓位进入生命周期管理。 |
| 第二轮修复 | Failure Safety | 新闻源无数据或请求失败原来仍返回 `allowTrade=true`，放宽模式也会自动下单。现在未知新闻状态降级为 `WAIT`，两种模式均不提交，待后续扫描数据恢复。 |
| Phase 4 Demo 已验证 | WebSocket / Data Consistency | Private WS 已在真实 Demo 完成认证、三频道订阅和订单生命周期验证；真实 `1006` 断线时引擎降级并阻断写请求，随后重连、重新认证订阅和 REST 对账恢复 READY。Public WS 未实现。 |
| 第三轮已修复 | Idempotency / Strategy | 使用 `owner + strategy + instId + timeframe + candle timestamp` 数据库唯一键领取决策；无信号也终结该 K 线，崩溃遗留 PROCESSING 转 RECONCILING 且不自动重放。 |
| Phase 4 已补全 | Recovery / Order | 普通订单从 pending 消失后查询详情、历史和 fills；部分成交、部分成交后撤单和 RECONCILING 可表达。Algo 订单改用专用 details API 映射终态；未知或部分失败仍保持 RECONCILING。 |
| 第三轮已修复 | Recovery / Fail-safe | 对账后仍有当前 owner 的 `RECONCILING` 订单时，同步明确返回 `ORDER_RECONCILIATION_REQUIRED`，启动恢复保持 BLOCKED；同一订单在单轮状态遍历中只查询一次详情。 |
| 第三轮已修复 | REST / Concurrency | 禁用合约模块不再执行启动、定时或首次页面扫描；并发首次候选请求在单 JVM 内共享一次扫描，消除浏览器刷新触发的重复全市场请求。 |
| 未解决 | Concurrency / Deployment | 扫描、自动执行和 symbol 锁均为进程内状态；多实例或两个调度器进程会各自执行，数据库没有分布式租约或版本锁。当前部署边界必须是单交易实例。 |

## Medium

| 状态 | 分类 | 证据与处理 |
|---|---|---|
| 已修复 | Frontend Security | 生产依赖审计曾有 React Router、DOMPurify、PostCSS、nanoid 漏洞；兼容升级后完整 `npm audit` 为 0。 |
| 已修复 | UX | 全局顶栏显示交易环境、REST、两个 WS、策略引擎和交易引擎状态；LIVE 使用真实资金危险标识，禁用状态不会冒充 Demo。 |
| 已修复 | UI | Dashboard 第一层改为权益、可用余额、今日/累计/未实现 PnL 和持仓；1440/1280 使用高密度布局，1024 保持三列且无横向溢出。 |
| 第二轮修复 | Runtime UX | 未绑定账号进入当前委托页原来会调用私有接口并产生 502；现在先读真实绑定状态并明确显示不可查询。三档 Dashboard 与五个主要页面的 Console/Network/横向溢出回归通过。 |
| Phase 4 部分完成 | PnL | 真实 fills 与近期 bills/funding 已持久化并分开展示；未知值不再显示 0。当前只有最近同步窗口，且同 instrument 的外部资金费无法精确分摊，因此不是完整账户净值账本。 |
| 未实现 | Backtest | 仓库没有独立回测引擎，无法审计手续费、滑点、资金费、未来函数和成交模型；现有策略测试不是回测结果。 |
| 部分缓解 | Performance | 首次候选扫描已单飞，页面轮询已防重叠；REST 客户端仍没有跨端点统一限流器，扩大标的、用户或轮询频率前必须增加按端点/用户维度的限流与退避。 |
| 已知边界 | Architecture / Multi-user | 自动交易运行开关是系统级单 owner 状态；任一认证用户开启时会替换 owner，不支持多个用户并行运行独立自动策略。改为多租户调度需要明确业务模型，未在本轮猜测重构。 |

## Low

- 业务页面已按路由延迟加载，入口 JS 由约 922.80 KB 降至 834.22 KB；React Admin/MUI 共享运行时仍触发 Vite 500 KB 提示，继续手工拆 vendor 只会改变文件数量而非总下载量。
- `NO_RISK` 是数据库兼容枚举名，当前 UI 和执行语义均为只读 Shadow：不创建 PendingOrder、不调用订单确认，最终确认层也禁止交易所写请求；未来做数据库迁移时可再改内部名称。
- `.akshare-venv-linux` 已加入 ignore，但历史提交中仍跟踪大量虚拟环境文件；本轮不做大规模索引删除。

## 第二轮最终验证

- `mvn clean package -DskipTests=false`：237 个测试，0 failure、0 error、0 skipped，JAR 打包成功。
- `npm run build`：TypeScript 与 Vite 生产构建成功；仍有一个 834.22 KB 共享入口包警告。
- `npm audit --audit-level=moderate`：0 个已知漏洞；后端没有配置依赖漏洞扫描插件，因此 Maven 打包成功不等价于后端 CVE 扫描。
- 浏览器：交易禁用、OKX 私有账号未绑定的真实运行态下，1440/1280/1024 首页和 1024 的合约、待确认、当前委托、风控、系统控制页面通过；Console、失败请求和 HTTP 错误列表为空。
- 敏感信息：当前受管配置只保留环境变量/占位值；Git 历史仍有需轮换的历史凭据，代码修复不能撤销已发生的泄露。
- Git：`git diff --check` 通过；当前变更仍是未提交工作区，包含本轮与第一轮修改。

## Phase 3 最终验证

- `mvn clean package -DskipTests=false`：249 个测试，0 failure、0 error、0 skipped；V1-V19 迁移和可执行 JAR 成功。
- `npm run build`：TypeScript 与 Vite 构建成功；入口包 834.43 KB。`npm audit --audit-level=moderate`：0 个已知漏洞。
- DISABLED 实际启动：REST `UNBOUND`、Public/Private WS `NOT_IMPLEMENTED`、Trading Engine `DISABLED`；禁用合约模块未发起启动扫描。
- 浏览器：1440/1280/1024 状态条与 1024 五个主要页面无横向溢出；Console、失败请求、HTTP 错误为空。真实 Demo 凭据不存在，Demo E2E 未执行。

## Phase 4 最终验证

- Demo 前置：使用部署环境提供的 OKX Demo 三件套，仅以 `TRADING_MODE=DEMO` 发送私有请求；没有切换或修改 LIVE 配置。
- 真实 E2E：专用 Demo intent 经正常风险、数量规整和最终写网关提交 ETH-USDT-SWAP 0.02 张；真实 ACK、成交、仓位、保护单、系统平仓、仓位归零、fills、bills 和 Telegram HTTP 200 通知均落库可追踪。
- 自动策略：STRICT、50 USDT 总预算和自动交易开关保持启用；真实 AI 扫描持续运行，但当前决策为 `AI_WAIT`。未降低策略或硬风控门槛来制造自动成交，故不能声称自然策略订单已成交。
- 平仓对账：恢复服务按 `closeClOrdId` 查询真实订单和 fills，只有成交事实可用后才确认关闭，并回补 `ordId/avgPx/fillPnl/fee`；本次真实 Demo 历史记录已成功回补。
- 后端：`mvn clean package -DskipTests=false` 通过，322 个测试均通过，Flyway V1-V26 验证和可执行 JAR 成功。
- 前端：`npm run build` 通过 TypeScript 与 Vite；入口包 856.00 KB，保留体积提示。`npm audit --audit-level=high` 为 0 个已知漏洞。
- 故障注入：交易写请求收到 429/500、连接重置或畸形 JSON 时单次失败、不会自动重发；HTTP 提交超时、ACK 丢失、WS 认证失败/断线/重复乱序事件、账本落库失败和缺失 instrument rules 均有失败关闭测试。
- 运行态：最终 JAR 以 `DEMO` 启动，状态真实返回 REST `CONNECTED`、Private WS `READY / AUTHENTICATED_SUBSCRIBED_RECONCILED`、Trading Engine `AUTO_ENABLED`、unknown orders 0、unmanaged positions 0、ledger `RECENT_WINDOW_SYNCED`；当前 OKX 持仓、普通订单和 Algo 订单均为 0。
- 浏览器：通过侧栏实际进入 Dashboard、账号绑定和待确认订单；1440/1280/1024 无页面横向溢出，Console、Page Error、失败请求和 HTTP 4xx/5xx 均为空。
- 安全：`.env/.env.*` 已忽略（保留 `.env.example`）；当前树启发式扫描未发现新增明文凭据。环境没有 gitleaks，Git 历史中既有疑似凭据仍必须外部轮换。
- 判断：真实 Demo 基础设施生命周期已跑通，系统保持 `READY FOR DEMO`。Public WS、多实例交易租约、长期账本窗口、外部仓位真实人工操作场景和自然自动策略成交尚未全部验证，不满足 Controlled Live Test。

## 已验证的不变式

- 交易 POST 超时不会盲目重试；系统使用提交前持久化的稳定 `clOrdId` 查询第一次请求结果，无法确认时保持 unknown、锁定预算并阻止新的自动开仓。
- 待确认订单用原子状态迁移阻止重复确认；自动交易预算与恢复记录继续使用稳定客户端订单号。
- OKX REST 签名保持 `timestamp + uppercase method + requestPath + body`、HMAC-SHA256、Base64，GET 查询参数属于 request path。
- 平仓使用 OKX close-position 接口；净持仓与双向持仓的 `posSide` 分支仍由账户模式提供器决定。
