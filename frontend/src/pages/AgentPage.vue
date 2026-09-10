<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { agentApi, buildAgentConfig, defaultAgentDraft, draftFromAgent, isSelectableTool, MAX_AGENT_KNOWLEDGE_BINDINGS, sameAgentConfig, sameBindingIds, toggleBindingId, validateAgentDraft, validateToolSelection, type AgentConfig, type AgentDetail, type AgentDraft, type ToolDefinition } from '../lib/agent-api'
import { agentDrafts, agentMutation } from '../lib/agent-requests'
import { compatible, knowledgeApi, type KnowledgeBase } from '../lib/knowledge-api'
import { requestScope } from '../lib/knowledge-requests'
import type { Page } from '../lib/types'
import AgentConfigurationForm from '../components/AgentConfigurationForm.vue'
import AgentWriteResult from '../components/AgentWriteResult.vue'

const id = String(useRoute().params.agentId), scope = requestScope(), drafts = agentDrafts(id)
const configWrite = agentMutation(scope, id, 'config'), kbWrite = agentMutation(scope, id, 'knowledge')
const toolWrite = agentMutation(scope, id, 'tools'), statusWrite = agentMutation(scope, id, 'status')
const draft = reactive<AgentDraft>(drafts.get<AgentDraft>('config') || defaultAgentDraft())
const selectedKbs = ref(drafts.get<string[]>('knowledge') || []), selectedTools = ref(drafts.get<string[]>('tools') || [])
watch(draft, value => drafts.set('config', value), { flush: 'sync' })
watch(selectedKbs, value => drafts.set('knowledge', value), { flush: 'sync', deep: true })
watch(selectedTools, value => drafts.set('tools', value), { flush: 'sync', deep: true })
const agent = ref<AgentDetail>(), loading = ref(false), error = ref('')
const kbLoaded = ref(false), toolsLoaded = ref(false), kbError = ref(''), toolsError = ref('')
const serverKbs = ref<string[]>([]), serverTools = ref<string[]>([]), tools = ref<ToolDefinition[]>([])
const kbPage = ref(1), kbOptions = ref<Page<KnowledgeBase>>(), kbLoading = ref(false), optionError = ref('')
const kbNames = reactive<Record<string, KnowledgeBase>>({})
const configReadback = ref<AgentDetail>()
const allowedTools = computed(() => tools.value.filter(isSelectableTool))
const dirty = computed(() => !agent.value || !!validateAgentDraft(draft) || !sameAgentConfig(agent.value, buildAgentConfig(draft)))
const kbDirty = computed(() => !sameBindingIds(selectedKbs.value, serverKbs.value))
const kbSelectionError = computed(() => selectedKbs.value.length > MAX_AGENT_KNOWLEDGE_BINDINGS
  ? `最多绑定 ${MAX_AGENT_KNOWLEDGE_BINDINGS} 个知识库，当前已选 ${selectedKbs.value.length} 个；请移除超出部分后保存。` : '')
const toolsDirty = computed(() => !sameBindingIds(selectedTools.value, serverTools.value))
const hasDraft = () => drafts.get<AgentDraft>('config') !== undefined

