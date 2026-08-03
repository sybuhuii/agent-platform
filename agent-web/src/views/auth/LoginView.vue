<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ArrowRight, Sparkles } from '@lucide/vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

onMounted(() => {
  if (authStore.authenticated) router.replace('/')
  if (route.query.expired === '1') error.value = '登录状态已失效，请重新登录'
})

async function handleLogin() {
  if (!username.value.trim() || !password.value || loading.value) return
  loading.value = true
  error.value = ''
  try {
    await authStore.login(username.value.trim(), password.value)
    password.value = ''
    await router.push((route.query.redirect as string) || '/')
  } catch (cause: unknown) {
    error.value = cause instanceof Error ? cause.message : '登录失败，请稍后重试'
    password.value = ''
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <header class="auth-header">
      <router-link to="/login" class="auth-brand" aria-label="智能协作首页">
        <span class="auth-brand-mark"><Sparkles :size="19" stroke-width="2" /></span>
        <span>智能协作</span>
      </router-link>
    </header>

    <section class="auth-panel" aria-labelledby="login-title">
      <div class="auth-heading">
        <h1 id="login-title">欢迎回来</h1>
        <p>继续你的智能协作对话</p>
      </div>

      <form class="auth-form" @submit.prevent="handleLogin">
        <label class="auth-field">
          <span>用户名</span>
          <input
            v-model="username"
            type="text"
            autocomplete="username"
            placeholder="请输入用户名"
            :disabled="loading"
            autofocus
          />
        </label>

        <label class="auth-field">
          <span>密码</span>
          <input
            v-model="password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
            :disabled="loading"
          />
        </label>

        <p v-if="error" class="auth-error" role="alert">{{ error }}</p>

        <button class="auth-submit" type="submit" :disabled="loading || !username.trim() || !password">
          <span>{{ loading ? '正在登录…' : '继续' }}</span>
          <ArrowRight v-if="!loading" :size="18" aria-hidden="true" />
        </button>
      </form>

      <div class="auth-divider"><span>还没有账号？</span></div>
      <router-link class="auth-secondary" to="/register">创建账号</router-link>

      <p class="auth-footnote">登录即表示你同意安全、负责地使用 AI 服务。</p>
    </section>
  </main>
</template>
