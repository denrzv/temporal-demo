#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
mkdir -p data

demo_port="${PORT:-8080}"
log_file="$repo_dir/data/codespaces-app.log"

# Docker and the app may already be running when the workspace reconnects.
docker compose up -d --wait --wait-timeout 120 temporal
if curl --silent --fail --max-time 3 "http://localhost:$demo_port/api/config" > /dev/null; then
  echo "Service-request UI is already running on port $demo_port."
  exit 0
fi

./mvnw -B -ntp package -DskipTests

export PORT="$demo_port"
export TEMPORAL_ADDRESS="${TEMPORAL_ADDRESS:-localhost:7233}"
export SYSTEMS_URL="${SYSTEMS_URL:-http://localhost:$demo_port/mock/systems}"
if [[ -n "${CODESPACE_NAME:-}" ]]; then
  export TEMPORAL_UI_URL="https://${CODESPACE_NAME}-8233.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
fi

nohup java -jar target/service-requests-0.1.0-SNAPSHOT.jar > "$log_file" 2>&1 < /dev/null &
app_pid=$!
printf '%s\n' "$app_pid" > data/codespaces-app.pid

for ((attempt=0; attempt<90; attempt++)); do
  if curl --silent --fail --max-time 2 "http://localhost:$demo_port/api/config" > /dev/null; then
    echo "Ready. Open the Ports tab and tap the browser icon next to 'Service requests' ($demo_port)."
    if [[ -n "${CODESPACE_NAME:-}" ]]; then
      echo "Application: https://${CODESPACE_NAME}-${demo_port}.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
      echo "Temporal UI: $TEMPORAL_UI_URL"
    fi
    exit 0
  fi
  if ! kill -0 "$app_pid" 2>/dev/null; then
    echo "Application stopped during startup. Recent log:"
    tail -60 "$log_file"
    exit 1
  fi
  sleep 1
done

echo "Application did not become ready. Inspect data/codespaces-app.log."
tail -60 "$log_file"
exit 1
