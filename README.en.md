# Quant Assistant · OKX USDT Perpetual Futures Terminal

[简体中文](README.md)

Quant Assistant is an OKX USDT perpetual futures terminal for finding trade opportunities, managing orders, and reviewing strategy decisions.

## Features

- **Contract scanning and ranking.** Review ranked candidates, scores, trade direction, and execution eligibility.
- **AI entry decisions.** The model evaluates eligible candidates and selects an entry method or waits for better conditions.
- **Order confirmation and automated trading.** Review a trade plan, enter margin, and confirm it manually. Automated orders must also pass risk checks.
- **Account and position management.** Link an OKX account per user, view balances, orders, fills, and positions, and close positions.
- **Trade review.** Review AI entry and wait decisions, rejection reasons, and subsequent market performance.
- **Chinese and English interface.** Switch languages on the sign-in page or in the console header. The browser remembers your choice.

## Deployment and startup

### Command-line startup

Install JDK 17, Maven, Node.js 20, and npm, and have a MySQL database ready. Before the first run, set `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DATABASE`, `MYSQL_USERNAME`, `MYSQL_PASSWORD`, `QUANT_AUTH_USERNAME`, `QUANT_AUTH_PASSWORD`, and `QUANT_CREDENTIAL_MASTER_KEY` in your shell environment. See [.env.example](.env.example) for the settings. Keep using the original credential encryption key with an existing database.

#### Linux / macOS

Run this command from the repository root. It builds the frontend, then uses `start.sh` to package and launch the application. Open `http://127.0.0.1:8080/` by default.

```bash
npm --prefix frontend ci && npm --prefix frontend run build && \
  TRADING_MODE=DISABLED QUANT_AGENT_AUTO_TRADE_ENABLED=false \
  SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:./frontend/dist/ ./start.sh
```

#### Windows

Install Python as well and open PowerShell at the repository root. The startup script uses your existing MySQL database and starts the frontend, backend, and market-data helper. Open `http://127.0.0.1:5173/`.

```powershell
$env:TRADING_MODE = 'DISABLED'
$env:QUANT_AGENT_AUTO_TRADE_ENABLED = 'false'
.\start-dev.cmd -SkipDocker
```

### Local Docker Compose startup

Install Docker Engine and Compose v2. From the repository root, create a local configuration file and generate a master key for credential encryption. The command preserves an existing `.env` file. If your database already contains encrypted credentials, keep using its original master key.

```bash
test -e .env || cp .env.example .env
openssl rand -base64 32
```

Edit `.env` in the repository root and replace the example values. Use `DISABLED` mode for the first run.

| Setting | Local value or purpose |
| --- | --- |
| `OKX_APP_IMAGE` | `okx-quant:local` |
| `TRADING_MODE` | `DISABLED` |
| `QUANT_AGENT_AUTO_TRADE_ENABLED` | `false` |
| `APP_BIND_ADDRESS` | `127.0.0.1` |
| `APP_PORT` | Defaults to `8080` |
| `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD` | Set separate passwords for the app and MySQL root accounts |
| `QUANT_AUTH_USERNAME`, `QUANT_AUTH_PASSWORD` | Set up the initial console administrator |
| `QUANT_CREDENTIAL_MASTER_KEY` | Enter the generated Base64 key and store it securely with the corresponding database backup |

```bash
docker build --target web-runtime -t okx-quant:local .
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --wait
docker compose --env-file .env ps
```

Open `http://127.0.0.1:8080/`, sign in as the initial administrator, and confirm that the system reports `DISABLED` as the trading mode. MySQL uses the named `mysql-data` volume, and Flyway runs migrations at application startup. If you changed `APP_PORT`, use that port in the URL. For LAN access, set `APP_BIND_ADDRESS` to the host's LAN address and check the firewall.

### Server deployment and upgrades with Docker Compose

On the build machine, sign in to your container registry and run `./scripts/build-image.sh --push <image:tag>`. Copy `docker-compose.yml` and `deploy.sh` to the target server. Prepare a server-specific `.env` and set `OKX_APP_IMAGE` to the published image. Then check the configuration and deploy.

```bash
./deploy.sh --check
./deploy.sh
docker compose --env-file .env logs --tail=100 app
```

For upgrades, update the image tag and follow the same steps. Back up the database and retain its volume across deployments. `docker compose down -v` deletes the project's MySQL volume. Before rolling back the image, check whether the older application supports the current database schema.

### Trading modes

| Mode | Purpose |
| --- | --- |
| `DISABLED` | Monitor markets and review decisions with order placement and position closing disabled |
| `DEMO` | Use an OKX demo account to verify the trading workflow |
| `LIVE` | Trade with a real account. Deploying with `deploy.sh` requires `LIVE_DEPLOY_CONFIRM=I_UNDERSTAND_LIVE_TRADING` |

Store real passwords and credentials in the deployment environment or a secret manager.
