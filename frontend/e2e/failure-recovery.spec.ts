import { test, expect, type Page, type BrowserContext, type Request as BrowserRequest, type APIResponse, type Response } from '@playwright/test'
import assert from 'node:assert/strict'
import { access, mkdir, readFile, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import path from 'node:path'
import { parseJson } from '../src/lib/sequence'
import type { Task, TaskTrace } from '../src/lib/types'
import { EXPECTED, checked, verifyPublicEvidence, type CaseExpectation, type EvidenceCheck } from './failure-recovery-evidence'

const control = process.env.V48_CONTROL_DIR
if (!control) throw new Error('Set V48_CONTROL_DIR to the running disposable V48 backend fixture directory')
const selectedCase = process.env.V48_CASE
if (selectedCase && !EXPECTED[selectedCase]) throw new Error(`Unknown V48_CASE: ${selectedCase}`)

interface ManifestCase extends CaseExpectation {
  agentId: string; userInput: string; gate: 'decision' | 'final' | null
  maxSteps: number; maxToolCalls: number; maxTokens: number; timeoutSeconds: number
  answer: string | null; knowledgeBaseId: string; documentId: string; chunkId: string
}
interface Manifest { schemaVersion: string; ownerId: string; username: string; password: string; cases: Record<string, ManifestCase> }
interface CreateObservation {
  key: string; agentId: string; userInput: string; status?: number; code?: string; taskId?: string
  bearer: boolean; delivery?: string
}
interface RawFrame { connection: string; index: number; id: string; event: string; data: string }
interface StreamObservation { connection: string; error: string }
interface BrowserProbe {
  frames: RawFrame[]; failures: StreamObservation[]
  abortStream: () => { connection: string; originalSignalAborted: boolean }
}
interface DomSnapshot {
  url: string; status: string | null; answer: string | null; citations: string | null
  timeline: string[]; cursor: string | null; connection: string | null; settled: boolean; syncing: boolean
}
interface Evidence {
  caseId: string; agentId: string; taskId?: string; status: 'RUNNING' | 'PASSED' | 'FAILED'
  stage: string; expectation: CaseExpectation; task?: Task; trace?: TaskTrace; refreshedTask?: Task
  checkpoints: { name: string; at: string; task?: Task; trace?: TaskTrace; dom: DomSnapshot }[]
  creates: CreateObservation[]
  sseRequests: { path: string; cursor: string | null; bearer: boolean; at: string }[]
  observedEvents: { connection: string; index: number; id: string; event: string; data: unknown }[]
  transportFailures: StreamObservation[]; checks: EvidenceCheck[]; gatesSettled: boolean
  observedCalls: Record<string, unknown>[]
  recovery: Record<string, unknown>; controlledBoundary: string; automaticRetries: 0
  error?: string; screenshots: string[]; startedAt: string; finishedAt?: string
}
interface Run {
  page: Page; context: BrowserContext; manifest: Manifest; fixture: ManifestCase; evidence: Evidence
  directory: string; pending: Promise<void>[]; requestRecords: Map<BrowserRequest, CreateObservation>
}

async function exists(file: string): Promise<boolean> { return access(file).then(() => true, () => false) }
async function gate(run: Run, stage: 'decision' | 'final', state: 'entered' | 'release' | 'exited'): Promise<void> {
  const file = path.join(run.directory, `${stage}.${state}`)
  if (state === 'release') await writeFile(file, 'V48 browser allows the controlled call to return\n')
  else await expect.poll(async () => {
    assert.equal(await exists(path.join(run.directory, `${stage}.exit-failed`)), false, `${stage} provider worker failed to exit`)
    return exists(file)
  }, { message: `${run.evidence.caseId}: ${stage}.${state}` }).toBe(true)
}
async function token(page: Page): Promise<string> {
  return page.evaluate(() => JSON.parse(sessionStorage.getItem('agentflow.session.v1')!).token as string)
}
async function publicGet<T>(run: Run, endpoint: string): Promise<T> {
  const response = await run.page.request.get(`/api/v1${endpoint}`, { headers: { Authorization: `Bearer ${await token(run.page)}` } })
  assert.equal(response.status(), 200, `GET ${endpoint}`)
  const body = parseJson(await response.text()) as { code: string; data: T }
  assert.equal(body.code, 'OK')
  return body.data
}
async function recordCreateResponse(run: Run, request: BrowserRequest, response: Response | APIResponse): Promise<void> {
  const record = run.requestRecords.get(request)
  if (!record) return
  record.status = response.status()
  const envelope = parseJson(await response.text()) as { code?: string; data?: { taskId?: string } }
  record.code = envelope.code
  if (envelope.data?.taskId) { record.taskId = envelope.data.taskId; run.evidence.taskId ??= record.taskId }
}

/** A transparent observer of real fetch SSE bytes, plus one explicit transport-abort fault.
 * It never creates a response/event or cancels the runtime's original AbortSignal.
 */
async function installTransportProbe(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const originalFetch = window.fetch.bind(window)
    const pageId = crypto.randomUUID()
    let nextConnection = 0
    let active: { connection: string; abort: AbortController; signal?: AbortSignal | null } | undefined
    const probe = { frames: [] as { connection: string; index: number; id: string; event: string; data: string }[],
      failures: [] as { connection: string; error: string }[],
      abortStream() {
        if (!active || active.abort.signal.aborted) throw new Error('No active real SSE transport')
        const result = { connection: active.connection, originalSignalAborted: active.signal?.aborted ?? false }
        if (result.originalSignalAborted) throw new Error('Runtime signal was already aborted')
        active.abort.abort(new DOMException('V48 injected SSE transport interruption', 'AbortError'))
        return result
      },
    }
    ;(window as unknown as { __v48: typeof probe }).__v48 = probe
    window.fetch = async (input, init) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (!/\/api\/v1\/tasks\/\d+\/events(?:\?|$)/.test(url)) return originalFetch(input, init)
      const connection = `${pageId}/${++nextConnection}`
      const abort = new AbortController()
      const signal = init?.signal ?? (input instanceof Request ? input.signal : undefined)
      const combined = signal ? AbortSignal.any([signal, abort.signal]) : abort.signal
      active = { connection, abort, signal }
      let response: globalThis.Response
      try { response = await originalFetch(input, { ...init, signal: combined }) }
      catch (error) { probe.failures.push({ connection, error: error instanceof Error ? error.name : 'fetch failure' }); throw error }
      if (!response.ok || !response.headers.get('content-type')?.startsWith('text/event-stream')) return response
      const reader = response.clone().body!.getReader()
      void (async () => {
        let buffer = '', index = 0
        const decoder = new TextDecoder()
        try {
          while (true) {
            const chunk = await reader.read()
            if (chunk.done) break
            buffer += decoder.decode(chunk.value, { stream: true })
            const frames = buffer.split(/\r?\n\r?\n/)
            buffer = frames.pop()!
            for (const frame of frames) {
              let id = '', event = ''
              const data: string[] = []
              for (const line of frame.split(/\r?\n/)) {
                if (line.startsWith('id:')) id = line.slice(3).replace(/^ /, '')
                if (line.startsWith('event:')) event = line.slice(6).replace(/^ /, '')
                if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
              }
              if (id || event || data.length) probe.frames.push({ connection, index: ++index, id, event, data: data.join('\n') })
            }
          }
        } catch (error) { probe.failures.push({ connection, error: error instanceof Error ? error.name : 'read failure' }) }
        finally { reader.releaseLock() }
      })()
      return response
    }
  })
}
async function collectTransport(run: Run): Promise<void> {
  const probe = await run.page.evaluate(() => {
    const value = (window as unknown as { __v48?: BrowserProbe }).__v48
    return value ? { frames: value.frames, failures: value.failures } : { frames: [], failures: [] }
  })
  const seen = new Set(run.evidence.observedEvents.map(frame => `${frame.connection}/${frame.index}`))
  for (const frame of probe.frames) {
    if (!seen.has(`${frame.connection}/${frame.index}`)) run.evidence.observedEvents.push({ ...frame, data: frame.data ? parseJson(frame.data) : null })
  }
  for (const failure of probe.failures) {
    if (!run.evidence.transportFailures.some(value => value.connection === failure.connection && value.error === failure.error)) {
      run.evidence.transportFailures.push(failure)
    }
  }
}
async function dom(page: Page): Promise<DomSnapshot> {
  const read = async (id: string) => page.getByTestId(id).count().then(count => count ? page.getByTestId(id).textContent() : null)
  return {
    url: new URL(page.url()).pathname,
    status: await page.getByTestId('task-status').getAttribute('data-status').catch(() => null),
    answer: await read('answer'), citations: await read('citations'),
    timeline: await page.getByTestId('timeline').locator('[data-sequence]').evaluateAll(nodes => nodes.map(node => node.getAttribute('data-sequence')!)),
    cursor: await read('cursor'), connection: await read('connection'),
    settled: await page.getByText('持久化最终答案', { exact: true }).isVisible(),
    syncing: await page.getByText('任务已结束，正在同步最终答案、引用与 Trace。同步成功前，当前输出仍为临时内容。', { exact: true }).isVisible(),
  }
}
async function checkpoint(run: Run, name: string, includeTrace = false): Promise<void> {
  await collectTransport(run)
  const task = run.evidence.taskId ? await publicGet<Task>(run, `/tasks/${run.evidence.taskId}`) : undefined
  const trace = includeTrace && task ? await publicGet<TaskTrace>(run, `/tasks/${task.taskId}/trace`) : undefined
  run.evidence.checkpoints.push({ name, at: new Date().toISOString(), task, trace, dom: await dom(run.page) })
}
async function loginAndPrepare(run: Run): Promise<void> {
  const { page, manifest, fixture } = run
  await page.goto('/login')
  await page.getByLabel('用户名').fill(manifest.username)
  await page.getByLabel('密码', { exact: true }).fill(manifest.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/tasks$/)
  await page.goto(`/agents/${fixture.agentId}/run`)
  await expect(page.getByLabel('Agent', { exact: true })).toHaveValue(fixture.agentId)
  await page.getByLabel('任务输入', { exact: true }).fill(fixture.userInput)
}
async function createWithButton(run: Run): Promise<void> {
  run.evidence.stage = 'browser-create'
  await run.page.getByRole('button', { name: '创建任务 →', exact: true }).click()
  await expect(run.page).toHaveURL(/\/tasks\/\d+$/)
  run.evidence.taskId = run.page.url().split('/').at(-1)!
  await Promise.all(run.pending)
  assert.match(run.evidence.taskId, /^[1-9]\d*$/)
}
async function waitRunningStream(run: Run, stage: 'decision' | 'final'): Promise<string> {
  await gate(run, stage, 'entered')
  await expect(run.page.getByTestId('task-status')).toHaveAttribute('data-status', 'RUNNING')
  await expect(run.page.getByTestId('connection')).toHaveText('已连接')
  const current = await publicGet<Task>(run, `/tasks/${run.evidence.taskId}`)
  await expect.poll(async () => (await dom(run.page)).timeline.at(-1)).toBe(String(current.lastEventSequence))
  const cursor = (await dom(run.page)).timeline.at(-1)!
  assert.ok(BigInt(cursor) > 0n)
  return cursor
}
async function waitTerminal(run: Run): Promise<void> {
  await expect(run.page.getByTestId('task-status')).toHaveAttribute('data-status', run.fixture.status)
  await expect(run.page.getByText('持久化最终答案', { exact: true })).toBeVisible()
  await expect(run.page.getByTestId('connection')).toHaveText('连接已结束')
}
async function backendTerminal(run: Run): Promise<Task> {
  let latest: Task | undefined
  await expect.poll(async () => {
    latest = await publicGet<Task>(run, `/tasks/${run.evidence.taskId}`)
    return latest.status
  }).toBe(run.fixture.status)
  return latest!
}
async function assertRuntime(run: Run, task: Task, trace: TaskTrace): Promise<void> {
  await waitTerminal(run)
  await expect.poll(async () => (await dom(run.page)).timeline).toEqual(trace.events.map(event => String(event.sequenceNo)))
  await expect(run.page.getByTestId('cursor')).toHaveText(`${task.lastEventSequence} / ${task.lastEventSequence}`)
  if (task.status === 'COMPLETED') {
    assert.equal(task.finalAnswer, run.fixture.answer)
    await expect(run.page.getByTestId('answer')).toHaveText(task.finalAnswer!)
    for (const value of task.citations) {
      const citation = value as { documentId: string; chunkId: string }
      await expect(run.page.getByTestId('citations')).toContainText(citation.documentId)
      await expect(run.page.getByTestId('citations')).toContainText(citation.chunkId)
    }
    if (!task.citations.length) await expect(run.page.getByTestId('citations')).toHaveCount(0)
  } else {
    await expect(run.page.getByTestId('answer')).toHaveCount(0)
    await expect(run.page.getByTestId('citations')).toHaveCount(0)
    await expect(run.page.getByText('本次任务没有最终答案。', { exact: true })).toBeVisible()
  }
  if (task.terminationReason !== 'ANSWERED') await expect(run.page.locator('.notice')).toContainText(task.terminationReason!)
  if (task.errorCode) await expect(run.page.locator('.error').filter({ hasText: task.errorCode })).toBeVisible()
}
async function finishAndRefresh(run: Run): Promise<void> {
  run.evidence.stage = 'terminal-convergence'
  await waitTerminal(run)
  const task = await publicGet<Task>(run, `/tasks/${run.evidence.taskId}`)
  const trace = await publicGet<TaskTrace>(run, `/tasks/${run.evidence.taskId}/trace`)
  run.evidence.task = task; run.evidence.trace = trace
  verifyPublicEvidence(run.evidence.caseId, task, trace, run.evidence.checks)
  await assertRuntime(run, task, trace)
  await checkpoint(run, 'before-refresh', true)
  await run.page.reload()
  await waitTerminal(run)
  const refreshed = await publicGet<Task>(run, `/tasks/${task.taskId}`)
  const refreshedTrace = await publicGet<TaskTrace>(run, `/tasks/${task.taskId}/trace`)
  run.evidence.refreshedTask = refreshed
  checked(run.evidence.checks, 'refresh_preserves_durable_task_and_trace', () => {
    assert.deepEqual(refreshed, task)
    assert.deepEqual(refreshedTrace, trace)
  })
  await assertRuntime(run, refreshed, refreshedTrace)
  await checkpoint(run, 'after-refresh', true)
  const screenshot = path.join(run.directory, 'runtime.png')
  await run.page.screenshot({ path: screenshot, fullPage: true })
  run.evidence.screenshots.push(screenshot)
  await run.page.getByRole('link', { name: '查看 Trace ↗' }).click()
  await expect(run.page).toHaveURL(new RegExp(`/tasks/${task.taskId}/trace$`))
  await expect(run.page.locator('.trace-overview [data-status]')).toHaveAttribute('data-status', task.status)
  await run.page.getByRole('tab', { name: `事件 (${trace.events.length})`, exact: true }).click()
  await expect(run.page.getByTestId('timeline').locator('[data-sequence]')).toHaveCount(trace.events.length)
  if (task.finalAnswer) await expect(run.page.getByTestId('answer')).toHaveText(task.finalAnswer)
  else await expect(run.page.getByText('本次任务没有最终答案。', { exact: true })).toBeVisible()
  run.evidence.checks.push({ code: 'browser_runtime_refresh_and_trace_dom', passed: true })
  await collectTransport(run)
}

