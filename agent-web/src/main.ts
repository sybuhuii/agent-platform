import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin, type QueryClient } from '@tanstack/vue-query'
import App from './App.vue'
import router from './router/index'
import { registerUnauthorizedHandler } from './api/client'
import { useAuthStore } from './stores/auth'
import { useChatStore } from './stores/chat'
import './styles/globals.css'
import './styles/markdown.css'

const pinia = createPinia()

const app = createApp(App)

app.use(pinia)
app.use(VueQueryPlugin, {
  queryClientConfig: {
    defaultOptions: {
      queries: {
        refetchOnWindowFocus: false,
        retry: 1
      }
    }
  }
})
app.use(router)

// ─── 注册 401 集中处理器 ───
// 在 Pinia 就绪后注册，避免循环依赖
registerUnauthorizedHandler(() => {
  const authStore = useAuthStore()
  // 清空认证状态
  authStore.currentUser = null
  // 清除所有会话数据（401 时必须清除）
  const chatStore = useChatStore()
  chatStore.clearAllConversations()
  // 跳转登录页，带 redirect
  const currentPath = router.currentRoute.value.fullPath
  const redirect = currentPath !== '/login' ? currentPath : undefined
  router.push({
    path: '/login',
    query: redirect ? { redirect, expired: '1' } : { expired: '1' }
  })
})

app.mount('#app')
