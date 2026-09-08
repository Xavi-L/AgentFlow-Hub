import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { Task, TaskTrace, TraceStep } from '../src/lib/types'
import { redactEvidence, verifyRealProviderEvidence, type EvidenceExpectation } from './real-provider-evidence'

// Offline counterexamples test the acceptance predicate only. These are never imported by the browser run.
function example() {
  const generation = '9007199254740993'
  const task: Task = {
    taskId: '47001', agentId: '47002', status: 'COMPLETED', terminationReason: 'ANSWERED',
    maxToolCalls: 3, maxTotalTokens: 24000, totalTokens: 1024, toolCallsUsed: 2, lastEventSequence: '5',
    phase: 'COMPLETED', userInput: 'order_1024', maxDecisionTurns: 5, reservedFinalTokens: 2048, decisionTurnsUsed: 3,
    inputTokens: 900, outputTokens: 124, tokenUsageQuality: 'EXACT', errorCode: null, errorMessage: null,
    cancelRequestedAt: null, startedAt: '2026-09-07T00:00:00Z', completedAt: '2026-09-07T00:00:01Z',
    createdAt: '2026-09-07T00:00:00Z', updatedAt: '2026-09-07T00:00:01Z',
    finalAnswer: 'order_1024: E_PAY_TIMEOUT. [S1]',
    citations: [{ citationId: 'S1', documentId: '47004', chunkId: '47005', vectorGeneration: generation }],
  }
  const expected: EvidenceExpectation = {
    taskId: task.taskId, agentId: task.agentId, knowledgeBaseId: '47003', model: 'example-chat',
    document: { id: '47004', knowledgeBaseId: '47003', vectorGeneration: generation, retrievalReadiness: 'READY',
      parseStatus: 'COMPLETED', vectorization: { pending: 0, processing: 0, completed: 1, failed: 0 } },
    chunks: [{ id: '47005', documentId: '47004', content: 'Source policy.', vectorizationStatus: 'COMPLETED', vectorId: 'point-id' }],
  }
  const step = (id: string, stepIndex: number, stepType: string): TraceStep => ({ id, stepIndex, stepType,
    status: 'SUCCESS', llmCalls: [], toolCalls: [], ragRetrievals: [] }) as unknown as TraceStep
  const rag = step('1', 1, 'RAG_RETRIEVAL')
  rag.ragRetrievals.push({ id: '101', status: 'SUCCESS', embeddingProfileCode: 'dashscope-te-v4-1024-cosine',
    validHitCount: 1, candidateCount: 1, staleHitCount: 0, hits: [{ id: '102', citationId: 'S1',
      documentIdSnapshot: '47004', knowledgeBaseIdSnapshot: '47003', chunkIdSnapshot: '47005', vectorGeneration: generation,
      contentSnapshot: 'Source policy.', score: 0.9 }] } as any)
  const steps = [rag]
  const events: any[] = []
  for (const [index, code] of ['order_query', 'payment_log_query'].entries()) {
    const decision = step(String(index * 2 + 2), index * 2 + 2, 'LLM_DECISION')
    decision.llmCalls.push({ id: `20${index}`, callType: 'DECISION', status: 'SUCCESS', provider: 'openai-compatible',
      requestedModel: 'example-chat', resolvedModel: null, responseText: JSON.stringify({ type: 'CALL_TOOL', toolCode: code }) } as any)
    const tool = step(String(index * 2 + 3), index * 2 + 3, 'TOOL_CALL')
    const data = code === 'order_query' ? { orderNo: 'order_1024', paymentStatus: 'PAY_FAILED', errorCode: 'E_PAY_TIMEOUT' }
      : { logs: [{ orderNo: 'order_1024', errorCode: 'E_PAY_TIMEOUT', traceId: 'pay-trace-1024', message: 'timeout after 3000ms' }] }
    tool.toolCalls.push({ id: `30${index}`, toolCode: code, arguments: { orderNo: 'order_1024' }, status: 'SUCCESS', retryCount: 0,
      result: { success: true, toolCode: code, data }, startedAt: '2026-09-07T00:00:00Z', finishedAt: '2026-09-07T00:00:01Z' } as any)
    events.push({ sequenceNo: String(events.length + 1), taskId: task.taskId, eventType: 'TOOL_FINISHED',
      payload: { stepId: tool.id, toolCode: code, status: 'SUCCESS', reused: false } })
    steps.push(decision, tool)
  }
  const finish = step('6', 6, 'LLM_DECISION')
  finish.llmCalls.push({ id: '203', callType: 'DECISION', status: 'SUCCESS', provider: 'openai-compatible',
    requestedModel: 'example-chat', resolvedModel: 'example-chat-revision', responseText: '{"type":"FINISH"}' } as any)
  const final = step('7', 7, 'LLM_FINAL_GENERATION')
  final.llmCalls.push({ id: '204', callType: 'FINAL_GENERATION', status: 'SUCCESS', provider: 'openai-compatible',
    requestedModel: 'example-chat', resolvedModel: 'example-chat-revision', responseText: task.finalAnswer } as any)
  steps.push(finish, final)
  for (const [eventType, payload] of [
    ['FINAL_GENERATION_STARTED', {}], ['ANSWER_CHUNK', { text: task.finalAnswer }], ['TASK_COMPLETED', {}],
  ] as const) events.push({ sequenceNo: String(events.length + 1), taskId: task.taskId, eventType, payload })
  const trace = { task, steps, events, executionSnapshot: {
    chatModel: { provider: 'openai-compatible', model: 'example-chat' }, retrieval: { knowledgeBases: [{
      knowledgeBaseId: '47003', embeddingProfileCode: 'dashscope-te-v4-1024-cosine',
      documents: [{ documentId: '47004', vectorGeneration: generation }],
    }] },
  } } as TaskTrace
  return { task, trace, expected }
}