async function basicCase(run: Run): Promise<void> {
  await createWithButton(run)
  if (run.evidence.caseId === 'B02' || run.evidence.caseId === 'B03') {
    await gate(run, 'final', 'entered')
    await checkpoint(run, 'final-call-in-flight', true)
    if (run.evidence.caseId === 'B03') {
      const responsePromise = run.page.waitForResponse(response => response.request().method() === 'POST'
        && response.url().endsWith(`/tasks/${run.evidence.taskId}/cancel`))
      await run.page.getByRole('button', { name: '取消任务', exact: true }).click()
      const response = await responsePromise
      assert.equal(response.status(), 200)
      const body = parseJson(await response.text()) as { data: Task }
      assert.ok(body.data.cancelRequestedAt || body.data.status === 'CANCELLED')
      run.evidence.recovery.cancelResponse = { status: response.status(), task: body.data }
    }
    await waitTerminal(run)
    await checkpoint(run, 'terminal-before-late-return', true)
    await gate(run, 'final', 'release')
    await gate(run, 'final', 'exited')
    await checkpoint(run, 'after-late-return', true)
    const first = run.evidence.checkpoints.find(value => value.name === 'terminal-before-late-return')!
    const last = run.evidence.checkpoints.at(-1)!
    checked(run.evidence.checks, 'late_final_return_cannot_publish_or_add_events', () => {
      assert.deepEqual(last.task, first.task)
      assert.deepEqual(last.trace, first.trace)
    })
  }
}

