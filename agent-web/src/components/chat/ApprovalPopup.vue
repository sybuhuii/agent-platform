/**
 * 审批弹窗组件 — 危险工具需要审批时立即弹出。
 * 不得在前端自行判断审批是否可以绕过。
 * 不展示 Supervisor/Agent 技术名称。
 */
<script setup lang="ts">
import type { ApprovalChatMessage } from '@/types'
import { computed, ref, onUnmounted } from 'vue'
import Dialog from '@/components/ui/Dialog.vue'

const props = defineProps<{
  message: ApprovalChatMessage
}>()

const emit = defineEmits<{
  approve: [approvalId: string, runId: string]
  reject: [approvalId: string, runId: string, comment: string]
}>()

const comment = ref('')
const submitting = ref(false)
const riskColor = computed(() => {
  switch (props.message.riskLevel) {
    case 'HIGH': return 'border-[var(--destructive)]/50 bg-[var(--destructive)]/5'
    case 'MEDIUM': return 'border-[var(--warning)]/50 bg-[var(--warning)]/5'
    default: return 'border-[var(--border)] bg-[var(--muted)]'
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

const isOpen = computed(() => props.message.status === 'pending')

function handleApprove() {
  submitting.value = true
  emit('approve', props.message.approvalId, props.message.runId)
}

function handleReject() {
  if (!comment.value.trim()) return
  submitting.value = true
  emit('reject', props.message.approvalId, props.message.runId, comment.value.trim())
}

onUnmounted(() => {
  submitting.value = false
})
</script>

<template>
  <Dialog :open="isOpen" class="max-w-md">
    <template #title>操作审批</template>
    <template #description>系统需要您确认以下操作</template>
    <template #content>
      <div class="space-y-3" :class="riskColor">
        <div class="rounded-lg border p-4">
          <div class="flex items-center gap-2 mb-2">
            <span class="text-xs px-1.5 py-0.5 rounded font-medium"
              :class="message.riskLevel === 'HIGH' ? 'bg-[var(--destructive)]/20 text-[var(--destructive)]' : 'bg-[var(--warning)]/20 text-[var(--warning)]'">
              {{ riskLabel }}
            </span>
            <span class="font-medium text-sm">{{ message.operationName }}</span>
          </div>
          <p class="text-sm text-[var(--muted-foreground)]">{{ message.reason }}</p>
        </div>

        <div>
          <label for="approval-comment" class="block text-sm text-[var(--muted-foreground)] mb-1">
            备注（拒绝时必填）
          </label>
          <textarea
            id="approval-comment"
            v-model="comment"
            rows="2"
            class="w-full rounded-lg border border-[var(--input)] bg-transparent px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
            placeholder="输入备注或拒绝原因..."
            :disabled="submitting"
          />
        </div>

        <div class="flex gap-2 justify-end">
          <button
            class="rounded-lg border border-[var(--border)] px-4 py-2 text-sm hover:bg-[var(--muted)] transition-colors disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
            :disabled="submitting || !comment.trim()"
            @click="handleReject"
          >
            拒绝
          </button>
          <button
            class="rounded-lg bg-[var(--accent)] text-[var(--accent-foreground)] px-4 py-2 text-sm font-medium hover:bg-[var(--accent)]/90 transition-colors disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
            :disabled="submitting"
            @click="handleApprove"
          >
            {{ submitting ? '处理中...' : '通过' }}
          </button>
        </div>
      </div>
    </template>
  </Dialog>
</template>
