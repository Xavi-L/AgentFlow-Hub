import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './api'
import { agentDrafts, agentMutation } from './agent-requests'
import { requestScope } from './knowledge-requests'
import { clearSession, setSession } from './session'

const login = (id = '1') => setSession({ accessToken: `secret-token-${id}`, tokenType: 'Bearer', expiresIn: 3600,
  user: { id, username: `user-${id}`, displayName: 'Test', role: 'USER' } })
const unknownFailure = () => Promise.reject(new ApiError('timeout', 0, 'NETWORK_ERROR', true))
afterEach(() => { clearSession(); vi.restoreAllMocks() })

describe('owner-scoped Agent drafts', () => {
  it('preserves independent snapshots across page recreation without storing the JWT', () => {
    login()
    const draft = agentDrafts('7'), config = { name: 'Draft', systemPrompt: 'Answer with evidence', maxSteps: 8 }
    draft.set('config', config); draft.set('knowledge', ['9', '9007199254740993']); draft.set('tools', ['4'])
    config.name = 'Changed outside editor'
    const restored = agentDrafts('7')
    expect(restored.get('config')).toEqual({ ...config, name: 'Draft' })
    expect(restored.get('knowledge')).toEqual(['9', '9007199254740993'])
    expect(restored.get('tools')).toEqual(['4'])
    expect(agentDrafts('8').get('config')).toBeUndefined()
    const copy = restored.get<{ name: string }>('config')!
    copy.name = 'Mutated return value'
    expect(restored.get<{ name: string }>('config')?.name).toBe('Draft')
    draft.remove('config')
    expect(restored.get('config')).toBeUndefined()
    expect(restored.get('knowledge')).toEqual(['9', '9007199254740993'])
    expect(sessionStorage.getItem('agentflow.agent-drafts.v1')).not.toContain('secret-token')
  })

  it('clears drafts on logout and prevents stale editors from reading or overwriting a later owner', () => {
    login()
    const oldEditor = agentDrafts('7')
    oldEditor.set('config', { name: 'Private' })
    clearSession()
    expect(sessionStorage.getItem('agentflow.agent-drafts.v1')).toBeNull()
    login('2')
    const nextEditor = agentDrafts('7')
    expect(nextEditor.get('config')).toBeUndefined()
    nextEditor.set('config', { name: 'Second owner' })
    oldEditor.set('config', { name: 'Stale write' }); oldEditor.remove('config')
    expect(oldEditor.get('config')).toBeUndefined()
    expect(nextEditor.get('config')).toEqual({ name: 'Second owner' })
  })
})

