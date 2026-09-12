import { ApiError, request } from './api'
import { resourceId, sequence } from './sequence'
import type { Page } from './types'

export const AGENT_PROVIDER = 'openai-compatible' as const
export const MAX_AGENT_KNOWLEDGE_BINDINGS = 20
export type DecisionResponseFormat = 'PROMPT_ONLY' | 'JSON_OBJECT' | 'JSON_SCHEMA'
export type ThinkingMode = 'PROVIDER_DEFAULT' | 'DISABLED'
export interface AgentExecutionOverrides {
  decisionMaxOutputTokens: number | null; finalMaxOutputTokens: number | null
  decisionResponseFormat: DecisionResponseFormat | null; thinkingMode: ThinkingMode | null
  modelCallTimeoutSeconds: number | null
}
export interface AgentExecutionOptions {
  policyVersion: 'agent-execution-policy-v1'; modelName: string
  defaults: { decisionMaxOutputTokens: number; finalMaxOutputTokens: number | null
    decisionResponseFormat: DecisionResponseFormat; thinkingMode: ThinkingMode; modelCallTimeoutSeconds: number }
  limits: { maxOutputTokens: number; maxModelCallTimeoutSeconds: number }
  capabilities: { jsonObject: boolean; jsonSchema: boolean; disableThinking: boolean }
}
const advancedNumbers = ['decisionMaxOutputTokens', 'finalMaxOutputTokens', 'modelCallTimeoutSeconds'] as const
const advancedKeys = [...advancedNumbers, 'decisionResponseFormat', 'thinkingMode'] as const
const formats = ['PROMPT_ONLY', 'JSON_OBJECT', 'JSON_SCHEMA'] as const
const thinkingModes = ['PROVIDER_DEFAULT', 'DISABLED'] as const
export interface AgentConfig extends AgentExecutionOverrides {
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
  decisionMaxOutputTokens: string; finalMaxOutputTokens: string; modelCallTimeoutSeconds: string
  decisionResponseFormat: '' | DecisionResponseFormat; thinkingMode: '' | ThinkingMode
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
    maxSteps: '6', maxToolCalls: '4', maxTokens: '8000', timeoutSeconds: '120',
    decisionMaxOutputTokens: '', finalMaxOutputTokens: '', decisionResponseFormat: '', thinkingMode: '', modelCallTimeoutSeconds: '' }
}

/** New fields inherit when restoring a draft saved before advanced settings existed. */
export function restoreAgentDraft(saved?: Partial<AgentDraft>): AgentDraft {
  return { ...defaultAgentDraft(), ...saved }
}

export function draftFromAgent(agent: AgentConfig): AgentDraft {
  return { name: agent.name, description: agent.description ?? '', systemPrompt: agent.systemPrompt,
    modelName: agent.modelName, temperature: String(agent.temperature), topP: String(agent.topP),
    maxSteps: String(agent.maxSteps), maxToolCalls: String(agent.maxToolCalls), maxTokens: String(agent.maxTokens),
    timeoutSeconds: String(agent.timeoutSeconds),
    decisionMaxOutputTokens: agent.decisionMaxOutputTokens == null ? '' : String(agent.decisionMaxOutputTokens),
    finalMaxOutputTokens: agent.finalMaxOutputTokens == null ? '' : String(agent.finalMaxOutputTokens),
    modelCallTimeoutSeconds: agent.modelCallTimeoutSeconds == null ? '' : String(agent.modelCallTimeoutSeconds),
    decisionResponseFormat: agent.decisionResponseFormat ?? '', thinkingMode: agent.thinkingMode ?? '' }
}

function decimalInput(value: string, min: number, max: number, exclusiveMin = false): boolean {
  return /^(?:\d+(?:\.\d{0,3})?|\.\d{1,3})$/.test(value.trim())
    && Number(value) <= max && (exclusiveMin ? Number(value) > min : Number(value) >= min)
}
function integerInput(value: string, min: number, max: number): boolean {
  return /^\d+$/.test(value.trim()) && Number.isSafeInteger(Number(value)) && Number(value) >= min && Number(value) <= max
}

