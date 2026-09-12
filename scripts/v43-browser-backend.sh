#!/usr/bin/env bash
set -euo pipefail

# Fresh local PostgreSQL + real backend HTTP for V43 browser acceptance. No production data.
# Set PG_BIN/JAVA_HOME when PostgreSQL 18 / Java 21 are not on PATH.
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)
fi
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}java
pg_bin=${PG_BIN:-/Library/PostgreSQL/18/bin}
pg_port=${V43_PG_PORT:-55443}
http_port=${V43_BACKEND_PORT:-18043}
run_dir=${V43_CONTROL_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/agentflow-v43-browser.XXXXXX")}
mkdir -p "$run_dir"
if [[ -e "$run_dir/pgdata" ]]; then
  echo "Use a new V43_CONTROL_DIR; fixture databases are never reused." >&2
  exit 1
fi
backend_pid=
cleanup() {
  if [[ -n "$backend_pid" ]]; then kill "$backend_pid" 2>/dev/null || true; fi
  "$pg_bin/pg_ctl" -D "$run_dir/pgdata" -m immediate stop >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM
"$pg_bin/initdb" -D "$run_dir/pgdata" -U v43_fixture --auth=trust --encoding=UTF8 --no-locale >"$run_dir/initdb.log"
"$pg_bin/pg_ctl" -D "$run_dir/pgdata" -l "$run_dir/postgres.log" \
  -o "-h 127.0.0.1 -p $pg_port -k $run_dir" -w start
"$pg_bin/createdb" -h 127.0.0.1 -p "$pg_port" -U v43_fixture agentflow_v43_browser
cd "$repo_dir/backend"
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.includeScope=test \
  "-Dmdep.outputFile=$run_dir/classpath.txt"
"$java_bin" -Dspring.devtools.restart.enabled=false \
  "-Dagentflow.task.recovery.lock-path=$run_dir/task-execution.lock" \
  -Dagentflow.task.recovery.mode=DISABLED \
  "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:$pg_port/agentflow_v43_browser" \
  -Dspring.datasource.username=v43_fixture -Dspring.datasource.password= \
  -Dspring.datasource.hikari.maximum-pool-size=12 \
  -Dagentflow.security.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= \
  -Dagentflow.task.execution.mode=engine -Dagentflow.task.sse.poll-interval-ms=50 \
  -Dagentflow.task.sse.heartbeat-interval-ms=300 -Dagentflow.task.sse.connection-timeout-ms=60000 \
  -Dmybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl \
  -Dlogging.level.com.agentflow=WARN "-Dserver.port=$http_port" -Dserver.address=127.0.0.1 \
  "-Dagentflow.document.storage.root=$run_dir/documents" \
  "-Dv43.control-dir=$run_dir" -cp "target/test-classes:target/classes:$(cat "$run_dir/classpath.txt")" \
  "${V43_FIXTURE_CLASS:-com.agentflow.acceptance.V43BrowserFixture}" >"$run_dir/backend.log" 2>&1 &
backend_pid=$!
echo "V43_CONTROL_DIR=$run_dir"
echo "Backend log: $run_dir/backend.log"
echo "Local fixture login: v43-browser / V43-browser-test!"
echo "Wait for $run_dir/backend-ready before starting browser acceptance. Stop with Ctrl-C."
wait "$backend_pid"
