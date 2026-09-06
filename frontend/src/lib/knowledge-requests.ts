import { reactive } from 'vue'
import { ApiError } from './api'
import { onSessionClear, session, sessionRevision, storage } from './session'

/** A page owns its requests. Each read lane accepts only its newest response. */
export function requestScope() {
  const lanes = new Map<string, AbortController>()
  const revision = sessionRevision()
  let closed = false
  const active = () => !closed && revision === sessionRevision()
  const cancel = (lane: string) => { lanes.get(lane)?.abort(); lanes.delete(lane) }
  const stop = onSessionClear(dispose)
  function dispose() { closed = true; lanes.forEach(c => c.abort()); lanes.clear(); stop() }
  return {
    active, dispose, cancel,
    start(lane: string) {
      cancel(lane)
      const controller = new AbortController()
      if (!active()) controller.abort()
      lanes.set(lane, controller)
      return { signal: controller.signal, current: () => active() && lanes.get(lane) === controller && !controller.signal.aborted }
    },
  }
}

const KEY = 'agentflow.knowledge-writes.v1'
interface PendingWrite { ownerId: string; label: string }
const pending = reactive<Record<string, PendingWrite>>({})
try { Object.assign(pending, JSON.parse(storage()?.getItem(KEY) || '{}')) } catch { /* Memory still guards duplicate submissions. */ }
const persist = () => { try { storage()?.setItem(KEY, JSON.stringify(pending)) } catch { /* Browser storage may be unavailable. */ } }
onSessionClear(() => {
  Object.keys(pending).forEach(key => delete pending[key])
  try { storage()?.removeItem(KEY) } catch { /* Memory is already cleared. */ }
})

/** No idempotency contract exists for these writes. Never replay one after uncertainty. */
export function knowledgeMutation(scope: ReturnType<typeof requestScope>, key: string) {
  const state = reactive({ busy: false, error: '', notice: '' })
  const unknown = () => pending[key]?.ownerId === session.user?.id ? pending[key]?.label : undefined
  return {
    state, unknown,
    acknowledge() { if (!state.busy) { delete pending[key]; persist(); state.notice = '' } },
    async run<T>(label: string, action: (signal: AbortSignal) => Promise<T>): Promise<T | undefined> {
      if (state.busy || unknown() || !scope.active()) return
      state.busy = true; state.error = ''; state.notice = ''
      pending[key] = { ownerId: session.user!.id, label }; persist()
      const flight = scope.start('mutation')
      try {
        const result = await action(flight.signal)
        if (!flight.current()) return
        delete pending[key]; persist()
        state.notice = `${label}已确认`
        return result
      } catch (error) {
        if (!flight.current()) return
        if (!(error instanceof ApiError) || error.outcomeUnknown) {
          state.notice = `${label}结果待确认。服务器可能仍在处理，请刷新核对；不会自动重新提交。`
        } else {
          delete pending[key]; persist()
          state.error = error.message
        }
      } finally { if (flight.current()) state.busy = false }
    },
  }
}

export const POLL_INTERVAL = 3000
export const POLL_LIMIT = 20
export const POLL_WINDOW = 60000
/** Polls GETs only; manual refresh starts a new bounded observation window. */
export function boundedPoll(read: () => Promise<boolean>, active: () => boolean) {
  const state = reactive({ running: false, attempts: 0, message: '' })
  let timer: ReturnType<typeof setTimeout> | undefined
  let epoch = 0, deadline = 0
  function stop() { epoch++; clearTimeout(timer); state.running = false }
  function schedule(version: number) {
    if (version !== epoch || !active()) { stop(); return }
    if (state.attempts >= POLL_LIMIT || Date.now() + POLL_INTERVAL > deadline) {
      state.running = false; state.message = '自动刷新已达上限，处理结果仍以服务端为准，可手动刷新。'; return
    }
    timer = setTimeout(async () => {
      if (version !== epoch || !active()) return
      if (Date.now() >= deadline) {
        state.running = false; state.message = '自动刷新已达上限，处理结果仍以服务端为准，可手动刷新。'; return
      }
      state.attempts++
      const again = await read()
      if (version !== epoch || !active()) return
      if (again) schedule(version)
      else { state.running = false; state.message = '自动刷新已停止，可手动刷新查看最新状态。' }
    }, POLL_INTERVAL)
  }
  return {
    state, stop,
    start(needed: boolean) {
      stop(); state.attempts = 0; state.message = ''
      if (!needed || !active()) return
      state.running = true; deadline = Date.now() + POLL_WINDOW; schedule(epoch)
    },
  }
}
