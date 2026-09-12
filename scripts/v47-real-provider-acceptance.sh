#!/usr/bin/env bash
set -euo pipefail
umask 077
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
# Optional trusted local shell environment file; never echo/export its contents to evidence.
if [[ -n "${V47_ENV_FILE:-}" ]]; then
  set -a
  source "$V47_ENV_FILE"
  set +a
fi
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)
fi
export PG_BIN=${PG_BIN:-/Library/PostgreSQL/18/bin}
export V47_CONTROL_DIR=${V47_CONTROL_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/agentflow-v47-real.XXXXXX")}
mkdir -p "$V47_CONTROL_DIR"
export V47_CONTROL_DIR=$(cd "$V47_CONTROL_DIR" && pwd)
if [[ -e "$V47_CONTROL_DIR/run-started" || -e "$V47_CONTROL_DIR/pgdata" ]]; then
  echo 'Use a fresh V47_CONTROL_DIR; existing runs/databases are never reused.' >&2
  exit 2
fi
touch "$V47_CONTROL_DIR/run-started"
export V47_BACKEND_PORT=${V47_BACKEND_PORT:-18047}
export V47_FRONTEND_PORT=${V47_FRONTEND_PORT:-5177}
export V47_PG_PORT=${V47_PG_PORT:-55447}
export V47_FRONTEND_URL="http://127.0.0.1:$V47_FRONTEND_PORT"
export V47_BROWSER_ARTIFACTS="$V47_CONTROL_DIR/browser-artifacts"
export V47_DEMO_FILE=${V47_DEMO_FILE:-$repo_dir/scripts/fixtures/v47-payment-diagnosis.md}
export V47_TASK_TIMEOUT_SECONDS=${V47_TASK_TIMEOUT_SECONDS:-180}
export OPENAI_BASE_URL=${OPENAI_BASE_URL:-http://127.0.0.1:1234/v1}
if [[ -z "${OPENAI_CHAT_MODEL:-}" ]]; then
  # Follow the application's configured fallback instead of overriding a changed model.
  OPENAI_CHAT_MODEL=$(python3 - "$repo_dir/backend/src/main/resources/application-dev.yml" <<'PY'
import re, sys
from pathlib import Path
models = re.findall(r'chat-model:\s*\$\{OPENAI_CHAT_MODEL:([^}]+)\}', Path(sys.argv[1]).read_text())
if len(models) != 1 or not models[0].strip():
    raise SystemExit('Cannot read application Chat model default; set OPENAI_CHAT_MODEL explicitly')
print(models[0])
PY
  )
fi
export OPENAI_CHAT_MODEL
export V47_CHAT_MODEL="$OPENAI_CHAT_MODEL"
export DASHSCOPE_BASE_URL=${DASHSCOPE_BASE_URL:-https://dashscope.aliyuncs.com/compatible-mode/v1}
export DASHSCOPE_EMBEDDING_MODEL=${DASHSCOPE_EMBEDDING_MODEL:-text-embedding-v4}
export DASHSCOPE_EMBEDDING_DIMENSIONS=${DASHSCOPE_EMBEDDING_DIMENSIONS:-1024}
export QDRANT_BASE_URL=${QDRANT_BASE_URL:-http://127.0.0.1:6333}
export QDRANT_VECTOR_SIZE=${QDRANT_VECTOR_SIZE:-1024}
export QDRANT_COLLECTION=${V47_QDRANT_COLLECTION:-v47_$(python3 -c 'import uuid; print(uuid.uuid4().hex)')}
export AGENTFLOW_API_TARGET="http://127.0.0.1:$V47_BACKEND_PORT"
export V47_USERNAME="v47_$(python3 -c 'import secrets; print(secrets.token_hex(6))')"
export V47_PASSWORD="V47-$(python3 -c 'import secrets; print(secrets.token_hex(16))')!"
export JWT_SECRET_BASE64=$(python3 -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())')
export V47_BROWSER=1
backend_pid=
vite_pid=
pg_started=false
stage=preflight
cleanup() {
  result=$?
  trap - EXIT INT TERM
  [[ -z "$vite_pid" ]] || kill "$vite_pid" 2>/dev/null || true
  [[ -z "$backend_pid" ]] || kill "$backend_pid" 2>/dev/null || true
  [[ -z "$vite_pid" ]] || wait "$vite_pid" 2>/dev/null || true
  [[ -z "$backend_pid" ]] || wait "$backend_pid" 2>/dev/null || true
  if [[ "$pg_started" == true ]]; then
    "$PG_BIN/pg_ctl" -D "$V47_CONTROL_DIR/pgdata" -m immediate -w stop >/dev/null 2>&1 || true
  fi
  V47_EXIT_CODE=$result V47_STAGE=$stage python3 - <<'PY'
import json, os
from pathlib import Path
e = os.environ
code = int(e['V47_EXIT_CODE'])
status = 'PASSED' if code == 0 and e['V47_STAGE'] == 'complete' else ('PREFLIGHT_READY' if code == 0 else ('BLOCKED' if e['V47_STAGE'] == 'preflight' else 'FAILED'))
Path(e['V47_CONTROL_DIR'], 'run-result.json').write_text(json.dumps(dict(status=status, exitCode=code, stage=e['V47_STAGE'], collection=e['QDRANT_COLLECTION'], automaticRetries=0), indent=2)+'\n')
PY
  echo "V47 evidence retained: $V47_CONTROL_DIR (exit $result, stage $stage)"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python3 "$repo_dir/scripts/v47-preflight.py"
if [[ "${1:-}" == '--preflight-only' ]]; then exit 0; fi
if [[ $# -ne 0 ]]; then echo 'Only --preflight-only is supported.' >&2; exit 2; fi
stage=build
cd "$repo_dir/backend"
# Production sources/runtime dependencies only. No test compilation or fixture classpath.
mvn -q -Dmaven.test.skip=true compile dependency:build-classpath -Dmdep.includeScope=runtime \
  "-Dmdep.outputFile=$V47_CONTROL_DIR/classpath.txt" >"$V47_CONTROL_DIR/build.log" 2>&1
stage=postgres
"$PG_BIN/initdb" -D "$V47_CONTROL_DIR/pgdata" -U v47_acceptance --auth=trust --encoding=UTF8 --no-locale >"$V47_CONTROL_DIR/initdb.log"
# Every client below uses loopback TCP; avoid the platform's Unix socket path
# limit when an evidence directory has a long absolute path.
"$PG_BIN/pg_ctl" -D "$V47_CONTROL_DIR/pgdata" -l "$V47_CONTROL_DIR/postgres.log" \
  -o "-h 127.0.0.1 -p $V47_PG_PORT -c unix_socket_directories=''" -w start >/dev/null
pg_started=true
"$PG_BIN/createdb" -h 127.0.0.1 -p "$V47_PG_PORT" -U v47_acceptance agentflow_v47_real
stage=backend
"$JAVA_HOME/bin/java" -Dspring.devtools.restart.enabled=false \
  "-Dagentflow.task.recovery.lock-path=$V47_CONTROL_DIR/task-execution.lock" \
  -Dagentflow.task.recovery.mode=DISABLED \
  -Dspring.profiles.active=dev \
  "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:$V47_PG_PORT/agentflow_v47_real" \
  -Dspring.datasource.username=v47_acceptance -Dspring.datasource.password= \
  -Dagentflow.knowledge.vectorization.mode=remote -Dagentflow.task.execution.mode=engine \
  -Dmybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl \
  -Dlogging.level.com.agentflow=WARN "-Dserver.port=$V47_BACKEND_PORT" -Dserver.address=127.0.0.1 \
  "-Dagentflow.document.storage.root=$V47_CONTROL_DIR/documents" \
  -cp "target/classes:$(cat "$V47_CONTROL_DIR/classpath.txt")" \
  com.agentflow.AgentFlowApplication >"$V47_CONTROL_DIR/backend.log" 2>&1 &
backend_pid=$!
wait_http() {
  local url=$1 pid=$2 limit=$3
  for ((attempt=0; attempt<limit; attempt++)); do
    if curl --noproxy '*' --connect-timeout 1 --max-time 1 --silent --fail "$url" >/dev/null; then return 0; fi
    if ! kill -0 "$pid" 2>/dev/null; then return 1; fi
    sleep 1
  done
  return 1
}
# Existing liveness endpoint; Redis is not involved in this task execution path.
wait_http "$AGENTFLOW_API_TARGET/api/v1/health" "$backend_pid" 90
stage=frontend
cd "$repo_dir/frontend"
node node_modules/vite/bin/vite.js --host 127.0.0.1 --port "$V47_FRONTEND_PORT" --strictPort >"$V47_CONTROL_DIR/vite.log" 2>&1 &
vite_pid=$!
wait_http "$V47_FRONTEND_URL" "$vite_pid" 30
stage=browser
browser_result=0
node node_modules/@playwright/test/cli.js test --config e2e/real-provider.config.ts || browser_result=$?
stage=storage-evidence
storage_result=0
python3 "$repo_dir/scripts/v47-storage-evidence.py" || storage_result=$?
if [[ "$browser_result" -ne 0 ]]; then exit "$browser_result"; fi
if [[ "$storage_result" -ne 0 ]]; then exit "$storage_result"; fi
stage=complete
