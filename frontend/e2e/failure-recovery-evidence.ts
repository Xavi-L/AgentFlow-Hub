import assert from 'node:assert/strict'
import type { Json, Task, TaskTrace } from '../src/lib/types'

export interface CaseExpectation {
  status: string; reason: string; errorCode: string | null
  decisions: number; tools: number; handlers: number; finals: number
}

const failure = (errorCode: string, decisions: number, tools = 0, handlers = 0, finals = 0): CaseExpectation =>
  ({ status: 'FAILED', reason: 'SYSTEM_ERROR', errorCode, decisions, tools, handlers, finals })
const completed = (reason: string, decisions: number, tools: number): CaseExpectation =>
  ({ status: 'COMPLETED', reason, errorCode: null, decisions, tools, handlers: tools, finals: 1 })

/** Independent expected outcomes from the frozen V48 matrix, not inferred from actual results. */
export const EXPECTED: Record<string, CaseExpectation> = {
  F01_JSON: failure('AGENT_INVALID_DECISION', 1),
  F01_SHAPE: failure('AGENT_INVALID_DECISION', 1),
  F02: failure('AGENT_DUPLICATE_TOOL_LOOP', 3, 1, 1),
  F03: failure('TOOL_ARGUMENT_INVALID', 1, 1),
  F04: failure('TOOL_EXECUTION_FAILED', 1, 1, 1),
  F05_EMBED: failure('RAG_RETRIEVAL_FAILED', 0),
  F05_VECTOR: failure('RAG_RETRIEVAL_FAILED', 0),
  F06_UNKNOWN: failure('AGENT_INVALID_CITATION', 1, 0, 0, 1),
  F06_MALFORMED: failure('AGENT_INVALID_CITATION', 1, 0, 0, 1),
  B01_PRE: { ...failure('AGENT_TOKEN_BUDGET_EXHAUSTED', 0), reason: 'TOKEN_BUDGET_EXHAUSTED' },
  B01_OVER: { ...failure('AGENT_TOKEN_BUDGET_EXHAUSTED', 1), reason: 'TOKEN_BUDGET_EXHAUSTED' },
  B02: { status: 'TIMED_OUT', reason: 'DEADLINE_EXCEEDED', errorCode: null, decisions: 1, tools: 0, handlers: 0, finals: 1 },
  B03: { status: 'CANCELLED', reason: 'USER_CANCELLED', errorCode: null, decisions: 1, tools: 0, handlers: 0, finals: 1 },
  C01: completed('ANSWERED', 2, 1),
  C02: completed('MAX_DECISION_TURNS', 4, 2),
  C03: completed('MAX_TOOL_CALLS', 1, 1),
  R01: failure('AGENT_INVALID_DECISION', 1),
  R02: failure('AGENT_INVALID_DECISION', 1),
  R03: failure('AGENT_INVALID_DECISION', 1),
  R04_SUCCESS: completed('ANSWERED', 1, 0),
  R04_FAILED: failure('AGENT_INVALID_DECISION', 1),
  R05: completed('ANSWERED', 1, 0),
}

export interface EvidenceCheck { code: string; passed: boolean; error?: string }
export function checked(checks: EvidenceCheck[], code: string, assertion: () => void): void {
  try { assertion(); checks.push({ code, passed: true }) }
  catch (error) {
    checks.push({ code, passed: false, error: error instanceof Error ? error.message : String(error) })
    throw error
  }
}
function object(value: Json): Record<string, Json> {
  assert.ok(value && typeof value === 'object' && !Array.isArray(value))
  return value as Record<string, Json>
}

