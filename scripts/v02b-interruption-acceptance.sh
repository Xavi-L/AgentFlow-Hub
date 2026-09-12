#!/usr/bin/env bash
set -euo pipefail
umask 077
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
export PG_BIN=${PG_BIN:-/Library/PostgreSQL/18/bin}
exec python3 "$repo_dir/scripts/v02b-interruption-acceptance.py" "$@"
