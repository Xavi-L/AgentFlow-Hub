import { test, expect, type Page } from '@playwright/test'
import { rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { parseJson } from '../src/lib/sequence'

const ORIGINAL = '430000000000000003'
const PAYMENT_KB = '430000000000000005'
const INVALID_AGENT = '460000000000000001'
const INVALID_KB = '460000000000000100'
const LEGACY_BINDING_AGENT = '460000000000000002'
const OVERFLOW_KB = '460000000000000102'
const ORDER_TOOL = '270000000000000001'
const PAYMENT_TOOL = '280000000000000001'
const control = process.env.V43_CONTROL_DIR
const ANSWER = '订单 order_1024 支付超时，请核对订单及支付日志。[S1]\n'
  + 'This answer uses the persisted knowledge citation and recorded order_query result.'
test.skip(!process.env.V46_BROWSER, 'Requires the V46 disposable PostgreSQL fixture')

let token = ''
let created: string[] = []
async function login(page: Page, target = '/agents') {
  await page.goto(target)
  await expect(page).toHaveURL(/\/login/)
  await page.getByLabel('用户名').fill('v43-browser')
  await page.getByLabel('密码', { exact: true }).fill('V43-browser-test!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(url => url.pathname === target)
  token = await page.evaluate(() => JSON.parse(sessionStorage.getItem('agentflow.session.v1')!).token as string)
}
async function publicGet(page: Page, endpoint: string) {
  const response = await page.request.get(`/api/v1${endpoint}`, { headers: { Authorization: `Bearer ${token}` } })
  expect(response.status()).toBe(200)
  return (parseJson(await response.text()) as any).data
}
async function fillConfig(page: Page, name: string) {
  for (const [label, value] of Object.entries({
    name, description: 'V46 real browser acceptance', systemPrompt: 'Explain recorded payment facts',
    modelName: 'v43-controlled-model', temperature: '0.2', topP: '0.8', maxSteps: '6',
    maxToolCalls: '4', maxTokens: '50000', timeoutSeconds: '300',
  })) await page.getByLabel(label === 'name' ? '名称' : label === 'description' ? '描述' : label, { exact: true }).fill(value)
}
async function createAgent(page: Page, name: string) {
  await fillConfig(page, name)
  await page.getByRole('button', { name: '创建 Agent →', exact: true }).click()
  await expect(page).toHaveURL(/\/agents\/\d+$/)
  const id = page.url().split('/').at(-1)!
  created.push(id)
  await expect(page.getByLabel('名称', { exact: true })).toHaveValue(name)
  return id
}
async function selectPaymentKnowledge(page: Page) {
  const section = page.getByTestId('knowledge-bindings')
  // The fixture has more than one public page; the existing READY payment corpus is on page two.
  await section.getByRole('button', { name: '下一页', exact: true }).click()
  await section.getByRole('checkbox', { name: 'Payment knowledge', exact: true }).check()
}

test.beforeEach(async () => { created = []; token = ''; await rm(path.join(control!, 'release-model'), { force: true }) })
test.afterEach(async ({ page }) => {
  await writeFile(path.join(control!, 'release-model'), 'release controlled provider after browser assertions')
  // Leave the original V43 fixture as the first ACTIVE Agent for its unchanged regression tests.
  for (const id of created) {
    const response = await page.request.post(`/api/v1/agents/${id}/disable`, { headers: { Authorization: `Bearer ${token}` } })
    expect(response.status()).toBe(200)
  }
})

test('page-created Agent saves independent configuration and bindings, then produces a persisted answer and Trace', async ({ page }, info) => {
  const writes: { method: string; url: string; body: any; bearer: boolean }[] = []
  page.on('request', request => {
    if (/\/api\/v1\/agents(?:\/|$)/.test(request.url()) && ['POST', 'PATCH', 'PUT'].includes(request.method())) {
      writes.push({ method: request.method(), url: request.url(), body: request.postDataJSON(), bearer: !!request.headers().authorization?.startsWith('Bearer ') })
    }
  })
  await login(page)
  const name = `V46 closed loop ${Date.now()}`
  const id = await createAgent(page, name)
  expect(writes[0]!.body).toMatchObject({ name, modelProvider: 'openai-compatible', modelName: 'v43-controlled-model', maxSteps: 6, maxToolCalls: 4, maxTokens: 50000 })
  expect(writes[0]!.body).not.toHaveProperty('maxIterations')
  await page.getByLabel('描述', { exact: true }).fill('Unsaved configuration survives binding writes')
  await selectPaymentKnowledge(page)
  const knowledge = page.getByTestId('knowledge-bindings')
  await knowledge.getByRole('button', { name: '保存知识库绑定', exact: true }).click()
  await expect.poll(async () => (await publicGet(page, `/agents/${id}/knowledge-bases`)).knowledgeBaseIds).toEqual([PAYMENT_KB])
  await expect(page.getByLabel('描述', { exact: true })).toHaveValue('Unsaved configuration survives binding writes')
  expect((await publicGet(page, `/agents/${id}`)).description).toBe('V46 real browser acceptance')
  await expect(knowledge).toContainText('READY')
  const tools = page.getByTestId('tool-bindings')
  await expect(tools.getByRole('checkbox')).toHaveCount(2)
  await expect(tools.getByRole('checkbox', { name: 'report_generate', exact: true })).toHaveCount(0)
  await tools.getByRole('checkbox', { name: 'order_query', exact: true }).check()
  await tools.getByRole('button', { name: '保存工具绑定', exact: true }).click()
  await expect.poll(async () => (await publicGet(page, `/agents/${id}/tools`)).toolIds).toEqual([ORDER_TOOL])
  await expect(page.getByLabel('描述', { exact: true })).toHaveValue('Unsaved configuration survives binding writes')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await expect.poll(async () => (await publicGet(page, `/agents/${id}`)).description).toBe('Unsaved configuration survives binding writes')
  expect(writes.filter(write => write.method === 'PATCH')).toHaveLength(1)
  expect(writes.filter(write => write.method === 'PUT')).toHaveLength(2)
  await page.screenshot({ path: info.outputPath('configured-agent.png'), fullPage: true })

  await page.getByRole('link', { name: '运行 Agent', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/agents/${id}/run$`))
  await expect(page.getByLabel('Agent', { exact: true })).toHaveValue(id)
  await page.getByLabel('任务输入', { exact: true }).fill(`V46 page created Agent ${id}: investigate order_1024`)
  await page.getByRole('button', { name: '创建任务 →', exact: true }).click()
  await expect(page).toHaveURL(/\/tasks\/\d+$/)
  const taskId = page.url().split('/').at(-1)!
  await expect(page.getByTestId('task-status')).toHaveAttribute('data-status', 'RUNNING')
  await expect(page.getByTestId('timeline')).toContainText('order_query')
  await page.goto(`/agents/${id}`)
  let disables = 0
  await page.route(`**/api/v1/agents/${id}/disable`, async route => {
    disables++
    const response = await route.fetch()
    expect(response.status()).toBe(200)
    await route.abort('connectionfailed')
  })
  await page.getByRole('button', { name: '停用 Agent', exact: true }).click()
  await expect(page.getByTestId('unknown-status')).toBeVisible()
  expect((await publicGet(page, `/tasks/${taskId}`)).status).toBe('RUNNING')
  await page.getByTestId('unknown-status').getByRole('button', { name: '读取服务端核对', exact: true }).click()
  await expect(page.getByRole('button', { name: '启用 Agent', exact: true })).toBeVisible()
  expect(disables).toBe(1)
  expect((await publicGet(page, `/tasks/${taskId}`)).status).toBe('RUNNING')
  await page.getByRole('button', { name: '启用 Agent', exact: true }).click()
  await expect(page.getByRole('button', { name: '停用 Agent', exact: true })).toBeVisible()
  expect((await publicGet(page, `/tasks/${taskId}`)).status).toBe('RUNNING')
  await writeFile(path.join(control!, 'release-model'), 'allow controlled final answer')
  await page.goto(`/tasks/${taskId}`)
  await expect(page.getByTestId('task-status')).toHaveAttribute('data-status', 'COMPLETED')
  await expect(page.getByTestId('answer')).toHaveText(ANSWER)
  await page.getByRole('link', { name: '查看 Trace ↗' }).click()
  await expect(page.getByTestId('answer')).toHaveText(ANSWER)
  const trace = await publicGet(page, `/tasks/${taskId}/trace`)
  expect(trace.task.agentId).toBe(id)
  expect(trace.steps.flatMap((step: any) => step.toolCalls)).toHaveLength(1)
  expect(trace.steps.flatMap((step: any) => step.ragRetrievals)).toHaveLength(1)
  expect(trace.task.citations).toEqual([{ citationId: 'S1', documentId: '430000000000000006', chunkId: '430000000000000007', vectorGeneration: 1 }])
  expect(writes.every(write => write.bearer)).toBe(true)
  await page.screenshot({ path: info.outputPath('agent-created-trace.png'), fullPage: true })
  await writeFile(info.outputPath('agent-evidence.json'), JSON.stringify({ agentId: id, taskId, writes, events: trace.events.length, providerBoundary: 'controlled model/embedding/vector; real browser JWT, public API, PostgreSQL, task runner and Trace' }, null, 2))
})

test('Agent pagination and strict step/tool budget validation preserve a create draft', async ({ page }) => {
  await login(page)
  const list = page.getByTestId('agent-list')
  await expect(list.locator('[data-agent-id]')).toHaveCount(20)
  await page.getByLabel('名称', { exact: true }).fill('Draft survives pagination')
  await list.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(list.locator('.pagination')).toContainText('第 2 页')
  await expect(list.locator('[data-agent-id]')).not.toHaveCount(0)
  await expect(page.getByLabel('名称', { exact: true })).toHaveValue('Draft survives pagination')
  let creates = 0
  page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/api/v1/agents')) creates++ })
  await fillConfig(page, 'Invalid tool budget')
  await page.getByLabel('maxSteps', { exact: true }).fill('4')
  await page.getByRole('button', { name: '创建 Agent →', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('maxToolCalls')
  expect(creates).toBe(0)
  await page.getByRole('link', { name: '任务', exact: true }).click()
  await page.getByRole('link', { name: 'Agent', exact: true }).click()
  await expect(page.getByLabel('名称', { exact: true })).toHaveValue('Invalid tool budget')
  await page.reload()
  await expect(page.getByLabel('maxSteps', { exact: true })).toHaveValue('4')
})

test('knowledge selection survives pages; an invalid bound ID remains explicit after a rejected replacement', async ({ page }) => {
  await login(page)
  const id = await createAgent(page, `V46 cross-page ${Date.now()}`)
  const knowledge = page.getByTestId('knowledge-bindings')
  await knowledge.getByRole('checkbox', { name: 'Pagination knowledge 18', exact: true }).check()
  await selectPaymentKnowledge(page)
  await expect(knowledge.getByTestId('selected-kb')).toHaveCount(2)
  await knowledge.getByRole('button', { name: '上一页', exact: true }).click()
  await expect(knowledge.getByRole('checkbox', { name: 'Pagination knowledge 18', exact: true })).toBeChecked()
  await knowledge.getByRole('button', { name: '保存知识库绑定', exact: true }).click()
  await expect.poll(async () => (await publicGet(page, `/agents/${id}/knowledge-bases`)).knowledgeBaseIds).toEqual(['450000000000000019', PAYMENT_KB])

  await page.goto(`/agents/${INVALID_AGENT}`)
  await expect(knowledge.getByTestId('selected-kb')).toContainText(INVALID_KB)
  await knowledge.getByRole('checkbox', { name: 'Pagination knowledge 18', exact: true }).check()
  const requests: any[] = []
  page.on('request', request => { if (request.method() === 'PUT' && request.url().endsWith(`/agents/${INVALID_AGENT}/knowledge-bases`)) requests.push(request.postDataJSON()) })
  await knowledge.getByRole('button', { name: '保存知识库绑定', exact: true }).click()
  await expect(knowledge.getByRole('alert')).toContainText('invalid')
  expect(requests).toEqual([{ knowledgeBaseIds: [INVALID_KB, '450000000000000019'] }])
  expect((await publicGet(page, `/agents/${INVALID_AGENT}/knowledge-bases`)).knowledgeBaseIds).toEqual([INVALID_KB])
  await expect(knowledge.getByTestId('selected-kb').filter({ hasText: INVALID_KB })).toBeVisible()
})

test('a committed create with a lost response remains uncertain after reload and never resubmits itself', async ({ page }) => {
  await login(page)
  let creates = 0, committedId = ''
  await page.route('**/api/v1/agents', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    creates++
    const response = await route.fetch()
    expect(response.status()).toBe(201)
    committedId = (await response.json()).data.id
    created.push(committedId)
    await route.abort('connectionfailed')
  })
  await fillConfig(page, `V46 uncertain create ${Date.now()}`)
  await page.getByRole('button', { name: '创建 Agent →', exact: true }).click()
  await expect(page.getByTestId('unknown-create')).toBeVisible()
  await page.reload()
  await expect(page.getByTestId('unknown-create')).toBeVisible()
  await expect(page.getByRole('button', { name: '创建 Agent →', exact: true })).toBeDisabled()
  await page.getByTestId('unknown-create').getByRole('button', { name: '刷新列表核对', exact: true }).click()
  await expect(page.locator(`[data-agent-id="${committedId}"]`)).toBeVisible()
  await expect(page.getByRole('button', { name: '创建 Agent →', exact: true })).toBeDisabled()
  await expect(page.getByTestId('unknown-create')).toContainText('同名记录不能证明')
  // A readback of page one must lose authority when the user moves to page two.
  let release!: () => void, fetched!: () => void, delivered!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const captured = new Promise<void>(resolve => { fetched = resolve })
  const lateResponse = new Promise<void>(resolve => { delivered = resolve })
  await page.route('**/api/v1/agents?page=1*', async route => {
    const response = await route.fetch()
    fetched(); await gate
    await route.fulfill({ response }).catch(() => {})
    delivered()
  })
  try {
    await page.getByTestId('unknown-create').getByRole('button', { name: '刷新列表核对', exact: true }).click()
    await captured
    const list = page.getByTestId('agent-list')
    await list.getByRole('button', { name: '下一页', exact: true }).click()
    await expect(list.locator(`[data-agent-id="${INVALID_AGENT}"]`)).toBeVisible()
    release(); await lateResponse
    await expect(list.locator('.pagination')).toContainText('第 2 页')
    await expect(list.locator(`[data-agent-id="${committedId}"]`)).toHaveCount(0)
    await expect(page.getByTestId('unknown-create')).toBeVisible()
    await expect(page.getByRole('button', { name: '创建 Agent →', exact: true })).toBeDisabled()
    await expect(page.getByRole('button', { name: '我已核对列表，允许新的创建', exact: true })).toHaveCount(0)
  } finally { release() }
  expect(creates).toBe(1)
  expect((await publicGet(page, `/agents/${committedId}`)).modelProvider).toBe('openai-compatible')
})

test('lost configuration response reads back once without replacing a newer persisted draft or retrying PATCH', async ({ page }) => {
  await login(page)
  const id = await createAgent(page, `V46 uncertain config ${Date.now()}`)
  let patches = 0
  await page.route(`**/api/v1/agents/${id}`, async route => {
    if (route.request().method() !== 'PATCH') return route.continue()
    patches++
    const response = await route.fetch()
    expect(response.status()).toBe(200)
    await route.abort('connectionfailed')
  })
  await page.getByLabel('描述', { exact: true }).fill('Committed configuration')
  await page.getByRole('button', { name: '保存配置', exact: true }).click()
  await expect(page.getByTestId('unknown-config')).toBeVisible()
  await page.getByLabel('描述', { exact: true }).fill('Newer unsaved configuration')
  await page.reload()
  await expect(page.getByLabel('描述', { exact: true })).toHaveValue('Newer unsaved configuration')
  await expect(page.getByTestId('unknown-config')).toBeVisible()
  await page.getByTestId('unknown-config').getByRole('button', { name: '读取服务端核对', exact: true }).click()
  await expect(page.getByTestId('unknown-config')).toHaveCount(0)
  await expect(page.getByLabel('描述', { exact: true })).toHaveValue('Newer unsaved configuration')
  expect((await publicGet(page, `/agents/${id}`)).description).toBe('Committed configuration')
  expect(patches).toBe(1)
})

test('knowledge and tool lost responses are independent and reconcile through GET without replaying either PUT', async ({ page }) => {
  await login(page)
  const id = await createAgent(page, `V46 uncertain bindings ${Date.now()}`)
  const puts = { knowledge: 0, tools: 0 }
  for (const [key, endpoint] of [['knowledge', 'knowledge-bases'], ['tools', 'tools']] as const) {
    await page.route(`**/api/v1/agents/${id}/${endpoint}`, async route => {
      if (route.request().method() !== 'PUT') return route.continue()
      puts[key]++
      const response = await route.fetch()
      expect(response.status()).toBe(200)
      await route.abort('connectionfailed')
    })
  }
  await selectPaymentKnowledge(page)
  await page.getByRole('button', { name: '保存知识库绑定', exact: true }).click()
  await expect(page.getByTestId('unknown-knowledge')).toBeVisible()
  await page.getByTestId('tool-bindings').getByRole('checkbox', { name: 'payment_log_query', exact: true }).check()
  await page.getByRole('button', { name: '保存工具绑定', exact: true }).click()
  await expect(page.getByTestId('unknown-tools')).toBeVisible()
  await page.getByTestId('unknown-knowledge').getByRole('button', { name: '读取服务端核对', exact: true }).click()
  await expect(page.getByTestId('unknown-knowledge')).toHaveCount(0)
  await expect(page.getByTestId('unknown-tools')).toBeVisible()
  await page.getByTestId('unknown-tools').getByRole('button', { name: '读取服务端核对', exact: true }).click()
  await expect(page.getByTestId('unknown-tools')).toHaveCount(0)
  expect(puts).toEqual({ knowledge: 1, tools: 1 })
  expect((await publicGet(page, `/agents/${id}/knowledge-bases`)).knowledgeBaseIds).toEqual([PAYMENT_KB])
  expect((await publicGet(page, `/agents/${id}/tools`)).toolIds).toEqual([PAYMENT_TOOL])
})

test('leaving a committed PATCH aborts its response and retains an uncertain write until explicit readback', async ({ page }) => {
  await login(page)
  const id = await createAgent(page, `V46 cancelled write ${Date.now()}`)
  let release!: () => void, committed!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const captured = new Promise<void>(resolve => { committed = resolve })
  let patches = 0, aborted = false
  page.on('requestfailed', request => {
    if (request.method() === 'PATCH' && request.url().endsWith(`/agents/${id}`)) aborted = true
  })
  await page.route(`**/api/v1/agents/${id}`, async route => {
    if (route.request().method() !== 'PATCH') return route.continue()
    patches++
    const response = await route.fetch()
    expect(response.status()).toBe(200)
    committed(); await gate
    await route.fulfill({ response }).catch(() => {})
  })
  try {
    await page.getByLabel('描述', { exact: true }).fill('Committed before leaving')
    await page.getByRole('button', { name: '保存配置', exact: true }).click()
    await captured
    await page.getByRole('link', { name: '任务', exact: true }).click()
    await expect(page).toHaveURL(/\/tasks$/)
    await expect.poll(() => aborted).toBe(true)
    release()
    await page.goto(`/agents/${id}`)
    await expect(page.getByTestId('unknown-config')).toBeVisible()
    await expect(page.getByLabel('描述', { exact: true })).toHaveValue('Committed before leaving')
    await page.getByLabel('描述', { exact: true }).fill('New draft after returning')
    await page.getByTestId('unknown-config').getByRole('button', { name: '读取服务端核对', exact: true }).click()
    await expect(page.getByTestId('unknown-config')).toHaveCount(0)
    await expect(page.getByLabel('描述', { exact: true })).toHaveValue('New draft after returning')
    expect((await publicGet(page, `/agents/${id}`)).description).toBe('Committed before leaving')
    expect(patches).toBe(1)
  } finally { release() }
})

test('a late knowledge page cannot replace a newer page or discard cross-page selection', async ({ page }) => {
  await login(page, `/agents/${ORIGINAL}`)
  const knowledge = page.getByTestId('knowledge-bindings')
  await expect(knowledge.getByTestId('selected-kb')).toContainText(PAYMENT_KB)
  await expect(knowledge.getByRole('checkbox', { name: 'Pagination knowledge 18', exact: true })).toBeEnabled()
  let release!: () => void, fetched!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const captured = new Promise<void>(resolve => { fetched = resolve })
  await page.route('**/api/v1/knowledge-bases?page=1*', async route => {
    const response = await route.fetch()
    fetched(); await gate
    await route.fulfill({ response }).catch(() => {})
  })
  try {
    await knowledge.getByRole('button', { name: '刷新知识库列表', exact: true }).click()
    await captured
    await knowledge.getByRole('button', { name: '下一页', exact: true }).click()
    await expect(knowledge.getByRole('checkbox', { name: 'Payment knowledge', exact: true })).toBeChecked()
    release()
    await expect(knowledge.locator('.pagination')).toContainText('第 2 页')
    await expect(knowledge.getByRole('checkbox', { name: 'Pagination knowledge 18', exact: true })).toHaveCount(0)
    await expect(knowledge.getByTestId('selected-kb')).toContainText(PAYMENT_KB)
  } finally { release() }
})

test('leaving and logout abort pending Agent GETs; missing Agent and rejected JWT expose no protected form', async ({ page }) => {
  await login(page, `/agents/${ORIGINAL}`)
  await expect(page.getByLabel('名称', { exact: true })).toHaveValue('Payment investigation')
  for (const destination of ['leave', 'logout']) {
    let release!: () => void, fetched!: () => void
    const gate = new Promise<void>(resolve => { release = resolve })
    const captured = new Promise<void>(resolve => { fetched = resolve })
    const aborted: string[] = []
    const onFailed = (request: import('@playwright/test').Request) => aborted.push(request.url())
    page.on('requestfailed', onFailed)
    await page.route(`**/api/v1/agents/${ORIGINAL}`, async route => {
      const response = await route.fetch()
      fetched(); await gate
      await route.fulfill({ response }).catch(() => {})
    })
    try {
      await page.reload()
      await captured
      if (destination === 'leave') {
        await page.getByRole('link', { name: '任务', exact: true }).click()
        await expect(page).toHaveURL(/\/tasks$/)
      } else {
        await page.getByRole('button', { name: '退出登录', exact: true }).click()
        await expect(page).toHaveURL(/\/login/)
      }
      await expect.poll(() => aborted.some(url => url.endsWith(`/agents/${ORIGINAL}`))).toBe(true)
      release()
      await expect(page.getByTestId('agent-config')).toHaveCount(0)
    } finally {
      release()
      await page.unroute(`**/api/v1/agents/${ORIGINAL}`)
      page.off('requestfailed', onFailed)
    }
    if (destination === 'leave') {
      await page.goto(`/agents/${ORIGINAL}`)
      await expect(page.getByLabel('名称', { exact: true })).toHaveValue('Payment investigation')
    }
  }
  expect(await page.evaluate(() => sessionStorage.getItem('agentflow.session.v1'))).toBeNull()
  expect(await page.evaluate(() => sessionStorage.getItem('agentflow.agent-drafts.v1'))).toBeNull()
  expect(await page.evaluate(() => sessionStorage.getItem('agentflow.agent-writes.v1'))).toBeNull()
  await login(page, '/agents/9223372036854775807')
  await expect(page.getByRole('alert')).toContainText('not found')
  await expect(page.getByRole('button', { name: '保存配置', exact: true })).toHaveCount(0)
  await page.evaluate(() => {
    const session = JSON.parse(sessionStorage.getItem('agentflow.session.v1')!)
    session.token += '-invalid-signature'
    sessionStorage.setItem('agentflow.session.v1', JSON.stringify(session))
  })
  await page.goto(`/agents/${ORIGINAL}`)
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByTestId('agent-config')).toHaveCount(0)
})

test('legacy 21 knowledge bindings remain visible and repair to 20 without uncertain or excess writes', async ({ page }, info) => {
  const legacyIds = [PAYMENT_KB,
    ...Array.from({ length: 19 }, (_, index) => String(450000000000000001n + BigInt(index))), OVERFLOW_KB]
  const repairedIds = legacyIds.slice(0, 20)
  const writes: { knowledgeBaseIds: string[] }[] = []
  page.on('request', request => {
    if (request.method() === 'PUT' && request.url().endsWith(`/agents/${LEGACY_BINDING_AGENT}/knowledge-bases`)) {
      writes.push(request.postDataJSON() as { knowledgeBaseIds: string[] })
    }
  })
  await login(page, `/agents/${LEGACY_BINDING_AGENT}`)
  const originalAgent = await publicGet(page, `/agents/${LEGACY_BINDING_AGENT}`)
  const knowledge = page.getByTestId('knowledge-bindings')
  const selectedIds = () => knowledge.getByTestId('selected-kb').evaluateAll(nodes => nodes.map(node => node.getAttribute('data-kb-id')!))
  const save = knowledge.getByRole('button', { name: '保存知识库绑定', exact: true })
  await expect.poll(selectedIds).toEqual(legacyIds)
  await expect(knowledge).toContainText('已选 21 / 20')
  await expect(knowledge.getByRole('alert')).toContainText('请移除超出部分后保存')
  await expect(save).toBeDisabled()
  await expect(knowledge.getByRole('button', { name: `移除知识库 ${OVERFLOW_KB}`, exact: true })).toBeEnabled()
  expect((await publicGet(page, `/agents/${LEGACY_BINDING_AGENT}/knowledge-bases`)).knowledgeBaseIds).toEqual(legacyIds)
  await page.reload()
  await expect.poll(selectedIds).toEqual(legacyIds)
  await expect(save).toBeDisabled()
  await expect(page.getByTestId('unknown-knowledge')).toHaveCount(0)
  expect(await page.evaluate(() => JSON.parse(sessionStorage.getItem('agentflow.agent-writes.v1') || '{}'))).toEqual({})
  expect(writes).toEqual([])

  await page.getByLabel('描述', { exact: true }).fill('Unsaved configuration survives V49 binding repair')
  await knowledge.getByRole('button', { name: `移除知识库 ${OVERFLOW_KB}`, exact: true }).click()
  await expect.poll(selectedIds).toEqual(repairedIds)
  await expect(knowledge).toContainText('已选 20 / 20')
  await expect(knowledge.getByRole('alert')).toHaveCount(0)
  await expect(save).toBeEnabled()
  // The historical extra KB is on page two; reaching 20 prevents adding it back, while selected rows stay removable.
  await knowledge.getByRole('button', { name: '下一页', exact: true }).click()
  await expect(knowledge.getByRole('checkbox', { name: 'Legacy overflow knowledge', exact: true })).toBeDisabled()
  const selectedPayment = knowledge.getByRole('checkbox', { name: 'Payment knowledge', exact: true })
  await expect(selectedPayment).toBeChecked()
  await expect(selectedPayment).toBeEnabled()
  await save.click()
  await expect.poll(async () => (await publicGet(page, `/agents/${LEGACY_BINDING_AGENT}/knowledge-bases`)).knowledgeBaseIds).toEqual(repairedIds)
  await expect(knowledge).toContainText('保存知识库绑定已确认')
  expect(writes).toEqual([{ knowledgeBaseIds: repairedIds }])
  expect((await publicGet(page, `/agents/${LEGACY_BINDING_AGENT}`)).description).toBe(originalAgent.description)
  await expect(page.getByLabel('描述', { exact: true })).toHaveValue('Unsaved configuration survives V49 binding repair')
  await page.reload()
  await expect.poll(selectedIds).toEqual(repairedIds)
  await expect(knowledge).toContainText('与已读绑定一致')
  await expect(page.getByTestId('unknown-knowledge')).toHaveCount(0)
  await expect(page.getByLabel('描述', { exact: true })).toHaveValue('Unsaved configuration survives V49 binding repair')
  expect((await publicGet(page, `/agents/${LEGACY_BINDING_AGENT}/knowledge-bases`)).knowledgeBaseIds).toEqual(repairedIds)
  expect(writes).toHaveLength(1)
  await page.screenshot({ path: info.outputPath('knowledge-binding-limit.png'), fullPage: true })
  await writeFile(info.outputPath('knowledge-binding-limit-evidence.json'), JSON.stringify({
    agentId: LEGACY_BINDING_AGENT, originalBindingIds: legacyIds, repairedBindingIds: repairedIds, writes,
    providerBoundary: 'Real browser JWT, public binding API and PostgreSQL; no task or provider call in this scenario',
  }, null, 2))
})
