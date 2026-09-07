import { reactive } from 'vue'
import { ApiError } from './api'
import type { requestScope } from './knowledge-requests'
import { onSessionClear, session, sessionRevision, storage } from './session'

export type AgentDraftSection = 'config' | 'knowledge' | 'tools'
export type AgentMutationSection = AgentDraftSection | 'create' | 'status'
interface Draft { ownerId: string; resource: string; section: AgentDraftSection; value: unknown }
interface PendingWrite {
  ownerId: string; resource: string; section: AgentMutationSection
  attemptId: string; label: string; expected: unknown
}
const DRAFT_KEY = 'agentflow.agent-drafts.v1'
const WRITE_KEY = 'agentflow.agent-writes.v1'
const drafts = reactive<Record<string, Draft>>({})
const pending = reactive<Record<string, PendingWrite>>({})
const keyFor = (ownerId: string, resource: string, section: string) => JSON.stringify([ownerId, resource, section])
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

function persist(key: string, values: object) {
  try { storage()?.setItem(key, JSON.stringify(values)) } catch { /* Memory still preserves drafts and prevents duplicate writes. */ }
}

// These records contain editor payloads and owner IDs only, never the session or its JWT.
// Restore only the signed-in owner; a stale editor cannot read or persist another session's draft.
for (const [key, target] of [[DRAFT_KEY, drafts], [WRITE_KEY, pending]] as const) {
  try {
    const saved: unknown = JSON.parse(storage()?.getItem(key) || '{}')
    if (saved && typeof saved === 'object' && !Array.isArray(saved)) {
      for (const value of Object.values(saved) as (Draft & Partial<PendingWrite>)[]) {
        if (!value || !session.user || value.ownerId !== session.user.id || typeof value.resource !== 'string'
          || !['config', 'knowledge', 'tools', 'create', 'status'].includes(value.section)) continue
        if (key === DRAFT_KEY && ['config', 'knowledge', 'tools'].includes(value.section) && 'value' in value) {
          drafts[keyFor(value.ownerId, value.resource, value.section)] = value
        } else if (key === WRITE_KEY && typeof value.attemptId === 'string' && typeof value.label === 'string' && 'expected' in value) {
          pending[keyFor(value.ownerId, value.resource, value.section)] = value as PendingWrite
        }
      }
    }
    persist(key, target)
  } catch { try { storage()?.removeItem(key) } catch { /* Unavailable storage is supported. */ } }
}
onSessionClear(() => {
  for (const [key, target] of [[DRAFT_KEY, drafts], [WRITE_KEY, pending]] as const) {
    Object.keys(target).forEach(entry => delete target[entry])
    try { storage()?.removeItem(key) } catch { /* Memory is already cleared. */ }
  }
})

function ownerScope() {
  const ownerId = session.user?.id
  const revision = sessionRevision()
  return { ownerId, active: () => Boolean(ownerId) && session.user?.id === ownerId && sessionRevision() === revision }
}

/** Independent config/knowledge/tool drafts survive route changes and browser refresh. */
export function agentDrafts(resource: string) {
  const owner = ownerScope()
  const key = (section: AgentDraftSection) => keyFor(owner.ownerId || '', resource, section)
  return {
    get<T>(section: AgentDraftSection): T | undefined {
      const entry = owner.active() ? drafts[key(section)] : undefined
      return entry ? clone(entry.value) as T : undefined
    },
    set(section: AgentDraftSection, value: unknown) {
      if (!owner.active()) return
      drafts[key(section)] = { ownerId: owner.ownerId!, resource, section, value: clone(value) }
      persist(DRAFT_KEY, drafts)
    },
    remove(section: AgentDraftSection) {
      if (!owner.active()) return
      delete drafts[key(section)]
      persist(DRAFT_KEY, drafts)
    },
  }
}

