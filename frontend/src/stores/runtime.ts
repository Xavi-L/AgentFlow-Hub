import { shallowReactive } from 'vue'
import { api, ApiError } from '../lib/api'
import { EventLedger, ProtocolError, rebuildTrace, stableJson } from '../lib/events'
import { compareSequence, resourceId } from '../lib/sequence'
import { hasSession, onSessionClear, session } from '../lib/session'
import { readTaskStream, type StreamOptions } from '../lib/stream'
import { isTerminal, type Json, type Task, type TaskEvent, type TaskStatus, type TaskTrace } from '../lib/types'

export type ConnectionState = 'idle' | 'loading' | 'connecting' | 'connected' | 'reconnecting' | 'offline' | 'stopped' | 'closed'
export interface RuntimeState {
  task: Task | null; trace: TaskTrace | null; events: TaskEvent[]; answer: string; citations: Json[]
  cursor: string; connection: ConnectionState; error: string; reconnects: number; loading: boolean; settled: boolean
}
interface RuntimeDependencies {
  api: Pick<typeof api, 'getTask' | 'getTrace' | 'cancelTask'>
  stream: (options: StreamOptions) => Promise<void>
  sleep: (milliseconds: number, signal: AbortSignal) => Promise<void>
  retryDelays: number[]
}
function sleep(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve()
    const done = () => { clearTimeout(timer); signal.removeEventListener('abort', done); resolve() }
    const timer = setTimeout(done, milliseconds)
    signal.addEventListener('abort', done, { once: true })
  })
}
function initialState(): RuntimeState {
  return { task: null, trace: null, events: [], answer: '', citations: [], cursor: '0', connection: 'idle', error: '', reconnects: 0, loading: false, settled: false }
}
function fatal(error: unknown): boolean {
  return error instanceof ProtocolError || error instanceof ApiError && (
    error.code === 'SESSION_CHANGED' || error.status >= 400 && error.status < 500 && ![408, 429].includes(error.status)
  )
}
function errorText(error: unknown): string { return error instanceof Error ? error.message : '任务连接失败' }

