import { reactive } from 'vue'
import { api, ApiError } from './api'
import { hasSession, onSessionClear, session, sessionRevision, storage } from './session'
import { resourceId } from './sequence'
import type { Task } from './types'

const STORAGE_KEY = 'agentflow.pending-task.v1'
export interface PendingSubmission { ownerId: string; agentId: string; userInput: string; key: string }
export const submission = reactive<{ pending: PendingSubmission | null; busy: boolean; error: string }>({ pending: null, busy: false, error: '' })
let inFlight: Promise<Task> | null = null

function savePending(pending: PendingSubmission | null): void {
  submission.pending = pending
  try {
    if (pending) storage()?.setItem(STORAGE_KEY, JSON.stringify(pending))
    else storage()?.removeItem(STORAGE_KEY)
  } catch { /* In-memory preservation still prevents duplicate submissions in this page. */ }
}
onSessionClear(() => { savePending(null); submission.busy = false; submission.error = ''; inFlight = null })
try {
  const saved = storage()?.getItem(STORAGE_KEY)
  const value = saved ? JSON.parse(saved) : null
  if (value && hasSession() && value.ownerId === session.user?.id && typeof value.userInput === 'string'
    && typeof value.key === 'string' && value.key && resourceId(value.agentId)) submission.pending = value
  else storage()?.removeItem(STORAGE_KEY)
} catch { savePending(null) }

export function abandonSubmission(): void {
  if (submission.busy) throw new Error('请求处理中，不能丢弃幂等键')
  savePending(null)
  submission.error = ''
}
export function submitTask(agentId: string, userInput: string): Promise<Task> {
  if (!hasSession() || !session.user) return Promise.reject(new ApiError('请先登录', 401))
  resourceId(agentId)
  const pending = submission.pending
  if (pending && (pending.agentId !== agentId || pending.userInput !== userInput || pending.ownerId !== session.user.id)) {
    return Promise.reject(new Error('上一请求结果未知，请先用原请求重试或明确放弃后再提交'))
  }
  if (inFlight) return inFlight
  if (!pending) savePending({ ownerId: session.user.id, agentId, userInput, key: crypto.randomUUID() })
  return sendPending()
}
export function retrySubmission(): Promise<Task> {
  if (!submission.pending) return Promise.reject(new Error('没有待确认的创建请求'))
  return submitTask(submission.pending.agentId, submission.pending.userInput)
}
function sendPending(): Promise<Task> {
  const pending = submission.pending!
  const version = sessionRevision()
  submission.busy = true
  submission.error = ''
  const attempt = api.createTask(pending.agentId, pending.userInput, pending.key).then((task) => {
    if (version !== sessionRevision()) throw new ApiError('登录状态已改变', 0, 'SESSION_CHANGED')
    savePending(null)
    return task
  }).catch((error: unknown) => {
    if (version === sessionRevision()) {
      submission.error = error instanceof Error ? error.message : '创建失败'
      if (error instanceof ApiError && !error.outcomeUnknown) savePending(null)
    }
    throw error
  }).finally(() => {
    if (inFlight === attempt) { submission.busy = false; inFlight = null }
  })
  inFlight = attempt
  return attempt
}
