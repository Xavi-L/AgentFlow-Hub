<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { compatible, knowledgeApi, type KnowledgeBase } from '../lib/knowledge-api'
import { knowledgeMutation, requestScope } from '../lib/knowledge-requests'
import type { Page } from '../lib/types'

const router = useRouter()
const scope = requestScope()
const mutation = knowledgeMutation(scope, 'create-base')
const unknown = computed(mutation.unknown)
const { state: write } = mutation
const name = ref(''), description = ref(''), error = ref(''), loading = ref(false), index = ref(1)
const result = ref<Page<KnowledgeBase>>()
async function refresh(page = index.value) {
  const flight = scope.start('list')
  index.value = page; loading.value = true; error.value = ''; result.value = undefined
  try {
    const value = await knowledgeApi.listBases(page, flight.signal)
    if (flight.current()) result.value = value
  } catch (reason) { if (flight.current()) error.value = reason instanceof Error ? reason.message : '知识库列表暂时无法读取' }
  finally { if (flight.current()) loading.value = false }
}
async function create() {
  if (!name.value.trim() || name.value.length > 128 || description.value.length > 4000) { write.error = '名称必填且不超过 128 字，描述不超过 4000 字'; return }
  const value = await mutation.run('创建知识库', signal => knowledgeApi.createBase(name.value.trim(), description.value, signal))
  if (value && scope.active()) await router.push(`/knowledge-bases/${value.id}`)
}
onBeforeUnmount(scope.dispose)
void refresh()
</script>

<template>
  <div class="page-heading"><div><p class="eyebrow">KNOWLEDGE / 知识管理</p><h1>知识库</h1><p class="muted">整理文档，查看解析与向量化进度。</p></div><button class="secondary" @click="refresh()">刷新列表</button></div>
  <div class="knowledge-grid">
    <section class="panel composer">
      <p class="section-label">01 / 新的知识库</p><h2>创建知识库</h2>
      <form @submit.prevent="create">
        <label for="kb-name">名称</label><input id="kb-name" v-model="name" required maxlength="128" :disabled="write.busy || !!unknown" placeholder="例如：支付业务知识库">
        <label for="kb-description">描述（选填）</label><textarea id="kb-description" v-model="description" rows="4" maxlength="4000" :disabled="write.busy || !!unknown" placeholder="说明文档主题与使用场景"></textarea>
        <p class="muted form-hint">创建使用服务端默认配置。Embedding profile 和 chunk strategy 将在详情页只读显示。</p>
        <button class="primary wide" :disabled="write.busy || !!unknown">{{ write.busy ? '正在创建…' : '创建知识库 →' }}</button>
      </form>
      <p v-if="write.error" class="error" role="alert">{{ write.error }}</p>
      <div v-if="unknown && !write.busy" class="notice" role="status" data-testid="unknown-write"><strong>{{ unknown }}结果待确认</strong><p>服务器可能已经创建。请先刷新列表核对，不会自动重新提交；同名记录不能证明是同一次请求。</p><button class="text-button" @click="mutation.acknowledge()">我已核对列表，允许新的提交</button></div>
    </section>
    <section class="panel" aria-label="知识库列表" :aria-busy="loading">
      <div class="task-row-head"><h2>我的知识库</h2><span v-if="result" class="muted">共 {{ result.total }} 个</span></div>
      <p v-if="error" class="error" role="alert">{{ error }}</p><p v-else-if="loading" class="empty">正在读取知识库…</p>
      <p v-else-if="!result?.items.length" class="empty">这一页还没有知识库。创建后即可上传文档。</p>
      <RouterLink v-for="kb in result?.items" :key="kb.id" :to="`/knowledge-bases/${kb.id}`" class="task-row knowledge-row" :data-kb-id="kb.id">
        <div class="task-row-head"><strong>{{ kb.name }}</strong><span class="status">{{ kb.status }}</span></div><p class="muted">{{ kb.description || '暂无描述' }}</p>
        <small class="break">Profile：{{ kb.embeddingProfileCode ?? '不兼容 / 无法解析' }}<br>Strategy：{{ kb.chunkStrategyVersion ?? '不兼容 / 无法解析' }}</small><p v-if="!compatible(kb)" class="config-warning">配置不兼容当前 Agent 入库要求</p><span class="row-arrow">↗</span>
      </RouterLink>
      <div class="pagination"><button class="secondary" :disabled="index === 1" @click="refresh(index - 1)">上一页</button><span>第 {{ index }} 页</span><button class="secondary" :disabled="!result?.hasNext" @click="refresh(index + 1)">下一页</button></div>
    </section>
  </div>
</template>
