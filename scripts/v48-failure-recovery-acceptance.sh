#!/usr/bin/env bash
set -euo pipefail
umask 077

# Controlled external faults, real browser/JWT/PostgreSQL/task execution. Never a live-provider run.
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
export V48_CASE=
if [[ $# -gt 0 ]]; then
  if [[ $# -ne 2 || "$1" != --case || ! "$2" =~ ^[A-Z][A-Z0-9_]+$ ]]; then
    echo 'Usage: bash scripts/v48-failure-recovery-acceptance.sh [--case F01_JSON]' >&2
    exit 2
  fi
  export V48_CASE=$2
fi
export V48_CONTROL_DIR=${V48_CONTROL_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/agentflow-v48-browser.XXXXXX")}
mkdir -p "$V48_CONTROL_DIR"
export V48_CONTROL_DIR=$(cd "$V48_CONTROL_DIR" && pwd)
if [[ -e "$V48_CONTROL_DIR/run-started" || -e "$V48_CONTROL_DIR/pgdata" ]]; then
  echo 'Use a fresh V48_CONTROL_DIR; existing runs and databases are never reused or stopped.' >&2
  exit 2
fi
touch "$V48_CONTROL_DIR/run-started"
export V48_BACKEND_PORT=${V48_BACKEND_PORT:-18048}
export V48_FRONTEND_PORT=${V48_FRONTEND_PORT:-5178}
export V48_PG_PORT=${V48_PG_PORT:-55448}
export V48_FRONTEND_URL="http://127.0.0.1:$V48_FRONTEND_PORT"
export V48_BROWSER_ARTIFACTS="$V48_CONTROL_DIR/browser-artifacts"
export AGENTFLOW_API_TARGET="http://127.0.0.1:$V48_BACKEND_PORT"
export PG_BIN=${PG_BIN:-/Library/PostgreSQL/18/bin}
backend_pid=
vite_pid=
pg_started=false
stage=preflight
stop_child() {
  local pid=$1
  [[ -n "$pid" ]] || return 0
  kill "$pid" 2>/dev/null || true
  for ((stop_attempt=0; stop_attempt<50; stop_attempt++)); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.1
  done
  kill -9 "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}
cleanup() {
  local result=$?
  trap - EXIT INT TERM
  stop_child "$vite_pid"
  stop_child "$backend_pid"
  if [[ "$pg_started" == true ]]; then
    "$PG_BIN/pg_ctl" -D "$V48_CONTROL_DIR/pgdata" -m fast -w stop >/dev/null 2>&1 || true
  fi
  V48_EXIT_CODE=$result V48_STAGE=$stage python3 - <<'PY'
import datetime, json, os
from pathlib import Path
e = os.environ
code, stage = int(e['V48_EXIT_CODE']), e['V48_STAGE']
status = 'PASSED' if code == 0 and stage == 'complete' else ('BLOCKED' if stage in ('preflight', 'postgres') else 'FAILED')
Path(e['V48_CONTROL_DIR'], 'run-result.json').write_text(json.dumps({
    'status': status, 'exitCode': code, 'stage': stage,
    'scope': 'single-case' if e['V48_CASE'] else 'full-matrix', 'selectedCase': e['V48_CASE'] or None,
    'automaticExecutionRetries': 0, 'playwrightRetries': 0,
    'boundary': 'controlled LLM, embedding, vector and selected handler faults; real browser/JWT/PostgreSQL/execution',
    'finishedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
}, indent=2) + '\n')
PY
  echo "V48 evidence retained: $V48_CONTROL_DIR (exit $result, stage $stage)"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)
fi
: "${JAVA_HOME:?Java 21 is required}"
for required in "$JAVA_HOME/bin/java" "$PG_BIN/initdb" "$PG_BIN/pg_ctl" "$PG_BIN/createdb" "$PG_BIN/psql"; do
  [[ -x "$required" ]] || { echo "Missing runtime: $required" >&2; exit 2; }
done
for command_name in node python3 mvn curl; do command -v "$command_name" >/dev/null; done
[[ -f "$repo_dir/frontend/node_modules/@playwright/test/cli.js" ]]
node -e 'const [major,minor]=process.versions.node.split(".").map(Number); if(major<22 || (major===22 && minor<12))process.exit(1)'
python3 - <<'PY'
import os, socket
ports = [int(os.environ[key]) for key in ('V48_BACKEND_PORT', 'V48_FRONTEND_PORT', 'V48_PG_PORT')]
if len(set(ports)) != 3 or any(p < 1024 or p > 65535 for p in ports):
    raise SystemExit('V48 ports must be distinct unprivileged TCP ports')
for port in ports:
    with socket.socket() as sock:
        # A completed prior run can leave TCP TIME_WAIT without a listener.
        # Check that a new server can listen, while still rejecting active listeners.
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('127.0.0.1', port))
        sock.listen(1)
PY
git -C "$repo_dir" rev-parse HEAD >"$V48_CONTROL_DIR/code-revision.txt"
stage=build
cd "$repo_dir/frontend"
node node_modules/typescript/bin/tsc --noEmit --target ES2022 --module ESNext --moduleResolution Bundler \
  --strict --skipLibCheck --esModuleInterop --types node \
  e2e/failure-recovery.config.ts e2e/failure-recovery.spec.ts e2e/failure-recovery-evidence.ts \
  >"$V48_CONTROL_DIR/browser-build.log" 2>&1
cd "$repo_dir/backend"
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.includeScope=test \
  "-Dmdep.outputFile=$V48_CONTROL_DIR/classpath.txt" >"$V48_CONTROL_DIR/build.log" 2>&1
stage=postgres
"$PG_BIN/initdb" -D "$V48_CONTROL_DIR/pgdata" -U v48_fixture --auth=trust --encoding=UTF8 --no-locale >"$V48_CONTROL_DIR/initdb.log"
"$PG_BIN/pg_ctl" -D "$V48_CONTROL_DIR/pgdata" -l "$V48_CONTROL_DIR/postgres.log" \
  -o "-h 127.0.0.1 -p $V48_PG_PORT -c unix_socket_directories=''" -w start >/dev/null
pg_started=true
"$PG_BIN/createdb" -h 127.0.0.1 -p "$V48_PG_PORT" -U v48_fixture agentflow_v48_browser
stage=backend
"$JAVA_HOME/bin/java" -Dspring.devtools.restart.enabled=false \
  "-Dagentflow.task.recovery.lock-path=$V48_CONTROL_DIR/task-execution.lock" \
  -Dagentflow.task.recovery.mode=DISABLED \
  "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:$V48_PG_PORT/agentflow_v48_browser" \
  -Dspring.datasource.username=v48_fixture -Dspring.datasource.password= \
  -Dspring.datasource.hikari.maximum-pool-size=12 \
  -Dagentflow.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= \
  -Dagentflow.task.execution.mode=engine -Dagentflow.task.sse.poll-interval-ms=50 \
  -Dagentflow.task.sse.heartbeat-interval-ms=300 -Dagentflow.task.sse.connection-timeout-ms=60000 \
  -Dagentflow.task.execution.decision-max-output-tokens=512 \
  -Dagentflow.task.execution.decision-json-schema-enabled=false \
  -Dagentflow.task.execution.decision-json-object-enabled=false \
  -Dagentflow.task.execution.provider-thinking-disabled=false \
  -Dagentflow.task.execution.final-max-output-tokens= \
  -Dmybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl \
  -Dlogging.level.com.agentflow=WARN "-Dserver.port=$V48_BACKEND_PORT" -Dserver.address=127.0.0.1 \
  "-Dagentflow.document.storage.root=$V48_CONTROL_DIR/documents" "-Dv48.control-dir=$V48_CONTROL_DIR" \
  -cp "target/test-classes:target/classes:$(cat "$V48_CONTROL_DIR/classpath.txt")" \
  com.agentflow.acceptance.V48FailureRecoveryFixture >"$V48_CONTROL_DIR/backend.log" 2>&1 &
backend_pid=$!
for ((attempt=0; attempt<90; attempt++)); do
  [[ -f "$V48_CONTROL_DIR/failure-recovery-ready" ]] && break
  if ! kill -0 "$backend_pid" 2>/dev/null; then tail -50 "$V48_CONTROL_DIR/backend.log" >&2; exit 1; fi
  sleep 1
done
[[ -f "$V48_CONTROL_DIR/failure-recovery-ready" ]] || { echo 'V48 fixture startup timed out' >&2; exit 1; }
stage=frontend
cd "$repo_dir/frontend"
node node_modules/vite/bin/vite.js --host 127.0.0.1 --port "$V48_FRONTEND_PORT" --strictPort >"$V48_CONTROL_DIR/vite.log" 2>&1 &
vite_pid=$!
frontend_ready=false
for ((attempt=0; attempt<30; attempt++)); do
  if curl --noproxy '*' --connect-timeout 1 --max-time 1 --silent --fail "$V48_FRONTEND_URL" >/dev/null; then frontend_ready=true; break; fi
  kill -0 "$vite_pid" 2>/dev/null || { tail -30 "$V48_CONTROL_DIR/vite.log" >&2; exit 1; }
  sleep 1
done
[[ "$frontend_ready" == true ]] || { echo 'V48 frontend startup timed out' >&2; exit 1; }
echo "V48 controlled acceptance: $V48_CONTROL_DIR ${V48_CASE:-full-matrix}"
stage=browser
browser_result=0
node node_modules/@playwright/test/cli.js test --config e2e/failure-recovery.config.ts >"$V48_CONTROL_DIR/browser.log" 2>&1 || browser_result=$?
tail -35 "$V48_CONTROL_DIR/browser.log"
stage=storage-evidence
storage_result=0
storage_args=(--run-dir "$V48_CONTROL_DIR" --pg-bin "$PG_BIN" --pg-port "$V48_PG_PORT")
[[ -z "$V48_CASE" ]] || storage_args+=(--case "$V48_CASE")
python3 "$repo_dir/scripts/v48-storage-evidence.py" "${storage_args[@]}" >"$V48_CONTROL_DIR/storage.log" 2>&1 || storage_result=$?
tail -20 "$V48_CONTROL_DIR/storage.log"
if [[ "$browser_result" -ne 0 ]]; then exit "$browser_result"; fi
if [[ "$storage_result" -ne 0 ]]; then exit "$storage_result"; fi
stage=complete
