/**
 * 工具调用卡片 — 更紧凑的 ChatGPT 风格。
 */
<script setup lang="ts">
import type { ToolCallChatMessage } from '@/types'
import { computed } from 'vue'
import { Wrench, Loader2, Check, X } from '@lucide/vue'

const props = defineProps<{
  message: ChatMessage
}>()

const toolMsg = computed(() => props.message as ToolCallChatMessage)

const statusIcon = computed(() => {
  switch (toolMsg.value.status) {
    case 'running': return Loader2
    case 'success': return Check
    case 'failed': return X
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
  <div class="border border-[var(--border)] rounded-xl p-3 bg-[var(--muted)]/30 text-sm">
    <div class="flex items-center gap-2">
      <Wrench class="w-3.5 h-3.5 text-[var(--muted-foreground)]" aria-hidden="true" />
      <span class="text-xs font-mono bg-[var(--muted)] px-1.5 py-0.5 rounded">{{ toolMsg.toolName }}</span>
      <span :class="statusColor" class="flex items-center gap-1 text-xs font-medium ml-auto">
        <component :is="statusIcon" class="w-3.5 h-3.5" :class="toolMsg.status === 'running' ? 'animate-spin' : ''" />
        {{ statusText }}
      </span>
    </div>
    <details v-if="toolMsg.result || toolMsg.error" class="mt-2">
      <summary class="cursor-pointer text-xs text-[var(--muted-foreground)] hover:text-[var(--foreground)]">
        查看详情
      </summary>
      <pre class="mt-1 whitespace-pre-wrap text-xs bg-[var(--muted)] p-2 rounded-lg max-h-40 overflow-y-auto">{{ toolMsg.result || toolMsg.error }}</pre>
    </details>
  </div>
</template>
