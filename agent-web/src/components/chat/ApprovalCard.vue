/**
 * 审批卡片 — ChatGPT 风格，更紧凑。
 */
<script setup lang="ts">
import type { ApprovalChatMessage } from '@/types'
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ShieldAlert } from '@lucide/vue'

const props = defineProps<{
  message: ApprovalChatMessage
}>()

const router = useRouter()

const riskColor = computed(() => {
  switch (props.message.riskLevel) {
    case 'HIGH': return 'bg-[var(--destructive)]/10 text-[var(--destructive)] border-[var(--destructive)]/30'
    case 'MEDIUM': return 'bg-[var(--warning)]/10 text-[var(--warning)] border-[var(--warning)]/30'
    case 'LOW': return 'bg-[var(--muted)] text-[var(--muted-foreground)] border-[var(--border)]'
    default: return 'bg-[var(--muted)] text-[var(--muted-foreground)] border-[var(--border)]'
  }
})

const statusText = computed(() => {
  switch (props.message.status) {
    case 'pending': return '待审批'
    case 'approved': return '已通过'
    case 'rejected': return '已拒绝'
  }
})

const statusColor = computed(() => {
  switch (props.message.status) {
    case 'pending': return 'text-[var(--warning)]'
    case 'approved': return 'text-[var(--success)]'
    case 'rejected': return 'text-[var(--destructive)]'
  }
})

function goToApproval() {
  router.push('/approvals')
}
</script>

<template>
  <div class="rounded-xl border-2 p-4" :class="riskColor">
    <div class="flex items-center justify-between mb-2">
      <div class="flex items-center gap-2">
        <ShieldAlert class="w-4 h-4" aria-hidden="true" />
        <span class="font-semibold text-sm">需要审批</span>
        <span class="text-xs px-1.5 py-0.5 rounded bg-[var(--destructive)]/20 text-[var(--destructive)]">
          {{ message.riskLevel }}
        </span>
      </div>
      <span :class="statusColor" class="text-xs font-medium">{{ statusText }}</span>
    </div>
    <div class="text-sm">
      <p><strong>操作：</strong>{{ message.operationName }}</p>
      <p v-if="message.reason" class="text-[var(--muted-foreground)] mt-1">{{ message.reason }}</p>
    </div>
    <button
      v-if="message.status === 'pending'"
      class="mt-3 text-xs font-medium text-[var(--accent)] hover:underline"
      @click="goToApproval"
    >
      前往审批 →
    </button>
  </div>
</template>
