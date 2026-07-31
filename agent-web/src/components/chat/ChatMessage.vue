/**
 * 聊天消息组件 — ChatGPT 风格。
 * - 用户消息：右侧灰色圆角气泡，无头像
 * - 助手消息：左侧，小圆形头像 + 内容，无边框卡片
 * - 工具调用、审批、错误使用专属卡片
 */
<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage, UserChatMessage, AssistantChatMessage, ErrorChatMessage, ApprovalChatMessage, ToolResultChatMessage } from '@/types'
import { Bot } from '@lucide/vue'
import MarkdownContent from './MarkdownContent.vue'
import ToolCallCard from './ToolCallCard.vue'
import ApprovalCard from './ApprovalCard.vue'

const props = defineProps<{
  message: ChatMessage
}>()

const isUser = computed(() => props.message.role === 'user')
const isAssistant = computed(() => props.message.role === 'assistant')
const isToolCall = computed(() => props.message.role === 'tool-call')
const isToolResult = computed(() => props.message.role === 'tool-result')
const isApproval = computed(() => props.message.role === 'approval')
const isError = computed(() => props.message.role === 'error')

const assistantMsg = computed<AssistantChatMessage | null>(() =>
  isAssistant.value ? props.message as AssistantChatMessage : null
)

const userMsg = computed<UserChatMessage | null>(() =>
  isUser.value ? props.message as UserChatMessage : null
)

const errorMsg = computed<ErrorChatMessage | null>(() =>
  isError.value ? props.message as ErrorChatMessage : null
)

const approvalMsg = computed<ApprovalChatMessage | null>(() =>
  isApproval.value ? props.message as ApprovalChatMessage : null
)

const toolResultMsg = computed<ToolResultChatMessage | null>(() =>
  isToolResult.value ? props.message as ToolResultChatMessage : null
)

function runStatusLabel(status?: string): string {
  switch (status) {
    case 'SUSPENDED': return '等待审批'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '执行失败'
    case 'RUNNING': return '运行中'
    case 'INTERRUPTED': return '已中断'
    default: return ''
  }
}
</script>

<template>
  <!-- 用户消息 — 右侧灰色气泡 -->
  <div v-if="isUser" class="flex justify-end mb-4">
    <div class="max-w-[85%] rounded-2xl bg-[var(--user-bubble)] text-[var(--user-bubble-foreground)] px-4 py-3 text-[0.9375rem] leading-relaxed">
      {{ userMsg!.content }}
    </div>
  </div>

  <!-- 助手消息 — 左侧头像 + 内容 -->
  <div v-else-if="isAssistant" class="mb-4">
    <div class="flex items-start gap-4">
      <!-- 头像 -->
      <div class="w-7 h-7 rounded-full bg-[var(--accent)] flex items-center justify-center shrink-0 mt-1">
        <Bot class="w-4 h-4 text-[var(--accent-foreground)]" aria-hidden="true" />
      </div>
      <!-- 内容 -->
      <div class="flex-1 min-w-0">
        <div v-if="assistantMsg!.status" class="text-xs text-[var(--muted-foreground)] mb-1.5">
          {{ runStatusLabel(assistantMsg!.status) }}
        </div>
        <div v-if="assistantMsg!.content" class="text-[0.9375rem] leading-relaxed">
          <MarkdownContent :content="assistantMsg!.content" />
        </div>
        <div v-else-if="assistantMsg!.success === undefined" class="flex items-center gap-2 text-[var(--muted-foreground)] py-1">
          <div class="flex gap-1">
            <span class="w-1.5 h-1.5 rounded-full bg-[var(--muted-foreground)] animate-bounce" style="animation-delay: 0ms" />
            <span class="w-1.5 h-1.5 rounded-full bg-[var(--muted-foreground)] animate-bounce" style="animation-delay: 150ms" />
            <span class="w-1.5 h-1.5 rounded-full bg-[var(--muted-foreground)] animate-bounce" style="animation-delay: 300ms" />
          </div>
        </div>
        <div v-if="assistantMsg!.errorCode" class="mt-2 text-sm text-[var(--destructive)]">
          错误码：{{ assistantMsg!.errorCode }}
        </div>
        <details v-if="assistantMsg!.evidence && assistantMsg!.evidence.length > 0" class="mt-2">
          <summary class="cursor-pointer text-xs text-[var(--muted-foreground)] hover:text-[var(--foreground)]">
            证据 ({{ assistantMsg!.evidence!.length }})
          </summary>
          <ul class="mt-1 text-xs text-[var(--muted-foreground)] space-y-0.5">
            <li v-for="(e, i) in assistantMsg!.evidence" :key="i">{{ e }}</li>
          </ul>
        </details>
      </div>
    </div>
  </div>

  <!-- 工具调用 -->
  <div v-else-if="isToolCall" class="mb-3">
    <ToolCallCard :message="message" />
  </div>

  <!-- 工具结果 -->
  <div v-else-if="isToolResult" class="mb-3">
    <div class="text-xs text-[var(--muted-foreground)] border border-[var(--border)] rounded-xl p-3 bg-[var(--muted)]/50">
      <span class="font-medium">{{ toolResultMsg!.toolName }}</span>
      <span :class="toolResultMsg!.success ? 'text-[var(--success)]' : 'text-[var(--destructive)]'" class="ml-2">
        {{ toolResultMsg!.success ? '成功' : '失败' }}
      </span>
      <details v-if="toolResultMsg!.result" class="mt-1">
        <summary class="cursor-pointer">详情</summary>
        <pre class="mt-1 whitespace-pre-wrap text-xs">{{ toolResultMsg!.result }}</pre>
      </details>
    </div>
  </div>

  <!-- 审批卡片 -->
  <div v-else-if="isApproval" class="mb-3">
    <ApprovalCard :message="approvalMsg!" />
  </div>

  <!-- 错误消息 -->
  <div v-else-if="isError" class="mb-3">
    <div class="rounded-xl border border-[var(--destructive)]/30 bg-[var(--destructive)]/5 p-3 text-sm text-[var(--destructive)]">
      <span v-if="errorMsg!.errorCode" class="font-mono text-xs mr-2">{{ errorMsg!.errorCode }}</span>
      {{ errorMsg!.content }}
    </div>
  </div>
</template>