async function refreshAgent() {
  if (configWrite.state.busy || statusWrite.state.busy) return
  const flight = scope.start('detail'); loading.value = true; error.value = ''
  try {
    const value = await agentApi.get(id, flight.signal)
    if (!flight.current()) return
    if (!hasDraft()) Object.assign(draft, draftFromAgent(value))
    agent.value = value
  } catch (e) { if (flight.current()) error.value = e instanceof Error ? e.message : 'Agent 读取失败' }
  finally { if (flight.current()) loading.value = false }
}
async function loadBindings(kind: 'knowledge' | 'tools') {
  const target = kind === 'knowledge' ? kbWrite : toolWrite
  if (target.state.busy) return
  const flight = scope.start(`bindings-${kind}`)
  const err = kind === 'knowledge' ? kbError : toolsError; err.value = ''
  try {
    if (kind === 'knowledge') {
      const value = await agentApi.getKnowledgeBindings(id, flight.signal)
      if (!flight.current()) return
      serverKbs.value = value.knowledgeBaseIds
      if (drafts.get<string[]>('knowledge') === undefined) selectedKbs.value = [...value.knowledgeBaseIds]
      kbLoaded.value = true
    } else {
      const [binding, catalog] = await Promise.all([agentApi.getToolBindings(id, flight.signal), agentApi.listTools(flight.signal)])
      if (!flight.current()) return
      serverTools.value = binding.toolIds; tools.value = catalog
      if (drafts.get<string[]>('tools') === undefined) selectedTools.value = [...binding.toolIds]
      toolsLoaded.value = true
    }
  } catch (e) { if (flight.current()) err.value = e instanceof Error ? e.message : '绑定读取失败' }
}
async function loadKnowledge(page = kbPage.value) {
  const flight = scope.start('knowledge-options'); kbPage.value = page; kbLoading.value = true; optionError.value = ''
  try {
    const value = await knowledgeApi.listBases(page, flight.signal)
    if (!flight.current()) return
    kbOptions.value = value; value.items.forEach(kb => { kbNames[kb.id] = kb })
  } catch (e) { if (flight.current()) optionError.value = e instanceof Error ? e.message : '知识库列表读取失败' }
  finally { if (flight.current()) kbLoading.value = false }
}
function updateConfig(value: AgentDetail) {
  agent.value = { ...value, status: agent.value?.status ?? value.status }
}
async function saveConfig() {
  configWrite.state.error = validateAgentDraft(draft)
  if (configWrite.state.error || !agent.value) return
  scope.cancel('detail'); loading.value = false
  const config = buildAgentConfig(draft)
  const value = await configWrite.run('保存配置', config, signal => agentApi.update(id, config, signal))
  if (value && scope.active()) updateConfig(value)
}
async function saveKnowledge() {
  if (!kbLoaded.value || kbSelectionError.value) return
  scope.cancel('bindings-knowledge')
  const ids = [...selectedKbs.value]
  const value = await kbWrite.run('保存知识库绑定', ids, signal => agentApi.replaceKnowledgeBindings(id, ids, signal))
  if (value && scope.active()) serverKbs.value = value.knowledgeBaseIds
}
async function saveTools() {
  if (!toolsLoaded.value) return
  toolWrite.state.error = validateToolSelection(selectedTools.value, tools.value)
  if (toolWrite.state.error) return
  scope.cancel('bindings-tools')
  const ids = [...selectedTools.value]
  const value = await toolWrite.run('保存工具绑定', ids, signal => agentApi.replaceToolBindings(id, ids, signal))
  if (value && scope.active()) serverTools.value = value.toolIds
}
async function toggleStatus() {
  if (!agent.value) return
  scope.cancel('detail'); loading.value = false
  const enabled = agent.value.status !== 'ACTIVE'
  const value = await statusWrite.run(enabled ? '启用 Agent' : '停用 Agent', enabled ? 'ACTIVE' : 'DISABLED', signal => agentApi.setEnabled(id, enabled, signal))
  if (value && scope.active() && agent.value) agent.value.status = value.status
}
async function reconcileConfig() {
  scope.cancel('detail'); loading.value = false
  const value = await configWrite.reconcile(signal => agentApi.get(id, signal), (actual, expected) => sameAgentConfig(actual, expected as AgentConfig))
  if (value && scope.active()) { updateConfig(value); configReadback.value = value }
}
async function reconcileStatus() {
  scope.cancel('detail'); loading.value = false
  const value = await statusWrite.reconcile(signal => agentApi.get(id, signal), (actual, expected) => actual.status === expected)
  if (value && scope.active() && agent.value) agent.value.status = value.status
}
async function reconcileKnowledge() {
  scope.cancel('bindings-knowledge')
  const value = await kbWrite.reconcile(signal => agentApi.getKnowledgeBindings(id, signal), (actual, expected) => sameBindingIds(actual.knowledgeBaseIds, expected as string[]))
  if (value && scope.active()) serverKbs.value = value.knowledgeBaseIds
}
async function reconcileTools() {
  scope.cancel('bindings-tools')
  const value = await toolWrite.reconcile(signal => agentApi.getToolBindings(id, signal), (actual, expected) => sameBindingIds(actual.toolIds, expected as string[]))
  if (value && scope.active()) serverTools.value = value.toolIds
}
onBeforeUnmount(scope.dispose)
void refreshAgent(); void loadBindings('knowledge'); void loadBindings('tools'); void loadKnowledge()
</script>

