#!/usr/bin/env bash
set -euo pipefail
# Offline acceptance-predicate checks only. Does not start any provider or application.
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
check_dir=$(mktemp -d "${TMPDIR:-/tmp}/agentflow-v47-predicate.XXXXXX")
trap 'rm -rf "$check_dir"' EXIT
cd "$repo_dir/frontend"
node_modules/.bin/tsc --noEmit --target ES2022 --module ESNext --moduleResolution Bundler \
  --strict --skipLibCheck --esModuleInterop --types node \
  e2e/real-provider.config.ts e2e/real-provider.spec.ts e2e/real-provider-evidence.ts e2e/real-provider-evidence.test.ts
node_modules/.bin/tsc --outDir "$check_dir" --target ES2022 --module CommonJS --moduleResolution Node \
  --strict --skipLibCheck --esModuleInterop --types node \
  e2e/real-provider-evidence.ts e2e/real-provider-evidence.test.ts
node --test "$check_dir/e2e/real-provider-evidence.test.js"
