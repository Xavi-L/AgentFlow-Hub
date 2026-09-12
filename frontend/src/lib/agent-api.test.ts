import { afterEach, describe, expect, it, vi } from 'vitest'
import { http } from './api'
import {
  agentApi, buildAgentConfig, defaultAgentDraft, draftFromAgent, isSelectableTool, sameAgentConfig,
  sameBindingIds, toggleBindingId, validateAgentDraft, validateToolSelection,
  type AgentDetail, type AgentDraft, type ToolDefinition,
} from './agent-api'
import { clearSession, setSession } from './session'

const login = () => setSession({ accessToken: 'token', tokenType: 'Bearer', expiresIn: 3600,
  user: { id: '1', username: 'test', displayName: 'Test', role: 'USER' } })
const validDraft = (): AgentDraft => ({ ...defaultAgentDraft(), name: ' Agent ', description: ' description ',
  systemPrompt: '  Preserve this prompt\n', modelName: ' configured-model ' })
const detail = (): AgentDetail => ({ ...buildAgentConfig(validDraft()), id: '9007199254740993', status: 'ACTIVE',
  createdAt: '2026-09-06T00:00:00Z', updatedAt: '2026-09-06T00:00:00Z' })
const tool = (id: string, toolCode = 'order_query'): ToolDefinition => ({ id, toolCode, name: toolCode,
  description: null, type: 'BUILTIN', inputSchema: {}, outputSchema: {}, timeoutMs: 3000, retryCount: 0,
  requiresConfirmation: false, permissionLevel: 'READ_ONLY', status: 'ACTIVE' })
const reply = (data: unknown, status = 200) => ({ status, data: JSON.stringify({ code: 'OK', data }) })

afterEach(() => { clearSession(); vi.restoreAllMocks() })

describe('Agent configuration and dependency boundaries', () => {
  it('uses backend defaults, public budgets, a fixed provider and exact prompt text', () => {
    const draft = validDraft(), config = buildAgentConfig(draft)
    expect(config).toEqual({ name: 'Agent', description: 'description', systemPrompt: '  Preserve this prompt\n',
      modelProvider: 'openai-compatible', modelName: 'configured-model', temperature: 0.2, topP: 0.8,
      maxSteps: 6, maxToolCalls: 4, maxTokens: 8000, timeoutSeconds: 120, decisionMaxOutputTokens: null,
      finalMaxOutputTokens: null, decisionResponseFormat: null, thinkingMode: null, modelCallTimeoutSeconds: null })
    expect(config).not.toHaveProperty('maxDecisionTurns')
    expect(config).not.toHaveProperty('maxTotalTokens')
    expect(buildAgentConfig({ ...draft, description: '  ' }).description).toBeNull()
    expect(sameAgentConfig(detail(), config)).toBe(true)
    expect(sameAgentConfig({ ...detail(), systemPrompt: config.systemPrompt.trim() }, config)).toBe(false)
    expect(draftFromAgent(config).systemPrompt).toBe(draft.systemPrompt)
  })

  it.each([
    ['name', ' '], ['name', 'x'.repeat(129)], ['description', 'x'.repeat(4001)],
    ['systemPrompt', '\n '], ['systemPrompt', 'x'.repeat(20001)], ['modelName', ''],
    ['temperature', '2.001'], ['temperature', '-0.001'], ['temperature', '0.0001'],
    ['topP', '0'], ['topP', '1.001'], ['maxSteps', '21'], ['maxSteps', '0'],
    ['maxToolCalls', '6'], ['maxToolCalls', '-1'], ['maxTokens', '255'], ['maxTokens', '100001'],
    ['timeoutSeconds', '0'], ['timeoutSeconds', '601'], ['maxSteps', '6.1'],
    ['maxTokens', '1e3'], ['temperature', 'NaN'], ['topP', ''],
  ] as const)('rejects invalid or incomplete %s=%s without replacing it with a default', (field, value) => {
    const draft = { ...validDraft(), [field]: value }
    expect(validateAgentDraft(draft)).not.toBe('')
    expect(() => buildAgentConfig(draft)).toThrow()
    expect(draft[field]).toBe(value)
  })

  it('accepts both ends of the budget and precision bounds with a final-answer step reserved', () => {
    expect(validateAgentDraft({ ...validDraft(), temperature: '0', topP: '0.001', maxSteps: '1',
      maxToolCalls: '0', maxTokens: '256', timeoutSeconds: '1' })).toBe('')
    expect(validateAgentDraft({ ...validDraft(), temperature: '2.000', topP: '1', maxSteps: '20',
      maxToolCalls: '19', maxTokens: '100000', timeoutSeconds: '600' })).toBe('')
  })

  it('retains cross-page and unresolved selections in priority order until explicitly removed', () => {
    const unresolved = '9223372036854775806'
    const selected = toggleBindingId([unresolved, '7'], '9007199254740993', true)
    expect(selected).toEqual([unresolved, '7', '9007199254740993'])
    expect(toggleBindingId(selected, '7', true)).toEqual(selected)
    expect(toggleBindingId(selected, '7', false)).toEqual([unresolved, '9007199254740993'])
    expect(sameBindingIds(['7', unresolved], [unresolved, '7'])).toBe(false)
  })

  it('uses discovered IDs and allows only the two active built-in tools', () => {
    const list = [tool('9007199254740993'), tool('9007199254740995', 'payment_log_query'), tool('1', 'report_generate'),
      { ...tool('2'), type: 'HTTP' }, { ...tool('3'), status: 'DISABLED' }]
    expect(list.filter(isSelectableTool).map(value => value.id)).toEqual(['9007199254740993', '9007199254740995'])
    expect(validateToolSelection(['9007199254740993'], list)).toBe('')
    expect(validateToolSelection(['1'], list)).not.toBe('')
    expect(validateToolSelection(['999'], list)).not.toBe('')
  })
})