export const finalAnswerReserve = (maxTokens: number): number => Math.min(2048, Math.max(1, Math.floor(maxTokens / 4)))

export function validateAgentAdvancedDraft(draft: AgentDraft, options?: AgentExecutionOptions): string {
  for (const key of advancedNumbers) {
    if (typeof draft[key] !== 'string' || (draft[key].trim() && !integerInput(draft[key], 1, Number.MAX_SAFE_INTEGER))) {
      return `${key} 必须为正整数，留空继承部署默认值`
    }
  }
  if (draft.decisionResponseFormat !== '' && !formats.includes(draft.decisionResponseFormat)) return '请选择受支持的决策输出格式或继承默认'
  if (draft.thinkingMode !== '' && !thinkingModes.includes(draft.thinkingMode)) return '请选择受支持的思考策略或继承默认'
  if (draft.finalMaxOutputTokens.trim() && Number(draft.finalMaxOutputTokens) < finalAnswerReserve(Number(draft.maxTokens))) {
    return `最终回答输出上限不能低于最终回答预留预算 ${finalAnswerReserve(Number(draft.maxTokens))} tokens`
  }
  if (!options) return ''
  if (options.modelName !== draft.modelName.trim()) return '模型已改变，请等待读取当前模型的高级设置'
  if ([draft.decisionMaxOutputTokens, draft.finalMaxOutputTokens].some(value => value.trim() && Number(value) > options.limits.maxOutputTokens)) {
    return `单次输出上限不能超过平台允许的 ${options.limits.maxOutputTokens} tokens`
  }
  if (draft.modelCallTimeoutSeconds.trim() && Number(draft.modelCallTimeoutSeconds) > options.limits.maxModelCallTimeoutSeconds) {
    return `单次模型调用超时不能超过平台允许的 ${options.limits.maxModelCallTimeoutSeconds} 秒`
  }
  if (draft.decisionResponseFormat === 'JSON_OBJECT' && !options.capabilities.jsonObject) return '当前模型未开放 JSON 模式，请明确选择其他格式或继承默认'
  if (draft.decisionResponseFormat === 'JSON_SCHEMA' && !options.capabilities.jsonSchema) return '当前模型未开放 JSON Schema，请明确选择其他格式或继承默认'
  if (draft.thinkingMode === 'DISABLED' && !options.capabilities.disableThinking) return '当前模型未验证关闭思考的能力，请明确选择模型默认或继承默认'
  return ''
}

export function validateAgentDraft(draft: AgentDraft, options?: AgentExecutionOptions): string {
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
  return validateAgentAdvancedDraft(draft, options)
}

export function buildAgentConfig(draft: AgentDraft): AgentConfig {
  const error = validateAgentDraft(draft)
  if (error) throw new ApiError(error, 400, 'COMMON_PARAM_INVALID')
  return { name: draft.name.trim(), description: draft.description.trim() || null, systemPrompt: draft.systemPrompt,
    modelProvider: AGENT_PROVIDER, modelName: draft.modelName.trim(), temperature: Number(draft.temperature),
    topP: Number(draft.topP), maxSteps: Number(draft.maxSteps), maxToolCalls: Number(draft.maxToolCalls),
    maxTokens: Number(draft.maxTokens), timeoutSeconds: Number(draft.timeoutSeconds),
    decisionMaxOutputTokens: draft.decisionMaxOutputTokens.trim() ? Number(draft.decisionMaxOutputTokens) : null,
    finalMaxOutputTokens: draft.finalMaxOutputTokens.trim() ? Number(draft.finalMaxOutputTokens) : null,
    decisionResponseFormat: draft.decisionResponseFormat || null, thinkingMode: draft.thinkingMode || null,
    modelCallTimeoutSeconds: draft.modelCallTimeoutSeconds.trim() ? Number(draft.modelCallTimeoutSeconds) : null }
}

