# V43 real browser acceptance

Run `npm ci` in `frontend` first. Java 21, Maven and PostgreSQL binaries are required.
Set `JAVA_HOME` and `PG_BIN` for your machine; on macOS the launcher locates Java 21
with `java_home` and defaults PostgreSQL to `/Library/PostgreSQL/18/bin`.
Playwright uses installed Chrome on macOS. Else run `npx playwright install chromium`,
or select an installed channel with `V43_BROWSER_CHANNEL`.

From the repository root:

```sh
bash scripts/v43-browser-acceptance.sh
```

The script creates a fresh temporary PostgreSQL cluster on `127.0.0.1:55443`, starts
the test-source-only backend on `127.0.0.1:18043` and Vite on `127.0.0.1:5173`, runs
the tests, then stops its processes. Override `V43_PG_PORT`, `V43_BACKEND_PORT` or
`V43_FRONTEND_PORT` when those ports are occupied. Logs, screenshots and evidence
remain under the printed temporary directory. The disposable local database uses
trust authentication and only listens on loopback.

For interactive browser inspection, run `bash scripts/v43-browser-backend.sh`, then
`AGENTFLOW_API_TARGET=http://127.0.0.1:18043 npm run dev` from `frontend`.
Login as `v43-browser` with local fixture password `V43-browser-test!` and choose
Payment investigation (`430000000000000003`). The controlled model pauses after
the real order tool call. Write an empty `release-model` file inside the printed
`V43_CONTROL_DIR` to let the final decision and answer complete.

With both servers already running:

```sh
V43_CONTROL_DIR=/absolute/path/printed/by/launcher \
  npx playwright test --config e2e/playwright.config.ts
```

Acceptance exercises real login/JWT HTTP, dispatcher/Runner, snapshot execution,
RAG retrieval and canonical PostgreSQL chunk validation, ToolRuntime, recorder,
persisted SSE and public GET/Trace. Only the LLM, embedding and vector provider
boundaries are controlled. The first creation response is deliberately dropped
after the real server returns `201`; a browser reload and retry must return the
same task with the same Idempotency-Key (`200`). Browser network emulation covers
offline/reconnect; page reload rebuilds persisted events. Assertions cover event
deduplication, Bearer/resume headers, IDs beyond JavaScript's safe integer range,
terminal answer/citation/Trace agreement, cancellation, logout and real JWT rejection.

This proves the bounded M4G-A browser recovery loop. It does not prove live-provider
or Qdrant E2E, provider streaming, task execution retry, multi-turn conversations,
or remaining frontend management pages.
