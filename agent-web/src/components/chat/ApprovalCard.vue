/**
 * 聊天中的审批状态卡片。
 * 只展示后端返回的操作、风险、原因与真实审批状态。
 */
<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { CheckCircle2, Clock3, ShieldCheck, XCircle } from '@lucide/vue'
import type { ApprovalChatMessage } from '@/types'

const props = defineProps<{
  message: ApprovalChatMessage
}>()

const router = useRouter()

const cardClass = computed(() => {
  switch (props.message.status) {
    case 'approved': return 'approval-card-approved'
    case 'rejected': return 'approval-card-rejected'
    case 'resolved': return 'approval-card-approved'
    default: return 'approval-card-pending'
  }
})

const riskLabel = computed(() => {
  switch (props.message.riskLevel) {
    case 'HIGH': return '高风险'
    case 'MEDIUM': return '中风险'
    case 'LOW': return '低风险'
    case 'SAFE': return '安全'
    default: return props.message.riskLevel
  }
})

const statusText = computed(() => {
  switch (props.message.status) {
    case 'pending': return '待审批'
    case 'approved': return '已通过'
    case 'rejected': return '已拒绝'
    case 'resolved': return '已处理'
  }
})

const statusIcon = computed(() => {
  switch (props.message.status) {
    case 'approved': return CheckCircle2
    case 'rejected': return XCircle
    case 'resolved': return CheckCircle2
    default: return Clock3
  }
})

const formattedTime = computed(() => new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit',
  minute: '2-digit',
  hour12: false
}).format(new Date(props.message.timestamp)))

function goToApproval(): void {
  router.push('/approvals')
}
</script>

<template>
  <section class="approval-card" :class="cardClass">
    <header class="approval-card-header">
      <div class="flex min-w-0 items-center gap-2.5">
        <ShieldCheck class="h-5 w-5 shrink-0" aria-hidden="true" />
        <h3 class="font-semibold">需要审批</h3>
        <span class="approval-risk-badge">{{ riskLabel }}</span>
      </div>
      <span class="approval-status-badge">
        <component :is="statusIcon" class="h-3.5 w-3.5" aria-hidden="true" />
        {{ statusText }}
      </span>
    </header>

    <div class="approval-operation">
      <span class="text-[var(--muted-foreground)]">操作</span>
      <strong>{{ message.operationName }}</strong>
    </div>

    <div v-if="message.reason" class="approval-reason">
      <span>申请原因</span>
      <p>{{ message.reason }}</p>
    </div>

    <footer class="approval-card-footer">
      <time :datetime="new Date(message.timestamp).toISOString()">
        请求时间：{{ formattedTime }}
      </time>
      <button v-if="message.status === 'pending'" @click="goToApproval">
        前往审批
      </button>
    </footer>
  </section>
</template>