/** Compare only the submitted public config after the server's documented text normalization. */
export function sameAgentConfig(actual: AgentConfig, expected: AgentConfig): boolean {
  return actual.modelProvider === expected.modelProvider && actual.name === expected.name
    && actual.description === expected.description && actual.systemPrompt === expected.systemPrompt
    && actual.modelName === expected.modelName && actual.temperature === expected.temperature && actual.topP === expected.topP
    && actual.maxSteps === expected.maxSteps && actual.maxToolCalls === expected.maxToolCalls
    && actual.maxTokens === expected.maxTokens && actual.timeoutSeconds === expected.timeoutSeconds
    && advancedKeys.every(key => (actual[key] ?? null) === (expected[key] ?? null))
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
  // Jackson NON_NULL omits nullable fields from public projections.
  if (value.description === undefined) value.description = null
  if (typeof value.name !== 'string' || !(value.description === null || typeof value.description === 'string')
    || typeof value.modelProvider !== 'string' || typeof value.modelName !== 'string'
    || !['ACTIVE', 'DISABLED'].includes(value.status) || typeof value.createdAt !== 'string'
    || typeof value.updatedAt !== 'string') throw new Error('Invalid Agent summary')
}
function detail(value: AgentDetail): void {
  summary(value)
  for (const key of advancedKeys) if (value[key] === undefined) Object.assign(value, { [key]: null })
  if (advancedNumbers.some(key => value[key] !== null && (typeof value[key] !== 'number' || !Number.isSafeInteger(value[key]) || value[key]! < 1))
    || (value.decisionResponseFormat !== null && !formats.includes(value.decisionResponseFormat))
    || (value.thinkingMode !== null && !thinkingModes.includes(value.thinkingMode))) throw new Error('Invalid Agent execution options')
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

function executionOptions(value: AgentExecutionOptions, modelName: string): void {
  if (value.policyVersion !== 'agent-execution-policy-v1' || value.modelName !== modelName
    || !value.defaults || !value.limits || !value.capabilities) throw new Error('Invalid execution policy')
  if (value.defaults.finalMaxOutputTokens === undefined) value.defaults.finalMaxOutputTokens = null
  const positive = (n: unknown): n is number => typeof n === 'number' && Number.isSafeInteger(n) && n > 0
  if (![value.limits.maxOutputTokens, value.limits.maxModelCallTimeoutSeconds, value.defaults.decisionMaxOutputTokens, value.defaults.modelCallTimeoutSeconds].every(positive)
    || (value.defaults.finalMaxOutputTokens !== null && !positive(value.defaults.finalMaxOutputTokens))
    || value.defaults.decisionMaxOutputTokens > value.limits.maxOutputTokens
    || (value.defaults.finalMaxOutputTokens ?? 0) > value.limits.maxOutputTokens
    || value.defaults.modelCallTimeoutSeconds > value.limits.maxModelCallTimeoutSeconds
    || !formats.includes(value.defaults.decisionResponseFormat) || !thinkingModes.includes(value.defaults.thinkingMode)
    || ['jsonObject', 'jsonSchema', 'disableThinking'].some(key => typeof value.capabilities[key as keyof typeof value.capabilities] !== 'boolean')
    || (value.defaults.decisionResponseFormat === 'JSON_OBJECT' && !value.capabilities.jsonObject)
    || (value.defaults.decisionResponseFormat === 'JSON_SCHEMA' && !value.capabilities.jsonSchema)
    || (value.defaults.thinkingMode === 'DISABLED' && !value.capabilities.disableThinking)) throw new Error('Invalid execution policy')
}

export const agentApi = {
  async executionOptions(modelName: string, signal?: AbortSignal): Promise<AgentExecutionOptions> {
    const model = modelName.trim()
    return checked(await request('GET', `/agents/execution-options?modelName=${encodeURIComponent(model)}`, undefined, { signal }),
      (value: AgentExecutionOptions) => executionOptions(value, model))
  },
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
