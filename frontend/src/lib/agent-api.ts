import { ApiError, request } from './api'
import { resourceId, sequence } from './sequence'
import type { Page } from './types'

export const AGENT_PROVIDER = 'openai-compatible' as const
export const MAX_AGENT_KNOWLEDGE_BINDINGS = 20
export interface AgentConfig {
  name: string; description: string | null; systemPrompt: string
  modelProvider: typeof AGENT_PROVIDER; modelName: string
  temperature: number; topP: number; maxSteps: number; maxToolCalls: number
  maxTokens: number; timeoutSeconds: number
}
export interface AgentSummary {
  id: string; name: string; description: string | null; modelProvider: string; modelName: string
  status: 'ACTIVE' | 'DISABLED'; createdAt: string; updatedAt: string
}
export interface AgentDetail extends AgentConfig {
  id: string; status: 'ACTIVE' | 'DISABLED'; createdAt: string; updatedAt: string
}
/** Keep incomplete numeric input as text; saving must never silently replace it with defaults. */
export interface AgentDraft {
  name: string; description: string; systemPrompt: string; modelName: string
  temperature: string; topP: string; maxSteps: string; maxToolCalls: string
  maxTokens: string; timeoutSeconds: string
}
export interface KnowledgeBindings { knowledgeBaseIds: string[] }
export interface ToolBindings { toolIds: string[] }
export interface ToolDefinition {
  id: string; toolCode: string; name: string; description: string | null; type: string
  inputSchema: unknown; outputSchema: unknown; timeoutMs: number; retryCount: number
  requiresConfirmation: boolean; permissionLevel: string; status: string
}

export function defaultAgentDraft(): AgentDraft {
  return { name: '', description: '', systemPrompt: '', modelName: '', temperature: '0.2', topP: '0.8',
    maxSteps: '6', maxToolCalls: '4', maxTokens: '8000', timeoutSeconds: '120' }
}

export function draftFromAgent(agent: AgentConfig): AgentDraft {
  return { name: agent.name, description: agent.description ?? '', systemPrompt: agent.systemPrompt,
    modelName: agent.modelName, temperature: String(agent.temperature), topP: String(agent.topP),
    maxSteps: String(agent.maxSteps), maxToolCalls: String(agent.maxToolCalls), maxTokens: String(agent.maxTokens),
    timeoutSeconds: String(agent.timeoutSeconds) }
}

function decimalInput(value: string, min: number, max: number, exclusiveMin = false): boolean {
  return /^(?:\d+(?:\.\d{0,3})?|\.\d{1,3})$/.test(value.trim())
    && Number(value) <= max && (exclusiveMin ? Number(value) > min : Number(value) >= min)
}
function integerInput(value: string, min: number, max: number): boolean {
  return /^\d+$/.test(value.trim()) && Number.isSafeInteger(Number(value)) && Number(value) >= min && Number(value) <= max
}

export function validateAgentDraft(draft: AgentDraft): string {
  if (!draft.name.trim() || draft.name.length > 128) return '名称必填，最多 128 个字符'
  if (draft.description.length > 4000) return '描述最多 4000 个字符'
  if (!draft.systemPrompt.trim() || draft.systemPrompt.length > 20000) return 'systemPrompt 必填，最多 20000 个字符'
  if (!draft.modelName.trim() || draft.modelName.length > 128) return 'modelName 必填，最多 128 个字符，请使用服务端已配置的模型名称'
  if (!decimalInput(draft.temperature, 0, 2)) return 'temperature 必须为 0–2，最多 3 位小数'
  if (!decimalInput(draft.topP, 0, 1, true)) return 'topP 必须大于 0 且不超过 1，最多 3 位小数'
  if (!integerInput(draft.maxSteps, 1, 20)) return 'maxSteps 必须为 1–20 的整数'
  if (!integerInput(draft.maxToolCalls, 0, 20)) return 'maxToolCalls 必须为 0–20 的整数'
  if (Number(draft.maxToolCalls) >= Number(draft.maxSteps)) return 'maxToolCalls 必须小于 maxSteps，为最终回答保留步骤'
  if (!integerInput(draft.maxTokens, 256, 100000)) return 'maxTokens 必须为 256–100000 的整数'
  if (!integerInput(draft.timeoutSeconds, 1, 600)) return 'timeoutSeconds 必须为 1–600 的整数'
  return ''
}

