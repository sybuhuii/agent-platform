/**
 * 聊天核心 composable — 封装 chatStore 的操作。
 */
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'

export function useChat() {
  const chatStore = useChatStore()
  const authStore = useAuthStore()

  function sendMessage(content: string) {
    if (!authStore.authenticated) return
    if (!chatStore.isReady) return
    chatStore.sendMessage(content)
  }

  return {
    chat: chatStore,
    sendMessage
  }
}
