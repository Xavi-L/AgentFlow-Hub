<script setup lang="ts">
import type { KnowledgeDocument } from '../lib/knowledge-api'
defineProps<{ document: KnowledgeDocument }>()
const labels = { NOT_READY: '未就绪', INDEXING: '索引中', READY: '已就绪', DEGRADED: '部分失败', FAILED: '不可用' }
</script>

<template>
  <div class="document-status" :data-readiness="document.retrievalReadiness">
    <div class="actions">
      <span class="status" :data-status="document.retrievalReadiness">{{ labels[document.retrievalReadiness] }} · {{ document.retrievalReadiness }}</span>
      <strong v-if="document.retrievalReadiness === 'READY'" class="ready-label">可用于 Agent</strong>
    </div>
    <div class="metadata"><span>解析 <b>{{ document.parseStatus }}</b></span><span>当前 generation <b>{{ document.vectorGeneration }}</b></span></div>
    <dl class="vector-counts" aria-label="当前 generation 向量化计数">
      <div><dt>待向量化</dt><dd>{{ document.vectorization.pending }}</dd></div>
      <div><dt>向量化中</dt><dd>{{ document.vectorization.processing }}</dd></div>
      <div><dt>已完成</dt><dd>{{ document.vectorization.completed }}</dd></div>
      <div><dt>失败</dt><dd>{{ document.vectorization.failed }}</dd></div>
    </dl>
  </div>
</template>