export function buildAgentConfig(draft: AgentDraft): AgentConfig {
  const error = validateAgentDraft(draft)
  if (error) throw new ApiError(error, 400, 'COMMON_PARAM_INVALID')
  return { name: draft.name.trim(), description: draft.description.trim() || null, systemPrompt: draft.systemPrompt,
    modelProvider: AGENT_PROVIDER, modelName: draft.modelName.trim(), temperature: Number(draft.temperature),
    topP: Number(draft.topP), maxSteps: Number(draft.maxSteps), maxToolCalls: Number(draft.maxToolCalls),
    maxTokens: Number(draft.maxTokens), timeoutSeconds: Number(draft.timeoutSeconds) }
}

/** Compare only the submitted public config after the server's documented text normalization. */
export function sameAgentConfig(actual: AgentConfig, expected: AgentConfig): boolean {
  return actual.modelProvider === expected.modelProvider && actual.name === expected.name
    && actual.description === expected.description && actual.systemPrompt === expected.systemPrompt
    && actual.modelName === expected.modelName && actual.temperature === expected.temperature && actual.topP === expected.topP
    && actual.maxSteps === expected.maxSteps && actual.maxToolCalls === expected.maxToolCalls
    && actual.maxTokens === expected.maxTokens && actual.timeoutSeconds === expected.timeoutSeconds
}

/** Binding order is execution priority, so reconciliation must compare order as well as membership. */
export const sameBindingIds = (actual: readonly string[], expected: readonly string[]): boolean =>
  actual.length === expected.length && actual.every((id, index) => id === expected[index])

/** Toggle one visible option without rebuilding the selection from the current page's rows. */
export function toggleBindingId(selected: readonly string[], id: string, checked: boolean): string[] {
  resourceId(id)
  return checked ? (selected.includes(id) ? [...selected] : [...selected, id]) : selected.filter(value => value !== id)
}

export const isSelectableTool = (tool: ToolDefinition): boolean =>
  ['order_query', 'payment_log_query'].includes(tool.toolCode) && tool.type === 'BUILTIN' && tool.status === 'ACTIVE'

export function validateToolSelection(ids: readonly string[], tools: readonly ToolDefinition[]): string {
  const allowed = new Set(tools.filter(isSelectableTool).map(tool => tool.id))
  return ids.some(id => !allowed.has(id)) ? '存在当前不可绑定的工具，请明确移除后再保存；仅支持 order_query 和 payment_log_query' : ''
}

function checked<T>(value: T, validate: (value: T) => void, write = false): T {
  try { validate(value); return value } catch {
    throw new ApiError('服务器响应无法确认，请读回核对最新记录', 0, 'INVALID_RESPONSE', write)
  }
}
function summary(value: AgentSummary): void {
  resourceId(value.id)
  // Jackson NON_NULL omits the only nullable Agent field from both public projections.
  if (value.description === undefined) value.description = null
  if (typeof value.name !== 'string' || !(value.description === null || typeof value.description === 'string')
    || typeof value.modelProvider !== 'string' || typeof value.modelName !== 'string'
    || !['ACTIVE', 'DISABLED'].includes(value.status) || typeof value.createdAt !== 'string'
    || typeof value.updatedAt !== 'string') throw new Error('Invalid Agent summary')
}
function detail(value: AgentDetail): void {
  summary(value)
  if (value.modelProvider !== AGENT_PROVIDER || typeof value.systemPrompt !== 'string'
    || ['temperature', 'topP', 'maxSteps', 'maxToolCalls', 'maxTokens', 'timeoutSeconds']
      .some(key => typeof value[key as keyof AgentConfig] !== 'number')
    || validateAgentDraft(draftFromAgent(value))) throw new Error('Invalid Agent config')
}
function bindings(ids: string[], maximum: number): void {
  if (!Array.isArray(ids) || ids.length > maximum) throw new Error('Invalid bindings')
  ids.forEach(resourceId)
  if (new Set(ids).size !== ids.length) throw new Error('Duplicate binding IDs')
}
function bindingBody(ids: readonly string[], maximum: number): string[] {
  try { bindings([...ids], maximum); return [...ids] } catch {
    throw new ApiError(`绑定 ID 必须为不重复的正整数文本，最多 ${maximum} 项`, 400, 'COMMON_PARAM_INVALID')
  }
}
function tool(value: ToolDefinition): void {
  resourceId(value.id)
  if (typeof value.toolCode !== 'string' || typeof value.name !== 'string' || typeof value.type !== 'string'
    || typeof value.status !== 'string' || !(value.description === null || typeof value.description === 'string')) {
    throw new Error('Invalid tool')
  }
}
const agentPath = (id: string) => `/agents/${resourceId(id)}`
// Build an allowlisted payload even if a caller passes a complete detail response.
const configBody = (value: AgentConfig): AgentConfig => buildAgentConfig(draftFromAgent(value))

