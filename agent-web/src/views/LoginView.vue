<template>
  <div class="login-page">
    <div class="login-card">
      <h2>Agent Platform 登录</h2>
      <form @submit.prevent="handleLogin">
        <div class="form-group">
          <label>用户名</label>
          <input v-model="username" type="text" autocomplete="username" :disabled="loading" />
        </div>
        <div class="form-group">
          <label>密码</label>
          <input v-model="password" type="password" autocomplete="current-password" :disabled="loading" />
        </div>
        <div v-if="error" class="error-msg">{{ error }}</div>
        <button type="submit" class="btn-primary" :disabled="loading || !username || !password">
          {{ loading ? '登录中...' : '登录' }}
        </button>
      </form>
      <div class="sample-info">
        <p>示例账号：</p>
        <p>admin / visitor</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { directLogin, useAuth } from '../stores/authStore.js'

const router = useRouter()
const route = useRoute()
const auth = useAuth()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

// 已登录跳转首页
if (auth.authenticated) {
  router.replace('/')
}

// 检查 expired 参数
if (route.query.expired === '1') {
  error.value = '登录状态已失效，请重新登录'
}

async function handleLogin() {
  if (!username.value || !password.value) return
  loading.value = true
  error.value = ''
  try {
    await directLogin(username.value, password.value)
    password.value = ''
    const redirect = route.query.redirect || '/'
    router.push(redirect)
  } catch (e) {
    error.value = e.message || '认证失败'
    password.value = ''
  } finally {
    loading.value = false
  }
}
</script>
