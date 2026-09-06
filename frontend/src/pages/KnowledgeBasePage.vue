<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute } from 'vue-router'
import KnowledgeConfiguration from '../components/KnowledgeConfiguration.vue'
import DocumentStatus from '../components/DocumentStatus.vue'
import { knowledgeApi, observing, uploadError, type KnowledgeBase, type KnowledgeDocument } from '../lib/knowledge-api'
import { boundedPoll, knowledgeMutation, requestScope } from '../lib/knowledge-requests'
import { resourceId } from '../lib/sequence'
import type { Page } from '../lib/types'

const kbId = String(useRoute().params.kbId)
const scope = requestScope()
const mutation = knowledgeMutation(scope, `base:${kbId}`)
const { state: write } = mutation
const unknown = computed(mutation.unknown)
const kb = ref<KnowledgeBase>(), documents = ref<Page<KnowledgeDocument>>()
const index = ref(1), loading = ref(false), error = ref(''), file = ref<File>(), input = ref<HTMLInputElement>()
const selectedId = ref(''), selected = ref<KnowledgeDocument>(), detailError = ref(''), detailLoading = ref(false)
const poll = boundedPoll(() => read(), scope.active)
const polling = poll.state
const needsObservation = () => !!documents.value?.items.some(observing) || !!(selected.value && observing(selected.value))
let valid = true
let refreshRevision = 0
try { resourceId(kbId) } catch { valid = false; error.value = '知识库地址无效' }

async function read(): Promise<boolean> {
  if (!valid || !scope.active()) return false
  const flight = scope.start('list')
  loading.value = true; error.value = ''
  try {
    const [base, page] = await Promise.all([knowledgeApi.getBase(kbId, flight.signal), knowledgeApi.listDocuments(kbId, index.value, flight.signal)])
    if (!flight.current()) return false
    kb.value = base; documents.value = page
    if (selectedId.value) await readSelected(selectedId.value)
    return flight.current() && needsObservation() && !detailError.value
  } catch (reason) {
    if (flight.current()) {
      error.value = reason instanceof Error ? reason.message : '状态暂时无法读取'
      // A previously visible parent may have been deleted or become inaccessible.
      if (typeof reason === 'object' && reason !== null && 'status' in reason && reason.status === 404) {
        kb.value = undefined; documents.value = undefined; selected.value = undefined; selectedId.value = ''; scope.cancel('detail')
      }
    }
    return false
  } finally { if (flight.current()) loading.value = false }
}
async function refresh(page = index.value) {
  const version = ++refreshRevision
  poll.stop()
  if (page !== index.value) { documents.value = undefined; selected.value = undefined; selectedId.value = ''; scope.cancel('detail') }
  index.value = page
  const again = await read()
  if (scope.active() && version === refreshRevision) poll.start(again)
}
async function readSelected(id: string) {
  const flight = scope.start('detail')
  detailLoading.value = true; detailError.value = ''
  try {
    const value = await knowledgeApi.getDocument(kbId, id, flight.signal)
    if (flight.current() && selectedId.value === id) selected.value = value
  } catch (reason) {
    if (flight.current()) { selected.value = undefined; detailError.value = reason instanceof Error ? reason.message : '文档状态暂时无法读取' }
  } finally { if (flight.current()) detailLoading.value = false }
}
async function select(id: string) {
  selectedId.value = id; selected.value = undefined
  await readSelected(id)
}
function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0]
  write.error = uploadError(file.value)
}
async function upload() {
  const validation = uploadError(file.value)
  if (validation) { write.error = validation; return }
  const value = await mutation.run('上传文档', signal => knowledgeApi.upload(kbId, file.value!, signal))
  if (value && scope.active()) {
    write.notice = `已上传 ${value.fileName}，解析状态 ${value.parseStatus}。请继续解析待处理文档。`
    file.value = undefined; if (input.value) input.value.value = ''
    selectedId.value = value.id; selected.value = undefined
    await refresh(1)
    // Page reset must not discard the newly uploaded document selection.
    await select(value.id)
  }
}
async function process(kind: 'process' | 'vectorize') {
  const label = kind === 'process' ? '解析待处理文档' : '向量化待处理分块'
  const value = await mutation.run(label, signal => knowledgeApi[kind](kbId, signal))
  if (value && scope.active()) {
    write.notice = `${label}：发现 ${value.discovered}，领取 ${value.claimed}，完成 ${value.completed}，失败 ${value.failed}，跳过 ${value.skipped}。文档状态见下方最新读取结果。`
    await refresh()
  }
}
onBeforeUnmount(() => { poll.stop(); scope.dispose() })
if (valid) void refresh()
</script>

