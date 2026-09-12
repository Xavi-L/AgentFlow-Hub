# Browser acceptance: controlled regression, V47 real providers, V48 failure recovery

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

## V48 task failures and browser observation recovery

From the repository root:

```sh
bash scripts/v48-failure-recovery-acceptance.sh --case F01_JSON
bash scripts/v48-failure-recovery-acceptance.sh
```

The first command is a single-case diagnostic and does not establish full V48 acceptance.
The second runs the complete matrix from
[`49_FAILURE_RECOVERY_E2E_PACKAGE_INTERFACE.md`](../../slice-docs/49_FAILURE_RECOVERY_E2E_PACKAGE_INTERFACE.md).
Each invocation requires a fresh `V48_CONTROL_DIR` (automatically created by default).
Ports default to frontend/backend/PostgreSQL 5178/18048/55448 and can be overridden by
`V48_FRONTEND_PORT`, `V48_BACKEND_PORT`, and `V48_PG_PORT`. The database is always
`agentflow_v48_browser`, with loopback-only disposable fixture credentials.

The V48 test-source fixture controls LLM, embedding/vector faults and the selected
builtin handler exception. JWT, task admission/snapshots, Runner, parser, RAG validation,
ToolRuntime, PostgreSQL, persisted events, public GET/Trace, and the browser remain real.
File gates provide entered/release/exited checkpoints, including late final responses
after cancellation/deadline. Browser transport injection drops committed responses,
changes offline state, or aborts the real SSE fetch; it never manufactures task events.
Recovery observes the original task and does not restart execution.

The printed directory retains `manifest.json`, `cases/<caseId>/calls.jsonl`, browser
and PostgreSQL evidence, screenshots, logs, and `run-result.json`. Fixture credentials
in the private local manifest are not copied into published case evidence. Expected
task failures can be passing cases; missing cases/evidence or incorrect counts fail
acceptance. Playwright and task execution retries are zero. Existing SSE reconnect
and explicit same-key confirmation are recorded separately.

V48 has its own exact Playwright test match and is excluded from the V43/V45/V46
configuration. It never starts paid V47 provider tests. If a V48 fix changes production
execution, rerun V47 preflight and the real main path using a fresh task; controlled
PASS results cannot replace that required regression. Keep historical failures.

## V45 knowledge management

Run `bash scripts/v45-browser-acceptance.sh` from the repository root. This uses
the same launcher with a fresh PostgreSQL cluster, real JWT and file parsing/storage,
and the V45 test-source provider fixture. Default ports are 5175 / 18045 / 55445;
override them with `V45_FRONTEND_PORT`, `V45_BACKEND_PORT`, and `V45_PG_PORT`.
The script runs `knowledge.spec.ts` and all three V43 task runtime regressions.
V45 tests are skipped when using the original V43 fixture.

Coverage includes TXT/MD upload -> PENDING -> explicit parsing -> INDEXING -> explicit
vectorization -> READY, public GET/detail agreement, both paginated lists, all five
Readiness states, current/old and large generations, incompatible/disabled knowledge
bases, real UTF-8 parsing failure, controlled embedding failure, lost committed write
responses across reload, a real 20-second browser timeout after parsing commits,
bounded polling, out-of-order pages, abort on leaving, logout, 404 and real JWT rejection.
`knowledge-evidence.json` and screenshots distinguish the real path from seeded states
and browser network fault injection. Embedding/vector remain controlled; this does not
prove live-provider/Qdrant E2E. Temporary source storage stays inside the fixture directory.

## V46 Agent configuration and controlled regressions

Run `bash scripts/v46-browser-acceptance.sh` from the repository root. It uses the
V46 test-source fixture and runs the V46 Agent tests plus all V43/V45 browser
regressions. Defaults are 5176 / 18046 / 55446; override with `V46_FRONTEND_PORT`,
`V46_BACKEND_PORT`, and `V46_PG_PORT`. Agent configuration, knowledge/tool bindings,
task execution, GET/Trace and unknown-write recovery use real HTTP/JWT/PostgreSQL;
Chat, embedding and vector boundaries remain controlled.

V49 adds a legacy 21-binding fixture to an existing pagination Agent. The browser
keeps all bindings visible, blocks overflowing writes without an unknown outcome,
removes one selection and saves exactly once; GET/reload and the independent
configuration draft remain consistent. On 2026-09-10,
`TMPDIR=/private/tmp bash scripts/v46-browser-acceptance.sh` passed 20/20 in 47.2 seconds.
The added scenario does not execute a task or call a provider. Its
`knowledge-binding-limit-evidence.json`, screenshot and durable summary are linked
from the [V49 acceptance record](../../slice-docs/50_KNOWLEDGE_BINDING_LIMIT_PACKAGE_INTERFACE.md).

