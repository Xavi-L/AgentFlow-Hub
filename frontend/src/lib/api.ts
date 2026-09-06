import axios from 'axios'
import { parseJson, resourceId, sequence } from './sequence'
import { clearSession, hasSession, onSessionClear, session, sessionRevision, setSession } from './session'
import type { Agent, LoginResponse, Page, Task, TaskSummary, TaskTrace } from './types'

export const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api/v1'
interface Envelope<T> { code: string; message: string; data: T; traceId?: string }
export class ApiError extends Error {
  constructor(message: string, public status = 0, public code = 'NETWORK_ERROR', public outcomeUnknown = false) { super(message) }
}
export const http = axios.create({ baseURL: API_BASE, timeout: 20000, responseType: 'text', transformResponse: [(body) => body] })
const activeRequests = new Set<AbortController>()
onSessionClear(() => { activeRequests.forEach((controller) => controller.abort()); activeRequests.clear() })

async function request<T>(method: 'GET' | 'POST', url: string, body?: unknown, options: { signal?: AbortSignal; key?: string; public?: boolean } = {}): Promise<T> {
  const version = sessionRevision()
  if (!options.public && !hasSession()) throw new ApiError('请先登录', 401, 'AUTH_UNAUTHENTICATED')
  const token = session.token
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (options.signal?.aborted) controller.abort()
  else options.signal?.addEventListener('abort', abort, { once: true })
  activeRequests.add(controller)
  try {
    const response = await http.request<string>({ method, url, data: body, signal: controller.signal, headers: {
      ...(token && !options.public ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.key ? { 'Idempotency-Key': options.key } : {}),
    }, validateStatus: () => true })
    if (version !== sessionRevision()) throw new ApiError('登录状态已改变', 0, 'SESSION_CHANGED')
    let envelope: Envelope<T>
    try { envelope = parseJson(response.data) as Envelope<T> } catch {
      if (response.status === 401 && !options.public && session.token === token) clearSession()
      throw new ApiError('服务器响应无法解析', response.status, 'INVALID_RESPONSE', method === 'POST' && response.status !== 401)
    }
    if (response.status === 401 && !options.public && session.token === token) clearSession()
    if (response.status < 200 || response.status >= 300 || envelope.code !== 'OK') {
      throw new ApiError(envelope.message || `请求失败 (${response.status})`, response.status, envelope.code || 'HTTP_ERROR', method === 'POST' && response.status >= 500)
    }
    return envelope.data
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (version !== sessionRevision()) throw new ApiError('登录状态已改变', 0, 'SESSION_CHANGED')
    throw new ApiError(axios.isCancel(error) ? '请求已取消' : '网络连接失败，服务器结果尚未确认', 0, axios.isCancel(error) ? 'ABORTED' : 'NETWORK_ERROR', method === 'POST')
  } finally {
    activeRequests.delete(controller)
    options.signal?.removeEventListener('abort', abort)
  }
}

function normalizeTask(task: Task): Task {
  resourceId(task.taskId); resourceId(task.agentId)
  task.lastEventSequence = sequence(task.lastEventSequence)
  if (!Array.isArray(task.citations)) throw new ApiError('任务引用格式错误', 0, 'INVALID_RESPONSE')
  return task
}

export const api = {
  async login(username: string, password: string): Promise<LoginResponse> {
    const result = await request<LoginResponse>('POST', '/auth/login', { username, password }, { public: true })
    setSession(result)
    return result
  },
  listAgents: (page = 1) => request<Page<Agent>>('GET', `/agents?page=${page}&pageSize=20`),
  listTasks: (page = 1) => request<Page<TaskSummary>>('GET', `/tasks?page=${page}&pageSize=20`),
  async getTask(id: string, signal?: AbortSignal): Promise<Task> {
    return normalizeTask(await request<Task>('GET', `/tasks/${resourceId(id)}`, undefined, { signal }))
  },
  async getTrace(id: string, signal?: AbortSignal): Promise<TaskTrace> {
    const trace = await request<TaskTrace>('GET', `/tasks/${resourceId(id)}/trace`, undefined, { signal })
    normalizeTask(trace.task)
    trace.events.forEach((event) => { event.sequenceNo = sequence(event.sequenceNo) })
    return trace
  },
  async cancelTask(id: string, signal?: AbortSignal): Promise<Task> {
    return normalizeTask(await request<Task>('POST', `/tasks/${resourceId(id)}/cancel`, undefined, { signal }))
  },
  async createTask(agentId: string, userInput: string, key: string, signal?: AbortSignal): Promise<Task> {
    const task = await request<Task>('POST', `/agents/${resourceId(agentId)}/tasks`, { userInput }, { signal, key })
    try { return normalizeTask(task) } catch { throw new ApiError('创建响应无法确认，请使用原请求重试', 0, 'INVALID_RESPONSE', true) }
  },
}