export function createTaskRuntime(overrides: Partial<RuntimeDependencies> = {}) {
  const deps = { api, stream: readTaskStream, sleep, retryDelays: [1000, 2000, 4000, 8000, 16000], ...overrides }
  const state = shallowReactive<RuntimeState>(initialState())
  let epoch = 0
  let controller: AbortController | undefined
  let streamController: AbortController | undefined
  let delayController: AbortController | undefined
  let networkOffline = typeof navigator !== 'undefined' && !navigator.onLine
  let ledger: EventLedger | undefined
  let activeTaskId: string | undefined
  let settling: Promise<void> | undefined
  let disposed = false
  const current = (run: number) => !disposed && run === epoch && !controller?.signal.aborted && hasSession()

  function stop() {
    epoch++
    streamController?.abort()
    delayController?.abort()
    controller?.abort()
    settling = undefined
  }
  const removeSessionListener = onSessionClear(() => {
    stop(); ledger = undefined; activeTaskId = undefined
    Object.assign(state, initialState())
  })
  const pagehide = () => { stop(); state.connection = 'closed' }
  const pageshow = (event: PageTransitionEvent) => { if (event.persisted && activeTaskId && !disposed) void open(activeTaskId) }
  const offline = () => {
    networkOffline = true
    if (!disposed && controller && !controller.signal.aborted && !state.settled) {
      state.connection = 'offline'
      streamController?.abort()
    }
  }
  const online = () => {
    networkOffline = false
    // Wake the existing bounded loop. Never reset its attempt budget or create a second stream.
    delayController?.abort()
  }
  if (typeof window !== 'undefined') {
    window.addEventListener('pagehide', pagehide)
    window.addEventListener('pageshow', pageshow)
    window.addEventListener('offline', offline)
    window.addEventListener('online', online)
  }

  function publishTrace(trace: TaskTrace) {
    const restored = rebuildTrace(trace, activeTaskId!)
    // Publish only after the entire Trace validates, never save a cursor without its reconstructed UI.
    ledger = restored
    state.task = { ...trace.task }
    state.trace = trace
    state.events = [...restored.events]
    state.answer = restored.answer
    state.citations = []
    state.cursor = restored.cursor
  }

  async function settle(run: number): Promise<void> {
    if (settling) return settling
    const signal = controller!.signal
    const id = activeTaskId!
    const pending = (async () => {
      const task = await deps.api.getTask(id, signal)
      if (!current(run)) return
      if (!isTerminal(task.status)) throw new ProtocolError('终态事件与公开任务状态不一致')
      const trace = await deps.api.getTrace(id, signal)
      if (!current(run)) return
      if (trace.task.status !== task.status || trace.task.lastEventSequence !== task.lastEventSequence
        || trace.task.terminationReason !== task.terminationReason || trace.task.errorCode !== task.errorCode
        || stableJson(trace.task.recovery ?? null) !== stableJson(task.recovery ?? null)
        || trace.task.finalAnswer !== task.finalAnswer || stableJson(trace.task.citations) !== stableJson(task.citations)) {
        throw new ProtocolError('终态任务与 Trace 不一致，请重新加载核对')
      }
      publishTrace(trace)
      state.task = task
      state.answer = task.finalAnswer ?? ''
      state.citations = task.citations
      state.settled = true
      state.connection = 'closed'
      state.error = ''
    })().finally(() => { if (settling === pending) settling = undefined })
    settling = pending
    return pending
  }

  function applyEvent(event: TaskEvent) {
    ledger!.apply(event, (applied, answer) => {
      const task = { ...state.task! }
      // A cancellation GET/POST may observe a newer committed snapshot before SSE replays it.
      // Apply historical events to the timeline without rolling that snapshot's fields back.
      if (compareSequence(applied.sequenceNo, task.lastEventSequence) >= 0) {
        if (applied.eventType === 'TASK_CREATED' || applied.eventType === 'TASK_STARTED' || isTerminalEvent(applied)) {
          task.status = applied.payload.status as TaskStatus
        }
        if (applied.eventType === 'TASK_STARTED' || applied.eventType === 'PHASE_CHANGED') task.phase = applied.payload.phase as string
        if (isTerminalEvent(applied)) task.terminationReason = applied.payload.terminationReason as string
        if (applied.payload.errorCode && isTerminalEvent(applied)) task.errorCode = applied.payload.errorCode as string
        task.lastEventSequence = applied.sequenceNo
        task.updatedAt = applied.createdAt
      }
      state.answer = answer
      state.events = [...state.events, applied]
      state.task = task
    })
    state.cursor = ledger!.cursor
  }

  async function pump(run: number): Promise<void> {
    while (current(run)) {
      try {
        if (networkOffline) throw new ApiError('网络已断开，等待恢复', 0, 'OFFLINE')
        if (isTerminal(state.task?.status)) { await settle(run); return }
        streamController = new AbortController()
        const connection = streamController
        const outer = controller!.signal
        const abort = () => connection.abort()
        outer.addEventListener('abort', abort, { once: true })
        state.connection = state.reconnects ? 'reconnecting' : 'connecting'
        try {
          await deps.stream({ taskId: activeTaskId!, cursor: ledger!.cursor, token: session.token!, signal: connection.signal,
            onOpen: () => { if (current(run)) { state.connection = 'connected'; state.error = '' } },
            onEvent: (event) => {
              if (!current(run) || connection.signal.aborted) return
              applyEvent(event)
              if (isTerminalEvent(event)) connection.abort()
            },
          })
        } finally { outer.removeEventListener('abort', abort) }
        if (!current(run)) return
        if (networkOffline) throw new ApiError('网络已断开，等待恢复', 0, 'OFFLINE')
        if (isTerminal(state.task?.status)) { await settle(run); return }
        // A normal EOF is not proof of task completion. Check durable state before reconnecting.
        const latest = await deps.api.getTask(activeTaskId!, controller!.signal)
        if (!current(run)) return
        if (isTerminal(latest.status)) { await settle(run); return }
        throw new ApiError('事件连接已关闭，正在恢复', 0, 'STREAM_CLOSED')
      } catch (error) {
        if (!current(run)) return
        streamController?.abort()
        state.error = errorText(error)
        if (fatal(error) || state.reconnects >= deps.retryDelays.length) { state.connection = 'stopped'; return }
        const delay = deps.retryDelays[state.reconnects]!
        state.reconnects++
        state.connection = networkOffline ? 'offline' : 'reconnecting'
        const delayAbort = new AbortController()
        delayController = delayAbort
        const signal = controller!.signal
        const abort = () => delayAbort.abort()
        signal.addEventListener('abort', abort, { once: true })
        try { await deps.sleep(delay, delayAbort.signal) } finally {
          signal.removeEventListener('abort', abort)
          if (delayController === delayAbort) delayController = undefined
        }
      }
    }
  }

  async function open(taskId: string): Promise<void> {
    stop()
    if (disposed) return
    activeTaskId = undefined
    Object.assign(state, initialState(), { loading: true, connection: 'loading' })
    controller = new AbortController()
    const run = epoch
    try {
      activeTaskId = resourceId(taskId)
      if (!hasSession()) throw new ApiError('请先登录', 401)
      const trace = await deps.api.getTrace(taskId, controller.signal)
      if (!current(run)) return
      publishTrace(trace)
      state.loading = false
      void pump(run)
    } catch (error) {
      if (run !== epoch || disposed) return
      state.error = errorText(error); state.loading = false; state.connection = 'stopped'
    }
  }

  async function cancel(): Promise<void> {
    if (!state.task || !controller || isTerminal(state.task.status)) return
    const run = epoch
    try {
      const task = await deps.api.cancelTask(state.task.taskId, controller.signal)
      if (!current(run)) return
      if (compareSequence(task.lastEventSequence, state.task.lastEventSequence) >= 0) {
        state.task = task
        if (isTerminal(task.status)) {
          streamController?.abort()
          await settle(run)
        }
      }
    } catch (error) {
      if (current(run)) state.error = errorText(error)
      throw error
    }
  }

  function dispose() {
    disposed = true; stop(); removeSessionListener()
    if (typeof window !== 'undefined') {
      window.removeEventListener('pagehide', pagehide)
      window.removeEventListener('pageshow', pageshow)
      window.removeEventListener('offline', offline)
      window.removeEventListener('online', online)
    }
    ledger = undefined; activeTaskId = undefined
    Object.assign(state, initialState(), { connection: 'closed' })
  }
  return { state, open, cancel, retry: () => activeTaskId ? open(activeTaskId) : Promise.resolve(), dispose }
}

function isTerminalEvent(event: TaskEvent): boolean {
  return ['TASK_COMPLETED', 'TASK_FAILED', 'TASK_CANCELLED', 'TASK_TIMED_OUT'].includes(event.eventType)
}