export const agentApi = {
  async list(index = 1, signal?: AbortSignal): Promise<Page<AgentSummary>> {
    return checked(await request('GET', `/agents?page=${index}&pageSize=20`, undefined, { signal }), (value: Page<AgentSummary>) => {
      if (!Array.isArray(value.items) || value.page !== index || value.pageSize !== 20 || typeof value.hasNext !== 'boolean') {
        throw new Error('Invalid Agent page')
      }
      sequence(value.total)
      value.items.forEach(summary)
    })
  },
  async get(id: string, signal?: AbortSignal): Promise<AgentDetail> {
    return checked(await request('GET', agentPath(id), undefined, { signal }), (value: AgentDetail) => {
      detail(value); if (value.id !== id) throw new Error('Wrong Agent')
    })
  },
  async create(config: AgentConfig, signal?: AbortSignal): Promise<AgentDetail> {
    return checked(await request('POST', '/agents', configBody(config), { signal }), detail, true)
  },
  async update(id: string, config: AgentConfig, signal?: AbortSignal): Promise<AgentDetail> {
    return checked(await request('PATCH', agentPath(id), configBody(config), { signal }), (value: AgentDetail) => {
      detail(value); if (value.id !== id) throw new Error('Wrong Agent')
    }, true)
  },
  async setEnabled(id: string, enabled: boolean, signal?: AbortSignal): Promise<AgentDetail> {
    return checked(await request('POST', `${agentPath(id)}/${enabled ? 'enable' : 'disable'}`, undefined, { signal }), (value: AgentDetail) => {
      detail(value); if (value.id !== id || value.status !== (enabled ? 'ACTIVE' : 'DISABLED')) throw new Error('Wrong Agent state')
    }, true)
  },
  async getKnowledgeBindings(id: string, signal?: AbortSignal): Promise<KnowledgeBindings> {
    // Historical 21–50 bindings must remain readable so users can explicitly remove the excess.
    return checked(await request('GET', `${agentPath(id)}/knowledge-bases`, undefined, { signal }),
      (value: KnowledgeBindings) => bindings(value.knowledgeBaseIds, 50))
  },
  async replaceKnowledgeBindings(id: string, ids: readonly string[], signal?: AbortSignal): Promise<KnowledgeBindings> {
    return checked(await request('PUT', `${agentPath(id)}/knowledge-bases`, { knowledgeBaseIds: bindingBody(ids, MAX_AGENT_KNOWLEDGE_BINDINGS) }, { signal }),
      (value: KnowledgeBindings) => bindings(value.knowledgeBaseIds, MAX_AGENT_KNOWLEDGE_BINDINGS), true)
  },
  async getToolBindings(id: string, signal?: AbortSignal): Promise<ToolBindings> {
    return checked(await request('GET', `${agentPath(id)}/tools`, undefined, { signal }),
      (value: ToolBindings) => bindings(value.toolIds, 20))
  },
  async replaceToolBindings(id: string, ids: readonly string[], signal?: AbortSignal): Promise<ToolBindings> {
    return checked(await request('PUT', `${agentPath(id)}/tools`, { toolIds: bindingBody(ids, 20) }, { signal }),
      (value: ToolBindings) => bindings(value.toolIds, 20), true)
  },
  async listTools(signal?: AbortSignal): Promise<ToolDefinition[]> {
    return checked(await request('GET', '/tools', undefined, { signal }), (value: ToolDefinition[]) => {
      if (!Array.isArray(value)) throw new Error('Invalid tools')
      value.forEach(tool)
    })
  },
}
