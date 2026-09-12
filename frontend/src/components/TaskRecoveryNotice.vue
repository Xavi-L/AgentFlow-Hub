<script setup lang="ts">
import type { Task } from '../lib/types'
import { recoveryUsageMessage, taskStatusLabel, timestamp } from '../presentation'

defineProps<{ task: Task }>()
</script>

<template>
  <section v-if="task.recovery" class="notice" data-testid="task-recovery" role="status">
    <strong>{{ taskStatusLabel(task) }}</strong>
    <p>{{ task.recovery.executionOutcome === 'UNKNOWN' ? '最终执行结果未确认；已保存的调用记录保留原结果。' : '本地尚未开始执行；遗留调度已经收尾。' }}</p>
    <p data-testid="recovery-usage">{{ recoveryUsageMessage(task) }}<template v-if="task.recovery.executionOutcome === 'UNKNOWN'"> 未记录的外部结果和用量无法确认。</template><template v-if="task.recovery.recordedLlmCalls">记录用量质量：{{ task.recovery.recordedUsage.tokenUsageQuality }}。</template></p>
    <p>已记录模型调用 {{ task.recovery.recordedLlmCalls }} 次、工具调用 {{ task.recovery.recordedToolCalls }} 次；这些是日志条数。<template v-if="task.recovery.counterCompleteness === 'UNCONFIRMED'">预算计数完整性未确认。</template></p>
    <p>收尾时间：{{ timestamp(task.recovery.recoveredAt) }}。实际执行耗时未知，不能用开始至收尾的时间差衡量。</p>
  </section>
</template>