describe('independent Agent writes and readback', () => {
  it('allows all three saves in parallel without cancellation and blocks duplicate submissions', async () => {
    login()
    const scope = requestScope()
    const requests = ['config', 'knowledge', 'tools'].map(section => agentMutation(scope, '7', section as 'config' | 'knowledge' | 'tools'))
    const signals: AbortSignal[] = [], finish: ((value: string) => void)[] = []
    const action = vi.fn((signal: AbortSignal) => new Promise<string>(resolve => { signals.push(signal); finish.push(resolve) }))
    const flights = requests.map((mutation, i) => mutation.run('保存', { index: i }, action))
    await requests[0]!.run('重复保存', { index: 0 }, action)
    expect(action).toHaveBeenCalledTimes(3)
    expect(signals.every(signal => !signal.aborted)).toBe(true)
    expect(JSON.parse(sessionStorage.getItem('agentflow.agent-writes.v1')!)).toEqual(expect.objectContaining({
      '["1","7","config"]': expect.objectContaining({ expected: { index: 0 } }),
    }))
    finish.forEach((resolve, i) => resolve(`confirmed-${i}`))
    expect(await Promise.all(flights)).toEqual(['confirmed-0', 'confirmed-1', 'confirmed-2'])
    expect(requests.every(mutation => !mutation.state.busy && !mutation.unknown())).toBe(true)
    scope.dispose()
  })

  it('keeps mismatched or failed readbacks unknown until the user explicitly accepts a successful read', async () => {
    login()
    const scope = requestScope(), mutation = agentMutation(scope, '7', 'config')
    const expected = { name: 'Expected' }
    const write = vi.fn(unknownFailure)
    await mutation.run('保存配置', expected, write)
    expected.name = 'Edited later'
    expect(mutation.state.unknown).toBe('保存配置')
    expect(mutation.acceptReadback()).toBe(false)
    const matcher = vi.fn((actual: { name: string }, sent: unknown) => actual.name === (sent as { name: string }).name)
    await mutation.reconcile(async () => ({ name: 'Older server value' }), matcher)
    expect(matcher).toHaveBeenCalledWith({ name: 'Older server value' }, { name: 'Expected' })
    expect(mutation.state.readbackResult).toBe('mismatch')
    await mutation.run('再次保存', expected, write)
    expect(write).toHaveBeenCalledTimes(1)
    await mutation.reconcile(async () => { throw new Error('Read failed') }, matcher)
    expect(mutation.state.readbackResult).toBe('none')
    expect(mutation.acceptReadback()).toBe(false)
    expect(mutation.unknown()).toBe('保存配置')
    await mutation.reconcile(async () => ({ name: 'Older server value' }), matcher)
    expect(mutation.acceptReadback()).toBe(true)
    expect(mutation.unknown()).toBeUndefined()
    await mutation.run('保存新配置', expected, async () => 'ok')
    expect(mutation.state.notice).toBe('保存新配置已确认')
    scope.dispose()
  })

  it('releases a matching update through GET reconciliation without replaying the write', async () => {
    login()
    const scope = requestScope(), mutation = agentMutation(scope, '7', 'knowledge')
    const write = vi.fn(unknownFailure)
    await mutation.run('保存知识库', ['9'], write)
    await mutation.reconcile(async () => ['9'], (actual, expected) => JSON.stringify(actual) === JSON.stringify(expected))
    expect(mutation.state.readbackResult).toBe('matched')
    expect(mutation.unknown()).toBeUndefined()
    expect(write).toHaveBeenCalledTimes(1)
    expect(mutation.acceptReadback()).toBe(false)
    scope.dispose()
  })

  it('never equates a same-name list row with an unknown create and requires explicit list review', async () => {
    login()
    const scope = requestScope(), mutation = agentMutation(scope, 'new', 'create')
    const write = vi.fn(unknownFailure), matcher = vi.fn(() => true)
    await mutation.run('创建 Agent', { name: 'Same name' }, write)
    await mutation.reconcile(async () => [{ id: '7', name: 'Same name' }], matcher)
    expect(matcher).not.toHaveBeenCalled()
    expect(mutation.unknown()).toBe('创建 Agent')
    expect(mutation.state.readbackResult).toBe('mismatch')
    await mutation.run('创建 Agent', { name: 'Same name' }, write)
    expect(write).toHaveBeenCalledTimes(1)
    expect(mutation.acceptReadback()).toBe(true)
    expect(mutation.unknown()).toBeUndefined()
    scope.dispose()
  })

  it('ignores late write/read responses after leaving and clears all pending state on logout', async () => {
    login()
    const scope = requestScope(), mutation = agentMutation(scope, '7', 'tools')
    let resolve!: (value: string[]) => void
    let signal!: AbortSignal
    const saving = mutation.run('保存工具', ['4'], received => {
      signal = received
      return new Promise<string[]>(done => { resolve = done })
    })
    scope.dispose(); expect(signal.aborted).toBe(true)
    resolve(['4'])
    expect(await saving).toBeUndefined()
    const nextScope = requestScope(), restored = agentMutation(nextScope, '7', 'tools')
    expect(restored.unknown()).toBe('保存工具')
    const readback = restored.reconcile(() => new Promise<string[]>(done => { resolve = done }), () => true)
    nextScope.dispose(); resolve(['4'])
    expect(await readback).toBeUndefined()
    expect(restored.unknown()).toBe('保存工具')
    clearSession()
    expect(restored.unknown()).toBeUndefined()
    expect(sessionStorage.getItem('agentflow.agent-writes.v1')).toBeNull()
  })

  it('permits corrected writes after a definite rejection, preserving the draft', async () => {
    login()
    const scope = requestScope(), mutation = agentMutation(scope, '7', 'config'), draft = agentDrafts('7')
    draft.set('config', { name: '' })
    await mutation.run('保存配置', { name: '' }, async () => { throw new ApiError('名称必填', 400, 'COMMON_PARAM_INVALID') })
    expect(mutation.unknown()).toBeUndefined()
    expect(mutation.state.error).toBe('名称必填')
    expect(draft.get('config')).toEqual({ name: '' })
    expect(await mutation.run('保存配置', { name: 'Valid' }, async () => 'saved')).toBe('saved')
    scope.dispose()
  })

  it('restores drafts and pending expected content after a module reload without resending', async () => {
    login()
    agentDrafts('7').set('knowledge', ['9', '9007199254740993'])
    const scope = requestScope(), mutation = agentMutation(scope, '7', 'knowledge')
    await mutation.run('保存知识库', ['9'], unknownFailure)
    scope.dispose()
    vi.resetModules()
    const restored = await import('./agent-requests')
    const nextSession = await import('./session')
    const nextScope = (await import('./knowledge-requests')).requestScope()
    try {
      expect(restored.agentDrafts('7').get('knowledge')).toEqual(['9', '9007199254740993'])
      const restoredWrite = restored.agentMutation(nextScope, '7', 'knowledge')
      const replay = vi.fn(async () => ['9'])
      await restoredWrite.run('保存知识库', ['9'], replay)
      expect(replay).not.toHaveBeenCalled()
      const matcher = vi.fn(() => true)
      await restoredWrite.reconcile(async () => ['9'], matcher)
      expect(matcher).toHaveBeenCalledWith(['9'], ['9'])
      expect(restoredWrite.unknown()).toBeUndefined()
    } finally { nextScope.dispose(); nextSession.clearSession() }
  })
})
