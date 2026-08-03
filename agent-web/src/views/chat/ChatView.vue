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
import { computed, onMounted, ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import {
  AlertCircle,
  ArrowDown,
  BookOpen,
  Code,
  Loader2,
  MessageSquare,
  Sparkles
} from '@lucide/vue'

import { useChatStore } from '@/stores/chat'
import { useAutoScroll } from '@/composables/useAutoScroll'
import ChatMessage from '@/components/chat/ChatMessage.vue'
import ChatComposer from '@/components/chat/ChatComposer.vue'
import RunStatusIndicator from '@/components/chat/RunStatusIndicator.vue'
import ApprovalPopup from '@/components/chat/ApprovalPopup.vue'
import * as approvalApi from '@/api/approvals'

const chatStore = useChatStore()
const queryClient = useQueryClient()

const scrollContainer = ref<HTMLElement | null>(null)
const approvalSubmitting = ref(false)
const approvalError = ref('')

const showRunStatus = computed(() =>
  ['submitting', 'running', 'suspended', 'failed'].includes(chatStore.runState.status)
)

const {
  showScrollButton,
  scrollToBottom,
  onScroll,
  watchMessages
} = useAutoScroll(scrollContainer)

watchMessages(computed(() => chatStore.messages))

// 自动初始化 Supervisor
onMounted(() => {
  chatStore.initSupervisor()
})

// 获取当前待审批的消息（用于弹窗）
const pendingApproval = computed(() => {
  for (let i = chatStore.messages.length - 1; i >= 0; i--) {
    const message = chatStore.messages[i]!

    if (
        message.role === 'approval' &&
        message.status === 'pending'
    ) {
      return message
    }
  }

  return null
})

// 快捷提问建议
const suggestions = [
  {
    icon: Sparkles,
    text: '请删除演示记录 demo-1，原因为审批功能演示',
    desc: '演示工具审批中断与恢复'
  },
  {
    icon: Code,
    text: '请执行节点审批中断恢复演示',
    desc: '演示流程节点审批与续跑'
  },
  {
    icon: BookOpen,
    text: '解释一个概念',
    desc: '学习与理解'
  },
  {
    icon: MessageSquare,
    text: '帮我写一封邮件',
    desc: '写作与沟通'
  }
]

function handleSuggestion(text: string): void {
  chatStore.sendMessage(text)
}

async function handleApprove(
    approvalId: string,
    runId: string,
    approvalRunId?: string
): Promise<void> {
  const messageId = pendingApproval.value?.id
  const conversationId = chatStore.activeConversationId

  if (!messageId || !conversationId || approvalSubmitting.value) {
    return
  }

  approvalSubmitting.value = true
  approvalError.value = ''

  try {
    // Supervisor 嵌套场景优先使用子 Agent 的 runId。
    const targetRunId = approvalRunId ?? runId

    const response = await approvalApi.decideAndResume(
        targetRunId,
        approvalId,
        'APPROVE',
        ''
    )

    const normalizedResponse =
        approvalApi.normalizeApprovalResumeResponse(response)

    chatStore.handleApprovalResume(
        normalizedResponse,
        messageId,
        'APPROVE',
        conversationId
    )

    await queryClient.invalidateQueries({
      queryKey: ['hitl', 'approvals']
    })
  } catch (error: unknown) {
    approvalError.value =
        error instanceof Error
            ? error.message
            : '审批操作失败'
  } finally {
    approvalSubmitting.value = false
  }
}

async function handleReject(
    approvalId: string,
    runId: string,
    comment: string,
    approvalRunId?: string
): Promise<void> {
  const messageId = pendingApproval.value?.id
  const conversationId = chatStore.activeConversationId

  if (!messageId || !conversationId || approvalSubmitting.value) {
    return
  }

  approvalSubmitting.value = true
  approvalError.value = ''

  try {
    // Supervisor 嵌套场景优先使用子 Agent 的 runId。
    const targetRunId = approvalRunId ?? runId

    const response = await approvalApi.decideAndResume(
        targetRunId,
        approvalId,
        'REJECT',
        comment
    )

    const normalizedResponse =
        approvalApi.normalizeApprovalResumeResponse(response)

    chatStore.handleApprovalResume(
        normalizedResponse,
        messageId,
        'REJECT',
        conversationId
    )

    await queryClient.invalidateQueries({
      queryKey: ['hitl', 'approvals']
    })
  } catch (error: unknown) {
    approvalError.value =
        error instanceof Error
            ? error.message
            : '审批操作失败'
  } finally {
    approvalSubmitting.value = false
  }
}

function handleApprovalResolved(approvalId: string) {
  chatStore.markApprovalResolved(approvalId)
  approvalError.value = ''
  void queryClient.invalidateQueries({ queryKey: ['pending-approvals'] })
}

function handleRetry(): void {
  chatStore.initSupervisor()
}

</script>

<template>
  <div class="chat-workspace flex flex-col flex-1 min-h-0">
    <!-- 加载中 -->
    <div
        v-if="chatStore.systemStatus === 'loading'"
        class="flex-1 flex items-center justify-center"
    >
      <div
          class="flex flex-col items-center gap-3 text-[var(--muted-foreground)]"
      >
        <Loader2
            class="w-6 h-6 animate-spin"
            aria-hidden="true"
        />

        <span class="text-sm">
          正在初始化...
        </span>
      </div>
    </div>

    <!-- 系统不可用 -->
    <div
        v-else-if="chatStore.systemStatus === 'unavailable'"
        class="flex-1 flex flex-col items-center justify-center text-[var(--muted-foreground)] py-20"
    >
      <AlertCircle
          class="w-12 h-12 mb-4 opacity-40"
          aria-hidden="true"
      />

      <p
          class="text-base font-medium text-[var(--foreground)] mb-1"
      >
        系统暂时不可用
      </p>

      <p class="text-sm mb-4">
        请稍后重试或联系管理员
      </p>

      <button
          class="rounded-lg bg-[var(--accent)] text-[var(--accent-foreground)] px-5 py-2.5 text-sm font-medium hover:bg-[var(--accent-hover)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
          @click="handleRetry"
      >
        重试
      </button>
    </div>

    <!-- 聊天主区域 -->
    <template
        v-else-if="chatStore.systemStatus === 'ready'"
    >
      <!-- 运行状态指示条 -->
      <div
          v-if="showRunStatus"
          class="chat-run-status"
      >
        <RunStatusIndicator
            :state="chatStore.runState"
        />
      </div>

      <!-- 消息滚动区域 -->
      <div
          ref="scrollContainer"
          class="chat-scroll-area flex-1 overflow-y-auto"
          @scroll="onScroll"
      >
        <!-- 空状态 -->
        <div
            v-if="chatStore.messages.length === 0"
            class="flex flex-col items-center justify-center min-h-full px-4 py-16"
        >
          <h1
            class="text-[28px] font-semibold tracking-[-0.035em] text-[var(--foreground)] mb-2"
          >
            有什么可以帮你？
          </h1>

          <p
            class="text-sm text-[var(--muted-foreground)] mb-10"
          >
            选择一个话题开始，或直接输入你的问题
          </p>

          <!-- 快捷建议卡片 -->
          <div
              class="grid grid-cols-1 sm:grid-cols-2 gap-2.5 w-full max-w-[640px]"
          >
            <button
                v-for="suggestion in suggestions"
                :key="suggestion.text"
                class="flex items-start gap-3 rounded-2xl border border-[var(--border)] p-4 text-left hover:bg-[var(--muted)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] group"
                @click="handleSuggestion(suggestion.text)"
            >
              <component
                  :is="suggestion.icon"
                  class="w-5 h-5 text-[var(--muted-foreground)] shrink-0 mt-0.5 group-hover:text-[var(--accent)] transition-colors"
                  aria-hidden="true"
              />

              <div>
                <div
                    class="text-sm font-medium text-[var(--foreground)]"
                >
                  {{ suggestion.text }}
                </div>

                <div
                    class="text-xs text-[var(--muted-foreground)] mt-0.5"
                >
                  {{ suggestion.desc }}
                </div>
              </div>
            </button>
          </div>
        </div>

        <!-- 消息列表 -->
        <div
            v-else
            class="chat-thread-shell"
        >
          <ChatMessage
              v-for="message in chatStore.messages"
              :key="message.id"
              :message="message"
          />
        </div>
      </div>

      <!-- 回到底部按钮 -->
      <div
          v-if="showScrollButton"
          class="flex justify-center py-2"
      >
        <button
            class="inline-flex items-center gap-1 rounded-full bg-[var(--card)] border border-[var(--border)] px-3 py-1.5 text-xs text-[var(--muted-foreground)] shadow-sm hover:bg-[var(--muted)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
            @click="scrollToBottom()"
        >
          <ArrowDown
              class="w-3 h-3"
              aria-hidden="true"
          />

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
        :submitting="approvalSubmitting"
        :error="approvalError"
        @approve="handleApprove"
        @reject="handleReject"
        @resolved="handleApprovalResolved"
    />
  </div>
</template>
