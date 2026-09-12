import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import AgentConfigurationForm from '../components/AgentConfigurationForm.vue'
import AgentsPage from '../pages/AgentsPage.vue'
import AgentPage from '../pages/AgentPage.vue'
import { taskFailureMessage } from '../presentation'
import { http } from './api'
import { agentApi, buildAgentConfig, defaultAgentDraft, draftFromAgent, finalAnswerReserve, restoreAgentDraft,
  sameAgentConfig, validateAgentDraft, type AgentDraft, type AgentExecutionOptions } from './agent-api'
import { executionOptionsLoader } from './agent-execution-options'
import { knowledgeApi } from './knowledge-api'
import { clearSession, setSession } from './session'

const draft = (): AgentDraft => ({ ...defaultAgentDraft(), name: 'Agent', systemPrompt: 'Use evidence', modelName: 'local-model' })
const options = (modelName = 'local-model'): AgentExecutionOptions => ({ policyVersion: 'agent-execution-policy-v1', modelName,
  defaults: { decisionMaxOutputTokens: 512, finalMaxOutputTokens: null, decisionResponseFormat: 'PROMPT_ONLY',
    thinkingMode: 'PROVIDER_DEFAULT', modelCallTimeoutSeconds: 30 }, limits: { maxOutputTokens: 16384, maxModelCallTimeoutSeconds: 600 },
  capabilities: { jsonObject: true, jsonSchema: false, disableThinking: false } })
const login = () => setSession({ accessToken: 'token', tokenType: 'Bearer', expiresIn: 3600,
  user: { id: '1', username: 'test', displayName: 'Test', role: 'USER' } })
const reply = (data: unknown) => ({ status: 200, data: JSON.stringify({ code: 'OK', data }) })
afterEach(() => { clearSession(); vi.restoreAllMocks(); vi.useRealTimers() })

