/**
 * 主聊天页 — 用户登录后直接与"系统"对话。
 *
 * 业务模型：用户 → 系统 → 唯一 Supervisor → 内部 Agent → Tool
 * - 不展示 Supervisor 选择器或技术名称
 * - 不展示 Agent 列表或选择器
 * - 登录后直接进入聊天
 * - 危险工具审批时弹出审批弹窗
 *
 * 系统初始化状态：
 * - loading: 正在获取 Supervisor
 * - ready: Supervisor 可用，进入聊天
 * - unavailable: 无 Supervisor 或请求失败，显示重试
 */
<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useAutoScroll } from '@/composables/useAutoScroll'
import ChatMessage from '@/components/chat/ChatMessage.vue'
import ChatComposer from '@/components/chat/ChatComposer.vue'
import RunStatusIndicator from '@/components/chat/RunStatusIndicator.vue'
import ApprovalPopup from '@/components/chat/ApprovalPopup.vue'
import { ArrowDown, Loader2, Bot, AlertCircle } from '@lucide/vue'
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

async function handleApprove(approvalId: string, runId: string) {
  try {
    await approvalApi.decideAndResume(runId, approvalId, 'APPROVE', '')
    await queryClient.invalidateQueries({ queryKey: ['hitl', 'approvals'] })
    const msg = chatStore.messages.find(m => m.role === 'approval' && m.id === pendingApproval.value?.id)
    if (msg && msg.role === 'approval') {
      msg.status = 'approved'
    }
  } catch {
    // 审批操作失败，弹窗保持
  }
}

async function handleReject(approvalId: string, runId: string, comment: string) {
  try {
    await approvalApi.decideAndResume(runId, approvalId, 'REJECT', comment)
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
    <!-- 顶部栏：显示会话标题和服务状态 -->
    <div class="flex items-center justify-between h-12 px-4 border-b border-[var(--border)] bg-[var(--background)] shrink-0">
      <h1 class="text-sm font-medium text-[var(--foreground)] truncate">{{ conversationTitle }}</h1>
      <span v-if="chatStore.isReady" class="flex items-center gap-1.5 text-xs text-[var(--success)]">
        <span class="w-1.5 h-1.5 rounded-full bg-[var(--success)]" />
        服务可用
      </span>
    </div>

    <!-- 运行状态指示 -->
    <div v-if="chatStore.runState.status !== 'idle'" class="flex items-center justify-center py-1 border-b border-[var(--border)] bg-[var(--muted)]/30">
      <RunStatusIndicator :state="chatStore.runState" />
    </div>

    <!-- 加载中 -->
    <div v-if="chatStore.systemStatus === 'loading'" class="flex-1 flex items-center justify-center">
      <div class="flex items-center gap-2 text-[var(--muted-foreground)]">
        <Loader2 class="w-5 h-5 animate-spin" aria-hidden="true" />
        <span class="text-sm">系统初始化中...</span>
      </div>
    </div>

    <!-- 系统不可用 -->
    <div v-else-if="chatStore.systemStatus === 'unavailable'" class="flex-1 flex flex-col items-center justify-center text-[var(--muted-foreground)] py-20">
      <AlertCircle class="w-10 h-10 mb-3 opacity-50" aria-hidden="true" />
      <p class="text-sm">系统暂时不可用</p>
      <button
        class="mt-3 rounded-lg bg-[var(--accent)] text-[var(--accent-foreground)] px-4 py-2 text-sm font-medium hover:bg-[var(--accent)]/90 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
        @click="handleRetry"
      >
        重试
      </button>
    </div>

    <!-- 消息区域 -->
    <div
      v-else-if="chatStore.systemStatus === 'ready'"
      ref="scrollContainer"
      class="flex-1 overflow-y-auto py-4"
      @scroll="onScroll"
    >
      <!-- 空状态 -->
      <div v-if="chatStore.messages.length === 0" class="flex flex-col items-center justify-center h-full text-[var(--muted-foreground)]">
        <div class="w-16 h-16 rounded-2xl bg-[var(--accent)]/10 flex items-center justify-center mb-4">
          <Bot class="w-8 h-8 text-[var(--accent)]" aria-hidden="true" />
        </div>
        <h2 class="text-lg font-medium text-[var(--foreground)] mb-1">有什么可以帮你？</h2>
        <p class="text-sm text-[var(--muted-foreground)]">向系统发送消息开始对话</p>
      </div>

      <!-- 消息列表 -->
      <div v-else>
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
        class="inline-flex items-center gap-1 rounded-full bg-[var(--muted)] border border-[var(--border)] px-3 py-1.5 text-xs text-[var(--muted-foreground)] hover:bg-[var(--muted)]/80 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
        @click="scrollToBottom()"
      >
        <ArrowDown class="w-3 h-3" aria-hidden="true" />
        回到底部
      </button>
    </div>

    <!-- 输入区域 -->
    <ChatComposer v-if="chatStore.isReady" />

    <!-- 审批弹窗 — 危险工具审批时立即弹出 -->
    <ApprovalPopup
      v-if="pendingApproval"
      :message="pendingApproval"
      @approve="handleApprove"
      @reject="handleReject"
    />
  </div>
</template>