<template>
  <RouterLink to="/knowledge-bases" class="back-link">← 返回知识库</RouterLink>
  <div class="page-heading"><div><p class="eyebrow">KNOWLEDGE / 文档入库</p><h1 class="break">{{ kb?.name || '知识库详情' }}</h1><p class="muted break">{{ kb?.description || '上传文档，逐步完成解析与向量化。' }}</p></div><button class="secondary" :disabled="!valid" @click="refresh()">刷新状态</button></div>
  <p v-if="error" class="error" role="alert">{{ error }}。{{ kb ? '当前保留上次读取状态，尚未确认最新结果。' : '' }}</p>
  <p v-if="!kb && loading" class="empty">正在读取知识库…</p>
  <template v-if="kb">
    <section class="panel knowledge-overview"><div class="task-row-head"><h2>知识库配置</h2><span class="status">{{ kb.status }}</span></div><KnowledgeConfiguration :knowledge-base="kb" /></section>
    <section class="panel ingestion-panel" aria-label="入库操作">
      <h2>文档入库</h2><p class="muted">依次上传、解析、向量化。上传只产生 PENDING 文档，刷新不会启动解析或向量化。</p>
      <div class="ingestion-steps">
        <form @submit.prevent="upload"><span class="section-label">01 / 上传</span><label for="document-file">TXT / MD 单文件</label><input id="document-file" ref="input" type="file" accept=".txt,.md" :disabled="write.busy || !!unknown" @change="chooseFile"><button class="primary" :disabled="write.busy || !!unknown || !file">上传文档</button></form>
        <div><span class="section-label">02 / 解析</span><h3>解析原始文件</h3><p class="muted">处理此知识库的待解析文档，生成分块。</p><button class="secondary" :disabled="write.busy || !!unknown" @click="process('process')">解析待处理文档</button></div>
        <div><span class="section-label">03 / 向量化</span><h3>建立检索索引</h3><p class="muted">处理此知识库的待向量化分块。部署为 remote 时会调用配置的 embedding / vector 服务。</p><button class="secondary" :disabled="write.busy || !!unknown" @click="process('vectorize')">向量化待处理分块</button></div>
      </div>
      <p v-if="write.busy" class="notice" role="status">正在提交，请稍候…</p>
      <p v-if="write.error" class="error" role="alert">{{ write.error }}</p>
      <p v-if="write.notice" class="notice" role="status">{{ write.notice }}</p>
      <div v-if="unknown && !write.busy" class="notice" data-testid="unknown-write"><strong>{{ unknown }}结果待确认</strong><p>请刷新核对文档和计数。不会自动重新提交；仅凭文件名不能确认是否为同一次上传。</p><button class="text-button" @click="mutation.acknowledge()">我已核对状态，允许新的提交</button></div>
    </section>
    <section class="panel document-list" aria-label="文档列表" :aria-busy="loading">
      <div class="task-row-head"><h2>文档</h2><span v-if="documents" class="muted">共 {{ documents.total }} 个</span></div>
      <p class="muted poll-message" role="status">{{ polling.running ? `自动刷新中 · 每 3 秒一次，最多 20 次 / 60 秒（已 ${polling.attempts} 次）` : polling.message || '可手动刷新。状态与计数来自最近一次服务端读取。' }}</p>
      <p v-if="!documents?.items.length" class="empty">{{ loading ? '正在读取文档…' : '这一页还没有文档。' }}</p>
      <article v-for="doc in documents?.items" :key="doc.id" class="document-row" :data-document-id="doc.id">
        <div class="task-row-head"><div class="break"><strong>{{ doc.fileName }}</strong><small class="muted document-file-meta">{{ doc.fileType }} · {{ doc.fileSize }} bytes</small></div><button class="text-button" :aria-label="`查看 ${doc.fileName} 状态详情`" @click="select(doc.id)">状态详情 ↗</button></div>
        <DocumentStatus :document="doc" />
      </article>
      <div class="pagination"><button class="secondary" :disabled="index === 1" @click="refresh(index - 1)">上一页</button><span>第 {{ index }} 页</span><button class="secondary" :disabled="!documents?.hasNext" @click="refresh(index + 1)">下一页</button></div>
    </section>
    <section v-if="selectedId" class="panel document-detail" aria-label="文档状态详情" data-testid="document-detail" :aria-busy="detailLoading">
      <h2>文档状态详情</h2><p v-if="detailLoading" class="muted">正在读取状态…</p><p v-if="detailError" class="error" role="alert">{{ detailError }}</p>
      <template v-if="selected"><h3 class="break">{{ selected.fileName }}</h3><p class="mono break">ID {{ selected.id }}</p><DocumentStatus :document="selected" />
        <p class="muted form-hint">解析完成不代表检索就绪。仅 READY 显示可用于 Agent；DEGRADED 表示部分向量化失败。</p></template>
    </section>
  </template>
</template>
