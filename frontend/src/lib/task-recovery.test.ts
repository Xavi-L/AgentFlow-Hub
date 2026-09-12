import { describe, expect, it } from 'vitest'
import { createApp, h } from 'vue'
import TaskRecoveryNotice from '../components/TaskRecoveryNotice.vue'
import { recoveryUsageMessage, taskStatusLabel } from '../presentation'
import type { Task, TaskRecovery } from './types'

function recovery(overrides: Partial<TaskRecovery> = {}): TaskRecovery {
  const usage = { inputTokens: 0, outputTokens: 0, totalTokens: 0, tokenUsageQuality: 'UNKNOWN' }
  return { schemaVersion: 'task-recovery-v1', mode: 'CONTROLLED_SINGLE_HOST', recoveryRunId: 'a53a6ea7-3ef5-47ce-8319-a4abf9f7c3d1',
    recoveredAt: '2026-09-12T12:00:00Z', previousStatus: 'RUNNING', reasonCode: 'TASK_RESTART_INTERRUPTED',
    executionOutcome: 'UNKNOWN', recordCompleteness: 'UNCONFIRMED', counterCompleteness: 'UNCONFIRMED',
    recordedUsage: usage, previousTaskUsage: usage, recordedLlmCalls: 0, recordedToolCalls: 0, ...overrides }
}

describe('restart settlement presentation', () => {
  it('distinguishes execution, dispatch and cancelled interruption without changing TaskStatus', () => {
    const running = recovery(), queued = recovery({ previousStatus: 'QUEUED', reasonCode: 'TASK_RESTART_DISPATCH_LOST' })
    expect(taskStatusLabel({ status: 'FAILED', recovery: running })).toBe('执行中断')
    expect(taskStatusLabel({ status: 'FAILED', recovery: queued })).toBe('调度中断')
    expect(taskStatusLabel({ status: 'CANCELLED', recovery: running })).toBe('已取消，执行曾中断')
    expect(taskStatusLabel({ status: 'CANCELLED', recovery: queued })).toBe('已取消，调度曾中断')
    expect(taskStatusLabel({ status: 'FAILED' })).toBe('执行失败')
  })

  it('describes zero and mixed recorded usage without treating interrupted counters as complete', () => {
    expect(recoveryUsageMessage({ recovery: recovery() })).toBe('当前无已记录用量，完整性未确认。')
    const mixed = recovery({ recordedUsage: { inputTokens: 80, outputTokens: 20, totalTokens: 100, tokenUsageQuality: 'MIXED' } })
    expect(recoveryUsageMessage({ recovery: mixed })).toBe('已记录 100 tokens，可能不完整。')
    expect(mixed.recordedUsage.tokenUsageQuality).toBe('MIXED')
    expect(recoveryUsageMessage({ recovery: recovery({ previousStatus: 'QUEUED', executionOutcome: 'NOT_STARTED',
      reasonCode: 'TASK_RESTART_DISPATCH_LOST', recordCompleteness: 'COMPLETE', counterCompleteness: 'COMPLETE' }) }))
      .toBe('本地未开始执行，已记录用量为 0 tokens。')
    expect(recoveryUsageMessage({})).toBe('')
  })

  it('renders unknown execution duration, preserves facts and provides no execution controls', () => {
    const task = { status: 'CANCELLED', recovery: recovery(),
      startedAt: '2026-09-10T12:00:00Z', completedAt: '2026-09-12T12:00:00Z' } as Task
    const container = document.createElement('div'), app = createApp({ render: () => h(TaskRecoveryNotice, { task }) })
    app.mount(container)
    try {
      expect(container.textContent).toContain('已取消，执行曾中断')
      expect(container.textContent).toContain('当前无已记录用量，完整性未确认')
      expect(container.textContent).toContain('预算计数完整性未确认')
      expect(container.textContent).toContain('实际执行耗时未知')
      expect(container.textContent).not.toContain('172800')
      expect(container.querySelector('button')).toBeNull()
    } finally { app.unmount() }
  })
})
