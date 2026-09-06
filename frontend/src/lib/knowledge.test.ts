import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, http } from './api'
import { knowledgeApi, uploadError } from './knowledge-api'
import { boundedPoll, knowledgeMutation, POLL_INTERVAL, POLL_LIMIT, requestScope } from './knowledge-requests'
import { clearSession, setSession, storage } from './session'

const login = () => setSession({ accessToken: 'token', tokenType: 'Bearer', expiresIn: 3600, user: { id: '1', username: 'test', displayName: 'Test', role: 'USER' } })
const base = { id: '9007199254740993', name: 'Test', status: 'ACTIVE', embeddingProfileCode: null, chunkStrategyVersion: 'structured-token-v1' }
const rawDocument = '{"id":"9007199254740995","knowledgeBaseId":"9007199254740993","fileName":"a.md","parseStatus":"COMPLETED","vectorGeneration":9007199254740993,"vectorization":{"pending":0,"processing":0,"completed":9007199254740994,"failed":0},"retrievalReadiness":"READY"}'
afterEach(() => { clearSession(); vi.useRealTimers(); vi.restoreAllMocks() })

describe('knowledge wire contract', () => {
  it('sends only name/description and one multipart file with Bearer; never an idempotency key', async () => {
    login()
    const send = vi.spyOn(http, 'request').mockResolvedValue({ status: 201, data: JSON.stringify({ code: 'OK', data: base }) })
    await knowledgeApi.createBase('Test', 'Description')
    expect(send.mock.calls[0]![0]).toMatchObject({ data: { name: 'Test', description: 'Description' }, headers: { Authorization: 'Bearer token' } })
    expect(send.mock.calls[0]![0].headers).not.toHaveProperty('Idempotency-Key')
    send.mockResolvedValue({ status: 201, data: `{"code":"OK","data":${rawDocument}}` })
    await knowledgeApi.upload(base.id, new File(['hello'], 'a.md'))
    const data = send.mock.calls[1]![0].data as FormData
    expect(Array.from(data.keys())).toEqual(['file'])
    expect((data.get('file') as File).name).toBe('a.md')
  })

  it('preserves numeric long generation/counts and rejects mismatched resources or missing readiness', async () => {
    login()
    const send = vi.spyOn(http, 'request').mockResolvedValue({ status: 200, data: `{"code":"OK","data":${rawDocument}}` })
    const document = await knowledgeApi.getDocument(base.id, '9007199254740995')
    expect(document.vectorGeneration).toBe('9007199254740993')
    expect(document.vectorization.completed).toBe('9007199254740994')
    await expect(knowledgeApi.getDocument('7', document.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    await expect(knowledgeApi.getDocument(base.id, '8')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    send.mockResolvedValue({ status: 200, data: `{"code":"OK","data":${rawDocument.replace('"READY"', 'null')}}` })
    await expect(knowledgeApi.getDocument(base.id, document.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('classifies malformed successful writes as unknown and validates files without guessing server size limits', async () => {
    login()
    vi.spyOn(http, 'request').mockResolvedValue({ status: 201, data: '{"code":"OK","data":{}}' })
    await expect(knowledgeApi.createBase('Test', '')).rejects.toMatchObject({ outcomeUnknown: true })
    expect(uploadError(new File(['x'], 'x.pdf'))).toContain('TXT / MD')
    expect(uploadError(new File([], 'x.md'))).toContain('不能为空')
    expect(uploadError(new File(['x'], 'x.MD'))).toBe('')
  })
})

describe('page requests and non-idempotent writes', () => {
  it('aborts superseded reads, leaving pages and logout; stale successes cannot be applied', () => {
    login()
    const scope = requestScope(), first = scope.start('list'), detail = scope.start('detail')
    const second = scope.start('list')
    expect(first.signal.aborted).toBe(true); expect(first.current()).toBe(false)
    expect(detail.current()).toBe(true); expect(second.current()).toBe(true)
    scope.dispose()
    expect(detail.signal.aborted).toBe(true); expect(second.current()).toBe(false)
    const next = requestScope(), reading = next.start('list')
    clearSession(); expect(reading.signal.aborted).toBe(true); expect(reading.current()).toBe(false)
  })

  it('merges double clicks and keeps an unknown upload across page recreation without replay', async () => {
    login()
    const scope = requestScope(), mutation = knowledgeMutation(scope, 'upload:7')
    let fail!: (error: Error) => void
    const action = vi.fn(() => new Promise<never>((_, reject) => { fail = reject }))
    const first = mutation.run('上传', action)
    await mutation.run('上传', action)
    expect(action).toHaveBeenCalledTimes(1)
    fail(new ApiError('timeout', 0, 'NETWORK_ERROR', true))
    await first
    expect(mutation.unknown()).toBe('上传')
    scope.dispose()
    const secondScope = requestScope(), restored = knowledgeMutation(secondScope, 'upload:7')
    await restored.run('上传', action)
    expect(action).toHaveBeenCalledTimes(1)
    expect(storage()?.getItem('agentflow.knowledge-writes.v1')).toContain('upload:7')
    restored.acknowledge(); expect(restored.unknown()).toBeUndefined()
    secondScope.dispose()
  })

  it('keeps a write unknown after leaving even if a transport ignores abort; clears it on logout', async () => {
    login()
    const scope = requestScope(), mutation = knowledgeMutation(scope, 'create-base')
    let resolve!: (id: string) => void
    const creating = mutation.run('创建', () => new Promise<string>(done => { resolve = done }))
    scope.dispose(); resolve('7')
    expect(await creating).toBeUndefined()
    expect(mutation.unknown()).toBe('创建')
    clearSession(); expect(storage()?.getItem('agentflow.knowledge-writes.v1')).toBeNull()
  })

  it('definite 4xx permits a corrected submission; 5xx does not', async () => {
    login()
    const scope = requestScope(), mutation = knowledgeMutation(scope, 'create-base')
    await mutation.run('创建', async () => { throw new ApiError('Invalid name', 400, 'COMMON_PARAM_INVALID') })
    expect(mutation.unknown()).toBeUndefined()
    expect(mutation.state.error).toBe('Invalid name')
    await mutation.run('创建', async () => { throw new ApiError('Unavailable', 503, 'HTTP_ERROR', true) })
    expect(mutation.unknown()).toBe('创建')
    scope.dispose()
  })
})

describe('bounded observation', () => {
  it('stops at the observation budget and never mutates a document into failure', async () => {
    vi.useFakeTimers()
    const read = vi.fn(async () => true)
    const poll = boundedPoll(read, () => true)
    poll.start(true)
    await vi.advanceTimersByTimeAsync(120000)
    expect(read.mock.calls.length).toBeGreaterThan(0)
    expect(read.mock.calls.length).toBeLessThanOrEqual(POLL_LIMIT)
    expect(poll.state.running).toBe(false)
    expect(poll.state.message).toContain('结果仍以服务端为准')
    const before = read.mock.calls.length
    await vi.advanceTimersByTimeAsync(60000)
    expect(read).toHaveBeenCalledTimes(before)
  })

  it('stops on a failed/terminal read, cancels on leave and isolates a late poll from a new observation window', async () => {
    vi.useFakeTimers()
    let resolve!: (active: boolean) => void
    const read = vi.fn(() => new Promise<boolean>(done => { resolve = done }))
    const poll = boundedPoll(read, () => true)
    poll.start(true); await vi.advanceTimersByTimeAsync(POLL_INTERVAL)
    poll.start(false); resolve(true); await Promise.resolve()
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL * 2)
    expect(read).toHaveBeenCalledTimes(1)
    const terminal = boundedPoll(async () => false, () => true)
    terminal.start(true); await vi.advanceTimersByTimeAsync(POLL_INTERVAL)
    expect(terminal.state.running).toBe(false)
    terminal.stop(); poll.stop()
  })
})
