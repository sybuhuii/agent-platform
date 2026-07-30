/**
 * Supervisor 分派卡片 — 展示子任务分派和子 Agent 结果。
 * 只展示后端真实返回的数据，不展示模型详细思维链。
 */
<script setup lang="ts">
import type { SupervisorDispatchMessage } from '@/types'
import { computed } from 'vue'

const props = defineProps<{
  message: ChatMessage
}>()

const dispatch = computed(() => props.message as SupervisorDispatchMessage)

const statusColor = computed(() => {
  switch (dispatch.value.status) {
    case 'dispatched': return 'text-[var(--muted-foreground)]'
    case 'running': return 'text-[var(--warning)]'
    case 'completed': return 'text-[var(--success)]'
    case 'failed': return 'text-[var(--destructive)]'
  }
})

const statusText = computed(() => {
  switch (dispatch.value.status) {
    case 'dispatched': return '已分派'
    case 'running': return '执行中'
    case 'completed': return '已完成'
    case 'failed': return '失败'
  }
})
</script>

<template>
  <div class="border border-[var(--border)] rounded-lg p-3 bg-[var(--muted)]/30 text-sm">
    <div class="flex items-center gap-2">
      <span class="text-xs font-mono bg-[var(--muted)] px-1.5 py-0.5 rounded">{{ dispatch.agentName }}</span>
      <span :class="statusColor" class="text-xs font-medium">{{ statusText }}</span>
    </div>
    <p class="mt-1 text-xs text-[var(--muted-foreground)]">{{ dispatch.taskDescription }}</p>
    <details v-if="dispatch.result" class="mt-2">
      <summary class="cursor-pointer text-xs text-[var(--muted-foreground)] hover:text-[var(--foreground)]">结果</summary>
      <pre class="mt-1 whitespace-pre-wrap text-xs bg-[var(--muted)] p-2 rounded max-h-40 overflow-y-auto">{{ dispatch.result }}</pre>
    </details>
  </div>
</template>