Agent advanced settings add three scenarios: nullable inheritance and explicit reset after
an uncertain PATCH, exact-model capabilities and late-response isolation, and a real task
whose frozen settings survive later Agent changes. The fixture allowlists only its controlled
model; this is not evidence of a real model's JSON or thinking support. On 2026-09-11 the full
entry passed 23/23 in 56.6 seconds. `advanced-settings-evidence.json` records the Agent before
and after editing, the persisted v2 snapshot, and the controlled gateway's actual call settings.
See the [advanced settings contract](../../slice-docs/51_AGENT_ADVANCED_SETTINGS_PACKAGE_INTERFACE.md)
for configuration, migration, and verification details.

## V47 real-provider and Qdrant main-path E2E

Latest follow-up (2026-09-10): V49 passed a fresh GLM-5.2/DashScope/Qdrant run with task
`2098011391776641026`, 23/23 storage checks, both tools, independent final generation,
valid citations and refresh/GET/Trace convergence. It used 5428 reported chat tokens,
with one task submission and zero automatic retries. This remains a single-KB,
fixed-model demonstration; the [V49 evidence](../../release-docs/evidence/v49-2026-09-10.json)
is separate from the earlier [V0.1 Release Gate](../../release-docs/V0.1_RELEASE_GATE.md).

Historical result (2026-09-07): one GLM-5.2/DashScope/Qdrant normal-application main path
PASSED on launcher attempt nine, with both tools, independent final generation,
valid citations and refresh/GET/Trace convergence; storage checks passed 23/23.
The first seven actual tasks failed, and attempt eight failed before application
startup without creating a task. Focused history-prompt tests passed 46/46 and the
then-current default controlled regression passed 19/19 in 44.6 seconds. This is one
fixed-model success, not a reliability assessment or V0.1 release acceptance.

V47 has its own launcher and Playwright configuration. It starts
`com.agentflow.AgentFlowApplication` with production sources/runtime dependencies,
remote DashScope embedding and actual Qdrant. It never loads the V43/V45/V46 fixture
classes. The controlled commands above remain independent and never run paid V47
generation as a regression side effect.

Prepare the same Java/Node/Maven/PostgreSQL/browser dependencies, and run `npm ci`
in `frontend`. Start the actual Chat service and Qdrant, then configure the process
environment or a trusted local shell file through `V47_ENV_FILE`. The launcher
sources that file; use a file you control. Keep credentials out of the repository.

| Setting | Required behavior |
| --- | --- |
| `OPENAI_BASE_URL`, `OPENAI_CHAT_MODEL` | Default URL: `http://127.0.0.1:1234/v1`; model follows the `OPENAI_CHAT_MODEL` fallback in `backend/src/main/resources/application-dev.yml` (currently `google/gemma-4-e4b`), unless explicitly set in the environment. Read-only `/models` must list the requested model |
| `OPENAI_API_KEY` | Required for remote Chat; loopback Chat may be keyless |
| `DASHSCOPE_API_KEY` | Required; actual vectorization checks validity and service availability |
| `DASHSCOPE_BASE_URL` | Defaults to `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `DASHSCOPE_EMBEDDING_MODEL`, `DASHSCOPE_EMBEDDING_DIMENSIONS`, `QDRANT_VECTOR_SIZE` | Must match the existing profile: `text-embedding-v4`, `1024`, `1024` |
| `QDRANT_BASE_URL`, `QDRANT_API_KEY` | Defaults to `http://127.0.0.1:6333`; key required only when that server uses authentication |
| `JAVA_HOME`, `PG_BIN` | Java 21 and PostgreSQL binaries, as for the controlled launcher |
| `AGENTFLOW_TASK_DECISION_MAX_OUTPUT_TOKENS` | Server decision output limit: default 512, range 1–16384; runs five/six used 2048, the Zhipu adaptation selects 8192, bounded by the remaining task/context budget |
| `AGENTFLOW_TASK_FINAL_MAX_OUTPUT_TOKENS` | Unset by default, retaining the existing final-reserve cap; explicit range 1–16384, Zhipu selects 8192; extra capacity requires actual remaining budget, while the reserve stays 2048 |
| `AGENTFLOW_TASK_DECISION_JSON_SCHEMA_ENABLED` | Default false; true in run six, false for Zhipu; mutually exclusive with JSON object mode |
| `AGENTFLOW_TASK_DECISION_JSON_OBJECT_ENABLED` | Default false; explicitly true for Zhipu DECISION calls; JSON object mode does not enforce the decision schema; final generation remains text |
| `AGENTFLOW_TASK_PROVIDER_THINKING_DISABLED` | Default false, omitting this provider option; explicitly true for Zhipu to send `thinking.type=disabled` |
| `AGENTFLOW_AGENT_JSON_SCHEMA_MODELS` | Comma-separated exact model IDs allowed to use schema mode; required when schema mode is enabled |
| `AGENTFLOW_AGENT_JSON_OBJECT_MODELS` | Comma-separated exact model IDs allowed to use JSON object mode; required when JSON object mode is enabled |
| `AGENTFLOW_AGENT_THINKING_DISABLED_MODELS` | Comma-separated exact model IDs allowed to disable thinking; required when that option is enabled |

