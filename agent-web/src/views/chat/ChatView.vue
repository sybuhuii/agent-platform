/**
 * 主聊天页 — ChatGPT 风格。
 *
 * 布局：
 * - 顶部标题栏（会话标题 + 模型选择器占位）
 * - 中间消息区域（居中、最大宽度限制）
 * - 底部输入区
 * - 空状态：居中 logo + 问候 + 快捷操作
 */
<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useAutoScroll } from '@/composables/useAutoScroll'
import ChatMessage from '@/components/chat/ChatMessage.vue'
import ChatComposer from '@/components/chat/ChatComposer.vue'
import RunStatusIndicator from '@/components/chat/RunStatusIndicator.vue'
import ApprovalPopup from '@/components/chat/ApprovalPopup.vue'
import { ArrowDown, Loader2, Bot, AlertCircle, Sparkles, MessageSquare, Code, BookOpen } from '@lucide/vue'
import * as approvalApi from '@/api/approvals'
import { useQueryClient } from '@tanstack/vue-query'

const chatStore = useChatStore()
const queryClient = useQueryClient()
const scrollContainer = ref<HTMLElement | null>(null)

const { showScrollButton, scrollToBottom, onScroll, watchMessages } = useAutoScroll(scrollContainer)
watchMessages(computed(() => chatStore.messages))

// 自动初始化 Supervisor
onMounted(() => {
  chatStore.initSupervisor()
})

// 获取当前待审批的消息（用于弹窗）
const pendingApproval = computed(() => {
  for (let i = chatStore.messages.length - 1; i >= 0; i--) {
    const msg = chatStore.messages[i]!
    if (msg.role === 'approval' && msg.status === 'pending') {
      return msg
    }
  }
  return null
})

// 当前会话标题
const conversationTitle = computed(() => chatStore.activeConversation?.title ?? '新对话')

// 快捷提问建议
const suggestions = [
  { icon: Sparkles, text: '帮我分析一组数据', desc: '数据洞察与可视化' },
  { icon: Code, text: '写一段代码', desc: '编程与调试' },
  { icon: BookOpen, text: '解释一个概念', desc: '学习与理解' },
  { icon: MessageSquare, text: '帮我写一封邮件', desc: '写作与沟通' },
]

function handleSuggestion(text: string) {
  chatStore.sendMessage(text)
}

async function handleApprove(approvalId: string, runId: string, approvalRunId?: string) {
  try {
    // 使用 approvalRunId（子 Agent runId）作为审批路径参数
    const targetRunId = approvalRunId ?? runId
    await approvalApi.decideAndResume(targetRunId, approvalId, 'APPROVE', '')
    await queryClient.invalidateQueries({ queryKey: ['hitl', 'approvals'] })
    const msg = chatStore.messages.find(m => m.role === 'approval' && m.id === pendingApproval.value?.id)
    if (msg && msg.role === 'approval') {
      msg.status = 'approved'
    }
  } catch {
    // 审批操作失败，弹窗保持
  }
}

async function handleReject(approvalId: string, runId: string, comment: string, approvalRunId?: string) {
  try {
    // 使用 approvalRunId（子 Agent runId）作为审批路径参数
    const targetRunId = approvalRunId ?? runId
    await approvalApi.decideAndResume(targetRunId, approvalId, 'REJECT', comment)
    await queryClient.invalidateQueries({ queryKey: ['hitl', 'approvals'] })
    const msg = chatStore.messages.find(m => m.role === 'approval' && m.id === pendingApproval.value?.id)
    if (msg && msg.role === 'approval') {
      msg.status = 'rejected'
    }
  } catch {
    // 审批操作失败，弹窗保持
  }
}

function handleRetry() {
  chatStore.initSupervisor()
}
</script>

