/**
* HITL 审批页 — 展示待审批列表和审批操作。
* 修复：
* - 使用组件 setup 中的 useQueryClient，不在事件处理中调用
* - 每个审批项有独立的拒绝原因输入
* - 审批通过/拒绝后刷新列表
* - 失败时保留当前审批项
* - 不得因一个审批项操作影响其他项的输入状态
*/
<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useQueryClient } from '@tanstack/vue-query'
import { ArrowLeft } from '@lucide/vue'
import { usePendingApprovals } from '@/queries'
import { useChatStore } from '@/stores/chat'
import * as approvalApi from '@/api/approvals'
import type {
  PendingApprovalSummaryResponse
} from '@/types'
import Dialog from '@/components/ui/Dialog.vue'

const queryClient = useQueryClient()
const router = useRouter()
const approvalsQuery = usePendingApprovals()
const chatStore = useChatStore()

const approving = ref<string | null>(null)
const error = ref('')
const resultMessage = ref('')

// 拒绝弹窗及当前审批项状态
const rejectDialogOpen = ref(false)

const rejectTarget =
    ref<PendingApprovalSummaryResponse | null>(null)

const rejectComment = ref('')
const rejecting = ref<string | null>(null)

async function handleApprove(
    item: PendingApprovalSummaryResponse
) {
  approving.value = item.approvalId
  error.value = ''
  resultMessage.value = ''

  try {
    const response =
        await approvalApi.decideAndResume(
            item.runId,
            item.approvalId,
            'APPROVE',
            ''
        )
    const normalizedResponse =
        approvalApi.normalizeApprovalResumeResponse(response)
    chatStore.handleExternalApprovalResume(
        normalizedResponse,
        item.approvalId,
        'APPROVE'
    )

    resultMessage.value =
        normalizedResponse.content ||
        `运行状态：${normalizedResponse.status}`

    await queryClient.invalidateQueries({
      queryKey: ['hitl', 'approvals']
    })
  } catch (exception: unknown) {
    error.value =
        exception instanceof Error
            ? exception.message
            : '操作失败'
  } finally {
    approving.value = null
  }
}

function openRejectDialog(
    item: PendingApprovalSummaryResponse
) {
  rejectTarget.value = item
  rejectComment.value = ''
  rejectDialogOpen.value = true
  error.value = ''
  resultMessage.value = ''
}

async function handleReject() {
  if (
      !rejectTarget.value ||
      !rejectComment.value.trim()
  ) {
    error.value = '请输入拒绝原因'
    return
  }

  const target = rejectTarget.value
  rejecting.value = target.approvalId

  error.value = ''
  resultMessage.value = ''

  try {
    const response =
        await approvalApi.decideAndResume(
            target.runId,
            target.approvalId,
            'REJECT',
            rejectComment.value.trim()
        )
    const normalizedResponse =
        approvalApi.normalizeApprovalResumeResponse(response)
    chatStore.handleExternalApprovalResume(
        normalizedResponse,
        target.approvalId,
        'REJECT'
    )

    resultMessage.value =
        normalizedResponse.content ||
        `运行状态：${normalizedResponse.status}`

    rejectDialogOpen.value = false
    rejectTarget.value = null
    rejectComment.value = ''

    await queryClient.invalidateQueries({
      queryKey: ['hitl', 'approvals']
    })
  } catch (exception: unknown) {
    error.value =
        exception instanceof Error
            ? exception.message
            : '操作失败'
  } finally {
    rejecting.value = null
  }
}

function riskLevelColor(
    level: string
): string {
  switch (level) {
    case 'HIGH':
      return 'text-[var(--destructive)]'

    case 'MEDIUM':
      return 'text-[var(--warning)]'

    case 'LOW':
      return 'text-[var(--muted-foreground)]'

    case 'SAFE':
      return 'text-[var(--success)]'

    default:
      return 'text-[var(--muted-foreground)]'
  }
}
</script>

