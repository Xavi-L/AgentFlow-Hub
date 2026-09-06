#!/usr/bin/env bash
set -euo pipefail

# V44 uses a fresh disposable PostgreSQL cluster and real random-port JWT HTTP.
# LLM/embedding/vector gateways remain mocks whose non-use is verified by the tests.
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
pg_bin=${PG_BIN:-/Library/PostgreSQL/18/bin}
pg_port=${V44_PG_PORT:-55444}
run_dir=$(mktemp -d "${TMPDIR:-/tmp}/agentflow-v44-readiness.XXXXXX")
cleanup() {
  "$pg_bin/pg_ctl" -D "$run_dir/pgdata" -m immediate stop >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM
"$pg_bin/initdb" -D "$run_dir/pgdata" -U v44_fixture --auth=trust --encoding=UTF8 --no-locale >"$run_dir/initdb.log"
"$pg_bin/pg_ctl" -D "$run_dir/pgdata" -l "$run_dir/postgres.log" \
  -o "-h 127.0.0.1 -p $pg_port -k $run_dir" -w start
"$pg_bin/createdb" -h 127.0.0.1 -p "$pg_port" -U v44_fixture agentflow_v44_readiness
echo "V44 disposable database logs: $run_dir"
cd "$repo_dir/backend"
mvn -B -Dtest=KnowledgeReadinessPostgresIntegrationTest \
  -Dagentflow.postgres.integration=true \
  "-Dagentflow.postgres.url=jdbc:postgresql://127.0.0.1:$pg_port/agentflow_v44_readiness" \
  -Dagentflow.postgres.user=v44_fixture -Dagentflow.postgres.password= test "$@"
