<script setup lang="ts">
import { computed } from 'vue'
import { finalAnswerReserve, validateAgentAdvancedDraft, type AgentDraft } from '../lib/agent-api'
import type { ExecutionOptionsState } from '../lib/agent-execution-options'
const props = defineProps<{ draft: AgentDraft; execution: ExecutionOptionsState; disabled?: boolean }>()
defineEmits<{ 'retry-options': [] }>()
const numbers = [
  { key: 'temperature', label: '采样温度', hint: 'temperature：0–2，最多三位小数' },
  { key: 'topP', label: '采样范围', hint: 'topP：大于 0 且不超过 1' },
  { key: 'maxSteps', label: '任务总步骤数', hint: 'maxSteps：1–20' },
  { key: 'maxToolCalls', label: '工具调用次数', hint: 'maxToolCalls：必须小于总步骤数' },
  { key: 'maxTokens', label: '任务总 token 预算', hint: 'maxTokens：累计输入与输出，256–100000' },
  { key: 'timeoutSeconds', label: '任务总超时（秒）', hint: 'timeoutSeconds：整个任务的执行时限，1–600 秒' },
] as const
const options = computed(() => props.execution.options?.modelName === props.draft.modelName.trim() ? props.execution.options : undefined)
const reserve = computed(() => /^\d+$/.test(props.draft.maxTokens.trim()) && Number(props.draft.maxTokens) >= 256
  && Number(props.draft.maxTokens) <= 100000 ? finalAnswerReserve(Number(props.draft.maxTokens)) : undefined)
const advancedError = computed(() => validateAgentAdvancedDraft(props.draft, options.value))
const source = (value: string) => value.trim() ? '自定义' : '继承部署默认'
const effectiveDecision = computed(() => options.value ? props.draft.decisionMaxOutputTokens.trim()
  ? Number(props.draft.decisionMaxOutputTokens) : options.value.defaults.decisionMaxOutputTokens : undefined)
const effectiveFinal = computed(() => options.value && reserve.value !== undefined ? props.draft.finalMaxOutputTokens.trim()
  ? Number(props.draft.finalMaxOutputTokens) : Math.max(reserve.value, options.value.defaults.finalMaxOutputTokens ?? 0) : undefined)
const effectiveTimeout = computed(() => options.value ? props.draft.modelCallTimeoutSeconds.trim()
  ? Number(props.draft.modelCallTimeoutSeconds) : options.value.defaults.modelCallTimeoutSeconds : undefined)
const formats = { PROMPT_ONLY: '提示词约束', JSON_OBJECT: 'JSON 模式', JSON_SCHEMA: 'JSON Schema' }
const thinking = { PROVIDER_DEFAULT: '遵循模型默认', DISABLED: '关闭思考' }
const finalDefault = computed(() => options.value?.defaults.finalMaxOutputTokens == null ? '自动（按最终回答预留）' : `${options.value.defaults.finalMaxOutputTokens} tokens`)
</script>

