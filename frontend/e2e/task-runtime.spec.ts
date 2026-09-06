import { test, expect, type Page } from '@playwright/test'
import { access, readFile, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'

const AGENT = '430000000000000003'
const DOCUMENT = '430000000000000006'
const CHUNK = '430000000000000007'
const ANSWER = '订单 order_1024 支付超时，请核对订单及支付日志。[S1]\n'
  + 'This answer uses the persisted knowledge citation and recorded order_query result.'
const control = process.env.V43_CONTROL_DIR
if (!control) throw new Error('Set V43_CONTROL_DIR to the running disposable backend fixture directory')
const gate = path.join(control, 'release-model')

async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('用户名').fill('v43-browser')
  await page.getByLabel('密码', { exact: true }).fill('V43-browser-test!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/tasks$/)
  await expect(page.getByLabel('Agent', { exact: true })).toHaveValue(AGENT)
}

async function publicGet(page: Page, endpoint: string) {
  const token = await page.evaluate(() => JSON.parse(sessionStorage.getItem('agentflow.session.v1')!).token as string)
  const response = await page.request.get(`/api/v1${endpoint}`, { headers: { Authorization: `Bearer ${token}` } })
  expect(response.status()).toBe(200)
  return (await response.json()).data
}

async function sequences(page: Page): Promise<string[]> {
  return page.getByTestId('timeline').locator('[data-sequence]').evaluateAll(nodes => nodes.map(node => node.getAttribute('data-sequence')!))
}

test.beforeAll(async () => { await access(path.join(control!, 'backend-ready')) })
test.beforeEach(async () => { await rm(gate, { force: true }); await rm(path.join(control!, 'model-waiting'), { force: true }) })
test.afterEach(async () => { await writeFile(gate, 'release any task left by a failed browser assertion') })

test('real login + uncertain creation + persisted replay + offline/reconnect + refresh converge to GET/Trace', async ({ page, context }, testInfo) => {
  const sseRequests: { authorization: string | undefined; cursor: string | undefined; url: string }[] = []
  page.on('request', request => {
    if (/\/tasks\/\d+\/events(?:\?|$)/.test(request.url())) {
      const headers = request.headers()
      sseRequests.push({ authorization: headers.authorization, cursor: headers['last-event-id'], url: request.url() })
    }
  })
  await login(page)
  await page.goto(`/agents/${AGENT}/run`)
  await expect(page.getByLabel('Agent', { exact: true })).toHaveValue(AGENT)

  // The real server commits the first create. Only its delivery to the browser is lost.
  const creates: { key: string | undefined; body: string | null }[] = []
  let committedTaskId = ''
  await page.route(`**/api/v1/agents/${AGENT}/tasks`, async route => {
    creates.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() })
    const response = await route.fetch()
    const body = await response.json()
    if (!committedTaskId) {
      expect(response.status()).toBe(201)
      committedTaskId = body.data.taskId
      await route.abort('connectionfailed')
    } else {
      expect(response.status()).toBe(200)
      expect(body.data.taskId).toBe(committedTaskId)
      await route.fulfill({ response })
    }
  })
  const input = `V43 browser recovery ${Date.now()}: Why did order_1024 payment fail?`
  await page.getByLabel('任务输入', { exact: true }).fill(input)
  await page.getByRole('button', { name: '创建任务 →', exact: true }).click()
  await expect(page.getByRole('button', { name: '确认原提交结果', exact: true })).toBeEnabled()
  await page.reload()
  await page.getByRole('button', { name: '确认原提交结果', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/tasks/${committedTaskId}$`))
  expect(creates).toHaveLength(2)
  expect(creates[0]!.key).toBeTruthy()
  expect(creates[1]).toEqual(creates[0])
  expect(BigInt(committedTaskId)).toBeGreaterThan(BigInt(Number.MAX_SAFE_INTEGER))
  await expect(page.getByTestId('task-status')).toHaveAttribute('data-status', 'RUNNING')
  await expect(page.getByTestId('timeline')).toContainText('order_query')
  await expect.poll(async () => (await sequences(page)).length).toBeGreaterThan(8)
  await expect.poll(async () => access(path.join(control!, 'model-waiting')).then(() => true, () => false)).toBe(true)

  const beforeOffline = await sequences(page)
  const initialConnections = sseRequests.length
  await context.setOffline(true)
  expect(await page.evaluate(() => navigator.onLine)).toBe(false)
  await expect(page.getByTestId('connection')).not.toHaveText('已连接')
  // Browser connectivity changes never change the persisted server task state to FAILED.
  await expect(page.getByTestId('task-status')).toHaveAttribute('data-status', 'RUNNING')
  await context.setOffline(false)
  await expect.poll(() => sseRequests.length).toBeGreaterThan(initialConnections)
  await expect(page.getByTestId('connection')).toHaveText('已连接')
  expect(await sequences(page)).toEqual(beforeOffline)
  expect(sseRequests.some(request => request.cursor === beforeOffline.at(-1))).toBe(true)
  expect(sseRequests.every(request => request.authorization?.startsWith('Bearer '))).toBe(true)
  expect(sseRequests.every(request => !/[?&](token|accessToken)=/.test(request.url))).toBe(true)

  await page.reload()
  await expect(page.getByTestId('task-status')).toHaveAttribute('data-status', 'RUNNING')
  await expect.poll(() => sequences(page)).toEqual(beforeOffline)
  await expect(page.getByTestId('connection')).toHaveText('已连接')
  // Leaving a still-running task must release the server emitter, without cancelling execution.
  await expect.poll(async () => (await readFile(path.join(control!, 'sse-active'), 'utf8')).trim()).toBe('1')
  await page.getByRole('link', { name: '查看 Trace ↗' }).click()
  await expect(page).toHaveURL(new RegExp(`/tasks/${committedTaskId}/trace$`))
  await expect.poll(async () => (await readFile(path.join(control!, 'sse-active'), 'utf8')).trim()).toBe('0')
  expect((await publicGet(page, `/tasks/${committedTaskId}`)).status).toBe('RUNNING')
  await page.getByRole('link', { name: '← 返回任务', exact: true }).click()
  await expect.poll(() => sequences(page)).toEqual(beforeOffline)
  await expect(page.getByTestId('connection')).toHaveText('已连接')
  await writeFile(gate, 'allow controlled final decision and final answer')
  await expect(page.getByTestId('task-status')).toHaveAttribute('data-status', 'COMPLETED')
  await expect(page.getByTestId('answer')).toHaveText(ANSWER)
  await expect(page.getByTestId('citations')).toContainText(DOCUMENT)
  await expect(page.getByTestId('citations')).toContainText(CHUNK)

  const detail = await publicGet(page, `/tasks/${committedTaskId}`)
  const trace = await publicGet(page, `/tasks/${committedTaskId}/trace`)
  expect(detail.status).toBe('COMPLETED')
  expect(detail.finalAnswer).toBe(ANSWER)
  expect(trace.task.finalAnswer).toBe(detail.finalAnswer)
  expect(trace.task.citations).toEqual(detail.citations)
  expect(detail.citations).toEqual([{ citationId: 'S1', documentId: DOCUMENT, chunkId: CHUNK, vectorGeneration: 1 }])
  expect(trace.steps.flatMap((step: any) => step.ragRetrievals)).toHaveLength(1)
  expect(trace.steps.flatMap((step: any) => step.toolCalls)).toHaveLength(1)
  expect(trace.steps.flatMap((step: any) => step.llmCalls)).toHaveLength(3)
  const finalSequences = await sequences(page)
  expect(finalSequences).toEqual(trace.events.map((event: any) => String(event.sequenceNo)))
  expect(new Set(finalSequences).size).toBe(finalSequences.length)
  expect(finalSequences).toEqual(finalSequences.map((_, i) => String(i + 1)))
  expect(trace.events.at(-1).eventType).toBe('TASK_COMPLETED')
  await page.screenshot({ path: testInfo.outputPath('completed-runtime.png'), fullPage: true })

  await page.getByRole('link', { name: '查看 Trace ↗' }).click()
  await expect(page).toHaveURL(new RegExp(`/tasks/${committedTaskId}/trace$`))
  await expect.poll(async () => (await readFile(path.join(control!, 'sse-active'), 'utf8')).trim()).toBe('0')
  await expect(page.getByTestId('answer')).toHaveText(ANSWER)
  await expect(page.getByTestId('timeline').locator('[data-sequence]')).toHaveCount(trace.events.length)
  await expect(page.locator('body')).toContainText('order_query')
  await page.screenshot({ path: testInfo.outputPath('readonly-trace.png'), fullPage: true })
  const evidenceFile = testInfo.outputPath('real-backend-evidence.json')
  await writeFile(evidenceFile, JSON.stringify({
    taskId: committedTaskId, agentId: AGENT, createdRequests: creates.length,
    sseConnections: sseRequests.length, recoveredCursor: beforeOffline.at(-1),
    events: trace.events.length, steps: trace.steps.length, finalAnswer: detail.finalAnswer,
    citations: detail.citations, providerBoundary: 'controlled model and vector; real backend execution and PostgreSQL',
  }, null, 2))
  await testInfo.attach('real-backend-evidence', { path: evidenceFile, contentType: 'application/json' })
})

test('cancel a running real task, then logout clears browser task and authentication state', async ({ page }) => {
  await login(page)
  await page.getByLabel('任务输入', { exact: true }).fill(`V43 cancellation ${Date.now()}: order_1024`)
  await page.getByRole('button', { name: '创建任务 →', exact: true }).click()
  await expect(page).toHaveURL(/\/tasks\/\d+$/)
  await expect(page.getByTestId('task-status')).toHaveAttribute('data-status', 'RUNNING')
  await expect(page.getByTestId('timeline')).toContainText('order_query')
  const taskId = page.url().split('/').at(-1)!
  await page.getByRole('button', { name: '取消任务', exact: true }).click()
  await expect(page.getByTestId('task-status')).toHaveAttribute('data-status', 'CANCELLED')
  expect((await publicGet(page, `/tasks/${taskId}`)).status).toBe('CANCELLED')
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect(page).toHaveURL(/\/login/)
  expect(await page.evaluate(() => sessionStorage.getItem('agentflow.session.v1'))).toBeNull()
  expect(await page.evaluate(() => sessionStorage.getItem('agentflow.pending-task.v1'))).toBeNull()
  await page.goto(`/tasks/${taskId}`)
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByTestId('timeline')).toHaveCount(0)
})

test('real backend JWT rejection clears restored session and pending request', async ({ page }) => {
  await login(page)
  await page.evaluate(() => {
    const session = JSON.parse(sessionStorage.getItem('agentflow.session.v1')!)
    session.token += '-invalid-signature'
    sessionStorage.setItem('agentflow.session.v1', JSON.stringify(session))
    sessionStorage.setItem('agentflow.pending-task.v1', JSON.stringify({
      ownerId: session.user.id, agentId: '430000000000000003', userInput: 'pending auth cleanup', key: 'v43-expired-session',
    }))
  })
  await page.reload()
  await expect(page).toHaveURL(/\/login/)
  expect(await page.evaluate(() => sessionStorage.getItem('agentflow.session.v1'))).toBeNull()
  expect(await page.evaluate(() => sessionStorage.getItem('agentflow.pending-task.v1'))).toBeNull()
})
