<script setup lang="ts">
import type { AgentDraft } from '../lib/agent-api'
defineProps<{ draft: AgentDraft; disabled?: boolean }>()
const numbers = [
  { key: 'temperature', min: 0, max: 2, step: '0.001', hint: '0–2，最多三位小数' },
  { key: 'topP', min: 0.001, max: 1, step: '0.001', hint: '大于 0 且不超过 1' },
  { key: 'maxSteps', min: 1, max: 20, step: '1', hint: '总步骤数：1–20' },
  { key: 'maxToolCalls', min: 0, max: 20, step: '1', hint: '必须小于 maxSteps' },
  { key: 'maxTokens', min: 256, max: 100000, step: '1', hint: '总 token 预算：256–100000' },
  { key: 'timeoutSeconds', min: 1, max: 600, step: '1', hint: '执行超时：1–600 秒' },
] as const
</script>

<template>
  <fieldset :disabled="disabled" class="agent-fields">
    <label for="agent-name">名称</label><input id="agent-name" v-model="draft.name" required maxlength="128" placeholder="例如：支付问题分析助手">
    <label for="agent-description">描述</label><textarea id="agent-description" v-model="draft.description" rows="2" maxlength="4000" placeholder="说明 Agent 的用途（选填）"></textarea>
    <label for="agent-prompt">systemPrompt</label><textarea id="agent-prompt" v-model="draft.systemPrompt" rows="5" required maxlength="20000" placeholder="描述 Agent 的任务、回答要求与边界"></textarea>
    <div class="field-grid">
      <div><label for="agent-provider">provider</label><input id="agent-provider" value="openai-compatible" readonly></div>
      <div><label for="agent-model">modelName</label><input id="agent-model" v-model="draft.modelName" required maxlength="128" placeholder="填写部署支持的模型名称"></div>
      <div v-for="field in numbers" :key="field.key"><label :for="`agent-${field.key}`">{{ field.key }}</label><input :id="`agent-${field.key}`" v-model="draft[field.key]" inputmode="decimal" required :aria-describedby="`hint-${field.key}`"><small :id="`hint-${field.key}`" class="muted">{{ field.hint }}</small></div>
    </div>
    <p class="muted form-hint">模型名称由部署配置决定。保存配置不会验证模型是否可调用。</p>
  </fieldset>
</template>