<template>
  <RouterLink to="/agents" class="back-link">← Agent 列表</RouterLink>
  <div class="page-heading"><div><p class="eyebrow">AGENT / 配置与依赖</p><h1 class="break">{{ agent?.name || 'Agent 详情' }}</h1><p class="mono muted">#{{ id }}</p></div><div class="actions"><button class="secondary" :disabled="loading || configWrite.state.busy || statusWrite.state.busy" @click="refreshAgent">刷新详情</button><RouterLink v-if="agent?.status === 'ACTIVE'" :to="`/agents/${id}/run`" class="primary" aria-label="运行 Agent">运行 Agent →</RouterLink></div></div>
  <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="loading" class="muted">正在读取 Agent…</p>
  <template v-if="agent">
    <section class="panel agent-status" data-testid="agent-status"><div class="task-row-head"><div><span class="status" :data-status="agent.status">{{ agent.status === 'ACTIVE' ? '已启用' : '已停用' }}</span><p class="form-hint muted">启停只影响新任务，不会取消已运行任务。运行使用服务端已保存的配置和绑定。</p></div><button class="secondary" :disabled="statusWrite.state.busy || !!statusWrite.unknown()" @click="toggleStatus">{{ agent.status === 'ACTIVE' ? '停用 Agent' : '启用 Agent' }}</button></div><AgentWriteResult :mutation="statusWrite" section="status" @readback="reconcileStatus" /></section>
    <p class="muted form-hint">配置、知识库绑定和工具绑定分别保存。草稿保留在当前标签页，退出登录后清除。</p>
    <div class="agent-detail-grid">
      <section class="panel composer" data-testid="agent-config"><div class="task-row-head"><h2>配置</h2><span class="small-tag">{{ dirty ? '有未保存草稿' : '与已读配置一致' }}</span></div>
        <form @submit.prevent="saveConfig"><AgentConfigurationForm :draft="draft" :disabled="configWrite.state.busy" /><button class="primary wide" :disabled="configWrite.state.busy || !!configWrite.unknown()">保存配置</button></form>
        <AgentWriteResult :mutation="configWrite" section="config" @readback="reconcileConfig" />
        <details v-if="configReadback"><summary>最近读回的服务端配置（草稿未覆盖）</summary><pre>{{ buildAgentConfig(draftFromAgent(configReadback)) }}</pre></details>
      </section>
      <div class="stack">
        <section class="panel" data-testid="knowledge-bindings"><div class="task-row-head"><h2>知识库绑定</h2><span class="small-tag">{{ kbDirty ? '有未保存选择' : '与已读绑定一致' }}</span></div>
          <p class="muted form-hint">绑定成功不代表具备 READY 语料。请在知识库详情核对文档就绪状态；禁用或配置不兼容的库不能用于新任务。</p>
          <p v-if="kbError" class="error" role="alert">{{ kbError }}</p><button v-if="!kbLoaded" class="text-button" @click="loadBindings('knowledge')">重新读取知识库绑定</button>
          <p class="label">已选 {{ selectedKbs.length }} / {{ MAX_AGENT_KNOWLEDGE_BINDINGS }}（含跨页选择，按选择顺序绑定）</p>
          <p v-if="kbSelectionError" class="error" role="alert">{{ kbSelectionError }}</p>
          <div class="selected-bindings"><div v-for="selected in selectedKbs" :key="selected" class="selected-binding" data-testid="selected-kb" :data-kb-id="selected"><span class="break">{{ kbNames[selected]?.name || '未在已加载列表中找到，可能已失效' }}<small class="mono">#{{ selected }}</small></span><button class="text-button" :aria-label="`移除知识库 ${selected}`" :disabled="!kbLoaded || kbWrite.state.busy" @click="selectedKbs = toggleBindingId(selectedKbs, selected, false)">移除</button></div></div>
          <button class="text-button" @click="loadKnowledge()">刷新知识库列表</button>
          <p v-if="optionError" class="error" role="alert">{{ optionError }}</p><p v-if="kbLoading" class="muted">正在读取知识库选项…</p>
          <div class="binding-options" :aria-busy="kbLoading"><label v-for="kb in kbOptions?.items" :key="kb.id" class="binding-option"><input type="checkbox" :aria-label="kb.name" :checked="selectedKbs.includes(kb.id)" :disabled="!kbLoaded || kbWrite.state.busy || kbLoading || (!selectedKbs.includes(kb.id) && (kb.status !== 'ACTIVE' || !compatible(kb) || selectedKbs.length >= MAX_AGENT_KNOWLEDGE_BINDINGS))" @change="selectedKbs = toggleBindingId(selectedKbs, kb.id, ($event.target as HTMLInputElement).checked)"><span class="break">{{ kb.name }}<small>{{ kb.status }} · {{ compatible(kb) ? '配置兼容，文档就绪状态待核对' : '配置不兼容' }}</small></span><RouterLink :to="`/knowledge-bases/${kb.id}`" class="text-button" :aria-label="`查看知识库 ${kb.name}`">查看</RouterLink></label></div>
          <div class="pagination"><button class="secondary" :disabled="kbPage === 1" @click="loadKnowledge(kbPage - 1)">上一页</button><span>第 {{ kbPage }} 页</span><button class="secondary" :disabled="!kbOptions?.hasNext" @click="loadKnowledge(kbPage + 1)">下一页</button></div>
          <button class="primary wide binding-save" :disabled="!kbLoaded || !!kbSelectionError || kbWrite.state.busy || !!kbWrite.unknown()" @click="saveKnowledge">保存知识库绑定</button>
          <AgentWriteResult :mutation="kbWrite" section="knowledge" @readback="reconcileKnowledge" />
          <details v-if="kbLoaded"><summary>最近读取或确认的服务端绑定</summary><p class="mono break">{{ serverKbs.join('、') || '无绑定' }}</p></details>
        </section>
        <section class="panel" data-testid="tool-bindings"><div class="task-row-head"><h2>工具绑定</h2><span class="small-tag">{{ toolsDirty ? '有未保存选择' : '与已读绑定一致' }}</span></div>
          <p class="muted form-hint">仅支持已有工具列表中的 order_query 与 payment_log_query。</p>
          <p v-if="toolsError" class="error" role="alert">{{ toolsError }}</p><button class="text-button" :disabled="toolWrite.state.busy" @click="loadBindings('tools')">刷新工具列表与绑定</button>
          <div v-for="selected in selectedTools.filter(key => !allowedTools.some(tool => tool.id === key))" :key="selected" class="selected-binding notice"><span class="break">工具 {{ selected }} 当前不可绑定，已保留选择</span><button class="text-button" :aria-label="`移除工具 ${selected}`" :disabled="toolWrite.state.busy" @click="selectedTools = toggleBindingId(selectedTools, selected, false)">移除</button></div>
          <label v-for="tool in allowedTools" :key="tool.id" class="binding-option"><input type="checkbox" :aria-label="tool.toolCode" :checked="selectedTools.includes(tool.id)" :disabled="!toolsLoaded || toolWrite.state.busy" @change="selectedTools = toggleBindingId(selectedTools, tool.id, ($event.target as HTMLInputElement).checked)"><span>{{ tool.toolCode }}<small class="break">{{ tool.name }} · #{{ tool.id }}</small></span></label>
          <p v-if="toolsLoaded && !allowedTools.length" class="muted">暂无可绑定的受支持工具。</p>
          <button class="primary wide binding-save" :disabled="!toolsLoaded || toolWrite.state.busy || !!toolWrite.unknown()" @click="saveTools">保存工具绑定</button>
          <AgentWriteResult :mutation="toolWrite" section="tools" @readback="reconcileTools" />
          <details v-if="toolsLoaded"><summary>最近读取或确认的服务端绑定</summary><p class="mono break">{{ serverTools.join('、') || '无绑定' }}</p></details>
        </section>
      </div>
    </div>
  </template>
</template>
