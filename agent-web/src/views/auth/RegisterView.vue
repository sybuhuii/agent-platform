<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowRight, Check, Sparkles } from '@lucide/vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const error = ref('')

const passwordLongEnough = computed(() => password.value.length >= 8)
const passwordsMatch = computed(() => confirmPassword.value.length > 0 && password.value === confirmPassword.value)
const canSubmit = computed(() =>
  username.value.trim().length >= 3 && passwordLongEnough.value && passwordsMatch.value && !loading.value
)

async function handleRegister() {
  if (!canSubmit.value) return
  loading.value = true
  error.value = ''
  try {
    await authStore.register(username.value.trim(), password.value, confirmPassword.value)
    password.value = ''
    confirmPassword.value = ''
    await router.push('/')
  } catch (cause: unknown) {
    error.value = cause instanceof Error ? cause.message : '注册失败，请稍后重试'
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

    <section class="auth-panel" aria-labelledby="register-title">
      <div class="auth-heading">
        <h1 id="register-title">创建你的账号</h1>
        <p>几秒钟后，就可以开始与 AI 协作</p>
      </div>

      <form class="auth-form" @submit.prevent="handleRegister">
        <label class="auth-field">
          <span>用户名</span>
          <input
            v-model="username"
            type="text"
            autocomplete="username"
            placeholder="3–32 位字母、数字或中文"
            :disabled="loading"
            autofocus
          />
        </label>

        <label class="auth-field">
          <span>密码</span>
          <input
            v-model="password"
            type="password"
            autocomplete="new-password"
            placeholder="至少 8 个字符"
            :disabled="loading"
          />
        </label>

        <label class="auth-field">
          <span>确认密码</span>
          <input
            v-model="confirmPassword"
            type="password"
            autocomplete="new-password"
            placeholder="再次输入密码"
            :disabled="loading"
          />
        </label>

        <div class="auth-requirements" aria-live="polite">
          <span :class="{ valid: passwordLongEnough }"><Check :size="14" /> 至少 8 个字符</span>
          <span :class="{ valid: passwordsMatch }"><Check :size="14" /> 两次密码一致</span>
        </div>

        <p v-if="error" class="auth-error" role="alert">{{ error }}</p>

        <button class="auth-submit" type="submit" :disabled="!canSubmit">
          <span>{{ loading ? '正在创建…' : '创建账号' }}</span>
          <ArrowRight v-if="!loading" :size="18" aria-hidden="true" />
        </button>
      </form>

      <div class="auth-divider"><span>已经有账号？</span></div>
      <router-link class="auth-secondary" to="/login">返回登录</router-link>

      <p class="auth-footnote">新账号将获得平台配置的基础访问权限。</p>
    </section>
  </main>
</template>
