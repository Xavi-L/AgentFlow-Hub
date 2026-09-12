<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { ElTabs, ElTabPane } from 'element-plus'
import 'element-plus/theme-chalk/el-tabs.css'
import 'element-plus/theme-chalk/el-tab-pane.css'
import { useRoute } from 'vue-router'
import { api } from '../lib/api'
import { statuses, timestamp, pretty, message, taskFailureMessage } from '../presentation'
import AnswerPanel from '../components/AnswerPanel.vue'
import EventTimeline from '../components/EventTimeline.vue'

const route = useRoute()
const trace = shallowRef<Awaited<ReturnType<typeof api.getTrace>> | null>(null), loading = ref(false), error = ref('')
let controller: AbortController | undefined, disposed = false
const rag = computed(() => trace.value?.steps.flatMap(s => s.ragRetrievals) || [])
const llm = computed(() => trace.value?.steps.flatMap(s => s.llmCalls) || [])
const tools = computed(() => trace.value?.steps.flatMap(s => s.toolCalls) || [])
async function load() {
  controller?.abort(); const current = new AbortController(); controller = current; loading.value = true; error.value = ''
  try { const result = await api.getTrace(String(route.params.taskId), current.signal); if (!disposed && current === controller) trace.value = result }
  catch (e) { if (!disposed && !current.signal.aborted) error.value = message(e) }
  finally { if (!disposed && current === controller) loading.value = false }
}
onMounted(load)
onBeforeUnmount(() => { disposed = true; controller?.abort(); trace.value = null })
</script>

<template>
  <RouterLink :to="`/tasks/${route.params.taskId}`" class="back-link">← 返回任务</RouterLink>
  <div class="page-heading"><div><div class="eyebrow">EXECUTION TRACE</div><h1>执行记录</h1><p class="mono muted break">#{{ route.params.taskId }}</p></div><button class="secondary" :disabled="loading" @click="load">{{ loading ? '正在读取…' : '刷新 Trace' }}</button></div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <template v-if="trace"><section class="panel trace-overview"><div><span class="label">任务状态</span><strong class="status" :data-status="trace.task.status">{{ statuses[trace.task.status] }}</strong></div><div><span class="label">步骤</span><strong>{{ trace.steps.length }}</strong></div><div><span class="label">持久事件</span><strong>{{ trace.events.length }}</strong></div><div><span class="label">Token 用量 / 质量</span><strong>{{ trace.task.totalTokens }} / {{ trace.task.tokenUsageQuality }}</strong></div></section>
    <section class="panel trace-tabs"><el-tabs model-value="steps">
      <el-tab-pane :label="`Steps (${trace.steps.length})`" name="steps"><p v-if="!trace.steps.length" class="empty">尚无执行步骤。</p><article v-for="step in trace.steps" :key="step.id" class="trace-entry"><h3>{{ step.stepIndex }} · {{ step.title || step.stepType }} <span class="small-tag">{{ step.status }}</span></h3><p class="muted">{{ timestamp(step.startedAt) }} · {{ step.latencyMs ?? '—' }} ms · Step {{ step.id }}</p><details><summary>步骤摘要</summary><pre>{{ pretty(step.summary) }}</pre></details><p v-if="step.errorCode" class="error">{{ step.errorCode }} · {{ taskFailureMessage(step.errorCode, step.errorMessage) }}</p></article></el-tab-pane>
      <el-tab-pane :label="`RAG (${rag.length})`" name="rag"><p v-if="!rag.length" class="empty">尚无检索记录。</p><article v-for="retrieval in rag" :key="retrieval.id" class="trace-entry"><h3>{{ retrieval.query }}</h3><p class="muted">{{ retrieval.status }} · 有效命中 {{ retrieval.validHitCount }} / 候选 {{ retrieval.candidateCount }} · {{ retrieval.latencyMs }} ms</p><details v-for="hit in retrieval.hits" :key="hit.id"><summary>[{{ hit.citationId }}] · 排名 {{ hit.rankNo }} · 分数 {{ hit.score }}</summary><p class="answer-text">{{ hit.contentSnapshot }}</p><pre>{{ pretty(hit.metadataSnapshot) }}</pre><p class="mono muted">文档 {{ hit.documentIdSnapshot }} · 片段 {{ hit.chunkIdSnapshot }}</p></details></article></el-tab-pane>
      <el-tab-pane :label="`LLM (${llm.length})`" name="llm"><p v-if="!llm.length" class="empty">尚无模型调用记录。</p><article v-for="call in llm" :key="call.id" class="trace-entry"><h3>{{ call.callType }} <span class="small-tag">{{ call.status }}</span></h3><p class="muted">{{ call.provider }} / {{ call.resolvedModel || call.requestedModel }} · {{ call.latencyMs }} ms · {{ call.totalTokens ?? '未知' }} tokens ({{ call.usageQuality }})</p><details><summary>公开请求快照</summary><pre>{{ pretty(call.requestSnapshot) }}</pre></details><details><summary>公开响应</summary><pre>{{ call.responseText }}</pre></details><p v-if="call.errorCode" class="error">{{ call.errorCode }} · {{ taskFailureMessage(call.errorCode, call.errorMessage) }}</p></article></el-tab-pane>
      <el-tab-pane :label="`工具 (${tools.length})`" name="tools"><p v-if="!tools.length" class="empty">尚无工具调用记录。</p><article v-for="call in tools" :key="call.id" class="trace-entry"><h3>{{ call.toolName || call.toolCode }} <span class="small-tag">{{ call.status }}</span></h3><p class="muted">{{ call.latencyMs ?? '—' }} ms · {{ timestamp(call.startedAt) }}</p><details><summary>参数</summary><pre>{{ pretty(call.arguments) }}</pre></details><details><summary>结果</summary><pre>{{ pretty(call.result) }}</pre></details><p v-if="call.errorCode" class="error">{{ call.errorCode }} · {{ taskFailureMessage(call.errorCode, call.errorMessage) }}</p></article></el-tab-pane>
      <el-tab-pane :label="`事件 (${trace.events.length})`" name="events"><EventTimeline :events="trace.events" /></el-tab-pane>
      <el-tab-pane label="执行快照" name="snapshot"><pre>{{ pretty(trace.executionSnapshot) }}</pre></el-tab-pane>
    </el-tabs></section>
    <AnswerPanel :answer="trace.task.finalAnswer" :citations="trace.task.citations" :trace="trace" :final="['COMPLETED','FAILED','CANCELLED','TIMED_OUT'].includes(trace.task.status)" />
  </template><p v-else-if="loading" class="empty">正在读取公开执行快照…</p>
</template>
