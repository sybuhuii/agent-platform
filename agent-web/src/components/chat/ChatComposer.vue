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
  minHeight: 54,
  maxHeight: 174
})

const canSend = computed(() =>
  inputValue.value.trim().length > 0 &&
  chatStore.isReady &&
  !chatStore.isRunning
)

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
  <div class="chat-composer-area">
    <div class="chat-composer-shell">
      <!-- 输入框容器 -->
      <div class="chat-composer-box">
        <textarea
          ref="textareaRef"
          v-model="inputValue"
          :disabled="chatStore.isRunning"
          placeholder="询问任何问题，描述你希望完成的任务"
          rows="1"
          class="chat-composer-input"
          @keydown="handleKeydown"
          @input="resize"
        />
        <!-- 发送/取消按钮 -->
        <div class="chat-composer-actions">
          <button
            v-if="chatStore.isRunning"
            class="inline-flex items-center justify-center h-8 w-8 rounded-full bg-[var(--foreground)] text-[var(--background)] hover:opacity-80 transition-opacity focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
            aria-label="取消等待"
            @click="handleCancel"
          >
            <Square class="w-4 h-4" />
          </button>
          <button
            v-else
            class="inline-flex items-center justify-center h-8 w-8 rounded-full transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
            :class="canSend
              ? 'bg-[var(--foreground)] text-[var(--background)] hover:opacity-80'
              : 'bg-[var(--muted)] text-[var(--muted-foreground)] cursor-not-allowed'"
            :disabled="!canSend"
            aria-label="发送消息"
            @click="handleSend"
          >
            <ArrowUp class="w-4 h-4" />
          </button>
        </div>
      </div>
      <p class="chat-disclaimer">AI 可能会出错，请核查重要信息。</p>
    </div>
  </div>
</template>
