<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../lib/api'
import { submission, submitTask, retrySubmission, abandonSubmission } from '../lib/submission'
import { statuses, timestamp, message } from '../presentation'

const route = useRoute(), router = useRouter()
type Agent = { id: string; name: string; description: string | null; status: string }
type Task = { taskId: string; userInput: string; status: string; createdAt: string }
const agents = ref<Agent[]>([]), tasks = ref<Task[]>([]), agentId = ref(submission.pending?.agentId || ''), input = ref(submission.pending?.userInput || ''), error = ref(''), loading = ref(true)
const taskPage = ref(1), taskHasNext = ref(false), agentPage = ref(1), agentHasNext = ref(false)
let disposed = false
onBeforeUnmount(() => { disposed = true })
async function loadTasks(page = taskPage.value) {
  error.value = ''
  try { const result = await api.listTasks(page); if (!disposed) { tasks.value = result.items; taskPage.value = result.page; taskHasNext.value = result.hasNext } }
  catch (e) { if (!disposed) error.value = message(e) }
}
async function loadAgents(page = 1) {
  try { const result = await api.listAgents(page); if (!disposed) { agents.value = page === 1 ? result.items : [...agents.value, ...result.items]; agentPage.value = page; agentHasNext.value = result.hasNext } }
  catch (e) { if (!disposed) error.value = message(e) }
}
onMounted(async () => {
  await Promise.all([loadTasks(), loadAgents()])
  if (disposed) return
  agentId.value = submission.pending?.agentId || (typeof route.params.agentId === 'string' ? route.params.agentId : agents.value.find(a => a.status === 'ACTIVE')?.id || '')
  loading.value = false
})
async function submit(retry = false) {
  error.value = ''
  try {
    const task = retry ? await retrySubmission() : await submitTask(agentId.value, input.value)
    if (task && !disposed) await router.push(`/tasks/${task.taskId}`)
  } catch (e) { if (!disposed) error.value = message(e) }
}
</script>

<template>
  <div class="page-heading"><div><div class="eyebrow">WORKSPACE</div><h1>任务</h1><p class="muted">选择已有 Agent，运行一次独立任务。</p></div><button class="secondary" @click="loadTasks()">刷新列表</button></div>
  <div class="workspace-grid">
    <section class="panel composer"><div class="section-label">01 / 新建任务</div><h2>交给 Agent 处理</h2>
      <form @submit.prevent="submit()">
        <label for="agent">Agent</label>
        <select id="agent" v-model="agentId" :disabled="loading || submission.busy || !!submission.pending" required>
          <option value="" disabled>选择已配置的 Agent</option>
          <option v-if="agentId && !agents.some(agent => agent.id === agentId)" :value="agentId">Agent {{ agentId }}（当前列表未加载）</option>
          <option v-for="agent in agents" :key="agent.id" :value="agent.id" :disabled="agent.status !== 'ACTIVE'">{{ agent.name }}{{ agent.status !== 'ACTIVE' ? '（已停用）' : '' }}</option>
        </select>
        <button v-if="agentHasNext" type="button" class="text-button" @click="loadAgents(agentPage + 1)">加载更多 Agent</button>
        <p v-if="!loading && !agents.length" class="muted">暂无 Agent。请先由管理员完成配置。</p>
        <label for="user-input">任务输入</label><textarea id="user-input" v-model="input" rows="7" placeholder="例如：分析订单支付失败的原因，并给出处理建议。" :disabled="submission.busy || !!submission.pending" required />
        <div v-if="submission.pending" class="notice" role="status">
          <strong>上次提交尚待确认</strong><p>继续查询原请求会使用相同的幂等键和原始输入。</p>
          <button type="button" class="primary" :disabled="submission.busy" @click="submit(true)">确认原提交结果</button>
          <button type="button" class="text-button" :disabled="submission.busy" @click="abandonSubmission()">放弃本地提交记录</button>
          <p class="muted">放弃记录不会取消服务端可能已创建的任务；可先检查右侧任务列表。</p>
        </div>
        <button v-else class="primary wide" :disabled="loading || submission.busy || !agentId || !input.trim()">{{ submission.busy ? '正在提交…' : '创建任务 →' }}</button>
      </form>
      <p v-if="error || submission.error" class="error" role="alert">{{ error || submission.error }}</p>
    </section>
    <section class="panel task-list"><div class="section-label">02 / 我的任务</div><h2>最近的执行</h2>
      <p v-if="loading" class="empty">正在读取任务…</p><p v-else-if="!tasks.length" class="empty">还没有任务。从左侧提交第一个任务。</p>
      <RouterLink v-for="task in tasks" :key="task.taskId" :to="`/tasks/${task.taskId}`" class="task-row">
        <div class="task-row-head"><span class="status" :data-status="task.status">{{ statuses[task.status] || task.status }}</span><time>{{ timestamp(task.createdAt) }}</time></div>
        <p>{{ task.userInput }}</p><span class="mono muted">#{{ task.taskId }}</span><span class="row-arrow" aria-hidden="true">↗</span>
      </RouterLink>
      <div class="pagination"><button class="secondary" :disabled="taskPage === 1" @click="loadTasks(taskPage - 1)">上一页</button><span>第 {{ taskPage }} 页</span><button class="secondary" :disabled="!taskHasNext" @click="loadTasks(taskPage + 1)">下一页</button></div>
    </section>
  </div>
</template>
