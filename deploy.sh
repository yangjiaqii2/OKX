#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

env_file="${ENV_FILE:-.env}"
check_only=false
case "${1:-}" in
  "") ;;
  --check) check_only=true ;;
  *)
    echo "Usage: ENV_FILE=/path/to/.env ./deploy.sh [--check]" >&2
    exit 1
    ;;
esac

read_env_value() {
  local key="$1" line value
  line="$(grep -m1 -E "^[[:space:]]*${key}=" "$env_file" || true)"
  value="${line#*=}"
  value="${value%$'\r'}"
  if [[ "$value" == \"*\" ]] || [[ "$value" == \'*\' ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}

require_env_value() {
  local key="$1" value
  value="$(read_env_value "$key")"
  [[ -n "$value" ]] || {
    echo "$key is required in $env_file." >&2
    exit 1
  }
  [[ "$value" != *replace-with* && "$value" != *registry.example.com* ]] || {
    echo "$key still contains an example placeholder." >&2
    exit 1
  }
}

command -v docker >/dev/null 2>&1 || {
  echo "Docker is required." >&2
  exit 1
}
docker compose version >/dev/null 2>&1 || {
  echo "Docker Compose v2 is required." >&2
  exit 1
}
[[ -f "$env_file" ]] || {
  echo "$env_file is required; copy .env.example and replace every placeholder." >&2
  exit 1
}

for required in OKX_APP_IMAGE MYSQL_PASSWORD MYSQL_ROOT_PASSWORD QUANT_AUTH_PASSWORD QUANT_CREDENTIAL_MASTER_KEY TRADING_MODE; do
  require_env_value "$required"
done

mysql_username="$(read_env_value MYSQL_USERNAME)"
mysql_username="${mysql_username:-okx}"
[[ "$mysql_username" != "root" ]] || {
  echo "MYSQL_USERNAME cannot be root in Docker deployment; use a dedicated account such as okx." >&2
  exit 1
}

mode="$(read_env_value TRADING_MODE)"
case "$mode" in
  DISABLED|DEMO) ;;
  LIVE)
    [[ "$(read_env_value LIVE_DEPLOY_CONFIRM)" == "I_UNDERSTAND_LIVE_TRADING" ]] || {
      echo "LIVE deployment refused. Set LIVE_DEPLOY_CONFIRM=I_UNDERSTAND_LIVE_TRADING only after controlled-live approval." >&2
      exit 1
    }
    ;;
  *)
    echo "TRADING_MODE must be DISABLED, DEMO, or LIVE." >&2
    exit 1
    ;;
esac

compose=(docker compose --env-file "$env_file")
"${compose[@]}" config --quiet
if [[ "$check_only" == true ]]; then
  echo "DEPLOYMENT_CONFIG_OK: mode=$mode"
  exit 0
fi

"${compose[@]}" pull mysql app
"${compose[@]}" up -d --no-build --remove-orphans

wait_seconds="$(read_env_value DEPLOY_WAIT_SECONDS)"
wait_seconds="${wait_seconds:-240}"
[[ "$wait_seconds" =~ ^[0-9]+$ ]] || {
  echo "DEPLOY_WAIT_SECONDS must be a positive integer." >&2
  exit 1
}

deadline=$((SECONDS + wait_seconds))
while (( SECONDS < deadline )); do
  container_id="$("${compose[@]}" ps -q app)"
  if [[ -n "$container_id" ]]; then
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
    if [[ "$status" == "healthy" ]]; then
      port="$(read_env_value APP_PORT)"
      echo "DEPLOYMENT_READY: http://127.0.0.1:${port:-8080} mode=$mode"
      exit 0
    fi
    if [[ "$status" == "exited" || "$status" == "dead" ]]; then
      break
    fi
  fi
  sleep 3
done

echo "Deployment did not become healthy within ${wait_seconds}s." >&2
"${compose[@]}" ps >&2
"${compose[@]}" logs --tail=120 app >&2
exit 1