<template>
  <div class="p-6 max-w-4xl mx-auto">
    <div class="mb-4 flex items-center gap-3">
      <button
        class="grid h-9 w-9 place-items-center rounded-lg text-[var(--muted-foreground)] transition-colors hover:bg-[var(--muted)] hover:text-[var(--foreground)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
        aria-label="返回对话"
        title="返回对话"
        @click="router.push('/')"
      >
        <ArrowLeft class="h-5 w-5" aria-hidden="true" />
      </button>
      <h1 class="text-xl font-semibold">人机审批</h1>
    </div>

    <div
        v-if="error"
        role="alert"
        class="rounded-lg border border-[var(--destructive)]/30 bg-[var(--destructive)]/5 p-3 text-sm text-[var(--destructive)] mb-4"
    >
      {{ error }}
    </div>

    <div
        v-if="resultMessage"
        class="rounded-lg border border-[var(--success)]/30 bg-[var(--success)]/5 p-3 text-sm mb-4"
    >
      {{ resultMessage }}
    </div>

    <div
        v-if="approvalsQuery.isLoading.value"
        class="text-sm text-[var(--muted-foreground)]"
    >
      加载中...
    </div>

    <div
        v-else-if="
        !approvalsQuery.data.value?.length
      "
        class="text-sm text-[var(--muted-foreground)] py-8 text-center"
    >
      暂无待审批项
    </div>

    <div
        v-else
        class="space-y-4"
    >
      <div
          v-for="item in approvalsQuery.data.value"
          :key="item.approvalId"
          class="rounded-lg border border-[var(--card-border)] bg-[var(--card)] p-4"
      >
        <div
            class="flex items-center justify-between mb-2"
        >
          <div
              class="flex items-center gap-2"
          >
            <span
                class="font-medium text-sm"
            >
              {{ item.operationName }}
            </span>

            <span
                class="text-xs px-1.5 py-0.5 rounded"
                :class="
                riskLevelColor(item.riskLevel)
              "
            >
              {{ item.riskLevel }}
            </span>
          </div>

          <span
              class="text-xs text-[var(--muted-foreground)]"
          >
            {{ item.agentName }}
          </span>
        </div>

        <p
            class="text-sm text-[var(--muted-foreground)] mb-3"
        >
          {{ item.reason }}
        </p>

        <div
            class="flex items-center gap-2"
        >
          <button
              class="inline-flex items-center rounded-lg bg-[var(--success)] text-[var(--success-foreground)] px-3 py-1.5 text-sm font-medium hover:bg-[var(--success)]/90 transition-colors disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
              :disabled="
              approving === item.approvalId ||
              rejecting === item.approvalId
            "
              @click="handleApprove(item)"
          >
            {{
              approving === item.approvalId
                  ? '处理中...'
                  : '通过'
            }}
          </button>

          <button
              class="inline-flex items-center rounded-lg bg-[var(--destructive)] text-[var(--destructive-foreground)] px-3 py-1.5 text-sm font-medium hover:bg-[var(--destructive)]/90 transition-colors disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
              :disabled="
              approving === item.approvalId ||
              rejecting === item.approvalId
            "
              @click="openRejectDialog(item)"
          >
            拒绝
          </button>
        </div>
      </div>
    </div>

    <!-- 拒绝原因弹窗 -->
    <Dialog
        :open="rejectDialogOpen"
        @update:open="
        rejectDialogOpen = $event
      "
    >
      <template #title>
        拒绝审批
      </template>

      <template #description>
        请输入拒绝原因（必填）
      </template>

      <template #content>
        <div class="space-y-3">
          <div
              v-if="rejectTarget"
              class="text-sm text-[var(--muted-foreground)]"
          >
            操作：{{ rejectTarget.operationName }}
          </div>

          <div>
            <label
                for="reject-comment"
                class="block text-sm text-[var(--muted-foreground)] mb-1"
            >
              拒绝原因
            </label>

            <textarea
                id="reject-comment"
                v-model="rejectComment"
                rows="3"
                class="w-full rounded-lg border border-[var(--input)] bg-transparent px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
                placeholder="请输入拒绝原因..."
                :disabled="rejecting !== null"
            />
          </div>

          <div
              class="flex justify-end gap-2"
          >
            <button
                class="rounded-lg border border-[var(--border)] px-4 py-2 text-sm hover:bg-[var(--muted)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
                @click="
                rejectDialogOpen = false
              "
            >
              取消
            </button>

            <button
                class="rounded-lg bg-[var(--destructive)] text-[var(--destructive-foreground)] px-4 py-2 text-sm font-medium hover:bg-[var(--destructive)]/90 transition-colors disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
                :disabled="
                !rejectComment.trim() ||
                rejecting !== null
              "
                @click="handleReject"
            >
              {{
                rejecting
                    ? '处理中...'
                    : '确认拒绝'
              }}
            </button>
          </div>
        </div>
      </template>
    </Dialog>
  </div>
</template>
