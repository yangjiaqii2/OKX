#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

docker compose --file docker-compose.yml --env-file .env --env-file .env.evaluation config --format json |
    python3 -c '
import json
import sys

env = json.load(sys.stdin)["services"]["app"].get("environment", {})
mode = str(env.get("TRADING_MODE", "")).strip().upper()
auto_trade = str(env.get("QUANT_AGENT_AUTO_TRADE_ENABLED", "")).strip().lower()
profiles = str(env.get("SPRING_PROFILES_ACTIVE", "")).split(",")
if mode != "DISABLED" or auto_trade != "false" or "evaluation" not in {p.strip() for p in profiles}:
    sys.exit("Evaluation Compose configuration rejected. Require evaluation profile, DISABLED and false.")
print("Evaluation Compose configuration verified.")
'
