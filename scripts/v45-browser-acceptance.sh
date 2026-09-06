#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
export V45_BROWSER=1
export V43_FIXTURE_CLASS=com.agentflow.acceptance.V45BrowserFixture
export V43_READY_FILE=knowledge-ready
export V43_CONTROL_DIR=${V45_CONTROL_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/agentflow-v45-browser.XXXXXX")}
export V43_BACKEND_PORT=${V45_BACKEND_PORT:-18045}
export V43_PG_PORT=${V45_PG_PORT:-55445}
export V43_FRONTEND_PORT=${V45_FRONTEND_PORT:-5175}
echo "V45 knowledge + V43 regression evidence: $V43_CONTROL_DIR"
exec bash "$repo_dir/scripts/v43-browser-acceptance.sh"
