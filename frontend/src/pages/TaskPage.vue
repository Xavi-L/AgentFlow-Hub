<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { createTaskRuntime } from '../stores/runtime'
import { statuses, phases, connections, timestamp, message, taskFailureMessage } from '../presentation'
import AnswerPanel from '../components/AnswerPanel.vue'
import EventTimeline from '../components/EventTimeline.vue'

const route = useRoute(), runtime = createTaskRuntime(), state = runtime.state
const cancelBusy = ref(false), cancelError = ref('')
const terminal = computed(() => !!state.task && ['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(state.task.status))
onMounted(() => { void runtime.open(String(route.params.taskId)) })
onBeforeUnmount(() => runtime.dispose())
async function cancel() { cancelBusy.value = true; cancelError.value = ''; try { await runtime.cancel() } catch(e) { cancelError.value = message(e) } finally { cancelBusy.value = false } }
</script>

<template>
  <RouterLink to="/tasks" class="back-link">← 我的任务</RouterLink>
  <div class="page-heading"><div><div class="eyebrow">TASK RUNTIME</div><h1>任务运行</h1><p class="mono muted break">#{{ route.params.taskId }}</p></div><div class="actions"><button class="secondary" :disabled="state.loading" @click="runtime.retry()">刷新并恢复</button><RouterLink :to="`/tasks/${route.params.taskId}/trace`" class="secondary">查看 Trace ↗</RouterLink></div></div>
  <div class="runtime-status panel"><div><span class="label">服务端任务状态</span><strong class="status" :data-status="state.task?.status" data-testid="task-status">{{ state.task ? statuses[state.task.status] || state.task.status : state.loading ? '正在读取' : '不可用' }}</strong></div><div><span class="label">当前阶段</span><strong>{{ state.task?.status === 'RUNNING' ? phases[state.task.phase || ''] || '执行中' : '—' }}</strong></div><div><span class="label">客户端连接</span><strong data-testid="connection">{{ connections[state.connection] || state.connection }}</strong></div><div><span class="label">已处理 / 服务端事件</span><strong class="mono" data-testid="cursor">{{ state.cursor }} / {{ state.task?.lastEventSequence ?? '—' }}</strong></div>
    <button v-if="state.task && !terminal" class="danger" :disabled="cancelBusy || !!state.task.cancelRequestedAt" @click="cancel">{{ cancelBusy ? '正在请求…' : state.task.cancelRequestedAt ? '已请求取消' : '取消任务' }}</button>
  </div>
  <p v-if="state.error || cancelError" class="error" role="alert">{{ state.error || cancelError }}</p>
  <p v-if="state.task?.cancelRequestedAt && !terminal" class="notice" role="status">取消请求已提交，等待当前外部调用结束后的安全边界。</p>
  <p v-if="state.task?.terminationReason && state.task.terminationReason !== 'ANSWERED'" class="notice">结束原因：{{ state.task.terminationReason }}<template v-if="state.task.status === 'COMPLETED'">。答案基于当时已获得的证据生成。</template></p>
  <p v-if="state.task?.errorCode" class="error">{{ state.task.errorCode }} · {{ taskFailureMessage(state.task.errorCode, state.task.errorMessage) }}</p>
  <p v-if="terminal && !state.settled" class="notice" role="status">任务已结束，正在同步最终答案、引用与 Trace。同步成功前，当前输出仍为临时内容。</p>
  <div v-if="state.task || state.loading" class="runtime-grid"><div class="stack"><section class="panel"><div class="section-label">任务输入</div><p class="answer-text">{{ state.task?.userInput || '正在恢复任务快照…' }}</p><div class="metadata"><span>创建 {{ timestamp(state.task?.createdAt) }}</span><span v-if="state.task?.completedAt">结束 {{ timestamp(state.task.completedAt) }}</span></div></section><AnswerPanel :answer="state.answer" :citations="state.citations" :trace="state.trace" :final="state.settled" /></div>
    <section class="panel"><div class="section-label">持久事件</div><h2>执行时间线 <span class="count">{{ state.events.length }}</span></h2><EventTimeline :events="state.events" /></section>
  </div>
</template>