All three capability lists default to empty. The advanced-settings policy applies them to
inherited modes as well as explicit Agent overrides. V47 preflight requires the selected
`OPENAI_CHAT_MODEL` in each enabled option's list, so a missing capability fails before a
generation call rather than silently testing a different request mode. For the previously
accepted `glm-5.2` JSON-object/thinking-disabled configuration, explicitly set both
`AGENTFLOW_AGENT_JSON_OBJECT_MODELS=glm-5.2` and
`AGENTFLOW_AGENT_THINKING_DISABLED_MODELS=glm-5.2`; the launcher does not grant capabilities.

From the repository root:

```sh
bash scripts/v47-evidence-check.sh
bash scripts/v47-real-provider-acceptance.sh --preflight-only
bash scripts/v47-real-provider-acceptance.sh
```

`v47-evidence-check.sh` runs focused TypeScript checks and five offline acceptance
predicate tests. It starts no application or provider and does not count as real E2E.

Preflight makes no Chat/embedding generation call. It checks environment/toolchain,
headless browser launch/close without opening a page, available ports, model listing,
Qdrant reachability and a fresh isolated collection.
It checks the DashScope credential is present and the profile configuration matches;
it does not prove the credential works or that generation will succeed. Missing
configuration, inaccessible services or failed assertions exit nonzero and preserve
the reached stage. No mock fallback or automatic rerun is allowed.

Each run gets a fresh loopback PostgreSQL cluster, document storage and randomly
named `v47_` Qdrant collection. Defaults are frontend 5177, backend 18047, PostgreSQL
55447; override with `V47_FRONTEND_PORT`, `V47_BACKEND_PORT`, and `V47_PG_PORT`.
`V47_CONTROL_DIR` must be unused; `V47_QDRANT_COLLECTION` may name a new collection
matching `v47_[a-zA-Z0-9_]{8,80}`. The launcher refuses an existing collection.
On exit it stops its frontend/backend/PostgreSQL processes and retains the database,
logs and Qdrant collection. After inspection, manually remove only the precise
collection named in that run's report if it is no longer needed.

The test prepares one user through the existing register API, then uses the browser
to log in, create a KB, upload `scripts/fixtures/v47-payment-diagnosis.md`, explicitly
parse and vectorize it to READY, create an Agent, bind that KB and both builtin tools,
and submit: `帮我分析 order_1024 支付失败的原因，并给出处理建议。`
The demo document contains generic timeout guidance, not that order's query results.
Both real builtin handlers use the existing V12 demo business tables; this is not
an external payment-system integration.

The budget is one task, retries=0, maxSteps=5, maxToolCalls=3 and maxTokens=24000.
The third tool allowance leaves room for the model's FINISH decision after exactly
two successful tool calls. A pass requires terminationReason=ANSWERED and a separate
successful FINAL_GENERATION. The expected path makes three decision calls plus one
final call; the existing step budget bounds it to five decision calls plus one final.
`V47_TASK_TIMEOUT_SECONDS` defaults to 180 and permits 60–300 seconds.
`OPENAI_TIMEOUT` independently sets the backend Chat connect/read timeout (default
`PT30S`). For a slow local model, an explicitly requested run can use
`OPENAI_TIMEOUT=PT120S V47_TASK_TIMEOUT_SECONDS=300` while retaining the same
token/step/tool budgets and zero automatic retries.
`V47_DEMO_FILE` may select one TXT/MD file of at most 2048 bytes and four parsed chunks.
Chat token accounting does not include the separate ingestion/query embedding calls
and is not a monetary spending limit.

