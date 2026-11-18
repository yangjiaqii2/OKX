#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

read_env_value() {
  local key="$1" line value
  [[ -f .env ]] || return 0
  line="$(grep -m1 -E "^[[:space:]]*${key}=" .env || true)"
  value="${line#*=}"
  value="${value%$'\r'}"
  if [[ "$value" == \"*\" ]] || [[ "$value" == \'*\' ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}

push=false
if [[ "${1:-}" == "--push" ]]; then
  push=true
  shift
fi

image="${1:-${OKX_APP_IMAGE:-}}"
[[ -n "$image" ]] || image="$(read_env_value OKX_APP_IMAGE)"
[[ -n "$image" ]] || {
  echo "OKX_APP_IMAGE is required; set it in .env or pass an image name." >&2
  exit 1
}

docker build --pull --tag "$image" .

if [[ "$push" == true ]]; then
  docker push "$image"
fi

echo "IMAGE_READY: $image"
