import { afterEach, describe, expect, it, vi } from 'vitest'
import { readTaskStream } from './stream'
import { ProtocolError } from './events'
import { clearSession, session, setSession } from './session'

const taskId = '9223372036854775806'
const login = () => setSession({ accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 3600, user: { id: '1', username: 'test', displayName: 'Test', role: 'USER' } })
const response = (text: string) => new Response(new ReadableStream({ start(controller) {
  const bytes = new TextEncoder().encode(text)
  // Deliberately split in the middle of fields/UTF-8 bytes to exercise the actual stream parser.
  for (let index = 0; index < bytes.length; index += 7) controller.enqueue(bytes.slice(index, index + 7))
  controller.close()
} }), { headers: { 'Content-Type': 'text/event-stream' } })

afterEach(() => { clearSession(); vi.restoreAllMocks() })
describe('one-attempt Bearer SSE transport', () => {
  it('sends the acknowledged decimal cursor and handles fragmented persisted events plus comments', async () => {
    login()
    const fetch = vi.spyOn(window, 'fetch').mockResolvedValue(response(': heartbeat\n\nid: 9007199254740993\nevent: ANSWER_CHUNK\ndata: {"taskId":"9223372036854775806","sequenceNo":9007199254740993,"eventType":"ANSWER_CHUNK","timestamp":"2026-09-06T12:00:00Z","payload":{"chunkIndex":0,"text":"你好"}}\n\n'))
    const onEvent = vi.fn(), onOpen = vi.fn()
    await readTaskStream({ taskId, cursor: '9007199254740992', token: 'test-token', signal: new AbortController().signal, onEvent, onOpen })
    expect(fetch).toHaveBeenCalledOnce()
    const headers = new Headers(fetch.mock.calls[0]![1]?.headers)
    expect(headers.get('Authorization')).toBe('Bearer test-token')
    expect(headers.get('Last-Event-ID')).toBe('9007199254740992')
    expect(onOpen).toHaveBeenCalledOnce()
    expect(onEvent).toHaveBeenCalledOnce()
    expect(onEvent.mock.calls[0]![0]).toMatchObject({ sequenceNo: '9007199254740993', payload: { text: '你好' } })
  })

  it('rejects gap control frames without applying them or retrying inside the library', async () => {
    const fetch = vi.spyOn(window, 'fetch').mockResolvedValue(response('event: STREAM_ERROR\ndata: {"code":"TASK_EVENT_SEQUENCE_GAP","lastSentSequence":2}\n\n'))
    const onEvent = vi.fn()
    await expect(readTaskStream({ taskId, cursor: '1', token: 'token', signal: new AbortController().signal, onOpen: vi.fn(), onEvent })).rejects.toThrow(ProtocolError)
    expect(onEvent).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('clears the authenticated session on an initial 401', async () => {
    login()
    vi.spyOn(window, 'fetch').mockResolvedValue(new Response('{"code":"AUTH_TOKEN_INVALID","message":"Expired"}', { status: 401 }))
    await expect(readTaskStream({ taskId, cursor: '0', token: 'test-token', signal: new AbortController().signal, onOpen: vi.fn(), onEvent: vi.fn() })).rejects.toThrow('Expired')
    expect(session.token).toBeNull()
  })
})
