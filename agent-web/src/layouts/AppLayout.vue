<template>
  <div v-if="auth.authenticated" class="app-layout">
    <header class="app-header">
      <div class="header-brand">Agent Platform</div>
      <div class="header-user">
        <span class="username">{{ auth.currentUser?.username }}</span>
        <span v-for="role in auth.currentUser?.roles" :key="role" class="role-tag">{{ role }}</span>
        <button class="btn-logout" @click="handleLogout" :disabled="logoutLoading">退出</button>
      </div>
    </header>
    <aside class="app-sidebar">
      <nav>
        <router-link to="/" class="nav-item">首页</router-link>
        <router-link to="/agents" class="nav-item">单Agent调用</router-link>
        <router-link to="/supervisors" class="nav-item">Supervisor调用</router-link>
        <router-link to="/permission-demo" class="nav-item">权限差异演示</router-link>
        <router-link v-if="hasPermission('security:user:read')" to="/admin/users" class="nav-item">用户管理</router-link>
        <router-link v-if="hasPermission('security:role:read')" to="/admin/roles" class="nav-item">角色管理</router-link>
      </nav>
    </aside>
    <main class="app-main">
      <router-view />
    </main>
  </div>
  <router-view v-else />
</template>

<script setup>
import { ref } from 'vue'
import { useAuth, logout as doLogout, hasPermission } from '../stores/authStore.js'

const auth = useAuth()
const logoutLoading = ref(false)

async function handleLogout() {
  logoutLoading.value = true
  try {
    await doLogout()
  } finally {
    logoutLoading.value = false
  }
}
</script>
