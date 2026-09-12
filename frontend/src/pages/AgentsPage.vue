<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { agentApi, restoreAgentDraft, buildAgentConfig, type AgentDraft, type AgentSummary } from '../lib/agent-api'
import { useAgentExecutionOptions } from '../lib/agent-execution-options'
import { agentDrafts, agentMutation } from '../lib/agent-requests'
import { requestScope } from '../lib/knowledge-requests'
import type { Page } from '../lib/types'
import AgentConfigurationForm from '../components/AgentConfigurationForm.vue'
import AgentWriteResult from '../components/AgentWriteResult.vue'

const router = useRouter(), scope = requestScope(), drafts = agentDrafts('new')
const mutation = agentMutation(scope, 'new', 'create')
const draft = reactive<AgentDraft>(restoreAgentDraft(drafts.get<AgentDraft>('config')))
const execution = useAgentExecutionOptions(draft)
watch(draft, value => drafts.set('config', value), { flush: 'sync' })
const result = ref<Page<AgentSummary>>(), index = ref(1), loading = ref(false), error = ref('')
async function refresh(page = index.value) {
  const flight = scope.start('list'); index.value = page; loading.value = true; error.value = ''
  try { const value = await agentApi.list(page, flight.signal); if (flight.current()) result.value = value }
  catch (e) { if (flight.current()) error.value = e instanceof Error ? e.message : 'Agent 列表读取失败' }
  finally { if (flight.current()) loading.value = false }
}
async function create() {
  mutation.state.error = execution.validate(draft)
  if (mutation.state.error) return
  const config = buildAgentConfig(draft)
  const value = await mutation.run('创建 Agent', config, signal => agentApi.create(config, signal))
  if (value && scope.active()) { drafts.remove('config'); await router.push(`/agents/${value.id}`) }
}
async function readback() {
  const flight = scope.start('list'), page = index.value
  loading.value = true
  const value = await mutation.reconcile(async () => {
    const currentPage = await agentApi.list(page, flight.signal)
    if (!flight.current()) throw new Error('列表已切换，请重新核对当前页')
    return currentPage
  }, () => false)
  if (flight.current()) { loading.value = false; if (value) result.value = value }
}
onBeforeUnmount(scope.dispose)
void refresh()
</script>

<template>
  <div class="page-heading"><div><p class="eyebrow">AGENTS / 执行配置</p><h1>Agent</h1><p class="muted">配置任务行为，绑定知识库与业务工具。</p></div><button class="secondary" @click="refresh()">刷新列表</button></div>
  <div class="agent-list-grid">
    <section class="panel composer" data-testid="agent-config"><p class="section-label">01 / 新的 Agent</p><h2>创建 Agent</h2>
      <form @submit.prevent="create"><AgentConfigurationForm :draft="draft" :execution="execution.state" @retry-options="execution.reload" :disabled="mutation.state.busy" /><button class="primary wide" :disabled="mutation.state.busy || !!mutation.unknown() || execution.state.loading">{{ mutation.state.busy ? '正在创建…' : '创建 Agent →' }}</button></form>
      <AgentWriteResult :mutation="mutation" section="create" create @readback="readback" />
    </section>
    <section class="panel composer" data-testid="agent-list" aria-label="Agent 列表" :aria-busy="loading">
      <div class="task-row-head"><h2>我的 Agent</h2><span v-if="result" class="muted">共 {{ result.total }} 个</span></div>
      <p v-if="error" class="error" role="alert">{{ error }}</p><p v-else-if="loading" class="muted">正在读取 Agent…</p><p v-else-if="!result?.items.length" class="empty">这一页没有 Agent。创建后可继续配置依赖。</p>
      <RouterLink v-for="agent in result?.items" :key="agent.id" :to="`/agents/${agent.id}`" class="task-row" :data-agent-id="agent.id"><div class="task-row-head"><strong class="break">{{ agent.name }}</strong><span class="status" :data-status="agent.status">{{ agent.status === 'ACTIVE' ? '已启用' : '已停用' }}</span></div><p>{{ agent.description || '暂无描述' }}</p><small class="mono muted">#{{ agent.id }}</small><span class="row-arrow">↗</span></RouterLink>
      <div class="pagination"><button class="secondary" :disabled="index === 1" @click="refresh(index - 1)">上一页</button><span>第 {{ index }} 页</span><button class="secondary" :disabled="!result?.hasNext" @click="refresh(index + 1)">下一页</button></div>
    </section>
  </div>
</template>