After the fourth run's empty response at the 512-token limit, the authorized
adaptation makes this limit configurable through
`agentflow.task.execution.decision-max-output-tokens` and the environment variable
above. `TaskExecutionProperties` supplies the configured value to the executor;
each actual cap remains the minimum of that limit, the task's remaining token budget
after reserving final input/output, and available context. The adaptation run uses
2048 with `OPENAI_TIMEOUT=PT120S V47_TASK_TIMEOUT_SECONDS=300`, preserving the 24000
total budget, final reserve, step/tool limits and single-JSON decision protocol.
Existing Trace `requestSnapshot.maxOutputTokens` records the actual cap; no new
public DTO or migration is introduced. The adaptation passed 25 focused backend
tests (19 executor and 6 property tests); preflight Python syntax also passed.
That adaptation validated 1–4096 (extended to 1–16384 for Zhipu) and recorded `budget.decisionMaxOutputTokens`; the actual
request cap remains a Trace fact. The fifth real run verified cap 2048 and both
tools, but failed on fenced FINISH; the later schema run failed on repeated intent,
and all seven runs below retain FAILED results. No frontend code changed, so the
earlier frontend unit/build results were not rerun for this adaptation.
After the adaptation, the V46/V45/V43 controlled suite was rerun with the default
512-token decision limit: 19/19 passed in 48.7 seconds, exit 0. Evidence is at
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v46-browser.X5X9dv`.
This is separate from the earlier 46.5-second controlled run and does not establish
the result of the explicit 2048-token real-provider run.

The subsequent Schema adaptation uses server property
`agentflow.task.execution.decision-json-schema-enabled`. When enabled, DECISION
requests carry `response_format` json_schema with strict=true and name
`agent_decision_v1`. Its object/oneOf schema derives CALL_TOOL branches from the
frozen tools (the original four fields and each tool's inputSchema) plus the original
two-field FINISH branch. Internal `responseSchema={name,schema}` is immutable and
limited to 64 KiB; existing Trace request allowlisting/projection preserves it.
Schema bytes count toward estimated input, remaining context/task budgets and
unknown-usage accounting. Cap 2048, total 24000, final reserve and 120/300-second
limits remain. Final generation has no schema. Parser/prompt stay unchanged: no
fence stripping, unsupported-provider fallback, self-repair or automatic retry.
Schema code passed 71 focused tests: Gateway 6, HTTP 10, executor 23, properties 7,
schema 3, parser 3, sanitizer 14 and public projection 5. Python syntax and four
offline storage-format guards also passed (disabled/default mode, enabled mode
without schema rejected, schema recorded, and final without schema).
The collector retains `response_schema` and checks
`decision_schema_mode_matches_requests` / `final_generation_keeps_text_format`.
These local/HTTP-stub and offline results are distinct from the sixth real application
run below, which verified schema transmission but failed on repeated tool intent.
After the Schema adaptation, the default schema-off/cap-512 controlled suite also
passed 19/19 in 47.3 seconds, exit 0; evidence:
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v46-browser.u5OIAK`.
This is separate from the earlier controlled runs and does not prove the schema-on
real-provider path.

The subsequent authorized Zhipu configuration uses model `glm-5.2` at
`https://open.bigmodel.cn/api/paas/v4`. The authorized IDEA `ZHIPU_API_KEY` is mapped
to the existing `OPENAI_API_KEY` only in the launched process environment; do not
print it or save it in evidence. The native compatibility probe below passed; the
seventh full application E2E FAILED on repeated order_query intent despite valid JSON.

This adaptation explicitly sets decision JSON object mode on, JSON Schema mode off,
and provider thinking disabled. Both JSON modes must not be enabled together.
DECISION carries `response_format.type=json_object`; it has no `responseSchema`
and does not promise valid action fields or correct tool selection. Final generation
has neither JSON response-format constraint and still returns answer text. The
thinking-disabled option sends `thinking.type=disabled` for these model calls.
This is an explicit provider choice, with no unsupported-format fallback, fence
stripping, parser relaxation, automatic repair or retry.

The server properties `agentflow.task.execution.decision-max-output-tokens` and
`final-max-output-tokens` permit 1–16384; Zhipu explicitly requests 8192 for each.
The final setting is unset by default and retains the old reserve-based cap.
Decision budgeting continues to protect final input plus the existing 2048-output
reserve. An explicit final cap above that reserve uses only capacity left after
already-accounted usage and the conservative estimate of this request's input,
within both task and context limits; provider input usage is not known before the call. Total tokens
remain 24000, steps 5, tools 3, Chat timeout 120 seconds and task timeout 300 seconds.
Trace must record actual request caps/options rather than treating the configured
8192 as proof that every request receives that allowance. The collector compares
request options field by field between PostgreSQL and public Trace. Its final-format
check now returns false when no final call exists; historical sixth-run artifacts
remain unchanged. These checks do not establish the seventh run's outcome.

The Zhipu adaptation passed 98/98 focused Java tests across nine classes. The first
run passed all 86 non-HTTP tests; sandbox restrictions blocked the loopback HTTP
class, and only that class was rerun with local listening available, passing 12/12.
Logs: `/private/tmp/v47-zhipu-core-focused-tests.log` and
`/private/tmp/v47-zhipu-http-focused-tests.log`. Python syntax, ten request-option
boundary checks and three offline integration assertions also passed. The latter
used copies of sixth-run evidence to check PostgreSQL/public Trace option equality,
missing-final rejection and tampered-cap rejection; original artifacts were unchanged.
The default-configuration controlled V46/V45/V43 suite passed 19/19 in 45.2 seconds;
log: `/private/tmp/v47-zhipu-controlled-browser.log`. These are separate controlled
results, not new provider calls or the seventh real E2E result.

A pass additionally requires actual retrieval hits for this document's current
generation, exactly one successful `order_query` and `payment_log_query` through
ToolRuntime, a substantive answer with valid RAG citations, and agreement between
the rendered result after reload and persisted GET/Trace. Bindings or COMPLETED
alone are insufficient. The run records requested/resolved models and provider IDs
when available, token usage/quality, task/KB/document/Agent IDs, retrieval/tool/final
evidence, Qdrant/PostgreSQL checks and screenshots.
These assertions check structural validity and concrete fact markers. After a real
run, compare the full answer and its advice with the tool results and document, and
record that review; keyword checks do not establish complete semantic correctness.

