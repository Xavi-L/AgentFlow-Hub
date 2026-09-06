import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createTaskRuntime } from './runtime'
import { ApiError } from '../lib/api'
import { clearSession, setSession } from '../lib/session'
import type { StreamOptions } from '../lib/stream'
import type { Task, TaskEvent, TaskTrace } from '../lib/types'

const ID = '9223372036854775806'
const event = (sequenceNo: string, eventType: TaskEvent['eventType'], payload: TaskEvent['payload']): TaskEvent => ({
  taskId: ID, sequenceNo, eventType, payload, createdAt: '2026-09-06T12:00:00Z',
})
const created = event('1', 'TASK_CREATED', { status: 'QUEUED' })
const started = event('2', 'TASK_STARTED', { status: 'RUNNING', phase: 'PREPARING' })
const chunk = event('3', 'ANSWER_CHUNK', { chunkIndex: 0, text: 'draft' })
const terminal = event('5', 'TASK_COMPLETED', { status: 'COMPLETED', terminationReason: 'SUCCESS' })
function task(lastEventSequence = '3', status: Task['status'] = 'RUNNING'): Task {
  return { taskId: ID, agentId: '7', status, phase: 'GENERATING', lastEventSequence, finalAnswer: null, citations: [], userInput: 'input',
    createdAt: created.createdAt, updatedAt: created.createdAt, completedAt: null, terminationReason: null,
    maxDecisionTurns: 5, maxToolCalls: 3, maxTotalTokens: 2000, reservedFinalTokens: 200,
    decisionTurnsUsed: 0, toolCallsUsed: 0, inputTokens: 0, outputTokens: 0, totalTokens: 0,
    tokenUsageQuality: 'UNKNOWN', errorCode: null, errorMessage: null, cancelRequestedAt: null, startedAt: null,
  }
}
function trace(events: TaskEvent[] = [created, started, chunk], value = task()): TaskTrace {
  return { task: value, executionSnapshot: {}, steps: [], events }
}
const live = new Set<ReturnType<typeof createTaskRuntime>>()
function make(options: Parameters<typeof createTaskRuntime>[0]) {
  const runtime = createTaskRuntime(options)
  live.add(runtime)
  return runtime
}
function pendingStream(options: StreamOptions): Promise<void> {
  options.onOpen()
  return new Promise((resolve) => options.signal.addEventListener('abort', () => resolve(), { once: true }))
}
beforeEach(() => {
  setSession({ accessToken: 'token', tokenType: 'Bearer', expiresIn: 3600, user: { id: '1', username: 'test', displayName: 'Test', role: 'USER' } })
})
afterEach(() => { live.forEach((runtime) => runtime.dispose()); live.clear(); clearSession(); vi.restoreAllMocks() })

