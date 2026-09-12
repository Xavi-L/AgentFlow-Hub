#!/usr/bin/env bash
set -euo pipefail
umask 077

# Real disposable PostgreSQL; controlled Java fixtures do not constitute provider evidence.
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
all_classes=(V03AConfigVersionPostgresIntegrationTest AgentTaskPostgresIntegrationTest \
  AgentTaskExecutionPostgresIntegrationTest AgentTaskApiPostgresIntegrationTest \
  AgentTaskSsePostgresIntegrationTest AgentExecutionTracePostgresIntegrationTest \
  TaskRecoveryPostgresIntegrationTest TaskSettlementPostgresIntegrationTest \
  KnowledgeReadinessPostgresIntegrationTest)
classes=()
while [[ $# -gt 0 ]]; do
  if [[ $# -lt 2 || "$1" != --class || -z "$2" ]]; then
    echo 'Usage: bash scripts/v03a-postgres-acceptance.sh [--class AllowedTestClass] (repeat --class to select more than one)' >&2
    exit 2
  fi
  case " ${all_classes[*]} " in
    *" $2 "*) ;;
    *) echo 'Unknown PostgreSQL acceptance class' >&2; exit 2 ;;
  esac
  case " ${classes[*]-} " in
    *" $2 "*) echo 'Duplicate PostgreSQL acceptance class' >&2; exit 2 ;;
  esac
  classes+=("$2")
  shift 2
done
export V03A_PG_SCOPE=selected
if [[ ${#classes[@]} -eq 0 ]]; then
  classes=("${all_classes[@]}")
  V03A_PG_SCOPE=full
fi
export V03A_EXPECTED_CLASS_COUNT=${#classes[@]}
if [[ -n "${V03A_PG_RUN_DIR:-}" ]]; then
  mkdir "$V03A_PG_RUN_DIR" || { echo 'V03A_PG_RUN_DIR must be a new directory' >&2; exit 2; }
else
  V03A_PG_RUN_DIR=$(mktemp -d "${TMPDIR:-/tmp}/agentflow-v03a-postgres.XXXXXX")
fi
export V03A_PG_RUN_DIR=$(cd "$V03A_PG_RUN_DIR" && pwd)
export V03A_PG_PORT=${V03A_PG_PORT:-55464}
export PG_BIN=${PG_BIN:-/Library/PostgreSQL/18/bin}
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
pg_started=false
stage=preflight
cleanup() {
  local result=$?
  trap - EXIT INT TERM
  if [[ "$pg_started" == true ]]; then
    "$PG_BIN/pg_ctl" -D "$V03A_PG_RUN_DIR/pgdata" -m fast -w stop >"$V03A_PG_RUN_DIR/postgres-stop.log" 2>&1 || true
  fi
  V03A_EXIT_CODE=$result V03A_STAGE=$stage python3 - <<'PY'
import datetime, json, os
from pathlib import Path
run = Path(os.environ['V03A_PG_RUN_DIR'])
matrix = json.loads((run / 'matrix.json').read_text()) if (run / 'matrix.json').exists() else []
code, stage = int(os.environ['V03A_EXIT_CODE']), os.environ['V03A_STAGE']
expected = int(os.environ['V03A_EXPECTED_CLASS_COUNT'])
passed = code == 0 and stage == 'complete' and len(matrix) == expected and all(case['status'] == 'PASSED' for case in matrix)
(run / 'run-result.json').write_text(json.dumps({
    'status': 'PASSED' if passed else ('BLOCKED' if stage in ('preflight', 'postgres') else 'FAILED'),
    'stage': stage, 'exitCode': code, 'scope': os.environ['V03A_PG_SCOPE'], 'expectedClassCount': expected, 'matrix': matrix,
    'tests': sum(case.get('tests', 0) for case in matrix),
    'failures': sum(case.get('failures', 0) for case in matrix),
    'errors': sum(case.get('errors', 0) for case in matrix),
    'skipped': sum(case.get('skipped', 0) for case in matrix),
    'boundary': 'Real temporary PostgreSQL, isolated database and JVM execution lock per class; controlled fixtures, no real provider evidence',
    'finishedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
}, indent=2) + '\n')
PY
  echo "V03A PostgreSQL evidence retained: $V03A_PG_RUN_DIR (exit $result, stage $stage)"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for required in "$JAVA_HOME/bin/java" "$PG_BIN/initdb" "$PG_BIN/pg_ctl" "$PG_BIN/createdb"; do
  [[ -x "$required" ]] || { echo "Missing runtime: $required" >&2; exit 2; }
done
command -v mvn >/dev/null
command -v python3 >/dev/null
"$JAVA_HOME/bin/java" -version >"$V03A_PG_RUN_DIR/java-version.txt" 2>&1
"$PG_BIN/initdb" --version >"$V03A_PG_RUN_DIR/postgres-version.txt"
python3 - <<'PY'
import os, re, socket
from pathlib import Path
run = Path(os.environ['V03A_PG_RUN_DIR'])
if not re.search(r'version "21\.', (run / 'java-version.txt').read_text()):
    raise SystemExit('Java 21 is required')
if 'PostgreSQL) 18.' not in (run / 'postgres-version.txt').read_text():
    raise SystemExit('PostgreSQL 18 is required')
port = int(os.environ['V03A_PG_PORT'])
if not 1024 <= port <= 65535:
    raise SystemExit('Use an unprivileged PostgreSQL port')
with socket.socket() as sock:
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(('127.0.0.1', port))
    sock.listen(1)
PY
git -C "$repo_dir" rev-parse HEAD >"$V03A_PG_RUN_DIR/code-revision.txt"
git -C "$repo_dir" status --porcelain >"$V03A_PG_RUN_DIR/worktree-status.txt"
stage=build
mvn -q -f "$repo_dir/backend/pom.xml" -DskipTests test-compile dependency:build-classpath \
  -Dmdep.includeScope=test "-Dmdep.outputFile=$V03A_PG_RUN_DIR/classpath.txt" \
  >"$V03A_PG_RUN_DIR/build.log" 2>&1
mockito_agent=$(python3 - "$V03A_PG_RUN_DIR/classpath.txt" <<'PY'
import re, sys
from pathlib import Path
matches = [item for item in Path(sys.argv[1]).read_text().strip().split(':') if re.search(r'/mockito-core-[\d.]+\.jar$', item)]
if len(matches) != 1:
    raise SystemExit('Expected exactly one Mockito core javaagent in current build classpath')
print(matches[0])
PY
)
stage=postgres
"$PG_BIN/initdb" -D "$V03A_PG_RUN_DIR/pgdata" -U v03a_fixture --auth=trust --encoding=UTF8 --no-locale \
  >"$V03A_PG_RUN_DIR/initdb.log" 2>&1
"$PG_BIN/pg_ctl" -D "$V03A_PG_RUN_DIR/pgdata" -l "$V03A_PG_RUN_DIR/postgres.log" \
  -o "-h 127.0.0.1 -p $V03A_PG_PORT -c unix_socket_directories=''" -w start >/dev/null
pg_started=true
stage=tests
all_result=0
index=0
for test_class in "${classes[@]}"; do
  index=$((index + 1))
  database="agentflow_v03a_$index"
  class_dir="$V03A_PG_RUN_DIR/$test_class"
  mkdir -p "$class_dir/surefire"
  "$PG_BIN/createdb" -h 127.0.0.1 -p "$V03A_PG_PORT" -U v03a_fixture "$database"
  echo "V03A PostgreSQL: $test_class ($database)"
  # Surefire's reportsDirectory has no portable command-line property in this build.
  # Remove only this class's generated reports so a failed launch cannot reuse old evidence.
  V03A_TEST_CLASS=$test_class V03A_REPO_DIR=$repo_dir python3 - <<'PY'
import os
from pathlib import Path
reports = Path(os.environ['V03A_REPO_DIR']) / 'backend/target/surefire-reports'
name = os.environ['V03A_TEST_CLASS']
for pattern in (f'TEST-*.{name}.xml', f'*.{name}.txt'):
    for path in reports.glob(pattern):
        path.unlink()
PY
  class_result=0
  mvn -q -f "$repo_dir/backend/pom.xml" "-Dtest=$test_class" -DfailIfNoTests=true \
    "-DargLine=-javaagent:$mockito_agent" \
    -Dagentflow.postgres.integration=true \
    "-Dagentflow.postgres.url=jdbc:postgresql://127.0.0.1:$V03A_PG_PORT/$database" \
    -Dagentflow.postgres.user=v03a_fixture -Dagentflow.postgres.password= \
    "-Dagentflow.task.recovery.lock-path=$class_dir/task-execution.lock" surefire:test \
    >"$class_dir/test.log" 2>&1 || class_result=$?
  V03A_TEST_CLASS=$test_class V03A_REPO_DIR=$repo_dir V03A_CLASS_DIR=$class_dir python3 - <<'PY'
import os, shutil
from pathlib import Path
reports = Path(os.environ['V03A_REPO_DIR']) / 'backend/target/surefire-reports'
destination = Path(os.environ['V03A_CLASS_DIR']) / 'surefire'
name = os.environ['V03A_TEST_CLASS']
for pattern in (f'TEST-*.{name}.xml', f'*.{name}.txt'):
    for path in reports.glob(pattern):
        shutil.copy2(path, destination / path.name)
PY
  V03A_CLASS=$test_class V03A_CLASS_EXIT=$class_result python3 - <<'PY' || all_result=1
import json, os, xml.etree.ElementTree as ET
from pathlib import Path
run = Path(os.environ['V03A_PG_RUN_DIR'])
name = os.environ['V03A_CLASS']
reports = list((run / name / 'surefire').glob('TEST-*.xml'))
counts = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
for report in reports:
    suite = ET.parse(report).getroot()
    for key in counts:
        counts[key] += int(suite.get(key, 0))
passed = os.environ['V03A_CLASS_EXIT'] == '0' and counts['tests'] > 0 and all(counts[key] == 0 for key in ('failures', 'errors', 'skipped'))
path = run / 'matrix.json'
matrix = json.loads(path.read_text()) if path.exists() else []
matrix.append({'class': name, 'status': 'PASSED' if passed else 'FAILED', 'exitCode': int(os.environ['V03A_CLASS_EXIT']), **counts})
path.write_text(json.dumps(matrix, indent=2) + '\n')
print(json.dumps(matrix[-1]))
raise SystemExit(0 if passed else 1)
PY
done
if [[ "$all_result" -ne 0 ]]; then exit "$all_result"; fi
stage=complete
