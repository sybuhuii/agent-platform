/**
 * 工具调用卡片 — 展示工具名称、状态、结果摘要。
 * 不展示完整敏感参数、内部堆栈和 Java 类名。
 */
<script setup lang="ts">
import type { ToolCallChatMessage } from '@/types'
import { computed } from 'vue'

const props = defineProps<{
  message: ChatMessage
}>()

const toolMsg = computed(() => props.message as ToolCallChatMessage)

const statusIcon = computed(() => {
  switch (toolMsg.value.status) {
    case 'running': return '⏳'
    case 'success': return '✓'
    case 'failed': return '✗'
  }
})

const statusColor = computed(() => {
  switch (toolMsg.value.status) {
    case 'running': return 'text-[var(--warning)]'
    case 'success': return 'text-[var(--success)]'
    case 'failed': return 'text-[var(--destructive)]'
  }
})

const statusText = computed(() => {
  switch (toolMsg.value.status) {
    case 'running': return '执行中'
    case 'success': return '成功'
    case 'failed': return '失败'
  }
})
</script>

<template>
  <div class="border border-[var(--border)] rounded-lg p-3 bg-[var(--muted)]/50 text-sm">
    <div class="flex items-center gap-2">
      <span class="text-xs font-mono bg-[var(--muted)] px-1.5 py-0.5 rounded">{{ toolMsg.toolName }}</span>
      <span :class="statusColor" class="flex items-center gap-1 text-xs font-medium">
        <span>{{ statusIcon }}</span>
        {{ statusText }}
      </span>
    </div>
    <details v-if="toolMsg.result || toolMsg.error" class="mt-2">
      <summary class="cursor-pointer text-xs text-[var(--muted-foreground)] hover:text-[var(--foreground)]">
        详情
      </summary>
      <pre class="mt-1 whitespace-pre-wrap text-xs bg-[var(--muted)] p-2 rounded max-h-40 overflow-y-auto">{{ toolMsg.result || toolMsg.error }}</pre>
    </details>
  </div>
</template>