<template>
  <div class="flex flex-col flex-1 min-h-0">
    <!-- 加载中 -->
    <div v-if="chatStore.systemStatus === 'loading'" class="flex-1 flex items-center justify-center">
      <div class="flex flex-col items-center gap-3 text-[var(--muted-foreground)]">
        <Loader2 class="w-6 h-6 animate-spin" aria-hidden="true" />
        <span class="text-sm">正在初始化...</span>
      </div>
    </div>

    <!-- 系统不可用 -->
    <div v-else-if="chatStore.systemStatus === 'unavailable'" class="flex-1 flex flex-col items-center justify-center text-[var(--muted-foreground)] py-20">
      <AlertCircle class="w-12 h-12 mb-4 opacity-40" aria-hidden="true" />
      <p class="text-base font-medium text-[var(--foreground)] mb-1">系统暂时不可用</p>
      <p class="text-sm mb-4">请稍后重试或联系管理员</p>
      <button
        class="rounded-lg bg-[var(--accent)] text-[var(--accent-foreground)] px-5 py-2.5 text-sm font-medium hover:bg-[var(--accent-hover)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
        @click="handleRetry"
      >
        重试
      </button>
    </div>

    <!-- 聊天主区域 -->
    <template v-else-if="chatStore.systemStatus === 'ready'">
      <!-- 运行状态指示条 -->
      <div v-if="chatStore.runState.status !== 'idle'" class="flex items-center justify-center py-1.5 bg-[var(--muted)]/50 border-b border-[var(--border)]">
        <RunStatusIndicator :state="chatStore.runState" />
      </div>

      <!-- 消息滚动区域 -->
      <div
        ref="scrollContainer"
        class="flex-1 overflow-y-auto"
        @scroll="onScroll"
      >
        <!-- 空状态 — ChatGPT 风格 -->
        <div v-if="chatStore.messages.length === 0" class="flex flex-col items-center justify-center min-h-full py-16">
          <div class="w-12 h-12 rounded-full bg-[var(--accent)]/10 flex items-center justify-center mb-6">
            <Bot class="w-7 h-7 text-[var(--accent)]" aria-hidden="true" />
          </div>
          <h1 class="text-2xl font-semibold text-[var(--foreground)] mb-2">有什么可以帮你？</h1>
          <p class="text-sm text-[var(--muted-foreground)] mb-8">选择一个话题开始，或直接输入你的问题</p>

          <!-- 快捷建议卡片 -->
          <div class="grid grid-cols-2 gap-3 w-full max-w-lg px-4">
            <button
              v-for="s in suggestions"
              :key="s.text"
              class="flex items-start gap-3 rounded-xl border border-[var(--border)] p-4 text-left hover:bg-[var(--muted)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] group"
              @click="handleSuggestion(s.text)"
            >
              <component :is="s.icon" class="w-5 h-5 text-[var(--muted-foreground)] shrink-0 mt-0.5 group-hover:text-[var(--accent)] transition-colors" aria-hidden="true" />
              <div>
                <div class="text-sm font-medium text-[var(--foreground)]">{{ s.text }}</div>
                <div class="text-xs text-[var(--muted-foreground)] mt-0.5">{{ s.desc }}</div>
              </div>
            </button>
          </div>
        </div>

        <!-- 消息列表 -->
        <div v-else class="max-w-[var(--chat-max-width)] mx-auto px-4 py-4">
          <ChatMessage
            v-for="msg in chatStore.messages"
            :key="msg.id"
            :message="msg"
          />
        </div>
      </div>

      <!-- 回到底部按钮 -->
      <div v-if="showScrollButton" class="flex justify-center py-2">
        <button
          class="inline-flex items-center gap-1 rounded-full bg-[var(--card)] border border-[var(--border)] px-3 py-1.5 text-xs text-[var(--muted-foreground)] shadow-sm hover:bg-[var(--muted)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
          @click="scrollToBottom()"
        >
          <ArrowDown class="w-3 h-3" aria-hidden="true" />
          回到底部
        </button>
      </div>

      <!-- 输入区域 -->
      <ChatComposer />
    </template>

    <!-- 审批弹窗 -->
    <ApprovalPopup
      v-if="pendingApproval"
      :message="pendingApproval"
      @approve="handleApprove"
      @reject="handleReject"
    />
  </div>
</template>