The printed evidence directory contains the artifacts reached by that run:
`preflight.json`, `run-result.json`, `browser-evidence.json`, `storage-evidence.json`,
`browser-artifacts/` and process logs. `PREFLIGHT_READY` is an environment result;
only the full automated path may produce `PASSED`; final slice acceptance also
records the answer review described above. Failure before task creation has no task
evidence. The test entrypoint is `frontend/e2e/real-provider.config.ts`; it is excluded
from the controlled configuration. For actual execution status and claim boundaries,
see `slice-docs/48_REAL_PROVIDER_E2E_PACKAGE_INTERFACE.md`.

On 2026-09-07 an earlier preflight returned `BLOCKED` because the process environment
lacked `DASHSCOPE_API_KEY` and Chat `/models` returned no HTTP response. After configuring
the actual dependencies, one explicit real run reached READY and retrieved its current
generation from Qdrant, then **FAILED** at the first Chat decision. `qwen/qwen3-1.7b`
returned two adjacent top-level CALL_TOOL objects, violating the existing single-object
decision contract. Task `2096862310168502274` recorded `AGENT_INVALID_DECISION`, 1342
exact Chat tokens, zero tool calls and zero final generations. The 16.4-second browser
test failed; no answer/citation or successful reload path was verified. No parser
relaxation, mock fallback or automatic rerun was performed. Evidence is retained at
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-hqs0ksyt`.
The same turn's 19/19 controlled regressions remain separate evidence; V47 success
acceptance was incomplete at that stage.

The user then selected `qwen/qwen3.5-9b`. With the original 30-second Chat read
limit, its first decision and `order_query` succeeded, but the second model call
ended at 30.019 seconds with client disconnect/cancellation in the provider log.
The retained database supplied post-run read-only evidence after a launcher editing
mistake interrupted collection; the original exit 127 remains recorded.

A further bounded run used `OPENAI_TIMEOUT=PT120S V47_TASK_TIMEOUT_SECONDS=300`,
with unchanged prompt and token budget. Three valid JSON decisions all requested
`order_query`: one real execution, one cached observation reuse, then
`AGENT_DUPLICATE_TOOL_LOOP`. The application request snapshots already contained
the order results and offered `payment_log_query`. There was no timeout in this run,
but no payment-log execution or final answer either. That run's task:
`2096888399116828674`, exact usage 3821 tokens, FAILED; evidence:
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-9b-timeout-a8uub6n_`.
Each run used zero automatic retries. V47 success acceptance remained incomplete after those runs.

The fourth explicit run followed the user's new application model, `google/gemma-4-e4b`,
with unchanged prompt/step/tool/token budgets and the same 120-second Chat / 300-second
task limits. Task `2096891315613560833` reached READY/current-generation retrieval and
executed `order_query` once. Its second decision returned empty content with
`finish_reason=length`: the 512-token request cap yielded 511 output tokens, including
509 reasoning tokens, without a decision. The gateway rejected the empty content;
this was not a 120-second timeout. Trace retains 5997 MIXED tokens, including the failed
call's conservative estimate; the server's two reported usages total 2835 and are
recorded separately, without replacing Trace or asserting a bill. The 53.1-second
browser run failed with `AGENT_LLM_FAILED`, no payment-log call or final answer.
Evidence: `/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-4b-e0rjnjjt`.
Only usage and response shape are retained for the failed response, never reasoning
text. That fourth-run result remains FAILED; no automatic retry or success claim followed it.

The fifth run used the same model/prompt/total budgets with decision cap 2048:
task `2096902272884645889`, current-generation retrieval, and both tools successfully
executed once (order 11 ms, payment log 24 ms, retryCount=0). All three actual caps
were 2048; exact usage was 4466 tokens. The third FINISH/answerPlan response had a
Markdown json fence, violating the existing prompt and strict whole-response JSON
contract. It failed with `AGENT_INVALID_DECISION`, without accepted FINISH, final
generation, answer/citations or successful reload. Browser elapsed time was about
74.2 seconds; storage collected with errors=[] but FAIL, run exit 1, automatic retries 0.
Evidence: `/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-4b-2048-jjvz83tf`.
Response IDs/caps, safe shape/usage summaries and the FAILED/16-event/two-tool screenshot
were checked; no reasoning text was retained, and run ports were closed. This run
observed no length/empty-content response, but its second completion was only 475
tokens, below the old 512 cap; one variable generation does not prove the higher cap
alone fixed truncation. Both tools had real evidence in that fifth run, but it remained FAILED;
parser/prompt were unchanged and this run did not retry. The sixth run below was
separately authorized afterward.

