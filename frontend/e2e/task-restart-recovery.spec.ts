import { test, expect, type Page } from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import type { Task, TaskTrace } from '../src/lib/types'

const control = process.env.V02A_CONTROL_DIR
if (!control) throw new Error('Set V02A_CONTROL_DIR to the current disposable JVM restart acceptance run')
type Manifest = { backendUrl: string; frontendUrl: string; username: string; password: string;
  tasks: { queued: string; running: string; cancelled: string }; evidenceBoundary: string }
let manifest: Manifest
test.beforeAll(async () => { manifest = JSON.parse(await readFile(path.join(control, 'browser-manifest.json'), 'utf8')) })

async function publicGet<T>(page: Page, endpoint: string): Promise<T> {
  const token = await page.evaluate(() => JSON.parse(sessionStorage.getItem('agentflow.session.v1')!).token as string)
  const response = await page.request.get(`/api/v1${endpoint}`, { headers: { Authorization: `Bearer ${token}` } })
  expect(response.status()).toBe(200)
  return (await response.json()).data
}

for (const kind of ['queued', 'running', 'cancelled'] as const) {
  test(`A15 ${kind} interruption remains visible across Task, Trace and refresh with no task writes`, async ({ page }, info) => {
    const writes: string[] = []
    page.on('request', request => {
      if (request.method() !== 'GET' && /\/api\/v1\/(?:agents\/\d+\/tasks|tasks\/\d+\/cancel)/.test(request.url())) {
        writes.push(`${request.method()} ${new URL(request.url()).pathname}`)
      }
    })
    await page.goto('/login')
    await page.getByLabel('用户名').fill(manifest.username)
    await page.getByLabel('密码', { exact: true }).fill(manifest.password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(/\/tasks$/)
    const id = manifest.tasks[kind]
    expect(typeof id).toBe('string')
    const task = await publicGet<Task>(page, `/tasks/${id}`)
    const trace = await publicGet<TaskTrace>(page, `/tasks/${id}/trace`)
    expect(task.recovery?.schemaVersion).toBe('task-recovery-v1')
    expect(trace.task).toEqual(task)
    expect(task.finalAnswer ?? null).toBeNull()
    expect(task.status).toBe(kind === 'cancelled' ? 'CANCELLED' : 'FAILED')
    expect(task.recovery?.previousStatus).toBe(kind === 'queued' ? 'QUEUED' : 'RUNNING')
    const label = kind === 'queued' ? '调度中断' : kind === 'running' ? '执行中断' : '已取消，执行曾中断'
    const usage = kind === 'queued' ? '本地未开始执行' : task.recovery!.recordedUsage.totalTokens === 0
      ? '当前无已记录用量，完整性未确认' : `已记录 ${task.recovery!.recordedUsage.totalTokens} tokens，可能不完整`
    await page.goto(`/tasks/${id}`)
    await expect(page.getByTestId('task-status')).toHaveText(label)
    await expect(page.getByTestId('connection')).toHaveText('连接已结束')
    await expect(page.getByTestId('task-recovery')).toContainText(usage)
    await expect(page.getByTestId('task-recovery')).toContainText('实际执行耗时未知')
    await expect(page.getByRole('button', { name: '取消任务', exact: true })).toHaveCount(0)
    await expect(page.getByText('本次任务没有最终答案。', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '刷新并恢复', exact: true }).click()
    await expect(page.getByTestId('connection')).toHaveText('连接已结束')
    await page.reload()
    await expect(page.getByTestId('task-recovery')).toContainText(usage)
    await expect(page.getByTestId('connection')).toHaveText('连接已结束')
    await expect(page.getByTestId('timeline').locator('[data-sequence]')).toHaveCount(trace.events.length)
    await page.screenshot({ path: info.outputPath(`${kind}-task.png`), fullPage: true })
    await page.getByRole('link', { name: '查看 Trace ↗' }).click()
    await expect(page.getByTestId('task-recovery')).toContainText(label)
    await expect(page.getByTestId('task-recovery')).toContainText(usage)
    await page.getByRole('button', { name: '刷新 Trace', exact: true }).click()
    await expect(page.getByTestId('task-recovery')).toContainText(usage)
    await page.screenshot({ path: info.outputPath(`${kind}-trace.png`), fullPage: true })
    const after = await publicGet<Task>(page, `/tasks/${id}`)
    const afterTrace = await publicGet<TaskTrace>(page, `/tasks/${id}/trace`)
    expect(after).toEqual(task)
    expect(afterTrace).toEqual(trace)
    expect(writes).toEqual([])
    const evidence = { caseId: 'A15', variant: kind, taskId: id, status: task.status,
      recovery: task.recovery, eventSequences: trace.events.map(event => String(event.sequenceNo)), taskWrites: writes,
      refreshedGetTraceUnchanged: true, boundary: manifest.evidenceBoundary, browserRetries: 0 }
    const evidencePath = info.outputPath(`${kind}-browser-evidence.json`)
    await writeFile(evidencePath, JSON.stringify(evidence, null, 2) + '\n')
    await info.attach('restart-observation-evidence', { path: evidencePath, contentType: 'application/json' })
  })
}