<template>
  <fieldset :disabled="disabled" class="agent-fields">
    <label for="agent-name">名称</label><input id="agent-name" v-model="draft.name" required maxlength="128" placeholder="例如：支付问题分析助手">
    <label for="agent-description">描述</label><textarea id="agent-description" v-model="draft.description" rows="2" maxlength="4000" placeholder="说明 Agent 的用途（选填）"></textarea>
    <label for="agent-prompt">系统提示词</label><textarea id="agent-prompt" aria-label="systemPrompt" v-model="draft.systemPrompt" rows="5" required maxlength="20000" placeholder="描述 Agent 的任务、回答要求与边界"></textarea>
    <div class="field-grid">
      <div><label for="agent-provider">模型服务</label><input id="agent-provider" aria-label="provider" value="openai-compatible" readonly></div>
      <div><label for="agent-model">模型名称</label><input id="agent-model" aria-label="modelName" v-model="draft.modelName" required maxlength="128" placeholder="填写部署支持的模型名称"></div>
      <div v-for="field in numbers" :key="field.key"><label :for="`agent-${field.key}`">{{ field.label }}</label><input :id="`agent-${field.key}`" :aria-label="field.key" v-model="draft[field.key]" inputmode="decimal" required :aria-describedby="`hint-${field.key}`"><small :id="`hint-${field.key}`" class="muted">{{ field.hint }}</small></div>
    </div>
    <p class="muted form-hint">模型名称由部署配置决定。保存配置不会验证模型是否可调用。</p>
    <p v-if="execution.loading" class="muted" role="status">正在读取当前模型的默认值与允许范围…</p>
    <div v-else-if="execution.error" class="notice" role="alert"><p>高级设置读取失败：{{ execution.error }}。当前默认值尚未确认，请重试后再保存。</p><button type="button" class="secondary" @click="$emit('retry-options')">重新读取高级设置</button></div>
    <p v-else-if="!options" class="muted">填写模型名称后，将读取高级设置的默认值与允许范围。</p>
    <p v-if="advancedError" class="error" role="alert">{{ advancedError }}</p>
    <details class="agent-advanced" data-testid="agent-advanced">
      <summary>高级执行设置 <span class="muted">单次输出、格式、思考与超时</span></summary>
      <p class="muted form-hint">留空或选择“继承部署默认”可跟随平台配置。创建任务时会固定当时的有效设置，修改 Agent 只影响之后创建的任务。</p>
      <div class="field-grid">
        <div>
          <label for="agent-decisionMaxOutputTokens">单次决策输出上限（tokens）</label>
          <input id="agent-decisionMaxOutputTokens" v-model="draft.decisionMaxOutputTokens" inputmode="numeric" placeholder="留空继承部署默认" aria-describedby="hint-decision-output">
          <small id="hint-decision-output" class="muted">{{ source(draft.decisionMaxOutputTokens) }}<template v-if="options"> · 部署默认 {{ options.defaults.decisionMaxOutputTokens }} · 平台上限 {{ options.limits.maxOutputTokens }}</template></small>
        </div>
        <div>
          <label for="agent-finalMaxOutputTokens">最终回答输出上限（tokens）</label>
          <input id="agent-finalMaxOutputTokens" v-model="draft.finalMaxOutputTokens" inputmode="numeric" placeholder="留空继承部署默认" aria-describedby="hint-final-output">
          <small id="hint-final-output" class="muted">{{ source(draft.finalMaxOutputTokens) }}<template v-if="options"> · 部署默认 {{ finalDefault }} · 平台上限 {{ options.limits.maxOutputTokens }}</template>。自定义值须不低于预留预算<template v-if="reserve !== undefined"> {{ reserve }} tokens</template>。</small>
        </div>
        <div>
          <label for="agent-decisionResponseFormat">决策输出格式</label>
          <select id="agent-decisionResponseFormat" v-model="draft.decisionResponseFormat" aria-describedby="hint-response-format">
            <option value="">继承部署默认</option><option value="PROMPT_ONLY">提示词约束</option>
            <option value="JSON_OBJECT" :disabled="!options?.capabilities.jsonObject">JSON 模式{{ !options?.capabilities.jsonObject ? '（当前未开放）' : '' }}</option>
            <option value="JSON_SCHEMA" :disabled="!options?.capabilities.jsonSchema">JSON Schema{{ !options?.capabilities.jsonSchema ? '（当前未开放）' : '' }}</option>
          </select>
          <small id="hint-response-format" class="muted">{{ source(draft.decisionResponseFormat) }}<template v-if="options"> · 部署默认：{{ formats[options.defaults.decisionResponseFormat] }}</template>。平台按模型兼容能力开放格式；已有不兼容值需明确修改。</small>
        </div>
        <div>
          <label for="agent-thinkingMode">模型思考策略</label>
          <select id="agent-thinkingMode" v-model="draft.thinkingMode" aria-describedby="hint-thinking-mode">
            <option value="">继承部署默认</option><option value="PROVIDER_DEFAULT">遵循模型默认</option>
            <option value="DISABLED" :disabled="!options?.capabilities.disableThinking">关闭思考{{ !options?.capabilities.disableThinking ? '（当前未验证支持）' : '' }}</option>
          </select>
          <small id="hint-thinking-mode" class="muted">{{ source(draft.thinkingMode) }}<template v-if="options"> · 部署默认：{{ thinking[options.defaults.thinkingMode] }}</template>。“遵循模型默认”不发送思考开关；“关闭思考”仅对已验证支持的模型开放。</small>
        </div>
        <div>
          <label for="agent-modelCallTimeoutSeconds">单次模型调用超时（秒）</label>
          <input id="agent-modelCallTimeoutSeconds" v-model="draft.modelCallTimeoutSeconds" inputmode="numeric" placeholder="留空继承部署默认" aria-describedby="hint-model-timeout">
          <small id="hint-model-timeout" class="muted">{{ source(draft.modelCallTimeoutSeconds) }}<template v-if="options"> · 部署默认 {{ options.defaults.modelCallTimeoutSeconds }} 秒 · 平台上限 {{ options.limits.maxModelCallTimeoutSeconds }} 秒</template>。实际等待还受任务剩余时间限制。</small>
        </div>
      </div>
      <div v-if="options && !advancedError && reserve !== undefined" class="execution-summary" data-testid="agent-execution-effective" aria-live="polite">
        <strong>当前有效配置</strong>
        <dl><div><dt>单次决策输出上限</dt><dd>{{ effectiveDecision }} tokens（{{ source(draft.decisionMaxOutputTokens) }}）</dd></div>
          <div><dt>最终回答输出上限</dt><dd>{{ effectiveFinal }} tokens（{{ source(draft.finalMaxOutputTokens) }}）</dd></div>
          <div><dt>最终回答预留预算</dt><dd>{{ reserve }} tokens</dd></div>
          <div><dt>决策格式 / 思考策略</dt><dd>{{ formats[draft.decisionResponseFormat || options.defaults.decisionResponseFormat] }} / {{ thinking[draft.thinkingMode || options.defaults.thinkingMode] }}</dd></div>
          <div><dt>单次模型调用 / 任务总超时</dt><dd>{{ effectiveTimeout }} 秒 / {{ draft.timeoutSeconds }} 秒</dd></div></dl>
        <p class="muted">以上为配置上限。每次实际输出额度仍受任务剩余总预算、上下文空间和最终回答预留限制。提高输出上限会消耗更多预算与时间。</p>
      </div>
    </details>
  </fieldset>
</template>

<style scoped>
.agent-advanced { margin: 20px 0; border-top: 1px solid var(--border); padding-top: 16px; }
.agent-advanced summary { font-weight: 600; }
.agent-advanced summary span { display: block; font-size: .75rem; font-weight: 400; margin-top: 4px; }
.execution-summary { margin-top: 20px; padding: 16px; border-radius: 8px; background: #f4f7fa; font-size: .8125rem; }
.execution-summary dl { margin: 12px 0; }
.execution-summary dl div { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 4px 12px; padding: 5px 0; }
.execution-summary dt { color: var(--muted); }
.execution-summary dd { margin: 0; }
.execution-summary p, .notice p { margin-bottom: 8px; }
</style>
