/**
 * 登录页 — ChatGPT 风格。
 * - 居中布局，简洁大方
 * - 绿色主色按钮
 * - 产品名称突出
 */
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/errors'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

onMounted(() => {
  if (authStore.authenticated) {
    router.replace('/')
  }
  if (route.query.expired === '1') {
    error.value = '登录状态已失效，请重新登录'
  }
})

async function handleLogin() {
  if (!username.value || !password.value) return
  loading.value = true
  error.value = ''
  try {
    await authStore.login(username.value, password.value)
    password.value = ''
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : '认证失败'
    error.value = message
    password.value = ''
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex min-h-dvh items-center justify-center bg-[var(--background)] px-4">
    <div class="w-full max-w-[400px]">
      <!-- Logo + 标题 -->
      <div class="flex flex-col items-center mb-8">
        <div class="w-12 h-12 rounded-full bg-[var(--accent)] flex items-center justify-center mb-4">
          <Bot class="w-6 h-6 text-[var(--accent-foreground)]" aria-hidden="true" />
        </div>
        <h1 class="text-2xl font-semibold text-[var(--foreground)]">欢迎回来</h1>
        <p class="mt-2 text-sm text-[var(--muted-foreground)]">登录智能协作平台</p>
      </div>

      <!-- 登录表单 -->
      <form
        class="rounded-2xl border border-[var(--border)] bg-[var(--card)] p-6 shadow-sm"
        @submit.prevent="handleLogin"
      >
        <div class="space-y-4">
          <div>
            <label for="username" class="block text-sm font-medium text-[var(--foreground)] mb-1.5">用户名</label>
            <input
              id="username"
              v-model="username"
              type="text"
              autocomplete="username"
              :disabled="loading"
              class="w-full rounded-xl border border-[var(--input)] bg-transparent px-4 py-2.5 text-sm placeholder:text-[var(--muted-foreground)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:border-transparent disabled:opacity-50 transition-shadow"
              placeholder="请输入用户名"
            />
          </div>

          <div>
            <label for="password" class="block text-sm font-medium text-[var(--foreground)] mb-1.5">密码</label>
            <input
              id="password"
              v-model="password"
              type="password"
              autocomplete="current-password"
              :disabled="loading"
              class="w-full rounded-xl border border-[var(--input)] bg-transparent px-4 py-2.5 text-sm placeholder:text-[var(--muted-foreground)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:border-transparent disabled:opacity-50 transition-shadow"
              placeholder="请输入密码"
              @keydown.enter="handleLogin"
            />
          </div>

          <div v-if="error" role="alert" class="rounded-xl border border-[var(--destructive)]/30 bg-[var(--destructive)]/5 p-3 text-sm text-[var(--destructive)]">
            {{ error }}
          </div>

          <button
            type="submit"
            :disabled="loading || !username || !password"
            class="w-full rounded-xl bg-[var(--accent)] text-[var(--accent-foreground)] px-4 py-3 text-sm font-medium hover:bg-[var(--accent-hover)] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {{ loading ? '登录中...' : '登录' }}
          </button>
        </div>
      </form>

      <!-- 底部信息 -->
      <p class="mt-6 text-center text-xs text-[var(--muted-foreground)]">
        智能协作 — AI Agent 管理与协作平台
      </p>
    </div>
  </div>
</template>
