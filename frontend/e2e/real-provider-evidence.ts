import assert from 'node:assert/strict'
import type { Json, Task, TaskTrace, TraceRagHit } from '../src/lib/types'

export interface CurrentDocument {
  id: string; knowledgeBaseId: string; vectorGeneration: number | string
  retrievalReadiness: string; parseStatus: string
  vectorization: { pending: number | string; processing: number | string; completed: number | string; failed: number | string }
}
export interface CurrentChunk { id: string; documentId: string; content: string; vectorizationStatus: string; vectorId: string | null }
export interface EvidenceExpectation {
  knowledgeBaseId: string; agentId: string; taskId: string; model: string
  document: CurrentDocument; chunks: CurrentChunk[]
}

function object(value: unknown): Record<string, Json> {
  assert.ok(value !== null && typeof value === 'object' && !Array.isArray(value), 'Expected an object in public evidence')
  return value as Record<string, Json>
}
const identity = (hit: TraceRagHit) => `${hit.citationId}/${hit.documentIdSnapshot}/${hit.chunkIdSnapshot}/${hit.vectorGeneration}`

/** This checks application evidence. The launcher separately proves normal remote gateway wiring. */
export function verifyRealProviderEvidence(task: Task, trace: TaskTrace, expected: EvidenceExpectation): void {
  assert.equal(task.taskId, expected.taskId)
  assert.equal(task.agentId, expected.agentId)
  assert.equal(task.status, 'COMPLETED', 'Task must complete successfully')
  assert.equal(task.terminationReason, 'ANSWERED', 'Budget termination is not the V47 main path')
  assert.equal(task.maxToolCalls, 3)
  assert.equal(task.maxTotalTokens, 24000)
  assert.ok(task.totalTokens > 0 && task.totalTokens <= task.maxTotalTokens)
  assert.equal(task.toolCallsUsed, 2, 'Both tools must execute once through ToolRuntime')
  assert.deepEqual(trace.task, task, 'GET task and GET Trace must agree after completion')
  assert.ok(task.finalAnswer?.trim(), 'A persisted final answer is required')
  assert.ok(task.finalAnswer!.includes('order_1024') && task.finalAnswer!.includes('E_PAY_TIMEOUT'),
    'Answer must identify the requested order and observed payment error; semantic quality also needs human review')

  const document = expected.document
  assert.equal(document.knowledgeBaseId, expected.knowledgeBaseId)
  assert.equal(document.parseStatus, 'COMPLETED')
  assert.equal(document.retrievalReadiness, 'READY')
  assert.ok(BigInt(document.vectorization.completed) > 0n)
  for (const count of ['pending', 'processing', 'failed'] as const) assert.equal(BigInt(document.vectorization[count]), 0n)
  assert.ok(expected.chunks.length > 0, 'Parsed source chunks must exist')
  assert.equal(BigInt(document.vectorization.completed), BigInt(expected.chunks.length))
  const chunks = new Map(expected.chunks.map(chunk => [chunk.id, chunk]))
  expected.chunks.forEach(chunk => {
    assert.equal(chunk.documentId, document.id)
    assert.equal(chunk.vectorizationStatus, 'COMPLETED')
    assert.ok(chunk.vectorId, 'Current chunks must have persisted vector identifiers')
  })

  const snapshot = object(trace.executionSnapshot)
  const chat = object(snapshot.chatModel)
  assert.equal(chat.provider, 'openai-compatible')
  assert.equal(chat.model, expected.model)
  const retrievalSnapshot = object(snapshot.retrieval)
  assert.ok(Array.isArray(retrievalSnapshot.knowledgeBases))
  assert.equal(retrievalSnapshot.knowledgeBases.length, 1)
  const corpus = object(retrievalSnapshot.knowledgeBases[0])
  assert.equal(corpus.knowledgeBaseId, expected.knowledgeBaseId)
  assert.equal(corpus.embeddingProfileCode, 'dashscope-te-v4-1024-cosine')
  assert.ok(Array.isArray(corpus.documents))
  assert.equal(corpus.documents.length, 1)
  assert.equal(object(corpus.documents[0]).documentId, document.id)
  assert.equal(String(object(corpus.documents[0]).vectorGeneration), String(document.vectorGeneration))

  const retrievals = trace.steps.flatMap(step => step.ragRetrievals)
  assert.equal(retrievals.length, 1)
  const retrieval = retrievals[0]!
  assert.equal(retrieval.status, 'SUCCESS')
  assert.equal(retrieval.embeddingProfileCode, 'dashscope-te-v4-1024-cosine')
  assert.ok(retrieval.validHitCount > 0, 'READY and COMPLETED alone do not prove Qdrant retrieval')
  assert.equal(retrieval.validHitCount, retrieval.hits.length)
  assert.ok(retrieval.candidateCount >= retrieval.validHitCount)
  assert.equal(retrieval.staleHitCount, 0)
  retrieval.hits.forEach(hit => {
    assert.equal(hit.knowledgeBaseIdSnapshot, expected.knowledgeBaseId)
    assert.equal(hit.documentIdSnapshot, document.id)
    assert.equal(String(hit.vectorGeneration), String(document.vectorGeneration), 'Retrieved generation must still be current')
    assert.ok(Number.isFinite(hit.score))
    assert.equal(chunks.get(hit.chunkIdSnapshot)?.content, hit.contentSnapshot, 'Trace hit must match the uploaded canonical chunk')
  })

  const calls = trace.steps.flatMap(step => step.toolCalls.map(call => ({ step, call })))
  assert.equal(calls.length, 2, 'Bindings and event labels cannot replace durable ToolRuntime logs')
  assert.equal(new Set(calls.map(({ call }) => call.id)).size, 2)
  for (const code of ['order_query', 'payment_log_query']) {
    const matches = calls.filter(({ call }) => call.toolCode === code)
    assert.equal(matches.length, 1, `Expected one ${code} ToolRuntime log`)
    const { step, call } = matches[0]!
    assert.equal(step.stepType, 'TOOL_CALL')
    assert.equal(step.status, 'SUCCESS')
    assert.match(call.id, /^[1-9]\d*$/)
    assert.equal(call.status, 'SUCCESS')
    assert.equal(call.retryCount, 0)
    assert.equal(object(call.arguments).orderNo, 'order_1024')
    const result = object(call.result)
    assert.equal(result.success, true)
    assert.equal(result.toolCode, code)
    assert.ok(result.data !== null && result.data !== undefined)
    const data = object(result.data)
    if (code === 'order_query') {
      assert.equal(data.orderNo, 'order_1024')
      assert.equal(data.paymentStatus, 'PAY_FAILED')
      assert.equal(data.errorCode, 'E_PAY_TIMEOUT')
    } else {
      assert.ok(Array.isArray(data.logs))
      assert.ok(data.logs.some(value => {
        const log = object(value)
        return log.orderNo === 'order_1024' && log.errorCode === 'E_PAY_TIMEOUT'
          && log.traceId === 'pay-trace-1024' && String(log.message).includes('3000ms')
      }), 'Payment log must contain the existing demonstration timeout fact')
    }
    assert.ok(call.startedAt && call.finishedAt, 'ToolRuntime must persist both lifecycle timestamps')
    const completion = trace.events.find(event => event.eventType === 'TOOL_FINISHED'
      && String(event.payload.stepId) === step.id && event.payload.toolCode === code)
    assert.equal(completion?.payload.status, 'SUCCESS')
    assert.equal(completion?.payload.reused, false)
  }

  const llmCalls = trace.steps.flatMap(step => step.llmCalls.map(call => ({ step, call })))
  assert.equal(new Set(llmCalls.map(({ call }) => call.id)).size, llmCalls.length)
  const decisions = llmCalls.filter(({ call }) => call.callType === 'DECISION')
  assert.equal(task.maxDecisionTurns, 5)
  assert.ok(decisions.length >= 3 && decisions.length <= task.maxDecisionTurns)
  assert.equal(JSON.parse(decisions.at(-1)!.call.responseText!).type, 'FINISH')
  const finals = llmCalls.filter(({ call }) => call.callType === 'FINAL_GENERATION')
  assert.equal(finals.length, 1, 'Final generation must be a separate persisted LLM call')
  const final = finals[0]!
  assert.equal(final.step.stepType, 'LLM_FINAL_GENERATION')
  assert.equal(final.call.responseText, task.finalAnswer)
  assert.ok([...calls, ...decisions].every(({ step }) => step.stepIndex < final.step.stepIndex))
  llmCalls.forEach(({ step, call }) => {
    assert.equal(step.status, 'SUCCESS')
    assert.equal(call.status, 'SUCCESS')
    assert.equal(call.provider, 'openai-compatible')
    assert.equal(call.requestedModel, expected.model)
    // resolvedModel/providerRequestId may be absent in a compatible provider response.
    assert.ok(call.resolvedModel == null || (typeof call.resolvedModel === 'string' && call.resolvedModel.length > 0))
  })

  const citedIds = [...new Set([...task.finalAnswer!.matchAll(/\[(S\d+)\]/g)].map(match => match[1]!))]
  assert.ok(citedIds.length > 0, 'At least one source must be cited in the answer')
  assert.equal(task.citations.length, citedIds.length)
  const hitIdentities = new Set(retrieval.hits.map(identity))
  const persistedCitationIds = task.citations.map(value => {
    const citation = object(value)
    assert.ok(hitIdentities.has(`${citation.citationId}/${citation.documentId}/${citation.chunkId}/${citation.vectorGeneration}`),
      'Every answer citation must identify a current, retrieved source chunk')
    return citation.citationId
  })
  assert.deepEqual([...persistedCitationIds].sort(), [...citedIds].sort())

  const events = trace.events
  assert.ok(events.length > 0)
  assert.deepEqual(events.map(event => String(event.sequenceNo)), events.map((_, index) => String(index + 1)))
  assert.ok(events.every(event => event.taskId === expected.taskId))
  assert.equal(String(task.lastEventSequence), String(events.at(-1)!.sequenceNo))
  assert.equal(events.at(-1)!.eventType, 'TASK_COMPLETED')
  const generationStart = events.filter(event => event.eventType === 'FINAL_GENERATION_STARTED')
  assert.equal(generationStart.length, 1)
  assert.ok(events.filter(event => event.eventType === 'TOOL_FINISHED').every(event =>
    BigInt(event.sequenceNo) < BigInt(generationStart[0]!.sequenceNo)))
  assert.equal(events.filter(event => event.eventType === 'ANSWER_CHUNK').map(event => event.payload.text).join(''), task.finalAnswer)
}

/** Public DTOs already sanitize payloads; additionally omit all local credentials from artifacts. */
export function redactEvidence(value: unknown, secrets: readonly string[]): unknown {
  if (typeof value === 'string') {
    let safe = value.replace(/Bearer\s+[A-Za-z0-9._~-]+/gi, 'Bearer [REDACTED]')
      .replace(/\beyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '[REDACTED_JWT]')
    for (const secret of secrets.filter(candidate => candidate.length >= 6)) safe = safe.split(secret).join('[REDACTED]')
    return safe
  }
  if (Array.isArray(value)) return value.map(item => redactEvidence(item, secrets))
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) =>
    [key, /^(authorization|accessToken|password|apiKey|api[-_]?key|secret|token)$/i.test(key) ? '[REDACTED]' : redactEvidence(item, secrets)]))
  return value
}