async function browserPosts(run: Run, key: string, inputs: string[]): Promise<{ status: number; body: { code: string; data?: Task } }[]> {
  return run.page.evaluate(async ({ agentId, key, inputs }) => {
    const session = JSON.parse(sessionStorage.getItem('agentflow.session.v1')!)
    return Promise.all(inputs.map(async userInput => {
      const response = await fetch(`/api/v1/agents/${agentId}/tasks`, { method: 'POST',
        headers: { Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json', 'Idempotency-Key': key },
        body: JSON.stringify({ userInput }) })
      return { status: response.status, body: await response.json() }
    }))
  }, { agentId: run.fixture.agentId, key, inputs })
}
async function repeatedPostCase(run: Run): Promise<void> {
  const key = `v48-${randomUUID()}`
  const results = await browserPosts(run, key, [run.fixture.userInput, run.fixture.userInput, run.fixture.userInput])
  checked(run.evidence.checks, 'concurrent_same_key_returns_one_created_task', () => {
    assert.deepEqual(results.map(result => result.status).sort(), [200, 200, 201])
    assert.equal(new Set(results.map(result => result.body.data?.taskId)).size, 1)
  })
  run.evidence.taskId = results[0]!.body.data!.taskId
  run.evidence.recovery.concurrentResults = results
  await run.page.goto(`/tasks/${run.evidence.taskId}`)
  await waitRunningStream(run, 'decision')
  const conflict = (await browserPosts(run, key, [`${run.fixture.userInput} changed`]))[0]!
  checked(run.evidence.checks, 'same_key_different_raw_input_conflicts', () => {
    assert.equal(conflict.status, 409); assert.equal(conflict.body.code, 'TASK_IDEMPOTENCY_CONFLICT')
  })
  await gate(run, 'decision', 'release'); await gate(run, 'decision', 'exited')
  await waitTerminal(run)
  const before = await publicGet<TaskTrace>(run, `/tasks/${run.evidence.taskId}/trace`)
  const repeated = (await browserPosts(run, key, [run.fixture.userInput]))[0]!
  const after = await publicGet<TaskTrace>(run, `/tasks/${run.evidence.taskId}/trace`)
  checked(run.evidence.checks, 'failed_task_repeated_post_never_reexecutes', () => {
    assert.equal(repeated.status, 200); assert.equal(repeated.body.data!.taskId, run.evidence.taskId)
    assert.deepEqual(after, before)
  })
  run.evidence.recovery.terminalRepeatedPost = repeated
  run.evidence.recovery.conflict = conflict
}
async function lostCreateResponseCase(run: Run): Promise<void> {
  const endpoint = `**/api/v1/agents/${run.fixture.agentId}/tasks`
  let dropped = false
  await run.page.route(endpoint, async route => {
    const response = await route.fetch()
    await recordCreateResponse(run, route.request(), response)
    const record = run.requestRecords.get(route.request())!
    if (!dropped) {
      assert.equal(response.status(), 201)
      dropped = true; record.delivery = 'committed-response-dropped'
      await route.abort('connectionfailed')
    } else { record.delivery = 'real-reused-response-delivered'; await route.fulfill({ response }) }
  })
  await run.page.getByRole('button', { name: '创建任务 →', exact: true }).click()
  await expect(run.page.getByRole('button', { name: '确认原提交结果', exact: true })).toBeEnabled()
  const pending = await run.page.evaluate(() => JSON.parse(sessionStorage.getItem('agentflow.pending-task.v1')!))
  assert.equal(pending.userInput, run.fixture.userInput)
  assert.equal(pending.ownerId, run.manifest.ownerId)
  await run.page.reload()
  await expect(run.page.getByRole('button', { name: '确认原提交结果', exact: true })).toBeEnabled()
  const restored = await run.page.evaluate(() => JSON.parse(sessionStorage.getItem('agentflow.pending-task.v1')!))
  assert.deepEqual(restored, pending)
  assert.equal(run.evidence.creates.length, 1, 'Reload must not resubmit an uncertain POST')
  await run.page.getByRole('button', { name: '确认原提交结果', exact: true }).click()
  await expect(run.page).toHaveURL(new RegExp(`/tasks/${run.evidence.taskId}$`))
  await waitRunningStream(run, 'decision')
  await Promise.all(run.pending)
  checked(run.evidence.checks, 'lost_committed_response_reuses_restored_request', () => {
    assert.equal(run.evidence.creates.length, 2)
    const [first, second] = run.evidence.creates
    assert.equal(first!.status, 201); assert.equal(second!.status, 200)
    assert.equal(first!.key, second!.key); assert.equal(first!.userInput, second!.userInput)
    assert.equal(first!.taskId, second!.taskId)
  })
  run.evidence.recovery.restoredPendingRequest = restored
  await gate(run, 'decision', 'release'); await gate(run, 'decision', 'exited')
}

async function sseInterruptionCase(run: Run): Promise<void> {
  await createWithButton(run)
  const cursor = await waitRunningStream(run, 'decision')
  await checkpoint(run, 'before-sse-interruption', true)
  const originalConnections = run.evidence.sseRequests.length
  let releaseConnection!: () => void
  const allowConnection = new Promise<void>(resolve => { releaseConnection = resolve })
  await run.page.route(`**/api/v1/tasks/${run.evidence.taskId}/events`, async route => { await allowConnection; await route.continue() })
  try {
    const interruption = await run.page.evaluate(() => (window as unknown as { __v48: BrowserProbe }).__v48.abortStream())
    assert.equal(interruption.originalSignalAborted, false)
    assert.equal(await run.page.evaluate(() => navigator.onLine), true)
    await expect(run.page.getByTestId('connection')).not.toHaveText('已连接')
    await gate(run, 'decision', 'release'); await gate(run, 'decision', 'exited')
    const terminalWhileDisconnected = await backendTerminal(run)
    await expect.poll(() => run.evidence.sseRequests.length).toBeGreaterThan(originalConnections)
    const reconnect = run.evidence.sseRequests.at(-1)!
    assert.equal(reconnect.cursor, cursor)
    await collectTransport(run)
    assert.ok(run.evidence.transportFailures.some(failure => failure.connection === interruption.connection), 'Real fetch stream must report interruption')
    run.evidence.recovery = { beforeCursor: cursor, originalConnections, interruption, terminalWhileDisconnected }
    releaseConnection()
    await waitTerminal(run)
    await expect.poll(async () => {
      await collectTransport(run)
      return run.evidence.observedEvents.some(frame => frame.connection !== interruption.connection
        && frame.event === 'TASK_FAILED' && BigInt(frame.id) > BigInt(cursor))
    }).toBe(true)
    checked(run.evidence.checks, 'real_sse_abort_replays_persisted_terminal_after_processed_cursor', () => {
      const replay = run.evidence.observedEvents.filter(frame => frame.connection !== interruption.connection && frame.id && BigInt(frame.id) > BigInt(cursor))
      assert.ok(replay.length > 0)
      assert.equal(replay[0]!.id, String(BigInt(cursor) + 1n))
      assert.equal(replay.at(-1)!.event, 'TASK_FAILED')
    })
  } finally { releaseConnection() }
}
async function offlineCase(run: Run): Promise<void> {
  await createWithButton(run)
  const stage = run.fixture.gate!
  const cursor = await waitRunningStream(run, stage)
  await checkpoint(run, 'before-offline', true)
  await run.context.setOffline(true)
  try {
    assert.equal(await run.page.evaluate(() => navigator.onLine), false)
    await expect(run.page.getByTestId('connection')).not.toHaveText('已连接')
    await expect(run.page.getByTestId('task-status')).toHaveAttribute('data-status', 'RUNNING')
    await gate(run, stage, 'release'); await gate(run, stage, 'exited')
    const terminalWhileOffline = await backendTerminal(run)
    run.evidence.recovery = { beforeCursor: cursor, terminalWhileOffline, domWhileOffline: await dom(run.page) }
    assert.equal(terminalWhileOffline.cancelRequestedAt ?? null, null)
    assert.equal(run.evidence.creates.length, 1)
  } finally { await run.context.setOffline(false) }
  await waitTerminal(run)
  assert.equal(run.evidence.creates.length, 1, 'Online must not create or retry task execution')
  run.evidence.checks.push({ code: 'offline_server_terminal_recovers_without_cancel_or_post', passed: true })
}
async function refreshAndUnsyncedCase(run: Run): Promise<void> {
  await createWithButton(run)
  const cursor = await waitRunningStream(run, 'final')
  await checkpoint(run, 'running-before-refresh', true)
  await run.page.reload()
  await waitRunningStream(run, 'final')
  await expect.poll(async () => (await dom(run.page)).timeline.at(-1)).toBe(cursor)
  await checkpoint(run, 'running-after-refresh', true)
  let rejectTaskGet = true
  let rejectedReads = 0
  const endpoint = `**/api/v1/tasks/${run.evidence.taskId}`
  await run.page.route(endpoint, async route => {
    if (route.request().method() === 'GET' && rejectTaskGet) { rejectedReads++; await route.abort('connectionfailed') }
    else await route.continue()
  })
  await gate(run, 'final', 'release'); await gate(run, 'final', 'exited')
  await expect(run.page.getByTestId('task-status')).toHaveAttribute('data-status', 'COMPLETED')
  await expect(run.page.getByText('任务已结束，正在同步最终答案、引用与 Trace。同步成功前，当前输出仍为临时内容。', { exact: true })).toBeVisible()
  await expect(run.page.getByText('持久化最终答案', { exact: true })).toHaveCount(0)
  assert.ok(rejectedReads > 0)
  await checkpoint(run, 'terminal-get-unavailable', true)
  rejectTaskGet = false
  await run.page.getByRole('button', { name: '刷新并恢复', exact: true }).click()
  await waitTerminal(run)
  run.evidence.recovery = { runningCursor: cursor, rejectedTerminalReads: rejectedReads }
  run.evidence.checks.push({ code: 'running_refresh_and_unsynced_terminal_manual_recovery', passed: true })
}

for (const caseId of Object.keys(EXPECTED).filter(id => !selectedCase || selectedCase === id)) {
  test(`${caseId}: real execution, browser observation and persisted recovery`, async ({ page, context }) => {
    const directory = path.join(control, 'cases', caseId)
    await mkdir(directory, { recursive: true })
    const evidence: Evidence = { caseId, agentId: '', status: 'RUNNING', stage: 'fixture', expectation: EXPECTED[caseId]!,
      checkpoints: [], creates: [], sseRequests: [], observedEvents: [], transportFailures: [], observedCalls: [], checks: [], gatesSettled: false,
      recovery: {}, controlledBoundary: 'Real browser/JWT/PostgreSQL/Runner/RAG/ToolRuntime/recorder/SSE; test-source model/embedding/vector/handler faults and explicit browser transport faults',
      automaticRetries: 0, screenshots: [], startedAt: new Date().toISOString() }
    let run: Run | undefined
    try {
      const manifest = JSON.parse(await readFile(path.join(control, 'manifest.json'), 'utf8')) as Manifest
      const fixture = manifest.cases[caseId]!
      assert.ok(fixture, `Manifest is missing ${caseId}`)
      for (const [key, value] of Object.entries(EXPECTED[caseId]!)) assert.equal(fixture[key as keyof CaseExpectation], value, `Manifest expectation ${caseId}.${key}`)
      assert.ok(fixture.userInput.startsWith(`V48:${caseId}:`))
      evidence.agentId = fixture.agentId
      run = { page, context, manifest, fixture, evidence, directory, pending: [], requestRecords: new Map() }
      const active = run
      page.on('request', request => {
        const url = new URL(request.url())
        if (/\/api\/v1\/tasks\/\d+\/events$/.test(url.pathname)) {
          evidence.sseRequests.push({ path: `${url.pathname}${url.search}`, cursor: request.headers()['last-event-id'] ?? null,
            bearer: /^Bearer /.test(request.headers().authorization ?? ''), at: new Date().toISOString() })
        }
        const match = url.pathname.match(/\/api\/v1\/agents\/(\d+)\/tasks$/)
        if (match && request.method() === 'POST') {
          const record: CreateObservation = { key: request.headers()['idempotency-key'] ?? '', agentId: match[1]!,
            userInput: (request.postDataJSON() as { userInput: string }).userInput, bearer: /^Bearer /.test(request.headers().authorization ?? '') }
          evidence.creates.push(record); active.requestRecords.set(request, record)
        }
      })
      page.on('response', response => {
        if (active.requestRecords.has(response.request())) {
          active.pending.push(recordCreateResponse(active, response.request(), response))
        }
      })
      await installTransportProbe(page)
      evidence.stage = 'login'
      await loginAndPrepare(run)
      evidence.stage = 'scenario'
      if (caseId === 'R01') await repeatedPostCase(run)
      else if (caseId === 'R02') await lostCreateResponseCase(run)
      else if (caseId === 'R03') await sseInterruptionCase(run)
      else if (caseId.startsWith('R04')) await offlineCase(run)
      else if (caseId === 'R05') await refreshAndUnsyncedCase(run)
      else await basicCase(run)
      if (fixture.gate) await gate(run, fixture.gate, 'exited')
      evidence.gatesSettled = true
      await finishAndRefresh(run)
      await Promise.all(run.pending)
      evidence.observedCalls = (await readFile(path.join(directory, 'calls.jsonl'), 'utf8')).split('\n').filter(Boolean)
        .map(line => JSON.parse(line) as Record<string, unknown>)
      checked(evidence.checks, 'independent_fixture_entry_counts', () => {
        const entries = evidence.observedCalls.filter(call => call.stage === 'ENTER')
        for (const [kind, expected] of [['DECISION', fixture.decisions], ['FINAL_GENERATION', fixture.finals], ['TOOL_HANDLER', fixture.handlers]] as const) {
          assert.equal(entries.filter(call => call.kind === kind).length, expected, `Actual ${kind} entries`)
        }
        assert.ok(evidence.observedCalls.every(call => call.caseId === caseId && String(call.taskId) === evidence.taskId))
      })
      checked(evidence.checks, 'authenticated_transport_and_no_unplanned_creates', () => {
        assert.ok(evidence.creates.every(value => value.bearer && value.key))
        assert.ok(evidence.sseRequests.every(value => value.bearer && value.cursor !== null && !/[?&](token|accessToken)=/.test(value.path)))
        assert.equal(evidence.creates.length, caseId === 'R01' ? 5 : caseId === 'R02' ? 2 : 1)
        assert.ok(evidence.creates.filter(value => value.status !== 409).every(value => value.taskId === evidence.taskId))
      })
      evidence.status = 'PASSED'; evidence.stage = 'complete'
    } catch (error) {
      evidence.status = 'FAILED'
      evidence.error = (error instanceof Error ? error.message : String(error)).slice(0, 6000)
      if (run?.evidence.taskId) {
        await context.setOffline(false).catch(() => {})
        await collectTransport(run).catch(() => {})
        evidence.task = await publicGet<Task>(run, `/tasks/${evidence.taskId}`).catch(() => evidence.task)
        evidence.trace = await publicGet<TaskTrace>(run, `/tasks/${evidence.taskId}/trace`).catch(() => evidence.trace)
        if (/\/tasks\/\d+/.test(page.url())) {
          const screenshot = path.join(directory, 'failure.png')
          await page.screenshot({ path: screenshot, fullPage: true, timeout: 3000 }).then(() => evidence.screenshots.push(screenshot), () => {})
        }
      }
      throw error
    } finally {
      // Release only this case's test-source gates, including after a failed assertion.
      for (const stage of ['decision', 'final'] as const) await writeFile(path.join(directory, `${stage}.release`), 'case cleanup\n')
      if (run) await Promise.allSettled(run.pending)
      evidence.finishedAt = new Date().toISOString()
      await writeFile(path.join(directory, 'browser-evidence.json'), `${JSON.stringify(evidence, null, 2)}\n`)
    }
  })
}
