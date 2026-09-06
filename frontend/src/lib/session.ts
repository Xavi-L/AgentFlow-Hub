import { reactive } from 'vue'
import type { LoginResponse, User } from './types'

const STORAGE_KEY = 'agentflow.session.v1'
const listeners = new Set<() => void>()
let revision = 0
let expiryTimer: ReturnType<typeof setTimeout> | undefined
export const session = reactive<{ token: string | null; user: User | null; expiresAt: number }>({ token: null, user: null, expiresAt: 0 })

export function storage(): Storage | undefined {
  try { return typeof window === 'undefined' ? undefined : window.sessionStorage } catch { return undefined }
}
export function sessionRevision(): number { return revision }
export function onSessionClear(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
export function clearSession(): void {
  revision++
  clearTimeout(expiryTimer)
  Object.assign(session, { token: null, user: null, expiresAt: 0 })
  try { storage()?.removeItem(STORAGE_KEY) } catch { /* Clearing memory still invalidates the session. */ }
  listeners.forEach((listener) => listener())
}
function scheduleExpiry(): void {
  clearTimeout(expiryTimer)
  expiryTimer = setTimeout(() => {
    if (Date.now() >= session.expiresAt) clearSession()
    else scheduleExpiry()
  }, Math.min(Math.max(session.expiresAt - Date.now(), 0), 2147483647))
}
export function hasSession(): boolean {
  if (session.token && Date.now() >= session.expiresAt) clearSession()
  return session.token !== null
}
export function setSession(result: LoginResponse): void {
  clearSession()
  if (!result.accessToken || !Number.isSafeInteger(result.expiresIn) || result.expiresIn <= 0) throw new Error('Invalid login response')
  Object.assign(session, { token: result.accessToken, user: result.user, expiresAt: Date.now() + result.expiresIn * 1000 })
  try { storage()?.setItem(STORAGE_KEY, JSON.stringify(session)) } catch { /* Memory-only sessions remain usable. */ }
  scheduleExpiry()
}

try {
  const saved = storage()?.getItem(STORAGE_KEY)
  const restored = saved ? JSON.parse(saved) : null
  if (restored && typeof restored.token === 'string' && restored.token && typeof restored.user?.id === 'string'
    && Number.isFinite(restored.expiresAt) && restored.expiresAt > Date.now()) {
    Object.assign(session, restored)
    scheduleExpiry()
  } else storage()?.removeItem(STORAGE_KEY)
} catch { /* An unavailable or damaged browser session requires login. */ }
