#!/usr/bin/env bash
set -euo pipefail

# Run from any directory. Requires Java 21, Maven, PostgreSQL binaries, Node 22.12+,
# frontend npm dependencies and Playwright Chromium (or installed Chrome on macOS).
# Model/vector are controlled; every browser API request reaches the real backend.
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
export V43_CONTROL_DIR=${V43_CONTROL_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/agentflow-v43-browser.XXXXXX")}
export V43_BACKEND_PORT=${V43_BACKEND_PORT:-18043}
export V43_PG_PORT=${V43_PG_PORT:-55443}
frontend_port=${V43_FRONTEND_PORT:-5173}
export V43_FRONTEND_URL="http://127.0.0.1:$frontend_port"
export V43_BROWSER_ARTIFACTS="$V43_CONTROL_DIR/browser-artifacts"
export AGENTFLOW_API_TARGET="http://127.0.0.1:$V43_BACKEND_PORT"
mkdir -p "$V43_CONTROL_DIR"
backend_launcher_pid=
vite_pid=
cleanup() {
  if [[ -n "$vite_pid" ]]; then kill "$vite_pid" 2>/dev/null || true; fi
  if [[ -n "$backend_launcher_pid" ]]; then kill "$backend_launcher_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM
bash "$repo_dir/scripts/v43-browser-backend.sh" >"$V43_CONTROL_DIR/launcher.log" 2>&1 &
backend_launcher_pid=$!
for ((attempt=0; attempt<120; attempt++)); do
  [[ -f "$V43_CONTROL_DIR/backend-ready" ]] && break
  if ! kill -0 "$backend_launcher_pid" 2>/dev/null; then
    cat "$V43_CONTROL_DIR/launcher.log" >&2
    [[ ! -f "$V43_CONTROL_DIR/backend.log" ]] || tail -60 "$V43_CONTROL_DIR/backend.log" >&2
    exit 1
  fi
  sleep 1
done
if [[ ! -f "$V43_CONTROL_DIR/backend-ready" ]]; then
  echo "Backend did not become ready; inspect $V43_CONTROL_DIR" >&2
  exit 1
fi
cd "$repo_dir/frontend"
node node_modules/vite/bin/vite.js --host 127.0.0.1 --port "$frontend_port" >"$V43_CONTROL_DIR/vite.log" 2>&1 &
vite_pid=$!
frontend_ready=false
for ((attempt=0; attempt<30; attempt++)); do
  if curl --noproxy '*' --connect-timeout 2 --max-time 3 --silent --fail "$V43_FRONTEND_URL" >/dev/null; then
    frontend_ready=true
    break
  fi
  if ! kill -0 "$vite_pid" 2>/dev/null; then cat "$V43_CONTROL_DIR/vite.log" >&2; exit 1; fi
  sleep 1
done
if [[ "$frontend_ready" != true ]]; then
  echo "Vite did not become ready; inspect $V43_CONTROL_DIR/vite.log" >&2
  exit 1
fi
echo "V43 browser evidence: $V43_CONTROL_DIR"
node node_modules/@playwright/test/cli.js test --config e2e/playwright.config.ts