A separate native Schema compatibility probe then passed against Gemma: one request,
zero retries, max 2048 tokens/120 seconds, approximately 4.7 seconds, bare FINISH,
77 input + 26 output = 103 reported tokens (reasoning count 0). Request ID:
`chatcmpl-hrjuluzdcs59l3fvijsp06`; evidence:
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/v47-json-schema-compat-_gomzhte`.
It generated an actual response, so is separate from read-only preflight; it made no
embedding/Qdrant calls or business task. This proves only that specific native request's
compatibility, not application integration or E2E.

The sixth real run enabled Schema with Gemma, cap 2048 and unchanged prompt/budgets:
task `2096937495382958081`; evidence:
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-4b-schema-6jee66ry`.
All three decisions were valid bare JSON/SUCCESS/stop. Each actual request used
json_schema/strict=true, with the complete schema matching persisted/frozen tool data.
However, all three requested order_query: one actual ToolRuntime call (6 ms), one
cached reuse, then `AGENT_DUPLICATE_TOOL_LOOP`. Request snapshots contained both tools
and prior results. Exact usage was 1048 + 1146 + 1250 = 3444 tokens; reasoning counts
were 0. The 19.4-second browser run FAILED, storage FAIL/errors=[], exit 1, retries 0.
GET/Trace agreed on 17 continuous events; the FAILED/no-answer screenshot was checked,
and no refresh checkpoint or final generation was reached. The final-format guard's
true result is vacuous when no final call exists. Ports were closed. This verifies
Schema transmission and JSON shape, not reliable action selection or why the model
looped; fifth-run payment-log success remains separate historical evidence. This round
comprised one compatibility probe plus one E2E, with no automatic seventh run.

A later authorized native Zhipu probe passed against `glm-5.2` at
`https://open.bigmodel.cn/api/paas/v4`; GET `/models` returned 200 and listed the model.
Evidence: `/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/v47-zhipu-glm52-probe-6jhr1dnp`.
The generated request used json_object, `thinking.type=disabled` and max_tokens 8192.
It returned HTTP 200/stop in 2.188 seconds, request ID
`202609072116078ee2823883904f3b`, with 124 input + 23 output = 147 reported tokens.
The JSON had no Markdown fence or `reasoning_content`. This is basic native JSON
object compatibility, not JSON Schema enforcement or observation of internal model
reasoning. It generated a response, so is separate from read-only preflight, and
created no business task or embedding/Qdrant/ToolRuntime evidence. The previous six
FAILED records remain intact.

The seventh full application E2E used the explicit Zhipu configuration above,
with the same prompt, decision/final settings of 8192, 24000 total tokens, five steps,
three tools, 2048 final reserve, 120-second Chat timeout and 300-second task timeout.
Evidence directory:
`/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-zhipu-glm52-4bawq2af`.
Preflight was READY; the normal application loaded no controlled Gateway.
Task `2096953425345351681`, Agent `2096953423323697154`, KB `2096953416898023425`,
document `2096953417262927873`, chunk `2096953417648803842` and collection
`v47_d048f077e0da4730a933232a1c7a3f05` identify this independent run. Actual
DashScope/Qdrant/RAG matched current generation 0, one valid hit, zero stale hits,
score 0.66849273, and matching document/chunk/point identities.

All three GLM-5.2 DECISION calls returned valid JSON/SUCCESS/stop. Recorded options
were maxOutputTokens 8192, json_object, thinking disabled and no responseSchema,
matching field by field in PostgreSQL and public Trace. Request IDs, latency and
reported input/output/total tokens were:

| Call | Provider request ID | Latency | Input / output / total |
| --- | --- | --- | --- |
| 1 | `202609072127117ca4599cee484941` | 1767 ms | 976 / 48 / 1024 |
| 2 | `202609072127125ad53690a0984d5d` | 1447 ms | 1061 / 44 / 1105 |
| 3 | `20260907212714fba4f6be8c014458` | 1959 ms | 1145 / 40 / 1185 |

Total usage was EXACT 3314 = 3182 input + 132 output, not a reconciled bill.
All three chose `order_query(order_1024)`: one real ToolRuntime execution (6 ms),
one cached observation reuse, then `AGENT_DUPLICATE_TOOL_LOOP`. Each application
request offered payment_log_query; the second already contained the order result,
and the third also contained its cached reuse. No missing history/tool was found.

The browser FAILED in 9.9 seconds at verify-persisted-evidence. Run status was
FAILED/exit 1/stage storage-evidence, storage FAIL/errors=[], with zero automatic
retries. No payment_log_query, FINISH, independent final generation, final answer,
final citations or successful refresh occurred; the missing-final format check
was false. GET task matched Trace.task, all 17 events were continuous and ended
in TASK_FAILED. The failure-state screenshot was checked. Run and controlled ports
were closed, retaining the evidence, temporary database and Qdrant collection.

The safe provider-response-summary.json records IDs/options/usage and outcome,
without credentials or reasoning text. These are application/public Trace options;
wire serialization was tested with the focused HTTP stub, not vendor-side request
logs. This run had no JSON-format error, output exhaustion or timeout, so those
do not explain this failure. It does not establish provider-wide reliability or
that disabling thinking caused the loop. At the end of that seventh run the prompt
had not changed and no further task had been launched. The subsequent independent
history-prompt diagnostics are recorded below. Fifth-run payment-log success remains
separate historical evidence.

### Independent decision replay and history-prompt adaptation

