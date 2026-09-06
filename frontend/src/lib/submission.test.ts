import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from './api'
import { clearSession, setSession } from './session'
import { retrySubmission, submission, submitTask } from './submission'
import type { Task } from './types'

const login = () => setSession({ accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 3600, user: { id: '1', username: 'test', displayName: 'Test', role: 'USER' } })
afterEach(() => { clearSession(); vi.restoreAllMocks() })
describe('task creation idempotency', () => {
  it('keeps the original key and exact payload after an unknown outcome and rejects editing it', async () => {
    login()
    const create = vi.spyOn(api, 'createTask').mockRejectedValueOnce(new ApiError('network', 0, 'NETWORK_ERROR', true)).mockResolvedValueOnce({ taskId: '9' } as Task)
    await expect(submitTask('7', 'original input')).rejects.toThrow('network')
    const pending = { ...submission.pending! }
    expect(pending.key).toBeTruthy()
    expect(JSON.parse(sessionStorage.getItem('agentflow.pending-task.v1')!)).toEqual(pending)
    await expect(submitTask('7', 'changed input')).rejects.toThrow(/上一请求/)
    await retrySubmission()
    expect(create).toHaveBeenNthCalledWith(1, '7', 'original input', pending.key)
    expect(create).toHaveBeenNthCalledWith(2, '7', 'original input', pending.key)
    expect(submission.pending).toBeNull()
  })

  it('coalesces double clicks and clears unknown state on logout without accepting late replies', async () => {
    login()
    let resolve!: (task: Task) => void
    const create = vi.spyOn(api, 'createTask').mockReturnValue(new Promise<Task>((done) => { resolve = done }))
    const first = submitTask('7', 'same')
    expect(submitTask('7', 'same')).toBe(first)
    expect(create).toHaveBeenCalledOnce()
    clearSession()
    resolve({ taskId: '9' } as Task)
    await expect(first).rejects.toThrow(/登录状态/)
    expect(submission.pending).toBeNull()
    expect(submission.busy).toBe(false)
  })

  it('clears the pending key only for a definite rejection', async () => {
    login()
    vi.spyOn(api, 'createTask').mockRejectedValue(new ApiError('conflict', 409, 'TASK_IDEMPOTENCY_CONFLICT'))
    await expect(submitTask('7', 'input')).rejects.toThrow('conflict')
    expect(submission.pending).toBeNull()
  })
})