/** No Agent write has an idempotency contract. An uncertain write may only be read back. */
export function agentMutation(scope: ReturnType<typeof requestScope>, resource: string, section: AgentMutationSection) {
  const owner = ownerScope()
  const key = keyFor(owner.ownerId || '', resource, section)
  const active = () => owner.active() && scope.active()
  const currentPending = () => owner.active() ? pending[key] : undefined
  let acceptedReadbackAttempt: string | undefined
  const state = reactive({
    busy: false, error: '', notice: '',
    get unknown() { return currentPending()?.label },
    readbackResult: 'none' as 'none' | 'matched' | 'mismatch',
  })
  function resetFeedback() {
    state.error = ''; state.notice = ''; state.readbackResult = 'none'; acceptedReadbackAttempt = undefined
  }
  return {
    state,
    unknown: () => currentPending()?.label,
    async run<T>(label: string, expected: unknown, action: (signal: AbortSignal) => Promise<T>): Promise<T | undefined> {
      if (state.busy || currentPending() || !active()) return
      resetFeedback()
      let snapshot: unknown
      try { snapshot = clone(expected ?? null) } catch { state.error = '保存内容无法序列化'; return }
      state.busy = true
      const attemptId = crypto.randomUUID()
      pending[key] = { ownerId: owner.ownerId!, resource, section, attemptId, label, expected: snapshot }
      persist(WRITE_KEY, pending)
      const flight = scope.start(`agent-write:${resource}:${section}`)
      const current = () => active() && flight.current() && currentPending()?.attemptId === attemptId
      try {
        const result = await action(flight.signal)
        if (!current()) return
        delete pending[key]; persist(WRITE_KEY, pending)
        state.notice = `${label}已确认`
        return result
      } catch (error) {
        if (!current()) return
        if (!(error instanceof ApiError) || error.outcomeUnknown) {
          state.notice = `${label}结果待确认。服务器可能仍在处理，请读回核对；不会自动重新提交。`
        } else {
          delete pending[key]; persist(WRITE_KEY, pending)
          state.error = error.message
        }
      } finally { if (active() && flight.current()) state.busy = false }
    },
    async reconcile<T>(read: (signal: AbortSignal) => Promise<T>, matches: (actual: T, expected: unknown) => boolean): Promise<T | undefined> {
      const entry = currentPending()
      if (!entry || state.busy || !active()) return
      resetFeedback(); state.busy = true
      const flight = scope.start(`agent-readback:${resource}:${section}`)
      const current = () => active() && flight.current() && currentPending()?.attemptId === entry.attemptId
      try {
        const actual = await read(flight.signal)
        if (!current()) return
        // A list row with the same name/config cannot identify a non-idempotent create request.
        if (section !== 'create' && matches(actual, clone(entry.expected))) {
          delete pending[key]; persist(WRITE_KEY, pending)
          state.readbackResult = 'matched'
          state.notice = '读回的当前状态与本次保存内容一致，已解除待确认状态。'
        } else {
          acceptedReadbackAttempt = entry.attemptId
          state.readbackResult = 'mismatch'
          state.notice = section === 'create'
            ? '列表已读回。相同名称不能确认是本次创建，请核对列表并明确接受当前结果后再新建；原请求仍可能晚到。'
            : '读回状态与本次保存内容不一致，不能据此判断写入未提交。请核对并明确接受当前状态；原请求仍可能晚到。'
        }
        return actual
      } catch (error) {
        if (!current()) return
        state.error = error instanceof Error ? error.message : '读回失败'
        state.notice = '读回尚未成功，写入结果仍待确认。'
      } finally { if (active() && flight.current()) state.busy = false }
    },
    /** Only a successful, explicit readback review can release an unresolved write. */
    acceptReadback(): boolean {
      if (!active() || state.busy || state.readbackResult !== 'mismatch' || !acceptedReadbackAttempt
        || currentPending()?.attemptId !== acceptedReadbackAttempt) return false
      delete pending[key]; persist(WRITE_KEY, pending)
      acceptedReadbackAttempt = undefined
      state.notice = '已接受读回的当前状态。上次请求仍可能晚到，请继续核对服务器状态。'
      return true
    },
  }
}
