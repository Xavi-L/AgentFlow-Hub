import { test, expect, type Page } from '@playwright/test'
import { readFile, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { parseJson } from '../src/lib/sequence'

test.skip(!process.env.V46_BROWSER, 'Requires the V46 disposable PostgreSQL and controlled provider fixture')
const control = process.env.V43_CONTROL_DIR!
const MODEL = 'v43-controlled-model'
const UNKNOWN_MODEL = 'unverified-controlled-model'
const PAYMENT_KB = '430000000000000005'
const ORDER_TOOL = '270000000000000001'
const inherited = { decisionMaxOutputTokens: null, finalMaxOutputTokens: null,
  decisionResponseFormat: null, thinkingMode: null, modelCallTimeoutSeconds: null }
const custom = { decisionMaxOutputTokens: 2048, finalMaxOutputTokens: 4096,
  decisionResponseFormat: 'JSON_SCHEMA', thinkingMode: 'DISABLED', modelCallTimeoutSeconds: 120 }
let token = ''
let created: string[] = []

async function login(page: Page) {
  await page.goto('/agents')
  await expect(page).toHaveURL(/\/login/)
  await page.getByLabel('用户名').fill('v43-browser')
  await page.getByLabel('密码', { exact: true }).fill('V43-browser-test!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(url => url.pathname === '/agents')
  token = await page.evaluate(() => JSON.parse(sessionStorage.getItem('agentflow.session.v1')!).token as string)
}
async function get(page: Page, endpoint: string) {
  const response = await page.request.get(`/api/v1${endpoint}`, { headers: { Authorization: `Bearer ${token}` } })
  expect(response.status()).toBe(200)
  return (parseJson(await response.text()) as any).data
}
async function openAdvanced(page: Page) {
  const section = page.getByTestId('agent-advanced')
  if (await section.getAttribute('open') === null) await section.locator('summary').click()
  return section
}
async function fillBase(page: Page, name: string) {
  await page.locator('#agent-name').fill(name)
  await page.locator('#agent-prompt').fill('Explain recorded payment facts')
  await page.locator('#agent-maxTokens').fill('50000')
  await page.locator('#agent-timeoutSeconds').fill('300')
  await page.locator('#agent-model').fill(MODEL)
  await openAdvanced(page)
  await expect(page.getByTestId('agent-execution-effective')).toBeVisible()
}
async function fillAdvanced(page: Page, values: typeof inherited | typeof custom) {
  await openAdvanced(page)
  await expect(page.locator('#hint-decision-output')).toContainText('平台上限')
  for (const key of ['decisionMaxOutputTokens', 'finalMaxOutputTokens', 'modelCallTimeoutSeconds'] as const) {
    await page.locator(`#agent-${key}`).fill(values[key] === null ? '' : String(values[key]))
  }
  await page.locator('#agent-decisionResponseFormat').selectOption(values.decisionResponseFormat ?? '')
  await page.locator('#agent-thinkingMode').selectOption(values.thinkingMode ?? '')
}
async function create(page: Page, name: string, values = inherited as typeof inherited | typeof custom) {
  await fillBase(page, name)
  await fillAdvanced(page, values)
  await page.getByRole('button', { name: '创建 Agent →', exact: true }).click()
  await expect(page).toHaveURL(/\/agents\/\d+$/)
  const id = page.url().split('/').at(-1)!
  created.push(id)
  await expect(page.locator('#agent-name')).toHaveValue(name)
  return id
}

test.beforeEach(async () => {
  token = ''; created = []
  await Promise.all(['release-model', 'model-waiting', 'advanced-llm-requests']
    .map(file => rm(path.join(control, file), { force: true })))
})
test.afterEach(async ({ page }) => {
  await writeFile(path.join(control, 'release-model'), 'release the controlled model after assertions')
  for (const id of created) {
    const response = await page.request.post(`/api/v1/agents/${id}/disable`, { headers: { Authorization: `Bearer ${token}` } })
    expect(response.status()).toBe(200)
  }
})

test('advanced settings validate platform and reserve limits, persist overrides, and clear them after an uncertain PATCH', async ({ page }, info) => {
  await login(page)
  await fillBase(page, `Advanced inheritance ${Date.now()}`)
  const options = await get(page, `/agents/execution-options?modelName=${MODEL}`)
  expect(options.policyVersion).toBe('agent-execution-policy-v1')
  expect(options.capabilities).toEqual({ jsonObject: true, jsonSchema: true, disableThinking: true })
  let creates = 0
  page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/api/v1/agents')) creates++ })
  await page.locator('#agent-decisionMaxOutputTokens').fill(String(options.limits.maxOutputTokens + 1))
  await page.getByRole('button', { name: '创建 Agent →', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: `单次输出上限不能超过平台允许的 ${options.limits.maxOutputTokens} tokens` }).first()).toBeVisible()
  expect(creates).toBe(0)
  await page.locator('#agent-decisionMaxOutputTokens').fill('')
  await page.locator('#agent-finalMaxOutputTokens').fill('2047')
  await page.getByRole('button', { name: '创建 Agent →', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: '最终回答输出上限不能低于最终回答预留预算 2048 tokens' }).first()).toBeVisible()
  expect(creates).toBe(0)
  await page.locator('#agent-finalMaxOutputTokens').fill('')
  await page.getByRole('button', { name: '创建 Agent →', exact: true }).click()
  await expect(page).toHaveURL(/\/agents\/\d+$/)
  const id = page.url().split('/').at(-1)!
  created.push(id)
  expect(creates).toBe(1)
  expect(await get(page, `/agents/${id}`)).toMatchObject(inherited)
  await openAdvanced(page)
  await expect(page.getByTestId('agent-execution-effective')).toContainText(String(options.defaults.decisionMaxOutputTokens))
  await fillAdvanced(page, custom)
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await expect.poll(async () => (await get(page, `/agents/${id}`)).decisionMaxOutputTokens).toBe(2048)
  expect(await get(page, `/agents/${id}`)).toMatchObject(custom)
  await page.reload()
  await openAdvanced(page)
  await expect(page.locator('#agent-finalMaxOutputTokens')).toHaveValue('4096')
  await expect(page.locator('#agent-thinkingMode')).toHaveValue('DISABLED')
  await fillAdvanced(page, inherited)
  let patches = 0
  await page.route(`**/api/v1/agents/${id}`, async route => {
    if (route.request().method() !== 'PATCH') return route.continue()
    patches++
    expect(route.request().postDataJSON()).toMatchObject(inherited)
    const response = await route.fetch()
    expect(response.status()).toBe(200)
    await route.abort('connectionfailed')
  })
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await expect(page.getByTestId('unknown-config')).toBeVisible()
  await page.reload()
  await expect(page.getByTestId('unknown-config')).toBeVisible()
  await page.getByTestId('unknown-config').getByRole('button', { name: '读取服务端核对', exact: true }).click()
  await expect(page.getByTestId('unknown-config')).not.toBeVisible()
  expect(patches).toBe(1)
  expect(await get(page, `/agents/${id}`)).toMatchObject(inherited)
  await openAdvanced(page)
  await expect(page.locator('#agent-modelCallTimeoutSeconds')).toHaveValue('')
  await expect(page.getByTestId('agent-execution-effective')).toContainText('继承部署默认')
  await page.screenshot({ path: info.outputPath('advanced-inheritance-restored.png'), fullPage: true })
})

