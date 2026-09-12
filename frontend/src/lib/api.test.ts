import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, http } from './api'
import { clearSession, session, setSession } from './session'

const login = () => setSession({ accessToken: 'token', tokenType: 'Bearer', expiresIn: 3600, user: { id: '1', username: 'test', displayName: 'Test', role: 'USER' } })
afterEach(() => { clearSession(); vi.restoreAllMocks() })
describe('authenticated API boundaries', () => {
  it('treats the explicit admission rejection as a definite no-write result, while retaining unknown other 5xx writes', async () => {
    login()
    const request = vi.spyOn(http, 'request').mockResolvedValue({ status: 503,
      data: '{"code":"TASK_EXECUTION_NOT_READY","message":"执行服务尚未就绪","data":null}' })
    await expect(api.createTask('7', 'input', 'gate-key')).rejects.toMatchObject({ status: 503, code: 'TASK_EXECUTION_NOT_READY', outcomeUnknown: false })
    await expect(api.cancelTask('9')).rejects.toMatchObject({ outcomeUnknown: false })
    request.mockResolvedValue({ status: 503, data: '{"code":"DEPENDENCY_UNAVAILABLE","data":null}' })
    await expect(api.createTask('7', 'input', 'other-key')).rejects.toMatchObject({ outcomeUnknown: true })
    expect(request).toHaveBeenCalledTimes(3)
  })

  it('uses the lossless parser before normalizing a task sequence', async () => {
    login()
    vi.spyOn(http, 'request').mockResolvedValue({ status: 200, data: '{"code":"OK","data":{"taskId":"9223372036854775806","agentId":"7","lastEventSequence":9007199254740993,"citations":[]}}' })
    expect((await api.getTask('9223372036854775806')).lastEventSequence).toBe('9007199254740993')
  })

  it('preserves the same persisted configuration in Task and Trace without inventing historical identity', async () => {
    login()
    const configuration = { configVersionId: '9223372036854775805', configHash: 'a'.repeat(64),
      effectiveConfigHash: 'b'.repeat(64), hashAlgorithmVersion: 'config-canonical-json-v1' }
    const task = { taskId: '9', agentId: '7', lastEventSequence: 1, citations: [], configuration }
    const request = vi.spyOn(http, 'request').mockResolvedValue({ status: 200, data: JSON.stringify({ code: 'OK', data: task }) })
    expect((await api.getTask('9')).configuration).toEqual(configuration)
    const executionSnapshot = { snapshotVersion: 'agent-task-snapshot-v2', agent: { systemPrompt: ' historical prompt ' } }
    request.mockResolvedValue({ status: 200, data: JSON.stringify({ code: 'OK', data: { task, executionSnapshot, events: [], steps: [] } }) })
    const trace = await api.getTrace('9')
    expect(trace.task.configuration).toEqual(configuration)
    expect(trace.executionSnapshot).toEqual(executionSnapshot)
    for (const historical of [{}, { configuration: null }]) {
      request.mockResolvedValue({ status: 200, data: JSON.stringify({ code: 'OK',
        data: { taskId: '9', agentId: '7', lastEventSequence: 1, citations: [], ...historical } }) })
      const result = await api.createTask('7', ' input with space ', 'original-key')
      expect(result.configuration).toBe(historical.configuration)
      expect(request.mock.lastCall![0]).toMatchObject({ data: { userInput: ' input with space ' },
        headers: { 'Idempotency-Key': 'original-key' } })
    }
  })

  it('aborts every active HTTP request on logout and rejects a late creation response', async () => {
    login()
    let resolve!: (value: unknown) => void
    const request = vi.spyOn(http, 'request').mockImplementation(() => new Promise((done) => { resolve = done }))
    const creating = api.createTask('7', 'input', 'same-key')
    const signal = request.mock.calls[0]![0].signal!
    expect(signal.aborted).toBe(false)
    clearSession()
    expect(signal.aborted).toBe(true)
    resolve({ status: 201, data: '{"code":"OK","data":{"taskId":"9","agentId":"7","lastEventSequence":1,"citations":[]}}' })
    await expect(creating).rejects.toThrow(/登录状态/)
    expect(session.token).toBeNull()
  })

  it('invalidates authentication on 401 while preserving a definite client error classification', async () => {
    login()
    vi.spyOn(http, 'request').mockResolvedValue({ status: 401, data: '{"code":"AUTH_TOKEN_INVALID","message":"expired","data":null}' })
    await expect(api.listTasks()).rejects.toMatchObject({ status: 401, code: 'AUTH_TOKEN_INVALID', outcomeUnknown: false })
    expect(session.token).toBeNull()
  })
})
