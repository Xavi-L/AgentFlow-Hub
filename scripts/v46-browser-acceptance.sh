#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
export V45_BROWSER=1
export V46_BROWSER=1
export V43_FIXTURE_CLASS=com.agentflow.acceptance.V46BrowserFixture
export V43_READY_FILE=agents-ready
export V43_CONTROL_DIR=${V46_CONTROL_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/agentflow-v46-browser.XXXXXX")}
export V43_BACKEND_PORT=${V46_BACKEND_PORT:-18046}
export V43_PG_PORT=${V46_PG_PORT:-55446}
export V43_FRONTEND_PORT=${V46_FRONTEND_PORT:-5176}
if [[ -e "$V43_CONTROL_DIR/pgdata" ]]; then
  echo "Use a new V46_CONTROL_DIR; existing databases are never reused or stopped." >&2
  exit 1
fi
echo "V46 Agent + V43/V45 regression evidence: $V43_CONTROL_DIR"
# The child launcher is signalled at the end of Playwright; stop the daemon here too,
# so its asynchronous shutdown cannot leave this disposable PostgreSQL port occupied.
cleanup_database() {
  if [[ -f "$V43_CONTROL_DIR/pgdata/postmaster.pid" ]]; then
    "${PG_BIN:-/Library/PostgreSQL/18/bin}/pg_ctl" -D "$V43_CONTROL_DIR/pgdata" -m immediate -w stop >/dev/null 2>&1 || true
  fi
}
trap cleanup_database EXIT
bash "$repo_dir/scripts/v43-browser-acceptance.sh"
