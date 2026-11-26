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
