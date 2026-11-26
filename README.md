# Quant Assistant · OKX USDT 永续合约终端

[English](README.en.md)

Quant Assistant 是面向 OKX USDT 永续合约的交易终端，提供从筛选交易机会到订单管理和策略复盘的控制台。

## 主要功能

- **合约扫描与排名**。查看候选合约、综合评分、交易方向和执行资格。
- **AI 入场判断**。从符合条件的候选中选择交易机会和入场方式，或在条件不足时观望。
- **订单确认与自动交易**。手动复核交易方案、填写保证金并确认订单。启用自动交易后，订单仍须通过风控检查。
- **账户与仓位管理**。按用户绑定 OKX 账户，查看余额、委托、成交及持仓，并发起平仓。
- **交易复盘**。查看 AI 入场或观望决定、拒绝原因和后续行情表现。
- **中英文界面**。在登录页或控制台顶部切换语言，浏览器会记住选择。

## 选币与评分参数

扫描先筛选 OKX USDT 永续合约，再对入围合约做深度分析。系统使用 20 分钟 K 线判断主要结构，结合 5 分钟 K 线判断入场时机，并参考更长周期。候选按交易资格分组，同组按 V2 排序分排序，分数相同则优先展示数据覆盖率较高的候选。

### 综合评分（满分 100）

每项原始分为 0–100，按下表加权求和并四舍五入。 [权重定义](src/main/java/com/example/quant/crypto/dto/ContractScoreBreakdown.java)

| 评分项 | 权重 | 主要依据 |
| --- | ---: | --- |
| 趋势 | 25% | 趋势方向和 K 线结构 |
| 流动性 | 25% | 24 小时成交额占流动性分的 40%，盘口价差和深度各占 30% |
| 量能 | 20% | 成交量放大及量价配合 |
| 波动性 | 10% | 波动幅度和走势稳定性 |
| 持仓量与资金费率 | 10% | 持仓量变化和资金费率 |
| 大盘环境 | 5% | 大盘对候选交易方向的支持程度 |
| 新闻安全 | 5% | 新闻风险对应的原始安全分 |

