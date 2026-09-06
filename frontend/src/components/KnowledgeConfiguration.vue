<script setup lang="ts">
import { compatible, type KnowledgeBase } from '../lib/knowledge-api'
defineProps<{ knowledgeBase: KnowledgeBase }>()
</script>

<template>
  <div class="knowledge-config">
    <dl class="config-grid">
      <div><dt>Embedding profile · 只读</dt><dd>{{ knowledgeBase.embeddingProfileCode ?? '不兼容 / 无法解析' }}</dd></div>
      <div><dt>Chunk strategy · 只读</dt><dd>{{ knowledgeBase.chunkStrategyVersion ?? '不兼容 / 无法解析' }}</dd></div>
      <div><dt>Provider / Model</dt><dd>{{ knowledgeBase.embeddingProvider }} / {{ knowledgeBase.embeddingModel }}</dd></div>
      <div><dt>分块大小 / 重叠</dt><dd>{{ knowledgeBase.chunkSize }} / {{ knowledgeBase.chunkOverlap }}</dd></div>
    </dl>
    <p v-if="!compatible(knowledgeBase)" class="notice" role="alert">此知识库配置不兼容当前 Agent 入库要求。配置仅供查看，本页不支持修改；已有文档以服务端 Readiness 为准。</p>
    <p v-if="knowledgeBase.status !== 'ACTIVE'" class="notice" role="alert">知识库已禁用，当前不能用于 Agent。</p>
  </div>
</template>