describe('Agent advanced configuration contract', () => {
  it('restores old drafts with inherited new fields and serializes explicit clear-to-inherit in PATCH', async () => {
    login()
    const legacy = { name: 'My draft', modelName: 'local-model', systemPrompt: 'Use evidence', maxTokens: '9000' }
    const restored = restoreAgentDraft(legacy)
    expect(restored).toMatchObject({ ...legacy, decisionMaxOutputTokens: '', thinkingMode: '' })
    const custom = buildAgentConfig({ ...restored, decisionMaxOutputTokens: '4096', finalMaxOutputTokens: '4096',
      decisionResponseFormat: 'JSON_OBJECT', thinkingMode: 'PROVIDER_DEFAULT', modelCallTimeoutSeconds: '90' })
    const inherited = buildAgentConfig({ ...draftFromAgent(custom), decisionMaxOutputTokens: ' ', finalMaxOutputTokens: '',
      decisionResponseFormat: '', thinkingMode: '', modelCallTimeoutSeconds: '' })
    const send = vi.spyOn(http, 'request').mockResolvedValue(reply({ ...inherited, id: '7', status: 'ACTIVE', createdAt: '', updatedAt: '' }))
    await agentApi.update('7', inherited)
    expect(send.mock.calls[0]![0].data).toMatchObject({ decisionMaxOutputTokens: null, finalMaxOutputTokens: null,
      decisionResponseFormat: null, thinkingMode: null, modelCallTimeoutSeconds: null })
    expect(sameAgentConfig(custom, inherited)).toBe(false)
    for (const key of ['decisionMaxOutputTokens', 'finalMaxOutputTokens', 'decisionResponseFormat', 'thinkingMode', 'modelCallTimeoutSeconds'] as const) {
      expect(sameAgentConfig(inherited, { ...inherited, [key]: custom[key] })).toBe(false)
    }
  })

  it('checks platform limits, final reserve, strict numeric syntax and model compatibility', () => {
    const policy = options()
    expect([256, 8000, 100000].map(finalAnswerReserve)).toEqual([64, 2000, 2048])
    expect(validateAgentDraft({ ...draft(), decisionMaxOutputTokens: '16384', finalMaxOutputTokens: '2000', modelCallTimeoutSeconds: '600' }, policy)).toBe('')
    expect(validateAgentDraft({ ...draft(), finalMaxOutputTokens: '1999' }, policy)).toContain('2000')
    expect(validateAgentDraft({ ...draft(), decisionMaxOutputTokens: '16385' }, policy)).toContain('16384')
    expect(validateAgentDraft({ ...draft(), modelCallTimeoutSeconds: '601' }, policy)).toContain('600')
    for (const value of ['0', '-1', '2.5', '1e3', 'Infinity', '9007199254740992']) {
      expect(validateAgentDraft({ ...draft(), decisionMaxOutputTokens: value }, policy)).not.toBe('')
    }
    expect(validateAgentDraft({ ...draft(), decisionResponseFormat: 'JSON_SCHEMA' }, policy)).toContain('未开放')
    expect(validateAgentDraft({ ...draft(), thinkingMode: 'DISABLED' }, policy)).toContain('未验证')
    expect(validateAgentDraft({ ...draft(), decisionResponseFormat: 'JSON_OBJECT', thinkingMode: 'PROVIDER_DEFAULT' }, policy)).toBe('')
    expect(validateAgentDraft(draft(), options('other-model'))).toContain('模型已改变')
  })

  it('reads authenticated model-specific policy and rejects malformed or incompatible defaults', async () => {
    login()
    const policy = options('vendor/model name'), { finalMaxOutputTokens: _final, ...defaults } = policy.defaults
    const send = vi.spyOn(http, 'request').mockResolvedValue(reply({ ...policy, defaults }))
    expect((await agentApi.executionOptions(policy.modelName)).defaults.finalMaxOutputTokens).toBeNull()
    expect(send.mock.calls[0]![0]).toMatchObject({ method: 'GET', url: '/agents/execution-options?modelName=vendor%2Fmodel%20name', headers: { Authorization: 'Bearer token' } })
    for (const invalid of [options('wrong-model'), { ...policy, limits: { ...policy.limits, maxOutputTokens: '16384' } },
      { ...policy, capabilities: { ...policy.capabilities, jsonObject: 'false' } },
      { ...policy, defaults: { ...policy.defaults, thinkingMode: 'DISABLED' } }]) {
      send.mockResolvedValue(reply(invalid))
      await expect(agentApi.executionOptions(policy.modelName)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', outcomeUnknown: false })
    }
  })

  it('normalizes omitted nullable response fields but rejects malformed advanced writes as unknown', async () => {
    login()
    const config = buildAgentConfig(draft())
    const wire: Record<string, unknown> = { ...config, id: '7', status: 'ACTIVE', createdAt: '', updatedAt: '' }
    for (const key of ['decisionMaxOutputTokens', 'finalMaxOutputTokens', 'decisionResponseFormat', 'thinkingMode', 'modelCallTimeoutSeconds']) delete wire[key]
    const send = vi.spyOn(http, 'request').mockResolvedValue(reply(wire))
    expect(sameAgentConfig(await agentApi.get('7'), config)).toBe(true)
    send.mockResolvedValue(reply({ ...wire, decisionMaxOutputTokens: '4096' }))
    await expect(agentApi.update('7', config)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', outcomeUnknown: true })
    send.mockResolvedValue(reply({ ...wire, thinkingMode: 'UNKNOWN' }))
    await expect(agentApi.get('7')).rejects.toMatchObject({ code: 'INVALID_RESPONSE', outcomeUnknown: false })
  })
})

describe('model-specific execution policy loading', () => {
  it.each(['create', 'edit'] as const)('disables the %s submit button through debounce and a pending policy read', async mode => {
    login(); vi.useFakeTimers()
    let resolve!: (value: AgentExecutionOptions) => void
    vi.spyOn(agentApi, 'executionOptions').mockImplementation(() => new Promise(done => { resolve = done }))
    vi.spyOn(agentApi, 'list').mockResolvedValue({ items: [], page: 1, pageSize: 20, total: '0', hasNext: false })
    vi.spyOn(agentApi, 'get').mockResolvedValue({ ...buildAgentConfig(draft()), id: '7', status: 'ACTIVE', createdAt: '', updatedAt: '' })
    vi.spyOn(agentApi, 'getKnowledgeBindings').mockResolvedValue({ knowledgeBaseIds: [] })
    vi.spyOn(agentApi, 'getToolBindings').mockResolvedValue({ toolIds: [] })
    vi.spyOn(agentApi, 'listTools').mockResolvedValue([])
    vi.spyOn(knowledgeApi, 'listBases').mockResolvedValue({ items: [], page: 1, pageSize: 20, total: '0', hasNext: false })
    const component = mode === 'create' ? AgentsPage : AgentPage
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/agents/:agentId?', component }] })
    await router.push(mode === 'create' ? '/agents' : '/agents/7')
    const container = document.createElement('div'), app = createApp(component).use(router)
    app.mount(container)
    try {
      await nextTick(); await nextTick()
      if (mode === 'create') {
        const model = container.querySelector<HTMLInputElement>('#agent-model')!
        model.value = 'local-model'; model.dispatchEvent(new Event('input')); await nextTick()
      }
      const button = container.querySelector<HTMLButtonElement>('[data-testid="agent-config"] form > button')!
      expect(button.disabled).toBe(true)
      await vi.advanceTimersByTimeAsync(250)
      expect(button.disabled).toBe(true)
      resolve(options()); await nextTick(); await nextTick()
      expect(button.disabled).toBe(false)
    } finally { app.unmount() }
  })

  it('debounces, immediately invalidates old options and ignores late responses after model changes', async () => {
    vi.useFakeTimers()
    let first!: (value: AgentExecutionOptions) => void
    const read = vi.fn().mockImplementationOnce(() => new Promise<AgentExecutionOptions>(resolve => { first = resolve }))
      .mockResolvedValueOnce(options('second'))
    const loader = executionOptionsLoader(read)
    try {
      loader.setModel('first'); await vi.advanceTimersByTimeAsync(250)
      const signal = read.mock.calls[0]![1] as AbortSignal
      loader.setModel('second')
      expect(signal.aborted).toBe(true)
      expect(loader.validate({ ...draft(), modelName: 'second' })).toContain('等待')
      await vi.advanceTimersByTimeAsync(250)
      expect(loader.state.options?.modelName).toBe('second')
      first(options('first')); await Promise.resolve()
      expect(loader.state.options?.modelName).toBe('second')
      loader.setModel('third'); loader.setModel('fourth')
      expect(loader.state.options).toBeUndefined()
      await vi.advanceTimersByTimeAsync(249)
      expect(read).toHaveBeenCalledTimes(2)
    } finally { loader.dispose() }
  })

  it('preserves unknown defaults on errors, supports read retry and prevents late updates after dispose', async () => {
    vi.useFakeTimers()
    const read = vi.fn().mockRejectedValueOnce(new Error('unavailable')).mockResolvedValueOnce(options())
    const loader = executionOptionsLoader(read)
    loader.setModel('local-model'); await vi.advanceTimersByTimeAsync(250)
    expect(loader.state.options).toBeUndefined()
    expect(loader.state.error).toBe('unavailable')
    expect(loader.validate(draft())).toContain('请重试')
    await loader.reload()
    expect(loader.validate(draft())).toBe('')
    let resolve!: (value: AgentExecutionOptions) => void
    read.mockImplementationOnce(() => new Promise<AgentExecutionOptions>(done => { resolve = done }))
    loader.setModel('later'); await vi.advanceTimersByTimeAsync(250)
    loader.dispose(); resolve(options('later')); await Promise.resolve()
    expect(loader.state.options).toBeUndefined()
  })
})

it('renders unsupported saved values without erasing them and shows the final reserve independently', async () => {
  const editing = reactive({ ...draft(), decisionResponseFormat: 'JSON_SCHEMA' as const, thinkingMode: 'DISABLED' as const })
  const state = reactive({ modelName: 'local-model', options: options(), loading: false, error: '' })
  const container = document.createElement('div'), app = createApp({ render: () => h(AgentConfigurationForm, { draft: editing, execution: state }) })
  app.mount(container)
  try {
    const format = container.querySelector<HTMLSelectElement>('#agent-decisionResponseFormat')!
    expect(format.value).toBe('JSON_SCHEMA')
    expect(format.selectedOptions[0]!.disabled).toBe(true)
    expect(editing.thinkingMode).toBe('DISABLED')
    expect(container.textContent).toContain('当前模型未开放 JSON Schema')
    Object.assign(editing, { decisionResponseFormat: '', thinkingMode: '' }); await nextTick()
    const summary = container.querySelector('[data-testid="agent-execution-effective"]')!
    expect(summary.textContent).toContain('512 tokens')
    expect(summary.textContent).toContain('2000 tokens')
    expect(summary.textContent).toContain('30 秒 / 120 秒')
  } finally { app.unmount() }
})

it('provides actionable model failure guidance while leaving unknown messages intact', () => {
  expect(taskFailureMessage('AGENT_LLM_OUTPUT_LIMIT', 'original')).toContain('输出上限')
  expect(taskFailureMessage('AGENT_LLM_EMPTY_RESPONSE')).toContain('未返回有效内容')
  expect(taskFailureMessage('AGENT_LLM_TIMEOUT')).toContain('单次模型调用超时')
  expect(taskFailureMessage('AGENT_LLM_REJECTED')).toContain('拒绝')
  expect(taskFailureMessage('AGENT_INVALID_DECISION')).toContain('决策输出格式')
  expect(taskFailureMessage('FUTURE_ERROR', 'Original evidence')).toBe('Original evidence')
})