test('model capability switching rejects incompatible drafts and ignores a late response for the previous model', async ({ page }) => {
  await login(page)
  await fillBase(page, `Advanced capability isolation ${Date.now()}`)
  await fillAdvanced(page, custom)
  await page.locator('#agent-model').fill(UNKNOWN_MODEL)
  await expect(page.locator('#agent-decisionResponseFormat option[value="JSON_SCHEMA"]')).toBeDisabled()
  await expect(page.locator('#agent-thinkingMode option[value="DISABLED"]')).toBeDisabled()
  const unsupported = await get(page, `/agents/execution-options?modelName=${UNKNOWN_MODEL}`)
  expect(unsupported.capabilities).toEqual({ jsonObject: false, jsonSchema: false, disableThinking: false })
  let release!: () => void, fetched!: () => void, delivered!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const captured = new Promise<void>(resolve => { fetched = resolve })
  const delivery = new Promise<void>(resolve => { delivered = resolve })
  await page.route(`**/api/v1/agents/execution-options?modelName=${MODEL}`, async route => {
    const response = await route.fetch()
    fetched(); await gate
    await route.fulfill({ response }).catch(() => {})
    delivered()
  })
  try {
    await page.locator('#agent-model').fill(MODEL)
    await captured
    await page.locator('#agent-model').fill(UNKNOWN_MODEL)
    await expect(page.getByRole('alert').filter({ hasText: '当前模型未开放 JSON Schema' }).first()).toBeVisible()
    release(); await delivery
    await expect(page.locator('#agent-model')).toHaveValue(UNKNOWN_MODEL)
    await expect(page.locator('#agent-decisionResponseFormat option[value="JSON_SCHEMA"]')).toBeDisabled()
    await expect(page.locator('#agent-thinkingMode option[value="DISABLED"]')).toBeDisabled()
    await expect(page.locator('#agent-decisionResponseFormat')).toHaveValue('JSON_SCHEMA')
    let creates = 0
    page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/api/v1/agents')) creates++ })
    await page.getByRole('button', { name: '创建 Agent →', exact: true }).click()
    expect(creates).toBe(0)
    // A direct request must meet the same policy, independently of UI disabling.
    const rejection = await page.request.post('/api/v1/agents', {
      headers: { Authorization: `Bearer ${token}` },
      data: { name: 'Rejected unsupported model override', systemPrompt: 'Explain recorded payment facts',
        modelProvider: 'openai-compatible', modelName: UNKNOWN_MODEL, ...custom },
    })
    expect(rejection.status()).toBe(400)
    expect((await rejection.json()).code).toBe('COMMON_PARAM_INVALID')
  } finally { release() }
})

