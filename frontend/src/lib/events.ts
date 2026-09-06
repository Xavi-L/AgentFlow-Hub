import { EVENT_TYPES, type EventType, type Json, type TaskEvent, type TaskTrace } from './types'
import { compareSequence, nextSequence, parseJson, resourceId, sequence } from './sequence'

export class ProtocolError extends Error {
  constructor(message: string, public code = 'INVALID_EVENT') { super(message) }
}
const TERMINAL_STATUS: Partial<Record<EventType, string>> = {
  TASK_COMPLETED: 'COMPLETED', TASK_FAILED: 'FAILED', TASK_CANCELLED: 'CANCELLED', TASK_TIMED_OUT: 'TIMED_OUT',
}
const PHASES = ['PREPARING', 'RETRIEVING', 'DECIDING', 'EXECUTING_TOOL', 'GENERATING']
function object(value: unknown): value is Record<string, unknown> { return !!value && typeof value === 'object' && !Array.isArray(value) }

export function validateEvent(raw: unknown, taskId: string): TaskEvent {
  try {
    if (!object(raw) || resourceId(raw.taskId) !== taskId || !EVENT_TYPES.includes(raw.eventType as EventType)
      || !object(raw.payload)) throw new Error('Invalid task, event type or payload')
    const event = raw as unknown as TaskEvent
    const createdAt = raw.createdAt ?? raw.timestamp
    if (typeof createdAt !== 'string' || !Number.isFinite(Date.parse(createdAt))) throw new Error('Invalid event timestamp')
    const sequenceNo = sequence(raw.sequenceNo)
    if (sequenceNo === '0') throw new Error('Event sequence starts at 1')
    if (raw.id !== undefined) resourceId(raw.id)
    const payload = event.payload
    const requireText = (key: string) => { if (typeof payload[key] !== 'string') throw new Error(`Invalid ${key}`) }
    const requireCount = (key: string) => { sequence(payload[key]) }
    switch (event.eventType) {
      case 'TASK_CREATED': if (payload.status !== 'QUEUED') throw new Error('Invalid created status'); break
      case 'TASK_STARTED':
        if (payload.status !== 'RUNNING') throw new Error('Invalid started status')
        if (!PHASES.includes(String(payload.phase))) throw new Error('Invalid task phase')
        break
      case 'PHASE_CHANGED': if (!PHASES.includes(String(payload.phase))) throw new Error('Invalid task phase'); break
      case 'ANSWER_CHUNK': requireCount('chunkIndex'); requireText('text'); break
      case 'RAG_FINISHED':
        resourceId(payload.stepId); ['validHitCount', 'candidateCount', 'staleHitCount'].forEach(requireCount); break
      case 'DECISION_FINISHED':
        resourceId(payload.stepId); requireCount('totalTokens'); requireText('usageQuality'); requireText('decisionType'); break
      case 'TOOL_STARTED': case 'TOOL_FINISHED':
        resourceId(payload.stepId); requireText('toolCode')
        if (typeof payload.reused !== 'boolean') throw new Error('Invalid reused flag')
        if (event.eventType === 'TOOL_FINISHED') requireText('status')
        break
      case 'FINAL_GENERATION_STARTED': requireCount('maxOutputTokens'); break
      default:
        if (payload.status !== TERMINAL_STATUS[event.eventType]) throw new Error('Invalid terminal status')
        requireText('terminationReason')
    }
    if (payload.errorCode !== undefined && payload.errorCode !== null && typeof payload.errorCode !== 'string') throw new Error('Invalid error code')
    return { ...event, sequenceNo, createdAt }
  } catch (error) {
    if (error instanceof ProtocolError) throw error
    throw new ProtocolError(error instanceof Error ? error.message : 'Invalid persisted event')
  }
}

export function decodeFrame(frame: { id: string; event: string; data: string }, taskId: string): TaskEvent {
  try {
    const event = validateEvent(parseJson(frame.data), taskId)
    if (sequence(frame.id) !== event.sequenceNo || frame.event !== event.eventType) throw new ProtocolError('SSE id / event 与 data 不一致')
    return event
  } catch (error) {
    if (error instanceof ProtocolError) throw error
    throw new ProtocolError('SSE 数据无法解析')
  }
}

/** Only this ledger owns the applied cursor. Transport last-sent IDs never write it. */
export class EventLedger {
  cursor = '0'
  answer = ''
  events: TaskEvent[] = []
  private nextChunk = '0'
  constructor(readonly taskId: string) { resourceId(taskId) }

  apply(raw: unknown, handler: (event: TaskEvent, answer: string) => void = () => {}): boolean {
    const event = validateEvent(raw, this.taskId)
    if (compareSequence(event.sequenceNo, this.cursor) <= 0) return false
    if (event.sequenceNo !== nextSequence(this.cursor)) {
      throw new ProtocolError(`事件缺口：期望 ${nextSequence(this.cursor)}，收到 ${event.sequenceNo}。已停止应用。`, 'TASK_EVENT_SEQUENCE_GAP')
    }
    let answer = this.answer
    let chunk = this.nextChunk
    if (event.eventType === 'ANSWER_CHUNK') {
      if (sequence(event.payload.chunkIndex) !== chunk) throw new ProtocolError('答案块顺序不连续')
      answer += event.payload.text as string
      chunk = nextSequence(chunk)
    }
    handler(event, answer)
    this.events.push(event)
    this.answer = answer
    this.nextChunk = chunk
    this.cursor = event.sequenceNo
    return true
  }
}

/** Validate the complete repeatable-read Trace before publishing any state or cursor. */
export function rebuildTrace(trace: TaskTrace, taskId: string): EventLedger {
  if (trace.task.taskId !== taskId || !Array.isArray(trace.events) || !Array.isArray(trace.steps)) throw new ProtocolError('Trace 与任务不匹配')
  const ledger = new EventLedger(taskId)
  for (const event of trace.events) {
    if (!ledger.apply(event)) throw new ProtocolError('Trace 中存在重复或倒序事件')
  }
  if (ledger.cursor !== sequence(trace.task.lastEventSequence)) throw new ProtocolError('Trace 事件与快照游标不一致', 'TASK_EVENT_SEQUENCE_GAP')
  return ledger
}

export function stableJson(value: Json): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(stableJson).join(',')}]`
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableJson(value[key]!)}`).join(',')}}`
}
