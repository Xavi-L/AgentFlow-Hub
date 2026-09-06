<script setup lang="ts">
import { eventNames, phases, timestamp } from '../presentation'
defineProps<{ events: { sequenceNo: unknown; eventType: string; timestamp?: string; createdAt?: string; payload: Record<string, unknown> }[] }>()
function detail(event: { eventType: string; payload: Record<string, unknown> }) {
  const p = event.payload
  if (event.eventType === 'PHASE_CHANGED') return phases[String(p.phase)] || String(p.phase || '')
  if (event.eventType === 'RAG_FINISHED') return `有效命中 ${p.validHitCount ?? '—'} / 候选 ${p.candidateCount ?? '—'}`
  if (event.eventType.startsWith('TOOL_')) return [p.toolCode, p.status, p.reused ? '复用已有结果' : '', p.stepId ? `Step ${p.stepId}` : '', p.errorCode].filter(Boolean).join(' · ')
  if (event.eventType === 'DECISION_FINISHED') return String(p.decisionType || '')
  if (event.eventType === 'ANSWER_CHUNK') return `分片 ${p.chunkIndex ?? '—'}`
  return [p.terminationReason, p.errorCode].filter(Boolean).join(' · ')
}
</script>

<template>
  <ol class="timeline" data-testid="timeline"><li v-for="event in events" :key="String(event.sequenceNo)" :data-sequence="String(event.sequenceNo)"><span class="timeline-dot" /><div class="timeline-title"><strong>{{ eventNames[event.eventType] || event.eventType }}</strong><span class="mono">#{{ event.sequenceNo }}</span></div><p v-if="detail(event)">{{ detail(event) }}</p><time>{{ timestamp(event.timestamp || event.createdAt) }}</time></li></ol>
  <p v-if="!events.length" class="empty">尚未处理任何持久事件。</p>
</template>