**最终排名分**以综合评分为起点。流动性原始分低于 50 时，上限为 55。量能原始分低于 50、盘口不符合交易条件或信号不具备交易资格时，上限为 60。多个条件同时满足时取最低上限。 [排名分计算](src/main/java/com/example/quant/crypto/OkxContractScanner.java#L1577)

### V2 候选排序分

| 评分项 | 基准权重 | 主要依据 |
| --- | ---: | --- |
| 资金流 | 25% | 24 小时成交额、持仓名义价值及盘口较薄侧深度 |
| 衍生品 | 20% | 价格与持仓量的变化方向、持仓量变化幅度及资金费率趋势 |
| 相对强弱 | 15% | 1 小时、4 小时、24 小时相对 BTC/ETH 的表现，按候选方向评分 |
| 市场结构 | 15% | 方向、K 线结构及市场风险 |
| 量能质量 | 10% | 放量倍数、成交额与市值比、盘口价差 |
| 历史高点突破 | 10% | 距离历史高点的水平和数据质量，做空候选跳过此项 |
| 新闻安全 | 5% | 可用的新闻风险安全分 |

V2 排序分为「可用项的加权得分之和 ÷ 当前方向的全部适用权重」。做空候选的适用权重为 90，其余方向为 100。缺失数据仍占适用权重。例如适用权重为 100、可用权重为 90 且可用项的加权平均分为 80 时，V2 排序分为 72，数据覆盖率为 90%。 [V2 权重](src/main/java/com/example/quant/crypto/selection/V2ScoreDimension.java) · [V2 算法](src/main/java/com/example/quant/crypto/selection/V2SelectionScoreCalculator.java)

### 默认筛选与准入门槛

| 参数 | 默认值 | 用途 |
| --- | --- | --- |
| 基础市值、24 小时振幅 | 市值 ≥ 2,000 万 USD，振幅 ≤ 40% | 候选池初筛，市值须有有效数据 |
| 盘口买卖价差和深度 | 价差 ≤ 8 bps（0.08%），较薄侧深度 ≥ 5,000 USDT | 深度分析前的盘口过滤 |
| 深度分析和候选展示 | 每轮最多深度分析 30 个，其中轮换名额最多 20 个，默认 4 路并发，展示最多 10 个 | 控制每轮工作量和覆盖范围 |
| 综合评分、最终排名分 | 各 ≥ 60 分 | 扫描动作判断 |
| 趋势、量能、流动性子分 | 加权后分别 ≥ 15/25、15/20、10/25 | 计划准入评分检查 |
| 新闻安全分 | 原始分 ≥ 70/100 | 计划准入检查，高风险新闻另行拦截 |
| 计划盈亏比和止损距离 | 盈亏比 ≥ 1.5，止损距离 ≤ 5% | 计划准入检查 |

常用可调项包括 `QUANT_AGENT_MIN_TOTAL_SCORE`、`QUANT_AGENT_MIN_FINAL_RANK_SCORE`、`QUANT_AGENT_MIN_NEWS_RISK_RAW_SCORE`、`QUANT_AGENT_MIN_DEPTH_USDT`，以及深度分析的数量、轮换和并发参数。以上是仓库默认值。实际下单还要检查账户、仓位和成交条件。 [配置示例](.env.example) · [默认配置](src/main/resources/application.yml) · [交易计划准入规则](src/main/java/com/example/quant/agent/gate/ContractTradeGate.java)

## 部署与启动

### 命令行启动

安装 JDK 17、Maven、Node.js 20 和 npm，并准备可连接的 MySQL。首次启动前，在当前终端设置 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`、`QUANT_AUTH_USERNAME`、`QUANT_AUTH_PASSWORD` 和 `QUANT_CREDENTIAL_MASTER_KEY`。配置项可参考 [.env.example](.env.example)。已有数据库继续使用原凭据加密主密钥。

#### Linux / macOS

在仓库根目录运行以下命令。它会构建前端，再通过 `start.sh` 打包并启动应用。默认访问 `http://127.0.0.1:8080/`。

```bash
npm --prefix frontend ci && npm --prefix frontend run build && \
  TRADING_MODE=DISABLED QUANT_AGENT_AUTO_TRADE_ENABLED=false \
  SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:./frontend/dist/ ./start.sh
```

#### Windows

另外安装 Python，并在仓库根目录打开 PowerShell。启动脚本使用已准备的 MySQL，同时启动前端、后端和行情辅助服务。访问 `http://127.0.0.1:5173/`。

```powershell
$env:TRADING_MODE = 'DISABLED'
$env:QUANT_AGENT_AUTO_TRADE_ENABLED = 'false'
.\start-dev.cmd -SkipDocker
```

### Docker Compose 本地启动

准备 Docker Engine 和 Compose v2。在仓库根目录创建本地配置并生成凭据加密主密钥。已有 `.env` 会保留，已有加密账户数据时继续使用原主密钥。

```bash
test -e .env || cp .env.example .env
openssl rand -base64 32
```

编辑根目录的 `.env`，按下表替换示例值。首次启动请使用 `DISABLED` 模式。

| 配置 | 本地启动值或用途 |
| --- | --- |
| `OKX_APP_IMAGE` | `okx-quant:local` |
| `TRADING_MODE` | `DISABLED` |
| `QUANT_AGENT_AUTO_TRADE_ENABLED` | `false` |
| `APP_BIND_ADDRESS` | `127.0.0.1` |
| `APP_PORT` | 默认为 `8080` |
| `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD` | 为应用和 MySQL 管理员分别设置密码 |
| `QUANT_AUTH_USERNAME`、`QUANT_AUTH_PASSWORD` | 首次启动时初始化管理员 |
| `QUANT_CREDENTIAL_MASTER_KEY` | 填写生成的 Base64 密钥，并与数据库备份配套保管 |

```bash
docker build --target web-runtime -t okx-quant:local .
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --wait
docker compose --env-file .env ps
```

默认控制台地址为 `http://127.0.0.1:8080/`。用初始化的管理员账号登录，并在系统状态确认交易模式为 `DISABLED`。MySQL 使用 `mysql-data` 命名卷，Flyway 在应用启动时执行迁移。若修改了 `APP_PORT`，请用对应端口访问。若需要局域网访问，将 `APP_BIND_ADDRESS` 设为宿主机的局域网地址，并核对防火墙。

### Docker Compose 服务器部署与升级

在构建机器登录对应镜像仓库，再执行 `./scripts/build-image.sh --push <镜像地址:版本>`。将 `docker-compose.yml` 和 `deploy.sh` 放到目标服务器，准备该环境专用的 `.env`，并将 `OKX_APP_IMAGE` 设为刚发布的镜像地址。然后执行检查和部署。

```bash
./deploy.sh --check
./deploy.sh
docker compose --env-file .env logs --tail=100 app
```

升级时更新镜像标签并执行相同步骤。备份数据库，随部署保留数据库卷。`docker compose down -v` 会删除该项目的 MySQL 数据卷。回滚镜像前请核对旧版本与当前数据库迁移的兼容性。

### 交易模式

| 模式 | 用途 |
| --- | --- |
| `DISABLED` | 查看行情和复盘，禁止下单和平仓 |
| `DEMO` | 使用 OKX 模拟盘账户验证交易流程 |
| `LIVE` | 使用真实账户交易。通过 `deploy.sh` 部署时，须显式设置 `LIVE_DEPLOY_CONFIRM=I_UNDERSTAND_LIVE_TRADING` |

真实密码和凭据保存在部署环境或密钥管理服务中。
