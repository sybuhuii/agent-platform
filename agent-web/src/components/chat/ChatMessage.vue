/**
 * 聊天消息组件 — 根据消息类型渲染不同样式。
 * - 助手消息以"系统"身份显示，配统一系统头像，不展示 Supervisor/Agent 技术名称
 * - 用户消息靠右，使用淡紫色圆角气泡
 * - 系统消息靠左，使用白色/浅色内容区域
 * - 工具调用、审批、错误使用专属卡片
 * - 消息之间有充分垂直间距
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

function formatTimestamp(ts: number): string {
  const d = new Date(ts)
  const h = d.getHours().toString().padStart(2, '0')
  const m = d.getMinutes().toString().padStart(2, '0')
  return `${h}:${m}`
}

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
  <!-- 用户消息 — 靠右，淡紫色圆角气泡 -->
  <div v-if="isUser" class="flex justify-end px-4 py-3">
    <div class="max-w-[80%]">
      <div class="rounded-2xl rounded-br-sm bg-[var(--accent)] text-[var(--accent-foreground)] px-4 py-2.5 text-sm leading-relaxed">
        {{ userMsg!.content }}
      </div>
      <div class="text-xs text-[var(--muted-foreground)] mt-1 text-right">{{ formatTimestamp(userMsg!.timestamp) }}</div>
    </div>
  </div>

  <!-- 助手消息 — 靠左，配系统头像，白色/浅色内容区域 -->
  <div v-else-if="isAssistant" class="px-4 py-3 max-w-[var(--chat-max-width)] mx-auto w-full">
    <div class="flex items-start gap-3">
      <!-- 系统头像 -->
      <div class="w-8 h-8 rounded-lg bg-[var(--accent)]/10 flex items-center justify-center shrink-0 mt-0.5">
        <Bot class="w-4 h-4 text-[var(--accent)]" aria-hidden="true" />
      </div>
      <div class="flex-1 min-w-0">
        <div class="text-xs font-medium text-[var(--muted-foreground)] mb-1">系统</div>
        <div v-if="assistantMsg!.status" class="text-xs text-[var(--muted-foreground)] mb-1">
          {{ runStatusLabel(assistantMsg!.status) }}
        </div>
        <div v-if="assistantMsg!.content" class="rounded-xl bg-[var(--card)] border border-[var(--card-border)] p-3 text-sm leading-relaxed">
          <MarkdownContent :content="assistantMsg!.content" />
        </div>
        <div v-else-if="assistantMsg!.success === undefined" class="flex items-center gap-2 text-[var(--muted-foreground)] py-2">
          <span class="inline-block w-2 h-2 rounded-full bg-[var(--accent)] animate-pulse" />
          思考中...
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
        <div class="text-xs text-[var(--muted-foreground)] mt-1">{{ formatTimestamp(assistantMsg!.timestamp) }}</div>
      </div>
    </div>
  </div>

  <!-- 工具调用 -->
  <div v-else-if="isToolCall" class="px-4 py-1.5 max-w-[var(--chat-max-width)] mx-auto w-full">
    <ToolCallCard :message="message" />
  </div>

  <!-- 工具结果 -->
  <div v-else-if="isToolResult" class="px-4 py-1.5 max-w-[var(--chat-max-width)] mx-auto w-full">
    <div class="text-xs text-[var(--muted-foreground)] border border-[var(--border)] rounded-lg p-3 bg-[var(--muted)]">
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

  <!-- 审批卡片 — 聊天流中的摘要，弹窗由 ApprovalPopup 处理 -->
  <div v-else-if="isApproval" class="px-4 py-2 max-w-[var(--chat-max-width)] mx-auto w-full">
    <ApprovalCard :message="approvalMsg!" />
  </div>

  <!-- 错误消息 -->
  <div v-else-if="isError" class="px-4 py-2 max-w-[var(--chat-max-width)] mx-auto w-full">
    <div class="rounded-lg border border-[var(--destructive)]/30 bg-[var(--destructive)]/5 p-3 text-sm text-[var(--destructive)]">
      <span v-if="errorMsg!.errorCode" class="font-mono text-xs mr-2">{{ errorMsg!.errorCode }}</span>
      {{ errorMsg!.content }}
    </div>
  </div>
</template>
