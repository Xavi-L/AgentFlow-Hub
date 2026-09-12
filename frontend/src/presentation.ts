export const statuses: Record<string, string> = {
  QUEUED: '等待执行', RUNNING: '执行中', COMPLETED: '已完成', FAILED: '执行失败', CANCELLED: '已取消', TIMED_OUT: '已超时',
}
export const phases: Record<string, string> = {
  PREPARING: '准备执行配置', RETRIEVING: '检索知识库', DECIDING: '判断下一步动作', EXECUTING_TOOL: '查询业务数据', GENERATING: '生成最终答案',
}
export const connections: Record<string, string> = {
  idle: '尚未连接', loading: '恢复历史记录', connecting: '正在连接', connected: '已连接', reconnecting: '正在重连', offline: '连接中断', stopped: '已停止连接', closed: '连接已结束',
}
export const eventNames: Record<string, string> = {
  TASK_CREATED: '任务已创建', TASK_STARTED: '开始执行', PHASE_CHANGED: '阶段变化', RAG_FINISHED: '知识检索完成', DECISION_FINISHED: '决策完成', TOOL_STARTED: '工具开始执行', TOOL_FINISHED: '工具执行结束', FINAL_GENERATION_STARTED: '开始生成答案', ANSWER_CHUNK: '答案已持久化', TASK_COMPLETED: '任务完成', TASK_FAILED: '任务失败', TASK_CANCELLED: '任务已取消', TASK_TIMED_OUT: '任务超时',
}
export function timestamp(value: unknown) {
  if (typeof value !== 'string' || !value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
export function pretty(value: unknown) { return JSON.stringify(value, (_, v) => typeof v === 'bigint' ? v.toString() : v, 2) }
export function message(error: unknown) { return error instanceof Error ? error.message : '请求失败，请稍后重试。' }

const modelFailures: Record<string, string> = {
  AGENT_LLM_OUTPUT_LIMIT: '模型达到输出上限，未生成完整有效内容。请在 Agent 高级设置中检查对应的决策或最终回答输出上限，并核对任务总预算与超时。',
  AGENT_LLM_EMPTY_RESPONSE: '模型未返回有效内容。请检查模型兼容性、思考策略和输出上限。',
  AGENT_LLM_TIMEOUT: '模型调用超时。请检查单次模型调用超时、任务总超时及模型服务状态。',
  AGENT_LLM_REJECTED: '模型服务拒绝了请求。请检查模型名称、输出格式和思考策略是否受支持，并查看 Trace 中的诊断。',
  AGENT_INVALID_DECISION: '模型返回的决策不符合要求。请检查决策输出格式与模型兼容性，并查看 Trace 中的响应。',
}
/** Present actionable guidance without altering the stored public error code or evidence. */
export function taskFailureMessage(code: string, original?: string | null): string {
  return modelFailures[code] || original || '任务执行失败，请查看 Trace。'
}
