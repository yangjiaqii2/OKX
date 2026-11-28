#!/bin/bash
set -eu

# Only the Java process needs database, account and notification credentials.
web_pid=
market_pid=
stopping=false
stop_children() {
  stopping=true
  if [[ -n "$web_pid" ]]; then kill -TERM "$web_pid" 2>/dev/null || true; fi
  if [[ -n "$market_pid" ]]; then kill -TERM "$market_pid" 2>/dev/null || true; fi
}
trap stop_children TERM INT

env -i HOME=/app PATH=/opt/akshare/bin:/usr/bin:/bin LANG=C.UTF-8 TZ="${TZ:-UTC}" \
  OPENBLAS_NUM_THREADS=1 PYTHONDONTWRITEBYTECODE=1 \
  /opt/akshare/bin/python -m uvicorn server:app --app-dir /app/akshare-service \
    --host 127.0.0.1 --port 18080 &
market_pid=$!
java -jar /app/app.jar --spring.web.resources.static-locations=file:/app/static/ &
web_pid=$!

status=0
if ! "$stopping"; then
  wait -n "$web_pid" "$market_pid" || status=$?
fi
if "$stopping"; then
  status=0
elif [[ "$status" == 0 ]]; then
  # Both processes are long-running. An unsolicited clean exit is a failure.
  status=1
fi
stop_children
wait "$web_pid" 2>/dev/null || true
wait "$market_pid" 2>/dev/null || true
exit "$status"
