/**
 * 聊天消息组件 — ChatGPT 风格。
 * - 用户消息：右侧灰色圆角气泡，无头像
 * - 助手消息：左侧，小圆形头像 + 内容，无边框卡片
 * - 工具调用、审批、错误使用专属卡片
 */
<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ChatMessage, UserChatMessage, AssistantChatMessage, ErrorChatMessage, ApprovalChatMessage, ToolResultChatMessage } from '@/types'
import { Bot, Check, Copy } from '@lucide/vue'
import MarkdownContent from './MarkdownContent.vue'
import ToolCallCard from './ToolCallCard.vue'
import ApprovalCard from './ApprovalCard.vue'

const props = defineProps<{
  message: ChatMessage
}>()

const copied = ref(false)

const isUser = computed(() => props.message.role === 'user')
const isAssistant = computed(() => props.message.role === 'assistant')
const isToolCall = computed(() => props.message.role === 'tool-call')
const isToolResult = computed(() => props.message.role === 'tool-result')
const isApproval = computed(() => props.message.role === 'approval')
const isError = computed(() => props.message.role === 'error')

const assistantMsg = computed<AssistantChatMessage | null>(() =>
  isAssistant.value ? props.message as AssistantChatMessage : null
)

const visibleAssistantErrorCode = computed(() => {
  const errorCode = assistantMsg.value?.errorCode
  return errorCode && errorCode !== 'APPROVAL_REQUIRED' ? errorCode : ''
})

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
    case 'COMPLETED': return ''
    case 'FAILED': return '执行失败'
    case 'RUNNING': return '运行中'
    case 'INTERRUPTED': return '已中断'
    default: return ''
  }
}

function formatTime(timestamp: number): string {
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(new Date(timestamp))
}

async function copyAssistantContent(): Promise<void> {
  if (!assistantMsg.value?.content || !navigator.clipboard) return
  await navigator.clipboard.writeText(assistantMsg.value.content)
  copied.value = true
  window.setTimeout(() => { copied.value = false }, 1600)
}

</script>

<template>
  <!-- 用户消息 — 右侧灰色气泡 -->
  <div v-if="isUser" class="chat-message chat-message-user">
    <div class="chat-user-message">
      <div class="chat-user-bubble">
        {{ userMsg!.content }}
      </div>
      <time class="chat-message-time" :datetime="new Date(userMsg!.timestamp).toISOString()">
        {{ formatTime(userMsg!.timestamp) }}
      </time>
    </div>
  </div>

  <!-- 助手消息 — 左侧头像 + 内容 -->
  <div v-else-if="isAssistant" class="chat-message chat-message-assistant">
    <div class="chat-assistant-row">
      <!-- 头像 -->
      <div class="agent-avatar" title="Agent 回复">
        <Bot class="w-4 h-4 text-[var(--background)]" aria-hidden="true" />
      </div>
      <!-- 内容 -->
      <div class="agent-response">
        <div v-if="runStatusLabel(assistantMsg!.status)" class="text-xs text-[var(--muted-foreground)] mb-1.5">
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
        <div v-if="visibleAssistantErrorCode" class="mt-2 text-sm text-[var(--destructive)]">
          错误码：{{ visibleAssistantErrorCode }}
        </div>
        <time
          v-if="assistantMsg!.content"
          class="chat-message-time mt-1 inline-block"
          :datetime="new Date(assistantMsg!.timestamp).toISOString()"
        >
          {{ formatTime(assistantMsg!.timestamp) }}
        </time>
        <details v-if="assistantMsg!.evidence && assistantMsg!.evidence.length > 0" class="mt-2">
          <summary class="cursor-pointer text-xs text-[var(--muted-foreground)] hover:text-[var(--foreground)]">
            证据 ({{ assistantMsg!.evidence!.length }})
          </summary>
          <ul class="mt-1 text-xs text-[var(--muted-foreground)] space-y-0.5">
            <li v-for="(e, i) in assistantMsg!.evidence" :key="i">{{ e }}</li>
          </ul>
        </details>

        <div v-if="assistantMsg!.content" class="message-actions" aria-label="回复操作">
          <button :aria-label="copied ? '已复制' : '复制回复'" :title="copied ? '已复制' : '复制'" @click="copyAssistantContent">
            <Check v-if="copied" class="h-4 w-4" />
            <Copy v-else class="h-4 w-4" />
          </button>
        </div>
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
  <div v-else-if="isApproval" class="chat-message chat-message-approval">
    <div class="chat-assistant-row">
      <div class="agent-avatar" title="Agent 审批请求">
        <Bot class="w-4 h-4 text-[var(--background)]" aria-hidden="true" />
      </div>
      <div class="min-w-0 flex-1">
        <ApprovalCard :message="approvalMsg!" />
      </div>
    </div>
  </div>

  <!-- 错误消息 -->
  <div v-else-if="isError" class="mb-3">
    <div class="rounded-xl border border-[var(--destructive)]/30 bg-[var(--destructive)]/5 p-3 text-sm text-[var(--destructive)]">
      <span v-if="errorMsg!.errorCode" class="font-mono text-xs mr-2">{{ errorMsg!.errorCode }}</span>
      {{ errorMsg!.content }}
    </div>
  </div>
</template>
