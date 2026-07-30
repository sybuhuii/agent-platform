/**
 * 运行状态指示器 — 展示当前 Agent/Supervisor 运行状态。
 */
<script setup lang="ts">
import type { RunState } from '@/types'
import { computed } from 'vue'

const props = defineProps<{
  state: RunState
}>()

const label = computed(() => {
  switch (props.state.status) {
    case 'idle': return ''
    case 'submitting': return '提交中...'
    case 'running': return '运行中...'
    case 'suspended': return '等待审批'
    case 'completed': return '已完成'
    case 'failed': return props.state.message || '执行失败'
  }
})

const colorClass = computed(() => {
  switch (props.state.status) {
    case 'idle': return ''
    case 'submitting':
    case 'running': return 'text-[var(--accent)]'
    case 'suspended': return 'text-[var(--warning)]'
    case 'completed': return 'text-[var(--success)]'
    case 'failed': return 'text-[var(--destructive)]'
  }
})

const showSpinner = computed(() =>
  props.state.status === 'submitting' || props.state.status === 'running'
)
</script>

<template>
  <div v-if="state.status !== 'idle'" class="flex items-center gap-2 text-xs" :class="colorClass">
    <span v-if="showSpinner" class="inline-block w-3 h-3 border-2 border-current border-t-transparent rounded-full animate-spin" />
    <span v-else-if="state.status === 'completed'" class="text-sm">✓</span>
    <span v-else-if="state.status === 'failed'" class="text-sm">✗</span>
    <span v-else-if="state.status === 'suspended'" class="text-sm">⏸</span>
    {{ label }}
  </div>
</template>