`scripts/v47-decision-replay.py` is a bounded paid diagnostic entrypoint. It executes
single Chat requests against GLM-5.2; it never invokes tools, browsers, embedding,
Qdrant, application task mutations or final generation. Inject `OPENAI_API_KEY`
through the authorized process environment, without printing or saving the key.
The source must be a GLM-5.2 browser-evidence.json with recorded json_object,
thinking disabled and maxOutputTokens 8192. The default source is DECISION 2,
which must already contain the order result and no payment-log result.

```sh
OPENAI_BASE_URL=https://open.bigmodel.cn/api/paas/v4 \
python3 scripts/v47-decision-replay.py \
  --source-evidence /absolute/path/to/glm52-run/browser-evidence.json \
  --candidate-instructions scripts/fixtures/v47-decision-history-instructions.txt \
  --output-dir /absolute/path/to/new-replay-directory
```

The output directory must not exist, and runs cannot resume automatically.
A reuses the saved messages unchanged; B appends the candidate only to the second
SYSTEM content. Both retain exactly three SYSTEM/SYSTEM/USER messages, with the
first SYSTEM and USER payload unchanged. The six independent calls use fixed
A,B,B,A,A,B order; responses are not fed into later requests. Model, temperature,
top_p, 8192 output cap, json_object and thinking-disabled options remain the same.
Each invocation has a 60000-token ceiling and a 120-second per-request timeout.
The UTF-8 input estimate plus 8192 must fit remaining tokens and the source context
window before each request; an insufficient budget stops the run without reducing
the cap. Unknown usage, HTTP/transport errors or malformed provider envelopes stop
subsequent requests; there is no retry or format fallback.

The parser rejects fences, trailing content, duplicate JSON keys and extra action
fields, and validates the used frozen-tool schema subset. That subset is deliberately
limited to the two current builtins' object/string/integer/required and top-level
required-only anyOf rules; unsupported schema capabilities block execution.
`inputs.json` retains complete safe messages and source identities. `report.json`
records safe type/toolCode/arguments/answerPlan, validity, expected-action matches,
provider IDs/model/usage, latency and content length. `reason` is length-checked but
not saved; credentials, reasoning text and provider error bodies are omitted.
COMPLETED means the planned diagnostic collection finished, while `groups` and
`allExpectedDecisions` report action outcomes. Neither field means application E2E PASS.

For an explicitly prepared two-result input, use the same command with
`--mode candidate --expected FINISH --state-input /absolute/path/to/two-results-state.json`.
This sends only B,B. The state envelope requires exactly schemaVersion 1,
description, sourceEvidenceFiles containing absolute existing paths, and three
messages. Record whether historical runs were combined: that is a diagnostic state,
not a same-task production snapshot or newly executed ToolRuntime evidence.

On 2026-09-07, the paired run used seventh-run task `2096953425345351681`,
DECISION 2 / call `2096953439882809346`, with source provider request ID
`202609072127125ad53690a0984d5d`. The candidate was 983 UTF-8 bytes; conservative
A/B input estimates were 4245/5229 tokens. Both used GLM-5.2, temperature 0.1,
top_p 0.8, json_object, thinking disabled and max_tokens 8192.
Evidence: `/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/v47-history-paired-dx53hc5r/replay`.

| Variant | Valid decision/arguments | Expected payment_log_query(order_1024) | Observed choice | Reported tokens |
| --- | --- | --- | --- | --- |
| A: original prompt | 3/3 | 0/3 | order_query three times | 3305 |
| B: explicit execution history | 3/3 | 3/3 | payment_log_query three times | 3856 |

All six responses finished with stop, with 6883 input + 278 output = 7161 tokens
and zero retries. The result supports promoting this candidate for the tested
decision state; it does not establish statistical reliability or a unique internal
cause of the previous loop. No selected tool executed during these requests.

The subsequent candidate-only FINISH replay used a historical composite:
seventh-run task `2096953425345351681` DECISION 2 supplied the original messages
and order result; fifth-run task `2096902272884645889` DECISION 3 supplied its
recorded successful payment-log observation. The diagnostic payload set remaining
budgets to three decisions and one tool call. This is explicitly not one production
task's frozen state. Provenance is recorded in two-results-state.json and replay inputs.
Evidence: `/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/v47-history-finish-968qb6_y`.
The two B requests returned valid FINISH/stop, using 1580 and 1560 tokens
(2666 input + 474 output = 3140 total). Provider IDs were
`20260907223801ba11db47ca794e6e` and `20260907223806547d02f43570455d`.
No independent final generation, citation verification or refreshed application
result occurred. The two diagnostics together used eight Chat calls / 10301
reported tokens and are separate from the seven application E2E runs.

The exact reviewed candidate has now been appended to the existing production
TaskPromptBuilder's second SYSTEM content. It describes observations as completed
call history, cached reuse as already completed, and identical-argument repeats as
cache reuse rather than refreshed data. It asks for missing information or FINISH
when evidence suffices, while retaining the distinction between returned facts and
untrusted instructions. It hardcodes neither order_1024 nor a tool sequence, and
does not introduce a universal once-per-tool rule. The existing strict parser,
same-intent cache, third-repeat guard and separate final generation remain.

