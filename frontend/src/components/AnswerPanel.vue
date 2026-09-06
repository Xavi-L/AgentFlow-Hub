<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElDrawer } from 'element-plus'
import 'element-plus/theme-chalk/el-drawer.css'
import 'element-plus/theme-chalk/el-overlay.css'
import { pretty } from '../presentation'

const props = defineProps<{ answer: string | null; citations: unknown[]; trace?: unknown; final?: boolean }>()
const selected = ref<string | null>(null)
type Citation = { citationId: string; documentId?: string; chunkId?: string; vectorGeneration?: unknown }
const citations = computed(() => props.citations.filter((c): c is Citation => !!c && typeof c === 'object' && 'citationId' in c && typeof c.citationId === 'string'))
const parts = computed(() => (props.answer || '').split(/(\[S\d+\])/g))
const selectedCitation = computed(() => citations.value.find(c => c.citationId === selected.value))
const hit = computed(() => {
  const trace = props.trace as { steps?: { ragRetrievals?: { hits?: { citationId?: string; contentSnapshot?: string; metadataSnapshot?: unknown; score?: unknown }[] }[] }[] } | undefined
  return trace?.steps?.flatMap(s => s.ragRetrievals || []).flatMap(r => r.hits || []).find(h => h.citationId === selected.value)
})
function citationFor(part: string) { return citations.value.find(c => `[${c.citationId}]` === part) }
</script>

<template>
  <section class="panel answer-panel"><div class="section-label">{{ final ? '持久化最终答案' : '执行输出' }}</div><h2>答案</h2>
    <div v-if="answer" class="answer-text" data-testid="answer"><template v-for="(part, index) in parts" :key="index"><button v-if="citationFor(part)" class="citation-marker" @click="selected = citationFor(part)!.citationId">{{ part }}</button><template v-else>{{ part }}</template></template></div>
    <p v-else class="empty">{{ final ? '本次任务没有最终答案。' : '等待答案写入。执行过程可在时间线中查看。' }}</p>
    <div v-if="citations.length" class="citations" data-testid="citations"><h3>引用 · {{ citations.length }}</h3><button v-for="citation in citations" :key="citation.citationId" class="citation-card" @click="selected = citation.citationId"><strong>[{{ citation.citationId }}]</strong><span>文档 {{ citation.documentId }}<small>片段 {{ citation.chunkId }} · 历史快照</small></span><span>↗</span></button></div>
    <el-drawer :model-value="selected !== null" title="引用证据 · 历史快照" size="min(520px, 100%)" @close="selected = null">
      <template v-if="selectedCitation"><h2>[{{ selectedCitation.citationId }}]</h2><p class="muted break">文档 {{ selectedCitation.documentId }} / 片段 {{ selectedCitation.chunkId }}</p>
        <p v-if="hit?.contentSnapshot" class="answer-text">{{ hit.contentSnapshot }}</p><p v-else class="muted">当前快照中未读取到对应正文，可在 Trace 页查看已有记录。</p>
        <p v-if="hit?.score != null">检索分数：{{ hit.score }}</p><details v-if="hit?.metadataSnapshot"><summary>来源元数据</summary><pre>{{ pretty(hit.metadataSnapshot) }}</pre></details>
      </template>
    </el-drawer>
  </section>
</template>
