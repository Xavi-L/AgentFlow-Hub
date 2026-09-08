import { test, expect, type Page, type TestInfo } from '@playwright/test'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { parseJson } from '../src/lib/sequence'
import { isTerminal, type Task, type TaskTrace } from '../src/lib/types'
import { redactEvidence, verifyRealProviderEvidence, type CurrentChunk, type CurrentDocument } from './real-provider-evidence'

const TASK_INPUT = '帮我分析 order_1024 支付失败的原因，并给出处理建议。'
const SYSTEM_PROMPT = '你是支付诊断助手。对 order_1024 必须分别调用 order_query 和 payment_log_query，参数 orderNo 都为 order_1024；两个工具各调用一次。获得两份结果后使用 FINISH，再独立生成中文答案。最终答案写出订单号和工具返回的错误码，并引用检索资料的原始 [S数字] 标记。区分演示业务事实、文档建议和无法确认的信息。'

type Evidence = {
  schemaVersion: 1; status: 'RUNNING' | 'PASSED' | 'FAILED'; phase: string
  startedAt: string; finishedAt?: string; error?: string; readbackErrors: string[]
  providerBoundary: string; requestedModel?: string
  budget: { maxSteps: number; maxToolCalls: number; maxTokens: number; timeoutSeconds: number; taskSubmissions: number }
  knowledgeBaseId?: string; documentId?: string; agentId?: string; taskId?: string
  sourceFile?: string; document?: CurrentDocument; chunks?: CurrentChunk[]
  task?: Task; trace?: TaskTrace; refreshedTask?: Task; refreshedTrace?: TaskTrace
  browserWrites: { method: string; endpoint: string }[]
  browserEventSequences?: string[]; refreshedBrowserEventSequences?: string[]
  checkpoints: { phase: string; at: string }[]
}

function required(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`Missing required ${name}; run the V47 real-provider launcher`)
  return value
}