### Eighth launcher attempt: PostgreSQL startup failed before task creation

Evidence: `/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-zhipu-history-1dij76aj`.
Preflight was PREFLIGHT_READY, but run-result.json recorded FAILED/exit 1/stage
postgres: the Unix-domain socket path exceeded PostgreSQL's 103-byte limit.
The application and browser had not started. No task, paid Chat/embedding call or
tool execution occurred, and browser-evidence.json/storage-evidence.json do not
exist for this attempt. This is a launcher startup failure, not a model failure.
The necessary launcher fix disables the unused Unix socket; database creation,
application and evidence clients already use loopback TCP. A fresh directory was
used for the next launch. It did not replay an uncertain task submission: only one
new task was planned and actually created across these two launch attempts.

### Ninth launcher attempt / eighth actual task: real main path PASSED

Evidence: `/var/folders/rk/2gmg78l55dl2m5ld98kpb3100000gn/T/agentflow-v47-real-zhipu-history-9yxypy7d`.
Preflight was PREFLIGHT_READY; the normal main application loaded no controlled
Gateway. The browser PASSED/complete in about 22.8 seconds. Run-result was
PASSED/exit 0/stage complete, and storage reported PASS with all 23 checks true
and no errors. Exactly one task was submitted, with zero automatic retries.

Task `2096972424179240962`, Agent `2096972422098866178`, KB `2096972415597694978`,
document `2096972415962599426`, chunk `2096972416348475394` and Qdrant collection
`v47_3eca76909da441e693ca3cb92005d350` identify this new run. The browser uploaded
the original 1293-byte MD, explicitly parsed and vectorized it using real DashScope
text-embedding-v4/1024, and read READY. Actual Qdrant/RAG matched the current
generation 0: one candidate, one valid hit, zero stale hits, score 0.66849273,
with matching point/chunk/document identity and content.

The Agent business prompt, task input and budgets remained as in run seven; the
production second SYSTEM message now contained the exact reviewed history-prompt
candidate. GLM-5.2, temperature 0.1/top_p 0.8, context 32768, total budget 24000,
five decisions, three tools, final reserve 2048 and Chat/task timeouts 120/300 seconds
were unchanged. All four actual requests had cap 8192 and thinking disabled;
only DECISION had json_object, while FINAL_GENERATION had neither JSON constraint.
Recorded request options agreed between PostgreSQL and public Trace.

| Stage | Provider request ID | Latency | Input / output / total |
| --- | --- | --- | --- |
| DECISION 1: order_query | `20260907224240ed49149e514348bf` | 1379 ms | 1149 / 41 / 1190 |
| DECISION 2: payment_log_query | `2026090722424216deef0149d04811` | 2594 ms | 1232 / 61 / 1293 |
| DECISION 3: FINISH | `20260907224244b43d8954f14245b1` | 4012 ms | 1335 / 190 / 1525 |
| FINAL_GENERATION | `20260907224248a0a682b4f66049a7` | 10763 ms | 1043 / 673 / 1716 |

All calls were SUCCESS with requested/resolved model glm-5.2. Exact reported usage
was 4759 input + 965 output = 5724 tokens. Both order_query and payment_log_query
executed once through ToolRuntime with orderNo order_1024, each SUCCESS/5 ms/
retryCount 0. These are new task-scoped tool logs, with no historical results or
cached reuse substituted for execution.

The task completed as COMPLETED/ANSWERED after three decisions and two tool calls,
about 19.056 seconds from startedAt to completedAt. All 20 events were continuous
and ended in TASK_COMPLETED. The strict parser accepted FINISH and a separate
FINAL_GENERATION persisted the answer. Its S1 citation mapped to the new run's
retrieved document/chunk/generation 0. The refresh-and-trace-convergence checkpoint
completed; pre/post-refresh task and Trace were identical, and rendered answer/events
matched persisted GET/Trace.

Answer review against the two tool results and document confirmed the order,
E_PAY_TIMEOUT, amount, pay-trace-1024 and 3000ms facts. The answer separated document
advice from facts, advised checking channel status before another debit, and retained
unknown final debit status and latency cause. It did not claim live external channel
integration or automatic reconciliation capability. This is one accepted fixed-model,
fixed-document main path, not a statistical reliability or production payment claim.

History-prompt focused Java tests passed 46/46, log
`/private/tmp/v47-history-focused-tests.log`; the final default V43/V45/V46 controlled
browser regression passed 19/19 in 44.6 seconds, log
`/private/tmp/v47-history-controlled-browser.log`. These remain distinct from the
real task's evidence. This phase used eight replay calls plus four task calls:
12 Chat requests and 10301 + 5724 = 16025 reported tokens. That excludes embedding
usage and is not a reconciled bill. The earlier seven failed tasks and eighth
launcher startup failure remain preserved; one later success does not erase them.

Full failure E2E, repository generated-artifact cleanup and V0.1 release acceptance
remain later work. A single real-provider run does not establish model generalization,
retrieval/citation accuracy statistics or production payment correctness.
