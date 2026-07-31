/**
 * 聊天输入组件 — ChatGPT 风格。
 * - 居中圆角输入框，内嵌发送按钮
 * - 发送按钮为绿色圆形
 * - 下方显示 AI 免责提示
 * - Enter 发送，Shift+Enter 换行
 */
<script setup lang="ts">
import { ref, nextTick, computed } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useTextareaAutosize } from '@/composables/useTextareaAutosize'
import { ArrowUp, Square } from '@lucide/vue'

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
      <!-- 输入框容器 -->
      <div class="relative flex items-end rounded-2xl border border-[var(--border)] bg-[var(--card)] shadow-sm focus-within:shadow-md focus-within:border-[var(--ring)]/50 transition-all">
        <textarea
          ref="textareaRef"
          v-model="inputValue"
          :disabled="chatStore.isRunning"
          placeholder="给智能协作发送消息..."
          rows="1"
          class="flex-1 bg-transparent text-[0.9375rem] resize-none outline-none placeholder:text-[var(--muted-foreground)] min-h-[52px] max-h-[200px] py-3.5 pl-4 pr-14"
          @keydown="handleKeydown"
          @input="resize"
        />
        <!-- 发送/取消按钮 -->
        <div class="absolute right-2 bottom-2.5">
          <button
            v-if="chatStore.isRunning"
            class="inline-flex items-center justify-center h-8 w-8 rounded-lg text-[var(--muted-foreground)] hover:bg-[var(--muted)] hover:text-[var(--destructive)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
            aria-label="取消等待"
            @click="handleCancel"
          >
            <Square class="w-4 h-4" />
          </button>
          <button
            v-else
            class="inline-flex items-center justify-center h-8 w-8 rounded-lg transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
            :class="canSend
              ? 'bg-[var(--accent)] text-[var(--accent-foreground)] hover:bg-[var(--accent-hover)]'
              : 'bg-[var(--muted)] text-[var(--muted-foreground)] cursor-not-allowed'"
            :disabled="!canSend"
            aria-label="发送消息"
            @click="handleSend"
          >
            <ArrowUp class="w-4 h-4" />
          </button>
        </div>
      </div>
      <!-- AI 免责提示 -->
      <p class="text-xs text-[var(--muted-foreground)] mt-2 text-center">
        内容由 AI 生成，请仔细甄别
      </p>
    </div>
  </div>
</template>
