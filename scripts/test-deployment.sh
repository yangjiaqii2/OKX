#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

fail() {
  echo "DEPLOYMENT_CHECK_FAILED: $1" >&2
  exit 1
}

for required in Dockerfile docker-compose.yml .env.example deploy.sh scripts/build-image.sh; do
  [[ -f "$required" ]] || fail "missing $required"
done

grep -q '^FROM node:20-alpine AS frontend-build$' Dockerfile || fail "frontend build stage missing"
grep -q '^FROM maven:3\.9\..*-eclipse-temurin-17 AS backend-build$' Dockerfile || fail "backend build stage missing"
grep -q '^FROM eclipse-temurin:17-jre-jammy$' Dockerfile || fail "Java 17 runtime stage missing"
grep -q '^USER app$' Dockerfile || fail "runtime must use non-root app user"
! grep -Eq 'COPY .*\.env' Dockerfile || fail ".env must not enter image layers"
if [[ -f target/quant-assistant-0.0.1-SNAPSHOT.jar ]]; then
  ! jar tf target/quant-assistant-0.0.1-SNAPSHOT.jar | grep -Eq '(^|/)\.env($|\.)' || fail ".env must not enter the application jar"
fi

for variable in OKX_APP_IMAGE MYSQL_PASSWORD QUANT_AUTH_PASSWORD QUANT_CREDENTIAL_MASTER_KEY TRADING_MODE; do
  grep -q "^${variable}=" .env.example || fail ".env.example missing $variable"
done

for variable in NOTIFICATION_ENABLED TELEGRAM_ENABLED TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID; do
  grep -q "^${variable}=" .env.example || fail ".env.example missing $variable"
  grep -q "${variable}:" docker-compose.yml || fail "docker-compose.yml missing $variable"
done

python3 - <<'PY'
from pathlib import Path

text = Path("docker-compose.yml").read_text()
mysql = text.split("  mysql:", 1)[1].split("  app:", 1)[0]
app = text.split("  app:", 1)[1]

assert "ports:" not in mysql, "MySQL must not expose a host port"
assert "healthcheck:" in mysql, "MySQL healthcheck missing"
assert "condition: service_healthy" in app, "app must wait for healthy MySQL"
assert "${OKX_APP_IMAGE" in app, "app image must come from OKX_APP_IMAGE"
assert "TRADING_MODE" in app, "TRADING_MODE must be passed explicitly"
assert "restart: unless-stopped" in mysql and "restart: unless-stopped" in app
PY

bash -n deploy.sh scripts/build-image.sh scripts/test-deployment.sh
grep -q -- '--check' deploy.sh || fail "deploy.sh must support validation without starting containers"
grep -q 'replace-with' deploy.sh || fail "deploy.sh must reject placeholder secrets"
grep -q 'MYSQL_USERNAME cannot be root' deploy.sh || fail "deploy.sh must reject root as the application database user"

echo "DEPLOYMENT_CHECK_OK"