test('a real task freezes advanced settings and final generation keeps them after the Agent is changed', async ({ page }, info) => {
  await login(page)
  const id = await create(page, `Advanced frozen task ${Date.now()}`, custom)
  // Public binding APIs prepare the existing READY corpus and controlled order tool.
  for (const [endpoint, data] of [
    ['knowledge-bases', { knowledgeBaseIds: [PAYMENT_KB] }], ['tools', { toolIds: [ORDER_TOOL] }],
  ] as const) {
    const response = await page.request.put(`/api/v1/agents/${id}/${endpoint}`, {
      headers: { Authorization: `Bearer ${token}` }, data,
    })
    expect(response.status()).toBe(200)
  }
  const userTask = `Advanced settings freeze ${id}: investigate order_1024`
  await page.getByRole('link', { name: '运行 Agent', exact: true }).click()
  await page.getByLabel('任务输入', { exact: true }).fill(userTask)
  await page.getByRole('button', { name: '创建任务 →', exact: true }).click()
  await expect(page).toHaveURL(/\/tasks\/\d+$/)
  const taskId = page.url().split('/').at(-1)!
  await expect.poll(async () => readFile(path.join(control, 'model-waiting'), 'utf8').catch(() => '')).toBe(userTask)
  const runningTrace = await get(page, `/tasks/${taskId}/trace`)
  expect(runningTrace.executionSnapshot.snapshotVersion).toBe('agent-task-snapshot-v2')
  expect(runningTrace.executionSnapshot.executionSettings).toMatchObject({ policyVersion: 'agent-execution-policy-v1', ...custom })
  expect(Object.values(runningTrace.executionSnapshot.executionSettings.sources)).toEqual(Array(5).fill('AGENT_OVERRIDE'))
  await page.goto(`/agents/${id}`)
  await openAdvanced(page)
  await expect(page.getByTestId('agent-execution-effective')).toBeVisible()
  await page.locator('#agent-decisionMaxOutputTokens').fill('512')
  await page.locator('#agent-finalMaxOutputTokens').fill('2048')
  await page.locator('#agent-modelCallTimeoutSeconds').fill('2')
  await page.locator('#agent-decisionResponseFormat').selectOption('PROMPT_ONLY')
  await page.locator('#agent-thinkingMode').selectOption('PROVIDER_DEFAULT')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await expect.poll(async () => (await get(page, `/agents/${id}`)).modelCallTimeoutSeconds).toBe(2)
  await writeFile(path.join(control, 'release-model'), 'continue the already created task without restarting it')
  await page.goto(`/tasks/${taskId}`)
  await expect(page.getByTestId('task-status')).toHaveAttribute('data-status', 'COMPLETED')
  const trace = await get(page, `/tasks/${taskId}/trace`)
  expect(trace.executionSnapshot).toEqual(runningTrace.executionSnapshot)
  expect(trace.task.status).toBe('COMPLETED')
  const llmCalls = trace.steps.flatMap((step: any) => step.llmCalls)
  expect(llmCalls).toHaveLength(3)
  for (const call of llmCalls) {
    expect(call.requestSnapshot.timeoutSeconds).toBe(120)
    expect(call.requestSnapshot.thinkingMode).toBe('disabled')
    expect(call.status).toBe('SUCCESS')
    if (call.callType === 'FINAL_GENERATION') {
      expect(call.requestSnapshot.maxOutputTokens).toBe(4096)
      expect(call.requestSnapshot).not.toHaveProperty('responseSchema')
      expect(call.requestSnapshot).not.toHaveProperty('responseFormat')
    } else {
      expect(call.requestSnapshot.maxOutputTokens).toBe(2048)
      expect(call.requestSnapshot.responseSchema.name).toBeTruthy()
    }
  }
  const actualRequests = (await readFile(path.join(control, 'advanced-llm-requests'), 'utf8')).trim().split('\n').map(line => JSON.parse(line))
  expect(actualRequests).toHaveLength(3)
  expect(actualRequests.filter(request => request.phase === 'FINAL')).toEqual([
    { userTask, phase: 'FINAL', maxOutputTokens: 4096, timeoutSeconds: 120,
      responseFormat: null, thinkingMode: 'disabled', responseSchemaName: null },
  ])
  expect(actualRequests.filter(request => request.phase === 'DECISION')).toHaveLength(2)
  await page.getByRole('link', { name: '查看 Trace ↗' }).click()
  await expect(page.getByTestId('answer')).toContainText('order_1024')
  await page.reload()
  expect((await get(page, `/tasks/${taskId}/trace`)).executionSnapshot).toEqual(trace.executionSnapshot)
  await writeFile(info.outputPath('advanced-settings-evidence.json'), JSON.stringify({ agentId: id, taskId,
    originalAgentSettings: custom, currentAgent: await get(page, `/agents/${id}`),
    executionSnapshot: trace.executionSnapshot, actualRequests,
    boundary: 'real browser JWT, API, PostgreSQL, Runner and Trace; controlled model, embedding and vector store' }, null, 2))
  await page.screenshot({ path: info.outputPath('advanced-settings-frozen-trace.png'), fullPage: true })
})
