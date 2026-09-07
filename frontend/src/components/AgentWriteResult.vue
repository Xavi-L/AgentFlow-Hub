<script setup lang="ts">
import { computed } from 'vue'
import { agentMutation } from '../lib/agent-requests'
const props = defineProps<{ mutation: ReturnType<typeof agentMutation>; section: string; create?: boolean }>()
defineEmits<{ readback: [] }>()
const unknown = computed(props.mutation.unknown)
</script>

<template>
  <p v-if="mutation.state.error" class="error" role="alert">{{ mutation.state.error }}</p>
  <div v-if="unknown && !mutation.state.busy" class="notice" role="status" :data-testid="`unknown-${section}`">
    <strong>{{ unknown }}结果待确认</strong>
    <p>服务器可能已提交。先读取服务端核对，页面不会自动重新发送。离页取消等待也不会撤销写入。</p>
    <button type="button" class="text-button" @click="$emit('readback')">{{ create ? '刷新列表核对' : '读取服务端核对' }}</button>
    <template v-if="mutation.state.readbackResult === 'mismatch'">
      <p>{{ create ? '已读取列表。同名记录不能证明属于原请求；请检查记录后再决定是否创建。' : '当前服务端值与原提交不一致。草稿仍保留；原请求仍可能稍后提交，请核对下方服务端值。' }}</p>
      <button type="button" class="text-button" @click="mutation.acceptReadback()">{{ create ? '我已核对列表，允许新的创建' : '我已核对当前值，允许新的保存' }}</button>
    </template>
  </div>
  <p v-else-if="mutation.state.notice" class="form-hint" role="status">{{ mutation.state.notice }}</p>
</template>
