import { onBeforeUnmount, reactive, watch } from 'vue'
import { agentApi, validateAgentDraft, type AgentDraft, type AgentExecutionOptions } from './agent-api'
import { requestScope } from './knowledge-requests'

export interface ExecutionOptionsState {
  modelName: string; options?: AgentExecutionOptions; loading: boolean; error: string
}

/** Invalidates immediately when typing, including during the debounce window. */
export function executionOptionsLoader(read = agentApi.executionOptions) {
  const scope = requestScope()
  const state = reactive<ExecutionOptionsState>({ modelName: '', loading: false, error: '' })
  let timer: ReturnType<typeof setTimeout> | undefined
  async function reload() {
    clearTimeout(timer)
    const modelName = state.modelName, flight = scope.start('execution-options')
    state.options = undefined; state.error = ''
    state.loading = !!modelName
    if (!modelName) return
    try {
      const value = await read(modelName, flight.signal)
      if (flight.current() && state.modelName === modelName) state.options = value
    } catch (error) {
      if (flight.current()) state.error = error instanceof Error ? error.message : '高级设置读取失败'
    } finally { if (flight.current()) state.loading = false }
  }
  return {
    state, reload,
    setModel(modelName: string) {
      clearTimeout(timer); scope.cancel('execution-options')
      state.modelName = modelName.trim(); state.options = undefined; state.error = ''
      state.loading = !!state.modelName
      if (state.modelName) timer = setTimeout(() => { void reload() }, 250)
    },
    validate(draft: AgentDraft): string {
      const error = validateAgentDraft(draft)
      if (error) return error
      if (!state.options || state.modelName !== draft.modelName.trim()) {
        return state.error ? '无法确认当前模型的高级设置，请重试读取后再保存' : '请等待当前模型的高级设置读取完成后再保存'
      }
      return validateAgentDraft(draft, state.options)
    },
    dispose() { clearTimeout(timer); scope.dispose() },
  }
}

export function useAgentExecutionOptions(draft: AgentDraft) {
  const loader = executionOptionsLoader()
  watch(() => draft.modelName.trim(), value => loader.setModel(value), { immediate: true, flush: 'sync' })
  onBeforeUnmount(loader.dispose)
  return loader
}
