/**
 * 聊天输入组件 — 用户直接与"系统"对话。
 * - 不展示 Supervisor 技术名称
 * - Enter 发送，Shift+Enter 换行
 * - 请求进行中防止重复提交
 * - 支持取消前端请求
 * - 移动端适配安全区域
 * - 白色大圆角输入容器，柔和边框和阴影
 * - 发送按钮使用主色
 * - 下方显示 AI 生成提示
 */
<script setup lang="ts">
import { ref, nextTick, computed } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useTextareaAutosize } from '@/composables/useTextareaAutosize'
import { Send, Square } from '@lucide/vue'

const chatStore = useChatStore()

const textareaRef = ref<HTMLTextAreaElement | null>(null)
const inputValue = ref('')

const { resize } = useTextareaAutosize(textareaRef, inputValue, {
  minHeight: 44,
  maxHeight: 200
})

const canSend = computed(() => inputValue.value.trim().length > 0 && !chatStore.isRunning)

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function handleSend() {
  if (!canSend.value) return
  chatStore.sendMessage(inputValue.value)
  inputValue.value = ''
  nextTick(resize)
}

function handleCancel() {
  chatStore.cancelRequest()
}
</script>

<template>
  <div class="shrink-0 bg-[var(--background)] px-4 pb-4 pt-2 pb-[env(safe-area-inset-bottom,0px)]">
    <div class="max-w-[var(--chat-max-width)] mx-auto">
      <div class="flex items-end gap-2 rounded-2xl border border-[var(--border)] bg-[var(--card)] p-2 shadow-sm focus-within:ring-2 focus-within:ring-[var(--ring)] focus-within:ring-offset-1 focus-within:border-[var(--ring)] transition-all">
        <textarea
          ref="textareaRef"
          v-model="inputValue"
          :disabled="chatStore.isRunning"
          placeholder="输入消息..."
          rows="1"
          class="flex-1 bg-transparent text-sm resize-none outline-none placeholder:text-[var(--muted-foreground)] min-h-[44px] max-h-[200px] py-2 px-2"
          @keydown="handleKeydown"
          @input="resize"
        />
        <button
          v-if="chatStore.isRunning"
          class="shrink-0 inline-flex items-center justify-center h-9 w-9 rounded-lg text-[var(--destructive)] hover:bg-[var(--destructive)]/10 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
          aria-label="取消等待"
          @click="handleCancel"
        >
          <Square class="w-4 h-4" />
        </button>
        <button
          v-else
          class="shrink-0 inline-flex items-center justify-center h-9 w-9 rounded-lg transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
          :class="canSend ? 'bg-[var(--success)] text-[var(--success-foreground)] hover:bg-[var(--success)]/90' : 'bg-[var(--muted)] text-[var(--muted-foreground)] cursor-not-allowed'"
          :disabled="!canSend"
          aria-label="发送消息"
          @click="handleSend"
        >
          <Send class="w-4 h-4" />
        </button>
      </div>
      <p class="text-xs text-[var(--muted-foreground)] mt-2 text-center opacity-70">
        内容由 AI 生成，请仔细甄别
      </p>
    </div>
  </div>
</template>