test('normal application uses real chat, DashScope and Qdrant for a browser-created payment diagnosis', async ({ page }, info) => {
  const seconds = Number(process.env.V47_TASK_TIMEOUT_SECONDS || 180)
  const evidence: Evidence = {
    schemaVersion: 1, status: 'RUNNING', phase: 'environment', startedAt: new Date().toISOString(),
    readbackErrors: [], checkpoints: [], browserWrites: [],
    providerBoundary: 'Normal application with remote chat/embedding/Qdrant; actual ToolRuntime over V12 read-only demonstration business data. Launcher and storage evidence independently verify gateway and Qdrant boundaries.',
    budget: { maxSteps: 5, maxToolCalls: 3, maxTokens: 24000, timeoutSeconds: seconds, taskSubmissions: 0 },
  }
  let token = ''
  const secrets = Object.entries(process.env).filter(([key]) => /PASSWORD|API_KEY|SECRET|TOKEN|V47_USERNAME/i.test(key))
    .flatMap(([, value]) => value ? [value] : [])

  async function save() {
    const serialized = JSON.stringify(redactEvidence(evidence, [...secrets, token]), null, 2)
    await writeFile(info.outputPath('browser-evidence.json'), serialized)
    if (process.env.V47_CONTROL_DIR) {
      await mkdir(process.env.V47_CONTROL_DIR, { recursive: true })
      await writeFile(path.join(process.env.V47_CONTROL_DIR, 'browser-evidence.json'), serialized)
    }
  }
  async function checkpoint(phase: string) {
    evidence.phase = phase
    evidence.checkpoints.push({ phase, at: new Date().toISOString() })
    await save()
  }
  async function get<T>(endpoint: string): Promise<T> {
    // Only GET is used for evidence/readback. Never replay an uncertain mutation.
    const response = await page.request.get(`/api/v1${endpoint}`, {
      headers: { Authorization: `Bearer ${token}` }, timeout: 10_000,
    }).catch(() => { throw new Error(`Public GET ${endpoint} did not complete`) })
    if (response.status() !== 200) throw new Error(`Public GET ${endpoint} returned HTTP ${response.status()}`)
    const body = parseJson(await response.text()) as { code: string; data: T }
    if (body.code !== 'OK') throw new Error(`Public GET ${endpoint} returned a non-OK envelope`)
    return body.data
  }
  async function documentReadback() {
    if (evidence.documentId) evidence.document = await get<CurrentDocument>(`/documents/${evidence.documentId}`)
    if (evidence.knowledgeBaseId && evidence.documentId) {
      const chunks = await get<{ items: CurrentChunk[]; hasNext: boolean }>(
        `/knowledge-bases/${evidence.knowledgeBaseId}/documents/${evidence.documentId}/chunks?page=1&pageSize=20`)
      if (chunks.hasNext) throw new Error('V47 demo exceeds the bounded single chunk page')
      evidence.chunks = chunks.items
    }
  }
  async function screenshot(name: string) {
    await page.screenshot({ path: info.outputPath(name), fullPage: true,
      mask: [page.getByLabel('用户名'), page.getByLabel('密码', { exact: true })] })
  }
  page.on('request', request => {
    const endpoint = new URL(request.url()).pathname
    if (!endpoint.startsWith('/api/v1/') || endpoint.startsWith('/api/v1/auth/') || request.method() === 'GET') return
    evidence.browserWrites.push({ method: request.method(), endpoint })
    if (request.method() === 'POST' && /^\/api\/v1\/agents\/\d+\/tasks$/.test(endpoint)) evidence.budget.taskSubmissions++
  })

  try {
    if (process.env.V47_BROWSER !== '1') throw new Error('V47_BROWSER=1 is required for this paid, single-attempt acceptance')
    if (['V43_CONTROL_DIR', 'V45_BROWSER', 'V46_BROWSER'].some(key => process.env[key])) {
      throw new Error('Controlled acceptance variables are forbidden in V47')
    }
    if (!Number.isInteger(seconds) || seconds < 60 || seconds > 300) throw new Error('V47 task timeout must be 60–300 seconds')
    const model = required('V47_CHAT_MODEL'), username = required('V47_USERNAME'), password = required('V47_PASSWORD')
    const source = required('V47_DEMO_FILE')
    required('V47_CONTROL_DIR')
    if (!path.isAbsolute(source) || !/\.(txt|md)$/i.test(source)) throw new Error('V47_DEMO_FILE must be an absolute TXT/MD path')
    const content = await readFile(source)
    if (!content.length || content.length > 2048) throw new Error('V47 demo must contain 1–2048 bytes')
    evidence.sourceFile = path.basename(source)
    evidence.requestedModel = model
    await checkpoint('account-setup')
    // There is no registration page. Prepare a unique account through the existing public API.
    // Subsequent login and all KB/document/Agent/task writes are performed in the production UI.
    const registration = await page.request.post('/api/v1/auth/register', {
      data: { username, password, displayName: 'V47 验收' }, timeout: 15_000,
    }).catch(() => { throw new Error('Account registration did not complete; no registration retry was attempted') })
    expect(registration.status(), 'Unique acceptance account registration must succeed once').toBe(201)
    await page.goto('/knowledge-bases')
    await expect(page).toHaveURL(/\/login/)
    try {
      await page.getByLabel('用户名').fill(username)
      await page.getByLabel('密码', { exact: true }).fill(password)
      await page.getByRole('button', { name: '登录', exact: true }).click()
      await expect(page).toHaveURL(url => url.pathname === '/knowledge-bases')
    } catch { throw new Error('Browser login failed; credential-bearing call details omitted') }
    token = await page.evaluate(() => JSON.parse(sessionStorage.getItem('agentflow.session.v1')!).token as string)
    expect(token.length > 0).toBe(true)

    await checkpoint('create-knowledge-base')
    const name = `V47 payment diagnosis ${Date.now()}`
    await page.getByLabel('名称', { exact: true }).fill(name)
    await page.getByLabel('描述（选填）').fill('Real DashScope embedding and Qdrant acceptance')
    await page.getByRole('button', { name: '创建知识库 →', exact: true }).click()
    await expect(page).toHaveURL(/\/knowledge-bases\/\d+$/)
    evidence.knowledgeBaseId = page.url().split('/').at(-1)!
    const knowledgeBase = await get<{ embeddingProfileCode: string; chunkStrategyVersion: string }>(`/knowledge-bases/${evidence.knowledgeBaseId}`)
    expect(knowledgeBase.embeddingProfileCode).toBe('dashscope-te-v4-1024-cosine')
    expect(knowledgeBase.chunkStrategyVersion).toBe('structured-token-v1')

    await checkpoint('upload-source')
    await page.getByLabel('TXT / MD 单文件').setInputFiles(source)
    await page.getByRole('button', { name: '上传文档', exact: true }).click()
    const documentRow = page.locator('[data-document-id]')
    await expect(documentRow).toHaveCount(1)
    await expect(documentRow).toContainText(path.basename(source))
    await expect(documentRow).toContainText('PENDING')
    evidence.documentId = (await documentRow.getAttribute('data-document-id'))!
    await documentReadback()
    expect(evidence.document!.retrievalReadiness).toBe('NOT_READY')

    await checkpoint('explicit-parse')
    await page.getByRole('button', { name: '解析待处理文档', exact: true }).click()
    await expect(documentRow.locator('[data-readiness]')).toHaveAttribute('data-readiness', 'INDEXING')
    await documentReadback()
    expect(evidence.document!.parseStatus).toBe('COMPLETED')
    expect(evidence.chunks!.length).toBeGreaterThan(0)
    expect(evidence.chunks!.length, 'Demo budget allows at most four embedding inputs').toBeLessThanOrEqual(4)

    await checkpoint('explicit-vectorization')
    await page.getByRole('button', { name: '向量化待处理分块', exact: true }).click()
    await expect.poll(async () => {
      evidence.document = await get<CurrentDocument>(`/documents/${evidence.documentId}`)
      return ['READY', 'FAILED', 'DEGRADED'].includes(evidence.document.retrievalReadiness)
    }, { timeout: 90_000, intervals: [1000, 3000, 3000] }).toBe(true)
    expect(evidence.document!.retrievalReadiness, 'Remote vectorization failure cannot fall back to a controlled gateway').toBe('READY')
    await page.getByRole('button', { name: '刷新状态', exact: true }).click()
    await expect(documentRow.locator('[data-readiness]')).toHaveAttribute('data-readiness', 'READY')
    await expect(documentRow.locator('.ready-label')).toBeVisible()
    await documentReadback()
    await screenshot('knowledge-ready.png')

    await checkpoint('create-agent-and-bindings')
    await page.getByRole('link', { name: 'Agent', exact: true }).click()
    for (const [label, value] of Object.entries({
      '名称': name, '描述': 'V47 real-provider main path', systemPrompt: SYSTEM_PROMPT, modelName: model,
      temperature: '0.1', topP: '0.8', maxSteps: '5', maxToolCalls: '3', maxTokens: '24000', timeoutSeconds: String(seconds),
    })) await page.getByLabel(label, { exact: true }).fill(value)
    await page.getByRole('button', { name: '创建 Agent →', exact: true }).click()
    await expect(page).toHaveURL(/\/agents\/\d+$/)
    evidence.agentId = page.url().split('/').at(-1)!
    const knowledge = page.getByTestId('knowledge-bindings')
    await knowledge.getByRole('checkbox', { name, exact: true }).check()
    await knowledge.getByRole('button', { name: '保存知识库绑定', exact: true }).click()
    await expect.poll(async () => (await get<{ knowledgeBaseIds: string[] }>(`/agents/${evidence.agentId}/knowledge-bases`)).knowledgeBaseIds)
      .toEqual([evidence.knowledgeBaseId])
    const toolSection = page.getByTestId('tool-bindings')
    for (const code of ['order_query', 'payment_log_query']) await toolSection.getByRole('checkbox', { name: code, exact: true }).check()
    await toolSection.getByRole('button', { name: '保存工具绑定', exact: true }).click()
    const catalog = await get<{ id: string; toolCode: string }[]>('/tools')
    const selectedTools = ['order_query', 'payment_log_query'].map(code => catalog.find(tool => tool.toolCode === code)!.id)
    await expect.poll(async () => (await get<{ toolIds: string[] }>(`/agents/${evidence.agentId}/tools`)).toolIds).toEqual(selectedTools)
    await screenshot('configured-agent.png')

    await checkpoint('submit-single-task')
    await page.getByRole('link', { name: '运行 Agent', exact: true }).click()
    await expect(page.getByLabel('Agent', { exact: true })).toHaveValue(evidence.agentId)
    await page.getByLabel('任务输入', { exact: true }).fill(TASK_INPUT)
    await page.getByRole('button', { name: '创建任务 →', exact: true }).click()
    await expect(page).toHaveURL(/\/tasks\/\d+$/)
    evidence.taskId = page.url().split('/').at(-1)!
    await checkpoint('observe-task')
    await expect.poll(async () => {
      evidence.task = await get<Task>(`/tasks/${evidence.taskId}`)
      return isTerminal(evidence.task.status)
    }, { timeout: (seconds + 30) * 1000, intervals: [1000, 3000, 3000] }).toBe(true)
    evidence.trace = await get<TaskTrace>(`/tasks/${evidence.taskId}/trace`)
    await documentReadback() // Current generation is checked again after model/tool execution.
    await checkpoint('verify-persisted-evidence')
    verifyRealProviderEvidence(evidence.task!, evidence.trace, {
      knowledgeBaseId: evidence.knowledgeBaseId, document: evidence.document!, chunks: evidence.chunks!,
      agentId: evidence.agentId, taskId: evidence.taskId, model,
    })
    expect(evidence.budget.taskSubmissions).toBe(1)
    const sequences = () => page.getByTestId('timeline').locator('[data-sequence]').evaluateAll(nodes =>
      nodes.map(node => node.getAttribute('data-sequence')!))
    const persistedSequences = evidence.trace.events.map(event => String(event.sequenceNo))
    await expect(page.getByTestId('task-status')).toHaveAttribute('data-status', 'COMPLETED')
    await expect(page.getByTestId('answer')).toHaveText(evidence.task!.finalAnswer!)
    await expect.poll(sequences).toEqual(persistedSequences)
    evidence.browserEventSequences = await sequences()
    await screenshot('completed-runtime.png')

    await checkpoint('refresh-and-trace-convergence')
    await page.reload()
    await expect(page.getByTestId('task-status')).toHaveAttribute('data-status', 'COMPLETED')
    await expect(page.getByTestId('answer')).toHaveText(evidence.task!.finalAnswer!)
    await expect.poll(sequences).toEqual(persistedSequences)
    evidence.refreshedBrowserEventSequences = await sequences()
    evidence.refreshedTask = await get<Task>(`/tasks/${evidence.taskId}`)
    evidence.refreshedTrace = await get<TaskTrace>(`/tasks/${evidence.taskId}/trace`)
    expect(evidence.refreshedTask).toEqual(evidence.task)
    expect(evidence.refreshedTrace).toEqual(evidence.trace)
    await page.getByRole('link', { name: '查看 Trace ↗' }).click()
    await expect(page).toHaveURL(new RegExp(`/tasks/${evidence.taskId}/trace$`))
    await expect(page.getByTestId('answer')).toHaveText(evidence.task!.finalAnswer!)
    await page.getByRole('tab', { name: `工具 (2)`, exact: true }).click()
    await expect(page.getByRole('tabpanel').filter({ visible: true })).toContainText('SUCCESS')
    await screenshot('tool-runtime-trace.png')
    await page.getByRole('tab', { name: /^LLM \(/ }).click()
    await expect(page.getByRole('tabpanel').filter({ visible: true })).toContainText('FINAL_GENERATION')
    await screenshot('model-trace.png')
    const firstCitation = evidence.task!.citations[0] as { citationId: string }
    await page.getByTestId('answer').getByRole('button', { name: `[${firstCitation.citationId}]`, exact: true }).first().click()
    const drawer = page.getByRole('dialog', { name: '引用证据 · 历史快照' })
    await expect(drawer).toContainText(evidence.documentId)
    await expect(drawer).toContainText('检索分数')
    await expect(drawer.locator('.answer-text')).not.toBeEmpty()
    await screenshot('citation-evidence.png')
    evidence.status = 'PASSED'
    await checkpoint('complete')
  } catch (failure) {
    evidence.status = 'FAILED'
    evidence.error = String(redactEvidence(failure instanceof Error ? failure.message : String(failure), [...secrets, token]))
    throw new Error(`V47 ${evidence.phase}: ${evidence.error}`)
  } finally {
    // Save partial evidence even on a failed provider run; never hide it with a mock retry.
    if (token && !page.isClosed()) {
      for (const [name, read] of [
        ['document', documentReadback],
        ['task', async () => { if (evidence.taskId) evidence.task = await get<Task>(`/tasks/${evidence.taskId}`) }],
        ['trace', async () => { if (evidence.taskId) evidence.trace = await get<TaskTrace>(`/tasks/${evidence.taskId}/trace`) }],
      ] as const) {
        try { await read() } catch { evidence.readbackErrors.push(`${name} final readback unavailable`) }
      }
      if (evidence.status !== 'PASSED') await screenshot('failure-state.png').catch(() => {})
    }
    evidence.finishedAt = new Date().toISOString()
    await save()
    await attachEvidence(info)
  }
})

async function attachEvidence(info: TestInfo) {
  await info.attach('V47 browser evidence', { path: info.outputPath('browser-evidence.json'), contentType: 'application/json' })
}
