import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, http } from './api'
import { clearSession, session, setSession } from './session'

const login = () => setSession({ accessToken: 'token', tokenType: 'Bearer', expiresIn: 3600, user: { id: '1', username: 'test', displayName: 'Test', role: 'USER' } })
afterEach(() => { clearSession(); vi.restoreAllMocks() })
describe('authenticated API boundaries', () => {
  it('uses the lossless parser before normalizing a task sequence', async () => {
    login()
    vi.spyOn(http, 'request').mockResolvedValue({ status: 200, data: '{"code":"OK","data":{"taskId":"9223372036854775806","agentId":"7","lastEventSequence":9007199254740993,"citations":[]}}' })
    expect((await api.getTask('9223372036854775806')).lastEventSequence).toBe('9007199254740993')
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