export function verifyPublicEvidence(caseId: string, task: Task, trace: TaskTrace, checks: EvidenceCheck[]): void {
  const expected = EXPECTED[caseId]!
  const llm = trace.steps.flatMap(step => step.llmCalls)
  const decisions = llm.filter(call => call.callType === 'DECISION')
  const finals = llm.filter(call => call.callType === 'FINAL_GENERATION')
  const tools = trace.steps.flatMap(step => step.toolCalls)
  const retrievals = trace.steps.flatMap(step => step.ragRetrievals)
  checked(checks, 'terminal_get_trace_agreement', () => {
    assert.deepEqual(trace.task, task)
    assert.equal(task.status, expected.status)
    assert.equal(task.terminationReason, expected.reason)
    assert.equal(task.errorCode ?? null, expected.errorCode)
    assert.ok(task.completedAt)
    assert.equal(task.phase ?? null, null)
    assert.equal(task.decisionTurnsUsed, expected.decisions)
    assert.equal(task.toolCallsUsed, expected.tools)
  })
  checked(checks, 'continuous_unique_events_and_one_terminal', () => {
    assert.deepEqual(trace.events.map(event => String(event.sequenceNo)), trace.events.map((_, index) => String(index + 1)))
    assert.equal(String(task.lastEventSequence), String(trace.events.length))
    assert.ok(trace.events.every(event => event.taskId === task.taskId))
    assert.equal(trace.events.filter(event => event.eventType === 'TASK_CREATED').length, 1)
    assert.equal(trace.events.filter(event => event.eventType === 'TASK_STARTED').length, 1)
    const terminal = trace.events.filter(event => ['TASK_COMPLETED', 'TASK_FAILED', 'TASK_CANCELLED', 'TASK_TIMED_OUT'].includes(event.eventType))
    assert.equal(terminal.length, 1)
    assert.equal(terminal[0], trace.events.at(-1))
    assert.equal(terminal[0]!.eventType, `TASK_${expected.status}`)
    assert.equal(terminal[0]!.payload.terminationReason, expected.reason)
    assert.equal(terminal[0]!.payload.errorCode ?? null, expected.errorCode)
  })
  checked(checks, 'durable_calls_and_usage', () => {
    assert.equal(decisions.length, expected.decisions)
    assert.equal(finals.length, expected.finals)
    assert.equal(tools.length, expected.tools)
    assert.equal(new Set(llm.map(call => call.id)).size, llm.length)
    assert.equal(new Set(tools.map(call => call.id)).size, tools.length)
    assert.ok(tools.every(call => call.retryCount === 0))
    for (const call of llm) {
      assert.ok(Number.isSafeInteger(call.inputTokens) && call.inputTokens! >= 0)
      assert.ok(Number.isSafeInteger(call.outputTokens) && call.outputTokens! >= 0)
      assert.equal(call.totalTokens, call.inputTokens! + call.outputTokens!)
    }
    assert.equal(task.inputTokens, llm.reduce((sum, call) => sum + call.inputTokens!, 0))
    assert.equal(task.outputTokens, llm.reduce((sum, call) => sum + call.outputTokens!, 0))
    assert.equal(task.totalTokens, task.inputTokens + task.outputTokens)
    if (caseId === 'B01_PRE' || caseId.startsWith('F05')) { assert.equal(llm.length, 0); assert.equal(task.totalTokens, 0); assert.equal(task.tokenUsageQuality, 'UNKNOWN') }
    else assert.ok(task.totalTokens > 0)
    if (caseId === 'B01_OVER') assert.ok(task.totalTokens > task.maxTotalTokens)
    if (caseId === 'B02' || caseId === 'B03') {
      assert.equal(finals[0]!.usageQuality, 'ESTIMATED')
      assert.ok(finals[0]!.totalTokens! > 0)
      assert.equal(task.tokenUsageQuality, 'MIXED')
    }
  })
  checked(checks, 'failure_boundary_and_preserved_history', () => {
    assert.equal(retrievals.length, 1)
    const rag = retrievals[0]!
    assert.equal(rag.status, caseId.startsWith('F05') ? 'FAILED' : 'SUCCESS')
    if (caseId.startsWith('F05')) assert.equal(rag.errorCode, 'RAG_RETRIEVAL_FAILED')
    else if (caseId === 'C01' || caseId === 'B01_PRE') { assert.equal(rag.validHitCount, 0); assert.deepEqual(rag.hits, []) }
    else assert.ok(rag.validHitCount > 0)
    if (caseId.startsWith('F01') || ['R01', 'R02', 'R03', 'R04_FAILED'].includes(caseId)) {
      assert.equal(decisions[0]!.status, 'FAILED')
      assert.equal(decisions[0]!.errorCode, 'AGENT_INVALID_DECISION')
    }
    if (caseId === 'F03') { assert.equal(tools[0]!.status, 'REJECTED'); assert.equal(tools[0]!.errorCode, 'TOOL_ARGUMENT_INVALID') }
    else if (caseId === 'F04') { assert.equal(tools[0]!.status, 'FAILED'); assert.equal(tools[0]!.errorCode, 'TOOL_EXECUTION_FAILED') }
    else assert.ok(tools.every(call => call.status === 'SUCCESS'))
    if (caseId.startsWith('F06')) {
      assert.equal(finals[0]!.status, 'FAILED')
      assert.equal(finals[0]!.errorCode, 'AGENT_INVALID_CITATION')
    }
    const reused = trace.events.filter(event => event.eventType === 'TOOL_FINISHED' && event.payload.reused === true)
    assert.equal(reused.length, caseId === 'F02' ? 1 : caseId === 'C02' ? 2 : 0)
    if (caseId === 'C02' || caseId === 'C03') {
      assert.ok(decisions.every(call => JSON.parse(call.responseText!).type === 'CALL_TOOL'))
      assert.equal(finals[0]!.status, 'SUCCESS')
    }
  })
  checked(checks, 'atomic_answer_publication_and_valid_citations', () => {
    const chunks = trace.events.filter(event => event.eventType === 'ANSWER_CHUNK')
    if (expected.status !== 'COMPLETED') {
      assert.equal(task.finalAnswer ?? null, null)
      assert.deepEqual(task.citations, [])
      assert.equal(chunks.length, 0)
      assert.equal(trace.events.filter(event => event.eventType === 'TASK_COMPLETED').length, 0)
      return
    }
    assert.ok(task.finalAnswer?.trim())
    assert.ok(task.totalTokens <= task.maxTotalTokens)
    assert.deepEqual(chunks.map(event => String(event.payload.chunkIndex)), chunks.map((_, index) => String(index)))
    assert.equal(chunks.map(event => event.payload.text).join(''), task.finalAnswer)
    assert.equal(finals[0]!.responseText, task.finalAnswer)
    if (caseId === 'C01') { assert.deepEqual(task.citations, []); assert.doesNotMatch(task.finalAnswer!, /\[S\d+\]/); return }
    assert.ok(task.citations.length > 0)
    const hits = retrievals.flatMap(retrieval => retrieval.hits)
    const identifiers = task.citations.map(value => {
      const citation = object(value)
      assert.ok(hits.some(hit => hit.citationId === citation.citationId && hit.documentIdSnapshot === citation.documentId
        && hit.chunkIdSnapshot === citation.chunkId && String(hit.vectorGeneration) === String(citation.vectorGeneration)))
      return citation.citationId
    })
    const inline = [...new Set([...task.finalAnswer!.matchAll(/\[(S\d+)\]/g)].map(match => match[1]!))].sort()
    assert.deepEqual(identifiers.sort(), inline)
  })
}