describe('Agent API wire contract', () => {
  it.each([0, 20])('writes %i knowledge bindings without changing their priority order', async count => {
    login()
    const ids = Array.from({ length: count }, (_, index) => String(9007199254740993n + BigInt(index)))
    const send = vi.spyOn(http, 'request').mockResolvedValue(reply({ knowledgeBaseIds: ids }))
    expect((await agentApi.replaceKnowledgeBindings('7', ids)).knowledgeBaseIds).toEqual(ids)
    expect(send).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      method: 'PUT', url: '/agents/7/knowledge-bases', data: { knowledgeBaseIds: ids },
    }))
  })

  it('rejects 21 knowledge bindings locally without sending or reporting an unknown outcome', async () => {
    login()
    const ids = Array.from({ length: 21 }, (_, index) => String(index + 1))
    const send = vi.spyOn(http, 'request')
    await expect(agentApi.replaceKnowledgeBindings('7', ids)).rejects.toMatchObject({
      status: 400, code: 'COMMON_PARAM_INVALID', outcomeUnknown: false, message: expect.stringContaining('最多 20 项'),
    })
    expect(send).not.toHaveBeenCalled()
  })

  it.each([21, 50])('keeps all %i historical knowledge bindings readable and removable in priority order', async count => {
    login()
    const ids = Array.from({ length: count }, (_, index) => String(9007199254740993n + BigInt(index)))
    const send = vi.spyOn(http, 'request').mockResolvedValue(reply({ knowledgeBaseIds: ids }))
    const restored = (await agentApi.getKnowledgeBindings('7')).knowledgeBaseIds
    expect(restored).toEqual(ids)
    expect(toggleBindingId(restored, ids.at(-1)!, false)).toEqual(ids.slice(0, -1))
    expect(send).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ method: 'GET', url: '/agents/7/knowledge-bases' }))
  })

  it('normalizes omitted NON_NULL descriptions in actual summary/detail response shapes', async () => {
    login()
    const summary = { id: '460000000000000021', name: 'Pagination Agent 20', modelProvider: 'openai-compatible',
      modelName: 'v43-controlled-model', status: 'ACTIVE', createdAt: '2026-01-01T08:00:00+08:00', updatedAt: '2026-01-01T08:00:00+08:00' }
    const send = vi.spyOn(http, 'request').mockResolvedValue(reply({ items: [summary], page: 1, pageSize: 20, total: 21, hasNext: true }))
    expect((await agentApi.list()).items[0]).toEqual({ ...summary, description: null })
    const actualDetail = { ...summary, id: '460000000000000001', name: 'Retained invalid binding',
      systemPrompt: 'Explain recorded payment facts', temperature: 0.2, topP: 0.8, maxSteps: 6, maxToolCalls: 4,
      maxTokens: 8000, timeoutSeconds: 120 }
    send.mockResolvedValue(reply(actualDetail))
    const agent = await agentApi.get(actualDetail.id)
    expect(agent.description).toBeNull()
    expect(draftFromAgent(agent).description).toBe('')
  })

  it('confirms blank-description writes and readback without treating an omitted description as malformed', async () => {
    login()
    const config = buildAgentConfig({ ...validDraft(), description: '' })
    const { description: _description, ...wireAgent } = { ...detail(), ...config }
    const send = vi.spyOn(http, 'request').mockResolvedValue(reply(wireAgent, 201))
    expect((await agentApi.create(config)).description).toBeNull()
    send.mockResolvedValue(reply(wireAgent))
    const updated = await agentApi.update(wireAgent.id, config)
    expect(sameAgentConfig(updated, config)).toBe(true)
    expect(sameAgentConfig(await agentApi.get(wireAgent.id), config)).toBe(true)
    send.mockResolvedValue(reply({ ...wireAgent, description: false }))
    await expect(agentApi.get(wireAgent.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('sends allowlisted config with Bearer and keeps PATCH separate from each binding PUT', async () => {
    login()
    const agent = detail(), send = vi.spyOn(http, 'request').mockResolvedValue(reply(agent, 201))
    await agentApi.create(agent)
    expect(send.mock.calls[0]![0]).toMatchObject({ method: 'POST', url: '/agents', data: buildAgentConfig(validDraft()),
      headers: { Authorization: 'Bearer token' } })
    expect(send.mock.calls[0]![0].data).not.toHaveProperty('id')
    expect(send.mock.calls[0]![0].headers).not.toHaveProperty('Idempotency-Key')
    await agentApi.update(agent.id, agent)
    expect(send.mock.calls[1]![0]).toMatchObject({ method: 'PATCH', url: `/agents/${agent.id}` })
    send.mockResolvedValue(reply({ knowledgeBaseIds: ['9223372036854775806', '7'] }))
    await agentApi.replaceKnowledgeBindings(agent.id, ['9223372036854775806', '7'])
    expect(send.mock.calls[2]![0]).toMatchObject({ method: 'PUT', url: `/agents/${agent.id}/knowledge-bases`,
      data: { knowledgeBaseIds: ['9223372036854775806', '7'] } })
    send.mockResolvedValue(reply({ toolIds: ['9007199254740995'] }))
    await agentApi.replaceToolBindings(agent.id, ['9007199254740995'])
    expect(send.mock.calls[3]![0]).toMatchObject({ method: 'PUT', url: `/agents/${agent.id}/tools`, data: { toolIds: ['9007199254740995'] } })
    expect(send).toHaveBeenCalledTimes(4)
  })

  it('reads paginated metadata, full config, tool discovery and unresolved binding IDs losslessly', async () => {
    login()
    const agent = detail(), send = vi.spyOn(http, 'request').mockResolvedValue(reply({ items: [agent], page: 2, pageSize: 20,
      total: '9007199254740997', hasNext: true }))
    expect((await agentApi.list(2)).items[0]!.id).toBe(agent.id)
    expect(send.mock.calls[0]![0].url).toBe('/agents?page=2&pageSize=20')
    send.mockResolvedValue(reply(agent)); expect((await agentApi.get(agent.id)).id).toBe(agent.id)
    send.mockResolvedValue(reply({ knowledgeBaseIds: ['9223372036854775806', '7'] }))
    expect((await agentApi.getKnowledgeBindings(agent.id)).knowledgeBaseIds).toEqual(['9223372036854775806', '7'])
    send.mockResolvedValue(reply({ toolIds: ['9007199254740995'] }))
    expect((await agentApi.getToolBindings(agent.id)).toolIds).toEqual(['9007199254740995'])
    send.mockResolvedValue(reply([tool('9007199254740995')]))
    expect((await agentApi.listTools())[0]!.id).toBe('9007199254740995')
  })

  it('rejects mismatched resources, numeric IDs, malformed config and missing binding arrays', async () => {
    login()
    const agent = detail(), send = vi.spyOn(http, 'request').mockResolvedValue(reply(agent))
    await expect(agentApi.get('7')).rejects.toMatchObject({ code: 'INVALID_RESPONSE', outcomeUnknown: false })
    send.mockResolvedValue(reply({ ...agent, id: 7 }))
    await expect(agentApi.create(agent)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', outcomeUnknown: true })
    send.mockResolvedValue(reply({ ...agent, maxToolCalls: agent.maxSteps }))
    await expect(agentApi.update(agent.id, agent)).rejects.toMatchObject({ code: 'INVALID_RESPONSE', outcomeUnknown: true })
    send.mockResolvedValue(reply({}))
    await expect(agentApi.getKnowledgeBindings(agent.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    await expect(agentApi.replaceToolBindings(agent.id, ['7'])).rejects.toMatchObject({ code: 'INVALID_RESPONSE', outcomeUnknown: true })
  })

  it('classifies network/5xx PATCH and PUT outcomes as unknown and does not replay them', async () => {
    login()
    const agent = detail(), send = vi.spyOn(http, 'request').mockRejectedValue(new Error('lost reply'))
    await expect(agentApi.update(agent.id, agent)).rejects.toMatchObject({ outcomeUnknown: true })
    expect(send).toHaveBeenCalledTimes(1)
    send.mockResolvedValue({ status: 503, data: '{"code":"UNAVAILABLE","message":"Unavailable"}' })
    await expect(agentApi.replaceKnowledgeBindings(agent.id, ['7'])).rejects.toMatchObject({ outcomeUnknown: true })
    expect(send).toHaveBeenCalledTimes(2)
    send.mockResolvedValue({ status: 400, data: '{"code":"AGENT_BINDING_INVALID","message":"Invalid binding"}' })
    await expect(agentApi.replaceToolBindings(agent.id, ['7'])).rejects.toMatchObject({ outcomeUnknown: false })
  })

  it('propagates page cancellation to every request and rejects late responses after logout', async () => {
    login()
    const controller = new AbortController()
    let resolve!: (value: unknown) => void
    const send = vi.spyOn(http, 'request').mockImplementation(() => new Promise(done => { resolve = done }))
    const reading = agentApi.getKnowledgeBindings('7', controller.signal)
    const requestSignal = send.mock.calls[0]![0].signal!
    controller.abort(); expect(requestSignal.aborted).toBe(true)
    clearSession(); resolve(reply({ knowledgeBaseIds: ['7'] }))
    await expect(reading).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
  })

  it('enables/disables only the selected Agent and checks the resulting state', async () => {
    login()
    const agent = detail(), send = vi.spyOn(http, 'request').mockResolvedValue(reply({ ...agent, status: 'DISABLED' }))
    await agentApi.setEnabled(agent.id, false)
    expect(send.mock.calls[0]![0]).toMatchObject({ method: 'POST', url: `/agents/${agent.id}/disable`, data: undefined })
    send.mockResolvedValue(reply(agent))
    await agentApi.setEnabled(agent.id, true)
    expect(send.mock.calls[1]![0].url).toBe(`/agents/${agent.id}/enable`)
    expect(send).toHaveBeenCalledTimes(2)
  })
})
