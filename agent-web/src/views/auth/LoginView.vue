/**
 * 登录页 — 简洁产品化页面。
 * - 居中登录卡片
 * - 产品名称和简短描述
 * - 用户名、密码输入
 * - 登录中状态
 * - 清晰但不泄漏内部信息的错误提示
 * - Enter 提交
 * - 密码不得写入持久化存储
 * - 不展示默认示例密码
 */
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

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
    <div class="w-full max-w-sm">
      <div class="text-center mb-8">
        <h1 class="text-2xl font-bold text-[var(--foreground)]">Agent Platform</h1>
        <p class="mt-2 text-sm text-[var(--muted-foreground)]">AI Agent 管理与协作平台</p>
      </div>

      <form
        class="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-6 shadow-sm"
        @submit.prevent="handleLogin"
      >
        <div class="space-y-4">
          <div>
            <label for="username" class="block text-sm font-medium text-[var(--muted-foreground)] mb-1">用户名</label>
            <input
              id="username"
              v-model="username"
              type="text"
              autocomplete="username"
              :disabled="loading"
              class="w-full rounded-lg border border-[var(--input)] bg-transparent px-3 py-2 text-sm placeholder:text-[var(--muted-foreground)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] disabled:opacity-50"
              placeholder="请输入用户名"
            />
          </div>

          <div>
            <label for="password" class="block text-sm font-medium text-[var(--muted-foreground)] mb-1">密码</label>
            <input
              id="password"
              v-model="password"
              type="password"
              autocomplete="current-password"
              :disabled="loading"
              class="w-full rounded-lg border border-[var(--input)] bg-transparent px-3 py-2 text-sm placeholder:text-[var(--muted-foreground)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] disabled:opacity-50"
              placeholder="请输入密码"
              @keydown.enter="handleLogin"
            />
          </div>

          <div v-if="error" role="alert" class="rounded-lg border border-[var(--destructive)]/30 bg-[var(--destructive)]/5 p-3 text-sm text-[var(--destructive)]">
            {{ error }}
          </div>

          <button
            type="submit"
            :disabled="loading || !username || !password"
            class="w-full rounded-lg bg-[var(--accent)] text-[var(--accent-foreground)] px-4 py-2.5 text-sm font-medium hover:bg-[var(--accent)]/90 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {{ loading ? '登录中...' : '登录' }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
