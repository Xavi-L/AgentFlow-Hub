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
