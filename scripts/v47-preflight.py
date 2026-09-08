#!/usr/bin/env python3
"""Read-only environment gate. Never issue a paid embedding/chat generation probe."""
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None  # Never forward provider credentials to a redirect target.


def main():
    env = os.environ
    checks = []
    def check(name, ok, detail):
        checks.append(dict(name=name, passed=bool(ok), detail=detail))

    for name in ('mvn', 'node', 'python3'):
        check(name, shutil.which(name), 'available' if shutil.which(name) else 'missing')
    java = Path(env.get('JAVA_HOME', '')) / 'bin/java'
    try:
        version = subprocess.run([str(java), '-version'], capture_output=True, text=True, timeout=10)
        check('java21', version.returncode == 0 and 'version "21.' in version.stderr, 'Java 21 required')
    except (OSError, subprocess.TimeoutExpired):
        check('java21', False, 'Java 21 unavailable')
    try:
        version = subprocess.check_output(['node', '--version'], text=True, timeout=10).strip()
        parts = tuple(map(int, version.lstrip('v').split('.')))
        check('nodeVersion', parts >= (22, 12, 0), version)
    except (OSError, ValueError, subprocess.SubprocessError):
        check('nodeVersion', False, 'Node 22.12+ required')
    pg = Path(env.get('PG_BIN', '/Library/PostgreSQL/18/bin'))
    check('postgresBinaries', all(os.access(pg / n, os.X_OK) for n in
          ('initdb', 'pg_ctl', 'createdb', 'psql')), 'initdb/pg_ctl/createdb/psql required')
    repo = Path(__file__).resolve().parents[1]
    check('frontendDependencies', (repo / 'frontend/node_modules/@playwright/test/cli.js').is_file()
          and (repo / 'frontend/node_modules/vite/bin/vite.js').is_file(), 'npm ci in frontend if missing')
    browser_probe = """const {chromium}=require('@playwright/test');
      (async()=>{const browser=await chromium.launch({headless:true,timeout:10000,
        channel:process.env.V47_BROWSER_CHANNEL || (process.platform==='darwin'?'chrome':undefined)});
        await browser.close();})().catch(()=>process.exit(1));"""
    try:
        browser = subprocess.run(['node', '-e', browser_probe], cwd=repo / 'frontend',
                                 capture_output=True, timeout=15)
        check('browserLaunch', browser.returncode == 0, 'headless browser launch/close; no page or provider call')
    except (OSError, subprocess.TimeoutExpired):
        check('browserLaunch', False, 'install Chrome or Playwright Chromium for the selected channel')
    check('noControlledFixture', not any(env.get(n) for n in
          ('V43_CONTROL_DIR', 'V43_FIXTURE_CLASS', 'V45_BROWSER', 'V46_BROWSER')),
          'V43/V45/V46 controls must be absent')
    check('embeddingCredential', bool(env.get('DASHSCOPE_API_KEY', '').strip()),
          'present (validity checked only by real vectorization)' if env.get('DASHSCOPE_API_KEY') else 'DASHSCOPE_API_KEY missing')
    check('embeddingProfile', env.get('DASHSCOPE_EMBEDDING_MODEL') == 'text-embedding-v4'
          and env.get('DASHSCOPE_EMBEDDING_DIMENSIONS') == '1024'
          and env.get('QDRANT_VECTOR_SIZE') == '1024', 'frozen dashscope/text-embedding-v4/1024 profile')
    model = env.get('OPENAI_CHAT_MODEL', '')
    check('chatModelConfigured', bool(model.strip()) and len(model) <= 128, 'explicit requested model')
    try:
        timeout = int(env.get('V47_TASK_TIMEOUT_SECONDS', '180'))
        check('taskDeadline', 60 <= timeout <= 300, '60..300 seconds; default 180')
    except ValueError:
        check('taskDeadline', False, 'integer required')
    decision_cap = None
    try:
        decision_cap = int(env.get('AGENTFLOW_TASK_DECISION_MAX_OUTPUT_TOKENS', '512'))
        check('decisionOutputCap', 1 <= decision_cap <= 16384,
              dict(configuredTokens=decision_cap, range='1..16384; default 512; actual cap remains budget-constrained'))
    except ValueError:
        check('decisionOutputCap', False, 'integer required')
    schema_mode = env.get('AGENTFLOW_TASK_DECISION_JSON_SCHEMA_ENABLED', 'false').strip().lower()
    check('decisionJsonSchemaMode', schema_mode in ('true', 'false'),
          dict(enabled=schema_mode == 'true', note='explicit opt-in; provider/schema compatibility needs separate evidence'))
    json_mode = env.get('AGENTFLOW_TASK_DECISION_JSON_OBJECT_ENABLED', 'false').strip().lower()
    check('decisionJsonObjectMode', json_mode in ('true', 'false'),
          dict(enabled=json_mode == 'true', note='JSON mode does not enforce a schema; strict application validation remains'))
    check('exclusiveDecisionFormat', not (schema_mode == 'true' and json_mode == 'true'),
          'JSON schema and JSON object modes cannot both be enabled')
    thinking_mode = env.get('AGENTFLOW_TASK_PROVIDER_THINKING_DISABLED', 'false').strip().lower()
    check('providerThinkingMode', thinking_mode in ('true', 'false'),
          dict(disabled=thinking_mode == 'true', note='explicit provider opt-in; no reasoning content is recorded'))
    final_cap = None
    try:
        configured_final = env.get('AGENTFLOW_TASK_FINAL_MAX_OUTPUT_TOKENS', '').strip()
        final_cap = int(configured_final) if configured_final else None
        check('finalOutputCap', final_cap is None or 1 <= final_cap <= 16384,
              dict(configuredTokens=final_cap, range='1..16384 or unset; frozen reserve remains unchanged'))
    except ValueError:
        check('finalOutputCap', False, 'integer or unset required')
    demo = Path(env['V47_DEMO_FILE'])
    check('demoFile', demo.is_absolute() and demo.is_file() and demo.suffix.lower() in ('.md', '.txt')
          and 0 < demo.stat().st_size <= 2048, 'one TXT/MD file, at most 2 KiB')
    for name in ('V47_BACKEND_PORT', 'V47_FRONTEND_PORT', 'V47_PG_PORT'):
        try:
            port = int(env[name])
            if not 1024 <= port <= 65535:
                raise ValueError()
            with socket.socket() as s:
                s.bind(('127.0.0.1', port))
            check(name, True, port)
        except (ValueError, OSError):
            check(name, False, 'port invalid, occupied, or network permission denied')

    def endpoint(name):
        value = env.get(name, '').rstrip('/')
        p = urlsplit(value)
        ok = p.scheme in ('http', 'https') and bool(p.hostname) and not (p.username or p.password or p.query or p.fragment)
        check(name, ok, value if ok else 'must be HTTP(S), without embedded credentials/query/fragment')
        return value if ok else None

    def get(url, key='', qdrant=False):
        headers = {'api-key' if qdrant else 'Authorization': key if qdrant else f'Bearer {key}'} if key else {}
        local = urlsplit(url).hostname in ('localhost', '127.0.0.1', '::1')
        opener = build_opener(ProxyHandler({}) if local else ProxyHandler(), NoRedirect())
        try:
            with opener.open(Request(url, headers=headers), timeout=10) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, None
        except (URLError, OSError, ValueError):
            return None, None

    chat = endpoint('OPENAI_BASE_URL')
    endpoint('DASHSCOPE_BASE_URL')
    qdrant = endpoint('QDRANT_BASE_URL')
    if chat:
        local = urlsplit(chat).hostname in ('localhost', '127.0.0.1', '::1')
        key = env.get('OPENAI_API_KEY', '')
        check('chatCredential', local or bool(key.strip()), 'loopback may be keyless; remote requires OPENAI_API_KEY')
        status, body = get(chat + '/models', key)
        data = body.get('data') if isinstance(body, dict) else None
        ids = [item.get('id') for item in data if isinstance(item, dict)] if isinstance(data, list) else []
        check('chatModels', status == 200 and model in ids,
              dict(httpStatus=status, requestedModelListed=model in ids,
                   note='read-only /models; generation compatibility remains unverified'))
    collection = env.get('QDRANT_COLLECTION', '')
    check('isolatedCollectionName', re.fullmatch(r'v47_[a-zA-Z0-9_]{8,80}', collection), 'unique v47_ collection required')
    if qdrant:
        status, body = get(qdrant + '/', env.get('QDRANT_API_KEY', ''), True)
        check('qdrantServer', status == 200 and isinstance(body, dict) and bool(body.get('version')),
              dict(httpStatus=status, version=body.get('version') if isinstance(body, dict) else None))
        if re.fullmatch(r'v47_[a-zA-Z0-9_]{8,80}', collection):
            status, _ = get(qdrant + '/collections/' + collection, env.get('QDRANT_API_KEY', ''), True)
            check('freshCollection', status == 404, dict(httpStatus=status, collection=collection))
    passed = all(c['passed'] for c in checks)
    report = dict(status='PREFLIGHT_READY' if passed else 'BLOCKED', checks=checks,
                  budget=dict(tasks=1, retries=0, maxSteps=5, maxToolCalls=3, maxTokens=24000,
                              decisionMaxOutputTokens=decision_cap, decisionJsonSchemaEnabled=schema_mode == 'true',
                              decisionJsonObjectEnabled=json_mode == 'true',
                              providerThinkingDisabled=thinking_mode == 'true', finalMaxOutputTokens=final_cap,
                              maxDocumentBytes=2048, maxChunks=4),
                  boundary='No paid probe. READY here is an environment gate, never an E2E pass.')
    path = Path(env['V47_CONTROL_DIR']) / 'preflight.json'
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f"V47 {report['status']}: {path}")
    for c in checks:
        if not c['passed']:
            print(f"  {c['name']}: {c['detail']}")
    return 0 if passed else 2


if __name__ == '__main__':
    sys.exit(main())
