import { fetchEventSource } from '@microsoft/fetch-event-source'
import { API_BASE, ApiError } from './api'
import { decodeFrame, ProtocolError } from './events'
import { parseJson, resourceId, sequence } from './sequence'
import { clearSession, session } from './session'
import type { TaskEvent } from './types'

export interface StreamOptions {
  taskId: string; cursor: string; token: string; signal: AbortSignal
  onOpen: () => void; onEvent: (event: TaskEvent) => void
}

/** Exactly one transport attempt. The runtime owns retries and the acknowledged cursor. */
export async function readTaskStream(options: StreamOptions): Promise<void> {
  if (options.signal.aborted) return
  const cursor = sequence(options.cursor)
  await fetchEventSource(`${API_BASE}/tasks/${resourceId(options.taskId)}/events`, {
    method: 'GET', signal: options.signal, openWhenHidden: true,
    headers: { authorization: `Bearer ${options.token}`, accept: 'text/event-stream', 'last-event-id': cursor },
    // The library mutates its private headers as it reads IDs. Never expose that mutable
    // object to a fetch attempt, and force this attempt's acknowledged cursor explicitly.
    fetch: (input, init) => {
      const headers = new Headers(init?.headers)
      headers.set('last-event-id', cursor)
      return window.fetch(input, { ...init, headers })
    },
    async onopen(response) {
      if (!response.ok) {
        let code = 'HTTP_ERROR', message = `事件连接失败 (${response.status})`
        try {
          const data = parseJson(await response.text()) as { code?: string; message?: string }
          code = data.code ?? code; message = data.message ?? message
        } catch { /* Keep HTTP status when a proxy returned non-JSON. */ }
        if (response.status === 401 && session.token === options.token) clearSession()
        if (['TASK_EVENT_SEQUENCE_GAP', 'TASK_SSE_EVENT_TOO_LARGE'].includes(code)) throw new ProtocolError(message, code)
        throw new ApiError(message, response.status, code)
      }
      if (!(response.headers.get('content-type') ?? '').toLowerCase().startsWith('text/event-stream')) {
        throw new ProtocolError('事件连接响应类型错误')
      }
      options.onOpen()
    },
    onmessage(message) {
      if (options.signal.aborted) return
      if (!message.id && !message.event && !message.data) return // Heartbeat / connected comments.
      if (message.event === 'STREAM_ERROR') {
        if (message.id) throw new ProtocolError('STREAM_ERROR 不能携带事件游标')
        let data: { code?: string }
        try { data = parseJson(message.data) as { code?: string } } catch { throw new ProtocolError('无效 STREAM_ERROR') }
        if (data.code === 'TASK_SSE_READ_FAILED') throw new ApiError('持久事件暂时读取失败', 503, data.code)
        throw new ProtocolError(`服务端停止事件流：${data.code ?? 'UNKNOWN'}`, data.code)
      }
      options.onEvent(decodeFrame(message, options.taskId))
    },
    onclose() { /* EOF is checked against public GET by the runtime. */ },
    onerror(error) { throw error }, // Disable the library's automatic retries and implicit Last-Event-ID reuse.
  })
}
