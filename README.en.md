# Quant Assistant · OKX USDT Perpetual Futures Terminal

[简体中文](README.md)

Quant Assistant is a multi-user AI personal trading Agent platform and OKX USDT perpetual futures terminal for market scanning, concurrent AI decision-making, BYOK LLM configuration, automated/manual execution, position risk management, and strategy review.

## Features

- **Multi-user personal trading Agent platform ("My Agents").** Create and independently run multiple AI trading Agents from built-in templates (Trend Balanced, Breakout Aggressive, Reversal Sniper, Custom) or custom prompts, each with its own budget, max leverage (up to 100x, auto-stepped down to each contract's OKX max leverage), max open positions, single-trade stop-loss (up to 5%), composite/rank score thresholds, evaluation cooldown, and symbol allow/deny lists.
- **Concurrent Agent execution with per-account symbol lock.** After each shared market scan, all due Agents evaluate concurrently with read-only market tool calls while an atomic per-account symbol lock prevents duplicate orders on the same contract. Supports `PAPER`, `MANUAL_CONFIRM`, and `AUTO` execution modes.
- **Personal LLM configuration (BYOK).** Includes 11 built-in domestic and global presets (DeepSeek, Qwen, Zhipu GLM, Kimi, Doubao, MiniMax, OpenAI, Claude, Gemini, OpenRouter) plus custom OpenAI-compatible endpoints, encrypted per user with AES-GCM.
- **Contract scanning and ranking.** Shared scanner screens OKX USDT perpetual futures and surfaces composite scores, V2 ranking scores, 20m/1h/4h structure, and execution eligibility.
- **Dashboard & account/position risk.** Link a live or demo OKX API key per user, view account equity, real-time risk diagnostics, open positions, one-click close position, and platform-wide emergency Kill Switch.
- **Decision timeline & auto-trade review.** Every Agent run records step-by-step decisions (Filter → LLM → Tool Calls → Risk Gate → Execution), and auto-trade records are attributed per Agent.
- **Email verification & bilingual UI.** Supports email-verified registration/password reset and instant Chinese/English switching.

## Contract selection and scoring

The scanner first screens OKX USDT perpetual futures, then runs deeper analysis on qualifying contracts. It uses 20-minute candles for the main structure, 5-minute candles for entry timing, and longer time frames for context. The scanner groups candidates by trading eligibility, sorts each group by V2 ranking score, and favors better data coverage when those scores tie.

### Composite score (out of 100)

Each factor receives a raw score from 0 to 100. The composite score is the rounded weighted sum. [Weight definitions](src/main/java/com/example/quant/crypto/dto/ContractScoreBreakdown.java)

| Factor | Weight | Main inputs |
| --- | ---: | --- |
| Trend | 25% | Trend direction and candle structure |
| Liquidity | 25% | 24-hour turnover accounts for 40% of the liquidity score, with bid–ask spread and order-book depth at 30% each |
| Volume | 20% | Volume spikes and price–volume alignment |
| Volatility | 10% | Price swings and trend stability |
| Open interest and funding | 10% | Changes in open interest and funding rates |
| Broad market | 5% | Alignment between the candidate's trade direction and the broader market |
| News safety | 5% | Raw safety score derived from news risk |

The **final rank score** starts from the composite score. A raw liquidity score below 50 caps it at 55. A raw volume score below 50, an order book that fails the liquidity checks, or an ineligible signal caps it at 60. The lowest applicable cap wins. [Final rank score calculation](src/main/java/com/example/quant/crypto/OkxContractScanner.java#L1577)

### V2 candidate ranking score

| Factor | Base weight | Main inputs |
| --- | ---: | --- |
| Capital flow | 25% | 24-hour turnover, notional open interest, and depth on the thinner side of the order book |
| Derivatives | 20% | Price and open-interest direction, magnitude of open-interest changes, and funding trends |
| Relative strength | 15% | 1-, 4-, and 24-hour performance relative to BTC/ETH, scored in the candidate's trade direction |
| Market structure | 15% | Direction, candle structure, and market risk |
| Volume quality | 10% | Volume spike ratio, turnover relative to market cap, and bid–ask spread |
| All-time high breakout | 10% | Proximity to the all-time high and data quality. Short candidates skip this factor |
| News safety | 5% | Available news safety score |

The V2 ranking score divides the weighted points from available factors by the total applicable weight for the direction. The applicable weight is 90 for short candidates and 100 for other directions. Missing factors still count toward the applicable weight. For example, if the applicable weight is 100, the available weight is 90, and the available factors average 80 after weighting, the V2 ranking score is 72 with 90% coverage. [V2 weights](src/main/java/com/example/quant/crypto/selection/V2ScoreDimension.java) · [V2 calculation](src/main/java/com/example/quant/crypto/selection/V2SelectionScoreCalculator.java)

### Default screening and eligibility thresholds

| Setting | Default | Purpose |
| --- | --- | --- |
| Market cap and 24-hour price range | Market cap ≥ USD 20 million and range ≤ 40% | Initial screening. Market cap data must be valid |
| Bid–ask spread and depth | Spread ≤ 8 bps (0.08%) and depth on the thinner side ≥ 5,000 USDT | Order-book screening before deep analysis |
| Deep analysis and published candidates | Up to 30 contracts per scan (12 on Miren), 3–4 concurrent analyses, 75s scan timeout, up to 10 published candidates | Controls analysis workload and proxy stability |
| Composite score and final rank score | Global default ≥ 60; each Agent can set its own custom score threshold (e.g. ≥ 50) | Scanner action and per-Agent candidate filtering |
| Trend signal trigger | Aligned 20m EMA + structure, 24h change ±1.5%–10%, volume ratio ≥ 0.65 (≥ 0.50 with 1h/4h alignment) | Identifies directional trend signals |
| Trend, volume, and liquidity subscores | Weighted subscores ≥ 15/25, 15/20, and 10/25 (personal Agents use their own score + safety gate) | Trade-plan scoring checks |
| News safety score | Raw score ≥ 70/100 | Trade-plan check. High news risk is screened separately |
| Planned R:R, stop-loss, and dynamic leverage | Nominal R:R 2.2–2.4 (net R:R after costs ≥ 1.5), stop-loss 0.8%–5%, auto step-down when hitting dynamic leverage cap | Trade-plan and pre-execution risk checks |

Common settings include `QUANT_AGENT_MIN_TOTAL_SCORE`, `QUANT_AGENT_MIN_FINAL_RANK_SCORE`, `QUANT_AGENT_MIN_NEWS_RISK_RAW_SCORE`, `QUANT_AGENT_MIN_DEPTH_USDT`, and the deep-analysis limit, rotation, and concurrency settings. The table shows repository defaults. Order execution also checks accounts, positions, and execution conditions. [Example settings](.env.example) · [Default configuration](src/main/resources/application.yml) · [Trade-plan checks](src/main/java/com/example/quant/agent/gate/ContractTradeGate.java)

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