test('predicate accepts complete evidence with lossless generation and optional provider metadata', () => {
  const { task, trace, expected } = example()
  assert.doesNotThrow(() => verifyRealProviderEvidence(task, trace, expected))
})

test('COMPLETED without retrieval, tools, final generation or citations is insufficient', () => {
  for (const remove of ['retrieval', 'tool', 'final', 'citations'] as const) {
    const { task, trace, expected } = example()
    if (remove === 'retrieval') trace.steps[0]!.ragRetrievals[0]!.hits = []
    if (remove === 'tool') trace.steps[2]!.toolCalls = []
    if (remove === 'final') trace.steps.at(-1)!.llmCalls = []
    if (remove === 'citations') task.citations = []
    assert.throws(() => verifyRealProviderEvidence(task, trace, expected), remove)
  }
})

test('rejects old generations, foreign citation chunks and unexecuted or reused tools', () => {
  for (const corrupt of ['generation', 'citation', 'toolResult', 'reused'] as const) {
    const { task, trace, expected } = example()
    if (corrupt === 'generation') trace.steps[0]!.ragRetrievals[0]!.hits[0]!.vectorGeneration = '9007199254740992'
    if (corrupt === 'citation') (task.citations[0] as any).chunkId = '999'
    if (corrupt === 'toolResult') (trace.steps[2]!.toolCalls[0]!.result as any).success = false
    if (corrupt === 'reused') trace.events[0]!.payload.reused = true
    assert.throws(() => verifyRealProviderEvidence(task, trace, expected), corrupt)
  }
})

test('rejects budget completion, merged decision/final and divergent GET versus Trace', () => {
  for (const corrupt of ['budget', 'final', 'readback'] as const) {
    const { task, trace, expected } = example()
    if (corrupt === 'budget') task.terminationReason = 'MAX_TOOL_CALLS'
    if (corrupt === 'final') trace.steps.at(-1)!.llmCalls[0]!.callType = 'DECISION'
    if (corrupt === 'readback') trace.task = { ...task, finalAnswer: 'Another answer' }
    assert.throws(() => verifyRealProviderEvidence(task, trace, expected), corrupt)
  }
})

test('artifact redaction removes local credentials and Bearer/JWT values while retaining provider model evidence', () => {
  assert.deepEqual(redactEvidence({ password: 'short', message: 'Bearer abc.def.ghi credential-value',
    nested: { api_key: 'key', provider: 'openai-compatible', requestedModel: 'example-chat' } }, ['credential-value']), {
    password: '[REDACTED]', message: 'Bearer [REDACTED] [REDACTED]',
    nested: { api_key: '[REDACTED]', provider: 'openai-compatible', requestedModel: 'example-chat' },
  })
})
