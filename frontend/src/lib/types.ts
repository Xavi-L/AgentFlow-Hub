export type Decimal = string
export type Json = null | boolean | number | string | Json[] | { [key: string]: Json }
export type TaskStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT'
export interface User { id: Decimal; username: string; displayName: string; role: string }
export interface LoginResponse { accessToken: string; tokenType: string; expiresIn: number; user: User }
export interface Page<T> { items: T[]; page: number; pageSize: number; total: number | Decimal; hasNext: boolean }
export interface Agent { id: Decimal; name: string; description: string | null; status: string; modelProvider: string; modelName: string }
export interface TaskSummary {
  taskId: Decimal; agentId: Decimal; status: TaskStatus; phase: string | null
  terminationReason: string | null; userInput: string; createdAt: string; updatedAt: string; completedAt: string | null
}
export interface Task extends TaskSummary {
  maxDecisionTurns: number; maxToolCalls: number; maxTotalTokens: number; reservedFinalTokens: number
  decisionTurnsUsed: number; toolCallsUsed: number; inputTokens: number; outputTokens: number; totalTokens: number
  tokenUsageQuality: string; finalAnswer: string | null; citations: Json[]
  errorCode: string | null; errorMessage: string | null; cancelRequestedAt: string | null
  startedAt: string | null; lastEventSequence: Decimal
}
export const EVENT_TYPES = [
  'TASK_CREATED', 'TASK_STARTED', 'PHASE_CHANGED', 'RAG_FINISHED', 'DECISION_FINISHED',
  'TOOL_STARTED', 'TOOL_FINISHED', 'FINAL_GENERATION_STARTED', 'ANSWER_CHUNK',
  'TASK_COMPLETED', 'TASK_FAILED', 'TASK_CANCELLED', 'TASK_TIMED_OUT',
] as const
export type EventType = typeof EVENT_TYPES[number]
export interface TaskEvent {
  id?: Decimal; taskId: Decimal; sequenceNo: Decimal; eventType: EventType
  createdAt: string; payload: Record<string, Json>
}
export interface TraceLlmCall {
  id: Decimal; callType: string; provider: string; requestedModel: string; resolvedModel: string | null
  requestSnapshot: Json; responseText: string | null; finishReason: string | null; providerRequestId: string | null
  inputTokens: number | null; outputTokens: number | null; totalTokens: number | null; usageQuality: string
  latencyMs: number | Decimal; status: string; errorCode: string | null; errorMessage: string | null; createdAt: string
}
export interface TraceRagHit {
  id: Decimal; rankNo: number; citationId: string; chunkIdSnapshot: Decimal; documentIdSnapshot: Decimal
  knowledgeBaseIdSnapshot: Decimal; vectorGeneration: number | Decimal; score: number
  contentSnapshot: string; metadataSnapshot: Json; createdAt: string
}
export interface TraceRagRetrieval {
  id: Decimal; query: string; embeddingProfileCode: string; corpusSnapshot: Json; topK: number
  similarityThreshold: number; candidateCount: number; validHitCount: number; staleHitCount: number
  latencyMs: number | Decimal; status: string; errorCode: string | null; errorMessage: string | null
  createdAt: string; hits: TraceRagHit[]
}
export interface TraceToolCall {
  id: Decimal; toolId: Decimal; toolCode: string; toolName: string; arguments: Json; result: Json
  status: string; retryCount: number; latencyMs: number | null; errorCode: string | null; errorMessage: string | null
  startedAt: string | null; finishedAt: string | null; createdAt: string
}
export interface TraceStep {
  id: Decimal; stepIndex: number; stepType: string; status: string; title: string; summary: Json
  errorCode: string | null; errorMessage: string | null; startedAt: string | null; endedAt: string | null
  latencyMs: number | Decimal | null; createdAt: string
  llmCalls: TraceLlmCall[]; ragRetrievals: TraceRagRetrieval[]; toolCalls: TraceToolCall[]
}
export interface TaskTrace { task: Task; executionSnapshot: Json; steps: TraceStep[]; events: TaskEvent[] }
export const isTerminal = (status?: string): boolean => ['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(status ?? '')
