/**
* 审批弹窗组件 — 危险工具需要审批时立即弹出。
* 不得在前端自行判断审批是否可以绕过。
* 不展示 Supervisor/Agent 技术名称。
*/
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ApprovalChatMessage } from '@/types'
import Dialog from '@/components/ui/Dialog.vue'
import * as approvalApi from '@/api/approvals'
import { ApiError } from '@/api/errors'
import { Check, Copy, ShieldCheck } from '@lucide/vue'

const props = defineProps<{
  message: ApprovalChatMessage
  submitting: boolean
  error: string
}>()

const dismissed = ref(false)

const emit = defineEmits<{
  approve: [
    approvalId: string,
    runId: string,
    approvalRunId?: string
  ]
  reject: [
    approvalId: string,
    runId: string,
    comment: string,
    approvalRunId?: string
  ]
  resolved: [approvalId: string]
}>()

type PendingApprovalDetail =
    Awaited<ReturnType<typeof approvalApi.getPendingApproval>>

const comment = ref('')
const detail = ref<PendingApprovalDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const copied = ref(false)

const riskColor = computed(() => {
  switch (props.message.riskLevel) {
    case 'HIGH':
      return 'border-[var(--destructive)]/50 bg-[var(--destructive)]/5'

    case 'MEDIUM':
      return 'border-[var(--warning)]/50 bg-[var(--warning)]/5'

    default:
      return 'border-[var(--border)] bg-[var(--muted)]'
  }
})

const riskLabel = computed(() => {
  switch (props.message.riskLevel) {
    case 'HIGH':
      return '高风险'

    case 'MEDIUM':
      return '中风险'

    case 'LOW':
      return '低风险'

    case 'SAFE':
      return '安全'

    default:
      return props.message.riskLevel
  }
})

const isOpen = computed(
    () => props.message.status === 'pending' && !dismissed.value
)

const canApprove = computed(() => {
  return (
      !props.submitting &&
      !detailLoading.value &&
      !detailError.value &&
      detail.value !== null
  )
})

const operationTypeLabel = computed(() =>
  detail.value?.operationType === 'NODE' ? '流程节点' : '工具调用'
)

const displayReason = computed(() =>
  detail.value?.reason || props.message.reason || '该操作需要人工确认'
)

const safeArgumentsText = computed(() =>
  detail.value ? JSON.stringify(detail.value.safeArguments, null, 2) : ''
)

watch(
    () => [
      props.message.approvalRunId ?? props.message.runId,
      props.message.approvalId
    ] as const,
    async ([targetRunId]) => {
      dismissed.value = false
      detail.value = null
      detailError.value = ''
      detailLoading.value = true

      try {
        detail.value =
            await approvalApi.getPendingApproval(targetRunId)
      } catch (error) {
        if (
          error instanceof ApiError &&
          (error.status === 404 || error.errorCode === 'CHECKPOINT_NOT_FOUND')
        ) {
          emit('resolved', props.message.approvalId)
          return
        }
        detailError.value = '无法核对操作参数，禁止批准'
      } finally {
        detailLoading.value = false
      }
    },
    {
      immediate: true
    }
)

function handleOpenChange(open: boolean): void {
  if (!open) {
    dismissed.value = true
  }
}

async function copySafeArguments(): Promise<void> {
  if (!safeArgumentsText.value || !navigator.clipboard) return
  try {
    await navigator.clipboard.writeText(safeArgumentsText.value)
    copied.value = true
    window.setTimeout(() => { copied.value = false }, 1600)
  } catch {
    copied.value = false
  }
}

function handleApprove() {
  if (!canApprove.value) {
    return
  }

  emit(
      'approve',
      props.message.approvalId,
      props.message.runId,
      props.message.approvalRunId
  )
}

function handleReject() {
  if (
      props.submitting ||
      !comment.value.trim()
  ) {
    return
  }

  emit(
      'reject',
      props.message.approvalId,
      props.message.runId,
      comment.value.trim(),
      props.message.approvalRunId
  )
}
</script>

<template>
  <Dialog
      :open="isOpen"
      class="approval-dialog max-w-[620px]"
      @update:open="handleOpenChange"
  >
    <template #title>
      <span class="flex items-center gap-3">
        <span class="flex h-10 w-10 items-center justify-center rounded-xl bg-[var(--success)]/10 text-[var(--success)]">
          <ShieldCheck class="h-6 w-6" aria-hidden="true" />
        </span>
        <span class="text-[22px] tracking-[-0.02em]">操作审批</span>
      </span>
    </template>

    <template #description>
      <span class="text-[15px]">系统需要您确认以下操作</span>
    </template>

    <template #content>
      <div class="approval-dialog-body">
        <div class="approval-dialog-summary" :class="riskColor">
          <div class="flex min-w-0 flex-wrap items-center gap-3">
            <span class="approval-dialog-risk">{{ riskLabel }}</span>
            <strong class="min-w-0 break-all text-[15px]">{{ message.operationName }}</strong>
            <span class="approval-dialog-type">{{ operationTypeLabel }}</span>
          </div>
          <span class="approval-dialog-status">待审批</span>
        </div>

        <div class="approval-dialog-reason">
          <span>申请原因</span>
          <p>{{ displayReason }}</p>
        </div>

        <div
            v-if="detailLoading"
            class="approval-dialog-placeholder"
        >
          正在加载操作参数...
        </div>

        <div
            v-else-if="detailError"
            role="alert"
            class="approval-dialog-placeholder border-[var(--destructive)]/30 bg-[var(--destructive)]/5 text-[var(--destructive)]"
        >
          {{ detailError }}
        </div>

        <div v-else-if="detail" class="approval-arguments-section">
          <div class="mb-2 flex items-center justify-between">
            <h3 class="text-sm font-medium text-[var(--muted-foreground)]">脱敏后的操作参数</h3>
            <button
              class="approval-copy-button"
              :aria-label="copied ? '参数已复制' : '复制操作参数'"
              :title="copied ? '已复制' : '复制参数'"
              @click="copySafeArguments"
            >
              <Check v-if="copied" class="h-4 w-4" aria-hidden="true" />
              <Copy v-else class="h-4 w-4" aria-hidden="true" />
            </button>
          </div>
          <pre class="approval-arguments-code">{{ safeArgumentsText }}</pre>
        </div>

        <div class="approval-comment-section">
          <label
              for="approval-comment"
              class="mb-2 block text-sm font-medium text-[var(--muted-foreground)]"
          >
            备注（拒绝时必填）
          </label>

          <textarea
              id="approval-comment"
              v-model="comment"
              rows="2"
              class="approval-comment-input"
              placeholder="输入备注或拒绝原因..."
              :disabled="props.submitting"
          />
        </div>

        <p
            v-if="props.error"
            role="alert"
            class="rounded-lg bg-[var(--destructive)]/5 px-3 py-2 text-sm text-[var(--destructive)]"
        >
          {{ props.error }}
        </p>

        <div class="approval-dialog-actions">
          <button class="approval-secondary-button" @click="handleOpenChange(false)">
            稍后处理
          </button>
          <button
              class="approval-reject-button"
              :disabled="
              props.submitting ||
              !comment.trim()
            "
              @click="handleReject"
          >
            拒绝
          </button>

          <button
              class="approval-approve-button"
              :disabled="!canApprove"
              @click="handleApprove"
          >
            {{
              props.submitting
                  ? '处理中...'
                  : detailLoading
                      ? '加载参数中...'
                      : '通过'
            }}
          </button>
        </div>
      </div>
    </template>
  </Dialog>
</template>