describe('task runtime recovery', () => {
  it('rebuilds applied events from Trace, deduplicates replay, then converges on public GET answer/citations', async () => {
    const final = { ...task('5', 'COMPLETED'), finalAnswer: 'Authoritative answer', citations: [{ citationId: 'S1' }] }
    const tail = event('4', 'ANSWER_CHUNK', { chunkIndex: 1, text: ' stream' })
    const finalTrace = trace([created, started, chunk, tail, terminal], final)
    const getTrace = vi.fn().mockResolvedValueOnce(trace()).mockResolvedValue(finalTrace)
    const stream = vi.fn(async (options: StreamOptions) => {
      options.onOpen(); options.onEvent(chunk); options.onEvent(tail); options.onEvent(terminal)
    })
    const runtime = make({ api: { getTrace, getTask: vi.fn().mockResolvedValue(final), cancelTask: vi.fn() }, stream })
    await runtime.open(ID)
    await vi.waitFor(() => expect(runtime.state.settled).toBe(true))
    expect(stream.mock.calls[0]![0].cursor).toBe('3')
    expect(runtime.state.cursor).toBe('5')
    expect(runtime.state.events.map((item) => item.sequenceNo)).toEqual(['1', '2', '3', '4', '5'])
    expect(runtime.state.answer).toBe('Authoritative answer')
    expect(runtime.state.citations).toEqual(finalTrace.task.citations)
    expect(runtime.state.connection).toBe('closed')
    expect(runtime.state.trace).toEqual(finalTrace)
  })

  it('stops on a gap and preserves the last successful cursor without GET-based skipping', async () => {
    const getTask = vi.fn()
    const stream = vi.fn(async (options: StreamOptions) => options.onEvent(terminal))
    const runtime = make({ api: { getTrace: vi.fn().mockResolvedValue(trace()), getTask, cancelTask: vi.fn() }, stream })
    await runtime.open(ID)
    await vi.waitFor(() => expect(runtime.state.connection).toBe('stopped'))
    expect(runtime.state.cursor).toBe('3')
    expect(runtime.state.answer).toBe('draft')
    expect(runtime.state.events).toHaveLength(3)
    expect(getTask).not.toHaveBeenCalled()
    expect(stream).toHaveBeenCalledOnce()
  })

  it('bounds reconnections and keeps the same applied cursor for every attempt', async () => {
    const stream = vi.fn().mockRejectedValue(new ApiError('disconnected'))
    const runtime = make({ api: { getTrace: vi.fn().mockResolvedValue(trace()), getTask: vi.fn(), cancelTask: vi.fn() },
      stream, sleep: async () => {}, retryDelays: [1, 1] })
    await runtime.open(ID)
    await vi.waitFor(() => expect(runtime.state.connection).toBe('stopped'))
    expect(stream).toHaveBeenCalledTimes(3)
    expect(stream.mock.calls.map(([options]) => options.cursor)).toEqual(['3', '3', '3'])
    expect(runtime.state.reconnects).toBe(2)
  })

  it('actively closes an existing stream offline and resumes with the same cursor when online', async () => {
    const stream = vi.fn(pendingStream)
    const runtime = make({ api: { getTrace: vi.fn().mockResolvedValue(trace()), getTask: vi.fn(), cancelTask: vi.fn() }, stream })
    await runtime.open(ID)
    expect(runtime.state.connection).toBe('connected')
    const first = stream.mock.calls[0]![0]
    window.dispatchEvent(new Event('offline'))
    expect(first.signal.aborted).toBe(true)
    expect(runtime.state.connection).toBe('offline')
    await vi.waitFor(() => expect(runtime.state.reconnects).toBe(1))
    window.dispatchEvent(new Event('online'))
    await vi.waitFor(() => expect(stream).toHaveBeenCalledTimes(2))
    expect(stream.mock.calls[1]![0].cursor).toBe('3')
    expect(runtime.state.connection).toBe('connected')
    expect(runtime.state.reconnects).toBe(1)
  })

  it('does not call a terminal event final until GET and Trace agree', async () => {
    const final = { ...task('5', 'COMPLETED'), finalAnswer: 'GET answer' }
    const finalEvents = [created, started, chunk, event('4', 'ANSWER_CHUNK', { chunkIndex: 1, text: ' stream' }), terminal]
    const runtime = make({ api: {
      getTrace: vi.fn().mockResolvedValue(trace(finalEvents, final)),
      getTask: vi.fn().mockRejectedValue(new ApiError('offline')),
      cancelTask: vi.fn(),
    }, stream: vi.fn(), retryDelays: [] })
    await runtime.open(ID)
    await vi.waitFor(() => expect(runtime.state.connection).toBe('stopped'))
    expect(runtime.state.task?.status).toBe('COMPLETED')
    expect(runtime.state.settled).toBe(false)
    expect(runtime.state.answer).toBe('draft stream')
    expect(runtime.state.citations).toEqual([])
  })

  it('rejects conflicting terminal GET and Trace without marking convergence complete', async () => {
    const final = { ...task('5', 'COMPLETED'), finalAnswer: 'GET answer' }
    const finalEvents = [created, started, chunk, event('4', 'ANSWER_CHUNK', { chunkIndex: 1, text: ' stream' }), terminal]
    const runtime = make({ api: {
      getTrace: vi.fn().mockResolvedValue(trace(finalEvents, { ...final, citations: [{ citationId: 'different' }] })),
      getTask: vi.fn().mockResolvedValue(final), cancelTask: vi.fn(),
    }, stream: vi.fn() })
    await runtime.open(ID)
    await vi.waitFor(() => expect(runtime.state.connection).toBe('stopped'))
    expect(runtime.state.settled).toBe(false)
    expect(runtime.state.error).toContain('不一致')
  })

  it('aborts on dispose/logout and fences late Trace and stream callbacks', async () => {
    let resolve!: (result: TaskTrace) => void
    const getTrace = vi.fn().mockImplementation((_id, signal) => {
      expect(signal.aborted).toBe(false)
      return new Promise<TaskTrace>((done) => { resolve = done })
    })
    const stream = vi.fn(pendingStream)
    const runtime = make({ api: { getTrace, getTask: vi.fn(), cancelTask: vi.fn() }, stream })
    const opening = runtime.open(ID)
    clearSession()
    expect(getTrace.mock.calls[0]![1].aborted).toBe(true)
    resolve(trace())
    await opening
    expect(runtime.state.task).toBeNull()
    expect(runtime.state.events).toEqual([])
    expect(stream).not.toHaveBeenCalled()
  })

  it('fences a superseded Trace load and handles malformed task route IDs visibly', async () => {
    let resolve!: (result: TaskTrace) => void
    const getTrace = vi.fn().mockImplementationOnce(() => new Promise<TaskTrace>((done) => { resolve = done })).mockResolvedValue(trace())
    const runtime = make({ api: { getTrace, getTask: vi.fn(), cancelTask: vi.fn() }, stream: vi.fn(pendingStream) })
    const stale = runtime.open(ID)
    await runtime.open(ID)
    resolve(trace([created], task('1', 'QUEUED')))
    await stale
    expect(runtime.state.cursor).toBe('3')
    await runtime.open('9e1')
    expect(runtime.state.connection).toBe('stopped')
    expect(runtime.state.loading).toBe(false)
    expect(runtime.state.error).toBeTruthy()
  })

  it('keeps server lastEventSequence distinct from the applied cursor after a cancellation response', async () => {
    const stream = vi.fn(pendingStream)
    const runtime = make({ api: { getTrace: vi.fn().mockResolvedValue(trace([created, started], task('2'))), getTask: vi.fn(),
      cancelTask: vi.fn().mockResolvedValue({ ...task('5'), cancelRequestedAt: '2026-09-06T12:00:01Z' }),
    }, stream })
    await runtime.open(ID)
    await runtime.cancel()
    stream.mock.calls[0]![0].onEvent(event('3', 'PHASE_CHANGED', { phase: 'DECIDING' }))
    expect(runtime.state.cursor).toBe('3')
    expect(runtime.state.task?.lastEventSequence).toBe('5')
    expect(runtime.state.task?.phase).toBe('GENERATING')
    expect(runtime.state.task?.cancelRequestedAt).toBe('2026-09-06T12:00:01Z')
  })
})
