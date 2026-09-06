import { describe, expect, it } from 'vitest'
import { decodeFrame, EventLedger, ProtocolError, rebuildTrace } from './events'
import { compareSequence, parseJson, resourceId, sequence } from './sequence'
import type { TaskEvent, TaskTrace } from './types'

export const taskId = '9223372036854775806'
export function event(sequenceNo: string, eventType: TaskEvent['eventType'] = 'TASK_CREATED', payload: TaskEvent['payload'] = { status: 'QUEUED' }): TaskEvent {
  return { taskId, sequenceNo, eventType, payload, createdAt: '2026-09-06T12:00:00Z' }
}

describe('lossless protocol boundaries', () => {
  it('preserves unsafe numeric sequence tokens and public string IDs without changing safe counters', () => {
    const data = parseJson('{"taskId":"9223372036854775806","sequenceNo":9007199254740993,"count":3,"score":0.85}') as Record<string, unknown>
    expect(data).toEqual({ taskId, sequenceNo: '9007199254740993', count: 3, score: 0.85 })
    expect(compareSequence('9007199254740993', '9007199254740992')).toBe(1)
    expect(() => sequence(9007199254740992)).toThrow()
    expect(() => resourceId(123)).toThrow()
    for (const invalid of ['-1', '1.0', '1e3', '01', '', '9223372036854775808']) expect(() => sequence(invalid)).toThrow()
    for (const raw of ['1.0', '1e0', '-0', '1.5']) expect(() => parseJson(`{"sequenceNo":${raw}}`)).toThrow()
  })

  it('checks SSE id, task, event name and required payload before applying', () => {
    const payload = '{"taskId":"9223372036854775806","sequenceNo":9007199254740993,"eventType":"ANSWER_CHUNK","timestamp":"2026-09-06T12:00:00Z","payload":{"chunkIndex":0,"text":"ok"}}'
    const frame = { id: '9007199254740993', event: 'ANSWER_CHUNK', data: payload }
    expect(decodeFrame(frame, taskId).sequenceNo).toBe(frame.id)
    expect(() => decodeFrame({ ...frame, id: '9007199254740992' }, taskId)).toThrow(ProtocolError)
    expect(() => decodeFrame({ ...frame, event: 'TOOL_STARTED' }, taskId)).toThrow(ProtocolError)
    expect(() => decodeFrame(frame, '42')).toThrow(ProtocolError)
    expect(() => decodeFrame({ ...frame, data: payload.replace('"chunkIndex":0', '"chunkIndex":-1') }, taskId)).toThrow(ProtocolError)
  })

  it('deduplicates replay, stops at a gap and acknowledges only after a successful handler', () => {
    const ledger = new EventLedger(taskId)
    expect(ledger.apply(event('1'))).toBe(true)
    expect(ledger.apply(event('1'))).toBe(false)
    expect(() => ledger.apply(event('3'))).toThrow(/缺口/)
    expect(ledger.cursor).toBe('1')
    expect(() => ledger.apply(event('2', 'ANSWER_CHUNK', { text: 'once', chunkIndex: 0 }), () => { throw new Error('render failed') })).toThrow('render failed')
    expect(ledger.cursor).toBe('1')
    expect(ledger.answer).toBe('')
    ledger.apply(event('2', 'ANSWER_CHUNK', { text: 'once', chunkIndex: 0 }))
    ledger.apply(event('2', 'ANSWER_CHUNK', { text: 'once', chunkIndex: 0 }))
    expect(ledger.answer).toBe('once')
    expect(ledger.events).toHaveLength(2)
    expect(() => ledger.apply(event('3', 'ANSWER_CHUNK', { text: 'skip', chunkIndex: 2 }))).toThrow(/答案块/)
    expect(ledger.cursor).toBe('2')
  })

  it('increments a cursor beyond Number.MAX_SAFE_INTEGER exactly', () => {
    const ledger = new EventLedger(taskId)
    ledger.cursor = '9007199254740992'
    ledger.apply(event('9007199254740993', 'PHASE_CHANGED', { phase: 'DECIDING' }))
    expect(ledger.cursor).toBe('9007199254740993')
  })

  it('requires a complete ordered Trace before restoring the acknowledged cursor', () => {
    const trace = { task: { taskId, lastEventSequence: '2' }, steps: [], events: [event('1'), event('2', 'ANSWER_CHUNK', { chunkIndex: 0, text: 'restored' })] } as unknown as TaskTrace
    const ledger = rebuildTrace(trace, taskId)
    expect(ledger.answer).toBe('restored')
    expect(ledger.cursor).toBe('2')
    expect(() => rebuildTrace({ ...trace, events: [event('1')] }, taskId)).toThrow(/游标/)
    expect(() => rebuildTrace({ ...trace, events: [event('1'), event('1')] }, taskId)).toThrow(/重复/)
  })
})
